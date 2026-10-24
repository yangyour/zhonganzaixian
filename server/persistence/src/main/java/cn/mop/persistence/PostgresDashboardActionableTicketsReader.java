package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader;
import cn.mop.domain.dashboard.DashboardPendingTicketSlaReader;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Counts, full-population SLA and pagination use one authorized read-only repeatable-read transaction. */
public final class PostgresDashboardActionableTicketsReader implements DashboardActionableTicketsReader {
    private static final String ACTIONABLE = "model<>'UNCONFIGURED' AND state NOT IN ('CLOSED','CANCELLED','RESOLVED')";
    private static final long READ_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** Reuses both persisted-model validation and tenant/reporter authorization without changing V1/V2. */
    private final PostgresDashboardPendingTicketsReader pending;

    /** Reuses frozen workflow clocks and the actual lifecycle feature gate, including unknown inputs. */
    private final PostgresDashboardPendingTicketSlaReader sla;

    public PostgresDashboardActionableTicketsReader(DataSource source, PostgresPlatformRepository platform,
                                                    PostgresTicketLifecycleRepository lifecycle, ObjectMapper mapper) {
        pending = new PostgresDashboardPendingTicketsReader(source, platform);
        sla = new PostgresDashboardPendingTicketSlaReader(source, platform, lifecycle, mapper);
    }

    @Override
    public Snapshot read(Access access, Page page) {
        Objects.requireNonNull(page);
        long started = System.nanoTime();
        return pending.readSnapshot(access, (connection, base) -> {
            boolean internal = "CURRENT_TENANT_INTERNAL".equals(base.scope());
            long total;
            try (var statement = PostgresDashboardPendingTicketsReader.query(connection, access, internal,
                    "SELECT count(*) FROM visible WHERE " + ACTIONABLE)) {
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw unavailable();
                    }
                    total = rows.getLong(1);
                }
            }
            var tier = PostgresDashboardPendingTicketSlaReader.currentTier(connection, access.tenantId());
            var items = new ArrayList<DashboardPendingTicketSlaReader.Item>();
            long evaluated = 0;
            long knownOverdue = 0;
            long unknown = 0;
            try (var statement = PostgresDashboardPendingTicketsReader.query(connection, access, internal,
                    "SELECT id,receipt_no,title,created_at,model,state,version,workflow_snapshot FROM visible WHERE "
                            + ACTIONABLE + " ORDER BY created_at DESC,id DESC")) {
                // PostgreSQL cursor batches bound memory, not the population used for the SLA total.
                statement.setFetchSize(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        requireBudget(started);
                        String ticketId = rows.getString("id");
                        String model = rows.getString("model");
                        String state = rows.getString("state");
                        long version = rows.getLong("version");
                        if (!DashboardActionableTicketsReader.actionable(model, state)) {
                            throw unavailable();
                        }
                        var assessment = sla.assess(connection, access, ticketId, model, state, version,
                                rows.getString("workflow_snapshot"), base.observedAt(), tier);
                        if (Boolean.TRUE.equals(assessment.overdue())) {
                            knownOverdue++;
                        } else if (assessment.overdue() == null) {
                            unknown++;
                        }
                        if (evaluated >= page.offset() && evaluated < page.offset() + page.size()) {
                            var ticket = new DashboardPendingTicketsReader.Item(UUID.fromString(ticketId),
                                    rows.getString("receipt_no"), rows.getString("title"),
                                    rows.getObject("created_at", OffsetDateTime.class).toInstant(), model, state, version);
                            items.add(new DashboardPendingTicketSlaReader.Item(ticket, assessment));
                        }
                        evaluated++;
                    }
                }
            }
            requireBudget(started);
            if (evaluated != total) {
                throw unavailable();
            }
            return new Snapshot(base.tenantId(), base.actorId(), base.permissionVersion(), base.observedAt(), base.scope(),
                    base.registrations(), total, base.registrations() - base.unconfigured() - total,
                    base.unconfigured(), knownOverdue, unknown, page, items);
        });
    }

    /** A timed-out complete read is a dependency failure; it must never masquerade as a truncated success. */
    private static void requireBudget(long started) {
        if (System.nanoTime() - started > READ_BUDGET_NANOS) {
            throw new DomainException(503, "UPSTREAM_UNAVAILABLE", "待处理工单完整SLA评估超出读取时限，请稍后重试");
        }
    }

    private static DomainException unavailable() {
        return new DomainException(503, "UPSTREAM_UNAVAILABLE", "待处理工单完整计数、分页或SLA快照不一致");
    }
}
