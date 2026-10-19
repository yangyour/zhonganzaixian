# Event global search and guarded detail links

API 0.63 adds a bounded event group to existing current-tenant global search. It uses existing V37 records and introduces no migration, capability grant, collector or notification-provider change. V1–V41 remain byte-identical.

## Behavior and authority

- Search rule names and rule codes with literal, Unicode case-insensitive substring matching. Percent signs, underscores and backslashes are literal; query text is not silently trimmed.
- Return at most four event summaries, newest occurrence first and UUID descending for ties. A fifth matching row establishes `has_more`; it is not a total or pagination promise.
- Return only the eleven documented summary fields. Root-cause notes, raw payloads, actor identities, IPs and quarantine data are neither searched nor exposed.
- Recheck active identity, tenant membership, permission version and effective search/assets/tickets read capabilities in the same read-only REPEATABLE READ transaction as all result groups. Event visibility additionally follows the existing internal-role event-detail boundary. Customer event groups remain NOT_AUTHORIZED with null data.
- Search results navigate through a canonical named route and UUID. Detail reads work outside the event workspace's latest 25 rows and remain visible independently of workspace loading.
- Initial links, same-route changes, Back/Forward, malformed or duplicate IDs, missing/foreign records, late responses and permission changes are guarded. Equivalent session refreshes preserve unresolved writes and search modal/query/results; true security-context changes clear inaccessible state. No unknown-outcome write is silently discarded, retargeted or retried automatically.
- Stored rule text such as embedded newline/tab is preserved as escaped text, matching the actual controller and existing detail contract; query validation remains strict.

## Final verification

Integrated source checkpoint: `212c8d15d5462e00d86b468759e278b5f0a9f36d`.

- Full Maven verify/package: 3,428 discovered, 2,449 executed and passed, 979 gated skips, zero failures/errors.
- Combined contracts/email: 149 passed; generated-contract consistency passed.
- Full Web: 171 files and 2,442 tests passed; typecheck, build, static release and security-policy checks passed.
- Real isolated PostgreSQL: 32 tests passed with zero skips. Actual HTTP: 72 checks passed, including project-local authentication, permission revocation, tenant isolation and old-event direct detail. All 97 table fingerprints were unchanged across the successful read-only observation window. Expected security-denial auditing was measured separately.
- The first harness attempt passed PostgreSQL tests but did not start HTTP because bootstrap required six synthetic identities rather than five. The corrected harness reran the full lifecycle; the first attempt is retained rather than relabeled.
- Reviewer confirmed the exact combined source and the fixes for equivalent-session resets and valid stored multiline rule text.

See `event-global-search-real-validation-20261019.md` and its evidence directory for the exact frozen runtime tested, initialization/repeat/check results and cleanup. The root's final package has different archive bytes from the tested package, but recursive comparison of all 35,895 uncompressed entries, including nested dependency jars, found identical paths and bytes. Both hashes and this comparison are recorded in the integration evidence. Production sources remained unchanged after the tested snapshot; later changes were frontend, test fixtures and documentation.

## Limits

Browser visual verification, original-server deployment, provider delivery and production-scale substring performance remain NOT_RUN. The automatic-script search group remains NOT_IMPLEMENTED, and bell behavior is unchanged. This is the bounded event-search slice, not all M1-8 or full product acceptance.
