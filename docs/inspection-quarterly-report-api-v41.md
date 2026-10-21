# D09 quarterly DRAFT API, upstream WeCom / Email lineage / V41

## Lineage and current status

This branch starts from `512f2bce21d238e11769a21ae80c1034a4b92115`, preserving the user's authoritative WeCom V38 and Email V39 implementations plus byte-identical approval V40. Only report feature changes are ported. Applied V1–V40 resources are unchanged. The earlier report V39/V40 branches and evidence remain historical and do not establish V41 execution. Report SQL is unchanged from the unexecuted V40 candidate except its resource filename; the historical SQL comment is not a migration-history declaration.

V41 is now **APPLIED_FROZEN_DOT_CLOUD_ONLY**. It was first explicitly applied at 2026-10-19 10:45:33.711883 UTC in a disposable cloud PostgreSQL database; the first test-setup failure and a subsequent fully passing fresh lifecycle are preserved separately. No original server was accessed. SQL SHA256: `54b9bd1d0995a248191e8f8f7dafe35fafd7d4ec15699a2ca8a49a7a025c7aa2`.

## API 0.61

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

V41 adds only `inspection_quarterly_reports:read` and `inspection_quarterly_reports:write` to padmin business-category limits and existing built-in padmin role grants. Other base roles and all existing custom roles remain unchanged. Future tenant built-in roles use the explicit limit table. The existing protected role update advances the role version and its unchanged permission-epoch trigger advances affected memberships. Old cached permission versions/receipts cannot bypass fresh authorization. This update intentionally changes built-in padmin role and applicable membership rows; migration evidence must distinguish these from unrelated business table fingerprints.

Repositories additionally enforce current padmin membership, strict tenant isolation and source-read for creation/replay. Cross-tenant/missing records return 404; expected source digest mismatch returns 409. Database or frozen document inconsistencies fail closed with no fixture/memory fallback.

The identity structure verifier now accepts explicit schema-version flags in the migration CLI: versions 27/28–40 do not expect future report capabilities, while V41 does. Normal runtime identity checks target the current implementation. The report verifier checks padmin-only limits and required built-in padmin grants in addition to tables/ACL/constraints/guard hashes.

## Migration and readiness

The independent CLI adds `--apply-inspection-quarterly-reports` targeting V41, strictly requiring complete V40 history; repeat/read-only runs do not apply anything. API and Worker startup never apply migrations. Report readiness calls the dedicated schema verifier and fails when the bean's required structure is unavailable. OpenAPI 0.61 preserves both upstream WeCom, Email and approval routes and adds report schemas/paths/readiness.

## Verification boundaries

Dedicated codec, repository integration definitions, controller and MVC/security tests cover exact source binding, null configuration, strict parsing, forged aggregates, frozen detail, metadata-only pagination, current capabilities, idempotency replay, CSRF/session/no-store and sanitized errors. The MVC/security tests use mocked repositories and are not real PG/HTTP provider evidence. Real PG checks remain explicitly gated; the coordinated disposable cloud run passed all 16 report PG tests and all 21 approval PG regressions. UI feature commit `88322efc0fa3e5fd1788174d87321d2d064f938b` has been ported into this combined branch; both source JSON fixtures pass the strict client parser.

Original historical implementation details: [storage design](inspection-quarterly-report-storage.md). The old V39 registration instructions in that document are historical, superseded here.

Current local verification is recorded separately under `docs/evidence/inspection-quarterly-report-v41-20261019/`. Historical evidence is not renamed or treated as a fresh run. PostgreSQL report migration application, 37 real PG tests and 133 real HTTP checks have now passed. Browser visual verification remains NOT_RUN.

The Python and PowerShell explicit migration entrypoints pin V41 in `candidate-inspection-quarterly-report-migration.json`, now labeled APPLIED_FROZEN_DOT_CLOUD_ONLY. The historical filename is retained for launcher compatibility; metadata links the actual first-apply and fresh-rerun evidence.

## Exact schema attestation

The report verifier checks the complete 15-name CHECK-definition SHA256 map under transaction-local UTC / ISO, MDY and restores caller settings on success or failure. Same-count replacement with CHECK(true), renamed/missing/extra checks and generated document-hash expression wrappers fail readiness. Dedicated unit and rollback-only integration tests cover drift and settings restoration; actual PostgreSQL catalog captures and execution evidence must be independently recorded before claims of database verification.

## Combined verification, 2026-10-19

Fresh reactor: 3,285 discovered, 2,329 passed, 956 explicitly gated skips, no failures/errors. Full contract command includes all 143 cases, including upstream Email cases. All 34 isolated migration-policy checks and 2,300 web tests across 168 files pass. Web typecheck/build and static release/security-policy checks pass. All 40 prior migration resource bytes match the new integration base exactly. These are local isolated tests; report PostgreSQL/real HTTP/browser runs remain unclaimed.


## Actual PostgreSQL and HTTP validation, 2026-10-19 10:50 UTC

The final runtime JAR remains SHA256 `a294db91ce0dcf4d39b61f4b6e801c13b0b3c13c39bc4bdfa614fcee56153910`. The only change after reviewed runtime commit af28579 is a real-PG test setup correction to JDBC-supported ISO, DMY, preserving non-default timezone and drift/restoration checks. All frozen SQL and runtime code remain unchanged.

Fresh V1–V41 CLI lifecycle: report apply 1, repeat 0, read-only 0, post-drift-test read-only 0. Exact intentional grants/epoch changes and 92 unchanged unrelated tables are recorded. All 16 report PG and 21 approval PG tests pass without skips. All 43 approval and 90 report HTTP checks pass, including independent hashes and current source-read/report-write revocation before replay. All ephemeral services/credentials are cleaned. Full scope and first-failure history: [verification record](evidence/inspection-quarterly-report-v41-20261019/README.md).

This verifies DRAFT storage and API behavior only. Browser visual acceptance, source evidence access, OSS/PDF publication and customer production activation remain unclaimed.
