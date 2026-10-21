package cn.mop.persistence;

import cn.mop.domain.CustomerReport;
import cn.mop.domain.CustomerReportRegistration;
import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import cn.mop.domain.monitoring.MonitoringEventPipeline.HandlingState;
import cn.mop.domain.monitoring.MonitoringEventPipeline.MonitorState;
import cn.mop.domain.monitoring.MonitoringEventPipeline.Severity;
import cn.mop.domain.search.GlobalSearchQuery;
import cn.mop.domain.search.GlobalSearchRepository.Access;
import cn.mop.domain.search.GlobalSearchRepository.EventHit;
import cn.mop.domain.search.GlobalSearchRepository.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in real loopback PostgreSQL checks. Uses synthetic runtime-owned fixtures only;
 * does not migrate, start services, change built-in grants, or contact providers.
 * Append-only fixtures are retained for the owner of the disposable database to clean up.
 */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_EVENT_GLOBAL_SEARCH_TESTS", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class PostgresEventGlobalSearchIntegrationTest {
    private static final String TENANT = "55000000-0000-4000-8000-000000000001";
    private static final String OTHER = "55000000-0000-4000-8000-000000000002";
    private static final Instant BASE = Instant.parse("2026-10-01T12:00:00.123Z");
    private static final List<String> SEARCH_CAPABILITIES = List.of("search:read", "assets:read", "tickets:read");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Map<String, String> ACTORS = new LinkedHashMap<>();
    private static final Map<String, String> ASSETS = new HashMap<>();
    private static HikariDataSource pool;
    private static PostgresPlatformRepository platform;
    private static PostgresGlobalSearchRepository search;
    private static PostgresMonitoringEventCenterRepository eventCenter;

    @BeforeAll static void connectAndSeed() throws SQLException {
        String url = required("SPRING_DATASOURCE_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"),
            "Event search integration tests require the approved loopback mop_test database");
        assertEquals("mop_test_runtime", required("SPRING_DATASOURCE_USERNAME"));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(required("SPRING_DATASOURCE_USERNAME"));
        config.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(6);
        config.setConnectionTimeout(5000);
        pool = new HikariDataSource(config);
        try (Connection c = pool.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT current_database(),current_user")) {
            assertTrue(r.next());
            assertEquals("mop_test", r.getString(1));
            assertEquals("mop_test_runtime", r.getString(2));
        }
        platform = new PostgresPlatformRepository(pool, MAPPER);
        search = new PostgresGlobalSearchRepository(pool);
        eventCenter = new PostgresMonitoringEventCenterRepository(pool, platform);
        for (String role : List.of("padmin", "msp", "viewer", "tadmin")) ACTORS.put(role, actor(role, true));
        for (String tenant : List.of(TENANT, OTHER)) ASSETS.put(tenant, asset(tenant, marker()));
    }

    @AfterAll static void close() { if (pool != null) pool.close(); }

    @Test void zeroMatchesIsAnAuthorizedEmptyEventGroup() {
        Snapshot result = find("msp", marker());
        assertTrue(result.eventsAuthorized());
        assertTrue(result.events().isEmpty());
        assertFalse(result.moreEvents());
        assertNotNull(result.observedAt());
        assertEquals(TENANT, result.tenantId());
    }

    @Test void oneMatchingNameOrCodeReturnsTheSameEventOnlyOnce() throws SQLException {
        String text = marker();
        SeedEvent event = event(TENANT, text + "-CODE", text + "-NAME", BASE);
        assertIds(List.of(event.id()), find("msp", text));
        assertIds(List.of(event.id()), find("msp", text + "-code"));
        Snapshot byName = find("padmin", text + "-name");
        assertIds(List.of(event.id()), byName);
        assertFalse(byName.moreEvents());
        assertEquals(text + "-name", byName.query());
    }

    @Test void exactlyFourMatchesDoesNotClaimOverflow() throws SQLException {
        String text = marker();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(event(TENANT, rule(), text + "-" + i, BASE.plusSeconds(i)).id());
        Snapshot result = find("msp", text);
        assertIds(List.of(ids.get(3), ids.get(2), ids.get(1), ids.get(0)), result);
        assertFalse(result.moreEvents());
    }

    @Test void fifthAndFurtherMatchesSetOverflowWithoutExposingMoreThanFour() throws SQLException {
        String text = marker();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(event(TENANT, rule(), text + "-" + i, BASE.plusSeconds(i)).id());
        Snapshot five = find("msp", text);
        assertIds(List.of(ids.get(4), ids.get(3), ids.get(2), ids.get(1)), five);
        assertTrue(five.moreEvents());
        for (int i = 5; i < 9; i++) ids.add(event(TENANT, rule(), text + "-" + i, BASE.plusSeconds(i)).id());
        Snapshot nine = find("msp", text);
        assertIds(List.of(ids.get(8), ids.get(7), ids.get(6), ids.get(5)), nine);
        assertTrue(nine.moreEvents());
    }

    @Test void equalTimestampsUseDescendingPostgresUuidOrderAcrossTheSignedBoundary() throws SQLException {
        String text = marker();
        List<String> ids = new ArrayList<>();
        for (char prefix : new char[]{'1', 'f', '7', '8', 'a'}) {
            String id = prefix + UUID.randomUUID().toString().substring(1);
            ids.add(insertEvent(id, TENANT, ASSETS.get(TENANT), rule(), text, BASE,
                Severity.HIGH, MonitorState.PROBLEM, HandlingState.NEW, 1, 1, marker(), null).id());
        }
        // UUID.compareTo uses signed longs; PostgreSQL UUID ordering is unsigned byte ordering.
        List<String> expected = ids.stream().sorted(Comparator.reverseOrder()).limit(4).toList();
        for (int i = 0; i < 3; i++) {
            Snapshot result = find("msp", text);
            assertIds(expected, result);
            assertTrue(result.moreEvents());
        }
    }

    @Test void eventOrderingUsesLastOccurrenceRatherThanInsertionOrUpdateOrder() throws SQLException {
        String text = marker();
        SeedEvent newest = event(TENANT, rule(), text, BASE.plusSeconds(30));
        SeedEvent oldest = event(TENANT, rule(), text, BASE);
        SeedEvent middle = event(TENANT, rule(), text, BASE.plusSeconds(10));
        exec("UPDATE public.mop_monitoring_event SET version=version+1,updated_at=? WHERE id=?::uuid",
            Timestamp.from(BASE.plusSeconds(1000)), oldest.id());
        assertIds(List.of(newest.id(), middle.id(), oldest.id()), find("msp", text));
    }

    @Test void unicodeCaseFoldingMatchesBothRuleNameAndRuleCode() throws SQLException {
        String text = marker() + "-警報-ÄΩÉ";
        SeedEvent named = event(TENANT, rule(), text, BASE);
        SeedEvent coded = event(TENANT, text, "Synthetic unrelated name", BASE.plusSeconds(1));
        assertIds(List.of(coded.id(), named.id()), find("msp", text.toLowerCase(Locale.ROOT)));
        assertIds(List.of(coded.id(), named.id()), find("padmin", text.toUpperCase(Locale.ROOT)));
    }

    @Test void percentUnderscoreAndApostropheAreLiteralInBothSearchFields() throws SQLException {
        String prefix = marker();
        String literal = prefix + "%_'";
        SeedEvent named = event(TENANT, rule(), literal, BASE);
        SeedEvent coded = event(TENANT, literal, "Synthetic unrelated name", BASE.plusSeconds(1));
        event(TENANT, prefix + "XX", prefix + "wildcard-decoy", BASE.plusSeconds(2));
        assertIds(List.of(coded.id(), named.id()), find("msp", literal));
        assertTrue(find("msp", literal + " OR '1'='1").events().isEmpty());
        assertTrue(find("msp", prefix + "%_''").events().isEmpty());
    }

    @Test void nonBlankQueriesKeepLeadingAndTrailingSpaces() throws SQLException {
        String text = marker();
        SeedEvent padded = event(TENANT, rule(), "prefix " + text + " suffix", BASE);
        event(TENANT, rule(), text + "-decoy", BASE.plusSeconds(1));
        Snapshot result = find("msp", " " + text + " ");
        assertIds(List.of(padded.id()), result);
        assertEquals(" " + text + " ", result.query());
        assertTrue(find("msp", "  " + text + "  ").events().isEmpty());
    }

    @Test void evenAnAdministratorWithBothMembershipsCannotMixTenants() throws SQLException {
        String text = marker();
        SeedEvent local = event(TENANT, text, text, BASE);
        SeedEvent foreign = event(OTHER, text, text, BASE.plusSeconds(100));
        for (String role : List.of("msp", "padmin")) {
            assertIds(List.of(local.id()), find(role, text));
            Snapshot reverse = search.search(access(ACTORS.get(role), OTHER), new GlobalSearchQuery(text));
            assertIds(List.of(foreign.id()), reverse);
            assertEquals(OTHER, reverse.events().get(0).tenantId());
        }
    }

    @Test void customerRolesKeepAssetsAndOwnTicketsButNeverReadTheEventTable() throws Exception {
        String text = marker();
        String asset = asset(TENANT, text);
        SeedEvent event = event(TENANT, rule(), text, BASE);
        String viewerTicket = report(TENANT, ACTORS.get("viewer"), text + "-viewer");
        String adminTicket = report(TENANT, ACTORS.get("tadmin"), text + "-tadmin");
        for (String role : List.of("viewer", "tadmin", "msp", "padmin")) {
            Probe probe = new Probe(null);
            Snapshot result = new PostgresGlobalSearchRepository(probe.source())
                .search(access(ACTORS.get(role), TENANT), new GlobalSearchQuery(text));
            assertEquals(List.of(asset), result.assets().stream().map(a -> a.id()).toList());
            if (Set.of("viewer", "tadmin").contains(role)) {
                assertFalse(result.eventsAuthorized());
                assertTrue(result.events().isEmpty());
                assertFalse(result.moreEvents());
                assertEquals(0, probe.eventQueries.get(), "Customer roles must not query events");
                assertEquals(List.of(role.equals("viewer") ? viewerTicket : adminTicket),
                    result.tickets().stream().map(t -> t.id()).toList());
                assertEquals("OWN_REPORT_REGISTRATIONS", result.ticketVisibility());
            } else {
                assertTrue(result.eventsAuthorized());
                assertIds(List.of(event.id()), result);
                assertEquals(Set.of(viewerTicket, adminTicket),
                    new HashSet<>(result.tickets().stream().map(t -> t.id()).toList()));
                assertEquals("CURRENT_TENANT_INTERNAL", result.ticketVisibility());
                assertEquals(1, probe.eventQueries.get());
            }
        }
    }

    @Test void actualEventSelectIsReadOnlyRepeatableReadAndBoundedToFiveRows() throws SQLException {
        String text = marker();
        for (int i = 0; i < 8; i++) event(TENANT, rule(), text, BASE.plusSeconds(i));
        Probe probe = new Probe(null);
        Snapshot result = new PostgresGlobalSearchRepository(probe.source())
            .search(access(ACTORS.get("msp"), TENANT), new GlobalSearchQuery(text));
        assertEquals(4, result.events().size());
        assertTrue(result.moreEvents());
        assertEquals(1, probe.eventQueries.get());
        assertEquals(List.of(5), probe.eventLimits);
    }

    @Test void staleVersionsUnknownActorsAndMissingMembershipFailBeforeEventReads() throws SQLException {
        Access current = access(ACTORS.get("msp"), TENANT);
        String onlyLocal = actor("msp", false);
        for (Access invalid : List.of(
            new Access(current.actorId(), TENANT, current.permissionVersion() + 1),
            new Access(UUID.randomUUID().toString(), TENANT, 1),
            new Access(onlyLocal, OTHER, 1))) {
            Probe probe = new Probe(null);
            assertEquals(401, assertThrows(DomainException.class, () ->
                new PostgresGlobalSearchRepository(probe.source()).search(invalid, new GlobalSearchQuery(marker()))).status);
            assertEquals(0, probe.eventQueries.get());
        }
    }

    @Test void inactiveUserRejectsEvenAnOtherwiseCurrentActiveMembership() throws SQLException {
        String actor = actor("msp", false);
        Access current = access(actor, TENANT);
        try {
            exec("UPDATE public.mop_user SET active=false WHERE id=?::uuid", actor);
            assertEquals(401, assertThrows(DomainException.class,
                () -> search.search(current, new GlobalSearchQuery(marker()))).status);
        } finally {
            exec("UPDATE public.mop_user SET active=true WHERE id=?::uuid", actor);
        }
        assertTrue(search.search(access(actor, TENANT), new GlobalSearchQuery(marker())).eventsAuthorized());
    }

    @Test void revokedMembershipRejectsStaleAndCurrentVersions() throws SQLException {
        String actor = actor("msp", false);
        Access old = access(actor, TENANT);
        try {
            exec("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid", TENANT, actor);
            assertEquals(401, assertThrows(DomainException.class,
                () -> search.search(old, new GlobalSearchQuery(marker()))).status);
            Access inactive = new Access(actor, TENANT, old.permissionVersion() + 1);
            assertEquals(401, assertThrows(DomainException.class,
                () -> search.search(inactive, new GlobalSearchQuery(marker()))).status);
        } finally {
            exec("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid", TENANT, actor);
        }
    }

    @Test void eachRequiredEffectiveCapabilityIsEnforcedForCustomInternalRoles() throws SQLException {
        String text = marker();
        SeedEvent event = event(TENANT, rule(), text, BASE);
        for (String baseRole : List.of("msp", "padmin")) {
            CustomActor custom = customActor(baseRole);
            try {
                assertIds(List.of(event.id()), search.search(access(custom.actor(), TENANT), new GlobalSearchQuery(text)));
                for (String revoked : SEARCH_CAPABILITIES) {
                    Access old = access(custom.actor(), TENANT);
                    permissions(custom.role(), SEARCH_CAPABILITIES.stream().filter(p -> !p.equals(revoked)).toList(), true);
                    assertEquals(401, assertThrows(DomainException.class,
                        () -> search.search(old, new GlobalSearchQuery(text))).status, revoked);
                    Access fresh = access(custom.actor(), TENANT);
                    assertEquals(403, assertThrows(DomainException.class,
                        () -> search.search(fresh, new GlobalSearchQuery(text))).status, revoked);
                    permissions(custom.role(), SEARCH_CAPABILITIES, true);
                    assertIds(List.of(event.id()), search.search(access(custom.actor(), TENANT), new GlobalSearchQuery(text)));
                }
            } finally {
                permissions(custom.role(), SEARCH_CAPABILITIES, true);
            }
        }
    }

    @Test void disabledCustomRoleCannotFallBackToItsBuiltinRole() throws SQLException {
        CustomActor custom = customActor("msp");
        Access old = access(custom.actor(), TENANT);
        try {
            permissions(custom.role(), SEARCH_CAPABILITIES, false);
            assertEquals(401, assertThrows(DomainException.class,
                () -> search.search(old, new GlobalSearchQuery(marker()))).status);
            Access fresh = access(custom.actor(), TENANT);
            assertEquals(403, assertThrows(DomainException.class,
                () -> search.search(fresh, new GlobalSearchQuery(marker()))).status);
        } finally {
            permissions(custom.role(), SEARCH_CAPABILITIES, true);
        }
    }

    @Test void privateFieldsAndRawPayloadDoNotBecomeSearchableOrAppearInProjection() throws Exception {
        String text = marker(), hidden = marker();
        String linkedAsset = asset(TENANT, hidden + "-asset");
        String ticket = report(TENANT, ACTORS.get("viewer"), hidden + "-ticket");
        SeedEvent event = insertEvent(UUID.randomUUID().toString(), TENANT, linkedAsset, rule(), text, BASE,
            Severity.HIGH, MonitorState.PROBLEM, HandlingState.NEW, 2, 3, hidden, ticket);
        seedPrivateEventChildren(event, hidden);
        for (String query : List.of(hidden, hidden + "-root", hidden + "-suppression", hidden + "-payload",
            hidden + "-source", hidden + "-noise", hidden + "-error", linkedAsset, ticket, ACTORS.get("padmin"))) {
            assertTrue(find("msp", query).events().isEmpty(), "Private event field matched: " + query);
        }
        EventHit hit = find("msp", text).events().get(0);
        JsonNode projection = MAPPER.valueToTree(hit);
        Set<String> fields = new HashSet<>();
        projection.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "tenantId", "ruleCode", "ruleName", "version", "severity", "monitorState",
            "handlingState", "occurrenceCount", "lastOccurredAt"), fields);
        assertFalse(projection.toString().contains(hidden));
        assertFalse(projection.toString().contains(linkedAsset));
        assertFalse(projection.toString().contains(ticket));
        assertEquals(event.id(), hit.id());
        assertEquals(2, hit.version());
        assertEquals(3, hit.occurrenceCount());
    }

    @Test void allLegalSeverityAndHandlingValuesAndBothMonitorStatesRoundTrip() throws SQLException {
        Set<Severity> severities = new HashSet<>();
        Set<MonitorState> monitors = new HashSet<>();
        Set<HandlingState> handling = new HashSet<>();
        for (int i = 0; i < HandlingState.values().length; i++) {
            String text = marker();
            Severity severity = Severity.values()[i % Severity.values().length];
            MonitorState monitor = i < 3 ? MonitorState.PROBLEM : MonitorState.RECOVERED;
            HandlingState state = HandlingState.values()[i];
            SeedEvent seed = insertEvent(UUID.randomUUID().toString(), TENANT, ASSETS.get(TENANT), rule(), text,
                BASE.plusMillis(i), severity, monitor, state, i + 1, i + 2, marker(), null);
            EventHit hit = find("msp", text).events().get(0);
            assertEquals(seed.id(), hit.id());
            assertEquals(severity, hit.severity());
            assertEquals(monitor, hit.monitorState());
            assertEquals(state, hit.handlingState());
            assertEquals(i + 1, hit.version());
            assertEquals(i + 2, hit.occurrenceCount());
            assertEquals(BASE.plusMillis(i), hit.lastOccurredAt());
            severities.add(hit.severity()); monitors.add(hit.monitorState()); handling.add(hit.handlingState());
        }
        assertEquals(Set.of(Severity.values()), severities);
        assertEquals(Set.of(MonitorState.values()), monitors);
        assertEquals(Set.of(HandlingState.values()), handling);
    }

    @Test void searchesDoNotChangeAuditReceiptsInboxOrAnyEventRecord() throws Exception {
        String text = marker();
        SeedEvent event = event(TENANT, rule(), text, BASE);
        seedPrivateEventChildren(event, marker());
        asset(TENANT, text);
        report(TENANT, ACTORS.get("viewer"), text);
        Map<String, String> before = fingerprints();
        for (String role : ACTORS.keySet()) {
            find(role, text);
            find(role, marker());
        }
        assertEquals(before, fingerprints(), "Search must not mutate persisted business rows or append read receipts");
    }

    @Test void authorizationAssetsTicketsAndEventsShareOneRealDatabaseSnapshot() throws Exception {
        String text = marker();
        String originalAsset = asset(TENANT, text + "-before");
        String originalTicket = report(TENANT, ACTORS.get("viewer"), text + "-before");
        SeedEvent originalEvent = event(TENANT, rule(), text, BASE);
        AtomicBoolean writerCommitted = new AtomicBoolean();
        Probe probe = new Probe(() -> concurrent(() -> {
            try (Connection c = pool.getConnection()) {
                c.setAutoCommit(false);
                exec(c, "UPDATE public.mop_monitoring_event SET version=version+1,occurrence_count=occurrence_count+1,last_occurred_at=? WHERE id=?::uuid",
                    Timestamp.from(BASE.plusSeconds(60)), originalEvent.id());
                exec(c, "UPDATE public.mop_asset SET name=?,version=version+1 WHERE id=?::uuid", text + "-after", originalAsset);
                c.commit();
            }
            asset(TENANT, text + "-concurrent");
            report(TENANT, ACTORS.get("viewer"), text + "-concurrent");
            event(TENANT, rule(), text, BASE.plusSeconds(90));
            writerCommitted.set(true);
        }));
        Snapshot during = new PostgresGlobalSearchRepository(probe.source())
            .search(access(ACTORS.get("msp"), TENANT), new GlobalSearchQuery(text));
        assertTrue(probe.hooked.get());
        assertTrue(writerCommitted.get());
        assertEquals(List.of(originalAsset), during.assets().stream().map(a -> a.id()).toList());
        assertEquals(text + "-before", during.assets().get(0).name());
        assertEquals(1, during.assets().get(0).version());
        assertEquals(List.of(originalTicket), during.tickets().stream().map(t -> t.id()).toList());
        assertIds(List.of(originalEvent.id()), during);
        assertEquals(1, during.events().get(0).version());
        assertEquals(1, during.events().get(0).occurrenceCount());
        assertEquals(BASE, during.events().get(0).lastOccurredAt());
        Snapshot after = find("msp", text);
        assertEquals(2, after.assets().size());
        assertEquals(2, after.tickets().size());
        assertEquals(2, after.events().size());
        assertEquals(2, after.events().stream().filter(e -> e.id().equals(originalEvent.id())).findFirst().orElseThrow().version());
    }

    @Test void concurrentCustomGrantRevocationAffectsTheNextSnapshotWithoutSplittingTheCurrentOne() throws Exception {
        String text = marker();
        SeedEvent event = event(TENANT, rule(), text, BASE);
        CustomActor custom = customActor("msp");
        Access old = access(custom.actor(), TENANT);
        Probe probe = new Probe(() -> concurrent(() -> permissions(custom.role(), List.of("search:read", "assets:read"), true)));
        try {
            Snapshot current = new PostgresGlobalSearchRepository(probe.source()).search(old, new GlobalSearchQuery(text));
            assertTrue(probe.hooked.get());
            assertTrue(current.eventsAuthorized());
            assertIds(List.of(event.id()), current);
            assertEquals(401, assertThrows(DomainException.class, () -> search.search(old, new GlobalSearchQuery(text))).status);
            Access fresh = access(custom.actor(), TENANT);
            assertEquals(403, assertThrows(DomainException.class, () -> search.search(fresh, new GlobalSearchQuery(text))).status);
        } finally {
            permissions(custom.role(), SEARCH_CAPABILITIES, true);
        }
    }

    @Test void searchHitOutsideLatestTwentyFiveStillHasDirectAuthorizedDetail() throws SQLException {
        String text = marker();
        SeedEvent old = event(TENANT, rule(), text, Instant.parse("2000-01-01T00:00:00Z"));
        for (int i = 0; i < 26; i++) event(TENANT, rule(), marker(), BASE.plusSeconds(i));
        MonitoringEventCenterRepository.Access internal = eventAccess("msp", TENANT);
        assertFalse(eventCenter.workspace(internal, "24h", 25).events().stream().anyMatch(e -> e.id().equals(old.id())));
        assertIds(List.of(old.id()), find("msp", text));
        assertEquals(old.id(), eventCenter.eventDetail(internal, old.id()).event().id());
        assertEquals(404, assertThrows(DomainException.class,
            () -> eventCenter.eventDetail(eventAccess("msp", OTHER), old.id())).status);
        for (String role : List.of("viewer", "tadmin")) assertEquals(403, assertThrows(DomainException.class,
            () -> eventCenter.eventDetail(eventAccess(role, TENANT), old.id())).status);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing private test setting " + name);
        return value;
    }

    private static String marker() { return "MOP-FAKE-EVS-" + UUID.randomUUID().toString().substring(0, 12); }
    private static String rule() { return "EVS_RULE_" + UUID.randomUUID().toString().substring(0, 12); }

    private static String actor(String role, boolean bothTenants) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            exec(c, "INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES (?::uuid,'test_event_search',?,?,?)",
                id, "MOP-FAKE-EVS-" + id, "MOP-FAKE event search " + role + " " + id, role);
            for (String tenant : bothTenants ? List.of(TENANT, OTHER) : List.of(TENANT))
                exec(c, "INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES (?::uuid,?::uuid,?)", tenant, id, role);
            c.commit();
        }
        return id;
    }

    private static String asset(String tenant, String name) throws SQLException {
        String id = UUID.randomUUID().toString();
        exec("INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,source,created_by,updated_by) VALUES (?::uuid,?::uuid,?,'router','192.0.2.95',?,'manual',?::uuid,?::uuid)",
            id, tenant, name, tenant.equals(TENANT) ? "site-a-main" : "site-b-main", ACTORS.get("padmin"), ACTORS.get("padmin"));
        return id;
    }

    private static String report(String tenant, String actor, String title) throws SQLException {
        String key = UUID.randomUUID().toString();
        // Use a distinct asset so the real 30-second customer-report deduplication cannot reuse a previous fixture.
        String reportAsset = asset(tenant, marker());
        CustomerReportRegistration result = (CustomerReportRegistration) platform.idempotent(actor, tenant,
            "POST /integration/event-search/reports", key, "b".repeat(64),
            platform.membership(actor, tenant).orElseThrow().permissionVersion(),
            () -> platform.registerCustomerReport(tenant, actor, new CustomerReport(title, "MOP-FAKE synthetic description",
                "网络不通", "company", "MOP-FAKE contact", "010-12345678", "urgent", reportAsset)));
        return result.ticketId();
    }

    private static Access access(String actor, String tenant) {
        return new Access(actor, tenant, platform.membership(actor, tenant).orElseThrow().permissionVersion());
    }

    private static MonitoringEventCenterRepository.Access eventAccess(String role, String tenant) {
        Access a = access(ACTORS.get(role), tenant);
        return new MonitoringEventCenterRepository.Access(tenant, a.actorId(), a.permissionVersion(), UUID.randomUUID().toString());
    }

    private static Snapshot find(String role, String query) {
        return search.search(access(ACTORS.get(role), TENANT), new GlobalSearchQuery(query));
    }

    private static void assertIds(List<String> expected, Snapshot snapshot) {
        assertEquals(expected, snapshot.events().stream().map(EventHit::id).toList());
        assertEquals(snapshot.events().size(), snapshot.events().stream().map(EventHit::id).distinct().count());
        assertTrue(snapshot.events().stream().allMatch(e -> e.tenantId().equals(snapshot.tenantId())));
    }

    private record SeedEvent(String id, String tenant, String asset, Instant happened) {}

    private static SeedEvent event(String tenant, String code, String name, Instant happened) throws SQLException {
        return insertEvent(UUID.randomUUID().toString(), tenant, ASSETS.get(tenant), code, name, happened,
            Severity.HIGH, MonitorState.PROBLEM, HandlingState.NEW, 1, 1, marker(), null);
    }

    private static SeedEvent insertEvent(String id, String tenant, String asset, String code, String name,
                                         Instant happened, Severity severity, MonitorState monitor, HandlingState handling,
                                         long version, int count, String hidden, String ticket) throws SQLException {
        exec("INSERT INTO public.mop_monitoring_event(id,tenant_id,asset_id,version,rule_code,rule_name,severity,severity_rank,fingerprint,occurrence_count,monitor_state,handling_state,suppression_state,suppression_reason,notification_empty_state,problem_at,last_occurred_at,recovered_at,root_cause_note,ticket_id,updated_by,updated_at) " +
                "VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,'NONE',?,'NORMAL_DISPATCH_ELIGIBLE',?,?,?,?,?::uuid,?::uuid,?)",
            id, tenant, asset, version, code, name, severity.name(), severity.rank(), hidden + "-fingerprint-" + id,
            count, monitor.name(), handling.name(), hidden + "-suppression", Timestamp.from(happened), Timestamp.from(happened),
            monitor == MonitorState.RECOVERED ? Timestamp.from(happened) : null, hidden + "-root", ticket,
            ACTORS.get("padmin"), Timestamp.from(happened));
        return new SeedEvent(id, tenant, asset, happened);
    }

    private static void seedPrivateEventChildren(SeedEvent event, String hidden) throws SQLException {
        String raw = UUID.randomUUID().toString();
        exec("INSERT INTO public.mop_raw_monitoring_event(id,pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano,received_epoch_second,received_nano,payload) VALUES (?::uuid,?::uuid,?,'PROBLEM',?::uuid,?::uuid,?,0,?,0,?)",
            raw, UUID.randomUUID().toString(), hidden + "-source", event.tenant(), event.asset(), event.happened().getEpochSecond(),
            event.happened().getEpochSecond(), ("{\"synthetic\":\"" + hidden + "-payload\"}").getBytes(StandardCharsets.UTF_8));
        exec("INSERT INTO public.mop_monitoring_event_member(id,tenant_id,event_id,raw_event_id,sequence_no,source_event_id,source_phase,occurred_at,merged_within_window,recorded_at) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1,?,'PROBLEM',?,false,?)",
            UUID.randomUUID().toString(), event.tenant(), event.id(), raw, hidden + "-source", Timestamp.from(event.happened()), Timestamp.from(event.happened()));
        exec("INSERT INTO public.mop_event_noise_record(id,tenant_id,event_id,layer,detail,recorded_at) VALUES (?::uuid,?::uuid,?::uuid,'RAW_INTAKE',?,?)",
            UUID.randomUUID().toString(), event.tenant(), event.id(), hidden + "-noise", Timestamp.from(event.happened()));
        exec("INSERT INTO public.mop_autoticket_attempt(id,tenant_id,event_id,version,attempt_count,status,last_error,updated_by,updated_at) VALUES (?::uuid,?::uuid,?::uuid,1,1,'RETRY_SCHEDULED',?,?::uuid,?)",
            UUID.randomUUID().toString(), event.tenant(), event.id(), hidden + "-error", ACTORS.get("padmin"), Timestamp.from(event.happened()));
    }

    private record CustomActor(String actor, String role) {}

    private static CustomActor customActor(String baseRole) throws SQLException {
        String actor = actor(baseRole, false), role = UUID.randomUUID().toString();
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO public.mop_project_role(id,tenant_id,name,base_role,permissions,created_by) VALUES (?::uuid,?::uuid,?,?,?,?::uuid)")) {
                s.setString(1, role); s.setString(2, TENANT); s.setString(3, "MOP-FAKE-EVS-" + role); s.setString(4, baseRole);
                s.setArray(5, c.createArrayOf("text", SEARCH_CAPABILITIES.toArray(String[]::new))); s.setString(6, ACTORS.get("padmin"));
                assertEquals(1, s.executeUpdate());
            }
            exec(c, "INSERT INTO public.mop_member_role_assignment(tenant_id,user_id,role_id) VALUES (?::uuid,?::uuid,?::uuid)", TENANT, actor, role);
            c.commit();
        }
        return new CustomActor(actor, role);
    }

    private static void permissions(String role, List<String> permissions, boolean enabled) throws SQLException {
        try (Connection c = pool.getConnection(); PreparedStatement s = c.prepareStatement(
            "UPDATE public.mop_project_role SET permissions=?,enabled=?,version=version+1,updated_at=statement_timestamp() WHERE id=?::uuid AND NOT builtin")) {
            s.setArray(1, c.createArrayOf("text", permissions.toArray(String[]::new)));
            s.setBoolean(2, enabled); s.setString(3, role);
            assertEquals(1, s.executeUpdate());
        }
    }

    private static Map<String, String> fingerprints() throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of("mop_audit", "mop_idempotency", "mop_export_inbox", "mop_notification_intent",
            "mop_notification_message", "mop_ticket", "mop_ticket_history", "mop_operation", "mop_outbox",
            "mop_ticket_workflow_clock_receipt", "mop_asset", "mop_monitoring_event", "mop_monitoring_event_member",
            "mop_event_noise_record", "mop_raw_monitoring_event", "mop_autoticket_attempt")) {
            try (Connection c = pool.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_data,E'\\n' ORDER BY row_data),'')) FROM (SELECT to_jsonb(t)::text AS row_data FROM public." + table + " t) snapshot_rows")) {
                assertTrue(r.next()); result.put(table, r.getString(1));
            }
        }
        return result;
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = pool.getConnection()) { exec(c, sql, args); }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            assertEquals(1, s.executeUpdate());
        }
    }

    @FunctionalInterface private interface SqlWork { void run() throws Exception; }

    private static void concurrent(SqlWork writer) {
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> { writer.run(); return null; }).get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Concurrent synthetic PostgreSQL writer failed", failure);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Scheduling/observation only: connections, SQL execution, and result sets remain real PostgreSQL. */
    private static final class Probe {
        final AtomicBoolean hooked = new AtomicBoolean();
        final AtomicInteger eventQueries = new AtomicInteger();
        final List<Integer> eventLimits = new ArrayList<>();
        private final Runnable afterFirstQuery;

        Probe(Runnable afterFirstQuery) { this.afterFirstQuery = afterFirstQuery; }

        DataSource source() {
            ClassLoader loader = PostgresEventGlobalSearchIntegrationTest.class.getClassLoader();
            return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                Object value = invoke(pool, method, args);
                if (!method.getName().equals("getConnection") || !(value instanceof Connection connection)) return value;
                return Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class}, (cp, cm, ca) -> {
                    Object prepared = invoke(connection, cm, ca);
                    if (!cm.getName().equals("prepareStatement") || !(prepared instanceof PreparedStatement statement)) return prepared;
                    String sql = (String) ca[0];
                    boolean events = sql.contains("FROM public.mop_monitoring_event");
                    Map<Integer, Integer> ints = new HashMap<>();
                    return Proxy.newProxyInstance(loader, new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                        if (sm.getName().equals("setInt")) ints.put((Integer) sa[0], (Integer) sa[1]);
                        Object rows = invoke(statement, sm, sa);
                        if (sm.getName().equals("executeQuery")) {
                            assertTrue(connection.isReadOnly());
                            assertFalse(connection.getAutoCommit());
                            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                            if (events) {
                                eventQueries.incrementAndGet();
                                assertTrue(sql.matches("(?s).*\\bLIMIT\\s+\\?\\s*;?\\s*"), "Event query must have a bound SQL LIMIT");
                                assertEquals(1, ints.size(), "Exactly one integer limit must be bound for event search");
                                eventLimits.addAll(ints.values());
                            }
                            // The real first SELECT has completed, so PostgreSQL has established its snapshot.
                            if (afterFirstQuery != null && hooked.compareAndSet(false, true)) afterFirstQuery.run();
                        }
                        return rows;
                    });
                });
            });
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
