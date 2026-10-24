package cn.mop.domain.reporting;

import cn.mop.domain.DomainException;
import cn.mop.domain.reporting.ReportPublication.Phase;
import cn.mop.domain.reporting.ReportPublication.ReadReceipt;
import cn.mop.domain.reporting.ReportPublication.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded readership of one immutable published revision; an explicit read is not delivery or acceptance. */
public final class ReportReadership {

    private ReportReadership() {}

    public enum Filter {
        ALL,
        UNREAD,
        FOLLOWUP_DUE,
    }

    /** Frozen recipient identity with the current display name, if still available in this tenant. */
    public record Reader(UUID customerId, String displayName, Instant firstReadAt, boolean followupDue) {}

    /** Counts and page are computed from the same receipt set and database observation time. */
    public record Page(
        UUID tenantId,
        UUID documentId,
        String documentSha256,
        long stateVersion,
        Instant publishedAt,
        Instant observedAt,
        Filter filter,
        int totalRecipients,
        int readCount,
        int unreadCount,
        int followupDueCount,
        int matchingCount,
        List<Reader> items,
        UUID nextAfter
    ) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** Reuse the publication rule: follow-up becomes due strictly after seven elapsed days. */
    public static Page page(
        State state,
        List<ReadReceipt> receipts,
        Map<UUID, String> names,
        Instant observedAt,
        Filter filter,
        UUID after
    ) {
        if (state.phase() != Phase.PUBLISHED) {
            throw new DomainException(409, "REPORT_NOT_PUBLISHED", "报告尚未发布，无法统计接收人阅读情况");
        }
        if (observedAt == null || observedAt.isBefore(state.publishedAt()) || filter == null) {
            throw invalid();
        }
        var byRecipient = new java.util.HashMap<UUID, ReadReceipt>();
        for (var receipt : receipts) {
            if (
                !state.recipients().contains(receipt.customerId()) ||
                byRecipient.putIfAbsent(receipt.customerId(), receipt) != null
            ) {
                throw invalid();
            }
        }
        var seen = new HashSet<UUID>();
        var matching = new ArrayList<Reader>();
        int read = 0;
        int due = 0;
        for (var recipient : state.recipients()) {
            if (!seen.add(recipient)) {
                throw invalid();
            }
            var receipt = byRecipient.get(recipient);
            boolean followup;
            try {
                followup = ReportPublication.unreadFollowupDue(state, recipient, receipt, observedAt);
            } catch (DomainException invalidReceipt) {
                throw invalid();
            }
            if (receipt != null) {
                read++;
            }
            if (followup) {
                due++;
            }
            if (
                filter == Filter.ALL ||
                (filter == Filter.UNREAD && receipt == null) ||
                (filter == Filter.FOLLOWUP_DUE && followup)
            ) {
                matching.add(
                    new Reader(
                        recipient,
                        names.get(recipient),
                        receipt == null ? null : receipt.firstReadAt(),
                        followup
                    )
                );
            }
        }
        matching.sort(Comparator.comparing(row -> row.customerId().toString()));
        var remaining = matching
            .stream()
            .filter(row -> after == null || row.customerId().toString().compareTo(after.toString()) > 0)
            .limit(51)
            .toList();
        var visible = remaining.stream().limit(50).toList();
        return new Page(
            state.document().tenantId(),
            state.document().id(),
            state.document().documentSha256(),
            state.version(),
            state.publishedAt(),
            observedAt,
            filter,
            seen.size(),
            read,
            seen.size() - read,
            due,
            matching.size(),
            visible,
            remaining.size() > 50 ? visible.get(49).customerId() : null
        );
    }

    private static DomainException invalid() {
        return new DomainException(503, "REPORT_PUBLICATION_UNAVAILABLE", "报告阅读记录未通过范围或时间核对");
    }
}
