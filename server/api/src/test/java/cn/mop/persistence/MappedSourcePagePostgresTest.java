package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MappedSourcePage;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real designated retained V55 PG transactions; source frames are synthetic and no device is contacted. */
@EnabledIfEnvironmentVariable(named = "MOP_MAPPED_PROBLEM_PG_TEST", matches = "retained-v55")
class MappedSourcePagePostgresTest extends MappedProblemIntakePostgresTest {

    String mode = "normal";

    @Override
    MappedSourcePage.Fetched sourcePage(MappedSourcePage.Window window) {
        var frames = new ArrayList<FetchedEvent>();
        long first = Long.parseLong(window.firstEventId());
        for (int index = 0; index < window.pageSize(); index++) {
            String id = Long.toString(first + index);
            if (Long.parseLong(id) > Long.parseLong(window.lastEventId())) {
                break;
            }
            phase =
                mode.equals("pending") && index == 1
                    ? RawMonitoringEvent.Phase.RECOVERED
                    : RawMonitoringEvent.Phase.PROBLEM;
            severity = mode.equals("pending") && index == 2 ? 0 : 4;
            var event = sourceEvent(id);
            if (mode.equals("invalid") && index == 1) {
                event = new FetchedEvent(id, event.phase(), event.occurredAt(), "23", new byte[] { '{' });
            }
            frames.add(event);
        }
        String next =
            first + frames.size() <= Long.parseLong(window.lastEventId()) ? Long.toString(first + frames.size()) : null;
        return new MappedSourcePage.Fetched(window, frames, next);
    }

    MappedSourcePage.Window window(String first, String last, int size) {
        return new MappedSourcePage.Window(first, last, occurred.getEpochSecond(), occurred.getEpochSecond(), size);
    }

    MappedSourcePage.Receipt page(MappedSourcePage.Window window) {
        var access = access(TENANT);
        return command(access, () ->
            repository.intakePage(access, mappingId, 2, HASH, window)
        );
    }

    @Test
    void commitsWholePageAndContinuationThenOverlapDoesNotDuplicateMembers() throws Exception {
        var first = page(window("5001", "5003", 2));
        assertEquals("5003", first.nextEventId());
        assertEquals(2, first.items().size());
        assertTrue(first.items().stream().allMatch(MappedSourcePage.Item::businessProcessed));
        var last = page(window(first.nextEventId(), "5003", 2));
        assertNull(last.nextEventId());
        assertEquals(1, last.items().size());
        repository = assembly();
        var overlap = page(window("5001", "5003", 2));
        for (int i = 0; i < 2; i++) {
            assertEquals("RAW_ALREADY_PRESENT", overlap.items().get(i).capture().outcome());
            assertEquals(first.items().get(i).capture().recordId(), overlap.items().get(i).capture().recordId());
            assertEquals(
                1,
                count(
                    "mop_monitoring_event_member",
                    "raw_event_id",
                    first.items().get(i).capture().recordId().toString()
                )
            );
        }
    }

    @Test
    void originalCommandReceiptSurvivesNewAssemblyWithoutAnotherSourceRead() throws Exception {
        var access = access(TENANT);
        var window = window("5101", "5103", 2);
        String originalKey = UUID.randomUUID().toString();
        java.util.function.Supplier<Object> send = () ->
            platform.idempotentAuthorized(
                ACTOR,
                TENANT,
                "/isolated/page/" + mappingId,
                originalKey,
                "c".repeat(64),
                access.permissionVersion(),
                () -> repository.authorize(access, mappingId, true),
                () -> JSON.copy()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .valueToTree(repository.intakePage(access, mappingId, 2, HASH, window))
            );
        var first = JSON.readTree(JSON.writeValueAsBytes(send.get()));
        int readCount = sourceReads.get();
        repository = assembly();
        assertEquals(first, JSON.readTree(JSON.writeValueAsBytes(send.get())));
        assertEquals(readCount, sourceReads.get());
    }

    @Test
    void malformedSecondEnvelopeRollsBackFirstRawMemberTicketAndAudit() throws Exception {
        mode = "invalid";
        assertEquals(503, assertThrows(DomainException.class, () -> page(window("5201", "5202", 2))).status);
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "5201"));
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "5202"));
        mode = "normal";
        assertTrue(
            page(window("5201", "5202", 2))
                .items()
                .stream()
                .allMatch(MappedSourcePage.Item::businessProcessed)
        );
    }

    @Test
    void recoveryAndUnclassifiedFactsRemainDurablePendingWithoutBusinessOrT2() throws Exception {
        mode = "pending";
        var receipt = page(window("5301", "5303", 3));
        assertTrue(receipt.items().get(0).businessProcessed());
        assertEquals("RECOVERY_OBSERVATION_REQUIRED", receipt.items().get(1).pendingReason());
        assertEquals("SOURCE_SEVERITY_UNCLASSIFIED", receipt.items().get(2).pendingReason());
        for (var item : receipt.items().subList(1, 3)) {
            assertNull(item.eventId());
            assertEquals(1, count("mop_raw_monitoring_event", "source_event_id", item.capture().sourceEventId()));
            assertEquals(0, count("mop_monitoring_event_member", "raw_event_id", item.capture().recordId().toString()));
        }
    }

    @Test
    void changedRawIsHeldWhileOtherItemsReplayWithoutDuplicateBusiness() throws Exception {
        var first = page(window("5401", "5402", 2));
        recovery = "999";
        var conflict = page(window("5401", "5402", 2));
        for (var item : conflict.items()) {
            assertEquals("SOURCE_KEY_CONFLICT", item.pendingReason());
            assertFalse(item.businessProcessed());
            assertEquals(1, count("mop_monitoring_quarantine", "source_event_id", item.capture().sourceEventId()));
        }
        assertEquals(
            1,
            count("mop_monitoring_event_member", "raw_event_id", first.items().get(0).capture().recordId().toString())
        );
    }

    @Test
    void pageRejectsStaleVersionAndForeignTenantBeforeReadingSource() {
        var own = access(TENANT);
        var window = window("5501", "5502", 2);
        assertEquals(
            409,
            assertThrows(DomainException.class, () ->
                command(own, () -> repository.intakePage(own, mappingId, 1, HASH, window))
            ).status
        );
        var foreign = access(FOREIGN);
        assertEquals(
            404,
            assertThrows(DomainException.class, () ->
                command(foreign, () -> repository.intakePage(foreign, mappingId, 2, HASH, window))
            ).status
        );
        assertEquals(0, sourceReads.get());
    }

    @Test
    void outerCommitFailureLeavesNoPartialPageAndRetryCanContinue() throws Exception {
        var own = access(TENANT);
        var window = window("5601", "5603", 2);
        assertThrows(IllegalStateException.class, () ->
            command(own, () -> {
                repository.intakePage(own, mappingId, 2, HASH, window);
                throw new IllegalStateException("SYNTHETIC precommit interruption");
            })
        );
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "5601"));
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "5602"));
        assertEquals("5603", page(window).nextEventId());
    }
}
