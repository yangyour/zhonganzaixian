package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated API gate tests; not HTTP filters, PostgreSQL grant loading or live revocation evidence. */
class EffectiveCapabilityGateTest {
    private static final String ACTOR="55000000-0000-4000-8000-000000000003", TENANT="55000000-0000-4000-8000-000000000001";
    private static final List<String> CONTROLLERS = List.of("ConfigurationRevisionController", "TenantInventoryController", "AssetDateAssessmentController", "AssetDateQueryController", "AssetDependencyController", "AssetExportJobController", "AssetImportController", "AssetImportTemplateController", "AuditReadController", "BackupPlanDraftController", "CapabilityCatalogController", "CapacityEstimateController", "CapacityScenarioController", "ChangeNoticeDraftController", "ConfigurationDiagnosticController", "ContactImportController", "CustomerContactController", "CustomerContactImportPreviewController", "DashboardController", "ExplicitOperationalMetricsController", "FileController", "GlobalSearchController", "InspectionSourceCollectionController", "InspectionTemplateController", "InspectionWorksheetController", "InternalTicketNoteController", "ManualAssetMetadataController", "ManualAssetQueryController", "ManualIncidentController", "ManualRecoveryController", "ManualWarrantyController", "OwnNotificationPreferenceController", "OwnWorklogController", "PlatformController", "SlaComplianceScenarioController", "ThresholdDiagnosticController", "TicketCommentController", "TicketController", "WorkingCalendarController", "WorklogController");
    @TestFactory Stream<DynamicTest> emptyEffectiveGrantsDoNotExpandFromBaseRole() {
        return CONTROLLERS.stream().flatMap(name -> cases(name).map(write -> DynamicTest.dynamicTest(name + ":" + write, () -> check(name, write, false))));
    }
    @TestFactory Stream<DynamicTest> explicitLegacyGrantsKeepExistingGateBehavior() {
        return CONTROLLERS.stream().flatMap(name -> cases(name).map(write -> DynamicTest.dynamicTest(name + ":" + write, () -> check(name, write, true))));
    }
    private static Stream<Boolean> cases(String name) {
        try {
            String gateName = Set.of("CapabilityCatalogController", "TenantInventoryController").contains(name) ? "get" : name.equals("AssetImportTemplateController") ? "download" : "access";
            Method gate = Arrays.stream(Class.forName("cn.mop.api." + name).getDeclaredMethods()).filter(m -> m.getName().equals(gateName)).findFirst().orElseThrow();
            return Arrays.asList(gate.getParameterTypes()).contains(boolean.class) ? Stream.of(false, true) : Stream.of(false);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void check(String name, boolean write, boolean granted) throws Exception {
        String role = Set.of("WorklogController", "OwnWorklogController").contains(name) ? "msp" : name.equals("FileController") ? "viewer" : "padmin";
        PlatformRepository platform = mock(PlatformRepository.class);
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true,granted?Capabilities.forRole(role):List.of())));
        when(platform.user(ACTOR)).thenReturn(Optional.of(new FixtureUser(ACTOR,"测试",role)));
        when(platform.tenant(TENANT)).thenReturn(new Tenant(TENANT,"测试租户"));
        when(platform.allowedTenants(ACTOR)).thenReturn(List.of(new Tenant(TENANT,"测试租户")));
        when(platform.metadata()).thenReturn(new RepositoryMetadata("remote_test","isolated",false,"isolated"));
        Class<?> type = Class.forName("cn.mop.api." + name);
        Constructor<?> constructor = type.getConstructors()[0];
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(p -> p == PlatformRepository.class ? platform : p == ObjectMapper.class ? new ObjectMapper() : mock(p)).toArray();
        Object controller = constructor.newInstance(dependencies);
        String gateName = Set.of("CapabilityCatalogController", "TenantInventoryController").contains(name) ? "get" : name.equals("AssetImportTemplateController") ? "download" : "access";
        Method gate = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(gateName)).findFirst().orElseThrow();gate.setAccessible(true);
        var request = new MockHttpServletRequest("GET","/api/v1/unit-gate");request.setUserPrincipal(() -> ACTOR);request.getSession().setAttribute("mop.tenant_id",TENANT);request.setAttribute("mop.trace_id","unit-effective-grants");
        Object[] arguments = Arrays.stream(gate.getParameterTypes()).map(p -> p == HttpServletRequest.class ? request : p == boolean.class ? write : p == String.class ? "assets:read" : null).toArray();
        if (granted) assertNotNull(gate.invoke(controller,arguments));
        else {
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> gate.invoke(controller,arguments));
            DomainException denied = assertInstanceOf(DomainException.class,thrown.getCause());assertEquals(403,denied.status);assertEquals("ACTION_FORBIDDEN",denied.code);
        }
    }
}
