package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protocol and identity tests only; this suite makes no source or PostgreSQL availability claim. */
class MappedMonitoringProblemTest {

    final UUID pool = UUID.randomUUID();
    final RawMonitoringEvent.Binding binding = new RawMonitoringEvent.Binding(UUID.randomUUID(), UUID.randomUUID());
    final Instant at = Instant.parse("2026-10-24T08:00:00.123456789Z");
    final ObjectMapper json = new ObjectMapper();

    TreeMap<String, Object> envelope() {
        var body = new TreeMap<String, Object>();
        body.put("provider", "ZABBIX_7");
        body.put("source_event_id", "44");
        body.put("trigger_id", "18446744073709551615");
        body.put("clock", at.getEpochSecond());
        body.put("nanoseconds", at.getNano());
        body.put("value", 1);
        body.put("severity", 4);
        body.put("recovery_event_id", "0");
        body.put("host_ids", List.of("23"));
        return body;
    }

    RawMonitoringEvent raw(byte[] payload, RawMonitoringEvent.Phase phase) {
        return new RawMonitoringEvent(
            new RawMonitoringEvent.Key(pool, "44", phase),
            binding,
            at,
            at.plusSeconds(2),
            payload
        );
    }

    @Test
    void derivesOnlyTechnicalIdentityAndPreservesExactRawTime() throws Exception {
        var raw = raw(json.writeValueAsBytes(envelope()), RawMonitoringEvent.Phase.PROBLEM);
        var command = MappedMonitoringProblem.decode(raw, "23");
        assertEquals("ZBX_" + pool.toString().replace("-", "") + "_18446744073709551615", command.ruleCode());
        assertTrue(command.ruleCode().length() <= 64);
        assertEquals("HIGH", command.severity().name());
        assertEquals("44", command.sourceEventId());
        assertEquals(binding.assetId().toString(), command.assetId());
        assertEquals(Instant.parse("2026-10-24T08:00:00.123Z"), command.occurredAt());
        assertEquals(at, raw.occurredAt());
        assertFalse(command.simulateAutoTicketFailure());
    }

    @Test
    void rejectsRecoveryWithoutPromotingItToVerifiedT2() throws Exception {
        var failure = assertThrows(DomainException.class, () ->
            MappedMonitoringProblem.decode(
                raw(json.writeValueAsBytes(envelope()), RawMonitoringEvent.Phase.RECOVERED),
                "23"
            )
        );
        assertEquals("RECOVERY_REQUIRES_OBSERVATION", failure.code);
    }

    @Test
    void preservesTheFiveNamedSourceLevelsWithoutMappingUnclassifiedToInfo() throws Exception {
        var names = List.of("INFO", "WARNING", "AVERAGE", "HIGH", "DISASTER");
        for (int level = 1; level <= 5; level++) {
            var body = envelope();
            body.put("severity", level);
            var command = MappedMonitoringProblem.decode(raw(json.writeValueAsBytes(body), RawMonitoringEvent.Phase.PROBLEM), "23");
            assertEquals(names.get(level - 1), command.severity().name());
        }
    }

    @Test
    void rejectsSourceClockAheadWithoutChangingRawPrecision() throws Exception {
        var raw = new RawMonitoringEvent(new RawMonitoringEvent.Key(pool, "44", RawMonitoringEvent.Phase.PROBLEM),
                binding, at, at.minusNanos(1), json.writeValueAsBytes(envelope()));
        assertEquals("SOURCE_CLOCK_AHEAD", assertThrows(DomainException.class,
                () -> MappedMonitoringProblem.decode(raw, "23")).code);
        assertEquals(at, raw.occurredAt());
    }

    @Test
    void unclassifiedSourceDoesNotInventSeverity() throws Exception {
        var body = envelope();
        body.put("severity", 0);
        assertEquals(
            "SOURCE_SEVERITY_UNCLASSIFIED",
            assertThrows(DomainException.class, () ->
                MappedMonitoringProblem.decode(
                    raw(json.writeValueAsBytes(body), RawMonitoringEvent.Phase.PROBLEM),
                    "23"
                )
            ).code
        );
    }

    @Test
    void rejectsAnyAlteredSourceFieldOrExtraClaim() throws Exception {
        for (var entry : java.util.Map.ofEntries(
            java.util.Map.entry("provider", "OTHER"),
            java.util.Map.entry("source_event_id", "45"),
            java.util.Map.entry("trigger_id", "18446744073709551616"),
            java.util.Map.entry("clock", at.getEpochSecond() + 1),
            java.util.Map.entry("nanoseconds", 123000000),
            java.util.Map.entry("value", 0),
            java.util.Map.entry("severity", 6),
            java.util.Map.entry("host_ids", List.of("24")),
            java.util.Map.entry("caller_rule", "OVERRIDE")
        ).entrySet()) {
            var body = envelope();
            body.put(entry.getKey(), entry.getValue());
            assertEquals(
                "MAPPED_PROBLEM_ENVELOPE_INVALID",
                assertThrows(
                    DomainException.class,
                    () ->
                        MappedMonitoringProblem.decode(
                            raw(json.writeValueAsBytes(body), RawMonitoringEvent.Phase.PROBLEM),
                            "23"
                        ),
                    entry.getKey()
                ).code
            );
        }
    }

    @Test
    void rejectsDuplicateJsonTrailingJsonAndNonIntegralNumbers() throws Exception {
        String body = json.writeValueAsString(envelope());
        for (String payload : List.of(
            body + "{}",
            body.replace("\"value\":1", "\"value\":1,\"value\":1"),
            body.replace("\"severity\":4", "\"severity\":4.0")
        )) {
            assertThrows(DomainException.class, () ->
                MappedMonitoringProblem.decode(
                    raw(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), RawMonitoringEvent.Phase.PROBLEM),
                    "23"
                )
            );
        }
    }
}
