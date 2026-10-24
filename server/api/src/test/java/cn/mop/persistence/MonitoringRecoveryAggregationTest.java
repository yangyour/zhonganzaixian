package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.MonitoringRecoveryClockEvidence.SourceTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Aggregation control-flow tests with explicit verification substitutes; no live database claim. */
class MonitoringRecoveryAggregationTest {
    private static final String TENANT = "10000000-0000-4000-8000-000000000001";
    private static final String TICKET = "10000000-0000-4000-8000-000000000002";
    private static final String FIRST = "10000000-0000-4000-8000-000000000003";
    private static final String SECOND = "10000000-0000-4000-8000-000000000004";

    private Connection linked() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn(FIRST, SECOND);
        return connection;
    }

    @Test
    void takesMaximumExactT2OnlyWhenEveryLinkedCurrentCycleHasVerifiedEvidence() throws Exception {
        var connection = linked();
        var earlier = new SourceTime(Instant.parse("2026-10-23T09:25:00.000000001Z"), UUID.randomUUID());
        var later = new SourceTime(earlier.recoveredAt().plusNanos(1), UUID.randomUUID());
        try (var verification = mockStatic(PostgresMonitoringRecoveryEvidence.class)) {
            verification.when(() -> PostgresMonitoringRecoveryEvidence.read(connection, TENANT, FIRST)).thenReturn(Optional.of(earlier));
            verification.when(() -> PostgresMonitoringRecoveryEvidence.read(connection, TENANT, SECOND)).thenReturn(Optional.of(later));
            var result = PostgresTicketWorkflowRepository.monitoringRecovery(connection, TENANT, TICKET);
            assertTrue(result.linked());
            assertEquals(later, result.sourceTime());
            assertEquals(later.recoveredAt(), result.recoveredAt());
        }
    }

    @Test
    void missingOrLegacyProofPreventsOtherEventsFromStoppingTheSharedClock() throws Exception {
        var connection = linked();
        var valid = new SourceTime(Instant.parse("2026-10-23T09:25:00Z"), UUID.randomUUID());
        try (var verification = mockStatic(PostgresMonitoringRecoveryEvidence.class)) {
            verification.when(() -> PostgresMonitoringRecoveryEvidence.read(connection, TENANT, FIRST)).thenReturn(Optional.of(valid));
            verification.when(() -> PostgresMonitoringRecoveryEvidence.read(connection, TENANT, SECOND)).thenReturn(Optional.empty());
            var result = PostgresTicketWorkflowRepository.monitoringRecovery(connection, TENANT, TICKET);
            assertTrue(result.linked());
            assertNull(result.sourceTime());
            assertNull(result.recoveredAt());
        }
    }

    @Test
    void corruptEvidenceFailsTheWholeReadInsteadOfReturningTheOtherEventsTime() throws Exception {
        var connection = linked();
        try (var verification = mockStatic(PostgresMonitoringRecoveryEvidence.class)) {
            verification.when(() -> PostgresMonitoringRecoveryEvidence.read(connection, TENANT, FIRST))
                    .thenReturn(Optional.of(new SourceTime(Instant.parse("2026-10-23T09:25:00Z"), UUID.randomUUID())));
            verification.when(() -> PostgresMonitoringRecoveryEvidence.read(connection, TENANT, SECOND))
                    .thenThrow(new DomainException(503, "RECOVERY_EVIDENCE_INVALID", "synthetic invalid proof"));
            assertEquals("RECOVERY_EVIDENCE_INVALID", assertThrows(DomainException.class,
                    () -> PostgresTicketWorkflowRepository.monitoringRecovery(connection, TENANT, TICKET)).code);
        }
    }
}
