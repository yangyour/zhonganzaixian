package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Controller unit tests for MonitoringEventCenterController (V37 D15).
 */
class MonitoringEventCenterControllerTest {
    private static final String TENANT = "55000000-0000-4000-8000-000000000001";
    private static final String ASSET = "66000000-0000-4000-8000-000000000001";
    private static final String EVENT_ID = "77000000-0000-4000-8000-000000000001";
    private static final String WINDOW_ID = "88000000-0000-4000-8000-000000000001";
    private static final String ATTEMPT_ID = "99000000-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static final String padmin = "55000000-0000-4000-8000-000000000011";
    private static final String msp = "55000000-0000-4000-8000-000000000012";
    private static final String viewer = "55000000-0000-4000-8000-000000000013";

    private PlatformRepository platform;

    @BeforeEach
    void setup() {
        platform = mock(PlatformRepository.class);
        when(platform.membership(padmin, TENANT)).thenReturn(Optional.of(new Membership(padmin, TENANT, "padmin", 3, true)));
        when(platform.membership(msp, TENANT)).thenReturn(Optional.of(new Membership(msp, TENANT, "msp", 3, true)));
        when(platform.membership(viewer, TENANT)).thenReturn(Optional.of(new Membership(viewer, TENANT, "viewer", 3, true)));
        when(platform.idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(x -> {
            ((Runnable) x.getArgument(6)).run();
            return ((Supplier<?>) x.getArgument(7)).get();
        });
    }

    private MockHttpServletRequest req(String method, String uri, String actor, String body) {
        var r = new MockHttpServletRequest(method, uri);
        r.setUserPrincipal(() -> actor);
        r.getSession().setAttribute("mop.tenant_id", TENANT);
        r.setAttribute("mop.trace_id", "trace-v37");
        if (body != null) {
            r.setContentType("application/json");
            r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            r.addHeader("Idempotency-Key", "idem-" + UUID.randomUUID());
        }
        return r;
    }

    private MonitoringEventCenterController controller() {
        return new MonitoringEventCenterController(platform, new MonitoringEventCenterRepository() {
            @Override public EventCenterWorkspace workspace(Access a, String window, int pageSize) {
                return new EventCenterWorkspace(
                    a.tenantId(),
                    List.of(sampleEvent(a.actorId())),
                    new NoiseFunnelView(a.tenantId(), window, 10, 5, 1, 1, 1, 2, NOW),
                    List.of(sampleWindow(a.actorId(), false)),
                    List.of(),
                    List.of(sampleAttempt(a.actorId())),
                    NOW
                );
            }
            @Override public EventDetailView eventDetail(Access a, String eventId) {
                return new EventDetailView(
                    sampleEvent(a.actorId()),
                    List.of(new EventMember(UUID.randomUUID().toString(), a.tenantId(), eventId, null, 1, "src-1", MonitorState.PROBLEM, NOW, false, NOW)),
                    List.of(new NoiseRecord(UUID.randomUUID().toString(), a.tenantId(), eventId, NoiseLayer.RAW_INTAKE, "原始事件到达", NOW)),
                    Optional.of(sampleAttempt(a.actorId())),
                    NOW
                );
            }
            @Override public NoiseFunnelView noiseFunnel(Access a, String window) {
                return new NoiseFunnelView(a.tenantId(), window, 10, 5, 1, 1, 1, 2, NOW);
            }
            @Override public void authorizeWrite(Access a) {}
            @Override public void authorizeAdminWrite(Access a) {}
            @Override public EventSummary ingestEvent(Access a, IngestEventCommand c) { return sampleEvent(a.actorId()); }
            @Override public EventSummary transitionEvent(Access a, TransitionEventCommand c) { return sampleEvent(a.actorId()); }
            @Override public MaintenanceWindow createMaintenanceWindow(Access a, CreateMaintenanceWindowCommand c) { return sampleWindow(a.actorId(), false); }
            @Override public MaintenanceWindow updateMaintenanceWindow(Access a, UpdateMaintenanceWindowCommand c) { return sampleWindow(a.actorId(), "CONFIRM_SLA_EXCLUSION".equals(c.action())); }
            @Override public QuarantineItem resolveQuarantine(Access a, QuarantineDecisionCommand c) {
                return new QuarantineItem(c.quarantineId(), UUID.randomUUID().toString(), "src-q", "PROBLEM", a.tenantId(), c.boundAssetId(), "UNKNOWN_MAPPING", NOW, NOW, "HELD_UNVERIFIED", c.decision(), c.boundAssetId(), EVENT_ID, c.reason(), a.actorId(), NOW);
            }
            @Override public AutoTicketAttempt manualCreateAutoTicket(Access a, ManualAutoTicketCommand c) { return sampleAttempt(a.actorId()); }
            @Override public List<String> readinessProblems() { return List.of(); }
        });
    }

    private static EventSummary sampleEvent(String actor) {
        return new EventSummary(
            EVENT_ID, TENANT, ASSET, 1L, "LINK_DOWN", "核心上联端口中断", Severity.DISASTER, 5,
            "FP_V1|" + TENANT + "|" + ASSET + "|LINK_DOWN", 5, 0, null,
            MonitorState.PROBLEM, HandlingState.NEW, SuppressionState.NONE, null,
            NotificationEmptyState.NORMAL_DISPATCH_ELIGIBLE, NotificationEmptyState.NORMAL_DISPATCH_ELIGIBLE.label(),
            false, "CONFIG_PENDING_Q29", NOW, NOW, null, null, null, null, null, null, actor, NOW
        );
    }

    private static MaintenanceWindow sampleWindow(String actor, boolean confirmed) {
        return new MaintenanceWindow(
            WINDOW_ID, TENANT, 1L, "核心交换机例行割接", ASSET, NOW, NOW.plusSeconds(3600),
            "SUPPRESS_NOTIFY", "ACTIVE", true, confirmed, confirmed ? actor : null, confirmed ? NOW : null,
            "版本升级维护窗口", actor, actor, NOW
        );
    }

    private static AutoTicketAttempt sampleAttempt(String actor) {
        return new AutoTicketAttempt(
            ATTEMPT_ID, TENANT, EVENT_ID, 1L, 5, 5, "30,120,600,600,600",
            AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED, "P1", "CONFIG_PENDING_Q14",
            null, null, "连续重试5次失败", actor, NOW
        );
    }

    @Test void workspaceAndDetailEnforceInternalRoleAndReturnStructuredData() {
        var ctrl = controller();
        assertEquals(403, assertThrows(DomainException.class, () -> ctrl.workspace(req("GET", "/api/v1/events", viewer, null))).status);
        @SuppressWarnings("unchecked")
        var ws = (Map<String, Object>) ((Map<String, Object>) ctrl.workspace(req("GET", "/api/v1/events", msp, null))).get("data");
        assertEquals(TENANT, ws.get("tenant_id"));
        assertEquals(1, ((List<?>) ws.get("events")).size());

        @SuppressWarnings("unchecked")
        var detail = (Map<String, Object>) ((Map<String, Object>) ctrl.eventDetail(EVENT_ID, req("GET", "/api/v1/events/" + EVENT_ID, msp, null))).get("data");
        assertEquals(1, ((List<?>) detail.get("members")).size());
    }

    @Test void confirmSlaExclusionRequiresPlatformAdminRole_M4_10() {
        var ctrl = controller();
        String body = "{\"expected_version\":1,\"action\":\"CONFIRM_SLA_EXCLUSION\",\"new_ends_at\":null}";
        assertEquals(403, assertThrows(DomainException.class, () ->
            ctrl.updateMaintenanceWindow(WINDOW_ID, req("POST", "/api/v1/maintenance-windows/" + WINDOW_ID + "/actions", msp, body))).status);

        @SuppressWarnings("unchecked")
        var res = (Map<String, Object>) ((Map<String, Object>) ctrl.updateMaintenanceWindow(
            WINDOW_ID, req("POST", "/api/v1/maintenance-windows/" + WINDOW_ID + "/actions", padmin, body))).get("data");
        assertEquals(true, res.get("sla_exclusion_confirmed"));
    }
}
