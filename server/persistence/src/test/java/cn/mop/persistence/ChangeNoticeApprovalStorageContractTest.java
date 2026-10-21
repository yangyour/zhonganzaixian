package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated resource/parser checks only; these do not execute PostgreSQL or prove its guards. */
class ChangeNoticeApprovalStorageContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String id="55000000-0000-4000-8000-000000000001";
    @Test void reviewedCandidateAndGuardHashesMatch()throws Exception {ChangeNoticeApprovalSchemaVerifier.resource();}
    @Test void explicitReviewerSetIsRequired() {assertEquals(Set.of(UUID.fromString(id)),PostgresChangeNoticeApprovalRepository.reviewers("{\"reviewer_user_ids\":[\""+id+"\"]}",mapper));}
    @Test void missingMalformedOrRoleBasedPolicyNeverCreatesDefaultAuthority() {
        for(String json:List.of("null","[]","{}","{\"role\":\"padmin\"}","{\"reviewer_user_ids\":[]}","{\"reviewer_user_ids\":null}","{\"reviewer_user_ids\":[1]}","{\"reviewer_user_ids\":[\"not-a-uuid\"]}","{\"reviewer_user_ids\":[\""+id+"\",\""+id+"\"]}","{\"reviewer_user_ids\":[\""+id+"\"],\"default_role\":\"padmin\"}","not json","{\"reviewer_user_ids\":[\""+id+"\"],\"reviewer_user_ids\":[\""+id+"\"]}","{\"reviewer_user_ids\":[\""+id+"\"]} {}")) {
            var e=assertThrows(DomainException.class,()->PostgresChangeNoticeApprovalRepository.reviewers(json,mapper),json);
            assertEquals(422,e.status);assertEquals("CONFIGURATION_PENDING",e.code);
        }
    }
    @Test void maximumReviewerCountIsTechnicalBound()throws Exception {
        List<String> ids=new ArrayList<>();for(int n=0;n<1001;n++)ids.add(UUID.randomUUID().toString());
        String json=mapper.writeValueAsString(Map.of("reviewer_user_ids",ids));assertThrows(DomainException.class,()->PostgresChangeNoticeApprovalRepository.reviewers(json,mapper));
    }
    @Test void viewActionListIsImmutableAndCannotAcceptNullSnapshot() {
        assertThrows(NullPointerException.class,()->new cn.mop.domain.change.ChangeNoticeApprovalRepository.View(null,List.of()));
        var draft=new cn.mop.domain.change.ChangeNoticeDraft(UUID.fromString(id),UUID.randomUUID(),null,"SYNTHETIC",java.time.Instant.parse("2026-10-19T00:00:00Z"),null,List.of(UUID.randomUUID()));
        var snapshot=cn.mop.domain.change.ChangeNoticeApproval.create(UUID.randomUUID(),1,draft,java.time.Instant.parse("2026-10-19T00:00:00Z"));
        var input=new ArrayList<>(List.of(cn.mop.domain.change.ChangeNoticeApproval.Action.SUBMIT));var view=new cn.mop.domain.change.ChangeNoticeApprovalRepository.View(snapshot,input);input.clear();assertEquals(1,view.allowedActions().size());assertThrows(UnsupportedOperationException.class,()->view.allowedActions().clear());
    }
    @Test void checkAttestationRejectsSameCountWeakeningOrRenaming() {
        var expected=ChangeNoticeApprovalSchemaVerifier.CHECK_HASHES;assertEquals(11,expected.size());ChangeNoticeApprovalSchemaVerifier.requireCheckHashes(expected);
        var altered=new HashMap<>(expected);String first=altered.keySet().iterator().next();altered.put(first,"0".repeat(64));assertThrows(IllegalStateException.class,()->ChangeNoticeApprovalSchemaVerifier.requireCheckHashes(altered));
        altered.clear();altered.putAll(expected);String value=altered.remove(first);altered.put("renamed",value);assertThrows(IllegalStateException.class,()->ChangeNoticeApprovalSchemaVerifier.requireCheckHashes(altered));
    }
    @Test void migrationIsAppendOnlyAndCannotActivateNotices()throws Exception {
        try(var in=getClass().getResourceAsStream("/db/migration/"+ChangeNoticeApprovalSchemaVerifier.SCRIPT)) {
            String sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
            for(String required:List.of("FOREIGN KEY(tenant_id,notice_id,draft_revision)","d.content_sha256<>NEW.content_sha256","NEW.version<>p.version+1","m.permission_version=NEW.permission_version","policy.id<>NEW.review_policy_release_id","NEW.actor_id=h.applicant_id","BEFORE INSERT OR UPDATE OR DELETE","CHECK(NOT effective)","CHECK(NOT device_execution_allowed)","CHECK(NOT alert_suppression_allowed)","GRANT SELECT,INSERT ON public.mop_change_notice_approval_event"))assertTrue(sql.contains(required),required);
            assertFalse(sql.contains("GRANT UPDATE")); assertFalse(sql.contains("ALTER TABLE public.mop_change_notice_revision"));
        }
    }
}
