import { ApiError, isCancelled, type ApiClient } from '../api/client'
import type { ConfigurationRevision, ConfigurationRevisionDraft } from '../api/types'
export interface RevisionWriteContext { tenantId: string; actorId: string; permissionVersion: number; configurationKey: string }
type Action = 'create' | 'submit' | 'withdraw' | 'review'
interface Attempt { action: Action; path: string; key: string; payload: string; baseline: ConfigurationRevision | null; state: 'uncertain' | 'accepted' | 'rejected' }
export interface RevisionWriteState { attempt: Attempt | null; receipt: ConfigurationRevision | null; busy: boolean; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const bad = () => new ApiError(200, 'INVALID_CONFIGURATION_REVISION_RESPONSE', '配置修订范围、版本或未生效事实回读异常，未确认新的成功结果', '—')
const invalid = () => new Error('修订动作、原目标或输入不完整；请明确准备后提交')
const timestamp = (v: unknown) => { if (typeof v !== 'string') throw bad(); const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(v); if (!match) throw bad(); const normalized = match[1] + '.' + (match[2] || '').padEnd(3, '0').slice(0, 3) + 'Z'; const millis = Date.parse(normalized); if (!Number.isFinite(millis) || new Date(millis).toISOString() !== normalized || millis < -62135596800000 || millis > 253402300799999) throw bad(); return millis }

const canonical = (v: unknown): string => JSON.stringify(v && typeof v === 'object' ? Array.isArray(v) ? v.map(x => JSON.parse(canonical(x))) : Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b)).map(([k, x]) => [k, JSON.parse(canonical(x))])) : v)
function evidence(v: unknown) { if (!Array.isArray(v) || v.length < 1 || v.length > 5) throw bad(); for (const item of v) if (!exact(item, ['reference', 'title', 'sha256']) || typeof item.reference !== 'string' || !/^document-ref:[A-Za-z0-9._:-]+$/.test(item.reference) || typeof item.title !== 'string' || !item.title.trim() || typeof item.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(item.sha256)) throw bad() }
export function readConfigurationRevision(v: unknown, context: RevisionWriteContext): ConfigurationRevision {
  if (!exact(v, ['id', 'tenant_id', 'configuration_key', 'proposed_value', 'reason', 'evidence_refs', 'workflow_status', 'version', 'created_by', 'created_at', 'updated_at', 'reviewed_by', 'reviewed_at', 'review_comment', 'decision_status', 'effective_value', 'confirmation_status', 'source', 'history']) || typeof v.id !== 'string' || !uuid.test(v.id) || v.tenant_id !== context.tenantId || v.configuration_key !== context.configurationKey || !version(v.version) || v.proposed_value === null || typeof v.reason !== 'string' || !v.reason.trim() || typeof v.created_by !== 'string' || !uuid.test(v.created_by) || v.source !== 'postgresql' || v.effective_value !== null || v.confirmation_status !== 'UNVERIFIED' || !['pending', 'contract_fixed'].includes(String(v.decision_status)) || !['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'].includes(String(v.workflow_status)) || timestamp(v.updated_at) < timestamp(v.created_at) || !Array.isArray(v.history) || v.history.length !== v.version) throw bad()
  evidence(v.evidence_refs)
  let previous: string | null = null
  for (let i = 0; i < v.history.length; i++) { const h = v.history[i]; if (!exact(h, ['action', 'actor_id', 'occurred_at', 'from_status', 'to_status', 'comment']) || typeof h.actor_id !== 'string' || !uuid.test(h.actor_id) || h.from_status !== previous || h.comment !== null && typeof h.comment !== 'string') throw bad(); timestamp(h.occurred_at); const valid = i === 0 ? h.action === 'CREATE' && h.to_status === 'DRAFT' && h.actor_id === v.created_by && h.comment === v.reason : h.action === 'SUBMIT' && previous === 'DRAFT' && h.to_status === 'IN_REVIEW' || h.action === 'WITHDRAW' && ['DRAFT', 'IN_REVIEW'].includes(previous || '') && h.to_status === 'WITHDRAWN' || h.action === 'APPROVE' && previous === 'IN_REVIEW' && h.to_status === 'APPROVED' || h.action === 'REJECT' && previous === 'IN_REVIEW' && h.to_status === 'REJECTED'; if (!valid) throw bad(); previous = String(h.to_status) }
  if (previous !== v.workflow_status) throw bad()
  if (['APPROVED', 'REJECTED'].includes(String(v.workflow_status))) { if (typeof v.reviewed_by !== 'string' || !uuid.test(v.reviewed_by) || v.reviewed_by === v.created_by || typeof v.review_comment !== 'string' || !v.review_comment.trim()) throw bad(); timestamp(v.reviewed_at) }
  else if (v.reviewed_by !== null || v.reviewed_at !== null || v.review_comment !== null) throw bad()
  return copy(v) as unknown as ConfigurationRevision
}
export const emptyRevisionWriteState = (): RevisionWriteState => ({ attempt: null, receipt: null, busy: false, error: null })
export function createConfigurationRevisionWriter(client: Pick<ApiClient, 'request'>, changed: (state: RevisionWriteState) => void, newKey: () => string = () => crypto.randomUUID()) {
  let scope: RevisionWriteContext | null = null, state = emptyRevisionWriteState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed({ ...state }), unresolved = () => state.busy || state.attempt?.state === 'uncertain'
  function reset(next: RevisionWriteContext | null) { controller?.abort(); ++epoch; const valid = next && uuid.test(next.actorId) && uuid.test(next.tenantId) && version(next.permissionVersion) && /^[A-Za-z0-9_.:-]{1,160}$/.test(next.configurationKey); scope = valid ? { ...next! } : null; state = emptyRevisionWriteState(); if (next && !valid) state.error = invalid(); emit() }
  function prepare() { if (!scope || unresolved()) return false; state = emptyRevisionWriteState(); emit(); return true }
  async function send(attempt: Attempt, retry = false) {
    if (!scope || state.busy) return false
    const context = { ...scope }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: true, error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as Record<string, unknown>; const raw = await client.request<unknown>(attempt.path, { method: 'POST', body, idempotencyKey: attempt.key, signal: abort.signal }); if (turn !== epoch || abort.signal.aborted) return false
      const result = readConfigurationRevision(raw, context), baseline = attempt.baseline
      if (attempt.action === 'create') { if (result.version !== 1 || result.workflow_status !== 'DRAFT' || result.created_by !== context.actorId || canonical(result.proposed_value) !== canonical(body.proposed_value) || result.reason !== body.reason || canonical(result.evidence_refs) !== canonical(body.evidence_refs)) throw bad() }
      else { const expected = attempt.action === 'submit' ? 'IN_REVIEW' : attempt.action === 'withdraw' ? 'WITHDRAWN' : body.decision === 'approve' ? 'APPROVED' : 'REJECTED'; const last = result.history.at(-1)!; if (!baseline || result.id !== baseline.id || result.version !== baseline.version + 1 || result.workflow_status !== expected || result.created_by !== baseline.created_by || result.created_at !== baseline.created_at || canonical(result.proposed_value) !== canonical(baseline.proposed_value) || result.reason !== baseline.reason || canonical(result.evidence_refs) !== canonical(baseline.evidence_refs) || canonical(result.history.slice(0, -1)) !== canonical(baseline.history) || last.actor_id !== context.actorId || last.comment !== (attempt.action === 'submit' ? null : attempt.action === 'withdraw' ? body.reason : body.comment)) throw bad() }
      state = { ...state, busy: false, receipt: result, attempt: { ...attempt, state: 'accepted' } }; return true
    } catch (error) { if (turn !== epoch) return false; const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_'); state = { ...state, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: false }; emit() } }
  }
  async function submit(action: Action, baseline: ConfigurationRevision | null, body: ConfigurationRevisionDraft | Record<string, unknown>) {
    if (!scope || state.attempt || state.busy) return false
    const target = baseline === null ? null : readConfigurationRevision(baseline, scope), payload = JSON.parse(JSON.stringify(body, (_key, value) => { if (value === undefined || typeof value === 'number' && !Number.isFinite(value) || ['bigint', 'function', 'symbol'].includes(typeof value)) throw invalid(); return value })) as Record<string, unknown>
    if (action === 'create') { if (target || !exact(payload, ['proposed_value', 'reason', 'evidence_refs']) || payload.proposed_value === null || payload.proposed_value === undefined || typeof payload.reason !== 'string' || !payload.reason.trim()) throw invalid(); evidence(payload.evidence_refs) }
    else { if (!target || target.version >= Number.MAX_SAFE_INTEGER || payload.expected_version !== target.version) throw invalid(); const own = target.created_by === scope.actorId; if (action === 'submit' ? !own || target.workflow_status !== 'DRAFT' || !exact(payload, ['expected_version']) : action === 'withdraw' ? !own || !['DRAFT', 'IN_REVIEW'].includes(target.workflow_status) || !exact(payload, ['expected_version', 'reason']) || typeof payload.reason !== 'string' || !payload.reason.trim() : action !== 'review' || own || target.workflow_status !== 'IN_REVIEW' || !exact(payload, ['expected_version', 'decision', 'comment']) || !['approve', 'reject'].includes(String(payload.decision)) || typeof payload.comment !== 'string' || !payload.comment.trim()) throw invalid() }
    return send({ action, baseline: target, path: action === 'create' ? '/configurations/' + encodeURIComponent(scope.configurationKey) + '/revisions' : '/configuration-revisions/' + target!.id + '/' + action, key: newKey(), payload: JSON.stringify(payload), state: 'uncertain' })
  }
  emit(); return { reset, prepare, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, state: () => state, dispose: () => reset(null) }
}
