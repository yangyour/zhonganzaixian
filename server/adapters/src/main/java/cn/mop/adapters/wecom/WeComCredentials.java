package cn.mop.adapters.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Server-only material. Deliberately not a record: generated toString must never expose the secret. */
public final class WeComCredentials {
    private final String corpId, secret;
    private final int agentId;
    public WeComCredentials(String corpId, int agentId, String secret) {
        if (corpId == null || !corpId.matches("ww[a-zA-Z0-9]{8,64}") || agentId < 1
            || secret == null || !secret.matches("[A-Za-z0-9_-]{16,256}"))
            throw new IllegalArgumentException("Invalid server-side WeCom credentials");
        this.corpId = corpId; this.agentId = agentId; this.secret = secret;
    }
    public static WeComCredentials load(Path path, ObjectMapper mapper) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 16384) throw new IllegalArgumentException();
            var n = mapper.readTree(Files.readAllBytes(path));
            if (!n.path("corp_id").isTextual() || !n.path("agent_id").isIntegralNumber()
                || !n.path("agent_id").canConvertToInt() || !n.path("secret").isTextual()) throw new IllegalArgumentException();
            return new WeComCredentials(n.get("corp_id").textValue(), n.get("agent_id").intValue(), n.get("secret").textValue());
        } catch (Exception failure) { throw new IllegalArgumentException("WeCom private configuration is missing or invalid"); }
    }
    public String corpId() { return corpId; }
    public int agentId() { return agentId; }
    String secret() { return secret; }
    @Override public String toString() { return "WeComCredentials[credentials=REDACTED]"; }
}
