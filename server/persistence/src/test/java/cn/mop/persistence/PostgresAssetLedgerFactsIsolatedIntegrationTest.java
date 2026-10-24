package cn.mop.persistence;

import cn.mop.domain.Asset;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository.Access;
import cn.mop.domain.assetmetadata.ManualAssetMetadata;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static cn.mop.persistence.PostgresDashboardPendingTicketsIntegrationTest.invoke;
import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit fresh project tenants on the designated PG only; no legacy principals, customer writes or migrations. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_ASSET_LEDGER_FACTS_PG_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresAssetLedgerFactsIsolatedIntegrationTest {
    static PostgresAssetLedgerFactsRepository facts;
    static PostgresManualAssetMetadataRepository metadata;
    static String unrecordedAsset;
    static final String PRIVATE_NOTE = "SYNTHETIC_PRIVATE_METADATA_DO_NOT_EXPOSE";

    @BeforeAll
    static void prepareFacts() throws Exception {
        PostgresReportCaptureIsolatedIntegrationTest.connect();
        facts = new PostgresAssetLedgerFactsRepository(pool);
        metadata = new PostgresManualAssetMetadataRepository(pool, platform, mapper);
        var current = platform.find(tenant, asset).orElseThrow();
        assertEquals("manual", current.source());
        assertEquals(1, current.version());
        assertEquals("NOT_RECORDED", facts.read(factsAccess(), List.of(asset)).items().get(0).metadataSource());
        // A second explicit bootstrap asset exercises read absence without bypassing unpublished creation quotas.
        unrecordedAsset = uuidEnv("MOP_ASSET_LEDGER_UNRECORDED_ASSET");
        assertNotEquals(asset, unrecordedAsset);
        assertNotEquals(foreignAsset, unrecordedAsset);
        var unrecorded = platform.find(tenant, unrecordedAsset).orElseThrow();
        assertEquals(tenant, unrecorded.tenantId());
        assertEquals("manual", unrecorded.source());
        assertEquals(1, unrecorded.version());
        assertEquals("NOT_RECORDED", facts.read(factsAccess(), List.of(unrecordedAsset)).items().get(0).metadataSource());
        updateMetadata(current, "SYNTHETIC_PUBLIC_BUSINESS_DECLARATION");
    }

    static Access factsAccess() {
        return new Access(actor, tenant, platform.membership(actor, tenant).orElseThrow().permissionVersion());
    }

    static ManualAssetMetadataRepository.Access metadataAccess() {
        return new ManualAssetMetadataRepository.Access(actor, tenant, factsAccess().permissionVersion(), UUID.randomUUID().toString());
    }

    static void updateMetadata(Asset current, String business) {
        var access = metadataAccess();
        var declaration = new ManualAssetMetadata(current.name(), current.type(), current.siteId(), null, null, null,
                null, actor, business, null, null, null, null, PRIVATE_NOTE);
        platform.idempotentAuthorized(actor, tenant, "PATCH /integration/ledger-facts/metadata/" + asset,
                UUID.randomUUID().toString(), "b".repeat(64), access.permissionVersion(),
                () -> metadata.authorizeCurrentAsset(access, asset),
                () -> metadata.update(access, asset, current.version(), declaration));
    }

    @AfterAll
    static void closeFacts() {
        PostgresReportCaptureIsolatedIntegrationTest.close();
    }

    @Test
    @Order(1)
    void publicFactsBindExactAssetAndRevisionWhileUnrecordedValuesRemainNull() throws Exception {
        var before = counts();
        var snapshot = facts.read(factsAccess(), List.of(asset, unrecordedAsset));
        assertEquals(2, snapshot.items().size());
        var declared = snapshot.items().stream().filter(item -> asset.equals(item.assetId())).findFirst().orElseThrow();
        assertEquals(2, declared.assetVersion());
        assertEquals(2L, declared.metadataVersion());
        assertEquals("MANUAL_DECLARATION", declared.metadataSource());
        assertEquals("SYNTHETIC_PUBLIC_BUSINESS_DECLARATION", declared.businessDescription());
        assertEquals(actor, declared.owner().id());
        assertEquals("ACTIVE", declared.owner().status());
        assertFalse(declared.metadataRecordedAt().isAfter(snapshot.observedAt()));
        var missing = snapshot.items().stream().filter(item -> unrecordedAsset.equals(item.assetId())).findFirst().orElseThrow();
        assertEquals("NOT_RECORDED", missing.metadataSource());
        assertNull(missing.metadataVersion());
        assertNull(missing.metadataRecordedAt());
        assertNull(missing.businessDescription());
        assertNull(missing.owner());
        String publicJson = mapper.writeValueAsString(snapshot);
        assertFalse(publicJson.contains(PRIVATE_NOTE));
        assertFalse(publicJson.contains("before_snapshot"));
        assertFalse(publicJson.contains("management_ip"));
        assertEquals(before, counts());
    }

    @Test
    @Order(2)
    void foreignMissingAndStalePermissionRequestsNeverReturnPartialFacts() {
        for (String missing : List.of(foreignAsset, UUID.randomUUID().toString())) {
            assertEquals(404, assertThrows(DomainException.class,
                    () -> facts.read(factsAccess(), List.of(asset, missing))).status);
        }
        assertEquals(401, assertThrows(DomainException.class, () -> facts.read(
                new Access(actor, tenant, factsAccess().permissionVersion() + 1), List.of(asset))).status);
        assertEquals(400, assertThrows(DomainException.class, () -> facts.read(factsAccess(), List.of(asset, asset))).status);
    }

    @Test
    @Order(3)
    void actualCoreDriftRejectsTheWholeBatchAndRestoringTheIsolatedCoreRestoresReadability() {
        String original = platform.find(tenant, asset).orElseThrow().name();
        try {
            changeIsolatedName("SYNTHETIC_LEDGER_CORE_DRIFT");
            var error = assertThrows(DomainException.class,
                    () -> facts.read(factsAccess(), List.of(unrecordedAsset, asset)));
            assertEquals(503, error.status);
            assertEquals("ASSET_LEDGER_FACTS_UNAVAILABLE", error.code);
            assertEquals(List.of(Map.of("reason", "MANUAL_METADATA_CORE_DRIFT")), error.details);
        } finally {
            changeIsolatedName(original);
        }
        assertEquals(2, facts.read(factsAccess(), List.of(asset, unrecordedAsset)).items().size());
    }

    /** Deliberate inconsistency only in the freshly isolated test asset, with an owned transaction and audit. */
    static void changeIsolatedName(String name) {
        var access = metadataAccess();
        platform.idempotentAuthorized(actor, tenant, "PATCH /integration/ledger-facts/core-drift/" + asset,
                UUID.randomUUID().toString(), "c".repeat(64), access.permissionVersion(),
                () -> metadata.authorizeCurrentAsset(access, asset), () -> {
                    try (var statement = platform.idempotentCommandConnection().prepareStatement(
                            "UPDATE public.mop_asset SET name=?,version=version+1,updated_at=statement_timestamp(),updated_by=?::uuid "
                                    + "WHERE tenant_id=?::uuid AND id=?::uuid AND source='manual'")) {
                        statement.setString(1, name);
                        statement.setString(2, actor);
                        statement.setString(3, tenant);
                        statement.setString(4, asset);
                        assertEquals(1, statement.executeUpdate());
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException("Isolated core-drift preparation failed", failure);
                    }
                    platform.audit(actor, tenant, "integration.ledger_facts.core_drift", "asset", asset,
                            "SUCCEEDED", "SYNTHETIC_ISOLATED_ONLY", access.traceId());
                    return Map.of("recorded", true);
                });
    }

    @Test
    @Order(4)
    void concurrentMetadataChangeCannotMixTheAssetRevisionOwnerAndObservationSnapshot() {
        var original = platform.find(tenant, asset).orElseThrow();
        var injected = new AtomicBoolean();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, pool, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (cp, cm, ca) -> {
                        Object value = invoke(cm, connection, ca);
                        if (!cm.getName().equals("prepareStatement") || !ca[0].toString().contains("AS metadata_management_ip")) {
                            return value;
                        }
                        assertTrue(connection.isReadOnly());
                        assertFalse(connection.getAutoCommit());
                        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (sp, sm, sa) -> {
                            Object rows = invoke(sm, statement, sa);
                            if (sm.getName().equals("executeQuery") && injected.compareAndSet(false, true)) {
                                updateMetadata(original, "SYNTHETIC_CONCURRENT_PUBLIC_DECLARATION");
                            }
                            return rows;
                        });
                    });
                });
        var snapshot = new PostgresAssetLedgerFactsRepository(wrapped).read(factsAccess(), List.of(asset));
        assertTrue(injected.get());
        assertEquals(original.version(), snapshot.items().get(0).assetVersion());
        assertEquals(2L, snapshot.items().get(0).metadataVersion());
        assertEquals("SYNTHETIC_PUBLIC_BUSINESS_DECLARATION", snapshot.items().get(0).businessDescription());
        assertFalse(snapshot.items().get(0).metadataRecordedAt().isAfter(snapshot.observedAt()));
        var current = facts.read(factsAccess(), List.of(asset)).items().get(0);
        assertEquals(original.version() + 1, current.assetVersion());
        assertEquals(current.assetVersion(), current.metadataVersion());
        assertEquals("SYNTHETIC_CONCURRENT_PUBLIC_DECLARATION", current.businessDescription());
    }
}
