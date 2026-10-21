package cn.mop.adapters.reports;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.concurrent.Semaphore;
import static org.junit.jupiter.api.Assertions.*;
class QuarterlyReportPdfPreviewTest {
 static JsonNode document()throws Exception{return new ObjectMapper().readTree(Files.readString(Path.of("../../fixtures/inspection-quarterly-report/selected.json"))).path("data");}
 @Test void disabledIsSafeWithoutFontsOrAnyOutput(){var r=new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(false,null,null,null,null));assertEquals("REPORT_PDF_UNCONFIGURED",assertThrows(DomainException.class,()->r.render(document())).code);}
 @Test void missingPathHashAndRelativeOrNonfontFilesFailClosed()throws Exception{
  var temp=Files.createTempFile("synthetic-nonfont-",".txt");Files.writeString(temp,"NOT A FONT SYNTHETIC ONLY");try{for(var settings:new QuarterlyReportPdfPreview.Settings[]{new QuarterlyReportPdfPreview.Settings(true,null,null,null,null),new QuarterlyReportPdfPreview.Settings(true,"relative.ttf","a".repeat(64),null,null),new QuarterlyReportPdfPreview.Settings(true,temp.toString(),"a".repeat(64),null,null)})assertEquals("REPORT_PDF_UNCONFIGURED",assertThrows(DomainException.class,()->new QuarterlyReportPdfPreview(settings).render(document())).code);}finally{Files.delete(temp);}
 }
 @Test void publishedOrUnverifiedContentIsNotMadeIntoADraft()throws Exception{for(String field:new String[]{"report_publishable","report_published","evidence_access_granted"}){var raw=(com.fasterxml.jackson.databind.node.ObjectNode)document();raw.put(field,true);var r=new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(true,null,null,null,null));assertEquals(422,assertThrows(DomainException.class,()->r.render(raw)).status);}}
 @Test void renderCapacityIsBoundedAndDoesNotQueueUnboundedWork()throws Exception{var r=new QuarterlyReportPdfPreview(new QuarterlyReportPdfPreview.Settings(true,null,null,null,null));var field=QuarterlyReportPdfPreview.class.getDeclaredField("renderSlot");field.setAccessible(true);var slot=(Semaphore)field.get(r);slot.acquire();try{assertEquals("REPORT_PDF_BUSY",assertThrows(DomainException.class,()->r.render(document())).code);}finally{slot.release();}}
}
