package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact versioned bytes freeze both full source bodies and computed output, never client aggregates. */
public final class InspectionQuarterlyReportDocumentCodec {
    public static final String FORMAT="MOP_INSPECTION_QUARTERLY_DOCUMENT_V1";
    public static final int MAX_BYTES=40*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(12000).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private InspectionQuarterlyReportDocumentCodec(){}
    public static byte[] encode(String collectionId,InspectionQuarterlyReport report){
        try{
            byte[] source=InspectionSourceDocumentCodec.encode(report.manifest());
            ObjectNode root=JSON.createObjectNode();root.put("format",FORMAT);root.put("tenant_id",report.tenantId().toString());
            root.put("source_collection_id",collectionId);root.put("source_document_sha256",InspectionSourceDocumentCodec.hash(source));
            root.put("source_manifest_sha256",report.manifest().fingerprint());root.put("report_sha256",report.fingerprint());
            root.put("configuration_source","REQUEST_DRAFT_NOT_APPROVED");root.set("configuration",JSON.valueToTree(report.configuration()));
            root.put("year",report.period().year());root.put("quarter",report.period().quarter());root.put("completeness",report.completeness().name());
            root.set("source_document",JSON.readTree(source));var output=root.putObject("output");
            output.put("generation_status","DRAFT");output.put("report_published",false);output.put("report_publishable",false);output.put("evidence_access_granted",false);output.putNull("tenant_asset_population");
            output.set("summary",JSON.valueToTree(report.summary()));output.set("pending_reasons",JSON.valueToTree(report.pendingReasons()));
            var rows=output.putArray("rows");for(var row:report.rows()){
                var n=rows.addObject();n.put("asset_id",row.assetId().toString());n.put("status",row.status().name());n.put("asset_name",row.assetName());
                n.set("summary",JSON.valueToTree(row.summary()));n.set("items",JSON.valueToTree(row.items()));
                n.set("selected",row.selected()==null?JSON.nullNode():JSON.valueToTree(row.selected().reference()));
                n.set("candidates",JSON.valueToTree(row.candidates().stream().map(InspectionSourceSnapshot::reference).toList()));
            }
            output.set("findings",JSON.valueToTree(report.findings()));byte[] bytes=JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES)throw new DomainException(413,"INSPECTION_REPORT_DOCUMENT_LIMIT","报告冻结文档超过40MiB技术保护");return bytes;
        }catch(DomainException e){throw e;}catch(Exception e){throw corrupt();}
    }
    public static String decode(byte[] bytes,UUID tenant,String collection,String sourceHash,String manifestHash,String reportHash,String documentHash,int year,int quarter){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES||!InspectionSourceDocumentCodec.hash(bytes).equals(documentHash))throw corrupt();
        try{
            var root=JSON.readTree(bytes);
            if(!root.path("tenant_id").asText().equals(tenant.toString())||!root.path("source_collection_id").asText().equals(collection)
                ||!root.path("source_document_sha256").asText().equals(sourceHash)||!root.path("source_manifest_sha256").asText().equals(manifestHash)
                ||!root.path("report_sha256").asText().equals(reportHash))throw corrupt();
            byte[] source=JSON.writeValueAsString(root.get("source_document")).getBytes(StandardCharsets.UTF_8);var manifest=InspectionSourceDocumentCodec.decode(source,tenant,sourceHash,manifestHash);
            var config=JSON.treeToValue(root.get("configuration"),InspectionQuarterlyReport.Configuration.class);
            if(!root.path("year").isInt()||!root.path("quarter").isInt()||root.path("year").intValue()!=year||root.path("quarter").intValue()!=quarter)throw corrupt();
            var report=InspectionQuarterlyReport.generate(tenant,manifest,new InspectionQuarterlyReport.Period(root.get("year").intValue(),root.get("quarter").intValue()),config,InspectionQuarterlyReport.Completeness.COMPLETE_EXPLICIT_SELECTION);
            if(!report.fingerprint().equals(reportHash)||!Arrays.equals(bytes,encode(collection,report)))throw corrupt();
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(Exception e){throw corrupt();}
    }
    private static DomainException corrupt(){return new DomainException(503,"INSPECTION_REPORT_DOCUMENT_INVALID","报告冻结输入、输出、范围或摘要不一致");}
}
