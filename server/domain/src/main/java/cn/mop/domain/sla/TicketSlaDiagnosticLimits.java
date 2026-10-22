package cn.mop.domain.sla;

import cn.mop.domain.DomainException;

/** Existing HTTP diagnostic limits, not business quotas or permission grants. Never truncate a success response. */
public final class TicketSlaDiagnosticLimits {
    public static final int MAX_CLOCK_TASKS = 64;
    public static final int MAX_POLICY_REFERENCES = 20;
    public static final int CLOCK_TASK_LOOKAHEAD = MAX_CLOCK_TASKS + 1;
    private TicketSlaDiagnosticLimits() {}
    public static void requireWithinLimits(int tasks, int references) {
        if (tasks < 0 || references < 0)
            throw new DomainException(503, "SLA_INSPECTION_UNAVAILABLE", "SLA 核验明细数量无法确认");
        if (tasks > MAX_CLOCK_TASKS || references > MAX_POLICY_REFERENCES)
            throw new DomainException(413, "SLA_INSPECTION_LIMIT", "SLA 核验明细超过单次接口范围，未截断返回；请联系管理员核对历史规模");
    }
}
