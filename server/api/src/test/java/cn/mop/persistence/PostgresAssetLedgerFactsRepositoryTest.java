package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC isolation and projection boundaries; these doubles do not claim designated PostgreSQL execution. */
class PostgresAssetLedgerFactsRepositoryTest {
    private static final String TENANT = "21000000-0000-4000-8000-000000000001";
    private static final String ACTOR = "22000000-0000-4000-8000-000000000001";
    private static final String ASSET = "23000000-0000-4000-8000-000000000001";
    private static final AssetLedgerFactsRepository.Access ACCESS = new AssetLedgerFactsRepository.Access(ACTOR, TENANT, 7);

    @Test
    void readsOnlyTheBoundedPublicAllowlistInOneAuthorizedReadonlySnapshot() throws Exception {
        var fixture = new Fixture();
        var snapshot = fixture.repository.read(ACCESS, List.of(ASSET));
        assertEquals(TENANT, snapshot.tenantId());
        assertEquals(1, snapshot.items().size());
        var fact = snapshot.items().get(0);
        assertEquals("Declared business", fact.businessDescription());
        assertEquals("Declared owner", fact.owner().displayName());
        assertEquals("ACTIVE", fact.owner().status());
        assertEquals("MANUAL_DECLARATION", fact.metadataSource());
        assertEquals(2, fact.metadataVersion());
        var order = inOrder(fixture.connection);
        order.verify(fixture.connection).setReadOnly(true);
        order.verify(fixture.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        order.verify(fixture.connection).setAutoCommit(false);
        verify(fixture.source, times(1)).getConnection();
        verify(fixture.connection).commit();
        assertEquals(List.of("authorize", "capabilities", "clock", "facts"), fixture.operations);
        assertTrue(fixture.factsSql.contains("a.tenant_id=?::uuid AND a.id=ANY(?::uuid[])"));
        assertTrue(fixture.factsSql.contains("m.tenant_id=a.tenant_id AND m.user_id=r.owner_id"));
        assertTrue(fixture.factsSql.endsWith("LIMIT 100"));
        for (String field : List.of("name", "type", "site_id", "management_ip")) {
            assertTrue(fixture.factsSql.contains("a." + field));
            assertTrue(fixture.factsSql.contains("r." + field + " AS metadata_" + field));
        }
        assertFalse(fixture.factsSql.contains("r.note"));
        assertFalse(fixture.factsSql.contains("before_snapshot"));
        assertFalse(fixture.factsSql.contains("FOR UPDATE"));
        verify(fixture.factsStatement).setString(1, TENANT);
    }

    @Test
    void noRevisionIsExplicitlyUnrecordedAndDisabledOwnerIsNotReportedActive() throws Exception {
        var none = new Fixture();
        none.row.remove("revision_version");
        none.row.remove("metadata_version");
        none.row.remove("latest_metadata_version");
        var result = none.repository.read(ACCESS, List.of(ASSET)).items().get(0);
        assertEquals("NOT_RECORDED", result.metadataSource());
        assertNull(result.metadataVersion());
        assertNull(result.owner());
        assertNull(result.businessDescription());
        var inactive = new Fixture();
        inactive.row.put("owner_member_active", false);
        assertEquals("INACTIVE", inactive.repository.read(ACCESS, List.of(ASSET)).items().get(0).owner().status());
    }

    @Test
    void missingForeignAndMixedBatchesHaveNoPartialResponse() throws Exception {
        var missing = new Fixture();
        missing.rows.clear();
        assertEquals(404, assertThrows(DomainException.class, () -> missing.repository.read(ACCESS, List.of(ASSET))).status);
        verify(missing.connection).rollback();
        var mixed = new Fixture();
        assertEquals(404, assertThrows(DomainException.class,
                () -> mixed.repository.read(ACCESS, List.of(ASSET, UUID.randomUUID().toString()))).status);
        verify(mixed.connection, never()).commit();
    }

    @Test
    void corruptRevisionOrOwnerJoinFailsClosed() throws Exception {
        for (String field : List.of("metadata_version", "owner_member_id", "owner_user_id", "recorded_at")) {
            var fixture = new Fixture();
            fixture.row.remove(field);
            assertEquals(503, assertThrows(DomainException.class, () -> fixture.repository.read(ACCESS, List.of(ASSET))).status);
            verify(fixture.connection).rollback();
        }
        var future = new Fixture();
        future.row.put("revision_version", 4L);
        future.row.put("metadata_version", 4L);
        assertEquals(503, assertThrows(DomainException.class, () -> future.repository.read(ACCESS, List.of(ASSET))).status);
        var rewound = new Fixture();
        rewound.row.put("latest_metadata_version", 3L);
        assertEquals(503, assertThrows(DomainException.class, () -> rewound.repository.read(ACCESS, List.of(ASSET))).status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "type", "site_id", "management_ip"})
    void anyCoreDriftRejectsEntireBatchWithoutReturningTheEarlierValidRow(String field) throws Exception {
        var fixture = new Fixture();
        String secondId = UUID.randomUUID().toString();
        var second = new HashMap<>(fixture.row);
        second.put("id", secondId);
        second.put("metadata_" + field, "SYNTHETIC_DRIFT");
        fixture.rows.add(second);
        var error = assertThrows(DomainException.class,
                () -> fixture.repository.read(ACCESS, List.of(ASSET, secondId)));
        assertEquals(503, error.status);
        assertEquals("ASSET_LEDGER_FACTS_UNAVAILABLE", error.code);
        assertEquals(List.of(Map.of("reason", "MANUAL_METADATA_CORE_DRIFT")), error.details);
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.source, times(1)).getConnection();
        assertEquals(List.of("authorize", "capabilities", "clock", "facts"), fixture.operations);
    }

    @Test
    void metadataRecordedAfterSnapshotIsRejectedAndExactBoundaryIsAccepted() throws Exception {
        var future = new Fixture();
        future.row.put("recorded_at", OffsetDateTime.parse("2026-10-23T01:00:00.001Z"));
        assertEquals(503, assertThrows(DomainException.class,
                () -> future.repository.read(ACCESS, List.of(ASSET))).status);
        verify(future.connection, never()).commit();
        var exact = new Fixture();
        exact.row.put("recorded_at", OffsetDateTime.parse("2026-10-23T01:00:00Z"));
        var snapshot = exact.repository.read(ACCESS, List.of(ASSET));
        assertEquals(snapshot.observedAt(), snapshot.items().get(0).metadataRecordedAt());
    }

    @Test
    void invalidBatchIsRejectedBeforeConnecting() throws Exception {
        var fixture = new Fixture();
        for (var ids : List.of(List.<String>of(), List.of(ASSET, ASSET), List.of("bad"), Collections.nCopies(101, ASSET))) {
            assertEquals(400, assertThrows(DomainException.class, () -> fixture.repository.read(ACCESS, ids)).status);
        }
        verify(fixture.source, never()).getConnection();
    }

    @Test
    void revokedMembershipAndCapabilityFailBeforeDataQuery() throws Exception {
        var oldVersion = new Fixture();
        oldVersion.permission = 8;
        assertEquals(401, assertThrows(DomainException.class, () -> oldVersion.repository.read(ACCESS, List.of(ASSET))).status);
        assertFalse(oldVersion.operations.contains("facts"));
        var noRead = new Fixture();
        noRead.capabilities = new String[0];
        assertEquals(403, assertThrows(DomainException.class, () -> noRead.repository.read(ACCESS, List.of(ASSET))).status);
        assertFalse(noRead.operations.contains("facts"));
    }

    @Test
    void databaseFailureDoesNotExposeDriverDetailsOrInventMissingFacts() throws Exception {
        var fixture = new Fixture();
        fixture.failFacts = true;
        var failure = assertThrows(DomainException.class, () -> fixture.repository.read(ACCESS, List.of(ASSET)));
        assertEquals(503, failure.status);
        assertFalse(failure.toString().contains("UNIT_PRIVATE"));
        verify(fixture.connection).rollback();
    }

    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PostgresAssetLedgerFactsRepository repository = new PostgresAssetLedgerFactsRepository(source);
        final Map<String, Object> row = new HashMap<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> operations = new ArrayList<>();
        long permission = 7;
        String[] capabilities = {"assets:read"};
        boolean failFacts;
        String factsSql;
        PreparedStatement factsStatement;

        Fixture() throws Exception {
            row.putAll(Map.of("id", ASSET, "version", 3L, "site_id", "site-a", "site_name", "Declared site",
                    "revision_version", 2L, "metadata_version", 2L, "latest_metadata_version", 2L, "verification_status", "SELF_REPORTED",
                    "recorded_at", OffsetDateTime.parse("2026-10-23T00:00:00Z")));
            row.putAll(Map.of("owner_id", ACTOR, "owner_member_id", ACTOR, "owner_user_id", ACTOR,
                    "owner_name", "Declared owner", "owner_member_active", true, "owner_user_active", true,
                    "business_description", "Declared business"));
            row.putAll(Map.of("asset_name", "Synthetic asset", "metadata_name", "Synthetic asset",
                    "asset_type", "router", "metadata_type", "router", "metadata_site_id", "site-a",
                    "asset_management_ip", "192.0.2.1", "metadata_management_ip", "192.0.2.1"));
            rows.add(row);
            when(source.getConnection()).thenReturn(connection);
            when(connection.createArrayOf(eq("uuid"), any())).thenReturn(mock(java.sql.Array.class));
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var statement = mock(PreparedStatement.class);
                when(statement.executeQuery()).thenAnswer(query -> {
                    if (sql.contains("FROM public.mop_membership WHERE")) {
                        operations.add("authorize");
                        return result(List.of(Map.of("1", permission)));
                    }
                    if (sql.contains("mop_effective_capabilities")) {
                        operations.add("capabilities");
                        return result(List.of(Map.of("1", capabilities)));
                    }
                    if (sql.contains("transaction_timestamp")) {
                        operations.add("clock");
                        return result(List.of(Map.of("1", OffsetDateTime.parse("2026-10-23T01:00:00Z"))));
                    }
                    factsSql = sql;
                    factsStatement = statement;
                    operations.add("facts");
                    if (failFacts) {
                        throw new SQLException("UNIT_PRIVATE raw database detail");
                    }
                    return result(rows);
                });
                return statement;
            });
        }

        private ResultSet result(List<Map<String, Object>> values) throws Exception {
            int[] index = {-1};
            boolean[] wasNull = {false};
            return mock(ResultSet.class, call -> {
                String method = call.getMethod().getName();
                if (method.equals("next")) {
                    return ++index[0] < values.size();
                }
                if (method.equals("close")) {
                    return null;
                }
                if (method.equals("wasNull")) {
                    return wasNull[0];
                }
                Object value = values.get(index[0]).get(String.valueOf((Object) call.getArgument(0)));
                wasNull[0] = value == null;
                return switch (method) {
                    case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                    case "getBoolean" -> Boolean.TRUE.equals(value);
                    case "getArray" -> {
                        var array = mock(java.sql.Array.class);
                        when(array.getArray()).thenReturn(value);
                        yield array;
                    }
                    default -> value;
                };
            });
        }
    }
}
