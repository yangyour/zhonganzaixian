# D15 event-center wire alignment

This bounded repair aligns the existing event page and API 0.62 contract with the unchanged Java controller. It does not complete D15 acceptance or change collectors, notification providers, business policies, production Java, or frozen V1–V41 SQL.

## Corrected behavior

- Decode the actual workspace and nested DTO fields, nullable priorities, notification/noise states and nanosecond timestamps. Reject unexpected fields and tenant/context mismatches.
- Use the actual ingest, transition and quarantine routes and action vocabulary. Preserve the exact request, path and idempotency key after an uncertain write; never retry automatically or infer a receipt from a refresh.
- Require explicitly entered resolution/root-cause information in the legal review state. Do not manufacture a successful resolution statement.
- Reject sub-millisecond maintenance create/extension inputs before a write because the existing database stores milliseconds. Response readers still accept actual nanosecond timestamps.
- Limit manual-ticket reasons to 492 trimmed Unicode code points, reserving the existing eight-code-point stored prefix. Other 500-code-point note limits are unchanged; RESOLVE's existing Java UTF-16 minimum is explicitly tested.
- Describe SLA exclusion as recorded administrator confirmation, not verified calculation or execution.

## Verification

Final integrated implementation checkpoint: `191c7f0`, based on published migration-guard fix `c0117836b539c4624f2649ac0de10042a6dde043`.

- Full Maven verify: 3,388 discovered, 2,432 executed and passed, 956 environment-gated skips, zero failures/errors; packaging passed.
- Includes 103 new MockMvc route/security checks. Eighteen controller-produced HTTP exchanges form a checked-in cross-language fixture; these use synthetic repositories, not a live PostgreSQL or provider integration.
- Full Web: 169 files, 2,382 tests passed, including 84 focused event-center tests. Typecheck, production build and release/security-policy gates passed. The pre-existing large-chunk build warning remains.
- Combined contract/email: 147 tests passed; generated contract consistency passed.
- Actual PowerShell migration-policy suite: 262 checks passed with zero database operations.
- Independent review verified unchanged production Java, all V1–V41 SQL, deployment/router files and all 20 Email/WeCom/Huawei contract routes. No external messages, original-server access or deployment occurred.

Fixture SHA-256: `0bf5d18782322ba5ace664e5520587e494a5d305ac4e309f53d703be71e09104`.

## Remaining boundaries

Browser visual verification remains NOT_RUN because of the previously recorded sandbox restriction. This change does not claim live-device monitoring, actual channel delivery, new PostgreSQL integration, production deployment or customer acceptance. Maintenance creation still lacks page controls, and event-detail noise records are not rendered; both remain separate product work.
