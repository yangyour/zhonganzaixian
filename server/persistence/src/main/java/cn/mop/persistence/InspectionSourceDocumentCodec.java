package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Versioned, credential-free source document encoding. No persistence, authority, quarter selection or file grants. */
public final class InspectionSourceDocumentCodec {
    public static final String FORMAT="MOP_INSPECTION_SOURCE_DOCUMENT_V1";
    public static final int MAX_DOCUMENT_BYTES=32*1024*1024;
    private static final int MAX_TOKENS=4_000_000;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private InspectionSourceDocumentCodec(){}
    /** Exact UTF-8 bytes, not JSONB: future storage must retain these bytes without whitespace/key-order normalization. */
    public static byte[] encode(InspectionSourceManifest manifest){
        if(manifest==null)throw corrupt();
        var root=JSON.createObjectNode();root.put("format",FORMAT);root.put("tenant_id",manifest.tenantId().toString());root.put("manifest_sha256",manifest.fingerprint());var sources=root.putArray("sources");
        for(var source:manifest.sources()){
            var node=sources.addObject();node.put("worksheet_id",source.worksheetId().toString());node.put("version",source.version());node.put("created_by",source.createdBy().toString());node.put("created_at",source.createdAt().toString());node.put("recorded_by",source.recordedBy().toString());node.put("recorded_at",source.recordedAt().toString());node.put("action",source.action());node.put("archived",source.revisionArchived());node.put("source_sha256",source.fingerprint());
            var content=node.putObject("content");var sheet=source.content().worksheet();content.put("asset_id",sheet.assetId().toString());content.put("asset_name",sheet.assetName());content.put("reported_performed_at",source.content().reportedPerformedAt()==null?null:source.content().reportedPerformedAt().toString());content.put("content_sha256",source.content().fingerprint());
            var template=content.putObject("template");var snapshot=sheet.template();var definition=snapshot.definition();template.put("id",snapshot.templateId().toString());template.put("version",snapshot.version());template.put("device_type",definition.deviceType());template.put("name",definition.name());template.put("content_sha256",snapshot.contentSha256());var items=template.putArray("items");
            for(var item:definition.items()){var value=items.addObject();value.put("key",item.key());value.put("section",item.section());value.put("label",item.label());value.put("instruction",item.instruction());}
            var observations=content.putArray("observations");for(var row:sheet.rows()){var observation=row.observation();if(observation==null)continue;var value=observations.addObject();value.put("key",row.item().key());value.put("result",observation.result().name());value.put("note",observation.note());var refs=value.putArray("evidence_ids");observation.evidenceIds().forEach(id->refs.add(id.toString()));}
        }
        try{byte[] bytes=JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_DOCUMENT_BYTES)throw new DomainException(413,"INSPECTION_SOURCE_DOCUMENT_LIMIT","来源文档超过32MiB技术编码保护；没有截断或保存");return bytes;}catch(DomainException failure){throw failure;}catch(Exception failure){throw corrupt();}
    }
    /** Caller supplies independently scoped tenant and stored digests; decoding is not a current membership check. */
    public static InspectionSourceManifest decode(byte[] document,UUID expectedTenant,String expectedDocumentSha256,String expectedManifestSha256){
        if(document==null||document.length==0||document.length>MAX_DOCUMENT_BYTES||expectedTenant==null||!digest(expectedDocumentSha256)||!digest(expectedManifestSha256))throw corrupt();byte[] bytes=document.clone();
        if(!hash(bytes).equals(expectedDocumentSha256))throw corrupt();
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(var parser=JSON.getFactory().createParser(text)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>MAX_TOKENS)throw corrupt();}
            var root=JSON.readTree(text);fields(root,"format","tenant_id","manifest_sha256","sources");if(!FORMAT.equals(text(root.get("format")))||!expectedTenant.equals(uuid(root.get("tenant_id")))||!expectedManifestSha256.equals(text(root.get("manifest_sha256"))))throw corrupt();array(root.get("sources"),InspectionSourceManifest.MAX_SOURCES);var sources=new ArrayList<InspectionSourceSnapshot>();
            for(var node:root.get("sources"))sources.add(source(node,expectedTenant));var manifest=new InspectionSourceManifest(expectedTenant,sources);
            if(!manifest.fingerprint().equals(expectedManifestSha256)||!Arrays.equals(bytes,encode(manifest)))throw corrupt();return manifest;
        }catch(Exception failure){throw corrupt();}
    }
    private static InspectionSourceSnapshot source(JsonNode node,UUID tenant){
        fields(node,"worksheet_id","version","created_by","created_at","recorded_by","recorded_at","action","archived","source_sha256","content");var c=node.get("content");fields(c,"asset_id","asset_name","reported_performed_at","content_sha256","template","observations");var t=c.get("template");fields(t,"id","version","device_type","name","content_sha256","items");array(t.get("items"),InspectionTemplate.MAX_ITEMS);var items=new ArrayList<InspectionTemplate.Item>();
        for(var item:t.get("items")){fields(item,"key","section","label","instruction");items.add(new InspectionTemplate.Item(text(item.get("key")),text(item.get("section")),text(item.get("label")),nullableText(item.get("instruction"))));}
        var definition=new InspectionTemplate(text(t.get("device_type")),text(t.get("name")),items);var template=new InspectionTemplate.Snapshot(tenant,uuid(t.get("id")),version(t.get("version")),definition,text(t.get("content_sha256")));array(c.get("observations"),InspectionTemplate.MAX_ITEMS);var observations=new LinkedHashMap<String,InspectionWorksheet.Observation>();
        for(var value:c.get("observations")){fields(value,"key","result","note","evidence_ids");array(value.get("evidence_ids"),10);var refs=new ArrayList<UUID>();for(var ref:value.get("evidence_ids"))refs.add(uuid(ref));var observation=new InspectionWorksheet.Observation(InspectionWorksheet.Result.valueOf(text(value.get("result"))),nullableText(value.get("note")),refs);if(observations.put(text(value.get("key")),observation)!=null)throw corrupt();}
        var content=new InspectionWorksheetContent(new InspectionWorksheet(tenant,uuid(c.get("asset_id")),text(c.get("asset_name")),template,observations),instant(c.get("reported_performed_at"),true));if(!content.fingerprint().equals(text(c.get("content_sha256"))))throw corrupt();
        if(!node.get("archived").isBoolean())throw corrupt();var snapshot=new InspectionSourceSnapshot(tenant,uuid(node.get("worksheet_id")),version(node.get("version")),uuid(node.get("created_by")),instant(node.get("created_at"),false),uuid(node.get("recorded_by")),instant(node.get("recorded_at"),false),text(node.get("action")),node.get("archived").booleanValue(),content);if(!snapshot.fingerprint().equals(text(node.get("source_sha256"))))throw corrupt();return snapshot;
    }
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Source document digest unavailable",impossible);}}
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw corrupt();return value.textValue();}
    private static String nullableText(JsonNode value){if(value==null)throw corrupt();return value.isNull()?null:text(value);}
    private static UUID uuid(JsonNode value){String id=text(value);if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw corrupt();return UUID.fromString(id);}
    private static long version(JsonNode value){if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1||value.longValue()>9_007_199_254_740_990L)throw corrupt();return value.longValue();}
    private static Instant instant(JsonNode value,boolean nullable){if(nullable&&value!=null&&value.isNull())return null;String raw=text(value);Instant instant=Instant.parse(raw);if(!instant.toString().equals(raw))throw corrupt();return instant;}
    private static void array(JsonNode value,int maximum){if(value==null||!value.isArray()||value.size()>maximum)throw corrupt();}
    private static void fields(JsonNode value,String... fields){var expected=Set.of(fields);if(value==null||!value.isObject()||value.size()!=expected.size())throw corrupt();value.fieldNames().forEachRemaining(key->{if(!expected.contains(key))throw corrupt();});}
    private static DomainException corrupt(){return new DomainException(503,"INSPECTION_SOURCE_DOCUMENT_INVALID","冻结来源文档的格式、范围、完整内容或摘要不一致；未提供部分结果");}
}
