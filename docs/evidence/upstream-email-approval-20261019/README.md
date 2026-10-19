# Approval V40: fresh service validation

PASS on compiled snapshot 512f2bce21d238e11769a21ae80c1034a4b92115, based on upstream b72c3cff2f030812926f4dee9944262a47205d7d. API jar SHA256 699c5423958d9de6ce0d0c564ba2650ddb9b1b2c1bfe4d587ee56bcaaa3c43c3.

A new disposable PostgreSQL 17.9 / ICU 76.1 database applied V1–V40. All 39 upstream migrations were byte-exact, source/compiled resources matched, and all 40 numbers were unique. Upstream V38 is WeCom and V39 is Email. Approval V40 retains SHA a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756, first applied at 2026-10-19T10:34:23.114985Z. Email V39 and approval V40 each applied once, then repeat/read-only executed zero migrations. Approval preserved 94 prior-table fingerprints, apart from its expected null/pending policy registry addition.

21 real PostgreSQL approval tests passed, zero failures/errors/skips. The actual packaged API passed 43 HTTP checks covering local login/readiness, policy publication, two-person review, self-review denial, CAS/replay, cross-tenant isolation, history and false execution flags. API reports production_ready=false and unconfigured optional storage/publication truthfully. Email and WeCom were disabled; no real provider delivery/login/QR tests or provider credentials were loaded.

Only loopback PG 25432 / API 28080 and synthetic test data were used. Both services stopped; database and temporary secrets were removed. Historical approval V38/V39 disposable databases were already deleted; historical evidence was not rewritten. This is a new database lineage, not renumbering a deployed migration.

## Evidence and reuse

summary.json, snapshot.json, preflight and upstream hash manifests, v40-migration-evidence.json, approval-pg-tests.json, approval-api-http-evidence.json and migration-and-cleanup-results.log document this feature. Raw Surefire XML is omitted because test JVM properties may expose temporary credentials.

Scripts are credential-free and assume official PostgreSQL binaries under /tmp/dot-test-services/pg-icu/bin and prepared JDK/offline Maven cache under /tmp/dot-tools. The evidence copy of verify-approval-v40.py is an approval-only reproduction: the separately authorized, successfully rolled-back report definition probe invocation was removed. The original executed full-lifecycle helper and probe evidence remain separately available to the report owner; no report migration or report tests were run.

Run only against a new disposable loopback database after verifying the snapshot and artifact checksum:

DOT_FRESH_LINEAGE_GO=b72c3cf-v40 DOT_API_APPROVAL_JAR=/tmp/dot-test-services-upstream-b72c3cf/approval-api-final.jar python /tmp/dot-test-services-upstream-b72c3cf/with-postgres.py python /tmp/dot-test-services-upstream-b72c3cf/migrate-upstream-through-v39.py /tmp/dot-upstream40-snapshot python /tmp/dot-test-services-upstream-b72c3cf/verify-approval-v40.py /tmp/dot-upstream40-snapshot
