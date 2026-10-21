package cn.mop.adapters.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

/** Real authentication probe only: no envelope, DATA, message body or mailbox read. */
public final class EmailProbeMain {
    public static void main(String[] args) {
        try {
            var settings = SmtpSettings.load(Path.of(System.getenv("MOP_EMAIL_CONFIG_FILE")), new ObjectMapper(), System.getenv("MOP_EMAIL_PASSWORD"));
            new SmtpMailClient(settings).check();
            System.out.println("{\"ready\":true,\"smtp_authenticated\":true,\"certificate_verification\":true,\"messages_sent\":0}");
        } catch (Exception failure) {
            String code = failure instanceof SmtpMailClient.Failure f ? f.category : "EMAIL_CONFIGURATION_INVALID";
            System.out.println("{\"ready\":false,\"reason\":\"" + code + "\",\"messages_sent\":0}"); System.exit(1);
        }
    }
}
