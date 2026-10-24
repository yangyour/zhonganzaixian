package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationRoutingValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode pending() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"published":false,"resource_pool_id":null,"matrix":null,"aggregate_seconds":null,
                 "precedence":null,"retry_budget":null,"retry_delay_seconds":null}
                """);
    }

    private ObjectNode complete() throws Exception {
        var value = pending();
        value.put("published", true);
        value.put("resource_pool_id", UUID.randomUUID().toString());
        value.set("matrix", mapper.readTree("{\"P1\":[\"EMAIL\"],\"P2\":[\"EMAIL\"],\"P3\":[],\"P4\":[]}"));
        value.put("aggregate_seconds", 0);
        value.put("precedence", "MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS");
        value.put("retry_budget", 0);
        value.put("retry_delay_seconds", 60);
        return value;
    }

    @Test
    void confirmedP2ImmediateRuleCannotBeOverriddenByPublishedAggregation() {
        var priority = cn.mop.domain.notification.NotificationRouter.Priority.class;
        assertEquals(java.time.Duration.ZERO, NotificationRoutingService.aggregateWait(Enum.valueOf(priority,"P1"),300));
        assertEquals(java.time.Duration.ZERO, NotificationRoutingService.aggregateWait(Enum.valueOf(priority,"P2"),300));
        assertEquals(java.time.Duration.ofSeconds(300), NotificationRoutingService.aggregateWait(Enum.valueOf(priority,"P3"),300));
    }

    @Test
    void unknownDraftValuesRemainNullWithoutInventedDefaults() throws Exception {
        var value = pending();
        var before = value.deepCopy();
        NotificationRoutingService.validatePolicy(value);
        assertEquals(before, value);
        value.put("published", true);
        assertEquals(409, assertThrows(DomainException.class, () -> NotificationRoutingService.validatePolicy(value)).status);
    }

    @Test
    void explicitZeroRetryAndEmptyRecordOnlyChannelsAreValid() throws Exception {
        NotificationRoutingService.validatePolicy(complete());
    }

    @Test
    void channelDuplicatesUnknownChannelsAndMissingPrioritiesAreRejected() throws Exception {
        for (String matrix : new String[]{"{\"P1\":[],\"P2\":[],\"P3\":[]}",
                "{\"P1\":[\"EMAIL\",\"EMAIL\"],\"P2\":[],\"P3\":[],\"P4\":[]}",
                "{\"P1\":[\"UNCONFIRMED\"],\"P2\":[],\"P3\":[],\"P4\":[]}"}) {
            var value = complete();
            value.set("matrix", mapper.readTree(matrix));
            assertEquals(400, assertThrows(DomainException.class, () -> NotificationRoutingService.validatePolicy(value)).status);
        }
    }

    @Test
    void operationalBoundsAndStrictIntegralValuesAreEnforced() throws Exception {
        for (String field : new String[]{"aggregate_seconds", "retry_budget", "retry_delay_seconds"}) {
            var value = complete();
            value.put(field, -1);
            assertThrows(DomainException.class, () -> NotificationRoutingService.validatePolicy(value));
            value.put(field, 1.0);
            assertThrows(DomainException.class, () -> NotificationRoutingService.validatePolicy(value));
            value.put(field, Integer.MAX_VALUE);
            assertThrows(DomainException.class, () -> NotificationRoutingService.validatePolicy(value));
        }
    }

    @Test
    void credentialsAndCallerDispatchFlagsCannotBeSmuggledIntoPolicy() throws Exception {
        for (String field : new String[]{"password", "external_dispatch_enabled", "customer_production_approved", "tenant_id"}) {
            var value = complete();
            value.put(field, "SYNTHETIC_REJECTED");
            assertEquals(400, assertThrows(DomainException.class, () -> NotificationRoutingService.validatePolicy(value)).status);
        }
    }

    @Test
    void canonicalStorageHashSurvivesJsonbKeyOrder() throws Exception {
        var source = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (proxy, method, args) -> { throw new AssertionError("No database needed for canonical encoding"); });
        var store = new NotificationControlStore(source, mapper);
        assertEquals(store.encode(mapper.readTree("{\"z\":{\"b\":1,\"a\":2},\"x\":null}")),
                store.encode(mapper.readTree("{\"x\":null,\"z\":{\"a\":2,\"b\":1}}")));
    }

    @Test
    void migrationIsPinnedAndExplicitlyRequiresThePreviousSchema() throws Exception {
        NotificationControlSchemaVerifier.resource();
        var mode = TestSchemaMigration.requestedMode(new String[]{"--apply-notification-control"});
        assertTrue(TestSchemaMigration.shouldMigrate(mode, TestSchemaMigration.Plan.DELIVERY_RECEIPT_JOURNAL_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(mode, TestSchemaMigration.Plan.NOTIFICATION_CONTROL_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.READ_ONLY_CHECK, TestSchemaMigration.Plan.NOTIFICATION_CONTROL_APPLIED));
        assertThrows(IllegalStateException.class, () -> TestSchemaMigration.shouldMigrate(mode, TestSchemaMigration.Plan.BACKUP_VERSION_REGISTRATION_APPLIED));
    }
}
