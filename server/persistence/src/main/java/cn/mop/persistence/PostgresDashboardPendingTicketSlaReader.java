package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardPendingTicketSlaReader;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Access;
import cn.mop.domain.dashboard.DashboardTicketSla;
import cn.mop.domain.sla.TicketSlaClockEvaluation;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketLifecycleRepository;
import cn.mop.domain.ticket.WorkflowLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import javax.sql.DataSource;

/** Bounded dashboard evaluation using the existing SLA engines and the same transaction as V1 facts. */
public final class PostgresDashboardPendingTicketSlaReader implements DashboardPendingTicketSlaReader {
    /** Owns the read-only authorized snapshot, including the exact current workflow versions. */
    private final PostgresDashboardPendingTicketsReader pending;

    /** Uses the actual lifecycle feature gate; disabled consumers stay unknown. */
    private final PostgresTicketLifecycleRepository lifecycle;

    /** Existing V33 frozen JSON naming and timestamp rules. */
    private final ObjectMapper mapper;

    public PostgresDashboardPendingTicketSlaReader(DataSource source, PostgresPlatformRepository platform,
                                                   PostgresTicketLifecycleRepository lifecycle, ObjectMapper mapper) {
        pending = new PostgresDashboardPendingTicketsReader(source, platform);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.mapper = PostgresTicketWorkflowRepository.snapshotMapper(mapper);
    }

    @Override
    public Snapshot read(Access access) {
        return pending.readSnapshot(access, (connection, base) -> {
            Tier tier = currentTier(connection, access.tenantId());
            var assessments = new HashMap<String, DashboardTicketSla.Assessment>();
            long knownOverdue = 0;
            long unknown = 0;
            try (var statement = PostgresDashboardPendingTicketsReader.query(connection, access,
                    "CURRENT_TENANT_INTERNAL".equals(base.scope()),
                    "SELECT id,model,state,version,workflow_snapshot FROM visible WHERE model<>'UNCONFIGURED' "
                            + "AND state NOT IN ('CLOSED','CANCELLED') ORDER BY created_at DESC,id DESC LIMIT " + EVALUATION_LIMIT)) {
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String ticket = rows.getString("id");
                        long version = rows.getLong("version");
                        var assessment = assess(connection, access, ticket, rows.getString("model"), rows.getString("state"),
                                version, rows.getString("workflow_snapshot"), base.observedAt(), tier);
                        if (assessments.put(ticket, assessment) != null) {
                            throw unavailable();
                        }
                        if (Boolean.TRUE.equals(assessment.overdue())) {
                            knownOverdue++;
                        } else if (assessment.overdue() == null) {
                            unknown++;
                        }
                    }
                }
            }
            if (assessments.size() != Math.min(EVALUATION_LIMIT, base.knownPending())) {
                throw unavailable();
            }
            var items = base.items().stream().map(item -> new Item(item, assessments.get(item.ticketId().toString()))).toList();
            return new Snapshot(base, assessments.size(), knownOverdue, unknown, items);
        });
    }

    /** Shared exact-version evaluation keeps additive projections on the existing frozen SLA engines. */
    DashboardTicketSla.Assessment assess(Connection connection, Access access, String ticket, String model,
                                         String state, long version, String workflowJson, Instant observed,
                                         Tier tier) throws SQLException {
        var recovery = PostgresTicketWorkflowRepository.monitoringRecovery(connection, access.tenantId(), ticket);
        if ("WORKFLOW_V33".equals(model)) {
            WorkflowLifecycle.Snapshot workflow;
            try {
                workflow = mapper.readValue(workflowJson, WorkflowLifecycle.Snapshot.class);
            } catch (Exception invalid) {
                throw unavailable();
            }
            if (workflow.version() != version || !workflow.state().name().equals(state)
                    || workflow.updatedAt().isAfter(observed)) {
                throw unavailable();
            }
            var evaluated = TicketSlaClockEvaluation.evaluateSourceRecovery(workflow, tier, observed,
                    recovery.linked(), recovery.sourceTime());
            var recoveryClock = "PENDING_Q27".equals(evaluated.reopenPolicyStatus())
                    ? DashboardTicketSla.unavailable("REOPEN_POLICY_MISSING")
                    : DashboardTicketSla.workflow(evaluated.recoveryClock(), observed);
            return new DashboardTicketSla.Assessment(evaluated.priority(), evaluated.frozenServiceTier().name(),
                    evaluated.metricVersion(), version, DashboardTicketSla.workflow(evaluated.responseClock(), observed),
                    recoveryClock);
        }
        if ("LIFECYCLE_V42".equals(model)) {
            return lifecycle(connection, access, ticket, version, observed, recovery.linked());
        }
        throw unavailable();
    }

    private DashboardTicketSla.Assessment lifecycle(Connection connection, Access access, String ticket,
                                                    long version, Instant observed, boolean monitoringLinked) throws SQLException {
        try {
            var entry = lifecycle.dashboardSla(connection, new TicketLifecycleRepository.Access(access.actorId(),
                    access.tenantId(), access.permissionVersion(), "dashboard-sla"), ticket, observed).orElseThrow(
                            PostgresDashboardPendingTicketSlaReader::unavailable);
            if (entry.clock().lifecycleVersion() != version || !entry.clock().observedAt().equals(observed)) {
                throw unavailable();
            }
            // V42's existing clock engine does not yet consume confirmed monitor t2. Do not invent that integration.
            var recovery = monitoringLinked ? DashboardTicketSla.unavailable("LIFECYCLE_MONITORING_RECOVERY_NOT_CONNECTED")
                    : DashboardTicketSla.lifecycle(entry.clock().recovery());
            return new DashboardTicketSla.Assessment(entry.priority(), entry.tier(), "LIFECYCLE_SLA_CLOCK_V1", version,
                    DashboardTicketSla.lifecycle(entry.clock().response()), recovery);
        } catch (DomainException failure) {
            if (failure.status == 422 && "LIFECYCLE_SLA_DISABLED".equals(failure.code)) {
                return DashboardTicketSla.unavailable(version, "LIFECYCLE_SLA_DISABLED");
            }
            if (failure.status == 422 && "CONFIG_PENDING".equals(failure.code)) {
                boolean historyLimit = failure.details.stream().anyMatch(detail -> detail instanceof java.util.Map<?, ?> values
                        && "HISTORY_BUDGET_EXCEEDED".equals(values.get("reason")));
                return DashboardTicketSla.unavailable(version, historyLimit
                        ? "SLA_HISTORY_BUDGET_EXCEEDED" : "LIFECYCLE_SLA_POLICY_UNAVAILABLE");
            }
            throw failure;
        }
    }

    static Tier currentTier(Connection connection, String tenant) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tier FROM public.mop_service_tier WHERE tenant_id=?::uuid")) {
            statement.setQueryTimeout(10);
            statement.setString(1, tenant);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Tier.valueOf(rows.getString(1)) : null;
            }
        }
    }

    private static DomainException unavailable() {
        return new DomainException(503, "UPSTREAM_UNAVAILABLE", "待办工单与SLA快照版本或来源不一致");
    }
}
