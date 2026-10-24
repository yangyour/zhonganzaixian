package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pure immutable-proof verification fixtures, not authentication or live PostgreSQL evidence. */
class PostgresMonitoringRecoveryEvidenceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String EVENT = "10000000-0000-4000-8000-000000000004";
    static final String ASSET = "10000000-0000-4000-8000-000000000005";
    static final String ACTOR = "10000000-0000-4000-8000-000000000006";
    static final String RELEASE = "10000000-0000-4000-8000-000000000007";
    static final UUID PROOF = UUID.fromString("10000000-0000-4000-8000-000000000008");
    static final UUID NAMESPACE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    static final class Fixture {
        final Instant candidate;
        final Instant confirmed;
        final Instant problem;
        final ObjectNode proof;

        Fixture(Instant candidate) {
            this.candidate = candidate;
            confirmed = candidate.plusSeconds(2);
            problem = candidate.minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
            proof = JSON.createObjectNode();
            proof.put("schema_version", 2).put("event_id", EVENT).put("tenant_id", TENANT).put("asset_id", ASSET)
                    .put("event_fingerprint", "synthetic-fingerprint").put("rule_code", "RECOVERY")
                    .put("source_namespace", NAMESPACE.toString()).put("source_address", "192.0.2.1")
                    .put("problem_boundary_at", problem.toString()).put("eligible_from", problem.toString())
                    .put("history_from", problem.minusSeconds(1).toString()).put("history_to_exclusive", confirmed.plusSeconds(1).toString())
                    .put("transition_predecessor_at", problem.toString()).put("event_version_before", 1)
                    .put("policy_release_id", RELEASE).put("policy_release_version", 1).put("policy_definition_hash", "d".repeat(64))
                    .put("source_binding_sha256", "c".repeat(64)).put("source", "ZABBIX_SIMPLE_CHECK")
                    .put("sample_unit", "").put("source_points_read", 2).put("sample_selection", "COMPLETE_RETURNED_SOURCE_WINDOW");
            proof.set("rule", JSON.valueToTree(Map.of("metric", "icmp", "comparison", "GE", "threshold", "1", "unit", "",
                    "hold_seconds", "0", "max_gap_seconds", "60", "minimum_samples", 1)));
            proof.set("samples", JSON.valueToTree(List.of(Map.of("recorded_at", problem.toString(), "quality", "KNOWN", "value", "0"),
                    Map.of("recorded_at", candidate.toString(), "quality", "KNOWN", "value", "1"))));
            var result = proof.putObject("result");
            result.put("tenant_id", TENANT).put("actor_id", ACTOR).put("permission_version", 1).put("event_id", EVENT)
                    .put("event_version", 2).put("proof_id", PROOF.toString()).put("status", "RECOVERY_CONFIRMED")
                    .put("applied", true).put("candidate_recovered_at", candidate.toString()).put("recovered_at", candidate.toString())
                    .put("confirmed_at", confirmed.toString()).put("observed_at", confirmed.toString())
                    .put("evidence_version", 2).put("recovery_time_basis", "SOURCE_NTH_MATCHING_SAMPLE")
                    .put("policy_release_id", RELEASE).put("policy_release_version", 1)
                    .put("latest_sample_at", candidate.toString()).put("span_started_at", candidate.toString())
                    .put("span_seconds", "0").put("matching_samples", 1).put("source_binding_sha256", "c".repeat(64))
                    .put("source", "ZABBIX_SIMPLE_CHECK");
        }

        byte[] bytes() throws Exception {
            return JSON.writeValueAsBytes(proof);
        }

        PostgresMonitoringRecoveryEvidence.Stored stored() throws Exception {
            return new PostgresMonitoringRecoveryEvidence.Stored(TENANT, EVENT, ASSET, "192.0.2.1", "RECOVERY",
                    "synthetic-fingerprint", 2, problem, candidate.truncatedTo(ChronoUnit.MILLIS),
                    candidate.truncatedTo(ChronoUnit.MILLIS), candidate.truncatedTo(ChronoUnit.MILLIS),
                    confirmed.truncatedTo(ChronoUnit.MILLIS), PROOF, NAMESPACE, candidate, confirmed,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes())), ACTOR, 1);
        }

        String releaseJson() throws Exception {
            var row = ((ObjectNode) proof.path("rule")).deepCopy();
            row.put("asset_id", ASSET).put("rule_code", "RECOVERY");
            return JSON.writeValueAsString(Map.of("recovery_observation", Map.of("schema_version", 1, "rules", List.of(row))));
        }
    }

    @Test
    void exactSourceNanosSurviveVerifiedRawAndSummaryBinding() throws Exception {
        var fixture = new Fixture(Instant.parse("2026-10-23T09:25:00.000000001Z"));
        var result = PostgresMonitoringRecoveryEvidence.verify(fixture.stored(), fixture.bytes());
        assertEquals(fixture.candidate, result.recoveredAt());
        assertEquals(PROOF, result.proofId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "tenant_id", "asset_id", "event_fingerprint", "rule_code", "source_namespace", "source_address"})
    void evenHashConsistentProofCannotBeReboundToAnotherEventAssetOrSource(String field) throws Exception {
        var fixture = new Fixture(Instant.parse("2026-10-23T09:25:00Z"));
        fixture.proof.put(field, UUID.randomUUID().toString());
        assertEquals("RECOVERY_EVIDENCE_INVALID", assertThrows(DomainException.class,
                () -> PostgresMonitoringRecoveryEvidence.verify(fixture.stored(), fixture.bytes())).code);
    }

    @Test
    void legacySchemaBodyCorruptionAndShiftedCandidateCannotAuthorizeStop() throws Exception {
        var fixture = new Fixture(Instant.parse("2026-10-23T09:25:00.000000001Z"));
        var stored = fixture.stored();
        fixture.proof.put("schema_version", 1);
        assertThrows(DomainException.class, () -> PostgresMonitoringRecoveryEvidence.verify(fixture.stored(), fixture.bytes()));
        assertThrows(DomainException.class, () -> PostgresMonitoringRecoveryEvidence.verify(stored, fixture.bytes()));
        fixture.proof.put("schema_version", 2);
        ((ObjectNode) fixture.proof.path("result")).put("recovered_at", fixture.candidate.truncatedTo(ChronoUnit.MILLIS).toString());
        assertThrows(DomainException.class, () -> PostgresMonitoringRecoveryEvidence.verify(fixture.stored(), fixture.bytes()));
    }

    @Test
    void healthyTailWithoutFaultPredecessorCannotValidateEvenWithFreshHashes() throws Exception {
        var fixture = new Fixture(Instant.parse("2026-10-23T09:25:00Z"));
        ((ObjectNode) fixture.proof.path("samples").get(0)).put("value", "1");
        assertThrows(DomainException.class, () -> PostgresMonitoringRecoveryEvidence.verify(fixture.stored(), fixture.bytes()));
    }

    @Test
    void changedCurrentSummaryOrRawSourceTimeCannotReuseTheOriginalProof() throws Exception {
        var fixture = new Fixture(Instant.parse("2026-10-23T09:25:00.000000001Z"));
        var original = fixture.stored();
        for (boolean changeRaw : List.of(false, true)) {
            var changed = new PostgresMonitoringRecoveryEvidence.Stored(original.tenant(), original.event(), original.asset(),
                    original.address(), original.ruleCode(), original.fingerprint(), original.eventVersion(), original.problemBoundary(),
                    changeRaw ? original.summaryRecovery() : original.summaryRecovery().plusMillis(1), original.lastOccurred(),
                    original.memberOccurred(), original.memberRecorded(), original.proofId(), original.sourceNamespace(),
                    changeRaw ? original.occurred().plusNanos(1) : original.occurred(), original.received(),
                    original.payloadSha256(), original.auditActor(), original.auditCount());
            assertThrows(DomainException.class, () -> PostgresMonitoringRecoveryEvidence.verify(changed, fixture.bytes()));
        }
    }
}
