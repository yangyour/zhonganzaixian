package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.domain.sla.ServiceTierRepository.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceTierControllerTest {
    static final String TENANT = "55000000-0000-4000-8000-000000000001";
    static final String ACTOR = "55000000-0000-4000-8000-000000000011";
    static final String CHANGE_ID = "55000000-0000-4000-8000-000000000099";
    static final Instant AT = Instant.parse("2026-10-18T08:00:00Z");

    PlatformRepository platform;
    ServiceTierRepository repository;
    RuntimeConfigurationRepository runtime;
    ObjectMapper mapper = new ObjectMapper();
    RuntimeConfigurationCatalog catalog = new RuntimeConfigurationCatalog(mapper);
    ServiceTierController controller;

    Snapshot snapshot(long version, Tier tier, BigDecimal target) {
        return new Snapshot(TENANT, version, tier, target, 120, BillingCycle.MONTHLY, 2500000L,
            EffectiveMode.IMMEDIATE, AT, ACTOR, AT, "UNVERIFIED");
    }

    Change changeRow(long version, Long prev, Tier from, Tier to, BigDecimal target) {
        return new Change(CHANGE_ID, TENANT, version, prev, from, to, target, 120, BillingCycle.MONTHLY, 2500000L,
            EffectiveMode.IMMEDIATE, AT, "SYNTHETIC_TIER_CHANGE", ACTOR, AT, "UNVERIFIED");
    }

    @BeforeEach
    void setup() {
        platform = mock(PlatformRepository.class);
        repository = mock(ServiceTierRepository.class);
        runtime = mock(RuntimeConfigurationRepository.class);
        controller = new ServiceTierController(platform, repository, runtime, catalog, mapper);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 5, true)));
        when(platform.idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(x -> {
            ((Runnable) x.getArgument(6)).run();
            return ((Supplier<?>) x.getArgument(7)).get();
        });
        when(runtime.read(any(), eq("sla.tier_availability_target"))).thenReturn(Map.of(
            "configuration_key", "sla.tier_availability_target",
            "tenant_id", TENANT,
            "status", "UNCONFIGURED",
            "source", "postgresql"
        ));
    }

    MockHttpServletRequest request(String method, String path, String body) {
        var r = new MockHttpServletRequest(method, "/api/v1" + path);
        r.setUserPrincipal(() -> ACTOR);
        r.getSession().setAttribute("mop.tenant_id", TENANT);
        r.setAttribute("mop.trace_id", "service-tier-test");
        r.addHeader("Idempotency-Key", "SYNTHETIC-service-tier-change");
        r.setContentType("application/json");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> data(Object value) {
        return (Map<String, Object>) ((Map<?, ?>) value).get("data");
    }

    @Test
    void getReturnsCurrentSnapshotAndAppendOnlyHistoryForInternalRoles() {
        when(repository.history(any(), eq(25), isNull())).thenReturn(new ChangePage(
            Optional.of(snapshot(2, Tier.ADVANCED, new BigDecimal("99.99"))),
            List.of(changeRow(2, 1L, Tier.STANDARD, Tier.ADVANCED, new BigDecimal("99.99"))),
            false, null, AT
        ));
        var result = data(controller.get(request("GET", "/service-tiers", "")));
        assertEquals(TENANT, result.get("tenant_id"));
        assertEquals(false, result.get("in_flight_ticket_retroactive"));
        assertEquals("UNVERIFIED", result.get("customer_approval_status"));
        var current = (Map<?, ?>) result.get("current");
        assertEquals("ADVANCED", current.get("tier"));
        assertEquals(2L, current.get("version"));
        assertEquals(new BigDecimal("99.99"), current.get("availability_target"));
        var items = (List<?>) result.get("items");
        assertEquals(1, items.size());
    }

    @Test
    void customerRolesCannotReadOrMutateServiceTiersAndMspCannotMutate() {
        for (String role : List.of("viewer", "approver", "tadmin")) {
            when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, role, 5, true)));
            assertEquals(403, assertThrows(DomainException.class, () -> controller.get(request("GET", "/service-tiers", ""))).status);
        }
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 5, true)));
        assertEquals(403, assertThrows(DomainException.class, () -> controller.change(request("POST", "/service-tiers/changes",
            "{\"expected_version\":0,\"to_tier\":\"STANDARD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":50,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":1000000,\"reason\":\"SYNTHETIC\"}"))).status);
        verify(repository, never()).applyChange(any(), any(), any());
    }

    @Test
    void changeResolvesRegisteredAvailabilityTargetAndPersistsViaIdempotentCommand() {
        when(repository.applyChange(any(), any(), eq(new BigDecimal("99.9")))).thenReturn(snapshot(1, Tier.STANDARD, new BigDecimal("99.9")));
        String body = "{\"expected_version\":0,\"to_tier\":\"STANDARD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":120,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":2500000,\"reason\":\"SYNTHETIC_TIER_CHANGE\"}";
        var result = data(controller.change(request("POST", "/service-tiers/changes", body)));
        assertEquals("STANDARD", result.get("tier"));
        assertEquals(new BigDecimal("99.9"), result.get("availability_target"));
        verify(repository).authorizeWrite(argThat(a -> a.tenantId().equals(TENANT) && a.actorId().equals(ACTOR)));
        verify(repository).applyChange(any(), argThat(c -> c.expectedVersion() == 0 && c.toTier() == Tier.STANDARD && c.effectiveMode() == EffectiveMode.IMMEDIATE), eq(new BigDecimal("99.9")));
    }

    @Test
    void disabledTargetConfigurationPersistsNullAvailabilityTargetWithoutFallback() {
        when(runtime.read(any(), eq("sla.tier_availability_target"))).thenReturn(Map.of(
            "configuration_key", "sla.tier_availability_target",
            "tenant_id", TENANT,
            "status", "DISABLED",
            "selected_release", Map.of("id", CHANGE_ID, "version", 2L, "definition_hash", catalog.hash("sla.tier_availability_target")),
            "source", "postgresql"
        ));
        when(repository.applyChange(any(), any(), isNull())).thenReturn(snapshot(1, Tier.BASIC, null));
        String body = "{\"expected_version\":0,\"to_tier\":\"BASIC\",\"effective_mode\":\"NEXT_MONTH_FIRST_DAY\",\"contract_device_count\":null,\"billing_cycle\":null,\"monthly_fee_fen\":null,\"reason\":\"SYNTHETIC_DISABLED_TARGET\"}";
        var result = data(controller.change(request("POST", "/service-tiers/changes", body)));
        assertNull(result.get("availability_target"));
        verify(repository).applyChange(any(), any(), isNull());
    }

    @Test
    void invalidChangePayloadsAndQueriesAreRejected() {
        for (String b : List.of(
            "{}",
            "{\"expected_version\":-1,\"to_tier\":\"STANDARD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":10,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":100,\"reason\":\"ok\"}",
            "{\"expected_version\":0,\"to_tier\":\"GOLD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":10,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":100,\"reason\":\"ok\"}",
            "{\"expected_version\":0,\"to_tier\":\"STANDARD\",\"effective_mode\":\"LATER\",\"contract_device_count\":10,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":100,\"reason\":\"ok\"}",
            "{\"expected_version\":0,\"to_tier\":\"STANDARD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":-1,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":100,\"reason\":\"ok\"}",
            "{\"expected_version\":0,\"to_tier\":\"STANDARD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":10,\"billing_cycle\":\"WEEKLY\",\"monthly_fee_fen\":100,\"reason\":\"ok\"}",
            "{\"expected_version\":0,\"to_tier\":\"STANDARD\",\"effective_mode\":\"IMMEDIATE\",\"contract_device_count\":10,\"billing_cycle\":\"MONTHLY\",\"monthly_fee_fen\":100,\"reason\":\"   \"}"
        )) {
            assertEquals(400, assertThrows(DomainException.class, () -> controller.change(request("POST", "/service-tiers/changes", b))).status);
        }
        var q = request("GET", "/service-tiers", "");
        q.addParameter("page_size", "101");
        assertEquals(400, assertThrows(DomainException.class, () -> controller.get(q)).status);
        var noStore = new MockHttpServletResponse();
        controller.headers(noStore);
        assertEquals("no-store", noStore.getHeader("Cache-Control"));
    }
}
