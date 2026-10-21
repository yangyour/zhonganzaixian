package cn.mop.adapters.wecom;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Fixed official HTTPS origin, bounded responses, redacted failures and no ambiguous-send retries. */
public final class WeComClient {
    public static final String ORIGIN = "https://qyapi.weixin.qq.com";
    private static final int MAX_RESPONSE = 1_048_576;
    public record Reply(int status, byte[] body) { @Override public String toString() { return "WeComReply[REDACTED]"; } }
    @FunctionalInterface public interface Transport { Reply exchange(String method, String path, Map<String,String> query, byte[] body) throws Exception; }
    public static final class Failure extends RuntimeException {
        public final String category; public final Integer providerCode; public final boolean outcomeUnknown;
        public Failure(String category, Integer providerCode, boolean outcomeUnknown) {
            super(category); this.category = category; this.providerCode = providerCode; this.outcomeUnknown = outcomeUnknown;
        }
    }
    public record Application(int agentId, String name, boolean closed) {}
    public record Member(String userId, Integer status) {}
    public record Acceptance(String messageId, String responseCode) {}
    private final WeComCredentials credentials; private final ObjectMapper mapper;
    private final Transport transport; private final Clock clock;
    private String accessToken; private Instant refreshAt = Instant.MIN;

    public WeComClient(WeComCredentials credentials, ObjectMapper mapper, URI loopbackProxy) {
        this(credentials, mapper, network(loopbackProxy), Clock.systemUTC());
    }
    public WeComClient(WeComCredentials credentials, ObjectMapper mapper, Transport transport, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials); this.mapper = Objects.requireNonNull(mapper);
        this.transport = Objects.requireNonNull(transport); this.clock = Objects.requireNonNull(clock);
    }
    public String corpId() { return credentials.corpId(); }
    public int agentId() { return credentials.agentId(); }
    public Application application() {
        var n = authorized("GET", "/cgi-bin/agent/get", Map.of("agentid", Integer.toString(agentId())), null, false);
        if (!n.path("agentid").isIntegralNumber() || n.path("agentid").intValue() != agentId()
            || !n.path("name").isTextual() || n.path("name").textValue().length() > 256
            || !n.path("close").isIntegralNumber() || !Set.of(0,1).contains(n.path("close").intValue())) throw malformed(false);
        return new Application(agentId(), n.get("name").textValue(), n.get("close").intValue() == 1);
    }
    public Member member(String userId) {
        userId(userId);
        var n = authorized("GET", "/cgi-bin/user/get", Map.of("userid", userId), null, false);
        if (!n.path("userid").isTextual() || !userId.equalsIgnoreCase(n.path("userid").textValue())) throw malformed(false);
        Integer status = n.path("status").isIntegralNumber() ? n.get("status").intValue() : null;
        return new Member(n.get("userid").textValue(), status);
    }
    public String identity(String code) {
        if (code == null || code.isBlank() || code.getBytes(StandardCharsets.UTF_8).length > 512 || code.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid authorization code");
        var n = authorized("GET", "/cgi-bin/auth/getuserinfo", Map.of("code", code), null, false);
        if (!n.path("userid").isTextual()) throw new Failure("WECOM_ENTERPRISE_MEMBER_REQUIRED", null, false);
        try { userId(n.get("userid").textValue()); } catch (IllegalArgumentException e) { throw malformed(false); }
        if (n.has("corpid") && !corpId().equals(n.path("corpid").asText())) throw malformed(false);
        return n.get("userid").textValue();
    }
    public Acceptance sendText(String userId, String text) {
        userId(userId);
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > 2048 || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Message must contain 1-2048 UTF-8 bytes");
        var body = mapper.createObjectNode().put("touser", userId).put("msgtype", "text").put("agentid", agentId())
            .put("safe", 0).put("enable_duplicate_check", 1).put("duplicate_check_interval", 600);
        body.putObject("text").put("content", text);
        var n = authorized("POST", "/cgi-bin/message/send", Map.of(), body, true);
        if (nonempty(n, "invaliduser") || nonempty(n, "invalidparty") || nonempty(n, "invalidtag") || nonempty(n, "unlicenseduser"))
            throw new Failure("WECOM_RECIPIENT_REJECTED", 81013, false);
        if (!n.path("msgid").isTextual() || n.path("msgid").textValue().isBlank() || n.path("msgid").textValue().length() > 512) throw malformed(true);
        // errcode=0 is API acceptance. It is never a delivery/read receipt.
        return new Acceptance(n.get("msgid").textValue(), n.path("response_code").isTextual() ? n.get("response_code").textValue() : null);
    }
    public static void userId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.@-]{1,64}") || value.equalsIgnoreCase("@all")) throw new IllegalArgumentException("A single explicit WeCom member ID is required");
    }
    private static boolean nonempty(JsonNode n, String key) { return n.has(key) && !n.get(key).isNull() && !n.get(key).asText().isBlank(); }
    private JsonNode authorized(String method, String path, Map<String,String> query, JsonNode body, boolean send) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = token(); var q = new LinkedHashMap<>(query); q.put("access_token", token);
            try { return request(method, path, q, body, send); }
            catch (Failure failure) {
                if (attempt == 0 && Set.of(40014,42001).contains(failure.providerCode == null ? -1 : failure.providerCode)) {
                    synchronized (this) { if (Objects.equals(accessToken, token)) { accessToken = null; refreshAt = Instant.MIN; } }
                } else throw failure;
            }
        }
        throw new Failure("WECOM_TOKEN_REJECTED", null, false);
    }
    private synchronized String token() {
        if (accessToken != null && clock.instant().isBefore(refreshAt)) return accessToken;
        Instant started = clock.instant();
        var n = request("GET", "/cgi-bin/gettoken", Map.of("corpid", corpId(), "corpsecret", credentials.secret()), null, false);
        if (!n.path("access_token").isTextual() || n.path("access_token").textValue().isBlank() || n.path("access_token").textValue().length() > 8192
            || !n.path("expires_in").isIntegralNumber() || !n.path("expires_in").canConvertToLong()) throw malformed(false);
        long ttl = n.get("expires_in").longValue(); if (ttl < 1 || ttl > 86400) throw malformed(false);
        accessToken = n.get("access_token").textValue(); refreshAt = started.plusSeconds(ttl - Math.min(60, ttl / 10));
        return accessToken;
    }
    private JsonNode request(String method, String path, Map<String,String> query, JsonNode body, boolean send) {
        try {
            Reply reply = transport.exchange(method, path, query, body == null ? null : mapper.writeValueAsBytes(body));
            if (reply.status() != 200) throw new Failure("WECOM_HTTP_UNAVAILABLE", null, send);
            if (reply.body() == null || reply.body().length > MAX_RESPONSE) throw malformed(send);
            var n = mapper.readTree(reply.body());
            if (n == null || !n.isObject() || !n.path("errcode").isIntegralNumber() || !n.path("errcode").canConvertToInt()) throw malformed(send);
            int code = n.get("errcode").intValue();
            if (code != 0) throw new Failure(category(code), code, false);
            return n;
        } catch (Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("WECOM_REQUEST_INTERRUPTED", null, send); }
        catch (Exception e) { throw new Failure("WECOM_NETWORK_OR_RESPONSE_UNAVAILABLE", null, send); }
    }
    private static String category(int code) {
        return switch(code) {
            case 60020 -> "WECOM_TRUSTED_IP_REQUIRED";
            case 40001,40013,40014,42001 -> "WECOM_CREDENTIAL_REJECTED";
            case 40029,42003 -> "WECOM_AUTHORIZATION_CODE_REJECTED";
            case 48002,48009,60011 -> "WECOM_PERMISSION_DENIED";
            case 60111,81013 -> "WECOM_MEMBER_UNAVAILABLE";
            case 45009 -> "WECOM_RATE_LIMITED";
            default -> "WECOM_PROVIDER_REJECTED";
        };
    }
    private static Failure malformed(boolean send) { return new Failure("WECOM_RESPONSE_INVALID", null, send); }
    private static Transport network(URI proxy) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null) {
            if (!"http".equals(proxy.getScheme()) || !"127.0.0.1".equals(proxy.getHost()) || proxy.getPort() < 1 || proxy.getPort() > 65535
                || proxy.getRawUserInfo() != null || proxy.getRawQuery() != null || proxy.getRawFragment() != null || !Set.of("", "/").contains(proxy.getPath()))
                throw new IllegalArgumentException("Only an explicit loopback HTTP CONNECT proxy is supported");
            builder.proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxy.getPort())));
        } else builder.proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
        var client = builder.build();
        return (method, path, query, body) -> {
            if (!Set.of("/cgi-bin/gettoken", "/cgi-bin/agent/get", "/cgi-bin/user/get", "/cgi-bin/message/send", "/cgi-bin/auth/getuserinfo").contains(path)) throw new IllegalArgumentException("Unsupported WeCom endpoint");
            String q = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
            var request = HttpRequest.newBuilder(URI.create(ORIGIN + path + "?" + q)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
            if (body == null) request.GET(); else request.header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            var reply = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var in = reply.body()) { return new Reply(reply.statusCode(), in.readNBytes(MAX_RESPONSE + 1)); }
        };
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @Override public String toString() { return "WeComClient[credentials=REDACTED]"; }
}
