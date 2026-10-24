package cn.mop.adapters.reports;

import cn.mop.domain.DomainException;
import cn.mop.domain.reporting.ReportPublication.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Server-frozen output only. PDF is rendered from these exact escaped XHTML bytes, never a second live query. */
public final class ReportPublicationRenderer {
 public static final String TEMPLATE="MOP_PUBLICATION_XHTML_V1";
 public record Result(Artifact html,byte[] htmlBytes,Artifact pdf,byte[] pdfBytes,RenderFailure failure) {
  public Result{htmlBytes=htmlBytes.clone();pdfBytes=pdfBytes==null?null:pdfBytes.clone();}
  public byte[] htmlBytes(){return htmlBytes.clone();}public byte[] pdfBytes(){return pdfBytes==null?null:pdfBytes.clone();}
 }
 private final QuarterlyReportPdfPreview pdf;
 private final ObjectMapper mapper;
 public ReportPublicationRenderer(QuarterlyReportPdfPreview pdf,ObjectMapper mapper){this.pdf=Objects.requireNonNull(pdf);this.mapper=mapper.copy();}
 public Result render(Document document,byte[] payload,Instant now){
  if(!TEMPLATE.equals(document.templateVersion())||!sha(payload).equals(document.documentSha256()))throw new DomainException(422,"REPORT_SOURCE_INVALID","未实现的模板或冻结文档摘要不一致");
  final JsonNode data;try{data=mapper.readTree(payload);}catch(Exception invalid){throw new DomainException(422,"REPORT_SOURCE_INVALID","冻结报告不是有效文档");}
  if(data==null||!data.isObject()||!"MOP_REPORT_PUBLICATION_V1".equals(data.path("format").asText())
    ||!document.tenantId().toString().equals(data.path("tenant_id").asText())||!data.path("output").isObject())throw new DomainException(422,"REPORT_SOURCE_INVALID","冻结报告范围不一致");
  var lines=new ArrayList<String>();lines.add("报告类型："+document.kind());lines.add("文档 SHA256："+document.documentSha256());
  lines.add("来源清单 SHA256："+document.sourceManifestSha256());lines.add("冻结时间："+document.capturedAt());
  lines.add("原始来源为冻结快照；流程状态及客户验收以报告中心当前记录为准。");
  flatten("来源输出",data.get("output"),lines,0);
  if(!document.incompleteReasons().isEmpty())lines.add("待补全事项："+String.join("、",document.incompleteReasons()));
  var html=new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>MOP report</title></head><body><h1>").append(escape(data.path("label").asText())).append("</h1>");
  for(var line:lines)html.append("<p>").append(escape(line)).append("</p>");html.append("</body></html>");
  var bytes=html.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new DomainException(413,"REPORT_OUTPUT_LIMIT","报告超过技术上限");
  String hash=sha(bytes);QuarterlyReportPdfPreview.Result result=null;RenderFailure failure=null;
  try{result=pdf.renderPublicationHtml(html.toString());}catch(DomainException unavailable){failure=unavailable.code.equals("REPORT_PDF_UNCONFIGURED")?RenderFailure.FONT_UNAVAILABLE:RenderFailure.RENDER_FAILED;}
  String font=result==null?null:pdf.publicationFontIdentity();
  var htmlArtifact=new Artifact(Format.HTML,document.documentSha256(),hash,hash,TEMPLATE,"MOP_XHTML_1",font,bytes.length,null,now);
  var pdfArtifact=result==null?null:new Artifact(Format.PDF,document.documentSha256(),hash,result.sha256(),TEMPLATE,"APACHE_FOP_2.12_XHTML_1",font,result.bytes().length,result.pages(),now);
  return new Result(htmlArtifact,bytes,pdfArtifact,result==null?null:result.bytes(),failure);
 }
 private static void flatten(String path,JsonNode value,List<String> lines,int depth){
  if(depth>16||lines.size()>10000)throw new DomainException(413,"REPORT_OUTPUT_LIMIT","报告内容超过技术上限");
  if(value==null||value.isNull()){lines.add(path+"：未配置或未知（null）");return;}
  if(value.isValueNode()){lines.add(path+"："+value.asText());return;}
  if(value.isEmpty()){lines.add(path+"：明确空集合，不推断为正常或完整");return;}
  if(value.isArray()){for(int i=0;i<value.size();i++)flatten(path+"["+(i+1)+"]",value.get(i),lines,depth+1);return;}
  var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(var key:keys)flatten(path+" / "+key,value.get(key),lines,depth+1);
 }
 private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 public static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
