package cn.mop.adapters.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Read-only live probe using the same client as the API. Never prints tokens, secrets or raw responses. */
public final class WeComProbeMain {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper(); var result = new LinkedHashMap<String,Object>();
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            var credentials = WeComCredentials.load(Path.of(System.getenv("MOP_WECOM_CONFIG_FILE")), mapper);
            String proxy = System.getenv("MOP_WECOM_PROXY_URL");
            var client = new WeComClient(credentials, mapper, proxy == null || proxy.isBlank() ? null : URI.create(proxy));
            var app = client.application(); result.put("application_verified", true); result.put("agent_id", app.agentId()); result.put("application_closed", app.closed());
            String member = System.getenv("MOP_WECOM_PROBE_USER_ID");
            if (member != null && !member.isBlank()) { var verified = client.member(member); result.put("member_verified", true); result.put("member_status", verified.status()); }
            result.put("message_sent", false); result.put("status", "SUCCEEDED");
        } catch (WeComClient.Failure error) { result.put("status", "FAILED"); result.put("category", error.category); result.put("provider_code", error.providerCode); }
        catch (Exception error) { result.put("status", "FAILED"); result.put("category", "CONFIGURATION_UNAVAILABLE"); }
        System.out.println(mapper.writeValueAsString(result));
        if (!"SUCCEEDED".equals(result.get("status"))) System.exit(1);
    }
}
