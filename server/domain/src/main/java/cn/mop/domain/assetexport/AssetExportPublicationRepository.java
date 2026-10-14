package cn.mop.domain.assetexport;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import java.time.Instant;

/** Explicit publication of immutable prepared bytes; preparation alone never authorizes this action. */
public interface AssetExportPublicationRepository {
    record Publication(Job job,AssetExportJobState.Encoding prepared,String objectId,Instant storedAt,Instant databaseNow) {
        public boolean published(){return job.state()==AssetExportJobState.State.SUCCEEDED&&objectId!=null&&storedAt!=null&&prepared!=null;}
        public String objectKey(){if(!published())throw new IllegalStateException("Export not published");return "asset-exports/"+job.tenantId()+"/"+objectId;}
        public AssetExportDownloadGrant.Binding binding(){if(!published())throw new IllegalStateException("Export not published");return new AssetExportDownloadGrant.Binding(job.id(),job.tenantId(),job.createdBy(),job.permissionVersion(),job.version(),prepared.fileSha256());}
    }
    @FunctionalInterface interface VerifiedWriter {void store(String key,AssetExportPreparedFile file);}
    void authorizePublication(Access access,String id);
    Publication publish(Access access,String id,long expectedVersion,String expectedSha256,VerifiedWriter writer);
    Publication publication(Access access,String id);
    Publication publicationInCommand(Access access,String id);
}
