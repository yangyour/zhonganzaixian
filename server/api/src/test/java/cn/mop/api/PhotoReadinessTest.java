package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated readiness behavior only; actual PostgreSQL/MinIO availability requires remote checks. */
class PhotoReadinessTest {
    private PlatformRepository repository;
    private PhotoObjectStore store;
    private PhotoConfiguration configuration;
    private MockHttpServletRequest request;

    @BeforeEach void setup(){
        repository=mock(PlatformRepository.class);store=mock(PhotoObjectStore.class);configuration=new PhotoConfiguration();
        when(repository.metadata()).thenReturn(new RepositoryMetadata("remote_test","postgresql",true,"test_oidc"));
        when(repository.assetReadProblems()).thenReturn(List.of());when(repository.ticketCommentProblems()).thenReturn(List.of());when(repository.workingCalendarProblems()).thenReturn(List.of());when(repository.manualIncidentProblems()).thenReturn(List.of());when(repository.manualRecoveryProblems()).thenReturn(List.of());when(repository.internalNoteProblems()).thenReturn(List.of());when(repository.assetMetadataProblems()).thenReturn(List.of());when(repository.worklogProblems()).thenReturn(List.of());when(repository.assetDependencyProblems()).thenReturn(List.of());when(repository.dependencyReady()).thenReturn(true);when(repository.photoDependencyProblems()).thenReturn(List.of());
        when(store.ready()).thenReturn(true);request=new MockHttpServletRequest();request.setAttribute("mop.trace_id","isolated-readiness");
    }
    private PhotoConfiguration.PhotoDependencyProbe probe(MockEnvironment environment){
        return configuration.photoDependencyProbe(environment,configuration.photoPolicy(environment),store,repository);
    }
    private PlatformController controller(PhotoConfiguration.PhotoDependencyProbe probe){
        var identity=mock(TestOidcConfiguration.OidcDependencyProbe.class);when(identity.ready()).thenReturn(true);
        return new PlatformController(repository,new ObjectMapper(),provider(identity),provider(probe),provider(new AssetImportConfiguration.ImportDependencyProbe(false,false,null,null)));
    }
    private static <T> ObjectProvider<T> provider(T bean){
        ObjectProvider<T> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(bean);return provider;
    }
    private static MockEnvironment enabled(){return new MockEnvironment().withProperty("MOP_PHOTO_ALLOWED_MEDIA_TYPES","image/jpeg,image/png");}
    private static Map<?,?> data(Object response){return (Map<?,?>)((Map<?,?>)response).get("data");}
    private static Map<?,?> photos(DomainException error){return (Map<?,?>)((Map<?,?>)error.details.get(0)).get("photos");}

    @Test void noPhotoConfigurationKeepsBaseReadinessButNeverReportsPhotoReady(){
        var probe=probe(new MockEnvironment());var response=data(controller(probe).ready(request));Map<?,?> photos=(Map<?,?>)response.get("photos");
        assertEquals(true,response.get("ready"));assertEquals(false,photos.get("enabled"));assertEquals(false,photos.get("ready"));
        assertEquals("PENDING",photos.get("policy_source"));assertEquals(List.of("PHOTO_NOT_CONFIGURED"),photos.get("reasons"));
        verify(repository,never()).photoDependencyProblems();verifyNoInteractions(store);
    }
    @Test void configuredPhotoSchemaFailureCannotHideBehindHealthyPostgresAndOidc(){
        when(repository.photoDependencyProblems()).thenReturn(List.of("PHOTO_SCHEMA_MISSING:mop_file"));
        DomainException error=assertThrows(DomainException.class,()->controller(probe(enabled())).ready(request));
        assertEquals(503,error.status);assertEquals("UPSTREAM_UNAVAILABLE",error.code);
        assertEquals(false,photos(error).get("schema_ready"));assertEquals(true,photos(error).get("storage_ready"));
        assertEquals(List.of("PHOTO_SCHEMA_MISSING:mop_file"),photos(error).get("reasons"));
    }
    @Test void storageOutageAndRecoveryAreReflectedOnEachReadyRequest(){
        var controller=controller(probe(enabled()));when(store.ready()).thenReturn(false).thenReturn(true);
        DomainException outage=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,outage.status);
        assertEquals(false,photos(outage).get("ready"));assertEquals(List.of("PHOTO_STORAGE_UNAVAILABLE"),photos(outage).get("reasons"));
        Map<?,?> recovered=(Map<?,?>)data(controller.ready(request)).get("photos");assertEquals(true,recovered.get("ready"));assertEquals(List.of(),recovered.get("reasons"));
        verify(store,times(2)).ready();verify(repository,times(2)).photoDependencyProblems();
    }
    @Test void storageConfigurationWithoutPolicyIsEnabledAndExplicitlyNotReady(){
        var environment=new MockEnvironment().withProperty("MOP_S3_BUCKET","mop-test-photos");
        DomainException error=assertThrows(DomainException.class,()->controller(probe(environment)).ready(request));
        assertEquals(503,error.status);assertEquals(true,photos(error).get("enabled"));assertEquals(false,photos(error).get("policy_ready"));
        assertEquals("PENDING",photos(error).get("policy_source"));assertEquals(List.of("PHOTO_POLICY_NOT_CONFIGURED"),photos(error).get("reasons"));
    }
    @Test void dependencyExceptionsDoNotLeakProviderOrCredentialMessages(){
        when(repository.photoDependencyProblems()).thenThrow(new IllegalStateException("private-connection-detail"));
        when(store.ready()).thenThrow(new IllegalStateException("private-storage-detail"));
        DomainException error=assertThrows(DomainException.class,()->controller(probe(enabled())).ready(request));
        assertEquals(503,error.status);assertEquals(List.of("PHOTO_DATABASE_UNAVAILABLE","PHOTO_STORAGE_UNAVAILABLE"),photos(error).get("reasons"));
        assertFalse(error.details.toString().contains("private-"));
    }
    @Test void missingProbeFailsClosedAndLivenessDoesNotProbePhotoComponents(){
        PlatformController controller=controller(null);DomainException error=assertThrows(DomainException.class,()->controller.ready(request));
        assertEquals(503,error.status);assertEquals(List.of("PHOTO_PROBE_MISSING"),((Map<?,?>)error.details.get(0)).get("reasons"));
        assertEquals("UP",data(controller.live(request)).get("status"));verify(repository,never()).photoDependencyProblems();verifyNoInteractions(store);
    }
    @Test void dependencyDraftSchemaCannotHideBehindOtherwiseHealthyCore(){
        var controller=controller(probe(new MockEnvironment()));when(repository.assetDependencyProblems()).thenReturn(List.of("missing"));
        var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("asset_dependencies",((Map<?,?>)failure.details.get(0)).get("component"));
        when(repository.assetDependencyProblems()).thenReturn(List.of());Map<?,?> available=(Map<?,?>)data(controller.ready(request)).get("asset_dependencies");assertEquals(true,available.get("schema_access_ready"));assertEquals(false,available.get("monitoring_applied"));assertNull(available.get("effective_rules"));
    }
    @Test void dependencyProbeExceptionIsSanitizedAndLivenessDoesNotProbeIt(){
        var controller=controller(probe(new MockEnvironment()));when(repository.assetDependencyProblems()).thenThrow(new IllegalStateException("synthetic-private-value"));
        controller.live(request);verify(repository,never()).assetDependencyProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertFalse(failure.details.toString().contains("synthetic-private-value"));
    }
    @Test void worklogSchemaReadinessFailsClosedWithoutAffectingLiveness(){
        var controller=controller(probe(new MockEnvironment()));when(repository.worklogProblems()).thenReturn(List.of("missing"));
        controller.live(request);verify(repository,never()).worklogProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("worklogs",((Map<?,?>)failure.details.get(0)).get("component"));
        when(repository.worklogProblems()).thenReturn(List.of());Map<?,?> available=(Map<?,?>)data(controller.ready(request)).get("worklogs");assertEquals(true,available.get("schema_access_ready"));assertEquals("CONFIG_PENDING",available.get("financial_status"));
    }
    @Test void manualMetadataReadinessFailsClosedWithoutAutomaticMigration(){
        var controller=controller(probe(new MockEnvironment()));when(repository.assetMetadataProblems()).thenReturn(List.of("missing"));
        controller.live(request);verify(repository,never()).assetMetadataProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("asset_manual_metadata",((Map<?,?>)failure.details.get(0)).get("component"));
        when(repository.assetMetadataProblems()).thenReturn(List.of());Map<?,?> available=(Map<?,?>)data(controller.ready(request)).get("asset_manual_metadata");assertEquals(true,available.get("schema_access_ready"));assertEquals(false,available.get("monitoring_applied"));assertEquals(false,available.get("cmdb_applied"));
    }

    @Test void internalNoteReadinessRequiresRealSchemaWithoutWritingOrMigrating(){
        var controller=controller(probe(new MockEnvironment()));when(repository.internalNoteProblems()).thenReturn(List.of("missing"));controller.live(request);verify(repository,never()).internalNoteProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("internal_notes",((Map<?,?>)failure.details.get(0)).get("component"));when(repository.internalNoteProblems()).thenReturn(List.of());Map<?,?> available=(Map<?,?>)data(controller.ready(request)).get("internal_notes");assertEquals(true,available.get("schema_access_ready"));assertEquals("INTERNAL",available.get("visibility"));assertEquals(false,available.get("ticket_state_changed"));assertEquals(false,available.get("sla_changed"));
    }

    @Test void assetUnicodeSearchReadinessFailsClosedWithoutChangingDatabaseSettings(){
        var controller=controller(probe(new MockEnvironment()));when(repository.assetReadProblems()).thenReturn(List.of("missing"));controller.live(request);verify(repository,never()).assetReadProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("asset_reads",((Map<?,?>)failure.details.get(0)).get("component"));when(repository.assetReadProblems()).thenReturn(List.of());assertEquals(true,((Map<?,?>)data(controller.ready(request)).get("asset_reads")).get("unicode_search_ready"));
    }

    @Test void ticketCommentReadinessRequiresActualSchemaAndDoesNotClaimProviderDelivery(){
        var controller=controller(probe(new MockEnvironment()));when(repository.ticketCommentProblems()).thenReturn(List.of("missing"));controller.live(request);verify(repository,never()).ticketCommentProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("ticket_comments",((Map<?,?>)failure.details.get(0)).get("component"));when(repository.ticketCommentProblems()).thenReturn(List.of());Map<?,?> ready=(Map<?,?>)data(controller.ready(request)).get("ticket_comments");assertEquals(true,ready.get("schema_access_ready"));assertEquals("REPORTER_AND_INTERNAL",ready.get("visibility"));assertEquals(false,ready.get("internal_identity_exposed"));assertEquals(false,ready.get("itsm_applied"));
    }

    @Test void calendarSchemaReadinessDoesNotImplyEffectiveCalendar(){
        var controller=controller(probe(new MockEnvironment()));when(repository.workingCalendarProblems()).thenReturn(List.of("missing"));controller.live(request);verify(repository,never()).workingCalendarProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("working_calendars",((Map<?,?>)failure.details.get(0)).get("component"));when(repository.workingCalendarProblems()).thenReturn(List.of());Map<?,?> ready=(Map<?,?>)data(controller.ready(request)).get("working_calendars");assertEquals(true,ready.get("schema_access_ready"));assertEquals(false,ready.get("effective"));assertEquals("UNVERIFIED",ready.get("customer_confirmation"));
    }

    @Test void manualRegistrationReadinessDoesNotClaimRecoveryOrTicketCompletion(){
        var controller=controller(probe(new MockEnvironment()));when(repository.manualIncidentProblems()).thenReturn(List.of("missing"));controller.live(request);verify(repository,never()).manualIncidentProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("manual_incidents",((Map<?,?>)failure.details.get(0)).get("component"));when(repository.manualIncidentProblems()).thenReturn(List.of());Map<?,?> ready=(Map<?,?>)data(controller.ready(request)).get("manual_incidents");assertEquals(true,ready.get("schema_access_ready"));assertEquals("MANUAL",ready.get("source_state"));assertEquals("REGISTERED_ONLY",ready.get("registration_status"));assertEquals(false,ready.get("monitoring_recovery_applicable"));assertEquals(false,ready.get("ticket_automatically_created"));
    }

    @Test void humanRecoveryReadinessDoesNotClaimMonitoringOrFormalClosure(){
        var controller=controller(probe(new MockEnvironment()));when(repository.manualRecoveryProblems()).thenReturn(List.of("missing"));controller.live(request);verify(repository,never()).manualRecoveryProblems();var failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("manual_recovery",((Map<?,?>)failure.details.get(0)).get("component"));when(repository.manualRecoveryProblems()).thenReturn(List.of());Map<?,?> ready=(Map<?,?>)data(controller.ready(request)).get("manual_recovery");assertEquals(true,ready.get("schema_access_ready"));assertEquals(false,ready.get("monitoring_recovery_applicable"));assertEquals(false,ready.get("ticket_state_changed"));assertEquals(false,ready.get("sla_changed"));
    }

}
