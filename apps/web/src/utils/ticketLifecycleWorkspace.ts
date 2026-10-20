import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { utcInstantNanos } from './utcInstant'

export type LifecycleAction = 'accept' | 'transfer' | 'wait-for-vendor' | 'resume' | 'submit-resolution' | 'confirm' | 'not-resolved' | 'reopen'
export type LifecycleStatus = 'NEW' | 'IN_PROGRESS' | 'WAITING_VENDOR' | 'PENDING_CUSTOMER' | 'RESOLVED' | 'CLOSED' | 'REOPENED'
export interface LifecycleContext { tenantId: string; ticketId: string; actorId: string; permissionVersion: number; customer: boolean; canWrite: boolean }
export interface LifecycleSnapshot { tenant_id: string; ticket_id: string; version: number; state: LifecycleStatus; reopen_count: number; occurred_at: string; resolved_at: string | null; closed_at: string | null; response_due_at: string | null; recovery_due_at: string | null; solution_snapshot_hash: string | null; close_mode: 'customer_confirm' | 'timeout_auto' | null; assignee_label: '工程师' | null; assigned_engineer_id: string | null; production_approved: false; notification_status: 'NOT_INTEGRATED'; sla_clock_status: 'NOT_INTEGRATED' }
export interface LifecycleSolution { tenant_id: string; ticket_id: string; lifecycle_version: number; resolved_at: string; ticket_summary: string; solution: string; snapshot_hash: string; engineer_label: '工程师'; source: 'postgresql'; production_approved: false }
interface Attempt { action: LifecycleAction; key: string; payload: string; state: 'uncertain' | 'accepted' | 'rejected' }
export interface LifecycleWorkspaceState { snapshot: LifecycleSnapshot | null; solution: LifecycleSolution | null; loaded: boolean; busy: 'read' | 'write' | null; error: unknown; attempt: Attempt | null }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const hash = /^[0-9a-f]{64}$/
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1 && Number(v) <= 9007199254740990
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const instant = (v: unknown): v is string => typeof v === 'string' && v.endsWith('Z') && utcInstantNanos(v) !== null
const invalid = () => new ApiError(200, 'INVALID_LIFECYCLE_RESPONSE', '工单状态或解决方案回读不一致，请重新读取', '—')
const customerActions: LifecycleAction[] = ['confirm', 'not-resolved', 'reopen']
const matrix: Record<LifecycleAction, LifecycleStatus[]> = { accept: ['NEW'], transfer: ['NEW', 'IN_PROGRESS', 'WAITING_VENDOR', 'REOPENED'], 'wait-for-vendor': ['IN_PROGRESS', 'REOPENED'], resume: ['WAITING_VENDOR'], 'submit-resolution': ['IN_PROGRESS', 'REOPENED'], confirm: ['PENDING_CUSTOMER'], 'not-resolved': ['PENDING_CUSTOMER'], reopen: ['CLOSED'] }
export const lifecycleLabels: Record<LifecycleStatus, string> = { NEW: '待接单', IN_PROGRESS: '处理中', WAITING_VENDOR: '待厂商', PENDING_CUSTOMER: '待客户确认', RESOLVED: '已解决', CLOSED: '已关闭', REOPENED: '已重开' }
export const lifecycleActionLabels: Record<LifecycleAction, string> = { accept: '接单', transfer: '转派', 'wait-for-vendor': '等待厂商', resume: '恢复处理', 'submit-resolution': '提交解决方案', confirm: '确认已解决', 'not-resolved': '仍未解决', reopen: '重开工单' }
export function lifecycleActions(s: LifecycleSnapshot | null, c: LifecycleContext | null): LifecycleAction[] {
  if (!s || !c?.canWrite) return []
  return (Object.keys(matrix) as LifecycleAction[]).filter(a => matrix[a].includes(s.state) && c.customer === customerActions.includes(a))
}
export function readLifecycle(value: unknown, context: LifecycleContext): LifecycleSnapshot | null {
  if (!exact(value, ['lifecycle','status','source','production_approved']) || value.source !== 'postgresql' || value.production_approved !== false) throw invalid()
  if (value.lifecycle === null && value.status === 'NOT_STARTED') return null
  const s = value.lifecycle
  if (value.status !== 'TEST_LIFECYCLE' || !exact(s, ['tenant_id','ticket_id','version','state','reopen_count','occurred_at','resolved_at','closed_at','response_due_at','recovery_due_at','solution_snapshot_hash','close_mode','assignee_label','assigned_engineer_id','production_approved','notification_status','sla_clock_status']) || s.tenant_id !== context.tenantId || s.ticket_id !== context.ticketId || !version(s.version) || typeof s.state !== 'string' || !Object.hasOwn(lifecycleLabels, s.state) || !Number.isSafeInteger(s.reopen_count) || Number(s.reopen_count) < 0 || !instant(s.occurred_at) || s.production_approved !== false || s.notification_status !== 'NOT_INTEGRATED' || s.sla_clock_status !== 'NOT_INTEGRATED') throw invalid()
  for (const k of ['resolved_at','closed_at','response_due_at','recovery_due_at']) if (s[k] !== null && !instant(s[k])) throw invalid()
  if (s.solution_snapshot_hash !== null && (typeof s.solution_snapshot_hash !== 'string' || !hash.test(s.solution_snapshot_hash)) || ![null,'customer_confirm','timeout_auto'].includes(s.close_mode as null | string) || ![null,'工程师'].includes(s.assignee_label as null | string) || s.assigned_engineer_id !== null && (context.customer || typeof s.assigned_engineer_id !== 'string' || !uuid.test(s.assigned_engineer_id))) throw invalid()
  if (['PENDING_CUSTOMER','RESOLVED','CLOSED'].includes(s.state) && (!s.resolved_at || !s.solution_snapshot_hash) || (s.state === 'CLOSED' ? !s.closed_at || !s.close_mode : s.closed_at !== null || s.close_mode !== null)) throw invalid()
  return structuredClone(s) as unknown as LifecycleSnapshot
}
function readSolution(value: unknown, s: LifecycleSnapshot): LifecycleSolution {
  if (!exact(value, ['tenant_id','ticket_id','lifecycle_version','resolved_at','ticket_summary','solution','snapshot_hash','engineer_label','source','production_approved']) || value.tenant_id !== s.tenant_id || value.ticket_id !== s.ticket_id || !version(value.lifecycle_version) || value.lifecycle_version > s.version || value.resolved_at !== s.resolved_at || value.snapshot_hash !== s.solution_snapshot_hash || typeof value.ticket_summary !== 'string' || !value.ticket_summary.trim() || typeof value.solution !== 'string' || Array.from(value.solution.trim()).length < 20 || value.solution.length > 65536 || value.engineer_label !== '工程师' || value.source !== 'postgresql' || value.production_approved !== false) throw invalid()
  return structuredClone(value) as unknown as LifecycleSolution
}
export const emptyLifecycleState = (): LifecycleWorkspaceState => ({ snapshot: null, solution: null, loaded: false, busy: null, error: null, attempt: null })
export function createTicketLifecycleWorkspace(client: Pick<ApiClient,'request'>, changed: (state: LifecycleWorkspaceState) => void, key: () => string = createRequestId) {
  let context: LifecycleContext | null = null, state = emptyLifecycleState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  const path = (c: LifecycleContext) => `/tickets/${c.ticketId}/lifecycle`
  function reset(next: LifecycleContext | null) {
    controller?.abort(); ++epoch; state = emptyLifecycleState()
    context = next && [next.tenantId,next.ticketId,next.actorId].every(id => uuid.test(id)) && version(next.permissionVersion) && typeof next.customer === 'boolean' && typeof next.canWrite === 'boolean' ? { ...next } : null
    if (next && !context) state.error = invalid(); emit()
  }
  async function refresh() {
    if (!context || state.busy === 'write') return false
    const c = { ...context }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    state = { ...state, snapshot: null, solution: null, loaded: false, busy: 'read', error: null }; emit()
    try {
      const s = readLifecycle(await client.request<unknown>(path(c), { signal: abort.signal }), c)
      if (turn !== epoch || abort.signal.aborted) return false
      if (state.attempt?.state === 'accepted' && (!s || s.version < (JSON.parse(state.attempt.payload).expected_version as number) + 1)) throw invalid()
      const solution = s?.solution_snapshot_hash ? readSolution(await client.request<unknown>(path(c) + '/solution?expected_hash=' + s.solution_snapshot_hash, { signal: abort.signal }), s) : null
      if (turn !== epoch || abort.signal.aborted) return false
      state = { ...state, snapshot: s, solution, loaded: true }; return true
    } catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!context?.canWrite || state.busy) return false
    const c = { ...context }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as { expected_version: number }
      const s = readLifecycle(await client.request<unknown>(path(c) + '/actions/' + attempt.action, { method: 'POST', body, idempotencyKey: attempt.key, signal: abort.signal }), c)
      if (turn !== epoch || abort.signal.aborted) return false
      if (!s || s.version !== body.expected_version + 1) throw invalid()
      const target = { accept: 'IN_PROGRESS', transfer: null, 'wait-for-vendor': 'WAITING_VENDOR', resume: 'IN_PROGRESS', 'submit-resolution': 'PENDING_CUSTOMER', confirm: 'CLOSED', 'not-resolved': 'REOPENED', reopen: 'REOPENED' }[attempt.action]
      if (target && s.state !== target) throw invalid()
      state = { ...state, snapshot: s, solution: null, busy: null, attempt: { ...attempt, state: 'accepted' } }; emit(); await refresh(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, snapshot: null, solution: null, loaded: false, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(action: LifecycleAction, reason = '', assigneeId = '', keepPriorAssignee = false) {
    if (!context || state.busy || unresolved() || !state.snapshot || !lifecycleActions(state.snapshot, context).includes(action)) return false
    const body: Record<string,unknown> = { expected_version: state.snapshot.version }
    if (!['accept','resume','confirm'].includes(action)) {
      if (!reason.trim() || Array.from(reason).length > 16000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(reason)) throw new Error('请填写有效说明，最多16000个字符')
      if (action === 'submit-resolution' && Array.from(reason.trim()).length < 20) throw new Error('解决方案至少20个字符')
      body.reason = reason
    }
    if (action === 'confirm' || action === 'not-resolved') {
      if (!state.solution || state.solution.snapshot_hash !== state.snapshot.solution_snapshot_hash) return false
      body.expected_solution_hash = state.solution.snapshot_hash
    }
    if (action === 'transfer') { if (!uuid.test(assigneeId)) throw new Error('请选择有效工程师'); body.assignee_id = assigneeId; body.keep_prior_assignee = keepPriorAssignee }
    return send({ action, key: key(), payload: JSON.stringify(body), state: 'uncertain' })
  }
  emit(); return { reset, refresh, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, dispose: () => reset(null), state: () => detachedWorkspaceState(state) }
}
