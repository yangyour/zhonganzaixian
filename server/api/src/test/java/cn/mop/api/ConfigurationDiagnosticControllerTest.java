package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated input/access/DTO checks; these mocks do not prove PostgreSQL or OIDC integration. */
class ConfigurationDiagnosticControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011",OTHER="55000000-0000-4000-8000-000000000012",KEY="remote.idle_timeout_sec";
    private PlatformRepository repository;
    private ConfigurationDiagnosticReader reader;
    private ConfigurationDiagnosticController controller;
    @BeforeEach void setup(){
        repository=mock(PlatformRepository.class);reader=mock(ConfigurationDiagnosticReader.class);controller=new ConfigurationDiagnosticController(repository,reader);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));
    }
    static ConfigurationDiagnostic diagnostic(String key,String tenant){
        return ConfigurationDiagnostic.blocked(key,new ConfigurationDiagnostic.Context(tenant),Instant.parse("2026-10-01T00:00:00Z"),
            new ConfigurationDiagnostic.Registry(1,"integer","秒","平台","M7-6","pending","configurable_default",List.of("Q19")),
            new ConfigurationDiagnostic.RecentProposals(List.of(new ConfigurationDiagnostic.Proposal("55000000-0000-4000-8000-000000000099",3,"APPROVED",1)),false));
    }
    private MockHttpServletRequest request(String... keys){
        var request=new MockHttpServletRequest("GET","/api/v1/settings/effective");request.setUserPrincipal(()->ACTOR);request.getSession().setAttribute("mop.tenant_id",TENANT);request.setAttribute("mop.trace_id","isolated-diagnostic");
        if(keys.length>0)request.addParameter("key",keys);return request;
    }
    @Test void returnsExplicitPendingMetadataUsingOnlyTrustedSessionScopeAndPermissionVersion(){
        var context=new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7);var diagnostic=diagnostic(KEY,TENANT);when(reader.read(context,KEY)).thenReturn(diagnostic);
        var request=request(KEY);request.addHeader("X-Tenant-ID",OTHER);
        Map<?,?> response=(Map<?,?>)controller.effective(request),data=(Map<?,?>)response.get("data");
        assertEquals(diagnostic.dto(),data);assertEquals("CONFIG_PENDING",data.get("status"));assertTrue(data.containsKey("effective_value"));assertNull(data.get("effective_value"));
        assertEquals(TENANT,((Map<?,?>)data.get("context")).get("tenant_id"));assertEquals("NOT_IMPLEMENTED",((Map<?,?>)data.get("publication")).get("availability"));
        assertFalse(data.toString().contains("proposed_value"));verify(reader).read(context,KEY);
        verify(repository).audit(ACTOR,TENANT,"configuration.effective_diagnostic.read","configuration",KEY,"SUCCEEDED","CONFIG_PENDING_METADATA_ONLY_NOT_EFFECTIVE","isolated-diagnostic");
    }
    @Test void missingDuplicateUnknownAndObjectScopeQueryParametersNeverReachReader(){
        List<MockHttpServletRequest> requests=new ArrayList<>(List.of(request(),request(KEY,KEY)));
        for(String field:List.of("tenant_id","actor_id","scope","object_id","revision_id","unknown")){var request=request(KEY);request.addParameter(field,OTHER);requests.add(request);}
        for(var request:requests)assertEquals(400,assertThrows(DomainException.class,()->controller.effective(request)).status);
        verifyNoInteractions(reader);
    }
    @Test void illegalKeysAreRejectedWithoutTrimmingOrNormalizingThem(){
        for(String key:List.of(""," "+KEY,KEY+" ","../key","a/b","key\n","配置","a".repeat(129)))
            assertEquals("VALIDATION_ERROR",assertThrows(DomainException.class,()->controller.effective(request(key))).code);
        verifyNoInteractions(reader);
    }
    @Test void allRolesWithoutCurrentConfigurationReadCapabilityAreDenied(){
        for(String role:List.of("viewer","tadmin","msp","unknown")){
            when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,8,true)));
            assertEquals(403,assertThrows(DomainException.class,()->controller.effective(request(KEY))).status);
        }
        verifyNoInteractions(reader);
    }
    @Test void absentStaleAndMalformedSessionContextsAreDenied(){
        var anonymous=request(KEY);anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.effective(anonymous)).status);
        var noTenant=request(KEY);noTenant.getSession().removeAttribute("mop.tenant_id");assertEquals(401,assertThrows(DomainException.class,()->controller.effective(noTenant)).status);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());assertEquals(401,assertThrows(DomainException.class,()->controller.effective(request(KEY))).status);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.effective(request(KEY))).status);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",0,true)));assertEquals(401,assertThrows(DomainException.class,()->controller.effective(request(KEY))).status);
        verifyNoInteractions(reader);
    }
    @Test void snapshotRevocationUnknownKeyAndDatabaseFailureKeepTheirPublicStatus(){
        var context=new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7);
        for(int status:List.of(401,403,404,503)){
            var error=new DomainException(status,"SAFE_DIAGNOSTIC_ERROR","安全诊断错误");doThrow(error).when(reader).read(context,KEY);
            assertSame(error,assertThrows(DomainException.class,()->controller.effective(request(KEY))));
        }
        verify(repository,never()).audit(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void mismatchedKeyOrTenantSourceResultsFailClosedAndAreNeverAuditedAsSuccessfulReads(){
        var context=new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7);
        for(var mismatch:List.of(diagnostic("business.timezone",TENANT),diagnostic(KEY,OTHER))){
            when(reader.read(context,KEY)).thenReturn(mismatch);assertEquals(503,assertThrows(DomainException.class,()->controller.effective(request(KEY))).status);
        }
        verify(repository,never()).audit(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void successfulDiagnosticReadRequiresItsAuditToBeDurable(){
        var context=new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7);when(reader.read(context,KEY)).thenReturn(diagnostic(KEY,TENANT));
        doThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","指定审计依赖不可用")).when(repository).audit(any(),any(),any(),any(),any(),any(),any(),any());
        assertEquals(503,assertThrows(DomainException.class,()->controller.effective(request(KEY))).status);
    }
}
