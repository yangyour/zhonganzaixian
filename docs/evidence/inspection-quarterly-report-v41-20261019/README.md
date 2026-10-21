# V41 quarterly DRAFT persistence and HTTP verification

## Result

PASS on the dot-owned, disposable, loopback-only PostgreSQL 17.9 / ICU 76.1 environment. The tested runtime snapshot is `0a8c584fe7c716f31f69b01df28bb84493a6fffc`; the API JAR SHA256 remains `a294db91ce0dcf4d39b61f4b6e801c13b0b3c13c39bc4bdfa614fcee56153910`, identical to the reviewed runtime artifact. No user's computer or original server was used.

- Explicit V41 migration: 1 applied, repeat 0, read-only 0. A further read-only check after rollback-only schema-tampering tests executed 0.
- 16 report and 21 approval real PostgreSQL tests passed, with no skips, failures or errors.
- 43 existing approval HTTP checks and 90 report HTTP checks passed against the real API and PostgreSQL. Counts include authenticated setup and negative HTTP assertions.
- Report HTTP covers create/read/metadata list/cursor, independent source-manifest/source-document/report/document hashes, both source-hash mismatches, canonical idempotency replay/conflict, null and explicit configuration, frozen detail, both-direction tenant isolation, four unauthorized role types, CSRF and validation limits.
- Source-read and report-write permissions were separately revoked through the real identity API before an existing receipt replay. Both replays were denied. The disposable reviewer’s effective permissions were restored, and the unused synthetic custom role was disabled. Its initially implicit built-in role became an equivalent explicit built-in assignment because the API has no remove-assignment endpoint. The entire disposable database was then removed.

The HTTP scenario uses explicitly empty selections, not a claim of tenant population or inspection completeness. Separate real PostgreSQL tests verify reports remain unchanged after actual source worksheet/template updates and archive operations, concurrent idempotency, atomic audit/receipt persistence, runtime ACLs, same-count CHECK weakening, and restoration of the caller’s Asia/Shanghai / ISO, DMY settings on success and failure.

## First attempt is preserved

V41 was first successfully applied at **2026-10-19T10:45:33.711883Z**, SHA256 `54b9bd1d0995a248191e8f8f7dafe35fafd7d4ec15699a2ca8a49a7a025c7aa2`. It is frozen from that point, regardless of the later test-setup failure.

The first run passed migration checks, 15 report tests and all 21 approval tests. The remaining report test set DateStyle to SQL, DMY, which PostgreSQL JDBC 42.7.11 rejects by closing the connection before verifier execution; the rollback error masked that setup failure. The installed driver’s `QueryExecutorImpl` requires an ISO-prefixed DateStyle. Commit `0a8c584` changes only the integration test to the supported, still non-default ISO, DMY and records the failed attempt. It retains the non-default timezone, exact drift assertions and caller-session restoration checks. The production verifier, runtime JAR and all SQL are unchanged.

A completely fresh second lifecycle applied the same V41 bytes at **2026-10-19T10:50:25.069237Z** and passed all checks. First-attempt evidence was not overwritten or renamed into a passing run.

## Migration scope

All V1–V40 source, compiled and packaged migration bytes match the published lineage. All 95 existing application tables were fingerprinted immediately before and after V41. The 92 unrelated tables are unchanged. Exact intentional changes are:

1. Two padmin-only report capability limits added.
2. Two built-in padmin roles receive exactly those two permissions, with version +1 and updated timestamp; all other fields/roles are unchanged.
3. Four affected padmin memberships receive permission_version +1; all other membership fields/rows are unchanged.

Only `mop_inspection_quarterly_report` is added. All 15 CHECK definitions, generated document-hash expression, trigger/function/ACL attestations and complete Flyway history are recorded. No frozen SQL was repaired or rewritten. API and Worker startup do not apply migrations.

## Lifecycle and boundaries

Inherited provider secrets were excluded from the process environment. Email and WeCom remained disabled. Both lifecycle attempts stopped PostgreSQL and removed the temporary database/credentials; the successful run also stopped the API and removed temporary authentication material. No external message was sent. Post-run checks confirm all 1,756 snapshotted source/class/JAR file contents are unchanged.

These are DRAFT persistence/API results, not browser visual acceptance, OSS/PDF publication, approved customer business rules, production activation, or completion of all 128 contract items. Report publication, publishability and evidence-access flags remain false; tenant asset population remains null. Existing `unit-results.json` and `check-definition-probe.json` retain their earlier, explicitly limited history.

## Evidence map

- `summary.json`: concise final outcome and boundaries
- `first-apply-freeze.json`, `first-attempt-summary.json`, `first-attempt-migration.json`: original applied/frozen SQL and failed test-setup attempt
- `snapshot-manifest.json`, `prior40-hashes.json`, `prior40-application.json`: clean snapshot identity and exact prior migration lineage
- `fresh-rerun-apply.json`, `migration.json`, `post-tests-schema-check.json`: fresh lifecycle, fingerprints and exact catalog attestations
- `real-pg-tests.json`, `real-pg-test-cases.json`: all 37 real PostgreSQL tests, no gated skips
- `real-http.json`, `report-http-checks.py`: actual HTTP observations and reproducible report assertions; no credentials or cookies
- `harness-hashes.json`: identities of local disposable harness components
