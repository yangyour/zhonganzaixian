package cn.mop.domain.assetimport.source;

import cn.mop.domain.DomainException;
import java.util.Map;

/** Durable source/parse/mapping staging only. This port never imports assets or reserves quota. */
public interface AssetImportSourceRepository {
    /** Supplied from the trusted current session, not a JSON body; the store reauthorizes every use. */
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {
        public Access {
            if(!uuid(actorId)||!uuid(tenantId)||permissionVersion<1||traceId==null||!traceId.matches("[A-Za-z0-9._:-]{1,128}"))
                throw new DomainException(400,"VALIDATION_ERROR","导入源授权上下文无效");
        }
        private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    }
    /** Writes require the existing PlatformRepository.idempotent transaction; audit is appended inside. */
    Map<String,Object> acceptSource(Access access,String filename,ImportFormat format,byte[] original);
    Map<String,Object> requestParse(Access access,String sourceId,long expectedVersion,ParseSelection selection);
    Map<String,Object> saveMapping(Access access,String sourceId,MappingRequest mapping);
    /** Owner-only metadata/raw artifact reads, authorized and projected from one read-only RR snapshot. */
    Map<String,Object> source(Access access,String sourceId);
    Map<String,Object> operation(Access access,String operationId);
    boolean ready();
    /** Only this project's import jobs; no ticket/photo outbox is claimed. */
    boolean processNext(String worker,AssetImportObjectStore store,AssetImportParser parser);
}
