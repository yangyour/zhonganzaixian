package cn.mop.adapters.notification;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.ChannelSubmissionResult;
import cn.mop.domain.notification.ChannelSubmissionResult.Outcome;
import cn.mop.domain.notification.ChannelSubmissionResult.Reason;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit one-attempt application message adapter. No retries, workers, account lookup or implicit recipients. */
public final class WeComMessageClient {
    public static final int MAX_RESPONSE_BYTES = 65536;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Token reference resolves a current token for this exact tenant/application; never a CorpSecret. */
    public record Settings(UUID tenantId, Long agentId, String tokenReference, Boolean enabled,
                           Duration connectTimeout, Duration requestTimeout) {
        public Settings {
            Objects.requireNonNull(tenantId);
            if (agentId != null && agentId <= 0) throw invalid();
            if (tokenReference != null && !tokenReference.matches("[A-Za-z0-9_./:-]{1,200}")) throw invalid();
            timeout(connectTimeout); timeout(requestTimeout);
        }
        public boolean configured() { return Boolean.TRUE.equals(enabled) && agentId != null && tokenReference != null
                && connectTimeout != null && requestTimeout != null; }
        @Override public String toString() { return "WeComSettings[private configuration redacted]"; }
    }
    /** Exactly one verified application user. Caller supplies authorized binding and rendered content. */
    public record Command(UUID tenantId, UUID deliveryId, UUID requestId, String userId, String content) {
        public Command {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(deliveryId); Objects.requireNonNull(requestId);
            // No @all, departments, tags, delimiter expansion, control characters or URL-shaped identifiers.
            if (userId == null || !userId.matches("[A-Za-z0-9_.@-]{1,64}") || userId.startsWith("@")) throw invalid();
            if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > 2048
                    || content.codePoints().anyMatch(c -> c == 0 || c >= 0xD800 && c <= 0xDFFF)) throw invalid();
        }
        @Override public String toString() { return "WeComCommand[recipient and content redacted]"; }
    }
    public record Response(int status, byte[] body) {
        public Response { body = body == null ? new byte[0] : body.clone(); }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "WeComResponse[status=" + status + ", body redacted]"; }
    }
    @FunctionalInterface public interface TokenResolver { String resolve(UUID tenantId, long agentId, String reference); }
    /** Exchange must issue at most one POST, use validated TLS, reject redirects and bound response size/time. */
    @FunctionalInterface public interface Exchange { Response post(String accessToken, byte[] json) throws Exception; }
    private final Settings settings;
    private final TokenResolver tokens;
    private final Exchange exchange;
    private final Clock clock;

    public WeComMessageClient(Settings settings, TokenResolver tokens, Exchange exchange, Clock clock) {
        this.settings = Objects.requireNonNull(settings); this.tokens = Objects.requireNonNull(tokens);
        this.exchange = Objects.requireNonNull(exchange); this.clock = Objects.requireNonNull(clock);
    }
    public static WeComMessageClient configured(Settings settings, TokenResolver tokens) {
        Objects.requireNonNull(settings);
        if (!settings.configured()) throw new IllegalArgumentException("WeCom configuration is incomplete");
        return new WeComMessageClient(settings, tokens, new WeComHttpsTransport(settings), Clock.systemUTC());
    }
    public ChannelSubmissionResult submit(Command command) {
        Objects.requireNonNull(command);
        if (!settings.tenantId().equals(command.tenantId())) throw new DomainException(404, "NOT_FOUND", "Channel not found");
        if (!settings.configured()) return result(command, Outcome.NOT_SUBMITTED, Reason.CONFIGURATION_UNAVAILABLE);
        String token;
        try {
            token = tokens.resolve(settings.tenantId(), settings.agentId(), settings.tokenReference());
            if (token == null || !token.matches("[A-Za-z0-9_-]{1,4096}"))
                return result(command, Outcome.NOT_SUBMITTED, Reason.TOKEN_UNAVAILABLE);
        } catch (RuntimeException failure) { return result(command, Outcome.NOT_SUBMITTED, Reason.TOKEN_UNAVAILABLE); }
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(Map.of("touser", command.userId(), "msgtype", "text",
                    "agentid", settings.agentId(), "text", Map.of("content", command.content())));
        } catch (Exception impossible) { throw new IllegalStateException("Message encoding failed"); }
        Response response;
        try { response = exchange.post(token, payload); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return result(command, Outcome.UNKNOWN, Reason.TRANSPORT_UNCERTAIN);
        } catch (Exception failure) { return result(command, Outcome.UNKNOWN, Reason.TRANSPORT_UNCERTAIN); }
        return interpret(command, response);
    }
    private ChannelSubmissionResult interpret(Command command, Response response) {
        if (response == null || response.status() != 200 || response.body.length > MAX_RESPONSE_BYTES)
            return result(command, Outcome.UNKNOWN, Reason.RESPONSE_UNCERTAIN);
        try {
            JsonNode root = JSON.readTree(response.body);
            if (root == null || !root.isObject() || !root.path("errcode").isIntegralNumber()
                    || !root.path("errcode").canConvertToInt()) return uncertain(command);
            if (root.get("errcode").intValue() != 0) return result(command, Outcome.REJECTED, Reason.PROVIDER_REJECTED);
            for (String field : new String[]{"invaliduser", "unlicenseduser", "invalidparty", "invalidtag"}) {
                JsonNode value = root.get(field);
                if (value == null) continue;
                if (!value.isTextual()) return uncertain(command);
                if (!value.textValue().isEmpty()) {
                    // A single-recipient request can only reject this recipient; mismatches are anomalous.
                    if ((field.equals("invaliduser") || field.equals("unlicenseduser"))
                            && value.textValue().equalsIgnoreCase(command.userId()))
                        return result(command, Outcome.REJECTED, Reason.RECIPIENT_REJECTED);
                    return uncertain(command);
                }
            }
            // No raw errmsg/msgid/recipient data escapes into durable domain results or exception chains.
            return result(command, Outcome.ACCEPTED, Reason.PROVIDER_ACCEPTED);
        } catch (Exception malformed) { return uncertain(command); }
    }
    private ChannelSubmissionResult uncertain(Command c) { return result(c, Outcome.UNKNOWN, Reason.RESPONSE_UNCERTAIN); }
    private ChannelSubmissionResult result(Command c, Outcome outcome, Reason reason) {
        return new ChannelSubmissionResult(c.tenantId(), c.deliveryId(), c.requestId(), outcome, reason, clock.instant());
    }
    private static void timeout(Duration d) { if (d != null && (d.compareTo(Duration.ofMillis(1)) < 0
            || d.compareTo(Duration.ofMinutes(2)) > 0)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid WeCom configuration or message"); }
    @Override public String toString() { return "WeComMessageClient[private configuration redacted]"; }
}
