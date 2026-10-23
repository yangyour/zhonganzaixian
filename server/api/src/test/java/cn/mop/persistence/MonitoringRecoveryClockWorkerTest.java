package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.WorkflowLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the actual tick control flow with JDBC substitutes; transaction rollback needs an isolated real database. */
class MonitoringRecoveryClockWorkerTest {
    private static final String TENANT = "10000000-0000-4000-8000-000000000001";
    private static final String TICKET = "10000000-0000-4000-8000-000000000002";
    private static final String TASK = "10000000-0000-4000-8000-000000000003";
    private static final String EVENT = "10000000-0000-4000-8000-000000000004";
    private static final Instant START = Instant.parse("2026-10-23T08:00:00Z");
    private static final Instant OBSERVED = START.plusSeconds(7200);

    private static final class Harness {
        final Connection connection = mock(Connection.class);
        final PostgresPlatformRepository platform = mock(PostgresPlatformRepository.class);
        final PostgresTicketWorkflowRepository repository;

        Harness(Instant recoveredAt) throws Exception {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            var snapshot = WorkflowLifecycle.open(TicketSlaSnapshot.freeze("P2", Tier.STANDARD, START,
                Duration.ofMinutes(30), Duration.ofHours(1), null, 48L, null, List.of(), Map.of()), START);
            String encoded = mapper.writeValueAsString(snapshot);
            when(platform.ticketClockTransaction(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
            when(platform.ticketClockConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet rows = mock(ResultSet.class);
                when(statement.executeQuery()).thenReturn(rows);
                when(statement.executeUpdate()).thenReturn(1);
                when(rows.next()).thenReturn(true, false);
                if (sql.startsWith("SELECT j.id::text")) {
                    when(rows.getString(1)).thenReturn(TASK);
                    when(rows.getString(2)).thenReturn(TICKET);
                    when(rows.getString(3)).thenReturn("recovery:50");
                    when(rows.getString(4)).thenReturn("SLA");
                } else if (sql.contains("pg_try_advisory_xact_lock")) {
                    when(rows.getBoolean(1)).thenReturn(true);
                } else if (sql.startsWith("SELECT 1 FROM public.mop_ticket_workflow_clock_receipt")) {
                    when(rows.next()).thenReturn(false);
                } else if (sql.startsWith("SELECT id::text,snapshot,snapshot_hash")) {
                    when(rows.getString(1)).thenReturn(EVENT);
                    when(rows.getString(2)).thenReturn(encoded);
                    when(rows.getString(3)).thenReturn("a".repeat(64));
                } else if (sql.startsWith("SELECT date_trunc")) {
                    when(rows.getObject(1, OffsetDateTime.class)).thenReturn(OBSERVED.atOffset(ZoneOffset.UTC));
                } else if (sql.startsWith("SELECT count(*)>0")) {
                    when(rows.getBoolean(1)).thenReturn(true);
                    var recovery = recoveredAt == null ? null : recoveredAt.atOffset(ZoneOffset.UTC);
                    when(rows.getObject(2)).thenReturn(recovery);
                    when(rows.getObject(2, OffsetDateTime.class)).thenReturn(recovery);
                } else if (!sql.startsWith("INSERT INTO public.mop_ticket_workflow_")) {
                    throw new AssertionError("Unexpected query: " + sql);
                }
                return statement;
            });
            repository = new PostgresTicketWorkflowRepository(mock(DataSource.class), platform, mapper);
        }
    }

    @Test
    void invalidEarlyT2CannotAppendObsoleteReceiptOrOutbox() throws Exception {
        var harness = new Harness(START.minusMillis(1));
        var failure = assertThrows(DomainException.class, () -> harness.repository.tick(TENANT, 1));
        assertEquals("RECOVERY_EVIDENCE_INVALID", failure.code);
        verify(harness.connection, never()).prepareStatement(startsWith("INSERT"));
        verify(harness.platform).ticketClockTransaction(any());
    }

    @Test
    void futureT2CannotAppendReminderOrConsumeTask() throws Exception {
        var harness = new Harness(OBSERVED.plusMillis(1));
        assertEquals(503, assertThrows(DomainException.class, () -> harness.repository.tick(TENANT, 1)).status);
        verify(harness.connection, never()).prepareStatement(startsWith("INSERT"));
    }

    @Test
    void nullRecoveryStillProducesOneDurableReminderIntentAndReceipt() throws Exception {
        var harness = new Harness(null);
        assertEquals(1, harness.repository.tick(TENANT, 1));
        verify(harness.connection).prepareStatement(startsWith("INSERT INTO public.mop_ticket_workflow_outbox"));
        verify(harness.connection).prepareStatement(startsWith("INSERT INTO public.mop_ticket_workflow_clock_receipt"));
    }

    @Test
    void validEarlyT2CanRetireTaskWithoutReminderIntent() throws Exception {
        var harness = new Harness(START.plusSeconds(600));
        assertEquals(1, harness.repository.tick(TENANT, 1));
        verify(harness.connection, never()).prepareStatement(startsWith("INSERT INTO public.mop_ticket_workflow_outbox"));
        verify(harness.connection).prepareStatement(startsWith("INSERT INTO public.mop_ticket_workflow_clock_receipt"));
    }
}
