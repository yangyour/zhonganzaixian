# Fresh upstream WeCom V38 + approval V39 validation

Result: PASS. Snapshot 7d687ba1e4ce46dc26c9ff29b28d3aaf146e5f3e.

A new disposable PostgreSQL 17.9 / ICU 76.1 database applied V1–V39. All 38 upstream e739027 SQL files matched exactly, source/compiled resources matched, and all 39 migration numbers were unique. Authoritative upstream V38 is WeCom; approval V39 retains SHA a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756. V40 was not included. Both V38 and V39 applied once, then repeat/read-only executed zero migrations. Approval left 92 prior-table fingerprints unchanged, apart from the expected null/pending approver-policy registry key.

21 real PostgreSQL approval cases passed with zero failures/errors/skips. Actual packaged API passed 43 HTTP checks, including local login, readiness, policy publication, two-person review, self-review denial, CAS/replay, cross-tenant isolation, history, and false execution flags. WeCom schema verification passed; live WeCom delivery/login/QR was not tested. All WeCom flags were false, no provider credentials were loaded. API readiness reports production_ready=false and honestly reports unconfigured optional storage/publication components.

Only loopback endpoints were used (PG 25432, API 28080), with synthetic test identities/data. Both processes stopped, and the fresh database plus temporary secrets were deleted. This is not a migration rename on a shared/deployed database. Earlier approval V38 applied only to prior deleted disposable databases; its separate historical evidence remains untouched.

## Evidence
summary.json, snapshot.json, new-lineage-preflight.json, frozen migration manifests, migration-and-cleanup-results.log, v39-migration-evidence.json, approval-pg-tests.json, approval-api-http-evidence.json. No raw Surefire XML is included because JVM properties can contain test environment secrets. CHECK definitions/hashes are normalized to UTC and ISO,MDY.

## Reproduction
The included scripts are exact credential-free helpers used for this run. They assume the prepared official PostgreSQL binaries at /tmp/dot-test-services/pg-icu/bin, JDK and offline Maven cache under /tmp/dot-tools, and helper files under /tmp/dot-test-services-upstream-e739027. Place an independently verified compiled source snapshot and matching API jar in the documented paths; inspect snapshot.json for the artifact checksum. Never point these scripts at an existing database. Run services, migrations, tests and API in one process tree due to isolated loopback namespaces.

DOT_FRESH_LINEAGE_GO=e739027-v39 DOT_API_APPROVAL_JAR=/tmp/dot-test-services-upstream-e739027/approval-api-final.jar python /tmp/dot-test-services-upstream-e739027/with-postgres.py python /tmp/dot-test-services-upstream-e739027/migrate-upstream-through-v38.py /tmp/dot-upstream39-snapshot python /tmp/dot-test-services-upstream-e739027/verify-approval-v39.py /tmp/dot-upstream39-snapshot
