package cn.mop.domain.contact;
import cn.mop.domain.contact.CustomerContactRepository.Access;
import java.time.*;
import java.util.*;
/** Durable create-only jobs, not storage uploads, identity provisioning or notification delivery. */
public interface ContactImportRepository {
    record ResultLink(int rowNumber,String contactId,long contactVersion) {}
    record Job(String id,String tenantId,String createdBy,long permissionVersion,String inputSha256,
               ContactImportJobState state,long version,Instant createdAt,Instant updatedAt,List<ResultLink> results) {
        public Job{results=List.copyOf(results);}
    }
    record Claim(String jobId,String worker,long fencingToken) {}
    void authorizeAcceptance(Access access);
    Job accept(Access access,List<CustomerContact> explicitRows);
    Job get(Access access,String id);
    List<Job> list(Access access,int limit,Instant beforeCreatedAt,String beforeId);
    Optional<Claim> claimNext(String worker,Duration lease);
    /** Targeted internal worker claim; completion still rechecks captured current authorization. */
    Optional<Claim> claim(String jobId,String worker,Duration lease);
    /** A false result is stale/not executed, not a claim that the business job failed. */
    boolean process(Claim claim);
    List<String> readinessProblems();
}
