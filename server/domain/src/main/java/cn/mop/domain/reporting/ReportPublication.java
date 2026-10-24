package cn.mop.domain.reporting;

import cn.mop.domain.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable report workflow. Authorization, policy provenance and all durable writes belong to one repository command. */
public final class ReportPublication {
    private ReportPublication() { }

    public enum Kind { MONTHLY_BOSS, MONTHLY_FULL, HEALTH_CHECK, QUARTERLY, ANNUAL }
    public enum Phase { DRAFT, GENERATING, REVIEW_REQUIRED, APPROVED, PUBLISHED, RETURNED, FAILED }
    public enum Scope { ISOLATED_TEST, CUSTOMER_DELIVERY }
    public enum Regeneration { NEW_REVISION }
    public enum Format { HTML, PDF }
    public enum RenderFailure { FONT_UNAVAILABLE, BROWSER_UNAVAILABLE, RENDER_TIMEOUT, OUTPUT_LIMIT, RENDER_FAILED, PDF_NOT_AVAILABLE }

    /** Capability flags are derived from current server-side membership, never accepted from request JSON. */
    public record Actor(UUID tenantId, UUID userId, boolean prepare, boolean manage, boolean customer) {
        public Actor {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(userId);
        }
    }

    /** Nullable policy values stay unconfigured. Internal review does not establish customer confirmation. */
    public record Policy(String reference, List<UUID> reviewers, List<UUID> publishers, List<UUID> customers,
                         Boolean selfReviewAllowed, Regeneration regeneration, Boolean annualEnabled,
                         Boolean customerTemplateConfirmed, String customerConfirmationReference) {
        public Policy {
            optionalText(reference, 200);
            reviewers = people(reviewers);
            publishers = people(publishers);
            customers = people(customers);
            optionalText(customerConfirmationReference, 500);
            if (Boolean.TRUE.equals(customerTemplateConfirmed) && customerConfirmationReference == null) {
                throw invalid("客户确认必须保留明确证据引用");
            }
        }
    }

    /** The payload bytes and source manifest are immutable; corrections receive a new document identity and revision. */
    public record Document(UUID tenantId, UUID id, UUID familyId, UUID parentId, int revision, Kind kind, Scope scope,
                           String documentSha256, String sourceManifestSha256, String metricVersion,
                           String templateVersion, UUID preparedBy, Instant capturedAt, List<String> incompleteReasons) {
        public Document {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(id);
            Objects.requireNonNull(familyId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(preparedBy);
            instant(capturedAt);
            digest(documentSha256);
            digest(sourceManifestSha256);
            optionalText(metricVersion, 200);
            optionalText(templateVersion, 200);
            if (revision < 1 || revision > 100000 || (revision == 1) != (parentId == null) || id.equals(parentId)) {
                throw invalid("报告修订及父文档关系无效");
            }
            incompleteReasons = List.copyOf(incompleteReasons);
            if (incompleteReasons.size() > 100 || incompleteReasons.stream().distinct().count() != incompleteReasons.size()) {
                throw invalid("报告缺失项无效");
            }
            incompleteReasons.forEach(reason -> requiredText(reason, 200));
        }
    }

    /** Both outputs bind the same frozen payload and exact HTML; renderer/font identity is retained for verification. */
    public record Artifact(Format format, String documentSha256, String htmlSha256, String contentSha256,
                           String templateVersion, String rendererVersion, String fontSha256, long bytes,
                           Integer pages, Instant renderedAt) {
        public Artifact {
            Objects.requireNonNull(format);
            digest(documentSha256);
            digest(htmlSha256);
            digest(contentSha256);
            requiredText(templateVersion, 200);
            requiredText(rendererVersion, 200);
            instant(renderedAt);
            if (bytes < 1 || bytes > 8 * 1024 * 1024) {
                throw invalid("报告输出超过技术上限");
            }
            if (format == Format.HTML) {
                if (!htmlSha256.equals(contentSha256) || pages != null) {
                    throw invalid("HTML输出元数据无效");
                }
                if (fontSha256 != null) {
                    digest(fontSha256);
                }
            } else {
                digest(fontSha256);
                if (pages == null || pages < 1 || pages > 200) {
                    throw invalid("PDF页数无效");
                }
            }
        }
    }

    public record State(Document document, long version, Phase phase, Artifact html, Artifact pdf,
                        UUID reviewer, Instant approvedAt, UUID publisher, Instant publishedAt,
                        String approvedPolicyReference, List<UUID> recipients, String reason, Instant updatedAt) {
        public State {
            Objects.requireNonNull(document);
            Objects.requireNonNull(phase);
            instant(updatedAt);
            if (version < 1 || version > 9_007_199_254_740_990L || updatedAt.isBefore(document.capturedAt())) {
                throw invalid("报告工作流版本或时刻无效");
            }
            bindArtifact(document, html, Format.HTML);
            bindArtifact(document, pdf, Format.PDF);
            if (pdf != null && (html == null || !pdf.htmlSha256().equals(html.contentSha256())
                    || !Objects.equals(pdf.fontSha256(), html.fontSha256()))) {
                throw invalid("PDF与H5不是同一HTML来源");
            }
            if (phase == Phase.DRAFT && (html != null || pdf != null) || phase == Phase.GENERATING && pdf != null
                    || phase == Phase.REVIEW_REQUIRED && (html == null || pdf == null)) {
                throw invalid("报告生成状态与输出不一致");
            }
            boolean approved = phase == Phase.APPROVED || phase == Phase.PUBLISHED;
            if (approved != (reviewer != null && approvedAt != null && approvedPolicyReference != null)
                    || !approved && (reviewer != null || approvedAt != null || approvedPolicyReference != null)) {
                throw invalid("审核状态与审核证据不一致");
            }
            if (approved) {
                instant(approvedAt);
                requiredText(approvedPolicyReference, 200);
                if (html == null || pdf == null || approvedAt.isAfter(updatedAt) || approvedAt.isBefore(document.capturedAt())) {
                    throw invalid("审核前必须完成同源双输出");
                }
            }
            recipients = List.copyOf(recipients);
            if (phase == Phase.PUBLISHED) {
                Objects.requireNonNull(publisher);
                instant(publishedAt);
                people(recipients);
                if (recipients.isEmpty() || publishedAt.isBefore(approvedAt) || publishedAt.isAfter(updatedAt)) {
                    throw invalid("发布时刻或接收人无效");
                }
            } else if (publisher != null || publishedAt != null || !recipients.isEmpty()) {
                throw invalid("未发布报告不能带发布或接收记录");
            }
            if (phase == Phase.RETURNED || phase == Phase.FAILED) {
                requiredText(reason, 3000);
            } else {
                optionalText(reason, 3000);
            }
        }

        public boolean customerAcceptanceConfirmed() {
            return false;
        }
    }

    /** One row per customer/document; a repeated opening never changes firstReadAt. */
    public record ReadReceipt(UUID tenantId, UUID documentId, UUID customerId, String documentSha256, Instant firstReadAt) {
        public ReadReceipt {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(customerId);
            digest(documentSha256);
            instant(firstReadAt);
        }
    }

    /** An intent is not provider acceptance or delivery, and always reuses the published artifacts. */
    public record DeliveryIntent(UUID documentId, UUID recipientId, String documentSha256, String htmlSha256,
                                 String pdfSha256, String cause, Instant requestedAt) { }

    public static State create(Document document) {
        return new State(document, 1, Phase.DRAFT, null, null, null, null, null, null, null, List.of(), null, document.capturedAt());
    }

    public static State queue(State before, Actor actor, long expectedVersion, Instant now, Policy policy) {
        command(before, actor, expectedVersion, now);
        if (!actor.prepare()) {
            throw forbidden();
        }
        phase(before, Phase.DRAFT, Phase.FAILED);
        annual(before.document(), policy);
        if (before.document().metricVersion() == null || before.document().templateVersion() == null) {
            throw pending("METRIC_OR_TEMPLATE_VERSION_MISSING");
        }
        return next(before, Phase.GENERATING, before.html(), null, null, null, null, null, null, List.of(), null, now);
    }

    public static State rendered(State before, Actor actor, long expectedVersion, Artifact html, Artifact pdf, Instant now) {
        command(before, actor, expectedVersion, now);
        if (!actor.prepare()) {
            throw forbidden();
        }
        phase(before, Phase.GENERATING);
        if (html == null) {
            throw invalid("必须提供实际HTML输出");
        }
        bindArtifact(before.document(), html, Format.HTML);
        bindArtifact(before.document(), pdf, Format.PDF);
        if (html.renderedAt().isAfter(now) || pdf != null && pdf.renderedAt().isAfter(now)) {
            throw invalid("报告渲染时刻无效");
        }
        if (before.document().kind() == Kind.MONTHLY_BOSS && pdf != null && pdf.pages() != 1) {
            throw pending("BOSS_REPORT_MUST_BE_ONE_PAGE");
        }
        return next(before, pdf == null ? Phase.FAILED : Phase.REVIEW_REQUIRED, html, pdf,
                null, null, null, null, null, List.of(), pdf == null ? "PDF_NOT_AVAILABLE" : null, now);
    }

    public static State approve(State before, Actor actor, long expectedVersion, Policy policy, Instant now) {
        command(before, actor, expectedVersion, now);
        phase(before, Phase.REVIEW_REQUIRED);
        manager(actor, policy, true);
        annual(before.document(), policy);
        if (actor.userId().equals(before.document().preparedBy())) {
            if (policy.selfReviewAllowed() == null) {
                throw pending("SELF_REVIEW_POLICY_MISSING");
            }
            if (!policy.selfReviewAllowed()) {
                throw forbidden();
            }
        }
        if (!before.document().incompleteReasons().isEmpty()) {
            throw new DomainException(422, "REPORT_INCOMPLETE", "报告必需来源或指标尚未完整", before.document().incompleteReasons());
        }
        customerTemplate(before.document(), policy);
        return next(before, Phase.APPROVED, before.html(), before.pdf(), actor.userId(), now,
                null, null, policy.reference(), List.of(), null, now);
    }

    public static State renderFailed(State before, Actor actor, long expectedVersion, Artifact html,
                                     RenderFailure failure, Instant now) {
        command(before, actor, expectedVersion, now);
        if (!actor.prepare()) {
            throw forbidden();
        }
        phase(before, Phase.GENERATING);
        Objects.requireNonNull(failure);
        bindArtifact(before.document(), html, Format.HTML);
        if (html != null && html.renderedAt().isAfter(now)) {
            throw invalid("报告渲染时刻无效");
        }
        return next(before, Phase.FAILED, html, null, null, null, null, null, null, List.of(), failure.name(), now);
    }

    public static State publish(State before, Actor actor, long expectedVersion, Policy policy, Instant now) {
        command(before, actor, expectedVersion, now);
        phase(before, Phase.APPROVED);
        manager(actor, policy, false);
        annual(before.document(), policy);
        if (!policy.reference().equals(before.approvedPolicyReference())) {
            throw new DomainException(409, "REPORT_POLICY_CHANGED", "审核后流程配置已变化，请退回后按明确新版本重新审核");
        }
        customerTemplate(before.document(), policy);
        if (policy.customers() == null || policy.customers().isEmpty()) {
            throw pending("REPORT_RECIPIENTS_MISSING");
        }
        return next(before, Phase.PUBLISHED, before.html(), before.pdf(), before.reviewer(), before.approvedAt(),
                actor.userId(), now, before.approvedPolicyReference(), policy.customers(), null, now);
    }

    public static State returnForCorrection(State before, Actor actor, long expectedVersion, Policy policy, String reason, Instant now) {
        command(before, actor, expectedVersion, now);
        phase(before, Phase.REVIEW_REQUIRED, Phase.APPROVED);
        manager(actor, policy, true);
        requiredText(reason, 3000);
        return next(before, Phase.RETURNED, before.html(), before.pdf(), null, null, null, null, null, List.of(), reason, now);
    }

    public static State revise(State before, Actor actor, long expectedVersion, Policy policy, Document replacement, String reason, Instant now) {
        command(before, actor, expectedVersion, now);
        if (!actor.prepare()) {
            throw forbidden();
        }
        phase(before, Phase.RETURNED, Phase.PUBLISHED);
        if (policy == null || policy.regeneration() == null) {
            throw pending("REGENERATION_POLICY_MISSING");
        }
        requiredText(reason, 3000);
        if (!before.document().tenantId().equals(replacement.tenantId()) || !before.document().familyId().equals(replacement.familyId())
                || !before.document().id().equals(replacement.parentId()) || before.document().id().equals(replacement.id())
                || replacement.revision() != before.document().revision() + 1 || replacement.kind() != before.document().kind()
                || replacement.scope() != before.document().scope() || !actor.userId().equals(replacement.preparedBy())
                || !replacement.capturedAt().equals(now)) {
            throw invalid("更正必须生成同一报告家族的新修订，不能覆盖旧文档");
        }
        return new State(replacement, 1, Phase.DRAFT, null, null, null, null, null, null, null, List.of(), reason, now);
    }

    public static ReadReceipt firstRead(State published, Actor actor, ReadReceipt existing, Instant now) {
        tenant(published, actor);
        phase(published, Phase.PUBLISHED);
        instant(now);
        if (!actor.customer() || !published.recipients().contains(actor.userId())) {
            throw missing();
        }
        if (now.isBefore(published.publishedAt())) {
            throw invalid("阅读时刻不能早于发布");
        }
        if (existing != null) {
            if (!actor.tenantId().equals(existing.tenantId()) || !actor.userId().equals(existing.customerId())
                    || !published.document().id().equals(existing.documentId())
                    || !published.document().documentSha256().equals(existing.documentSha256())
                    || existing.firstReadAt().isBefore(published.publishedAt()) || existing.firstReadAt().isAfter(now)) {
                throw invalid("原首次阅读记录与当前发布不一致");
            }
            return existing;
        }
        return new ReadReceipt(actor.tenantId(), published.document().id(), actor.userId(), published.document().documentSha256(), now);
    }

    public static List<DeliveryIntent> delivery(State published, Actor actor, long expectedVersion, Policy policy,
                                                 String cause, Instant now) {
        command(published, actor, expectedVersion, now);
        phase(published, Phase.PUBLISHED);
        manager(actor, policy, false);
        if (!List.of("INITIAL_PUBLICATION", "RESEND").contains(cause)) {
            throw invalid("交付意图类型无效");
        }
        return published.recipients().stream().map(customer -> new DeliveryIntent(published.document().id(), customer,
                published.document().documentSha256(), published.html().contentSha256(), published.pdf().contentSha256(), cause, now)).toList();
    }

    public static boolean unreadFollowupDue(State published, UUID customer, ReadReceipt receipt, Instant now) {
        phase(published, Phase.PUBLISHED);
        instant(now);
        if (!published.recipients().contains(customer)) {
            throw missing();
        }
        if (receipt != null && (!published.document().tenantId().equals(receipt.tenantId())
                || !published.document().id().equals(receipt.documentId()) || !customer.equals(receipt.customerId())
                || !published.document().documentSha256().equals(receipt.documentSha256())
                || receipt.firstReadAt().isBefore(published.publishedAt()) || receipt.firstReadAt().isAfter(now))) {
            throw invalid("阅读记录范围无效");
        }
        return receipt == null && now.isAfter(published.publishedAt().plus(Duration.ofDays(7)));
    }

    private static void customerTemplate(Document document, Policy policy) {
        if (document.scope() == Scope.CUSTOMER_DELIVERY && !Boolean.TRUE.equals(policy.customerTemplateConfirmed())) {
            throw pending("CUSTOMER_TEMPLATE_CONFIRMATION_MISSING");
        }
    }

    private static void annual(Document document, Policy policy) {
        if (document.kind() == Kind.ANNUAL && (policy == null || !Boolean.TRUE.equals(policy.annualEnabled()))) {
            throw pending("ANNUAL_PHASE_NOT_CONFIRMED");
        }
    }

    private static void manager(Actor actor, Policy policy, boolean review) {
        if (!actor.manage()) {
            throw forbidden();
        }
        if (policy == null || policy.reference() == null || (review ? policy.reviewers() : policy.publishers()) == null) {
            throw pending("REPORT_APPROVAL_POLICY_MISSING");
        }
        if (!(review ? policy.reviewers() : policy.publishers()).contains(actor.userId())) {
            throw forbidden();
        }
    }

    private static void command(State state, Actor actor, long expected, Instant now) {
        tenant(state, actor);
        if (state.version() != expected) {
            throw new DomainException(409, "VERSION_CONFLICT", "报告工作流已变化，请读取明确版本后重试");
        }
        instant(now);
        if (now.isBefore(state.updatedAt())) {
            throw invalid("报告操作时刻不能倒退");
        }
    }

    private static void tenant(State state, Actor actor) {
        if (state == null || actor == null || !state.document().tenantId().equals(actor.tenantId())) {
            throw missing();
        }
    }

    private static void phase(State state, Phase... allowed) {
        if (!List.of(allowed).contains(state.phase())) {
            throw new DomainException(409, "REPORT_STATE_CONFLICT", "当前报告状态不允许此操作");
        }
    }

    private static State next(State before, Phase phase, Artifact html, Artifact pdf, UUID reviewer, Instant approved,
                              UUID publisher, Instant published, String policy, List<UUID> recipients, String reason, Instant now) {
        return new State(before.document(), Math.addExact(before.version(), 1), phase, html, pdf, reviewer, approved,
                publisher, published, policy, recipients, reason, now);
    }

    private static void bindArtifact(Document document, Artifact artifact, Format expected) {
        if (artifact != null && (artifact.format() != expected || !document.documentSha256().equals(artifact.documentSha256())
                || !Objects.equals(document.templateVersion(), artifact.templateVersion())
                || artifact.renderedAt().isBefore(document.capturedAt()))) {
            throw invalid("输出未绑定同一冻结文档及模板版本");
        }
    }

    private static List<UUID> people(List<UUID> people) {
        if (people == null) {
            return null;
        }
        var result = List.copyOf(people);
        if (result.size() > 1000 || result.stream().distinct().count() != result.size()) {
            throw invalid("明确人员清单无效");
        }
        return result;
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid("报告摘要无效");
        }
    }

    private static void instant(Instant time) {
        if (time == null || time.getNano() % 1_000_000 != 0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || time.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))) {
            throw invalid("报告时刻须为有效UTC毫秒");
        }
    }

    private static void optionalText(String text, int max) {
        if (text != null) {
            requiredText(text, max);
        }
    }

    private static void requiredText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max || text.indexOf('\0') >= 0) {
            throw invalid("报告文字字段无效");
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(400, "VALIDATION_ERROR", message);
    }

    private static DomainException pending(String reason) {
        return new DomainException(422, "CONFIG_PENDING", "报告所需来源或配置尚未完整", List.of(reason));
    }

    private static DomainException forbidden() {
        return new DomainException(403, "ACTION_FORBIDDEN", "当前身份不在本报告的明确操作授权范围内");
    }

    private static DomainException missing() {
        return new DomainException(404, "OBJECT_NOT_FOUND", "报告不存在或不可见");
    }
}
