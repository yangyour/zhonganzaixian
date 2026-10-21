package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
class InspectionQuarterlyReportDocumentCodecTest {
    final InspectionSourceDocumentCodecTest f=new InspectionSourceDocumentCodecTest();
    final ObjectMapper json=new ObjectMapper();
    final String collection=f.id(90).toString();
    InspectionQuarterlyReport report(){return InspectionQuarterlyReport.generate(f.id(1),f.manifest(),new InspectionQuarterlyReport.Period(2026,4),InspectionQuarterlyReport.Configuration.pending(),InspectionQuarterlyReport.Completeness.COMPLETE_EXPLICIT_SELECTION);}
    byte[] bytes(){return InspectionQuarterlyReportDocumentCodec.encode(collection,report());}
    String decode(byte[] bytes){return InspectionQuarterlyReportDocumentCodec.decode(bytes,f.id(1),collection,InspectionSourceDocumentCodec.hash(f.bytes()),f.manifest().fingerprint(),report().fingerprint(),InspectionSourceDocumentCodec.hash(bytes),2026,4);}
    void bad(Consumer<ObjectNode> edit)throws Exception{var root=(ObjectNode)json.readTree(bytes());edit.accept(root);assertEquals(503,assertThrows(DomainException.class,()->decode(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8))).status);}
    @Test void exactBytesRoundTripFullSourceAndPendingOutput()throws Exception{
        byte[] bytes=bytes();assertEquals(new String(bytes,StandardCharsets.UTF_8),decode(bytes));assertArrayEquals(bytes,bytes());
        var root=json.readTree(bytes);assertEquals(json.readTree(f.bytes()),root.get("source_document"));assertTrue(root.path("configuration").path("timeZone").isNull());
        assertEquals("REQUEST_DRAFT_NOT_APPROVED",root.path("configuration_source").asText());assertEquals("DRAFT",root.path("output").path("generation_status").asText());
        assertTrue(root.path("output").path("tenant_asset_population").isNull());assertEquals(1,root.path("output").path("summary").path("unresolvedAssets").intValue());assertEquals(0,root.path("output").path("summary").path("normal").intValue());
    }
    @Test void configuredUnknownTimeRemainsUnknownRatherThanHealthy()throws Exception{
        var config=new InspectionQuarterlyReport.Configuration("SYNTHETIC_REQUEST","UTC",InspectionQuarterlyReport.DuplicateRule.REQUIRE_SINGLE_SOURCE,InspectionQuarterlyReport.ArchivedRule.INCLUDE,"test-only","test-only");
        var report=InspectionQuarterlyReport.generate(f.id(1),f.manifest(),new InspectionQuarterlyReport.Period(2026,4),config,InspectionQuarterlyReport.Completeness.COMPLETE_EXPLICIT_SELECTION);
        byte[] bytes=InspectionQuarterlyReportDocumentCodec.encode(collection,report);var root=json.readTree(bytes);assertEquals("SOURCE_TIME_UNKNOWN",root.path("output").path("rows").get(0).path("status").asText());
        assertNotEquals(report.fingerprint(),report().fingerprint());assertEquals(new String(bytes,StandardCharsets.UTF_8),InspectionQuarterlyReportDocumentCodec.decode(bytes,f.id(1),collection,InspectionSourceDocumentCodec.hash(f.bytes()),f.manifest().fingerprint(),report.fingerprint(),InspectionSourceDocumentCodec.hash(bytes),2026,4));
    }
    @Test void selectedOutputFreezesFullItemsAndFindings()throws Exception{
        var old=f.manifest().sources().get(0);var content=new InspectionWorksheetContent(old.content().worksheet(),f.time);
        var source=new InspectionSourceSnapshot(old.tenantId(),old.worksheetId(),old.version(),old.createdBy(),old.createdAt(),old.recordedBy(),old.recordedAt(),old.action(),old.revisionArchived(),content);
        var manifest=new InspectionSourceManifest(f.id(1),List.of(source));var config=new InspectionQuarterlyReport.Configuration("SYNTHETIC_REQUEST","UTC",InspectionQuarterlyReport.DuplicateRule.REQUIRE_SINGLE_SOURCE,InspectionQuarterlyReport.ArchivedRule.INCLUDE,"synthetic","synthetic");
        var report=InspectionQuarterlyReport.generate(f.id(1),manifest,new InspectionQuarterlyReport.Period(2026,4),config,InspectionQuarterlyReport.Completeness.COMPLETE_EXPLICIT_SELECTION);
        byte[] bytes=InspectionQuarterlyReportDocumentCodec.encode(collection,report);var root=json.readTree(bytes);
        assertEquals(2,root.path("output").path("rows").get(0).path("items").size());assertEquals(1,root.path("output").path("findings").size());assertEquals(1,root.path("output").path("summary").path("unanswered").intValue());
        assertEquals(new String(bytes,StandardCharsets.UTF_8),InspectionQuarterlyReportDocumentCodec.decode(bytes,f.id(1),collection,InspectionSourceDocumentCodec.hash(InspectionSourceDocumentCodec.encode(manifest)),manifest.fingerprint(),report.fingerprint(),InspectionSourceDocumentCodec.hash(bytes),2026,4));
    }
    @Test void forgedAggregatesOutputRowsAndFlagsRejectEvenWithRehashedOuterBytes()throws Exception{
        bad(r->((ObjectNode)r.path("output").path("summary")).put("normal",999));bad(r->((ObjectNode)r.path("output")).putArray("rows"));bad(r->((ObjectNode)r.path("output")).put("report_published",true));bad(r->((ObjectNode)r.path("output")).put("tenant_asset_population",0));
    }
    @Test void sourceBodyAndExpectedCollectionCannotBeReplaced()throws Exception{bad(r->r.put("source_collection_id",f.id(99).toString()));bad(r->((ObjectNode)r.path("source_document").path("sources").get(0).path("content")).put("asset_name","forged"));bad(r->r.put("source_document_sha256","0".repeat(64)));}
    @Test void explicitConfigurationRevisionAndProvenanceAreFrozen()throws Exception{bad(r->((ObjectNode)r.path("configuration")).put("revision","new"));bad(r->r.put("configuration_source","APPROVED"));bad(r->r.put("completeness","COMPLETE_TENANT"));bad(r->r.put("year",2027));}
    @Test void duplicateKeysTrailingBytesUnknownKeysAndNoncanonicalEncodingReject()throws Exception{
        bad(r->r.put("client_stats",0));String s=new String(bytes(),StandardCharsets.UTF_8);for(String v:List.of(" "+s,s+"{}",s.replace("\"year\":2026","\"year\":2025,\"year\":2026")))assertThrows(DomainException.class,()->decode(v.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void wrongTenantAndMetadataPeriodFailClosed(){byte[] b=bytes();assertThrows(DomainException.class,()->InspectionQuarterlyReportDocumentCodec.decode(b,f.id(2),collection,InspectionSourceDocumentCodec.hash(f.bytes()),f.manifest().fingerprint(),report().fingerprint(),InspectionSourceDocumentCodec.hash(b),2026,4));assertThrows(DomainException.class,()->InspectionQuarterlyReportDocumentCodec.decode(b,f.id(1),collection,InspectionSourceDocumentCodec.hash(f.bytes()),f.manifest().fingerprint(),report().fingerprint(),InspectionSourceDocumentCodec.hash(b),2025,4));}
    @Test void boundedDocumentAndChangedDigestReject(){assertThrows(DomainException.class,()->decode(new byte[0]));assertThrows(DomainException.class,()->decode(new byte[InspectionQuarterlyReportDocumentCodec.MAX_BYTES+1]));byte[] b=bytes();assertThrows(DomainException.class,()->InspectionQuarterlyReportDocumentCodec.decode(b,f.id(1),collection,InspectionSourceDocumentCodec.hash(f.bytes()),f.manifest().fingerprint(),report().fingerprint(),"0".repeat(64),2026,4));}
    @Test void candidateResourceHashesMatch()throws Exception{InspectionQuarterlyReportSchemaVerifier.resource();}
    @Test void creationRejectsImplicitSourceUnknownHashAndNullConfiguration(){var p=new InspectionQuarterlyReport.Period(2026,4);var c=InspectionQuarterlyReport.Configuration.pending();String h="0".repeat(64);assertThrows(DomainException.class,()->new InspectionQuarterlyReportRepository.Creation("x",collection,h,h,p,null));assertThrows(DomainException.class,()->new InspectionQuarterlyReportRepository.Creation("x",collection,"bad",h,p,c));assertThrows(DomainException.class,()->new InspectionQuarterlyReportRepository.Creation("x","latest",h,h,p,c));assertDoesNotThrow(()->new InspectionQuarterlyReportRepository.Creation("x",collection,h,h,p,c));}
}
