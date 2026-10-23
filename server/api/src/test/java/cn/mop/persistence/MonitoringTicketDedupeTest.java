package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL substitutes verify decisions and binding/locking boundaries; they are not PostgreSQL integration evidence. */
class MonitoringTicketDedupeTest {
    private static final String TENANT = "10000000-0000-4000-8000-000000000001";
    private static final String EVENT = "10000000-0000-4000-8000-000000000002";
    private static final String PREVIOUS = "10000000-0000-4000-8000-000000000003";
    private static final String TICKET = "10000000-0000-4000-8000-000000000004";
    private static final String RELEASE = "10000000-0000-4000-8000-000000000005";
    private static final Instant PROBLEM = Instant.parse("2026-10-23T08:10:00Z");
    private static final Instant NOW = Instant.parse("2026-10-23T09:10:00Z");
    private static final String FINGERPRINT = "FP_V1|" + TENANT + "|asset|RULE";
    private static final String REGISTRY = "{\"key\":\"ticket.default_dedupe_window_sec\","
        + "\"type\":\"integer\",\"contract_value_kind\":\"configurable_default\",\"value\":1800}";

    private static final class Database {
        final Connection connection = mock(Connection.class);
        final PreparedStatement release = mock(PreparedStatement.class);
        final PreparedStatement registry = mock(PreparedStatement.class);
        final PreparedStatement candidate = mock(PreparedStatement.class);
        final PreparedStatement state = mock(PreparedStatement.class);
        final ResultSet releases = mock(ResultSet.class);
        final ResultSet registryRows = mock(ResultSet.class);
        final ResultSet candidates = mock(ResultSet.class);
        final ResultSet states = mock(ResultSet.class);
        final List<String> operations = new ArrayList<>();

        Database() throws SQLException {
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("pg_advisory_xact_lock")) {
                    PreparedStatement lock = mock(PreparedStatement.class);
                    doAnswer(bind -> {
                        operations.add("lock:" + bind.getArgument(1));
                        return null;
                    }).when(lock).setString(eq(1), anyString());
                    return lock;
                }
                operations.add(sql);
                if (sql.contains("mop_runtime_configuration_release")) {
                    return release;
                }
                if (sql.contains("FROM public.mop_configuration")) {
                    return registry;
                }
                if (sql.startsWith("SELECT e.id::text")) {
                    return candidate;
                }
                if (sql.startsWith("SELECT EXISTS")) {
                    return state;
                }
                throw new AssertionError("Unexpected SQL: " + sql);
            });
            when(release.executeQuery()).thenReturn(releases);
            when(registry.executeQuery()).thenReturn(registryRows);
            when(candidate.executeQuery()).thenReturn(candidates);
            when(state.executeQuery()).thenReturn(states);
            when(registryRows.next()).thenReturn(true);
            when(registryRows.getString(1)).thenReturn(REGISTRY);
            when(candidates.next()).thenReturn(true);
            when(candidates.getString(1)).thenReturn(PREVIOUS);
            when(candidates.getString(2)).thenReturn(TICKET);
            when(states.next()).thenReturn(true);
            when(states.getString(2)).thenReturn("IN_PROGRESS");
        }

        void activeRelease(String action, String value, Instant expires) throws SQLException {
            when(releases.next()).thenReturn(true);
            when(releases.getString("action")).thenReturn(action);
            when(releases.getString("value_json")).thenReturn(value);
            when(releases.getString("id")).thenReturn(RELEASE);
            when(releases.getLong("version")).thenReturn(7L);
            when(releases.getObject("expires_at", OffsetDateTime.class))
                .thenReturn(expires == null ? null : expires.atOffset(ZoneOffset.UTC));
        }

        java.util.Optional<MonitoringTicketDedupe.Match> find() throws SQLException {
            return MonitoringTicketDedupe.find(connection, TENANT, EVENT, FINGERPRINT, PROBLEM, NOW);
        }
    }

    @Test
    void defaultComesFromRegistryAndScopesCandidateToTenantFingerprintAndOriginalProblemTime() throws Exception {
        var db = new Database();
        var match = db.find().orElseThrow();
        assertEquals(PREVIOUS, match.eventId());
        assertEquals(TICKET, match.ticketId());
        assertEquals(1800, match.policy().seconds());
        assertEquals("contract-default:ticket.default_dedupe_window_sec", match.policy().reference());
        verify(db.candidate).setString(1, TENANT);
        verify(db.candidate).setString(2, FINGERPRINT);
        verify(db.candidate).setString(3, EVENT);
        verify(db.candidate).setObject(4, PROBLEM.atOffset(ZoneOffset.UTC));
        verify(db.candidate).setObject(5, PROBLEM.atOffset(ZoneOffset.UTC));
        verify(db.candidate).setLong(6, 1800);
        verify(db.release).setObject(3, NOW.atOffset(ZoneOffset.UTC));
        assertTrue(db.operations.stream().anyMatch(sql -> sql.contains("a.model='WORKFLOW_V33'")
            && sql.contains("e.problem_at<=?") && sql.contains("))<=?") && sql.endsWith("LIMIT 1")));
    }

    @Test
    void workflowLockPrecedesFreshStateReadAndNoTicketOrClockIsWritten() throws Exception {
        var db = new Database();
        db.find();
        int lock = db.operations.indexOf("lock:mop.ticket.workflow|" + TENANT + "|" + TICKET);
        int read = java.util.stream.IntStream.range(0, db.operations.size())
            .filter(i -> db.operations.get(i).startsWith("SELECT EXISTS")).findFirst().orElseThrow();
        assertTrue(lock >= 0 && lock < read);
        verify(db.state).setString(1, TENANT);
        verify(db.state).setString(2, TICKET);
        verify(db.state).setString(3, TENANT);
        verify(db.state).setString(4, TICKET);
        verify(db.connection, never()).prepareStatement(startsWith("INSERT"));
        verify(db.connection, never()).prepareStatement(startsWith("UPDATE"));
    }

    @Test
    void activePublicationOverridesDefault() throws Exception {
        var db = new Database();
        db.activeRelease("PUBLISH", "600", NOW.plusSeconds(1));
        assertEquals(new MonitoringTicketDedupe.Policy(600, "release:" + RELEASE + ":7"),
            db.find().orElseThrow().policy());
        verifyNoInteractions(db.registry);
    }

    @Test
    void rollbackUsesItsPublishedValue() throws Exception {
        var db = new Database();
        db.activeRelease("ROLLBACK", "3600", null);
        assertEquals(3600, db.find().orElseThrow().policy().seconds());
    }

    @Test
    void disabledOrExpiredReleaseDoesNotFallBackOrSearchTickets() throws Exception {
        for (String action : List.of("DISABLE", "PUBLISH")) {
            var db = new Database();
            db.activeRelease(action, "1800", action.equals("PUBLISH") ? NOW : null);
            assertEquals("CONFIG_PENDING", assertThrows(DomainException.class, db::find).code);
            verifyNoInteractions(db.registry, db.candidate, db.state);
        }
    }

    @Test
    void malformedDurationsNeverBecomeAWindow() throws Exception {
        for (String value : List.of("null", "0", "-1", "0.5", "\"1800\"", "{}", "9007199254740991")) {
            var db = new Database();
            db.activeRelease("PUBLISH", value, null);
            assertEquals(422, assertThrows(DomainException.class, db::find).status, value);
            verifyNoInteractions(db.registry, db.candidate, db.state);
        }
    }

    @Test
    void missingOrNonDefaultRegistryIsNotInvented() throws Exception {
        var missing = new Database();
        when(missing.registryRows.next()).thenReturn(false);
        assertThrows(DomainException.class, missing::find);
        var wrong = new Database();
        when(wrong.registryRows.getString(1)).thenReturn(REGISTRY.replace("configurable_default", "pending_customer"));
        assertThrows(DomainException.class, wrong::find);
    }

    @Test
    void noMatchingPredecessorDoesNotReadOrLockAnotherTicket() throws Exception {
        var db = new Database();
        when(db.candidates.next()).thenReturn(false);
        assertTrue(db.find().isEmpty());
        verifyNoInteractions(db.state);
        assertFalse(db.operations.stream().anyMatch(op -> op.startsWith("lock:mop.ticket.workflow")));
    }

    @Test
    void terminalWorkflowCannotBeReusedAndDoesNotFallBackToOlderTicket() throws Exception {
        for (String state : List.of("CLOSED", "CANCELLED")) {
            var db = new Database();
            when(db.states.getString(2)).thenReturn(state);
            assertTrue(db.find().isEmpty());
            verify(db.candidate, times(1)).executeQuery();
        }
    }

    @Test
    void eachLiveWorkflowStateKeepsTheSameTicket() throws Exception {
        for (String state : List.of("OPEN", "ACCEPTED", "IN_PROGRESS", "RESOLVED")) {
            var db = new Database();
            when(db.states.getString(2)).thenReturn(state);
            assertEquals(TICKET, db.find().orElseThrow().ticketId());
        }
    }

    @Test
    void otherLifecycleCannotBeReused() throws Exception {
        var db = new Database();
        when(db.states.getBoolean(1)).thenReturn(true);
        assertTrue(db.find().isEmpty());
    }

    @Test
    void missingOrUnknownWorkflowStateFailsClosed() throws Exception {
        for (String state : new String[] {null, "UNRECOGNIZED"}) {
            var db = new Database();
            when(db.states.getString(2)).thenReturn(state);
            assertThrows(SQLException.class, db::find);
        }
    }

    @Test
    void databaseFailureIsNotANoMatch() throws Exception {
        var db = new Database();
        when(db.candidate.executeQuery()).thenThrow(new SQLException("synthetic dependency failure"));
        assertThrows(SQLException.class, db::find);
    }
}
