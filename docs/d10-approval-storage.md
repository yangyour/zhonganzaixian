> 当前谱系：用户 V39 为邮件；审批候选现为 V40，组合 API 0.60。本文及旧证据保留历史 V38/V39 审批记录，不代表当前 V40 已应用。见 [当前合入说明](upstream-email-approval-20261019.md)。

> 历史基线说明：本文保留旧审批分支的 V38 / API 0.58 验证记录。当前上游 V38 是用户企业微信；审批在新谱系为 V39，组合 API 为 0.59。旧证据保持原字节，不能证明本线 V39 已执行。当前映射与验证见 [合入说明](upstream-wecom-integration-20261019.md)。

# D10 durable revision review

## Implemented boundary

`ChangeNoticeApprovalRepository` persists review evidence for `(tenant_id, notice_id, draft_revision)`. The source draft, ordered asset references and canonical SHA-256 are reconstructed from the immutable V25 revision, never from an untrusted request body. An untouched revision has derived approval version 1 / DRAFT; V38 stores only append-only transitions at approval versions 2–3. Replay verifies the state machine and source fingerprint. Approval never sets effective, execution or suppression permissions.

Writes require the existing platform protected idempotent transaction. The authorization callback must run before every receipt replay. Current active user/membership, permission version and existing `change_notice_drafts:write` technical capability are checked and locked; applicant-only submit/withdraw and distinct configured reviewer rules are then applied. Existing read capability authorizes tenant-scoped historical inspection. This reuses the technical capability ceiling; it does not assign an invented Q09 reviewer role.

All transitions require the draft head still to equal the target revision. Editing or archiving after submission blocks further transitions on the old revision (409); historical review evidence remains readable and cannot transfer to a replacement revision. Another revision starts in DRAFT. Head locking and state/version checks serialize competing commands; SQL independently checks membership, head, fingerprint, chain, actor and policy. Application audit and platform receipt writes share the same transaction as the event. The database assigns millisecond audit time after authorization locks; caller-supplied past/future timestamps are replaced. INSERT RETURNING supplies the authoritative timestamp used in the returned snapshot and idempotency receipt.

`view` returns the exact snapshot and informational `allowedActions` from one authorized read-only repeatable-read transaction. It checks the technical write capability, lifecycle state, current/unarchived head and current explicit Q09 policy without taking publisher locks or writing audit/receipt records. Historical or archived revisions have no actions. Mutation authorization remains mandatory; an availability response is never permission to execute later. Mutation responses should refresh this view instead of freezing transient actions into idempotency receipts.

## Configured authority

The null-by-default `change_notice.approver_policy` registry key accepts exactly:

```json
{"reviewer_user_ids":["00000000-0000-4000-8000-000000000001"]}
```

This is a format example, not an approved user or seeded value. The explicit UUID allowlist must contain 1–1000 distinct canonical UUIDs. Malformed, disabled, expired, absent or scheduled-only policies do not grant authority. A review resolves the latest currently effective release under the runtime publisher's advisory lock, records that exact release ID and rechecks current membership. A newer expired release does not resurrect older authority. Existing approvals remain historical facts after a policy change, while replay authorization uses current policy. The policy does not authorize publication/activation of an approved notice, matching, automatic P3, actual device work or customer production approval.

## Migration and wiring status

V38 was explicitly applied once in the disposable dot PostgreSQL17.9/ICU76.1 test environment at 2026-10-19T06:44:28.980258Z, then repeated/read-only checks applied zero migrations. Its frozen SHA-256 is `a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756`. The original designated server was not touched. 89 existing-table fingerprints remained unchanged apart from the expected null registry addition. No V1–V37 bytes were changed by this slice. The dedicated verifier checks columns, relationships, ACLs, append-only trigger body hash, frozen resource hash and all 11 CHECK-definition hashes. It normalizes deparsing to UTC / ISO, MDY and restores caller settings; same-count weakened constraints fail verification. It never executes DDL. No API, frontend or capability catalog is wired by this slice. Shared V38 CLI/manifest/verifier registration and config baseline/validation are separate integration changes; this storage branch does not own those files. Neither API nor worker may auto-migrate.

## Validation

Exact project Maven dependencies and JDK17 compile domain/persistence successfully. Isolated parser/resource/SQL-text checks are not PostgreSQL proof. The real integration class runs only with `MOP_RUN_CHANGE_NOTICE_APPROVAL_TESTS=true` plus an explicitly configured existing loopback `mop_test` and `mop_test_runtime` credentials. It requires V38 already applied through the separately authorized migration flow and the normal synthetic fixture identities/assets. It does not start a database or migrate. It refuses to replace non-test policy releases, retains synthetic notices/evidence, and disables its own synthetic policy afterward. Do not run against production.

Final validation in the disposable dot PostgreSQL17.9 environment passed all 21 real database tests (zero failures/errors/skips), plus 7 isolated contract tests. An earlier expanded run had one JDBC-incompatible DateStyle fixture error; the corrected fixture retained the original tamper assertion and the failed log is preserved.

Coverage includes actual durable readback with a fresh repository, exact revision separation after edits, idempotent replay, CAS races, stale/cross-tenant membership, self-review, current policy disabling, transaction rollback, direct-SQL rewrite/delete/hash and stale-head rejection, and server-default timestamp precision. See the dated evidence directory for actual run status rather than inferring execution from test existence.
