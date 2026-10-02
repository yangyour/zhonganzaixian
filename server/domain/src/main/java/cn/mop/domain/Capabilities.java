package cn.mop.domain;

import java.util.List;

/** Technical test role capabilities; customer-wide sharing remains pending Q09. */
public final class Capabilities {
    private Capabilities() {}
    public static List<String> forRole(String role) {
        if(role==null)return List.of();
        return switch (role) {
            case "padmin" -> List.of("assets:read", "assets:write", "configurations:read", "configurations:write", "audit:read", "tickets:read", "ticket_comments:read", "ticket_comments:write", "ticket_notes:read_internal", "ticket_notes:write_internal");
            case "msp" -> List.of("assets:read", "assets:write", "tickets:read", "ticket_comments:read", "ticket_comments:write", "worklogs:read_own", "worklogs:write_own", "ticket_notes:read_internal", "ticket_notes:write_internal");
            case "tadmin" -> List.of("assets:read", "assets:write", "tickets:read", "ticket_comments:read", "ticket_comments:write", "tickets:report");
            case "viewer" -> List.of("assets:read", "tickets:read", "ticket_comments:read", "ticket_comments:write", "tickets:report");
            default -> List.of();
        };
    }
    public static boolean customer(String role) { return "viewer".equals(role) || "tadmin".equals(role); }
    public static boolean internal(String role) { return "msp".equals(role) || "padmin".equals(role); }
}
