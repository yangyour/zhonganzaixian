package cn.mop.domain.assetexport;

import java.time.Instant;
import java.util.List;
import cn.mop.domain.assetexport.AssetExportJobRepository.Access;
import cn.mop.domain.assetexport.AssetExportPublicationRepository.Publication;

/** Durable own-account export messages. Storage availability is separate from explicit reading or external delivery. */
public interface ExportInboxRepository {
    record Message(String jobId,String tenantId,String recipientId,long permissionVersion,long publishedVersion,
                   String sha256,long sizeBytes,int rowCount,Instant issuedAt,Instant expiresAt,long version,Instant readAt) {
        public AssetExportDownloadGrant.Binding binding(){return new AssetExportDownloadGrant.Binding(jobId,tenantId,recipientId,permissionVersion,publishedVersion,sha256);}
    }
    record Page(List<Message> items,boolean hasMore,long unreadCount,Instant observedAt){public Page{items=List.copyOf(items);}}
    void recordPublished(Access access,Publication publication);
    Page list(Access access,int limit,Instant beforeTime,String beforeId,boolean unreadOnly);
    void authorizeRead(Access access,String jobId);
    Message markRead(Access access,String jobId,long expectedVersion);
    List<String> readinessProblems();
}
