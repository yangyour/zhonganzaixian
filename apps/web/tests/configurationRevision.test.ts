import { describe, expect, it } from 'vitest'
import type { ConfigurationRevisionStatus, PlatformUser } from '../src/api/types'
import { parseRevisionForm, revisionActions, RevisionValidationError, validateRevisionComment } from '../src/utils/configurationRevision'

const evidence = () => ({ reference: 'document-ref:Q03.network-thresholds', title: '书面阈值资料', sha256: 'a'.repeat(64) })
const configuration = { type: 'number', contract_value_kind: 'pending' }
const form = () => ({ proposed: '0', reason: '修订理由', evidence_refs: [evidence()] })
function errors(input: ReturnType<typeof form>) {
  try { parseRevisionForm(configuration, input); throw new Error('Expected validation failure') }
  catch (cause) { expect(cause).toBeInstanceOf(RevisionValidationError); return (cause as RevisionValidationError).fields }
}
describe('configuration revision proposals', () => {
  it('preserves explicit zero and false while rejecting empty and null suggestions', () => {
    expect(parseRevisionForm(configuration, form()).proposed_value).toBe(0)
    expect(parseRevisionForm({ ...configuration, type: 'boolean' }, { ...form(), proposed: false }).proposed_value).toBe(false)
    expect(errors({ ...form(), proposed: '' }).proposed).toBeTruthy()
    expect(() => parseRevisionForm({ ...configuration, type: 'json' }, { ...form(), proposed: 'null' })).toThrow(RevisionValidationError)
    expect(() => parseRevisionForm({ ...configuration, contract_value_kind: 'fixed_constraint' }, form())).toThrow(RevisionValidationError)
  })
  it('accepts Unicode codepoint limits and normalizes only surrounding whitespace and hash case', () => {
    const result = parseRevisionForm(configuration, { ...form(), reason: ` ${'😀'.repeat(500)} `, evidence_refs: [{ ...evidence(), title: '😀'.repeat(200), sha256: 'A'.repeat(64) }] })
    expect(Array.from(result.reason)).toHaveLength(500)
    expect(result.evidence_refs[0]?.sha256).toBe('a'.repeat(64))
    expect(errors({ ...form(), reason: '😀'.repeat(501) }).reason).toBeTruthy()
    expect(() => validateRevisionComment(' ', '审核意见')).toThrow()
    expect(() => validateRevisionComment('意见\n', '审核意见')).toThrow()
    expect(errors({ ...form(), evidence_refs: [{ ...evidence(), title: '标题\t' }] })['evidence_refs.0.title']).toBeTruthy()
    expect(errors({ ...form(), evidence_refs: [{ ...evidence(), reference: `${evidence().reference}\n` }] })['evidence_refs.0.reference']).toBeTruthy()
    expect(errors({ ...form(), evidence_refs: [{ ...evidence(), sha256: `${evidence().sha256}\n` }] })['evidence_refs.0.sha256']).toBeTruthy()
  })
  it('rejects unsafe references, malformed hashes, and missing or excessive evidence', () => {
    for (const reference of ['https://example.test/doc', 'C:\\private\\doc', 'document-ref:C:file', 'document-ref:SECRET-key', 'document-ref:apiToken', 'document-ref:private_key', 'document-ref:中文', 'document-ref:', `document-ref:${'a'.repeat(148)}`]) {
      expect(errors({ ...form(), evidence_refs: [{ ...evidence(), reference }] })['evidence_refs.0.reference']).toBeTruthy()
    }
    expect(parseRevisionForm(configuration, { ...form(), evidence_refs: [{ ...evidence(), reference: `document-ref:${'a'.repeat(147)}` }] }).evidence_refs[0]?.reference).toHaveLength(160)
    expect(errors({ ...form(), evidence_refs: [{ ...evidence(), sha256: 'g'.repeat(64) }] })['evidence_refs.0.sha256']).toBeTruthy()
    expect(errors({ ...form(), evidence_refs: [{ ...evidence(), title: '😀'.repeat(201) }] })['evidence_refs.0.title']).toBeTruthy()
    expect(errors({ ...form(), evidence_refs: [] }).evidence_refs).toBeTruthy()
    expect(errors({ ...form(), evidence_refs: Array.from({ length: 6 }, evidence) }).evidence_refs).toBeTruthy()
  })
})
describe('configuration revision action permissions', () => {
  const creator: PlatformUser = { id: 'creator', display_name: '创建人', role: 'padmin' }
  const reviewer: PlatformUser = { id: 'reviewer', display_name: '审批人', role: 'padmin' }
  const revision = (workflow_status: ConfigurationRevisionStatus) => ({ created_by: creator.id, workflow_status })
  it('allows only creators to submit and withdraw their draft or review-pending revision', () => {
    expect(revisionActions(revision('DRAFT'), creator, true)).toMatchObject({ submit: true, withdraw: true, review: false })
    expect(revisionActions(revision('IN_REVIEW'), creator, true)).toMatchObject({ submit: false, withdraw: true, review: false, waitingForReviewer: true })
    expect(revisionActions(revision('DRAFT'), reviewer, true)).toMatchObject({ submit: false, withdraw: false, review: false })
  })
  it('requires another padmin with write capability to review and keeps terminal states closed', () => {
    expect(revisionActions(revision('IN_REVIEW'), reviewer, true).review).toBe(true)
    expect(revisionActions(revision('IN_REVIEW'), { ...reviewer, role: 'tadmin' }, true).review).toBe(false)
    expect(revisionActions(revision('IN_REVIEW'), reviewer, false).review).toBe(false)
    expect(revisionActions(revision('IN_REVIEW'), null, true).review).toBe(false)
    for (const status of ['APPROVED', 'REJECTED', 'WITHDRAWN'] as const) expect(revisionActions(revision(status), creator, true)).toMatchObject({ submit: false, withdraw: false, review: false })
  })
})
