package cn.mop.api;
import cn.mop.adapters.reports.QuarterlyReportPdfPreview;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
/** Fresh frozen read before render and again before releasing bytes; no new report, approval, artifact or outbox. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/inspection-quarterly-reports")
public final class QuarterlyReportPdfController {
 private final InspectionQuarterlyReportController reports;private final QuarterlyReportPdfPreview renderer;private final ObjectMapper mapper;
 public QuarterlyReportPdfController(InspectionQuarterlyReportController reports,QuarterlyReportPdfPreview renderer,ObjectMapper mapper){this.reports=reports;this.renderer=renderer;this.mapper=mapper;}
 @GetMapping("/{id}/preview.pdf") ResponseEntity<byte[]> preview(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
  response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Content-Type-Options","nosniff");
  var initial=reports.detail(id,request);String expected=request.getHeader("If-Match");
  if(expected==null)throw new DomainException(428,"REPORT_PREVIEW_PRECONDITION_REQUIRED","请先读取冻结报告，再携带其文档摘要请求 PDF 草稿");
  if(Collections.list(request.getHeaders("If-Match")).size()!=1||!expected.matches("\"[0-9a-f]{64}\""))throw new DomainException(400,"VALIDATION_ERROR","If-Match 必须为当前冻结文档摘要，不接受布局、字体或正文覆盖");
  String hash=expected.substring(1,65),record=(String)initial.get("id"),tenant=(String)initial.get("tenant_id");
  if(!hash.equals(initial.get("document_sha256")))throw changed();
  // The renderer never receives the full source_document or other frozen input payloads.
  var projection=new LinkedHashMap<String,Object>(initial);var document=(JsonNode)initial.get("frozen_document");
  if(document==null||!document.isObject())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","冻结报告正文不完整，未生成 PDF");
  var outputOnly=mapper.createObjectNode();outputOnly.set("configuration",document.get("configuration"));outputOnly.set("output",document.get("output"));projection.put("frozen_document",outputOnly);
  var result=renderer.render(mapper.valueToTree(projection));
  // Frozen output and authorization must still be readable after potentially expensive font/layout processing.
  var current=reports.detail(record,request);if(!hash.equals(current.get("document_sha256"))||!tenant.equals(current.get("tenant_id"))||!record.equals(current.get("id")))throw changed();
  byte[] bytes=result.bytes();return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(bytes.length).cacheControl(CacheControl.noStore())
    .header("Content-Disposition","attachment; filename=\"quarterly-draft-"+record+".pdf\"").header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff")
    .header("Content-Security-Policy","sandbox; default-src 'none'").header("X-MOP-Report-ID",record).header("X-MOP-Tenant-ID",tenant).header("X-MOP-Document-SHA256",hash)
    .header("X-MOP-Preview-Status","DRAFT").header("X-MOP-PDF-SHA256",result.sha256()).header("X-MOP-PDF-Pages",Integer.toString(result.pages())).body(bytes);
 }
 private static DomainException changed(){return new DomainException(412,"REPORT_PREVIEW_CHANGED","冻结报告或当前范围已变化，未下载 PDF，请重新读取报告");}
}
