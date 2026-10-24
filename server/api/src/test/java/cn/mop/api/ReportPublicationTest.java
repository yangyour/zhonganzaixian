package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.reporting.ReportPublication;
import cn.mop.domain.reporting.ReportPublication.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Workflow invariants only; persistence, real rendering and published report UI are separate required evidence. */
class ReportPublicationTest {
    static final UUID TENANT = UUID.randomUUID();
    static final UUID AUTHOR = UUID.randomUUID();
    static final UUID MANAGER = UUID.randomUUID();
    static final UUID CUSTOMER = UUID.randomUUID();
    static final Instant TIME = Instant.parse("2026-10-23T08:00:00Z");
    static final Actor PREPARER = new Actor(TENANT, AUTHOR, true, false, false);
    static final Actor REVIEWER = new Actor(TENANT, MANAGER, true, true, false);
    static final Actor READER = new Actor(TENANT, CUSTOMER, false, false, true);

    static Policy policy() {
        return new Policy("SYNTHETIC_POLICY_V1", List.of(MANAGER), List.of(MANAGER), List.of(CUSTOMER),
                false, Regeneration.NEW_REVISION, null, null, null);
    }

    static Document document(Kind kind, Scope scope, List<String> missing) {
        return new Document(TENANT, UUID.randomUUID(), UUID.randomUUID(), null, 1, kind, scope,
                "a".repeat(64), "b".repeat(64), "SYNTHETIC_METRIC_V1", "SYNTHETIC_TEMPLATE_V1", AUTHOR, TIME, missing);
    }

    static Artifact html(Document document) {
        return new Artifact(Format.HTML, document.documentSha256(), "c".repeat(64), "c".repeat(64),
                document.templateVersion(), "SYNTHETIC_HTML_V1", "f".repeat(64), 200, null, TIME.plusSeconds(1));
    }

    static Artifact pdf(Document document, int pages) {
        return new Artifact(Format.PDF, document.documentSha256(), "c".repeat(64), "d".repeat(64),
                document.templateVersion(), "SYNTHETIC_BROWSER_V1", "f".repeat(64), 300, pages, TIME.plusSeconds(1));
    }

    static State review(Document document) {
        var draft = ReportPublication.create(document);
        var queued = ReportPublication.queue(draft, PREPARER, 1, TIME, policy());
        return ReportPublication.rendered(queued, PREPARER, 2, html(document), pdf(document, 1), TIME.plusSeconds(1));
    }

    static State published() {
        var review = review(document(Kind.MONTHLY_BOSS, Scope.ISOLATED_TEST, List.of()));
        var approved = ReportPublication.approve(review, REVIEWER, 3, policy(), TIME.plusSeconds(2));
        return ReportPublication.publish(approved, REVIEWER, 4, policy(), TIME.plusSeconds(3));
    }

    @Test
    void exactDualOutputReviewAndPublicationRemainDistinctFromCustomerAcceptance() {
        var result = published();
        assertEquals(5, result.version());
        assertEquals(Phase.PUBLISHED, result.phase());
        assertEquals(MANAGER, result.reviewer());
        assertEquals(List.of(CUSTOMER), result.recipients());
        assertFalse(result.customerAcceptanceConfirmed());
        assertEquals(result.html().contentSha256(), result.pdf().htmlSha256());
    }

    @Test
    void missingPdfPreservesH5ButCannotBeApprovedOrPublished() {
        var document = document(Kind.MONTHLY_FULL, Scope.ISOLATED_TEST, List.of());
        var queued = ReportPublication.queue(ReportPublication.create(document), PREPARER, 1, TIME, policy());
        var failed = ReportPublication.rendered(queued, PREPARER, 2, html(document), null, TIME.plusSeconds(1));
        assertEquals(Phase.FAILED, failed.phase());
        assertNotNull(failed.html());
        assertNull(failed.pdf());
        assertEquals("PDF_NOT_AVAILABLE", failed.reason());
        assertEquals(409, assertThrows(DomainException.class,
                () -> ReportPublication.approve(failed, REVIEWER, 3, policy(), TIME.plusSeconds(2))).status);
        assertEquals(Phase.GENERATING, ReportPublication.queue(failed, PREPARER, 3, TIME.plusSeconds(2), policy()).phase());
    }

    @Test
    void rendererFailureUsesBoundedReasonAndKeepsFrozenSource() {
        var document = document(Kind.HEALTH_CHECK, Scope.ISOLATED_TEST, List.of());
        var queued = ReportPublication.queue(ReportPublication.create(document), PREPARER, 1, TIME, policy());
        var failed = ReportPublication.renderFailed(queued, PREPARER, 2, html(document), RenderFailure.FONT_UNAVAILABLE, TIME.plusSeconds(1));
        assertEquals(document, failed.document());
        assertEquals("FONT_UNAVAILABLE", failed.reason());
    }

    @Test
    void bossReportRequiresOnePageAndBothFormatsBindTheSameHtml() {
        var document = document(Kind.MONTHLY_BOSS, Scope.ISOLATED_TEST, List.of());
        var queued = ReportPublication.queue(ReportPublication.create(document), PREPARER, 1, TIME, policy());
        assertEquals(422, assertThrows(DomainException.class,
                () -> ReportPublication.rendered(queued, PREPARER, 2, html(document), pdf(document, 2), TIME.plusSeconds(1))).status);
        var wrongPdf = new Artifact(Format.PDF, document.documentSha256(), "e".repeat(64), "d".repeat(64),
                document.templateVersion(), "SYNTHETIC_BROWSER_V1", "f".repeat(64), 300, 1, TIME.plusSeconds(1));
        assertEquals(400, assertThrows(DomainException.class,
                () -> ReportPublication.rendered(queued, PREPARER, 2, html(document), wrongPdf, TIME.plusSeconds(1))).status);
        var unconfiguredFont = new Artifact(Format.HTML, document.documentSha256(), "c".repeat(64), "c".repeat(64),
                document.templateVersion(), "SYNTHETIC_HTML_V1", null, 200, null, TIME.plusSeconds(1));
        assertEquals(400, assertThrows(DomainException.class,
                () -> ReportPublication.rendered(queued, PREPARER, 2, unconfiguredFont, pdf(document, 1), TIME.plusSeconds(1))).status);
    }

    @Test
    void unconfirmedCustomerTemplateAndIncompleteMetricsCannotBecomeFormalReports() {
        var customer = review(document(Kind.MONTHLY_FULL, Scope.CUSTOMER_DELIVERY, List.of()));
        assertEquals(422, assertThrows(DomainException.class,
                () -> ReportPublication.approve(customer, REVIEWER, 3, policy(), TIME.plusSeconds(2))).status);
        var incomplete = review(document(Kind.MONTHLY_FULL, Scope.ISOLATED_TEST, List.of("AVAILABILITY_COVERAGE_UNKNOWN")));
        assertEquals("REPORT_INCOMPLETE", assertThrows(DomainException.class,
                () -> ReportPublication.approve(incomplete, REVIEWER, 3, policy(), TIME.plusSeconds(2))).code);
    }

    @Test
    void selfReviewAndUnlistedManagerAreNotImplicitlyGranted() {
        var review = review(document(Kind.QUARTERLY, Scope.ISOLATED_TEST, List.of()));
        var authorManager = new Actor(TENANT, AUTHOR, true, true, false);
        var policy = new Policy("SYNTHETIC", List.of(AUTHOR), List.of(AUTHOR), List.of(CUSTOMER), false,
                Regeneration.NEW_REVISION, null, null, null);
        assertEquals(403, assertThrows(DomainException.class,
                () -> ReportPublication.approve(review, authorManager, 3, policy, TIME.plusSeconds(2))).status);
        assertEquals(403, assertThrows(DomainException.class,
                () -> ReportPublication.approve(review, authorManager, 3, policy(), TIME.plusSeconds(2))).status);
    }

    @Test
    void staleVersionAndForeignTenantFailBeforeAnyTransition() {
        var review = review(document(Kind.QUARTERLY, Scope.ISOLATED_TEST, List.of()));
        assertEquals(409, assertThrows(DomainException.class,
                () -> ReportPublication.approve(review, REVIEWER, 2, policy(), TIME.plusSeconds(2))).status);
        var foreign = new Actor(UUID.randomUUID(), MANAGER, true, true, false);
        assertEquals(404, assertThrows(DomainException.class,
                () -> ReportPublication.approve(review, foreign, 0, null, null)).status);
    }

    @Test
    void approvalPolicyCannotChangeBetweenReviewAndPublish() {
        var review = review(document(Kind.MONTHLY_FULL, Scope.ISOLATED_TEST, List.of()));
        var approved = ReportPublication.approve(review, REVIEWER, 3, policy(), TIME.plusSeconds(2));
        var changed = new Policy("SYNTHETIC_POLICY_V2", List.of(MANAGER), List.of(MANAGER), List.of(CUSTOMER),
                false, Regeneration.NEW_REVISION, null, null, null);
        assertEquals("REPORT_POLICY_CHANGED", assertThrows(DomainException.class,
                () -> ReportPublication.publish(approved, REVIEWER, 4, changed, TIME.plusSeconds(3))).code);
    }

    @Test
    void returnedOrPublishedCorrectionsCreateNewIdentityWithoutChangingOldContent() {
        var before = published();
        var replacement = new Document(TENANT, UUID.randomUUID(), before.document().familyId(), before.document().id(), 2,
                before.document().kind(), before.document().scope(), "e".repeat(64), "b".repeat(64),
                "SYNTHETIC_METRIC_V1", "SYNTHETIC_TEMPLATE_V1", AUTHOR, TIME.plusSeconds(5), List.of());
        var next = ReportPublication.revise(before, PREPARER, 5, policy(), replacement, "SYNTHETIC correction", TIME.plusSeconds(5));
        assertEquals(1, next.version());
        assertEquals(Phase.DRAFT, next.phase());
        assertEquals(Phase.PUBLISHED, before.phase());
        assertEquals("a".repeat(64), before.document().documentSha256());
        assertNotEquals(before.document().id(), next.document().id());
    }

    @Test
    void firstReadIsPerCustomerImmutableAndScopeChecked() {
        var publication = published();
        var first = ReportPublication.firstRead(publication, READER, null, TIME.plusSeconds(4));
        assertSame(first, ReportPublication.firstRead(publication, READER, first, TIME.plusSeconds(40)));
        assertEquals(404, assertThrows(DomainException.class,
                () -> ReportPublication.firstRead(publication, new Actor(TENANT, UUID.randomUUID(), false, false, true), null, TIME.plusSeconds(4))).status);
    }

    @Test
    void unreadFollowupIsStrictlyAfterSevenDaysAndCannotOverwriteFirstRead() {
        var publication = published();
        var boundary = publication.publishedAt().plus(Duration.ofDays(7));
        assertFalse(ReportPublication.unreadFollowupDue(publication, CUSTOMER, null, boundary));
        assertTrue(ReportPublication.unreadFollowupDue(publication, CUSTOMER, null, boundary.plusMillis(1)));
        var first = ReportPublication.firstRead(publication, READER, null, TIME.plusSeconds(4));
        assertFalse(ReportPublication.unreadFollowupDue(publication, CUSTOMER, first, boundary.plusMillis(1)));
    }

    @Test
    void resendReusesExactPublishedDocumentAndFilesRatherThanGeneratingAnotherReport() {
        var publication = published();
        var intent = ReportPublication.delivery(publication, REVIEWER, 5, policy(), "RESEND", TIME.plusSeconds(20)).get(0);
        assertEquals(publication.document().id(), intent.documentId());
        assertEquals(publication.document().documentSha256(), intent.documentSha256());
        assertEquals(publication.html().contentSha256(), intent.htmlSha256());
        assertEquals(publication.pdf().contentSha256(), intent.pdfSha256());
        assertEquals(Phase.PUBLISHED, publication.phase());
    }

    @Test
    void annualPhaseRemainsGatedWithoutExplicitConfirmation() {
        var annual = ReportPublication.create(document(Kind.ANNUAL, Scope.ISOLATED_TEST, List.of()));
        assertEquals(422, assertThrows(DomainException.class, () -> ReportPublication.queue(annual, PREPARER, 1, TIME, policy())).status);
    }
}
