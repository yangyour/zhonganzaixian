package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionWorksheetRepository.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static cn.mop.api.InspectionWorksheetControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class InspectionWorksheetSourceControllerTest {
    final InspectionWorksheetControllerTest f=new InspectionWorksheetControllerTest();
    InspectionSourceSnapshot snapshot;
    @BeforeEach void setup(){f.setup();snapshot=InspectionSourceSnapshot.fromEntry(f.row(1,"CREATE",false,new Content(Map.of(),null),f.definition()));when(f.repository.captureSources(any(),any())).thenReturn(new InspectionSourceManifest(UUID.fromString(TENANT),List.of(snapshot)));}
    @Test void returnsFullImmutableSourceAndNoHeadTimestamp(){var d=f.data(f.controller.sourceRevision(ID,"1",f.request("GET")));assertEquals(ID,d.get("worksheet_id"));assertEquals(1L,d.get("version"));assertEquals(snapshot.fingerprint(),d.get("source_snapshot_sha256"));assertFalse(d.containsKey("updated_at"));assertEquals(2,((List<?>)d.get("template_items")).size());assertEquals(false,d.get("evidence_access_granted"));assertEquals(false,d.get("report_published"));assertNull(d.get("reported_performed_at"));verify(f.repository).captureSources(f.access(),List.of(new Selection(ID,1)));verify(f.platform,never()).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());}
    @Test void rejectsUnknownQueriesAndNonCanonicalVersionsBeforeRead(){for(String v:List.of("0","01","-1","1.0","latest","9007199254740991","99999999999999999"))assertEquals(400,assertThrows(DomainException.class,()->f.controller.sourceRevision(ID,v,f.request("GET"))).status);var r=f.request("GET");r.addParameter("tenant_id",TENANT);assertEquals(400,assertThrows(DomainException.class,()->f.controller.sourceRevision(ID,"1",r)).status);verify(f.repository,never()).captureSources(any(),any());}
    @Test void normalizesIdButRequiresCurrentRoleBeforeQueryParsing(){f.controller.sourceRevision(ID.toUpperCase(),"1",f.request("GET"));verify(f.repository).captureSources(f.access(),List.of(new Selection(ID,1)));clearInvocations(f.repository);for(String role:List.of("msp","tadmin","viewer")){when(f.platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(403,assertThrows(DomainException.class,()->f.controller.sourceRevision("bad","latest",f.request("GET"))).status);}verifyNoInteractions(f.repository);}
    @Test void missingSessionCannotRead(){var r=f.request("GET");r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->f.controller.sourceRevision(ID,"1",r)).status);verify(f.repository,never()).captureSources(any(),any());}
    @Test void preservesRepository404401And503WithoutFallbackToCurrent(){for(int status:new int[]{404,401,503}){doThrow(new DomainException(status,"TEST","synthetic")).when(f.repository).captureSources(any(),any());assertEquals(status,assertThrows(DomainException.class,()->f.controller.sourceRevision(ID,"1",f.request("GET"))).status);}verify(f.repository,never()).get(any(),any());}
    @Test void unexpectedEmptyOrDifferentRequestedVersionFailsClosed(){when(f.repository.captureSources(any(),any())).thenReturn(new InspectionSourceManifest(UUID.fromString(TENANT),List.of()));assertEquals(503,assertThrows(DomainException.class,()->f.controller.sourceRevision(ID,"1",f.request("GET"))).status);when(f.repository.captureSources(any(),any())).thenReturn(new InspectionSourceManifest(UUID.fromString(TENANT),List.of(snapshot)));assertEquals(503,assertThrows(DomainException.class,()->f.controller.sourceRevision(ID,"2",f.request("GET"))).status);assertEquals(503,assertThrows(DomainException.class,()->f.controller.sourceRevision(UUID.randomUUID().toString(),"1",f.request("GET"))).status);}
}
