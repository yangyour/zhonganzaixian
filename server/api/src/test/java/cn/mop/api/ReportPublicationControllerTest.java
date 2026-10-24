package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.reporting.*;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportPublicationRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReportPublicationControllerTest {
 final String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002",id="79000000-0000-4000-8000-000000000003";
 final PlatformRepository platform=mock(PlatformRepository.class);final ReportPublicationRepository reports=mock(ReportPublicationRepository.class);
 final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());final Membership member=new Membership(actor,tenant,"padmin",7,true);
 final ReportPublicationController controller=new ReportPublicationController(platform,reports,json);
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);r.setAttribute("trace_id","unit");when(platform.membership(actor,tenant)).thenReturn(Optional.of(member));return r;}
 Stored stored(String target){var doc=new Document(UUID.fromString(target),UUID.fromString(id),UUID.fromString(id),null,1,Kind.QUARTERLY,Scope.CUSTOMER_DELIVERY,"a".repeat(64),"b".repeat(64),"v1","v1",UUID.fromString(actor),Instant.parse("2026-10-23T00:00:00Z"),List.of());return new Stored("synthetic",ReportPublication.create(doc),("{\"source_id\":\""+id+"\",\"source_document_sha256\":\""+"c".repeat(64)+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @Test void getRejectsRevocationBeforeReturning() {var r=request();when(reports.get(any(),eq(id))).thenAnswer(call->{when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",8,true)));return stored(tenant);});assertThrows(DomainException.class,()->controller.detail(id,r));verify(platform,times(2)).membership(actor,tenant);}
 @Test void getRejectsAnotherTenantResult(){var r=request();when(reports.get(any(),eq(id))).thenReturn(stored(actor));assertEquals("REPORT_PUBLICATION_UNAVAILABLE",assertThrows(DomainException.class,()->controller.detail(id,r)).code);}
 @Test void getRejectsQueryAndNoncanonicalIds(){var r=request();r.addParameter("tenant_id",actor);assertThrows(DomainException.class,()->controller.detail(id,r));verifyNoInteractions(reports);}
 @Test void listRejectsDuplicateAndIncompleteCursor(){var r=request();r.addParameter("before","2026-10-23T00:00:00Z");assertThrows(DomainException.class,()->controller.list(r));verifyNoInteractions(reports);}
 @Test void unknownScannerOrArtifactFieldsCannotBePosted()throws Exception{var r=request();assertThrows(DomainException.class,()->controller.command(id,json.readTree("{\"action\":\"RENDER\",\"expected_version\":1,\"reason\":null,\"pdf\":\"pretend\"}"),r));verifyNoInteractions(reports);}
 @Test void renderingDoesNotAcceptCallerPolicyOrTenant()throws Exception{var r=request();assertThrows(DomainException.class,()->controller.prepare(json.readTree("{\"source_id\":\""+id+"\",\"expected_source_sha256\":\""+"a".repeat(64)+"\",\"tenant_id\":\""+tenant+"\"}"),r));verifyNoInteractions(reports);}
 @Test void absentContentPreconditionDoesNotReadArtifact(){var r=request();assertEquals(428,assertThrows(DomainException.class,()->controller.content(id,"pdf",r)).status);verifyNoInteractions(reports);}
 @Test void duplicateIdempotencyHeadersFailBeforeCommand()throws Exception{var r=request();r.addHeader("Idempotency-Key","one");r.addHeader("Idempotency-Key","two");assertThrows(DomainException.class,()->controller.read(id,json.readTree("{\"expected_version\":1}"),r));verifyNoInteractions(reports);}
 @Test void queryVersionCannotReplaceBodyVersion()throws Exception{var r=request();assertThrows(DomainException.class,()->controller.read(id,json.readTree("{\"expected_version\":1.5}"),r));verifyNoInteractions(reports);}
}
