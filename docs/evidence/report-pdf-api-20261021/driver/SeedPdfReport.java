package cn.mop.persistence;
import cn.mop.domain.inspection.*;import java.time.Instant;import java.util.*;
public class SeedPdfReport {
 public static void main(String[] ignored)throws Exception{
  if(!"dot-integration-fresh-pg17-11".equals(System.getenv("MOP_TEST_ENVIRONMENT_NAME"))||!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(System.getenv("SPRING_DATASOURCE_URL")))throw new IllegalStateException("Disposable test database required");
  PostgresInspectionQuarterlyReportIntegrationTest.start();var fixture=new PostgresInspectionQuarterlyReportIntegrationTest();
  try{fixture.setup();var output=new LinkedHashMap<String,Object>();
   for(String kind:List.of("selected","missing_glyph")){
    String note=kind.equals("selected")?"人工自述：合成😀。不是实际客户检查。":"合成缺字 "+new String(Character.toChars(0x10ffff));
    var content=new InspectionWorksheetRepository.Content(Map.of("firmware",new InspectionWorksheet.Observation(InspectionWorksheet.Result.ATTENTION,note,List.of())),Instant.parse("2026-10-19T01:02:03.004Z"));
    var source=fixture.collection(fixture.f.create(content));var input=new InspectionQuarterlyReportRepository.Creation("SYNTHETIC_PDF_"+kind,source.id(),source.documentSha256(),source.sourceManifestSha256(),new InspectionQuarterlyReport.Period(2026,4),new InspectionQuarterlyReport.Configuration("SYNTHETIC_RENDER_ONLY","UTC",InspectionQuarterlyReport.DuplicateRule.REQUIRE_SINGLE_SOURCE,InspectionQuarterlyReport.ArchivedRule.INCLUDE,"SYNTHETIC_V1","SYNTHETIC_V1"));
    var report=PostgresInspectionQuarterlyReportIntegrationTest.save(input);output.put(kind,Map.of("id",report.id(),"tenant_id",report.tenantId(),"document_sha256",report.documentSha256()));
   }
   fixture.cleanup();System.out.println("SEED_REPORT="+PostgresInspectionWorksheetIntegrationTest.mapper.writeValueAsString(output));
  }finally{PostgresInspectionQuarterlyReportIntegrationTest.stop();}
 }
}
