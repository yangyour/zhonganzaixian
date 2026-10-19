package cn.mop.adapters.email;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Angus/Jakarta Mail with verified implicit TLS, one recipient and one external attempt. */
public final class SmtpMailClient {
    public static final class Failure extends RuntimeException {
        public final String category; public final boolean outcomeUnknown; public final Integer smtpCode;
        public Failure(String category, boolean unknown, Integer code) {
            super(category); this.category = category; outcomeUnknown = unknown; smtpCode = code;
        }
    }
    public record Accepted(String messageId, int smtpCode) {}
    private final SmtpSettings settings;
    private final java.util.function.Supplier<Session> sessions;
    public SmtpMailClient(SmtpSettings settings) { this.settings = settings; this.sessions = null; }
    SmtpMailClient(SmtpSettings settings, java.util.function.Supplier<Session> sessions) { this.settings=settings; this.sessions=sessions; }
    public SmtpSettings settings() { return settings; }
    Properties properties() {
        var p = new Properties();
        p.setProperty("mail.transport.protocol", "smtps");
        for (var item : Map.ofEntries(Map.entry("auth", "true"), Map.entry("auth.mechanisms", "LOGIN"),
            Map.entry("ssl.enable", "true"), Map.entry("ssl.checkserveridentity", "true"),
            Map.entry("ssl.protocols", "TLSv1.3 TLSv1.2"), Map.entry("connectiontimeout", "10000"),
            Map.entry("timeout", "10000"), Map.entry("writetimeout", "10000"),
            Map.entry("quitwait", "false"), Map.entry("sendpartial", "false"),
            Map.entry("localhost", "mop-notifications.invalid")).entrySet()) p.setProperty("mail.smtps." + item.getKey(), item.getValue());
        p.setProperty("mail.debug", "false"); p.setProperty("mail.debug.auth", "false");
        return p;
    }
    private Session session() { var s = sessions==null?Session.getInstance(properties()):sessions.get(); s.setDebug(false); return s; }
    public void check() {
        Transport transport = null;
        try { transport = session().getTransport("smtps"); transport.connect(settings.host(), 465, settings.username(), settings.password()); }
        catch (MessagingException failure) { throw connectionFailure(failure); }
        finally { close(transport); }
    }
    public Accepted send(String deliveryId, String recipient, String subject, String text) {
        UUID.fromString(deliveryId);
        if (!settings.allows(recipient) || subject == null || subject.isBlank() || subject.length() > 200
            || subject.codePoints().anyMatch(c -> c < 32 || c == 127) || text == null || text.isBlank()
            || text.getBytes(StandardCharsets.UTF_8).length > 32768) throw new Failure("EMAIL_INPUT_REJECTED", false, null);
        var session = session(); Transport transport = null; boolean submitting = false;
        try {
            var message = message(session, deliveryId, recipient, subject, text);
            transport = session.getTransport("smtps");
            transport.connect(settings.host(), 465, settings.username(), settings.password());
            submitting = true;
            transport.sendMessage(message, message.getAllRecipients());
            int code = transport instanceof SMTPTransport smtp ? smtp.getLastReturnCode() : 250;
            return new Accepted(message.getMessageID(), code);
        } catch (MessagingException failure) {
            if (!submitting) throw connectionFailure(failure);
            if (failure instanceof SMTPSendFailedException rejected && rejected.getReturnCode() >= 400 && rejected.getReturnCode() <= 599)
                throw new Failure("EMAIL_PROVIDER_REJECTED", false, rejected.getReturnCode());
            throw new Failure("EMAIL_SUBMISSION_OUTCOME_UNKNOWN", true, null);
        } catch (java.io.UnsupportedEncodingException failure) { throw new Failure("EMAIL_INPUT_REJECTED", false, null); }
        finally { close(transport); }
    }
    MimeMessage message(Session session, String id, String recipient, String subject, String text) throws MessagingException, java.io.UnsupportedEncodingException {
        String messageId = "<mop." + id + "@" + settings.sender().substring(settings.sender().indexOf('@') + 1) + ">";
        var message = new MimeMessage(session) { @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", messageId); } };
        message.setFrom(new InternetAddress(settings.sender(), settings.senderName(), "UTF-8"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(SmtpSettings.address(recipient), true));
        message.setSubject(subject, "UTF-8"); message.setText(text, "UTF-8");
        message.setSentDate(new Date()); message.setHeader("Auto-Submitted", "auto-generated");
        message.saveChanges(); return message;
    }
    private static Failure connectionFailure(Exception failure) {
        if (failure instanceof AuthenticationFailedException) return new Failure("EMAIL_AUTHENTICATION_REJECTED", false, null);
        for (Throwable item = failure; item != null; item = item.getCause()) if (item instanceof SSLException) return new Failure("EMAIL_TLS_VERIFICATION_FAILED", false, null);
        return new Failure("EMAIL_CONNECTION_UNAVAILABLE", false, null);
    }
    private static void close(Transport transport) { if (transport != null) try { transport.close(); } catch (MessagingException ignored) { /* Acceptance is retained even if QUIT fails. */ } }
}
