package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.dashboard.DashboardAvailabilityReader;
import cn.mop.domain.dashboard.DashboardAvailabilityReader.Access;
import cn.mop.domain.dashboard.DashboardAvailabilitySnapshot;
import cn.mop.domain.dashboard.DashboardAvailabilitySnapshot.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

/** Only authorization and database time are available. No monitor samples are fabricated. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Profile("remote_test")
public final class DashboardController {
    private final PlatformRepository platform;
    private final DashboardAvailabilityReader reader;
    public DashboardController(PlatformRepository platform, DashboardAvailabilityReader reader) {
        this.platform=platform;
        this.reader=reader;
    }

    @GetMapping Object read(HttpServletRequest request) {
        Access access=access(request);
        TrendWindow window=window(request);
        DashboardAvailabilitySnapshot snapshot=reader.read(access,window);
        if (snapshot==null || !access.actorId().equals(snapshot.actorId().toString())
                || !access.tenantId().equals(snapshot.tenantId().toString())
                || access.permissionVersion()!=snapshot.permissionVersion()
                || snapshot.requestedWindow()!=window) throw unavailable();
        return success(dto(snapshot),request);
    }

    private Access access(HttpServletRequest request) {
        if (request.getUserPrincipal()==null || request.getSession(false)==null) throw expired();
        Object selected=request.getSession(false).getAttribute("mop.tenant_id");
        String actor=request.getUserPrincipal().getName();
        if (!(selected instanceof String tenant) || !uuid(tenant) || !uuid(actor)) throw expired();
        Membership membership=platform.membership(actor,tenant).orElseThrow(DashboardController::expired);
        if (!membership.active() || !actor.equals(membership.userId()) || !tenant.equals(membership.tenantId())
                || membership.permissionVersion()<1 || membership.permissionVersion()>9_007_199_254_740_991L) throw expired();
        if (!membership.capabilities().contains("dashboard:read")) {
            throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能读取本租户运营首页");
        }
        return new Access(actor,tenant,membership.permissionVersion());
    }

    private static TrendWindow window(HttpServletRequest request) {
        if (request.getQueryString()!=null && request.getQueryString().getBytes(StandardCharsets.UTF_8).length>2048) throw invalid();
        var parameters=request.getParameterMap();
        for (var entry:parameters.entrySet()) {
            if (!entry.getKey().equals("window") || entry.getValue()==null || entry.getValue().length!=1
                    || entry.getValue()[0]==null || entry.getValue()[0].length()>16) throw invalid();
        }
        String value=parameters.containsKey("window")?parameters.get("window")[0]:"HOURS_24";
        try { return TrendWindow.valueOf(value); } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    static Map<String,Object> dto(DashboardAvailabilitySnapshot snapshot) {
        return map("tenant_id",snapshot.tenantId().toString(),"actor_id",snapshot.actorId().toString(),
                "permission_version",snapshot.permissionVersion(),"generated_at",snapshot.generatedAt().toString(),
                "window",snapshot.requestedWindow().name(),"timezone",snapshot.timezone(),
                "trend_period",period(snapshot.trendPeriod()),"last_24_hours",period(snapshot.last24Hours()),
                "month_start",snapshot.monthStart().toString(),"month_window_empty",snapshot.monthWindowEmpty(),
                "layout_version",snapshot.layoutVersion(),"layout_customization_allowed",false,
                "contract_refresh_seconds",snapshot.contractRefreshSeconds(),
                "kpis",snapshot.kpis().stream().map(DashboardController::kpi).toList(),
                "panels",snapshot.panels().stream().map(DashboardController::panel).toList(),
                "metric_samples_loaded",false,"clock_accuracy_verified",false,"source",snapshot.source(),
                "scope",snapshot.scope(),"formal_metric_status",snapshot.formalMetricStatus(),"writes_performed",false);
    }
    private static Object period(Period period) {
        return period==null?null:map("from",period.from().toString(),"to",period.to().toString());
    }
    private static Object kpi(Kpi kpi) {
        return map("id",kpi.id().name(),"unit",kpi.unit(),"period",period(kpi.period()),
                "value",null,"sample_count",null,"sampled_at",null,"metric_version",null,
                "threshold_breached",null,"drilldown_path",null,"state",kpi.state(),
                "reader_status",kpi.readerStatus(),"unavailable_reason",kpi.unavailableReason(),
                "detail_counts",kpi.detailCounts());
    }
    private static Object panel(Panel panel) {
        return map("id",panel.id().name(),"period",period(panel.period()),"contract_item_limit",panel.contractItemLimit(),
                "items",null,"sample_count",null,"sampled_at",null,"metric_version",null,
                "state",panel.state(),"reader_status",panel.readerStatus(),"unavailable_reason",panel.unavailableReason());
    }
    private static boolean uuid(String value) { return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static DomainException expired() { return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效"); }
    private static DomainException invalid() { return new DomainException(400,"VALIDATION_ERROR","首页只接受单个window预设；租户、身份和观察时刻由当前授权数据库确定"); }
    private static DomainException unavailable() { return new DomainException(503,"UPSTREAM_UNAVAILABLE","首页投影未通过当前授权范围核对；未返回指标"); }
}
