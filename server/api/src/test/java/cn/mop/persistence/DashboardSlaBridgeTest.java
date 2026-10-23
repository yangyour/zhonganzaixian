package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.TicketLifecycleRepository.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies that dashboard composition cannot silently enable the existing V42 consumer or switch transactions. */
class DashboardSlaBridgeTest {
    static final Access ACCESS = new Access("10000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000002", 1, "dashboard-unit");
    static final String TICKET = "30000000-0000-4000-8000-000000000003";

    @Test
    void disabledConsumerReturnsAnExplicitReasonBeforeDatabaseAccess() {
        var source = mock(DataSource.class);
        var mapper = new ObjectMapper();
        var repository = new PostgresTicketLifecycleRepository(source, new PostgresPlatformRepository(source, mapper), mapper, false);
        var connection = mock(Connection.class);
        var error = assertThrows(DomainException.class,
                () -> repository.dashboardSla(connection, ACCESS, TICKET, Instant.parse("2026-10-23T08:00:00Z")));
        assertEquals(422, error.status);
        assertEquals("LIFECYCLE_SLA_DISABLED", error.code);
        verifyNoInteractions(connection, source);
    }

    @Test
    void compositionRequiresReadOnlyRepeatableTransactionAndExplicitTime() throws Exception {
        var source = mock(DataSource.class);
        var mapper = new ObjectMapper();
        var repository = new PostgresTicketLifecycleRepository(source, new PostgresPlatformRepository(source, mapper), mapper, true);
        var connection = mock(Connection.class);
        assertThrows(IllegalStateException.class, () -> repository.dashboardSla(connection, ACCESS, TICKET, Instant.now()));
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        assertThrows(NullPointerException.class, () -> repository.dashboardSla(connection, ACCESS, TICKET, null));
        verifyNoInteractions(source);
        verify(connection, never()).prepareStatement(anyString());
    }
}
