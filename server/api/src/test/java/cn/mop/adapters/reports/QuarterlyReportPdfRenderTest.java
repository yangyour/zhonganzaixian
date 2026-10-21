package cn.mop.adapters.reports;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Explicit local-font rendering only. Not external browser, PDF/UA, publication or customer delivery acceptance. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REPORT_PDF_TESTS",matches="true")
class QuarterlyReportPdfRenderTest {
 static final ObjectMapper MAPPER=new ObjectMapper();
 static Path primary(){return Path.of(Objects.requireNonNull(System.getenv("MOP_REPORT_PDF_TEST_FONT")));}
 static Path fallback(){return Path.of(Objects.requireNonNull(System.getenv("MOP_REPORT_PDF_TEST_FALLBACK_FONT")));}
 static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
 static QuarterlyReportPdfPreview renderer()throws Exception{return new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(true,primary().toString(),hash(primary()),fallback().toString(),hash(fallback())));}
 static JsonNode document(String name)throws Exception{return MAPPER.readTree(Files.readString(Path.of("../../fixtures/inspection-quarterly-report/"+name+".json"))).path("data");}
 @Test void realPendingAndSelectedIncludingEmojiProduceCompleteBoundedPdfs()throws Exception{var r=renderer();for(String name:List.of("pending","selected")){var result=r.render(document(name));byte[] bytes=result.bytes();assertTrue(bytes.length>100&&bytes.length<QuarterlyReportPdfPreview.MAX_PDF_BYTES);assertTrue(new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"));assertTrue(new String(bytes,bytes.length-32,32,StandardCharsets.US_ASCII).stripTrailing().endsWith("%%EOF"));assertTrue(result.pages()>0&&result.pages()<=200);assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),result.sha256());bytes[0]=0;assertEquals('%',result.bytes()[0]);var dir=Path.of("target/pdf-preview-fixtures");Files.createDirectories(dir);Files.write(dir.resolve(name+".pdf"),result.bytes());}}
 @Test void missingGlyphRefusesInsteadOfRenderingAReplacementBox()throws Exception{var report=(ObjectNode)document("selected");((ObjectNode)report.path("frozen_document").path("output").path("rows").get(0).path("items").get(0).path("observation")).put("note","合成缺字 "+new String(Character.toChars(0x10ffff)));assertEquals("REPORT_PDF_RENDER_FAILED",assertThrows(DomainException.class,()->renderer().render(report)).code);}
 @Test void technicalTextLimitNeverReturnsATruncatedFile()throws Exception{var report=(ObjectNode)document("selected");report.put("label","SYNTHETIC".repeat(QuarterlyReportPdfPreview.MAX_TEXT_CHARS));assertEquals(413,assertThrows(DomainException.class,()->renderer().render(report)).status);}
 @Test void invalidControlCharactersAreRejectedNotSilentlyRemoved()throws Exception{var report=(ObjectNode)document("selected");report.put("label","SYNTHETIC\u0001");assertEquals(422,assertThrows(DomainException.class,()->renderer().render(report)).status);}
 @Test void wrongFontDigestRefusesEvenIfAReadableFontExists()throws Exception{var r=new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(true,primary().toString(),"0".repeat(64),null,null));assertEquals("REPORT_PDF_UNCONFIGURED",assertThrows(DomainException.class,()->r.render(document("pending"))).code);}
 @Test void longPlainTextAndMarkupRemainTextAcrossMultiplePages()throws Exception{var report=(ObjectNode)document("selected");report.put("label","合成安全版面测试");var rows=(ArrayNode)report.path("frozen_document").path("output").path("rows");for(int i=0;i<5;i++)rows.add(rows.get(0).deepCopy());((ObjectNode)rows.get(0).path("items").get(0).path("observation")).put("note","<script>fetch('http://127.0.0.1/private')</script>\n"+"W".repeat(800));((ObjectNode)report.path("frozen_document").path("source_document")).put("not_exported","SYNTHETIC_SOURCE_PRIVATE_MARKER");var result=renderer().render(report);assertTrue(result.pages()>2);var dir=Path.of("target/pdf-preview-fixtures");Files.createDirectories(dir);Files.write(dir.resolve("long-inert.pdf"),result.bytes());}
 @Test void emojiBesideCjkPunctuationUsesExplicitGraphemeFontFallback()throws Exception{
  var report=(ObjectNode)document("selected");((ObjectNode)report.path("frozen_document").path("output").path("rows").get(0).path("items").get(0).path("observation")).put("note","人工自述：合成😀。不是实际客户检查。 e\u0301");var result=renderer().render(report);assertTrue(result.pages()>0);
 }
}
