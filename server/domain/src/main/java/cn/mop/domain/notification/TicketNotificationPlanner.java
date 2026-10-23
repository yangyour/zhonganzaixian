package cn.mop.domain.notification;

import cn.mop.domain.DomainException;
import java.util.*;

/**
 * Pure in-app recipient planning for committed ticket workflow outbox facts.
 * It never selects external channels: the channel x severity matrix (Q14) and provider credentials (Q18)
 * remain unconfirmed, so every plan is marked NOT_CONFIGURED for external dispatch. Recipients whose
 * selection depends on unconfirmed on-call / escalation / service-manager rules (Q09, Q15) are not guessed;
 * the plan records an explicit pending reason instead.
 */
public final class TicketNotificationPlanner {
    private TicketNotificationPlanner() {}
    public static final int MAX_RECIPIENTS = 100;
    public static final List<String> EXTERNAL_BLOCKERS = List.of("Q14_CHANNEL_MATRIX", "Q18_PROVIDER_CREDENTIALS");
    public static final String PENDING_QUEUE = "Q15_ONCALL_QUEUE_RECIPIENTS";
    public static final String PENDING_ESCALATION = "Q15_ESCALATION_CHAIN";
    public static final String PENDING_MANAGER = "Q09_SERVICE_MANAGER_ROLE";
    public static final String PENDING_UNMAPPED = "UNMAPPED_EVENT_TYPE";
    public static final String PENDING_INACTIVE = "RECIPIENT_INACTIVE";
    public enum Audience { CUSTOMER, INTERNAL }
    public enum RecipientStatus { COMPLETE, PARTIAL, PENDING_RULE }
    public record Recipient(String userId, Audience audience, String title, String body) {
        public Recipient {
            if (userId == null || userId.isBlank() || audience == null || blank(title) || blank(body)
                    || title.codePointCount(0, title.length()) > 160 || body.codePointCount(0, body.length()) > 600) throw invalid();
        }
    }
    /** Snapshot facts copied from the committed workflow event and the immutable registration receipt. */
    public record Input(String eventType, String receiptNo, String ticketTitle, String reporterId, String assigneeId,
                        List<String> collaborators, String previousAssigneeId, String priority) {
        public Input {
            if (eventType == null || !eventType.matches("ticket\\.[a-z0-9_]{1,64}") || blank(receiptNo) || reporterId == null) throw invalid();
            collaborators = collaborators == null ? List.of() : List.copyOf(collaborators);
            if (priority != null && !Set.of("P1", "P2", "P3").contains(priority)) throw invalid();
        }
    }
    public record Plan(String eventType, String phase, String priority, List<Recipient> recipients, List<String> pendingReasons) {
        public Plan {
            recipients = List.copyOf(recipients); pendingReasons = List.copyOf(new LinkedHashSet<>(pendingReasons));
            if (recipients.size() > MAX_RECIPIENTS || recipients.isEmpty() && pendingReasons.isEmpty()) throw invalid();
            if (recipients.stream().map(Recipient::userId).distinct().count() != recipients.size()) throw invalid();
        }
        public RecipientStatus status() {
            if (recipients.isEmpty()) return RecipientStatus.PENDING_RULE;
            return pendingReasons.isEmpty() ? RecipientStatus.COMPLETE : RecipientStatus.PARTIAL;
        }
        /** Drops recipients that are no longer active members; never substitutes another person. */
        public Plan retainActive(Set<String> activeUserIds) {
            var kept = recipients.stream().filter(r -> activeUserIds.contains(r.userId())).toList();
            var reasons = new ArrayList<>(pendingReasons);
            if (kept.size() != recipients.size()) reasons.add(PENDING_INACTIVE);
            return new Plan(eventType, phase, priority, kept, reasons);
        }
    }

    public static String phase(String eventType) {
        if (eventType == null || !eventType.matches("ticket\\.[a-z0-9_]{1,64}")) throw invalid();
        return eventType.substring("ticket.".length()).toUpperCase(Locale.ROOT);
    }

    public static Plan plan(Input in) {
        var recipients = new LinkedHashMap<String, Recipient>();
        var pending = new ArrayList<String>();
        String ref = "[" + in.receiptNo() + "] " + shortTitle(in.ticketTitle());
        switch (in.eventType()) {
            case "ticket.opened" -> {
                customer(recipients, in, ref + " 已建立正式工单", "您的报障已建立正式工单，平台将按服务约定安排工程师处理。");
                if (in.assigneeId() == null) pending.add(PENDING_QUEUE);
            }
            case "ticket.accepted" -> customer(recipients, in, ref + " 工程师已接单", "工程师已接单，处理进展会在工单中持续更新。");
            case "ticket.started" -> customer(recipients, in, ref + " 正在处理", "工程师已开始处理您的工单。");
            case "ticket.reassigned" -> {
                internal(recipients, in.assigneeId(), ref + " 已转派给您", "该工单已转派给您处理，原SLA截止时间不变，请尽快核对处理记录。");
                internal(recipients, in.previousAssigneeId(), ref + " 已从您转出", "该工单已转派给其他工程师；如保留为协办，仍可查看处理记录。");
            }
            case "ticket.resolved" -> customer(recipients, in, ref + " 已解决，待您确认", "工程师已提交解决说明，请确认问题是否已解决；如未解决请填写说明。");
            case "ticket.confirmation_reminder" -> customer(recipients, in, ref + " 请确认解决结果", "工单仍待您确认，超过确认期限后将按服务约定自动关闭。");
            case "ticket.closed" -> internal(recipients, in.assigneeId(), ref + " 客户已确认关闭", "客户已确认工单解决结果，工单已关闭。");
            case "ticket.auto_closed" -> {
                customer(recipients, in, ref + " 已超时自动关闭", "确认期限已到，工单已自动关闭；关闭后在规定时间内仍可重开。");
                internal(recipients, in.assigneeId(), ref + " 已超时自动关闭", "客户未在确认期限内答复，工单已自动关闭。");
            }
            case "ticket.reopened" -> {
                internal(recipients, in.assigneeId(), ref + " 客户已重开", "客户反馈问题仍未解决并重开了工单，请查看客户说明后继续处理。");
                for (String collaborator : in.collaborators()) internal(recipients, collaborator, ref + " 客户已重开", "您协办的工单已被客户重开。");
            }
            case "ticket.monitoring_recurred" -> {
                if (in.assigneeId() == null) {
                    pending.add(PENDING_QUEUE);
                } else {
                    internal(recipients, in.assigneeId(), ref + " 监控故障复发",
                        "关联监控事件再次报告故障，请核对当前状态并继续处理；原SLA截止时间保持不变。");
                }
                for (String collaborator : in.collaborators()) {
                    internal(recipients, collaborator, ref + " 监控故障复发",
                        "您协办工单的关联监控事件再次报告故障，请核对恢复及复核记录。");
                }
            }
            case "ticket.monitoring_recovered" -> {
                if (in.assigneeId() == null) {
                    pending.add(PENDING_QUEUE);
                } else {
                    internal(recipients, in.assigneeId(), ref + " 监控恢复待复核",
                        "已发布策略的恢复观察窗已通过，恢复SLA按确认t2停钟；仍须人工核对根因与工单状态。");
                }
                for (String collaborator : in.collaborators()) {
                    internal(recipients, collaborator, ref + " 监控恢复待复核",
                        "您协办的工单已收到经过观察窗核验的恢复记录，请核对证据并完成复核。");
                }
            }
            case "ticket.manager_review_required" -> pending.add(PENDING_MANAGER);
            case "ticket.cancelled" -> customer(recipients, in, ref + " 已撤销", "该工单已由平台管理员撤销。");
            case "ticket.sla_50", "ticket.sla_80", "ticket.sla_100" -> {
                String percent = in.eventType().substring("ticket.sla_".length());
                if (in.assigneeId() == null) pending.add(PENDING_QUEUE);
                else internal(recipients, in.assigneeId(), ref + " SLA进度已达" + percent + "%", "工单SLA计时已达" + percent + "%节点，请核对响应/恢复截止时间。");
                if (percent.equals("100")) pending.add(PENDING_ESCALATION);
            }
            default -> pending.add(PENDING_UNMAPPED);
        }
        return new Plan(in.eventType(), phase(in.eventType()), in.priority(), List.copyOf(recipients.values()), pending);
    }
    private static void customer(Map<String, Recipient> out, Input in, String title, String body) {
        out.putIfAbsent(in.reporterId(), new Recipient(in.reporterId(), Audience.CUSTOMER, title, body));
    }
    private static void internal(Map<String, Recipient> out, String user, String title, String body) {
        if (user != null && !user.isBlank()) out.putIfAbsent(user, new Recipient(user, Audience.INTERNAL, title, body));
    }
    private static String shortTitle(String title) {
        if (blank(title)) return "工单";
        String t = title.strip();
        return t.codePointCount(0, t.length()) <= 60 ? t : t.substring(0, t.offsetByCodePoints(0, 60)) + "…";
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "通知计划输入无效"); }
}
