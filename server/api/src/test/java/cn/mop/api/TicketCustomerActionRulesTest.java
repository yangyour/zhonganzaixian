package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.TicketCustomerActionRules;
import cn.mop.domain.TicketCustomerActionRules.Relationships;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic facts for pure guards, not an authenticated API or real customer relationship proof. */
class TicketCustomerActionRulesTest {
    private static Membership member(String user, String role) { return new Membership(user, "tenant-a", role, 7, true); }
    private static Relationships facts() { return new Relationships("tenant-a", "reporter", Set.of("decision"), true); }

    @Test void reporterAndActiveDecisionContactMayPassTheCustomerGuard() {
        for (String user : Set.of("reporter", "decision")) for (String role : Set.of("viewer", "tadmin"))
            assertDoesNotThrow(() -> TicketCustomerActionRules.requireCustomer(member(user, role), facts()));
    }

    @Test void internalRolesCannotConfirmEvenWhenNamedAsReporterOrDecisionContact() {
        for (String role : Set.of("msp", "padmin", "unknown")) for (String user : Set.of("reporter", "decision"))
            assertEquals(403, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(member(user, role), facts())).status);
    }

    @Test void unrelatedCustomerCannotConfirm() {
        assertEquals(403, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(member("other", "tadmin"), facts())).status);
    }

    @Test void invisibleAndCrossTenantObjectsShareTheSame404() {
        var actor = member("reporter", "viewer");
        var missing = assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(actor, null));
        var other = assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(actor, new Relationships("tenant-b", "reporter", Set.of("reporter"), true)));
        assertEquals(404, missing.status); assertEquals(missing.status, other.status); assertEquals(missing.code, other.code);
    }

    @Test void revokedOrAbsentMembershipFailsBeforeObjectEvaluation() {
        assertEquals(401, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(null, facts())).status);
        var revoked = new Membership("reporter", "tenant-a", "viewer", 8, false);
        assertEquals(401, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(revoked, facts())).status);
    }

    @Test void incompleteRelationshipLookupFailsClosedWithoutTreatingUnknownAsEmpty() {
        assertEquals(503, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(member("reporter", "viewer"), new Relationships("tenant-a", "reporter", null, false))).status);
        assertThrows(IllegalArgumentException.class, () -> new Relationships("tenant-a", null, null, true));
    }

    @Test void relationshipInputsAreDetachedAndImmutable() {
        var contacts = new HashSet<>(Set.of("decision"));
        var snapshot = new Relationships("tenant-a", "reporter", contacts, true);
        contacts.add("other");
        assertEquals(403, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireCustomer(member("other", "viewer"), snapshot)).status);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.activeDecisionContacts().add("other"));
    }

    @Test void solutionUsesUnicodeCodePointsAndDoesNotCountBoundaryWhitespace() {
        assertEquals(400, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireSolution("修".repeat(19))).status);
        assertDoesNotThrow(() -> TicketCustomerActionRules.requireSolution("修".repeat(20)));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireSolution("😀".repeat(10)));
        assertDoesNotThrow(() -> TicketCustomerActionRules.requireSolution("😀".repeat(20)));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireSolution(" \n" + "修".repeat(19) + "\t "));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireSolution("\u00a0" + "修".repeat(19) + "\u202f"));
    }

    @Test void reasonsMustBeNonBlankAndUnicodeMustBeWellFormed() {
        for (String reason : new String[] { null, "", " \t\r\n", "\u2003", "\u00a0\u202f" }) {
            assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireUnresolvedReason(reason));
            assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireReopenReason(reason));
        }
        assertDoesNotThrow(() -> TicketCustomerActionRules.requireUnresolvedReason("仍无法联网"));
        assertDoesNotThrow(() -> TicketCustomerActionRules.requireReopenReason("问题再次出现"));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireSolution("修".repeat(20) + '\uD800'));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireReopenReason("reason" + '\uDC00'));
    }

    @Test void reopenIncludesExact72HoursButRejectsTheNextNanosecondAndFutureClosure() {
        Instant closed = Instant.parse("2026-09-30T15:50:00Z");
        for (Instant now : new Instant[] { closed, closed.plusSeconds(72 * 3600L).minusNanos(1), closed.plusSeconds(72 * 3600L) })
            assertDoesNotThrow(() -> TicketCustomerActionRules.requireReopenWindow(closed, now));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireReopenWindow(closed, closed.plusSeconds(72 * 3600L).plusNanos(1)));
        assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireReopenWindow(closed, closed.minusNanos(1)));
    }

    @Test void unknownClosureOrClockDoesNotBecomeAnOpenWindow() {
        assertEquals(422, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireReopenWindow(null, Instant.EPOCH)).status);
        assertEquals(422, assertThrows(DomainException.class, () -> TicketCustomerActionRules.requireReopenWindow(Instant.EPOCH, null)).status);
    }
}
