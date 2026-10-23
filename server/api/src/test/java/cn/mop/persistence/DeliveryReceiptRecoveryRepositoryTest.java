package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.domain.notification.DeliveryReceiptState.Deduplication;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryReceiptRecoveryRepositoryTest {
    private final UUID tenant = UUID.randomUUID();
    private final Access access = new Access(tenant, UUID.randomUUID(), 1);
    private final Scope scope = new Scope(tenant, UUID.randomUUID(), new Deduplication(UUID.randomUUID(),
            UUID.randomUUID(), Channel.WEBHOOK, UUID.randomUUID(), "OPENED"));

    @Test
    void crossTenantAndMissingCommandAreRejectedBeforeConnection() {
        var source = mock(DataSource.class);
        var repository = new PostgresDeliveryReceiptRepository(source, (c, a, s, o) -> {}, (c, a, s, command) -> {});
        assertEquals(404, assertThrows(DomainException.class, () -> repository.recover(
                new Access(UUID.randomUUID(), access.actorId(), 1), scope, UUID.randomUUID())).status);
        assertEquals(400, assertThrows(DomainException.class, () -> repository.recover(access, scope, null)).status);
        verifyNoInteractions(source);
    }

    @Test
    void missingDurableRootIsEmptyAndLiveReadAuthorityIsCheckedTwice() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        var checks = new AtomicInteger();
        var repository = new PostgresDeliveryReceiptRepository(source, (c, a, s, operation) -> {
            assertEquals(PostgresDeliveryReceiptRepository.Operation.READ, operation);
            checks.incrementAndGet();
        }, (c, a, s, command) -> fail("Read recovery must not append a transition"));
        assertTrue(repository.recover(access, scope, UUID.randomUUID()).isEmpty());
        assertEquals(2, checks.get());
        verify(connection).commit();
        verify(connection).close();
        verify(statement).setObject(1, tenant);
        verify(statement).setObject(2, scope.deliveryId());
        verify(statement, never()).executeUpdate();
    }

    @Test
    void revokedRecoveryIsSanitizedAndDoesNotReadOrCommit() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        var repository = new PostgresDeliveryReceiptRepository(source, (c, a, s, operation) -> {
            throw new DomainException(403, "SYNTHETIC_PRIVATE", "SYNTHETIC_PRIVATE");
        }, (c, a, s, command) -> {});
        var failure = assertThrows(DomainException.class, () -> repository.recover(access, scope, UUID.randomUUID()));
        assertEquals(403, failure.status);
        assertFalse(failure.getMessage().contains("PRIVATE"));
        assertNull(failure.getCause());
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).commit();
        verify(connection).rollback();
    }

    @Test
    void databaseFailureDoesNotReturnAnEmptySuccessfulRecovery() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("SYNTHETIC_PRIVATE"));
        var repository = new PostgresDeliveryReceiptRepository(source, (c, a, s, operation) -> {}, (c, a, s, command) -> {});
        var failure = assertThrows(DomainException.class, () -> repository.recover(access, scope, UUID.randomUUID()));
        assertEquals(503, failure.status);
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("PRIVATE"));
    }
}
