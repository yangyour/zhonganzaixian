package cn.mop.adapters.reports;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.fop.apps.*;
import org.apache.fop.apps.io.ResourceResolverFactory;
import org.apache.fop.configuration.DefaultConfigurationBuilder;
import org.apache.fop.events.model.EventSeverity;
import org.apache.xmlgraphics.io.*;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.AttributesImpl;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.BreakIterator;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, inert preview of server-verified frozen OUTPUT only. No publication, network, evidence or source files. */
public final class QuarterlyReportPdfPreview {
 public static final int MAX_PDF_BYTES=8*1024*1024,MAX_TEXT_CHARS=200_000,MAX_PAGES=200,MAX_FONT_BYTES=32*1024*1024;
 public record Settings(boolean enabled,String fontPath,String fontSha256,String fallbackPath,String fallbackSha256){}
 public record Result(byte[] bytes,String sha256,int pages){public Result{bytes=bytes.clone();}public byte[] bytes(){return bytes.clone();}}
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(QuarterlyReportPdfPreview.class);
 private final Settings settings;private final Semaphore renderSlot=new Semaphore(1);private FopFactory factory;private BitSet primaryGlyphs,fallbackGlyphs;
 public QuarterlyReportPdfPreview(Settings settings){this.settings=Objects.requireNonNull(settings);}
 public Result render(JsonNode report){
  if(!settings.enabled())throw unconfigured();
  if(!renderSlot.tryAcquire())throw new DomainException(503,"REPORT_PDF_BUSY","PDF 草稿生成器正在处理其他请求，请稍后手动重试");
  try{
   validate(report);var f=factory();var user=f.newFOUserAgent();user.setAccessibility(true);user.setTitle("DRAFT - "+report.path("label").asText());user.setCreator("MOP frozen report draft preview");user.setProducer("MOP draft preview / Apache FOP");
   var pages=new AtomicInteger();user.getEventBroadcaster().addEventListener(event->{if(event.getEventID().endsWith(".endPage")&&pages.incrementAndGet()>MAX_PAGES)throw limit();if(event.getSeverity()!=EventSeverity.INFO){LOG.warn("PDF preview rejected event_id={} severity={}",event.getEventID(),event.getSeverity());throw failed();}});
   var out=new LimitedOutput();var fop=f.newFop("application/pdf",user,out);var writer=new Layout(fop.getDefaultHandler(),primaryGlyphs,fallbackGlyphs);writer.render(report);
   int count=fop.getResults().getPageCount();if(count<1||count>MAX_PAGES||out.size()<100)throw failed();byte[] bytes=out.toByteArray();return new Result(bytes,digest(bytes),count);
  }catch(DomainException safe){throw safe;}catch(Exception failure){LOG.warn("PDF preview rejected exception_type={}",failure.getClass().getName());for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof DomainException safe)throw safe;throw failed();}finally{renderSlot.release();}
 }
 /** Render only the exact inert XHTML emitted by the publication renderer; no URL or caller layout is accepted. */
 public Result renderPublicationHtml(String html) {
  if(!settings.enabled())throw unconfigured();
  if(html==null||html.length()>MAX_TEXT_CHARS*8)throw limit();
  if(!renderSlot.tryAcquire())throw new DomainException(503,"REPORT_PDF_BUSY","PDF生成器正在处理其他请求");
  try {
   var parser=javax.xml.parsers.DocumentBuilderFactory.newInstance();
   parser.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);
   parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
   parser.setFeature("http://xml.org/sax/features/external-general-entities",false);
   parser.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
   parser.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");parser.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
   parser.setXIncludeAware(false);parser.setExpandEntityReferences(false);
   var doc=parser.newDocumentBuilder().parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
   var all=doc.getElementsByTagName("*");for(int i=0;i<all.getLength();i++){
    var element=(org.w3c.dom.Element)all.item(i);
    if(!Set.of("html","head","title","body","h1","p").contains(element.getTagName()))throw invalid();
    if(element.getAttributes().getLength()>(element.getTagName().equals("html")?1:0))throw invalid();
    if(element.getAttributes().getLength()==1&&!"http://www.w3.org/1999/xhtml".equals(element.getAttribute("xmlns")))throw invalid();
   }
   if(doc.getElementsByTagName("body").getLength()!=1||doc.getElementsByTagName("h1").getLength()!=1)throw invalid();
   var lines=new ArrayList<String>();var body=doc.getElementsByTagName("body").item(0).getChildNodes();
   for(int i=0;i<body.getLength();i++){var node=body.item(i);if(node.getNodeType()==org.w3c.dom.Node.ELEMENT_NODE){
    if(!Set.of("h1","p").contains(node.getNodeName()))throw invalid();
    for(int j=0;j<node.getChildNodes().getLength();j++)if(node.getChildNodes().item(j).getNodeType()!=org.w3c.dom.Node.TEXT_NODE)throw invalid();
    lines.add(node.getTextContent());
   }}
   var user=factory().newFOUserAgent();user.setAccessibility(true);user.setTitle("MOP report");user.setCreator("MOP frozen XHTML publication renderer");
   var pageCounter=new AtomicInteger();user.getEventBroadcaster().addEventListener(event->{if(event.getEventID().endsWith(".endPage")&&pageCounter.incrementAndGet()>MAX_PAGES)throw limit();if(event.getSeverity()!=EventSeverity.INFO)throw failed();});
   var out=new LimitedOutput();var fop=factory().newFop("application/pdf",user,out);
   new Layout(fop.getDefaultHandler(),primaryGlyphs,fallbackGlyphs).renderParagraphs(lines);
   int pages=fop.getResults().getPageCount();if(pages<1||pages>MAX_PAGES||out.size()<100)throw limit();
   var bytes=out.toByteArray();return new Result(bytes,digest(bytes),pages);
  }catch(DomainException safe){throw safe;}catch(Exception failure){throw failed();}finally{renderSlot.release();}
 }
 public String publicationFontIdentity() {
  if(!settings.enabled()||settings.fontSha256()==null)return null;
  return digest((settings.fontSha256()+"\n"+Objects.toString(settings.fallbackSha256(),"")).getBytes(StandardCharsets.UTF_8));
 }
 private FopFactory factory()throws Exception{
  if(factory!=null)return factory;
  byte[] font=font(settings.fontPath(),settings.fontSha256());boolean hasFallback=settings.fallbackPath()!=null&&!settings.fallbackPath().isBlank();
  if(!hasFallback&&settings.fallbackSha256()!=null&&!settings.fallbackSha256().isBlank())throw unconfigured();
  byte[] fallback=hasFallback?font(settings.fallbackPath(),settings.fallbackSha256()):null;
  primaryGlyphs=coverage(font);fallbackGlyphs=fallback==null?new BitSet():coverage(fallback);
  // OTF/TTF only. No TTC implicit face selection, OS font discovery, font cache, URL/file fallback or output resource.
  URI base=URI.create("mopfont:/"),primary=URI.create("mopfont:/primary.otf"),secondary=URI.create("mopfont:/fallback.ttf");
  ResourceResolver resolver=new ResourceResolver(){public Resource getResource(URI uri)throws IOException{if(primary.equals(uri))return new Resource(new ByteArrayInputStream(font));if(secondary.equals(uri)&&fallback!=null)return new Resource(new ByteArrayInputStream(fallback));throw new IOException("Preview resource is not permitted");}public OutputStream getOutputStream(URI uri)throws IOException{throw new IOException("Preview output resources are not permitted");}};
  String config="<fop version='1.0'><strict-configuration>true</strict-configuration><renderers><renderer mime='application/pdf'><fonts><font embed-url='mopfont:/primary.otf' embedding-mode='subset'><font-triplet name='ReportFont' style='normal' weight='normal'/></font>"+(hasFallback?"<font embed-url='mopfont:/fallback.ttf' embedding-mode='subset'><font-triplet name='ReportFallback' style='normal' weight='normal'/></font>":"")+"</fonts></renderer></renderers></fop>";
  var configured=new FopFactoryBuilder(base,resolver).setConfiguration(new DefaultConfigurationBuilder().build(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)))).build();
  configured.getFontManager().disableFontCache();configured.getFontManager().setResourceResolver(ResourceResolverFactory.createInternalResourceResolver(base,resolver));factory=configured;return factory;
 }
 private static BitSet coverage(byte[] bytes)throws IOException{
  try(var buffer=new RandomAccessReadBuffer(bytes);var font=new OTFParser().parse(buffer)){
   var cmap=font.getUnicodeCmapLookup();if(cmap==null)throw unconfigured();var glyphs=new BitSet(0x110000);for(int cp=32;cp<=0x10ffff;cp++)if(cmap.getGlyphId(cp)>0)glyphs.set(cp);return glyphs;
  }
 }
 private static byte[] font(String value,String expected){
  if(value==null||value.isBlank()||expected==null||!expected.matches("[0-9a-f]{64}"))throw unconfigured();
  try{Path path=Path.of(value);if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(path)||Files.size(path)<12||Files.size(path)>MAX_FONT_BYTES)throw unconfigured();byte[] bytes;try(var in=Files.newInputStream(path)){bytes=in.readNBytes(MAX_FONT_BYTES+1);}if(bytes.length>MAX_FONT_BYTES||!digest(bytes).equals(expected))throw unconfigured();
   boolean otf=bytes[0]=='O'&&bytes[1]=='T'&&bytes[2]=='T'&&bytes[3]=='O',ttf=bytes[0]==0&&bytes[1]==1&&bytes[2]==0&&bytes[3]==0;if(!otf&&!ttf)throw unconfigured();return bytes;
  }catch(DomainException safe){throw safe;}catch(Exception failure){throw unconfigured();}
 }
 private static void validate(JsonNode report){
  if(report==null||!report.isObject()||!report.path("id").asText().matches("[0-9a-f-]{36}")||!report.path("tenant_id").asText().matches("[0-9a-f-]{36}")||!report.path("document_sha256").asText().matches("[0-9a-f]{64}")||!"VERIFIED_SERVER_SIDE".equals(report.path("document_integrity").asText())||!"DRAFT".equals(report.path("generation_status").asText())||!report.path("report_publishable").isBoolean()||report.path("report_publishable").booleanValue()||!report.path("report_published").isBoolean()||report.path("report_published").booleanValue()||!report.path("evidence_access_granted").isBoolean()||report.path("evidence_access_granted").booleanValue())throw invalid();
  JsonNode d=report.path("frozen_document"),o=d.path("output");if(!d.isObject()||!o.isObject()||!o.path("rows").isArray()||!o.path("pending_reasons").isArray()||!o.path("summary").isObject()||!d.path("configuration").isObject()||!"DRAFT".equals(o.path("generation_status").asText())||o.path("report_published").asBoolean(true)||o.path("report_publishable").asBoolean(true)||o.path("evidence_access_granted").asBoolean(true))throw invalid();
 }
 private static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 private static DomainException unconfigured(){return new DomainException(503,"REPORT_PDF_UNCONFIGURED","PDF 草稿字体或摘要配置未就绪；请使用 HTML 草稿预览，不会生成替代文件");}
 private static DomainException failed(){return new DomainException(503,"REPORT_PDF_RENDER_FAILED","PDF 草稿的字体或版面不能完整呈现当前内容；未生成残缺文件，可使用 HTML 预览");}
 private static DomainException invalid(){return new DomainException(422,"REPORT_PDF_INVALID_CONTENT","当前内容不满足已验证未发布草稿的 PDF 预览条件");}
 private static DomainException limit(){return new DomainException(413,"REPORT_PDF_LIMIT","PDF 草稿超过文字、页数或文件大小技术上限；未生成截断文件");}
 private static final class LimitedOutput extends ByteArrayOutputStream{
  @Override public synchronized void write(int b){if(count>=MAX_PDF_BYTES)throw limit();super.write(b);}
  @Override public synchronized void write(byte[] b,int off,int len){if(len>MAX_PDF_BYTES-count)throw limit();super.write(b,off,len);}
 }
 private static final class Layout {
  private static final String FO="http://www.w3.org/1999/XSL/Format";private final ContentHandler h;private final BitSet primary,fallback;private int chars;
  private static final Map<String,String> LABELS=Map.ofEntries(Map.entry("id","报告编号"),Map.entry("tenant_id","租户编号"),Map.entry("recorded_at","记录时间（原值）"),Map.entry("source_collection_id","来源集合编号"),Map.entry("source_document_sha256","来源文档 SHA256"),Map.entry("source_manifest_sha256","来源清单 SHA256"),Map.entry("report_sha256","报告输入 SHA256"),Map.entry("document_sha256","冻结文档 SHA256"),Map.entry("capturedAssets","冻结来源资产数"),Map.entry("selectedAssets","已明确选定资产数"),Map.entry("unresolvedAssets","未解决来源选择数"),Map.entry("totalItems","选定检查项数"),Map.entry("answered","已填写"),Map.entry("normal","记录为正常"),Map.entry("attention","记录为关注"),Map.entry("requiresAction","记录为需处理"),Map.entry("unanswered","未填写"),Map.entry("revision","配置修订"),Map.entry("timeZone","报告业务时区"),Map.entry("duplicateRule","重复来源规则"),Map.entry("archivedRule","归档来源规则"),Map.entry("metricVersion","指标版本"),Map.entry("reportTemplateVersion","报告模板版本"));
  Layout(ContentHandler h,BitSet primary,BitSet fallback){this.h=h;this.primary=primary;this.fallback=fallback;}
  void start(String name,String... pairs)throws Exception{var a=new AttributesImpl();for(int i=0;i<pairs.length;i+=2){String k=pairs[i];a.addAttribute(k.equals("xml:lang")?"http://www.w3.org/XML/1998/namespace":"",k.equals("xml:lang")?"lang":k,k,"CDATA",pairs[i+1]);}h.startElement(FO,name,"fo:"+name,a);}
  void end(String name)throws Exception{h.endElement(FO,name,"fo:"+name);}
  void text(String text)throws Exception{text(text,48);}
  void text(String text,int run)throws Exception{if(text==null)text="未配置（null）";chars+=text.length();if(chars>MAX_TEXT_CHARS)throw limit();for(int i=0;i<text.length();){int c=text.codePointAt(i);if(c<32&&c!=9&&c!=10&&c!=13||c>=0xd800&&c<=0xdfff||c==0xfffe||c==0xffff)throw invalid();i+=Character.charCount(c);}String safe=text.replaceAll("([!-~]{"+run+"})(?=[!-~])","$1\n");// Resolve a complete grapheme before passing text to FOP. Word-level automatic fallback can
   // choose one font for an emoji plus CJK punctuation even when neither font covers the whole word.
   var breaks=BreakIterator.getCharacterInstance(Locale.ROOT);breaks.setText(safe);int begin=breaks.first();String selected=null;var runText=new StringBuilder();
   for(int finish=breaks.next();finish!=BreakIterator.DONE;begin=finish,finish=breaks.next()){
    String cluster=safe.substring(begin,finish);String chosen=covers(primary,cluster)?"ReportFont":covers(fallback,cluster)?"ReportFallback":null;
    if(chosen==null){LOG.warn("PDF preview rejected glyph_coverage_missing");throw failed();}
    if(selected!=null&&!selected.equals(chosen)){inline(runText.toString(),selected);runText.setLength(0);}selected=chosen;runText.append(cluster);
   }
   if(selected!=null)inline(runText.toString(),selected);}
  boolean covers(BitSet glyphs,String text){return text.codePoints().allMatch(cp->cp==9||cp==10||cp==13||glyphs.get(cp));}
  void inline(String value,String font)throws Exception{start("inline","font-family",font);char[] raw=value.toCharArray();h.characters(raw,0,raw.length);end("inline");}
  void p(String value,String... attrs)throws Exception{start("block",attrs);boolean large=false;for(int i=0;i<attrs.length;i+=2)if(attrs[i].equals("font-size"))large=Double.parseDouble(attrs[i+1].replace("pt",""))>=12;text(value,large?32:48);end("block");}
  void heading(String value)throws Exception{p(value,"font-size","15pt","color","#17324d","space-before","16pt","space-after","8pt","keep-with-next.within-page","always","role","H2");}
  void fact(String label,JsonNode value)throws Exception{p(label+"："+(value==null||value.isNull()?"未配置（null）":value.isValueNode()?value.asText():value.toString()),"space-after","5pt");}
  String reasonLabel(String value){return switch(value){case "TIME_ZONE_MISSING"->"报告业务时区未配置";case "DUPLICATE_RULE_MISSING"->"重复来源规则未配置";case "ARCHIVE_RULE_MISSING"->"归档来源规则未配置";case "METRIC_VERSION_MISSING"->"指标版本未配置";case "REPORT_TEMPLATE_VERSION_MISSING"->"报告模板版本未配置";case "CONFIG_REVISION_MISSING"->"配置修订未指定";case "SOURCE_SET_UNKNOWN"->"来源集合未知";case "SOURCE_SET_INCOMPLETE"->"来源集合不完整";case "EMPTY_SELECTION"->"未明确选定来源";case "UNRESOLVED_ASSET_SELECTION"->"仍有资产来源选择待解决";case "UNANSWERED_ITEMS"->"仍有检查项未填写";case "SELF_REPORTED_SOURCES_UNVERIFIED"->"人工自述来源尚未核验";case "TEMPLATE_APPROVAL_UNVERIFIED"->"模板批准状态尚未核验";case "EVIDENCE_REFERENCES_UNVERIFIED"->"证据引用尚未核验";case "PUBLICATION_PIPELINE_NOT_CONNECTED"->"正式发布链路未接通";default->"待解决事项";};}
  void renderParagraphs(List<String> lines)throws Exception {
   h.startDocument();h.startPrefixMapping("fo",FO);start("root","xml:lang","zh-CN");
   start("layout-master-set");start("simple-page-master","master-name","report","page-width","210mm","page-height","297mm","margin","18mm");start("region-body");end("region-body");end("simple-page-master");end("layout-master-set");
   start("page-sequence","master-reference","report","font-family","ReportFont","font-size","10pt","line-height","15pt");start("flow","flow-name","xsl-region-body");
   for(int i=0;i<lines.size();i++)p(lines.get(i),"font-size",i==0?"15pt":"10pt","space-after","6pt","white-space-collapse","false","linefeed-treatment","preserve","wrap-option","wrap");
   end("flow");end("page-sequence");end("root");h.endPrefixMapping("fo");h.endDocument();
  }
  void render(JsonNode report)throws Exception{
   JsonNode doc=report.path("frozen_document"),output=doc.path("output");h.startDocument();h.startPrefixMapping("fo",FO);
   start("root","xml:lang","zh-CN");start("layout-master-set");start("simple-page-master","master-name","report","page-width","210mm","page-height","297mm","margin-top","15mm","margin-bottom","15mm","margin-left","18mm","margin-right","18mm");start("region-body","margin-top","12mm","margin-bottom","12mm");end("region-body");start("region-before","extent","9mm");end("region-before");start("region-after","extent","9mm");end("region-after");end("simple-page-master");end("layout-master-set");
   start("page-sequence","master-reference","report","font-family","ReportFont","font-size","10pt","line-height","15pt","color","#24384b","language","zh","country","CN");
   start("static-content","flow-name","xsl-region-before");p("DRAFT / "+report.path("year").asInt()+" Q"+report.path("quarter").asInt()+" / "+report.path("id").asText(),"font-size","9pt","color","#52677a","border-bottom","0.5pt solid #aebbc8","padding-bottom","4pt");end("static-content");
   start("static-content","flow-name","xsl-region-after");start("block","font-size","8pt","text-align","center","color","#52677a");text("未批准 · 不可发布  |  第 ");start("page-number");end("page-number");text(" / ");start("page-number-citation-last","ref-id","report-end");end("page-number-citation-last");text(" 页");end("block");end("static-content");
   start("flow","flow-name","xsl-region-body","linefeed-treatment","preserve","white-space-collapse","false","wrap-option","wrap");
   p("季度运维报告","font-size","25pt","line-height","32pt","color","#17324d","space-after","6pt","role","H1");p(report.path("label").asText()+"  /  "+report.path("year").asInt()+" Q"+report.path("quarter").asInt(),"font-size","13pt","space-after","14pt");
   p("DRAFT 草稿 · 未批准 · 不可发布 · 未发布\n来源为人工自述，尚未核验。租户资产总量未知，不代表覆盖率或客户验收；证据访问未授权。","background-color","#fff4df","border","0.7pt solid #bb8110","padding","10pt","color","#68480e","space-after","12pt");
   p("本文件仅预览冻结输出，不包含全部来源原文、附件或可点击证据。它不是正式报告、完整备份、签章或发布凭证。离线副本包含租户数据，后续撤销权限无法收回。","font-size","9pt","color","#52677a");
   heading("冻结身份与来源摘要");for(String key:List.of("id","tenant_id","recorded_at","source_collection_id","source_document_sha256","source_manifest_sha256","report_sha256","document_sha256"))fact(LABELS.getOrDefault(key,key),report.get(key));
   heading("未批准配置");var config=doc.path("configuration").fields();while(config.hasNext()){var e=config.next();fact(LABELS.getOrDefault(e.getKey(),e.getKey()),e.getValue());}
   start("block","keep-together.within-page","always");heading("明确选择来源汇总（非租户覆盖率）");var summary=output.path("summary").fields();while(summary.hasNext()){var e=summary.next();fact(LABELS.getOrDefault(e.getKey(),e.getKey()),e.getValue());}
   end("block");heading("待解决事项");for(var reason:output.path("pending_reasons"))p("- "+reasonLabel(reason.asText())+"（"+reason.asText()+"）","space-after","4pt");
   heading("全部资产输出");if(output.path("rows").isEmpty())p("明确空选择，不代表零库存或巡检已经完成。");int index=0;
   for(var row:output.path("rows")){
    p(++index+". "+(row.path("asset_name").isNull()?"未选定来源":row.path("asset_name").asText())+" / "+row.path("status").asText(),"font-size","12pt","color","#17324d","space-before","12pt","space-after","6pt","keep-with-next.within-page","always","role","H3");fact("资产 ID",row.get("asset_id"));p(row.path("selected").isNull()?"未选定来源，不计作正常。":"已明确选定一个冻结修订。","space-after","6pt");
    p("候选修订摘要","color","#17324d","keep-with-next.within-page","always");for(var candidate:row.path("candidates")){p(candidate.path("worksheetId").asText()+" / v"+candidate.path("version").asInt(),"font-size","9pt");fact("快照 SHA256",candidate.get("snapshotSha256"));fact("内容 SHA256",candidate.get("contentSha256"));}
    if(row.path("items").isEmpty())p("没有选定检查项，不代表零项已完成。");
    for(var item:row.path("items")){JsonNode definition=item.path("item"),observation=item.path("observation");p(definition.path("section").asText()+" / "+definition.path("label").asText(),"color","#17324d","space-before","8pt","keep-with-next.within-page","always","role","H4");fact("检查说明",definition.get("instruction"));p("结果："+(observation.isNull()?"未填写":observation.path("result").asText()),"space-after","4pt");p(observation.isNull()||observation.path("note").isNull()?"无备注":observation.path("note").asText(),"space-after","7pt");}
   }
   p("关注及需处理项共 "+output.path("findings").size()+" 项，已包含在上方选定检查项中。证据引用不导出为链接。","space-before","14pt","font-size","9pt","color","#52677a");start("block","id","report-end");end("block");end("flow");end("page-sequence");end("root");h.endPrefixMapping("fo");h.endDocument();
  }
 }
}
