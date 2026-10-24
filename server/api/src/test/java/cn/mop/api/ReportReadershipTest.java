package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.reporting.ReportPublication;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportReadership;
import cn.mop.domain.reporting.ReportReadership.Filter;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure readership rules, independently exercising bounded paging, first-read integrity and elapsed-time boundaries. */
class ReportReadershipTest {

    private State published(int count) {
        var old = ReportPublicationTest.published();
        var recipients = new ArrayList<UUID>();
        for (int index = 1; index <= count; index++) {
            recipients.add(UUID.fromString("79000000-0000-4000-8000-" + String.format("%012d", index)));
        }
        Collections.reverse(recipients);
        return new State(
            old.document(),
            old.version(),
            old.phase(),
            old.html(),
            old.pdf(),
            old.reviewer(),
            old.approvedAt(),
            old.publisher(),
            old.publishedAt(),
            old.approvedPolicyReference(),
            recipients,
            old.reason(),
            old.updatedAt()
        );
    }

    @Test
    void countsAndStableUuidPagingCoverAllThousandRecipients() {
        var state = published(1000);
        var now = state.publishedAt().plus(Duration.ofDays(8));
        var seen = new HashSet<UUID>();
        UUID after = null;
        do {
            var page = ReportReadership.page(state, List.of(), Map.of(), now, Filter.ALL, after);
            assertEquals(1000, page.totalRecipients());
            assertEquals(1000, page.followupDueCount());
            assertEquals(50, page.items().size());
            for (var row : page.items()) {
                assertTrue(seen.add(row.customerId()));
            }
            after = page.nextAfter();
        } while (after != null);
        assertEquals(1000, seen.size());
    }

    @Test
    void exactSevenDaysIsNotOverdueButOneMillisecondLaterIs() {
        var state = published(1);
        var boundary = state.publishedAt().plus(Duration.ofDays(7));
        assertEquals(
            0,
            ReportReadership.page(
                state,
                List.of(),
                Map.of(),
                boundary,
                Filter.FOLLOWUP_DUE,
                null
            ).matchingCount()
        );
        assertEquals(
            1,
            ReportReadership.page(
                state,
                List.of(),
                Map.of(),
                boundary.plusMillis(1),
                Filter.FOLLOWUP_DUE,
                null
            ).matchingCount()
        );
    }

    @Test
    void filtersUseCompleteFrozenPopulationAndPreserveFirstReadAndInactiveNames() {
        var state = published(3);
        var read = new ReadReceipt(
            state.document().tenantId(),
            state.document().id(),
            state.recipients().get(0),
            state.document().documentSha256(),
            state.publishedAt().plusSeconds(1)
        );
        var now = state.publishedAt().plus(Duration.ofDays(8));
        var page = ReportReadership.page(
            state,
            List.of(read),
            Map.of(read.customerId(), "SYNTHETIC reader"),
            now,
            Filter.ALL,
            null
        );
        assertEquals(1, page.readCount());
        assertEquals(2, page.unreadCount());
        assertEquals(2, page.followupDueCount());
        assertEquals(read.firstReadAt(), page.items().get(2).firstReadAt());
        assertEquals("SYNTHETIC reader", page.items().get(2).displayName());
        assertNull(page.items().get(0).displayName());
        assertEquals(
            2,
            ReportReadership.page(state, List.of(read), Map.of(), now, Filter.UNREAD, null).items().size()
        );
        assertEquals(
            2,
            ReportReadership.page(state, List.of(read), Map.of(), now, Filter.FOLLOWUP_DUE, null)
                .items()
                .size()
        );
    }

    @Test
    void invalidReceiptScopeHashTimeAndDuplicatesCannotBecomeReadStatistics() {
        var state = published(1);
        var doc = state.document();
        var now = state.publishedAt().plusSeconds(10);
        var receipt = new ReadReceipt(
            doc.tenantId(),
            doc.id(),
            state.recipients().get(0),
            doc.documentSha256(),
            now
        );
        var bad = List.of(
            new ReadReceipt(UUID.randomUUID(), doc.id(), receipt.customerId(), doc.documentSha256(), now),
            new ReadReceipt(
                doc.tenantId(),
                UUID.randomUUID(),
                receipt.customerId(),
                doc.documentSha256(),
                now
            ),
            new ReadReceipt(doc.tenantId(), doc.id(), receipt.customerId(), "f".repeat(64), now),
            new ReadReceipt(
                doc.tenantId(),
                doc.id(),
                receipt.customerId(),
                doc.documentSha256(),
                now.plusSeconds(1)
            ),
            new ReadReceipt(
                doc.tenantId(),
                doc.id(),
                receipt.customerId(),
                doc.documentSha256(),
                state.publishedAt().minusSeconds(1)
            ),
            new ReadReceipt(doc.tenantId(), doc.id(), UUID.randomUUID(), doc.documentSha256(), now)
        );
        for (var value : bad) {
            assertThrows(DomainException.class, () ->
                ReportReadership.page(state, List.of(value), Map.of(), now, Filter.ALL, null)
            );
        }
        assertThrows(DomainException.class, () ->
            ReportReadership.page(state, List.of(receipt, receipt), Map.of(), now, Filter.ALL, null)
        );
    }

    @Test
    void draftsDoNotPresentZeroReadershipAsPublishedStatistics() {
        var draft = ReportPublication.create(
            ReportPublicationTest.document(Kind.QUARTERLY, Scope.ISOLATED_TEST, List.of())
        );
        assertEquals(
            "REPORT_NOT_PUBLISHED",
            assertThrows(DomainException.class, () ->
                ReportReadership.page(draft, List.of(), Map.of(), draft.updatedAt(), Filter.ALL, null)
            ).code
        );
    }
}
