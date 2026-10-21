# D09 immutable quarterly DRAFT report storage

## Scope and current evidence

This slice persists server-computed quarterly drafts from one explicitly selected immutable source collection. It does not publish, approve, deliver notifications, grant evidence access, or claim tenant-wide completeness or customer acceptance. Q24 business defaults are not supplied. The API, UI, shared permission/config catalogs and migration CLI registration are integration work, not included in this branch.

V39 is a **candidate, not applied**. V1–V38 have not been edited. No database or services were started by this slice. Offline compilation and 30 codec unit tests pass; 15 real PostgreSQL tests compile but were skipped because the explicit integration environment was not enabled. They are not integration evidence. Full persistence reactor: 1,066 discovered, 171 passed, 895 environment-gated skips, zero failures. See `evidence/inspection-quarterly-report-20261019/unit-results.json`.

## Data flow and authority

1. An already authenticated command supplies actor, tenant, current permission version and trace ID. `PostgresPlatformRepository.idempotentAuthorized` must surround `authorizeWrite` and `create`; replay must rerun `authorizeWrite`.
2. `Creation` accepts only label, exact source collection UUID, expected source document and manifest SHA-256 digests, year/quarter and explicit configuration. It accepts no counts, findings, result rows, source bodies or client-generated output.
3. The report write capability and current platform-admin membership are checked. The source collection is read through the same protected transaction after its own current source-read authorization. No worksheet or template head is reread.
4. Source hashes must match or the entire command returns 409. The source codec independently verifies frozen source bodies, canonical encoding and manifest digest.
5. The domain aggregator computes a DRAFT. Configuration is frozen under `REQUEST_DRAFT_NOT_APPROVED`; missing values remain null. Its `revision` is a caller-provided draft label, never an authoritative runtime release ID. Completeness is `COMPLETE_EXPLICIT_SELECTION`, referring solely to the selected collection. `tenant_asset_population` remains null.
6. Report row, exact UTF-8 JSON document, audit and idempotent receipt commit in one transaction. Caller failure rolls all back. Audit action is `inspection_quarterly_report.create`; object type is `inspection_quarterly_report`.
7. Reads use scoped repeatable-read read-only transactions and current report-read authorization. Frozen input and output are validated together, with no live-source fallback. The returned JSON string is the persisted frozen document, not newly fetched source data. Metadata lists do not load or claim verification of document bodies.

## Immutable document

`MOP_INSPECTION_QUARTERLY_DOCUMENT_V1` contains source collection ID, both source digests, full original canonical source-document JSON, exact explicit configuration/provenance, period, source-selection completeness, domain report fingerprint, and full output (summary, selected/candidate references, complete selected items, findings, pending reasons and draft-only truth flags).

The domain fingerprint covers the canonical source manifest, period and all configuration fields. The database-generated document digest additionally binds collection identity, full input and output. Label, recording metadata and snapshot identity live in immutable relational columns. Two independently created reports with identical calculation inputs may share the domain fingerprint; their snapshot IDs differ. There is no deduplication that silently changes requested source identity.

Exact UTF-8 generation uses string encoding consistently with the existing source codec, including supplementary Unicode characters. Reads reject duplicate keys, unknown fields, noncanonical encodings, different expected scope/period, changed source content, fabricated summaries and modified flags even if an outer hash is recomputed. Current decoding revalidates V1 output using the V1 aggregator; future behavior changes must introduce a new codec/algorithm version rather than reinterpret V1 documents.

## Technical limits and guards

- One immutable source collection, up to existing 1,000 captured sources and source document/canonical limits.
- Report document: 40 MiB maximum; no truncation or partial success.
- Label: existing 200-character validated text rules.
- Period: year 1–9998, quarter 1–4.
- Configuration: existing bounded text and Java `ZoneId` validation, explicit duplicate/archive enums; null is pending.
- List: 1–21 metadata records with stable `(recorded_at,id)` cursor; cursor time must be bounded millisecond precision.
- SQL statements: 10-second timeout; runtime only SELECT/INSERT, no UPDATE/DELETE/TRUNCATE.
- Scoped FK binds collection and creator membership. Trigger rejects update/delete, source hash/body mismatch, wrong period/input fingerprint, provenance and false publication claims. Generated digest binds exact bytes.
- PostgreSQL independently checks the source binding and canonical report-input fingerprint. It does not reimplement every aggregation rule in PL/pgSQL; the Java codec verifies complete computed output before command completion and on every full read. A direct SQL fabricated output therefore cannot become a successfully returned report.

## Parent integration requirements

1. Register candidate V39 in explicit migration CLI/resource verifier/frozen-manifest flow only after review. Resource `V39__immutable_inspection_quarterly_reports.sql`, SHA-256 `8ccc3c997ff66ca2c549bd68a2ef546ed43b8cfef2df1f782a97898ddf790e72`. Call `InspectionQuarterlyReportSchemaVerifier.resource()` for resources and `verify(Connection)` for structure. API/Worker startup must not apply it.
2. Decide and explicitly register/grant `inspection_quarterly_reports:read` and `inspection_quarterly_reports:write` in shared catalogs and database permission mapping. This branch adds no implicit role grants. Repository additionally restricts to current `padmin`, matching the source-collection slice. Creating also requires `inspection_source_collections:read`. Tests require these capabilities on the synthetic authorized platform-admin identity.
3. Wire `PostgresInspectionQuarterlyReportRepository(DataSource, PostgresPlatformRepository)` and its readiness problems. Do not default to fixture/memory if unavailable.
4. API must wrap create in current-authorization idempotency, enforce CSRF/tenant scope/expected version zero, reject unknown/client aggregate fields, and hash the complete canonical request. Full reads should parse the returned frozen document only for representation, never replace it by recomputing live heads.
5. No new runtime configuration keys are necessary for this explicitly labeled request-snapshot slice. If integration chooses runtime configuration instead, it must resolve and freeze an explicit release and provenance; do not relabel request draft values as an approved configuration snapshot.

## Planned real PG checks

`PostgresInspectionQuarterlyReportIntegrationTest` is gated by `MOP_RUN_REMOTE_TESTS=true`, using the existing shared test connection harness. It never applies migrations or starts components. Checks cover atomic audit/receipt, fresh repository reads, immutable history after source changes, conflicting digests, cross-tenant 404, current authorization before replay, caller rollback, protected connection boundaries, pagination, role denial, exact verifier/ACL, denied mutation, negative direct inserts, metadata-only reads, corrupted bytes with no fallback, and concurrent identical idempotency keys.

Run only after parent review, candidate migration application and explicit permission registration in the disposable dot-local PG environment. Original servers are out of scope. Real PG/HTTP/UI/production outcomes remain NOT_RUN until separately recorded.
