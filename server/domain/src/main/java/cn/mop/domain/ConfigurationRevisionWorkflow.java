package cn.mop.domain;

import java.util.Set;

/** Internal review never verifies customer evidence or activates registry values. */
public final class ConfigurationRevisionWorkflow {
    private ConfigurationRevisionWorkflow() {}

    public static String nextStatus(String status, String creator, String actor, String action) {
        if (Set.of("SUBMIT", "WITHDRAW").contains(action) && !creator.equals(actor))
            throw new DomainException(403, "ACTION_FORBIDDEN", "仅修订创建者可以提交或撤回");
        if (Set.of("APPROVE", "REJECT").contains(action) && creator.equals(actor))
            throw new DomainException(403, "ACTION_FORBIDDEN", "审批人不能是修订创建者");
        return switch (action) {
            case "SUBMIT" -> { require(status, Set.of("DRAFT")); yield "IN_REVIEW"; }
            case "WITHDRAW" -> { require(status, Set.of("DRAFT", "IN_REVIEW")); yield "WITHDRAWN"; }
            case "APPROVE" -> { require(status, Set.of("IN_REVIEW")); yield "APPROVED"; }
            case "REJECT" -> { require(status, Set.of("IN_REVIEW")); yield "REJECTED"; }
            default -> throw new DomainException(400, "VALIDATION_ERROR", "未知配置修订动作");
        };
    }

    private static void require(String status, Set<String> permitted) {
        if (!permitted.contains(status))
            throw new DomainException(422, "STATE_NOT_ALLOWED", "当前修订状态不允许此动作");
    }
}
