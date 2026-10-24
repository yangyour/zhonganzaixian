package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.reporting.*;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportPublicationRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Atomic durable source capture, publication transitions, artifacts, reads and delivery intents; no external delivery. */
public final class PostgresReportPublicationRepository implements ReportPublicationRepository {

    private final PostgresPlatformRepository platform;
    private final ObjectMapper json;
    private final Renderer renderer;
    private final Function<String, Policy> policies;
    private final String templateVersion;

    public PostgresReportPublicationRepository(
        PostgresPlatformRepository platform,
        ObjectMapper mapper,
        Renderer renderer,
        Function<String, Policy> policies,
        String templateVersion
    ) {
        this.platform = Objects.requireNonNull(platform);
        this.json = mapper
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.renderer = Objects.requireNonNull(renderer);
        this.policies = Objects.requireNonNull(policies);
        this.templateVersion = templateVersion;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c, Membership m) throws Exception;
    }

    private <T> T transaction(Access a, boolean write, Work<T> work) {
        validate(a);
        return platform.identityTransaction(c -> {
            try {
                ReportPublicationSchemaVerifier.verify(c);
                PostgresAuthorization.require(
                    c,
                    a.actorId(),
                    a.tenantId(),
                    a.permissionVersion(),
                    write ? "inspection_quarterly_reports:write" : "dashboard:read"
                );
                try (
                    var s = query(
                        c,
                        "SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=? FOR SHARE OF m,u"
                    )
                ) {
                    s.setString(1, a.tenantId());
                    s.setString(2, a.actorId());
                    s.setLong(3, a.permissionVersion());
                    try (var r = s.executeQuery()) {
                        if (!r.next()) {
                            throw expired();
                        }
                    }
                }
                var m = platform
                    .membership(a.actorId(), a.tenantId())
                    .orElseThrow(PostgresReportPublicationRepository::expired);
                if (!m.active() || m.permissionVersion() != a.permissionVersion()) {
                    throw expired();
                }
                return work.run(c, m);
            } catch (DomainException e) {
                throw e;
            } catch (Exception failure) {
                throw unavailable();
            }
        });
    }

    private Actor actor(Access a, Membership m) {
        return new Actor(
            UUID.fromString(a.tenantId()),
            UUID.fromString(a.actorId()),
            m.capabilities().contains("inspection_quarterly_reports:write"),
            m.capabilities().containsAll(List.of("inspection_quarterly_reports:write", "configurations:write")),
            Set.of("viewer", "tadmin").contains(m.role())
        );
    }

    @Override
    public void authorize(Access a, String id, boolean write) {
        transaction(a, write, (c, m) -> {
            if (id != null) {
                get(c, a, m, uuid(id), write);
            }
            return null;
        });
    }

    /** Only trusted, immutable quarterly snapshots supply document bytes and incomplete-source reasons. */
    private record FrozenSource(
        String id,
        String sha256,
        String label,
        String manifest,
        String metric,
        int year,
        int quarter,
        byte[] payload,
        List<String> reasons
    ) {}

    @Override
    public Stored prepare(Access access, String sourceId, String expectedSourceSha256) {
        validateSource(sourceId, expectedSourceSha256);
        return transaction(access, true, (connection, member) -> {
            var source = frozenSource(connection, access, sourceId, expectedSourceSha256);
            Instant capturedAt = now(connection);
            UUID id = UUID.randomUUID();
            var document = document(access, source, id, id, null, 1, Scope.CUSTOMER_DELIVERY, capturedAt);
            var state = ReportPublication.create(document);
            insertDocument(connection, access, source, state);
            audit(access, id.toString(), "prepare");
            return new Stored(source.label(), state, source.payload());
        });
    }

    /** Serialize child creation on the parent; a second command cannot fork the same report revision. */
    @Override
    public Stored revise(
        Access access,
        String id,
        long expectedVersion,
        String sourceId,
        String expectedSourceSha256,
        String reason
    ) {
        uuid(id);
        validateSource(sourceId, expectedSourceSha256);
        if (
            expectedVersion < 1 ||
            expectedVersion >= 9007199254740990L ||
            reason == null ||
            reason.isBlank() ||
            reason.length() > 3000
        ) {
            throw invalid();
        }
        return transaction(access, true, (connection, member) -> {
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw unavailable();
            }
            var parent = get(connection, access, member, id, true).state();
            if (parent.version() != expectedVersion) {
                throw conflict();
            }
            if (parent.document().revision() >= 100000) {
                throw new DomainException(409, "REPORT_REVISION_LIMIT", "报告家族已达到修订技术上限");
            }
            // The locked immutable parent is also the serialization point for original-command recovery.
            try (
                var statement = query(
                    connection,
                    "SELECT 1 FROM public.mop_report_publication_revision " +
                        "WHERE tenant_id=?::uuid AND version=1 AND state#>>'{document,parent_id}'=? LIMIT 1"
                )
            ) {
                statement.setString(1, access.tenantId());
                statement.setString(2, id);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        throw new DomainException(409, "REPORT_REVISION_EXISTS", "该报告已有后续修订，请读取最新修订");
                    }
                }
            }
            var source = frozenSource(connection, access, sourceId, expectedSourceSha256);
            requireSamePeriod(connection, access, id, source);
            Instant capturedAt = now(connection);
            UUID replacementId = UUID.randomUUID();
            var replacement = document(
                access,
                source,
                replacementId,
                parent.document().familyId(),
                parent.document().id(),
                parent.document().revision() + 1,
                parent.document().scope(),
                capturedAt
            );
            var state = ReportPublication.revise(
                parent,
                actor(access, member),
                expectedVersion,
                policies.apply(access.tenantId()),
                replacement,
                reason,
                capturedAt
            );
            insertDocument(connection, access, source, state);
            audit(access, replacementId.toString(), "revise");
            return new Stored(source.label(), state, source.payload());
        });
    }

    private FrozenSource frozenSource(Connection connection, Access access, String id, String expected)
        throws Exception {
        PostgresAuthorization.require(
            connection,
            access.actorId(),
            access.tenantId(),
            access.permissionVersion(),
            "inspection_quarterly_reports:read"
        );
        JsonNode source;
        String label;
        String manifest;
        int year;
        int quarter;
        try (
            var statement = query(
                connection,
                "SELECT * FROM public.mop_inspection_quarterly_report WHERE tenant_id=?::uuid AND id=?::uuid"
            )
        ) {
            statement.setString(1, access.tenantId());
            statement.setString(2, id);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw missing();
                }
                if (!expected.equals(row.getString("document_sha256"))) {
                    throw conflict();
                }
                source = json.readTree(
                    InspectionQuarterlyReportDocumentCodec.decode(
                        row.getBytes("document"),
                        UUID.fromString(access.tenantId()),
                        row.getString("source_collection_id"),
                        row.getString("source_document_sha256"),
                        row.getString("source_manifest_sha256"),
                        row.getString("report_sha256"),
                        expected,
                        row.getInt("year"),
                        row.getInt("quarter")
                    )
                );
                label = row.getString("label");
                manifest = row.getString("source_manifest_sha256");
                year = row.getInt("year");
                quarter = row.getInt("quarter");
            }
        }
        ObjectNode output = json.createObjectNode();
        output.put("format", "MOP_REPORT_PUBLICATION_V1");
        output.put("tenant_id", access.tenantId());
        output.put("source_id", id);
        output.put("source_document_sha256", expected);
        output.put("label", label);
        output.set("configuration", source.get("configuration"));
        output.set("output", source.get("output"));
        byte[] payload = json.writeValueAsBytes(output);
        if (payload.length > 2097152) {
            throw new DomainException(413, "REPORT_OUTPUT_LIMIT", "报告输出超过2MiB技术保护");
        }
        var reasons = new ArrayList<String>();
        for (var reason : source.path("output").path("pending_reasons")) {
            if (!"PUBLICATION_PIPELINE_NOT_CONNECTED".equals(reason.asText())) {
                reasons.add(reason.asText());
            }
        }
        String metric = source.path("configuration").path("metricVersion").isTextual()
            ? source.path("configuration").path("metricVersion").asText()
            : null;
        if (metric == null && !reasons.contains("METRIC_VERSION_MISSING")) {
            reasons.add("METRIC_VERSION_MISSING");
        }
        if (templateVersion == null && !reasons.contains("REPORT_TEMPLATE_VERSION_MISSING")) {
            reasons.add("REPORT_TEMPLATE_VERSION_MISSING");
        }
        return new FrozenSource(id, expected, label, manifest, metric, year, quarter, payload, List.copyOf(reasons));
    }

    /** A correction cannot silently turn an existing report family into a different reporting period. */
    private void requireSamePeriod(Connection connection, Access access, String parent, FrozenSource source)
        throws SQLException {
        try (
            var statement = query(
                connection,
                "SELECT q.year,q.quarter " +
                    "FROM public.mop_report_publication_document d JOIN public.mop_inspection_quarterly_report q " +
                    "ON q.tenant_id=d.tenant_id AND q.id=d.source_report_id WHERE d.tenant_id=?::uuid AND d.id=?::uuid"
            )
        ) {
            statement.setString(1, access.tenantId());
            statement.setString(2, parent);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unavailable();
                }
                if (rows.getInt(1) != source.year() || rows.getInt(2) != source.quarter()) {
                    throw new DomainException(
                        422,
                        "REPORT_REVISION_PERIOD_MISMATCH",
                        "修订来源必须与原报告属于同一季度"
                    );
                }
            }
        }
    }

    private Document document(
        Access access,
        FrozenSource source,
        UUID id,
        UUID familyId,
        UUID parentId,
        int revision,
        Scope scope,
        Instant capturedAt
    ) {
        return new Document(
            UUID.fromString(access.tenantId()),
            id,
            familyId,
            parentId,
            revision,
            Kind.QUARTERLY,
            scope,
            hash(source.payload()),
            source.manifest(),
            source.metric(),
            templateVersion,
            UUID.fromString(access.actorId()),
            capturedAt,
            source.reasons()
        );
    }

    private void insertDocument(Connection connection, Access access, FrozenSource source, State state)
        throws Exception {
        try (
            var statement = query(
                connection,
                "INSERT INTO public.mop_report_publication_document" +
                    "(tenant_id,id,source_report_id,source_document_sha256,label,payload,created_by,permission_version,captured_at) " +
                    "VALUES(?::uuid,?::uuid,?::uuid,?,?,?,?::uuid,?,?)"
            )
        ) {
            statement.setString(1, access.tenantId());
            statement.setString(2, state.document().id().toString());
            statement.setString(3, source.id());
            statement.setString(4, source.sha256());
            statement.setString(5, source.label());
            statement.setBytes(6, source.payload());
            statement.setString(7, access.actorId());
            statement.setLong(8, access.permissionVersion());
            time(statement, 9, state.document().capturedAt());
            statement.executeUpdate();
        }
        append(connection, access, "CREATE", state);
    }

    private static void validateSource(String id, String expected) {
        uuid(id);
        if (expected == null || !expected.matches("[a-f0-9]{64}")) {
            throw invalid();
        }
    }

    @Override
    public Stored get(Access a, String id) {
        return transaction(a, false, (c, m) -> get(c, a, m, uuid(id), false));
    }

    private Stored get(Connection c, Access a, Membership m, String id, boolean lock) throws Exception {
        try (
            var s = query(
                c,
                "SELECT label,payload,payload_sha256 FROM public.mop_report_publication_document WHERE tenant_id=?::uuid AND id=?::uuid" +
                    (lock ? " FOR UPDATE" : " FOR SHARE")
            )
        ) {
            s.setString(1, a.tenantId());
            s.setString(2, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw missing();
                }
                String label = r.getString(1);
                byte[] bytes = r.getBytes(2);
                if (!hash(bytes).equals(r.getString(3))) {
                    throw unavailable();
                }
                var state = state(c, a, id);
                if (
                    !m.capabilities().contains("inspection_quarterly_reports:read") &&
                    !(
                        actor(a, m).customer() &&
                        state.phase() == Phase.PUBLISHED &&
                        state.recipients().contains(UUID.fromString(a.actorId()))
                    )
                ) {
                    throw missing();
                }
                if (
                    !a.tenantId().equals(state.document().tenantId().toString()) ||
                    !id.equals(state.document().id().toString()) ||
                    !hash(bytes).equals(state.document().documentSha256())
                ) {
                    throw unavailable();
                }
                return new Stored(label, state, bytes);
            }
        }
    }

    private State state(Connection c, Access a, String id) throws Exception {
        try (
            var s = query(
                c,
                "SELECT state::text FROM public.mop_report_publication_revision WHERE tenant_id=?::uuid AND document_id=?::uuid ORDER BY version DESC LIMIT 1"
            )
        ) {
            s.setString(1, a.tenantId());
            s.setString(2, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw unavailable();
                }
                return json.readValue(r.getString(1), State.class);
            }
        }
    }

    @Override
    public List<Stored> list(Access a, int limit, Instant before, String beforeId) {
        if (limit < 1 || limit > 21 || (before == null) != (beforeId == null)) {
            throw invalid();
        }
        if (beforeId != null) {
            uuid(beforeId);
        }
        return transaction(a, false, (c, m) -> {
            var ids = new ArrayList<String>();
            boolean manager = m.capabilities().contains("inspection_quarterly_reports:read");
            String visibility = manager
                ? ""
                : " AND EXISTS(SELECT 1 FROM public.mop_report_publication_revision r WHERE r.tenant_id=d.tenant_id AND r.document_id=d.id AND r.phase='PUBLISHED' AND r.state->'recipients' ? ? AND NOT EXISTS(SELECT 1 FROM public.mop_report_publication_revision n WHERE n.tenant_id=r.tenant_id AND n.document_id=r.document_id AND n.version>r.version))";
            if (!manager && !actor(a, m).customer()) {
                return List.of();
            }
            // PostgreSQL JDBC escapes the JSONB existence operator as ??.
            visibility = visibility.replace("recipients' ? ?", "recipients' ?? ?");
            try (
                var s = query(
                    c,
                    "SELECT d.id FROM public.mop_report_publication_document d WHERE d.tenant_id=?::uuid" +
                        visibility +
                        (before == null ? "" : " AND (d.captured_at,d.id)<(?,?::uuid)") +
                        " ORDER BY d.captured_at DESC,d.id DESC LIMIT ?"
                )
            ) {
                int p = 1;
                s.setString(p++, a.tenantId());
                if (!manager) {
                    s.setString(p++, a.actorId());
                }
                if (before != null) {
                    time(s, p++, before);
                    s.setString(p++, beforeId);
                }
                s.setInt(p, limit);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        ids.add(r.getString(1));
                    }
                }
            }
            var out = new ArrayList<Stored>();
            for (var id : ids) {
                out.add(get(c, a, m, id, false));
            }
            return List.copyOf(out);
        });
    }

    @Override
    public State command(Access a, String id, long expected, String action, String reason) {
        uuid(id);
        if (
            expected < 1 ||
            expected >= 9007199254740990L ||
            !Set.of("RENDER", "APPROVE", "RETURN", "PUBLISH", "RESEND").contains(action)
        ) {
            throw invalid();
        }
        return transaction(a, true, (c, m) -> {
            var stored = get(c, a, m, id, true);
            var before = stored.state();
            var actor = actor(a, m);
            var policy = policies.apply(a.tenantId());
            State after;
            Instant now = now(c);
            if (action.equals("RENDER")) {
                var queued = ReportPublication.queue(before, actor, expected, now, policy);
                append(c, a, "QUEUE", queued);
                var rendered = renderer.render(queued.document(), stored.payload(), now(c));
                saveArtifact(c, a, id, queued.version(), rendered.html(), rendered.htmlBytes());
                if (rendered.pdf() != null) {
                    saveArtifact(c, a, id, queued.version(), rendered.pdf(), rendered.pdfBytes());
                }
                after =
                    rendered.failure() == null
                        ? ReportPublication.rendered(
                              queued,
                              actor,
                              queued.version(),
                              rendered.html(),
                              rendered.pdf(),
                              now(c)
                          )
                        : ReportPublication.renderFailed(
                              queued,
                              actor,
                              queued.version(),
                              rendered.html(),
                              rendered.failure(),
                              now(c)
                          );
                append(c, a, rendered.failure() == null ? "RENDER" : "RENDER_FAILED", after);
            } else if (action.equals("RESEND")) {
                intents(c, a, ReportPublication.delivery(before, actor, expected, policy, "RESEND", now));
                after = before;
            } else {
                after = switch (action) {
                    case "APPROVE" -> ReportPublication.approve(before, actor, expected, policy, now);
                    case "RETURN" -> ReportPublication.returnForCorrection(
                        before,
                        actor,
                        expected,
                        policy,
                        reason,
                        now
                    );
                    case "PUBLISH" -> ReportPublication.publish(before, actor, expected, policy, now);
                    default -> throw invalid();
                };
                if (action.equals("PUBLISH")) {
                    for (var recipient : after.recipients()) {
                        requireRecipient(c, a, recipient);
                    }
                }
                append(c, a, action, after);
                if (action.equals("PUBLISH")) {
                    intents(
                        c,
                        a,
                        ReportPublication.delivery(after, actor, after.version(), policy, "INITIAL_PUBLICATION", now)
                    );
                }
            }
            audit(a, id, action.toLowerCase(Locale.ROOT));
            return after;
        });
    }

    private void requireRecipient(Connection c, Access a, UUID id) throws SQLException {
        try (
            var s = query(
                c,
                "SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=? AND m.active AND u.active AND m.role_code IN ('viewer','tadmin') FOR SHARE OF m,u"
            )
        ) {
            s.setString(1, a.tenantId());
            s.setObject(2, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw missing();
                }
            }
        }
    }

    private void saveArtifact(Connection c, Access a, String id, long generation, Artifact artifact, byte[] bytes)
        throws Exception {
        if (
            artifact == null ||
            bytes == null ||
            !hash(bytes).equals(artifact.contentSha256()) ||
            bytes.length != artifact.bytes()
        ) {
            throw unavailable();
        }
        try (
            var s = query(
                c,
                "INSERT INTO public.mop_report_publication_artifact(tenant_id,document_id,format,content,metadata,actor_id,permission_version,render_version) VALUES(?::uuid,?::uuid,?,?,?::jsonb,?::uuid,?,?)"
            )
        ) {
            s.setString(1, a.tenantId());
            s.setString(2, id);
            s.setString(3, artifact.format().name());
            s.setBytes(4, bytes);
            s.setString(5, json.writeValueAsString(artifact));
            s.setString(6, a.actorId());
            s.setLong(7, a.permissionVersion());
            s.setLong(8, generation);
            s.executeUpdate();
        }
    }

    private void append(Connection c, Access a, String action, State state) throws Exception {
        try (
            var s = query(
                c,
                "INSERT INTO public.mop_report_publication_revision(tenant_id,document_id,version,action,phase,state,actor_id,permission_version,recorded_at) VALUES(?::uuid,?::uuid,?,?,?,?::jsonb,?::uuid,?,?)"
            )
        ) {
            s.setString(1, a.tenantId());
            s.setString(2, state.document().id().toString());
            s.setLong(3, state.version());
            s.setString(4, action);
            s.setString(5, state.phase().name());
            s.setString(6, json.writeValueAsString(state));
            s.setString(7, a.actorId());
            s.setLong(8, a.permissionVersion());
            time(s, 9, state.updatedAt());
            s.executeUpdate();
        }
    }

    private void intents(Connection c, Access a, List<DeliveryIntent> intents) throws SQLException {
        for (var value : intents) {
            requireRecipient(c, a, value.recipientId());
            try (
                var s = query(
                    c,
                    "INSERT INTO public.mop_report_publication_delivery_intent(tenant_id,id,document_id,recipient_id,document_sha256,html_sha256,pdf_sha256,cause,actor_id,permission_version,requested_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?::uuid,?,?)"
                )
            ) {
                s.setString(1, a.tenantId());
                s.setString(2, UUID.randomUUID().toString());
                s.setString(3, value.documentId().toString());
                s.setString(4, value.recipientId().toString());
                s.setString(5, value.documentSha256());
                s.setString(6, value.htmlSha256());
                s.setString(7, value.pdfSha256());
                s.setString(8, value.cause());
                s.setString(9, a.actorId());
                s.setLong(10, a.permissionVersion());
                time(s, 11, value.requestedAt());
                s.executeUpdate();
            }
        }
    }

    @Override
    public ReadReceipt read(Access a, String id, long expected) {
        return transaction(a, false, (c, m) -> {
            var stored = get(c, a, m, uuid(id), true);
            if (stored.state().version() != expected) {
                throw conflict();
            }
            ReadReceipt existing = null;
            try (
                var s = query(
                    c,
                    "SELECT document_sha256,first_read_at FROM public.mop_report_publication_read WHERE tenant_id=?::uuid AND document_id=?::uuid AND customer_id=?::uuid"
                )
            ) {
                s.setString(1, a.tenantId());
                s.setString(2, id);
                s.setString(3, a.actorId());
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        existing = new ReadReceipt(
                            UUID.fromString(a.tenantId()),
                            UUID.fromString(id),
                            UUID.fromString(a.actorId()),
                            r.getString(1),
                            r.getObject(2, OffsetDateTime.class).toInstant()
                        );
                    }
                }
            }
            var receipt = ReportPublication.firstRead(stored.state(), actor(a, m), existing, now(c));
            if (existing != null) {
                return receipt;
            }
            try (
                var s = query(
                    c,
                    "INSERT INTO public.mop_report_publication_read(tenant_id,document_id,customer_id,document_sha256,permission_version,first_read_at) VALUES(?::uuid,?::uuid,?::uuid,?,?,?)"
                )
            ) {
                s.setString(1, a.tenantId());
                s.setString(2, id);
                s.setString(3, a.actorId());
                s.setString(4, receipt.documentSha256());
                s.setLong(5, a.permissionVersion());
                time(s, 6, receipt.firstReadAt());
                s.executeUpdate();
            }
            audit(a, id, "first_read");
            return receipt;
        });
    }

    @Override
    public ReportReadership.Page readership(
        Access access,
        String id,
        ReportReadership.Filter filter,
        UUID after
    ) {
        uuid(id);
        if (filter == null) {
            throw invalid();
        }
        // The write capability controls management visibility; this transaction performs SELECTs only.
        return transaction(access, true, (connection, member) -> {
            var stored = get(connection, access, member, id, false);
            var receipts = new ArrayList<ReadReceipt>();
            var names = new HashMap<UUID, String>();
            try (
                var statement = query(
                    connection,
                    "SELECT customer_id,document_sha256,first_read_at FROM public.mop_report_publication_read " +
                        "WHERE tenant_id=?::uuid AND document_id=?::uuid"
                )
            ) {
                statement.setString(1, access.tenantId());
                statement.setString(2, id);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        receipts.add(
                            new ReadReceipt(
                                UUID.fromString(access.tenantId()),
                                UUID.fromString(id),
                                rows.getObject(1, UUID.class),
                                rows.getString(2),
                                rows.getObject(3, OffsetDateTime.class).toInstant()
                            )
                        );
                    }
                }
            }
            try (
                var statement = query(
                    connection,
                    "SELECT m.user_id,u.display_name FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id " +
                        "WHERE m.tenant_id=?::uuid AND m.user_id=ANY(?::uuid[])"
                )
            ) {
                statement.setString(1, access.tenantId());
                var recipients = connection.createArrayOf("uuid", stored.state().recipients().toArray());
                try {
                    statement.setArray(2, recipients);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            names.put(rows.getObject(1, UUID.class), rows.getString(2));
                        }
                    }
                } finally {
                    recipients.free();
                }
            }
            return ReportReadership.page(stored.state(), receipts, names, now(connection), filter, after);
        });
    }

    @Override
    public Content content(Access a, String id, Format format) {
        return transaction(a, false, (c, m) -> {
            var stored = get(c, a, m, uuid(id), false);
            var artifact = format == Format.HTML ? stored.state().html() : stored.state().pdf();
            if (artifact == null) {
                throw missing();
            }
            try (
                var s = query(
                    c,
                    "SELECT content FROM public.mop_report_publication_artifact WHERE tenant_id=?::uuid AND document_id=?::uuid AND format=? AND content_sha256=?"
                )
            ) {
                s.setString(1, a.tenantId());
                s.setString(2, id);
                s.setString(3, format.name());
                s.setString(4, artifact.contentSha256());
                try (var r = s.executeQuery()) {
                    if (!r.next()) {
                        throw unavailable();
                    }
                    var bytes = r.getBytes(1);
                    if (!hash(bytes).equals(artifact.contentSha256())) {
                        throw unavailable();
                    }
                    return new Content(format, artifact.contentSha256(), bytes);
                }
            }
        });
    }

    private void audit(Access a, String id, String action) {
        platform.audit(
            a.actorId(),
            a.tenantId(),
            "report_publication." + action,
            "report_publication",
            id,
            "SUCCEEDED",
            "NO_EXTERNAL_DISPATCH_NOT_CUSTOMER_ACCEPTANCE",
            a.traceId()
        );
    }

    private static void validate(Access a) {
        if (a == null || a.permissionVersion() < 1 || a.permissionVersion() > 9007199254740990L) {
            throw invalid();
        }
        uuid(a.actorId());
        uuid(a.tenantId());
        if (a.traceId() == null || !a.traceId().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw invalid();
        }
    }

    private static String uuid(String s) {
        if (s == null || !s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw invalid();
        }
        return s;
    }

    private static PreparedStatement query(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(10);
        return s;
    }

    private static Instant now(Connection c) throws SQLException {
        try (var s = query(c, "SELECT date_trunc('milliseconds',clock_timestamp())"); var r = s.executeQuery()) {
            r.next();
            return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void time(PreparedStatement s, int i, Instant t) throws SQLException {
        s.setObject(i, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
    }

    private static String hash(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "报告明确版本、范围或输入无效");
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "报告成员授权已变化");
    }

    private static DomainException missing() {
        return new DomainException(404, "OBJECT_NOT_FOUND", "报告不存在或不可见");
    }

    private static DomainException conflict() {
        return new DomainException(409, "VERSION_CONFLICT", "报告或来源版本已变化");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "REPORT_PUBLICATION_UNAVAILABLE", "报告发布结构、证据或受限权限尚未就绪");
    }
}
