package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Tenant service tier snapshot and append-only change history (M2-10 / M5-8 / M5-20). */
public interface ServiceTierRepository {
    ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    enum EffectiveMode { IMMEDIATE, NEXT_MONTH_FIRST_DAY }
    enum BillingCycle { MONTHLY, QUARTERLY, YEARLY }

    record Access(String tenantId, String actorId, long permissionVersion, String traceId) {
        public Access {
            if (tenantId == null || tenantId.isBlank() || actorId == null || actorId.isBlank()
                    || permissionVersion < 1 || traceId == null || traceId.isBlank())
                throw new DomainException(401, "SESSION_EXPIRED", "当前会话或成员上下文无效");
        }
    }

    record Snapshot(String tenantId, long version, Tier tier, BigDecimal availabilityTarget,
            Integer contractDeviceCount, BillingCycle billingCycle, Long monthlyFeeFen,
            EffectiveMode effectiveMode, Instant effectiveFrom, String updatedBy, Instant updatedAt,
            String customerApprovalStatus) {
        public Snapshot {
            if (tenantId == null || tenantId.isBlank() || version < 1 || tier == null
                    || effectiveMode == null || effectiveFrom == null || updatedBy == null || updatedBy.isBlank()
                    || updatedAt == null || !"UNVERIFIED".equals(customerApprovalStatus))
                throw invalid();
            TicketSlaSnapshot.time(effectiveFrom);
            TicketSlaSnapshot.time(updatedAt);
            validateMetrics(availabilityTarget, contractDeviceCount, monthlyFeeFen);
        }
    }

    record Change(String id, String tenantId, long version, Long previousVersion, Tier fromTier, Tier toTier,
            BigDecimal availabilityTarget, Integer contractDeviceCount, BillingCycle billingCycle, Long monthlyFeeFen,
            EffectiveMode effectiveMode, Instant effectiveFrom, String reason, String approvedBy, Instant changedAt,
            String customerApprovalStatus) {
        public Change {
            if (id == null || id.isBlank() || tenantId == null || tenantId.isBlank() || version < 1
                    || (version == 1 ? previousVersion != null || fromTier != null : previousVersion == null || previousVersion != version - 1 || fromTier == null)
                    || toTier == null || effectiveMode == null || effectiveFrom == null
                    || reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 1000
                    || approvedBy == null || approvedBy.isBlank() || changedAt == null
                    || !"UNVERIFIED".equals(customerApprovalStatus))
                throw invalid();
            TicketSlaSnapshot.time(effectiveFrom);
            TicketSlaSnapshot.time(changedAt);
            validateMetrics(availabilityTarget, contractDeviceCount, monthlyFeeFen);
        }
    }

    record Command(long expectedVersion, Tier toTier, EffectiveMode effectiveMode,
            Integer contractDeviceCount, BillingCycle billingCycle, Long monthlyFeeFen, String reason) {
        public Command {
            if (expectedVersion < 0 || expectedVersion > 9007199254740990L || toTier == null || effectiveMode == null
                    || reason == null || reason.trim().isEmpty() || reason.codePointCount(0, reason.length()) > 1000)
                throw invalid();
            if (reason.chars().anyMatch(ch -> ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' || ch == 0x7f))
                throw invalid();
            validateMetrics(null, contractDeviceCount, monthlyFeeFen);
        }
    }

    record ChangePage(Optional<Snapshot> current, List<Change> changes, boolean hasMore, Long nextBeforeVersion, Instant observedAt) {}

    static Instant resolveEffectiveFrom(Instant now, EffectiveMode mode) {
        TicketSlaSnapshot.time(now);
        if (now == null || mode == null) throw invalid();
        if (mode == EffectiveMode.IMMEDIATE) return now;
        ZonedDateTime local = now.atZone(DEFAULT_ZONE);
        LocalDate firstOfNextMonth = local.toLocalDate().withDayOfMonth(1).plusMonths(1);
        return firstOfNextMonth.atStartOfDay(DEFAULT_ZONE).toInstant();
    }

    static void validateMetrics(BigDecimal availabilityTarget, Integer contractDeviceCount, Long monthlyFeeFen) {
        if (availabilityTarget != null && (availabilityTarget.scale() > 2
                || availabilityTarget.compareTo(BigDecimal.ZERO) <= 0
                || availabilityTarget.compareTo(new BigDecimal("100.00")) > 0))
            throw invalid();
        if (contractDeviceCount != null && (contractDeviceCount < 0 || contractDeviceCount > 1_000_000))
            throw invalid();
        if (monthlyFeeFen != null && (monthlyFeeFen < 0 || monthlyFeeFen > 9007199254740990L))
            throw invalid();
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "服务档位参数无效或超出允许范围");
    }

    Optional<Snapshot> current(Access access);
    ChangePage history(Access access, int limit, Long beforeVersion);
    void authorizeWrite(Access access);
    Snapshot applyChange(Access access, Command command, BigDecimal configuredAvailabilityTarget);
    List<String> readinessProblems();
}
