package cn.mop.adapters.cmdb;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CmdbSourcePreflightTest {
 final CustomerCmdbDictionary dictionary=CustomerCmdbDictionary.load(new ObjectMapper());
 final CmdbSourcePreflight checker=new CmdbSourcePreflight(dictionary);
 ParsedArtifact artifact(Map<Integer,String> values,boolean numericDate){
  var headers=new ArrayList<String>();dictionary.document().path("import_columns").elements().forEachRemaining(v->headers.add(v.asText()));
  var cells=new ArrayList<ParsedArtifact.SourceCell>();values.forEach((i,v)->cells.add(new ParsedArtifact.SourceCell(i,numericDate&&i==10?ParsedArtifact.CellKind.NUMERIC:ParsedArtifact.CellKind.TEXT,v,v,null,null)));
  return new ParsedArtifact(ParsedArtifact.State.PARSED,ImportFormat.XLSX,List.of(new ParsedArtifact.SheetInfo(0,"Synthetic",false)),0,1,ParsedArtifact.DateEpoch.MAC_1904,ParsedArtifact.columns(headers),List.of(new ParsedArtifact.SourceRow(2,2,null,null,cells)),"synthetic","synthetic");
 }
 @Test void validSourceStillCannotImport(){var r=checker.check(artifact(Map.of(0,"Synthetic",1,"SW",2,"192.0.2.1",16,"sw_core"),false));assertFalse(r.canImport());assertEquals("CONFIG_PENDING",r.status());assertEquals(0,r.errorRows());}
 @Test void retainsSourceLocationForMismatch(){var r=checker.check(artifact(Map.of(0,"Synthetic",1,"SW",2,"192.0.2.1",16,"fw_adc"),false));var f=r.findings().get(0);assertEquals("SUBTYPE_PARENT_MISMATCH",f.code());assertEquals(2,f.record());assertEquals(2,f.sourceRow());assertEquals(16,f.column());}
 @Test void numericExcelDateIsNeverGuessed(){var r=checker.check(artifact(Map.of(0,"Synthetic",1,"SW",2,"192.0.2.1",10,"45000",16,"sw_core"),true));assertTrue(r.findings().stream().anyMatch(f->f.code().equals("EXCEL_DATE_POLICY_PENDING")));}
 @Test void missingRequiredAndInvalidDateAreErrors(){var r=checker.check(artifact(Map.of(1,"SW",10,"2025-02-29"),false));assertEquals(1,r.errorRows());assertTrue(r.findings().stream().anyMatch(f->f.code().equals("DATE_INVALID")));}
 @Test void sshAndLoadBalancerStayPending(){var r=checker.check(artifact(Map.of(0,"Synthetic",1,"FW",2,"192.0.2.1",13,"SSH",16,"fw_adc"),false));assertEquals(0,r.errorRows());assertEquals(1,r.warningRows());assertEquals(2,r.findings().size());assertThrows(UnsupportedOperationException.class,()->r.findings().clear());}
 @Test void oldFifteenColumnSchemaCannotBeMisinterpreted(){var p=artifact(Map.of(0,"Synthetic"),false);var old=new ParsedArtifact(p.state(),p.detectedFormat(),p.sheets(),p.selectedSheetIndex(),p.headerRow(),p.dateEpoch(),p.columns().subList(0,15),p.rows(),p.parserVersion(),p.resourcePolicyVersion());assertThrows(IllegalArgumentException.class,()->checker.check(old));}
}
