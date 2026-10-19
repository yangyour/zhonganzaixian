package cn.mop.adapters.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import java.nio.file.*;
import java.util.*;

/** Trusted server configuration. No request may supply a host, secret or unrestricted recipient. */
public final class SmtpSettings {
    private final String host, username, password, sender, senderName, testRecipient;
    private final Set<String> allowedRecipients;
    public SmtpSettings(String host, String username, String password, String sender,
                        String senderName, String testRecipient, Set<String> allowedRecipients) {
        if (host == null || !host.matches("(?=.{1,253}$)[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
            || password == null || password.isBlank() || password.length() > 1024
            || password.chars().anyMatch(c -> c < 32 || c == 127)
            || senderName == null || senderName.isBlank() || senderName.length() > 80
            || senderName.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        this.host = host.toLowerCase(Locale.ROOT); this.username = address(username);
        this.sender = address(sender); this.password = password; this.senderName = senderName;
        if (!this.sender.equals(this.username) || allowedRecipients == null || allowedRecipients.isEmpty() || allowedRecipients.size() > 100) throw invalid();
        var recipients = new TreeSet<String>(); allowedRecipients.forEach(v -> recipients.add(address(v)));
        this.allowedRecipients = Collections.unmodifiableSet(recipients);
        this.testRecipient = address(testRecipient);
        if (!this.allowedRecipients.contains(this.testRecipient)) throw invalid();
    }
    public static SmtpSettings load(Path config, ObjectMapper mapper, String environmentPassword) {
        try {
            if (!Files.isRegularFile(config) || Files.size(config) > 16384) throw invalid();
            var n = mapper.readTree(Files.readAllBytes(config));
            var names = new HashSet<String>(); n.fieldNames().forEachRemaining(names::add);
            var required = Set.of("host", "port", "username", "sender", "sender_name", "test_recipient", "allowed_recipients");
            if (!n.isObject() || !names.containsAll(required) || !Set.of("host", "port", "username", "sender", "sender_name", "test_recipient", "allowed_recipients", "password_file").containsAll(names)
                || !n.path("port").isIntegralNumber() || !n.path("port").canConvertToInt() || n.path("port").intValue() != 465
                || !n.path("allowed_recipients").isArray()) throw invalid();
            String secret = environmentPassword;
            if (n.has("password_file")) {
                if (secret != null && !secret.isBlank() || !n.get("password_file").isTextual()) throw invalid();
                Path file = Path.of(n.get("password_file").textValue());
                if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > 4096) throw invalid();
                secret = Files.readString(file);
            }
            var recipients = new HashSet<String>();
            for (var v : n.get("allowed_recipients")) { if (!v.isTextual()) throw invalid(); recipients.add(v.textValue()); }
            for (String key : List.of("host", "username", "sender", "sender_name", "test_recipient")) if (!n.get(key).isTextual()) throw invalid();
            return new SmtpSettings(n.get("host").textValue(), n.get("username").textValue(), secret,
                n.get("sender").textValue(), n.get("sender_name").textValue(), n.get("test_recipient").textValue(), recipients);
        } catch (Exception failure) { throw invalid(); }
    }
    public static String address(String value) {
        try {
            if (value == null || value.length() > 254 || !value.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")) throw invalid();
            new InternetAddress(value, true).validate();
            return value.toLowerCase(Locale.ROOT);
        } catch (Exception failure) { throw invalid(); }
    }
    public boolean allows(String recipient) { return allowedRecipients.contains(address(recipient)); }
    public String host() { return host; } public String sender() { return sender; }
    public String senderName() { return senderName; } public String testRecipient() { return testRecipient; }
    public Set<String> allowedRecipients() { return allowedRecipients; }
    String username() { return username; } String password() { return password; }
    @Override public String toString() { return "SmtpSettings[credentials=REDACTED]"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Email private configuration is missing or invalid"); }
}
