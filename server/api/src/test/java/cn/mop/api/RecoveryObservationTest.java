package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.RecoveryObservation;
import cn.mop.domain.monitoring.RecoveryObservation.*;
import cn.mop.persistence.MonitoringRecoveryRuleSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryObservationTest {
    static final Instant START = Instant.parse("2026-10-23T00:00:00Z");
    static Rule rule() {
        return new Rule("icmp", Comparison.GE, BigDecimal.ONE, "", new BigDecimal("60"), new BigDecimal("60"), 2);
    }
    static Sample known(long seconds) {
        return new Sample(START.plusSeconds(seconds), Quality.KNOWN, BigDecimal.ONE);
    }
    static Result evaluate(List<Sample> samples, long now) {
        return RecoveryObservation.evaluate(rule(), "", START, START.plusSeconds(now), samples);
    }

    @Test
    void matchingBoundaryRequiresRealLastPointAndMinimumCount() {
        assertEquals(Status.OBSERVING, evaluate(List.of(known(0), known(60)), 60).status());
        assertEquals(Status.CONFIRMABLE, evaluate(List.of(known(0), known(60), known(120)), 120).status());
        assertEquals(Status.OBSERVING, evaluate(List.of(known(0)), 60).status());
    }

    @Test
    void customerTimelineStopsAtSourceT2OnlyAfterTheFollowingObservationWindowPasses() {
        var rule=new Rule("icmp",Comparison.GE,BigDecimal.ONE,"",new BigDecimal("300"),new BigDecimal("60"),3);
        var points=new java.util.ArrayList<Sample>();
        for(long second:new long[]{1490,1495,1500,1560,1620,1680,1740})points.add(known(second));
        var before=RecoveryObservation.evaluate(rule,"",START,START.plusSeconds(1799),points);
        assertEquals(Status.OBSERVING,before.status());assertEquals(START.plusSeconds(1500),before.candidateRecoveredAt());
        points.add(known(1800));
        var after=RecoveryObservation.evaluate(rule,"",START,START.plusSeconds(1805),points);
        assertEquals(Status.CONFIRMABLE,after.status());
        assertEquals(START.plusSeconds(1500),after.candidateRecoveredAt());
        assertNotEquals(START.plusSeconds(1805),after.candidateRecoveredAt());
    }

    @Test
    void staleTailIsNotAddedToObservedSpan() {
        assertEquals(Status.STALE_LATEST, evaluate(List.of(known(0), known(60)), 121).status());
        assertEquals(Status.OBSERVING, evaluate(List.of(known(0), known(30)), 60).status());
    }

    @Test
    void unknownCollectorErrorAndBrokenConditionResetTheSpan() {
        for (Sample interrupt : List.of(new Sample(START.plusSeconds(30), Quality.UNKNOWN, null),
                new Sample(START.plusSeconds(30), Quality.COLLECTOR_ERROR, null),
                new Sample(START.plusSeconds(30), Quality.KNOWN, BigDecimal.ZERO))) {
            Result result = evaluate(List.of(known(0), interrupt, known(60)), 60);
            assertEquals(Status.OBSERVING, result.status());
            assertEquals(START.plusSeconds(60), result.spanStartedAt());
            assertEquals(1, result.matchingSamples());
        }
    }

    @Test
    void explicitGapEqualityPassesAndLargerGapRestarts() {
        assertEquals(Status.OBSERVING, evaluate(List.of(known(0), known(60)), 60).status());
        assertEquals(Status.CONFIRMABLE, evaluate(List.of(known(0), known(60), known(120)), 120).status());
        assertEquals(Status.OBSERVING, evaluate(List.of(known(0), known(61)), 61).status());
    }

    @Test
    void nanosecondSourceTimestampIsPreservedWithoutRounding() {
        var points = List.of(new Sample(START.plusNanos(1), Quality.KNOWN, BigDecimal.ONE), known(60));
        Result result = evaluate(points, 60);
        assertEquals(Status.OBSERVING, result.status());
        assertEquals(new BigDecimal("59.999999999"), result.spanSeconds());
    }

    @Test
    void samplesBeforeCurrentFaultOrPolicyAreExcluded() {
        Result result = RecoveryObservation.evaluate(rule(), "", START.plusSeconds(30), START.plusSeconds(60),
            List.of(known(0), known(60)));
        assertEquals(Status.OBSERVING, result.status());
        assertEquals(1, result.matchingSamples());
    }

    @Test
    void noSamplesUnknownLatestAndNonRecoveryRemainDistinct() {
        assertEquals(Status.NO_SAMPLES, evaluate(List.of(), 60).status());
        assertEquals(Status.UNKNOWN_LATEST, evaluate(List.of(new Sample(START, Quality.UNKNOWN, null)), 0).status());
        assertEquals(Status.NOT_RECOVERED, evaluate(List.of(new Sample(START, Quality.KNOWN, BigDecimal.ZERO)), 0).status());
    }

    @Test
    void missingValuesNeverReceiveDefaultDurations() {
        var pending = new Rule("icmp", Comparison.GE, BigDecimal.ONE, "", null, new BigDecimal("60"), 2);
        assertEquals(Status.CONFIG_PENDING, RecoveryObservation.evaluate(pending, "", START, START.plusSeconds(60),
            List.of(known(0), known(60))).status());
    }

    @Test
    void wrongUnitFutureAndUnorderedSamplesAreRejected() {
        assertEquals("RECOVERY_UNIT_MISMATCH", assertThrows(DomainException.class,
            () -> RecoveryObservation.evaluate(rule(), "s", START, START, List.of(known(0)))).code);
        assertThrows(DomainException.class, () -> evaluate(List.of(known(1)), 0));
        assertThrows(DomainException.class, () -> evaluate(List.of(known(0), known(0)), 0));
        assertThrows(DomainException.class, () -> evaluate(List.of(known(1), known(0)), 1));
    }

    @Test
    void policyCodecRequiresExplicitAssetRuleAndPreservesNullDraftValues() throws Exception {
        var json = new ObjectMapper();
        String asset = "10000000-0000-4000-8000-000000000001";
        var matrix = json.readTree("{\"recovery_observation\":{\"schema_version\":1,\"rules\":[{"
            + "\"asset_id\":\"" + asset + "\",\"rule_code\":\"ICMP\",\"metric\":\"icmp\","
            + "\"comparison\":\"GE\",\"threshold\":\"1\",\"unit\":\"\",\"hold_seconds\":null,"
            + "\"max_gap_seconds\":\"60\",\"minimum_samples\":2}]}}");
        MonitoringRecoveryRuleSet.validate(matrix);
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class,
            () -> MonitoringRecoveryRuleSet.resolve(matrix, asset, "ICMP")).code);
        ((com.fasterxml.jackson.databind.node.ObjectNode) matrix.at("/recovery_observation/rules/0")).put("hold_seconds", "60");
        assertEquals(rule(), MonitoringRecoveryRuleSet.resolve(matrix, asset, "ICMP"));
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class,
            () -> MonitoringRecoveryRuleSet.resolve(matrix, asset, "OTHER")).code);
    }
}
