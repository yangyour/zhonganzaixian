# Event global-search isolated verification, 2026-10-19

Result: PASS. These are actual PostgreSQL and HTTP checks on dot’s disposable loopback environment, not production or browser acceptance.

- Backend snapshot: `c26d4f584565400babba131e201e94882f842473`. Published baseline: `b21bb8c699f62d33cd7ec6ad5b00a3bd278995dc`.
- API JAR SHA256: `db33d6d1d368a1d652847794e6f7fcc13b55ea48e8b8b9bf15c5bafb9fdcb803`. All 2126 captured source/class/JAR files remained unchanged.
- PostgreSQL 17.9 / ICU 76.1 started against a NEW empty dedicated database. V1–V4 bootstrap followed by every explicit V5–V41 migration CLI. No V42 was created. All V1–V41 source/compiled/nested-JAR bytes equal the published baseline. Repeated V41 and read-only CLI checks left all 97 table fingerprints unchanged.
- 32 real PG tests passed with zero failures, errors or skips: 9 existing GlobalSearch tests and 23 new event tests. Cases include literal Unicode, a four-hit limit with fifth-row overflow detection, PostgreSQL UUID tie ordering, current custom capabilities, tenant isolation, private-field exclusion, repeatable-read data consistency and concurrent grant revocation, no writes, and detail beyond latest25. See `real-pg-test-cases.json`.
- First attempt passed all 32 PG tests but did not start HTTP because the harness supplied five instead of the required six bootstrap identities. Only the harness was corrected; `attempt-1/` preserves that result. The final rerun repeated the entire lifecycle in another new disposable database.
- 72 real HTTP checks passed with five exercised ephemeral project-local accounts (six existing identities explicitly bootstrapped), real CSRF/session handling, and 45 synthetic events across two tenants. Direct detail was checked for an event older than more than 25 newer rows. Every event search item had exactly the approved 11 fields.
- 31 successful HTTP search/detail reads were bracketed by full row fingerprints of all 97 tables; every fingerprint stayed identical. Denied-request tests are separate because the existing security layer intentionally records denied-request audits.
- API and PostgreSQL stopped; disposable database, extracted temporary classpath and random credentials removed. Email/WeCom/OIDC disabled and inherited provider settings stripped. No original server, external provider, device polling or user computer was used.

## Reproduction

The retained harness requires a clean compiled source worktree, captures an independent snapshot and verifies the exact artifact bytes. The lifecycle will refuse to start unless `DOT_EVENT_SEARCH_SNAPSHOT_GO` equals the approved API JAR hash. Run `capture_snapshot.py` with source and NEW snapshot destination, then explicitly approve and invoke `with_postgres.py python run_validation.py`. No credentials belong in commands or evidence.

JUnit directly executes the approved compiled classes; it does not run Maven or recompile the snapshot. Helpers are retained for reproduction, and the complete SHA256 manifest is `snapshot.json`.

## Boundaries

Event search is available only to current internal authorization. Customer event groups are explicitly `NOT_AUTHORIZED`; automation-script search remains `NOT_IMPLEMENTED`. This evidence does not claim UI/browser acceptance, any external delivery, production approval, or completion of all contract requirements.

## Exact snapshot scope

The real PG/HTTP run executed the compiled backend snapshot at `c26d4f584565400babba131e201e94882f842473` and the JAR hash recorded above. It does not claim that subsequent frontend changes or every file in the final integration repository belonged to this snapshot. At evidence packaging, production sources/resources in the integration checkout and backend test/fixture-only follow-up were compared with the tested snapshot; every compared file matched, and the backend JAR remained identical. See `post-snapshot-production-equivalence.json`. Later test/fixture additions require their own normal test results; they are not retroactively included in these 32 real PG/72 HTTP counts.
