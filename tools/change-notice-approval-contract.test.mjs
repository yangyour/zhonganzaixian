import test from 'node:test'
import assert from 'node:assert/strict'
import { addChangeNoticeApprovalContract } from './change-notice-approval-contract.mjs'
test('D10 contract is bounded, explicit, and grants no runtime execution', () => {
  const c = { paths: {}, components: { schemas: {} } }
  addChangeNoticeApprovalContract(c)
  assert.equal(Object.keys(c.paths).length, 3)
  const s = c.components.schemas
  assert.equal(s.ChangeNoticeApprovalAction.additionalProperties, false)
  assert.deepEqual(s.ChangeNoticeApprovalAction.required, ['expected_version', 'action', 'note'])
  for (const name of ['ChangeNoticeApprovalSnapshot', 'ChangeNoticeApprovalEvent']) {
    for (const flag of ['effective', 'device_execution_allowed', 'alert_suppression_allowed']) assert.equal(s[name].properties[flag].const, false)
  }
  for (const path of Object.values(c.paths)) for (const op of Object.values(path)) assert.equal(op['x-mop-runtime-activation'], false)
  const post = c.paths['/change-notice-drafts/{id}/revisions/{draft_revision}/approval/actions'].post
  assert.equal(post['x-mop-idempotent-replay-reauthorization'], true)
  assert.equal(post['x-mop-capability'], 'change_notice_drafts:write')
  assert.equal(post['x-mop-review-policy'], 'change_notice.approver_policy')
})
