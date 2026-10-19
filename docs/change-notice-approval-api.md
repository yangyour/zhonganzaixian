> 当前谱系：用户 V39 为邮件；审批候选现为 V40，组合 API 0.60。本文及旧证据保留历史 V38/V39 审批记录，不代表当前 V40 已应用。见 [当前合入说明](upstream-email-approval-20261019.md)。

> 历史基线说明：本文保留旧审批分支的 V38 / API 0.58 验证记录。当前上游 V38 是用户企业微信；审批在新谱系为 V39，组合 API 为 0.59。旧证据保持原字节，不能证明本线 V39 已执行。当前映射与验证见 [合入说明](upstream-wecom-integration-20261019.md)。

# D10 exact-revision approval HTTP slice

The `remote_test` Spring controller is backed only by `PostgresChangeNoticeApprovalRepository`; fixture repositories are refused. No migration is run by the configuration. This slice does not create new role grants or change the existing draft API.

## Routes

- `GET /api/v1/change-notice-drafts/{id}/revisions/{draft_revision}/approval`
- `GET .../approval/history?page_size=15&before_version=3`
- `POST .../approval/actions`, JSON `{ "expected_version": 1, "action": "SUBMIT", "note": null }`

Every action requires exactly these three fields, `Idempotency-Key` and the existing session CSRF token header `X-CSRF-TOKEN`. Actions are `SUBMIT`, `WITHDRAW`, `APPROVE`, `REJECT`. `REJECT` requires a nonblank note. Other actions allow explicit null; a supplied note must be valid nonblank Unicode text of at most 2000 code points and is never trimmed. Body cap: 16384 bytes, including unknown-length streams. Duplicate JSON keys, trailing documents, unrecognized keys, nonintegral versions, repeated query/header values and query overrides are rejected. IDs use canonical lowercase UUIDs. Revision versions are positive safe integers <= 9007199254740990; write expected versions must be smaller than that ceiling.

The approval `version` begins at 1 (`DRAFT`) independently of `draft_revision`; each accepted action increments it. Snapshot returns the immutable revision content, target IDs, fingerprint and review evidence. History returns descending events with a strictly decreasing `before_version` cursor (>=2); page sizes are 10, 15, 20 and default 15. Unknown outcomes must retain the exact request payload and original idempotency key.

## Authorization and business boundaries

Fresh session membership and existing `change_notice_drafts:read` / `change_notice_drafts:write` technical capabilities gate requests. Repository checks within the real transaction enforce tenant visibility, current membership/permission version, exact current draft revision for writes, applicant-only submit/withdraw, non-applicant review, and current published Q09 `change_notice.approver_policy`. Neither a broad platform role nor a client flag grants review authority. The idempotency authorization callback runs even when returning an existing receipt.

GET includes server-derived `allowed_actions`, calculated by the repository read-only view from current state, head and policy; historical or non-writable views must not invent eligibility. Mutation receipts deliberately have `allowed_actions: []`. Refresh GET after success to obtain current permissions rather than trusting cached receipt availability. The actions list is advisory; writes still reauthorize transactionally.

Every snapshot/event explicitly returns `effective:false`, `device_execution_allowed:false`, `alert_suppression_allowed:false`, `automatic_p3_decision:null`, and `record_source:REVISION_REVIEW_ONLY`. APPROVED is review evidence only, never activation, runtime matching, automatic P3, device work, or suppression. No customer approval or production acceptance is claimed.

## Contract integration and validation

`tools/change-notice-approval-contract.mjs` exports `addChangeNoticeApprovalContract(contract)`. The integration owner wires this into the aggregate contract builder; this isolated slice does not edit generated OpenAPI or capability catalogs.

Dedicated controller and MVC/security tests use isolated repository mocks. They exercise strict parsing, tenant/capability/session boundaries, idempotency authorization/replay, exact revision/version mapping, DTO safety flags, HTTP CSRF, sanitized unexpected errors, and pagination. They do not establish PostgreSQL, OIDC, browser, migration or end-to-end acceptance. Database/schema application remains a separate explicit operation under the final reviewed migration SHA.

Validated 2026-10-19 in the isolated dot cloud worktree: 31 Java tests passed (12 controller, 5 Spring MVC/security, 14 domain), plus 1 contract-module Node test. Evidence: `docs/evidence/d10-approval-api-20261019/isolated.json`. Maven invocation uses the supplied offline JDK/repository and `-pl server/api -am -Dtest=ChangeNoticeApprovalControllerTest,ChangeNoticeApprovalHttpSecurityTest,ChangeNoticeApprovalTest -Dsurefire.failIfNoSpecifiedTests=false` with the supplied Mockito javaagent. No real component or migration was contacted.
