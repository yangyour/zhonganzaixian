package cn.mop.api;
import cn.mop.adapters.reports.*;
import cn.mop.domain.DomainException;
import cn.mop.domain.reporting.ReportPublication.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPublicationRendererTest {
 final ObjectMapper mapper=new ObjectMapper();
 final UUID tenant=UUID.fromString("79000000-0000-4000-8000-000000000002");
 final Instant now=Instant.parse("2026-10-23T19:00:00Z");
 byte[] payload()throws Exception{return mapper.writeValueAsBytes(Map.of("format","MOP_REPORT_PUBLICATION_V1","tenant_id",tenant.toString(),"label","<script>alert(1)</script>","output",mapper.readTree("{\"count\":null,\"items\":[],\"note\":\"<img src='https://example.invalid/x'>\"}")));}
 Document document(byte[] bytes){return new Document(tenant,UUID.randomUUID(),UUID.randomUUID(),null,1,Kind.QUARTERLY,Scope.CUSTOMER_DELIVERY,ReportPublicationRenderer.sha(bytes),"a".repeat(64),"explicit-v1",ReportPublicationRenderer.TEMPLATE,UUID.randomUUID(),now,List.of("SOURCE_APPROVAL_UNVERIFIED"));}
 @Test void missingFontKeepsRealHtmlWithoutInventingPdf()throws Exception{
  var bytes=payload();var renderer=new ReportPublicationRenderer(new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(false,null,null,null,null)),mapper);
  var result=renderer.render(document(bytes),bytes,now);String html=new String(result.htmlBytes(),StandardCharsets.UTF_8);
  assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));assertFalse(html.contains("<img"));assertTrue(html.contains("未知（null）"));
  assertNull(result.pdf());assertNull(result.pdfBytes());assertEquals(RenderFailure.FONT_UNAVAILABLE,result.failure());
  assertEquals(ReportPublicationRenderer.sha(result.htmlBytes()),result.html().contentSha256());
 }
 @Test void pdfTransportReceivesExactlyThePersistedHtmlNotASecondProjection()throws Exception{
  var pdf=mock(QuarterlyReportPdfPreview.class);when(pdf.publicationFontIdentity()).thenReturn("f".repeat(64));
  var pdfBytes="synthetic PDF transport result".getBytes(StandardCharsets.UTF_8);
  when(pdf.renderPublicationHtml(anyString())).thenReturn(new QuarterlyReportPdfPreview.Result(pdfBytes,ReportPublicationRenderer.sha(pdfBytes),1));
  var bytes=payload();var result=new ReportPublicationRenderer(pdf,mapper).render(document(bytes),bytes,now);
  verify(pdf).renderPublicationHtml(new String(result.htmlBytes(),StandardCharsets.UTF_8));
  assertEquals(result.html().contentSha256(),result.pdf().htmlSha256());assertEquals(result.html().fontSha256(),result.pdf().fontSha256());
 }
 @Test void changedBytesOrForeignTenantCannotBeRendered()throws Exception{
  var bytes=payload();var renderer=new ReportPublicationRenderer(new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(false,null,null,null,null)),mapper);
  assertThrows(DomainException.class,()->renderer.render(document(bytes),"{}".getBytes(StandardCharsets.UTF_8),now));
  var foreign=mapper.readTree(bytes);((com.fasterxml.jackson.databind.node.ObjectNode)foreign).put("tenant_id",UUID.randomUUID().toString());var other=mapper.writeValueAsBytes(foreign);
  assertThrows(DomainException.class,()->renderer.render(document(other),other,now));
 }
 @Test void htmlOnlyDoesNotEnterReviewOrPublication()throws Exception{
  var bytes=payload();var doc=document(bytes);var result=new ReportPublicationRenderer(new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(false,null,null,null,null)),mapper).render(doc,bytes,now);
  var actor=new Actor(tenant,doc.preparedBy(),true,false,false);
  var state=cn.mop.domain.reporting.ReportPublication.queue(cn.mop.domain.reporting.ReportPublication.create(doc),actor,1,now,null);
  var failed=cn.mop.domain.reporting.ReportPublication.rendered(state,actor,2,result.html(),result.pdf(),now);
  assertEquals(Phase.FAILED,failed.phase());assertEquals("PDF_NOT_AVAILABLE",failed.reason());
 }
}
