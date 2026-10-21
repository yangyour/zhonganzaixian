import { ApiError, type ApiClient } from '../api/client'
import { createRequestId } from './browserCrypto'

export const workflowLabels = { OPEN: '待接单', ACCEPTED: '已接单', IN_PROGRESS: '处理中', RESOLVED: '待客户确认', CLOSED: '已关闭', CANCELLED: '已撤销' } as const
export type WorkflowState = keyof typeof workflowLabels
export interface WorkflowContext { tenantId: string; actorId: string; permissionVersion: number; ticketId: string; customer: boolean }
export interface TicketWorkflow {
  ticket_id: string; tenant_id: string; title: string; receipt_no: string; registration_created_at: string
  version: number; state: WorkflowState | null; priority: 'P1' | 'P2' | 'P3' | null; service_tier: 'BASIC' | 'STANDARD' | 'ADVANCED' | null
  assignee_id: string | null; collaborator_ids: string[]; accepted_at: string | null; resolved_at: string | null
  closed_at: string | null; confirm_due_at: string | null; close_mode: 'customer_confirm' | 'timeout_auto' | null
  reopen_count: number; confirmation_cycle: number; resolution_note: string | null; response_due_at: string | null; recovery_due_at: string | null
  sla_missing: Record<string, string>; sla_references: unknown[]; snapshot_hash: string | null; updated_at: string | null
  observed_at: string; authority_source: 'MOP'; source: 'postgresql'; scope: 'REPORTER_ONLY' | 'CURRENT_TENANT_INTERNAL'
  customer_confirmation_status: 'UNVERIFIED'; production_approved: false; external_notification_delivery: 'NOT_CONFIRMED'
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const keys = ['ticket_id','tenant_id','title','receipt_no','registration_created_at','version','state','priority','service_tier','assignee_id','collaborator_ids','accepted_at','resolved_at','closed_at','confirm_due_at','close_mode','reopen_count','confirmation_cycle','resolution_note','response_due_at','recovery_due_at','sla_missing','sla_references','snapshot_hash','updated_at','observed_at','authority_source','source','scope','customer_confirmation_status','production_approved','external_notification_delivery']
function invalid(): never { throw new Error('工单处理结果未通过核对，请重新读取') }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const instant = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v) && Number.isFinite(Date.parse(v))
export function readWorkflow(value: unknown, context: WorkflowContext): TicketWorkflow {
  if (!object(value) || Object.keys(value).length !== keys.length || keys.some(k => !(k in value))
    || value.ticket_id !== context.ticketId || value.tenant_id !== context.tenantId || !uuid.test(context.ticketId)
    || value.source !== 'postgresql' || value.authority_source !== 'MOP' || value.production_approved !== false
    || value.customer_confirmation_status !== 'UNVERIFIED' || value.external_notification_delivery !== 'NOT_CONFIRMED'
    || value.scope !== (context.customer ? 'REPORTER_ONLY' : 'CURRENT_TENANT_INTERNAL')) invalid()
  const v = value as unknown as TicketWorkflow
  if (!Number.isSafeInteger(v.version) || v.version < 0 || !Number.isSafeInteger(v.reopen_count) || v.reopen_count < 0
    || !Number.isSafeInteger(v.confirmation_cycle) || v.confirmation_cycle < 0 || typeof v.title !== 'string' || typeof v.receipt_no !== 'string'
    || !instant(v.registration_created_at) || !instant(v.observed_at) || !Array.isArray(v.sla_references)
    || !object(v.sla_missing) || Object.values(v.sla_missing).some(x => typeof x !== 'string') || !Array.isArray(v.collaborator_ids)
    || v.collaborator_ids.some(x => typeof x !== 'string' || !uuid.test(x)) || new Set(v.collaborator_ids).size !== v.collaborator_ids.length) invalid()
  for (const time of [v.accepted_at,v.resolved_at,v.closed_at,v.confirm_due_at,v.response_due_at,v.recovery_due_at,v.updated_at]) if (time !== null && !instant(time)) invalid()
  if (v.version === 0) {
    if ([v.state,v.priority,v.service_tier,v.snapshot_hash,v.updated_at,v.assignee_id,v.accepted_at,v.resolved_at,v.closed_at,v.confirm_due_at,v.close_mode,v.resolution_note,v.response_due_at,v.recovery_due_at].some(x => x !== null) || v.collaborator_ids.length || v.reopen_count || v.confirmation_cycle) invalid()
  } else if (v.state === null || !Object.hasOwn(workflowLabels,v.state) || !['P1','P2','P3'].includes(v.priority ?? '')
    || !['BASIC','STANDARD','ADVANCED'].includes(v.service_tier ?? '') || !v.updated_at || !v.snapshot_hash || !/^[0-9a-f]{64}$/.test(v.snapshot_hash)) invalid()
  if (context.customer && (v.assignee_id !== null || v.collaborator_ids.length || v.sla_references.length)) invalid()
  if (v.assignee_id !== null && (typeof v.assignee_id !== 'string' || !uuid.test(v.assignee_id))) invalid()
  if (v.state === 'CLOSED' && (!v.closed_at || !['customer_confirm','timeout_auto'].includes(v.close_mode ?? '')) || v.state !== 'CLOSED' && (v.closed_at !== null || v.close_mode !== null)) invalid()
  if (v.resolution_note !== null && typeof v.resolution_note !== 'string') invalid()
  return JSON.parse(JSON.stringify(v)) as TicketWorkflow
}
export interface WorkflowWorkspaceState { current: TicketWorkflow | null; busy: boolean; error: unknown; uncertain: boolean; receiptVersion: number | null }
export const emptyWorkflowState = (): WorkflowWorkspaceState => ({ current: null, busy: false, error: null, uncertain: false, receiptVersion: null })
export function createWorkflowWorkspace(client: Pick<ApiClient,'request'>, publish: (state: WorkflowWorkspaceState) => void) {
  let context: WorkflowContext | null = null, generation = 0, state = emptyWorkflowState(), readAbort: AbortController | null = null
  let pending: { path: string; payload: string; key: string } | null = null
  const emit = () => publish({ ...state, current: state.current ? JSON.parse(JSON.stringify(state.current)) as TicketWorkflow : null })
  const unresolved = () => !!pending
  async function refresh() {
    if (!context || state.busy) return false
    const expected = { ...context }, token = generation; readAbort?.abort(); readAbort = new AbortController()
    state = { ...state, busy: true, current: null, error: null }; emit()
    try { const raw = await client.request<unknown>(`/tickets/${expected.ticketId}/workflow`, { signal: readAbort.signal }); if (token !== generation) return false; state.current = readWorkflow(raw,expected); return true }
    catch (error) { if (token === generation) state.error = error; return false }
    finally { if (token === generation) { state.busy = false; emit() } }
  }
  async function sendOriginal() {
    if (!context || !pending || state.busy) return false
    const expected = { ...context }, token = generation, command = { ...pending }
    state = { ...state, busy: true, error: null, current: null }; emit()
    let accepted = false
    try {
      const raw = await client.request<unknown>(command.path, { method: 'POST', body: JSON.parse(command.payload), idempotencyKey: command.key })
      if (token !== generation) return false
      const receipt = readWorkflow(raw,expected); state.receiptVersion = receipt.version; pending = null; state.uncertain = false; accepted = true
    } catch (error) {
      if (token !== generation) return false
      state.error = error
      const definitive = error instanceof ApiError && [400,401,403,404,409,413,422].includes(error.status)
      if (definitive) { pending = null; state.uncertain = false } else state.uncertain = true
    } finally { if (token === generation) { state.busy = false; emit() } }
    // A replayed receipt can be older than the current version. Only this independent GET supplies current state.
    if (accepted) await refresh()
    return accepted
  }
  return {
    reset(next: WorkflowContext | null) { generation++; readAbort?.abort(); context = next ? { ...next } : null; pending = null; state = emptyWorkflowState(); emit() },
    refresh, unresolved,
    async submit(action: string, fields: Record<string,unknown>) {
      if (!context || state.busy || pending || !state.current || !['activate','accept','reassign','start','resolve','close','reopen','cancel'].includes(action)) return false
      pending = { path: `/tickets/${context.ticketId}/${action}`, payload: JSON.stringify({ ...fields, expected_version: state.current.version }), key: createRequestId() }
      state.receiptVersion = null; return sendOriginal()
    },
    retry: sendOriginal,
    dispose() { generation++; readAbort?.abort(); context = null; state.current = null }
  }
}
