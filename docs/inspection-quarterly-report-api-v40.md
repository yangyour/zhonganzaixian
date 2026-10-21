# D09 quarterly DRAFT API, upstream WeCom lineage / V40

## Lineage and current status

This branch starts from integrated upstream commit `504d57b3da29242a22e50c9fb6286ef4682a9d52`, including the user's WeCom implementation and upstream V38. Integration correction `7d687ba1e4ce46dc26c9ff29b28d3aaf146e5f3e` is included. Upstream V1–V38 and approval V39 SQL are unchanged. Only the previously **unapplied** report candidate is ported to V40. The old report V39 notes/evidence remain historical and do not establish this lineage's migration status.

V40 is currently **CANDIDATE_NOT_APPLIED**. No database/service lifecycle or migration execution was performed by the implementation worker. SQL SHA256: `54b9bd1d0995a248191e8f8f7dafe35fafd7d4ec15699a2ca8a49a7a025c7aa2`.

## API 0.60

`/api/v1/inspection-quarterly-reports`
- POST: authenticated tenant session, CSRF and Idempotency-Key required. `expected_version` must be 0. Exact source collection UUID and expected source document/manifest hashes are mandatory. Body is bounded to 16 KiB, duplicate keys/unknown fields rejected, full canonical request hashed for idempotency. Current report-write and source-read capabilities are rechecked before original receipts replay.
- GET: metadata only; `page_size` 10/15/20 (default 15), paired `before_recorded_at` and `before_id`, lookahead and stable descending cursor. It neither loads nor certifies full frozen document bodies.
- GET `/{id}`: full immutable document, server-verified input/output/source digests and scope. Returns `projection=FROZEN_INPUT_OUTPUT`, `document_integrity=VERIFIED_SERVER_SIDE`, `frozen_document` as a parsed JSON object. No live source recapture or publication.

POST fields: expected_version, label, source_collection_id, expected_source_document_sha256, expected_source_manifest_sha256, year, quarter, configuration. All six configuration keys must exist, with null explicitly permitted:
- revision: unapproved request-draft label
- time_zone: explicit Java ZoneId
- duplicate_rule: REQUIRE_SINGLE_SOURCE or LATEST_REPORTED_PERFORMED_AT
- archived_rule: INCLUDE or EXCLUDE
- metric_version
- report_template_version

No customer values or approved Q24 defaults are invented. Provenance remains `REQUEST_DRAFT_NOT_APPROVED`. Stored document V1 retains the established camelCase Java record fields within configuration and computed records; outer document/row fields are snake_case. Contract schemas describe these exactly. The document digest is over the stored canonical UTF-8 JSON, not over an HTTP response serializer's escape choices.

`COMPLETE_EXPLICIT_SELECTION` means only the selected frozen source collection, never tenant-wide completeness. Tenant asset population remains null; unknown source time is not healthy or normal. Report publishable/published and evidence access are always false. There are no approval, publication, notification or device-execution endpoints.

## Deliberate permission scope and epochs

V40 adds only `inspection_quarterly_reports:read` and `inspection_quarterly_reports:write` to padmin business-category limits and existing built-in padmin role grants. Other base roles and all existing custom roles remain unchanged. Future tenant built-in roles use the explicit limit table. The existing protected role update advances the role version and its unchanged permission-epoch trigger advances affected memberships. Old cached permission versions/receipts cannot bypass fresh authorization. This update intentionally changes built-in padmin role and applicable membership rows; migration evidence must distinguish these from unrelated business table fingerprints.

Repositories additionally enforce current padmin membership, strict tenant isolation and source-read for creation/replay. Cross-tenant/missing records return 404; expected source digest mismatch returns 409. Database or frozen document inconsistencies fail closed with no fixture/memory fallback.

The identity structure verifier now accepts explicit schema-version flags in the migration CLI: versions 27/28–39 do not expect future report capabilities, while V40 does. Normal runtime identity checks target the current implementation. The report verifier checks padmin-only limits and required built-in padmin grants in addition to tables/ACL/constraints/guard hashes.

## Migration and readiness

The independent CLI adds `--apply-inspection-quarterly-reports` targeting V40, strictly requiring complete V39 history; repeat/read-only runs do not apply anything. API and Worker startup never apply migrations. Report readiness calls the dedicated schema verifier and fails when the bean's required structure is unavailable. OpenAPI 0.60 preserves both upstream WeCom and approval routes and adds report schemas/paths/readiness.

## Verification boundaries

Dedicated codec, repository integration definitions, controller and MVC/security tests cover exact source binding, null configuration, strict parsing, forged aggregates, frozen detail, metadata-only pagination, current capabilities, idempotency replay, CSRF/session/no-store and sanitized errors. The MVC/security tests use mocked repositories and are not real PG/HTTP provider evidence. Real PG checks remain explicitly gated; root coordinates review and execution in the disposable cloud test environment. UI work is a separate branch.

Original historical implementation details: [storage design](inspection-quarterly-report-storage.md). The old V39 registration instructions in that document are historical, superseded here.

Current local evidence: full Java reactor 3,246 discovered / 2,297 passed / 949 explicitly gated skips / zero failures; 141 contract checks and 34 isolated Python migration-policy checks pass. Six mocked MVC/filter checks and 16 controller tests are included. Java-generated synthetic pending/selected detail fixtures live under `fixtures/inspection-quarterly-report/`; contract tests validate full shape and cross-language document digest. PowerShell-specific execution is NOT_RUN (pwsh unavailable).

The Python and PowerShell explicit migration entrypoints pin V40 in `candidate-inspection-quarterly-report-migration.json`, intentionally labeled UNAPPLIED_CANDIDATE_REQUIRES_REVIEW. This pin is resource-integrity protection, not a claim of migration execution. After reviewed real application, root must record actual evidence and update the candidate/frozen status coherently.
