package cn.mop.api;
import cn.mop.adapters.reports.QuarterlyReportPdfPreview;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QuarterlyReportPdfControllerTest {
 static final String ID="78000000-0000-4000-8000-000000000003",TENANT="78000000-0000-4000-8000-000000000002",HASH="a".repeat(64);
 InspectionQuarterlyReportController reports;QuarterlyReportPdfPreview renderer;QuarterlyReportPdfController controller;Map<String,Object> detail;
 @BeforeEach void setup(){reports=mock(InspectionQuarterlyReportController.class);renderer=mock(QuarterlyReportPdfPreview.class);controller=new QuarterlyReportPdfController(reports,renderer,new ObjectMapper());detail=new HashMap<>(Map.of("id",ID,"tenant_id",TENANT,"document_sha256",HASH));detail.put("frozen_document",new ObjectMapper().createObjectNode());when(reports.detail(eq(ID),any())).thenReturn(detail);when(renderer.render(any())).thenReturn(new QuarterlyReportPdfPreview.Result(new byte[200],"b".repeat(64),2));}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/inspection-quarterly-reports/"+ID+"/preview.pdf");r.addHeader("If-Match","\""+HASH+"\"");return r;}
 @Test void readsBeforeAndAfterRenderAndBindsDownloadHeaders(){var r=request();var result=controller.preview(ID,r,new MockHttpServletResponse());assertEquals(200,result.getStatusCode().value());assertEquals("application/pdf",result.getHeaders().getContentType().toString());assertEquals("attachment; filename=\"quarterly-draft-"+ID+".pdf\"",result.getHeaders().getFirst("Content-Disposition"));assertEquals(HASH,result.getHeaders().getFirst("X-MOP-Document-SHA256"));assertEquals("DRAFT",result.getHeaders().getFirst("X-MOP-Preview-Status"));assertEquals("2",result.getHeaders().getFirst("X-MOP-PDF-Pages"));assertEquals("no-store",result.getHeaders().getCacheControl());var order=inOrder(reports,renderer);order.verify(reports).detail(ID,r);order.verify(renderer).render(any());order.verify(reports).detail(ID,r);}
 @Test void absentPreconditionDoesNotRender(){var r=request();r.removeHeader("If-Match");assertEquals(428,assertThrows(DomainException.class,()->controller.preview(ID,r,new MockHttpServletResponse())).status);verifyNoInteractions(renderer);}
 @Test void malformedWeakOrMultiplePreconditionsAreRejected(){for(String value:List.of("*",HASH,"W/\""+HASH+"\"","\""+HASH+"\",\""+HASH+"\"")){var r=request();r.removeHeader("If-Match");r.addHeader("If-Match",value);assertEquals(400,assertThrows(DomainException.class,()->controller.preview(ID,r,new MockHttpServletResponse())).status);}var r=request();r.addHeader("If-Match","\""+HASH+"\"");assertEquals(400,assertThrows(DomainException.class,()->controller.preview(ID,r,new MockHttpServletResponse())).status);verifyNoInteractions(renderer);}
 @Test void staleHashNeverRenders(){var r=request();r.removeHeader("If-Match");r.addHeader("If-Match","\""+"c".repeat(64)+"\"");assertEquals(412,assertThrows(DomainException.class,()->controller.preview(ID,r,new MockHttpServletResponse())).status);verifyNoInteractions(renderer);}
 @Test void authorizationFailureWinsBeforeFeatureOrPreconditionDisclosure(){when(reports.detail(eq(ID),any())).thenThrow(new DomainException(401,"SESSION_EXPIRED","expired"));var r=request();r.removeHeader("If-Match");var response=new MockHttpServletResponse();assertEquals(401,assertThrows(DomainException.class,()->controller.preview(ID,r,response)).status);assertEquals("no-store",response.getHeader("Cache-Control"));verifyNoInteractions(renderer);}
 @Test void revokedAccessAfterRenderingDoesNotReleaseBytes(){when(reports.detail(eq(ID),any())).thenReturn(detail).thenThrow(new DomainException(403,"ACTION_FORBIDDEN","revoked"));assertEquals(403,assertThrows(DomainException.class,()->controller.preview(ID,request(),new MockHttpServletResponse())).status);verify(renderer).render(any());}
 @Test void changedFrozenIdentityAfterRenderingDoesNotReleaseBytes(){var changed=new HashMap<>(detail);changed.put("document_sha256","c".repeat(64));when(reports.detail(eq(ID),any())).thenReturn(detail,changed);assertEquals(412,assertThrows(DomainException.class,()->controller.preview(ID,request(),new MockHttpServletResponse())).status);}
 @Test void disabledOrFailedRendererCannotBecomeAnEmptyPdf(){when(renderer.render(any())).thenThrow(new DomainException(503,"REPORT_PDF_UNCONFIGURED","not ready"));assertEquals(503,assertThrows(DomainException.class,()->controller.preview(ID,request(),new MockHttpServletResponse())).status);verify(reports).detail(eq(ID),any());}
 @Test void binaryAcceptStillGetsStructuredJsonFailureAndAudit()throws Exception{
  var platform=mock(cn.mop.domain.PlatformRepository.class);when(renderer.render(any())).thenThrow(new DomainException(503,"REPORT_PDF_UNCONFIGURED","not ready"));
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler(platform)).build();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/inspection-quarterly-reports/"+ID+"/preview.pdf").header("If-Match","\""+HASH+"\"").accept(org.springframework.http.MediaType.APPLICATION_PDF))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("REPORT_PDF_UNCONFIGURED"));
  verify(platform).audit(any(),any(),any(),eq("request"),isNull(),eq("DENIED"),eq("REPORT_PDF_UNCONFIGURED"),any());
 }
}
