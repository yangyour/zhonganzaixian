package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Probe wiring only; actual frozen schema verification remains a designated-component test. */
class NotificationControlReadinessGuardTest {

    @Test
    void aTriggerNameAndPrivilegeMatchAreNotEnoughWithoutFrozenSchemaVerification() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);
        try (var schema = mockStatic(NotificationControlSchemaVerifier.class)) {
            schema
                .when(() -> NotificationControlSchemaVerifier.verify(connection))
                .thenThrow(new SQLException("wrong immutable guard"));
            assertFalse(new NotificationControlStore(source, new ObjectMapper()).ready());
            schema.verify(NotificationControlSchemaVerifier::resource);
            schema.verify(() -> NotificationControlSchemaVerifier.verify(connection));
        }
    }

    @Test
    void successfulProbeRequiresBothResourceAndDatabaseGuardChecks() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        try (
            var schema = mockStatic(NotificationControlSchemaVerifier.class);
            var delivery = mockStatic(DeliveryReceiptSchemaVerifier.class)
        ) {
            assertTrue(new NotificationControlStore(source, new ObjectMapper()).ready());
            schema.verify(NotificationControlSchemaVerifier::resource);
            schema.verify(() -> NotificationControlSchemaVerifier.verify(connection));
            delivery.verify(() -> DeliveryReceiptSchemaVerifier.verify(connection));
        }
    }

    @Test
    void missingDeliveryGuardOrSnapshotIsolationCannotReportCancellationReady() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        try (
            var schema = mockStatic(NotificationControlSchemaVerifier.class);
            var delivery = mockStatic(DeliveryReceiptSchemaVerifier.class)
        ) {
            delivery
                .when(() -> DeliveryReceiptSchemaVerifier.verify(connection))
                .thenThrow(new SQLException("missing V50 guard"));
            assertFalse(new NotificationControlStore(source, new ObjectMapper()).ready());
            delivery.reset();
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
            assertFalse(new NotificationControlStore(source, new ObjectMapper()).ready());
        }
    }
}
