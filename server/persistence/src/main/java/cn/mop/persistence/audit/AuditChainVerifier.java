package cn.mop.persistence.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Read-only verification of the existing V1 chain encoding. Not an external trust anchor or WORM proof. */
public final class AuditChainVerifier {
    public static final int MAX_RANGE=1000;
    private static final String ZERO="0".repeat(64);
    private static final Set<String> KEYS=Set.of("id","occurred_at","actor_id","actor_name","tenant_id","action","resource_type","resource_id","result","reason","trace_id","source","sequence","previous_hash","entry_hash");
    private static final ObjectMapper JSON=new ObjectMapper();
    public enum Failure { NONE, MISSING_RECORD, SEQUENCE_MISMATCH, ROW_PAYLOAD_MISMATCH, HASH_MISMATCH, PREVIOUS_HASH_MISMATCH, ANCHOR_INVALID, DUPLICATE_ID, UNEXPECTED_RECORD }
    public record Row(String id,String namespace,String tenantId,long sequence,String previousHash,String entryHash,JsonNode payload){
        public Row { payload=payload==null?null:payload.deepCopy(); }
        @Override public JsonNode payload(){return payload==null?null:payload.deepCopy();}
    }
    public record Result(boolean valid,long fromSequence,long toSequence,int verifiedRecords,Long failureSequence,Failure failure,String anchorHash,String finalHash,boolean externallyAnchored) {}
    private AuditChainVerifier(){}
    /** Input rows must be ascending and complete; optional previous row binds a partial range to its local predecessor. */
    public static Result verify(String tenant,long from,long to,Row anchor,List<Row> rows){
        if(!uuid(tenant)||from<1||to<from||to-from>=MAX_RANGE||rows==null||rows.size()>MAX_RANGE||from==1&&anchor!=null)throw new IllegalArgumentException("Invalid bounded audit range");
        String previous=ZERO;
        if(from>1){if(anchor==null||anchor.sequence()!=from-1||rowFailure(tenant,anchor)!=Failure.NONE)return failed(from,to,0,from-1,Failure.ANCHOR_INVALID,null,null);previous=anchor.entryHash();}
        String initial=previous;long expected=from;int verified=0;Set<String> ids=new HashSet<>();if(anchor!=null)ids.add(anchor.id());
        for(Row row:rows){
            if(expected>to)return failed(from,to,verified,expected,Failure.UNEXPECTED_RECORD,initial,previous);
            if(row==null||row.sequence()!=expected)return failed(from,to,verified,expected,Failure.SEQUENCE_MISMATCH,initial,previous);
            Failure failure=rowFailure(tenant,row);if(failure!=Failure.NONE)return failed(from,to,verified,expected,failure,initial,previous);
            if(!ids.add(row.id()))return failed(from,to,verified,expected,Failure.DUPLICATE_ID,initial,previous);
            if(!previous.equals(row.previousHash()))return failed(from,to,verified,expected,Failure.PREVIOUS_HASH_MISMATCH,initial,previous);
            previous=row.entryHash();verified++;expected++;
        }
        if(verified!=to-from+1)return failed(from,to,verified,expected,Failure.MISSING_RECORD,initial,previous);
        return new Result(true,from,to,verified,null,Failure.NONE,initial,previous,false);
    }
    private static Result failed(long from,long to,int verified,long sequence,Failure failure,String anchor,String last){return new Result(false,from,to,verified,sequence,failure,anchor,last,false);}
    /** Verifies a single stored envelope/hash only, not its predecessor chain. */
    public static Failure verifyLocalRecord(String tenant,Row row){return row==null?Failure.ROW_PAYLOAD_MISMATCH:rowFailure(tenant,row);}
    private static Failure rowFailure(String tenant,Row row){
        JsonNode p=row.payload;
        if(!uuid(row.id())||!tenant.equals(row.namespace())||!tenant.equals(row.tenantId())||row.sequence()<1||!hash(row.previousHash())||!hash(row.entryHash())||p==null||!p.isObject()||p.size()!=KEYS.size())return Failure.ROW_PAYLOAD_MISMATCH;
        Set<String> keys=new HashSet<>();p.fieldNames().forEachRemaining(keys::add);if(!keys.equals(KEYS))return Failure.ROW_PAYLOAD_MISMATCH;
        if(!textEquals(p,"id",row.id())||!textEquals(p,"tenant_id",tenant)||!textEquals(p,"previous_hash",row.previousHash())||!textEquals(p,"entry_hash",row.entryHash())||!p.get("sequence").isIntegralNumber()||!p.get("sequence").canConvertToLong()||p.get("sequence").longValue()!=row.sequence()||!textEquals(p,"source","postgresql"))return Failure.ROW_PAYLOAD_MISMATCH;
        for(String key:List.of("occurred_at","actor_name","action","resource_type","result","reason","trace_id"))if(!p.get(key).isTextual())return Failure.ROW_PAYLOAD_MISMATCH;
        for(String key:List.of("actor_id","resource_id"))if(!p.get(key).isNull()&&!p.get(key).isTextual())return Failure.ROW_PAYLOAD_MISMATCH;
        if(!p.get("actor_id").isNull()&&!uuid(p.get("actor_id").textValue()))return Failure.ROW_PAYLOAD_MISMATCH;
        return MessageDigest.isEqual(calculateHash(p).getBytes(StandardCharsets.US_ASCII),row.entryHash().getBytes(StandardCharsets.US_ASCII))?Failure.NONE:Failure.HASH_MISMATCH;
    }
    /** Exact V1 rule: previous_hash + top-level key-sorted JSON excluding entry_hash, UTF-8 SHA-256. */
    public static String calculateHash(JsonNode payload){
        if(payload==null||!payload.isObject()||!payload.path("previous_hash").isTextual()||!hash(payload.path("previous_hash").textValue()))throw new IllegalArgumentException("Invalid audit payload");
        ObjectNode canonical=JSON.createObjectNode();TreeSet<String> keys=new TreeSet<>();payload.fieldNames().forEachRemaining(keys::add);keys.remove("entry_hash");keys.forEach(key->canonical.set(key,payload.get(key).deepCopy()));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((payload.get("previous_hash").textValue()+canonical.toString()).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static boolean textEquals(JsonNode node,String field,String value){return node.get(field).isTextual()&&value.equals(node.get(field).textValue());}
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
}
