import { createRequestId } from './browserCrypto'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readContactFacts, type ContactContext, type ContactFacts } from './customerContactWorkspace'
import { parseContactPreviewText } from './contactImportPreviewWorkspace'
import { worklogInstant } from './worklogWorkspace'
import { writeFailureUncertain } from './writeFailure'
export interface ContactImportInput { expected_version: 0; mode: 'CREATE_ONLY'; acknowledge_exact_duplicate_rows: boolean; rows: ContactFacts[] }
export interface ContactImportReceipt { operation_id: string; status: 'PENDING' }
export interface ContactImportSummary { id: string; tenant_id: string; created_by: string; accepted_permission_version: number; input_sha256: string; mode: 'CREATE_ONLY'; row_count: number; status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'; step: string; attempts: number; version: number; completed_rows: number | null; error: { code: string; message: string } | null; next_attempt_at: string | null; created_at: string; updated_at: string; worker_liveness: null; notification_dispatched: false; identity_binding_created: false; contact_verification: 'SELF_REPORTED_UNVERIFIED'; source: 'postgresql'; scope: 'CURRENT_CREATOR_TENANT' }
export interface ContactImportJob extends ContactImportSummary { results: { row_number: number; contact_id: string; contact_version: 1 }[] }
export interface ContactImportPage { tenant_id: string; created_by: string; items: ContactImportSummary[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; source: 'postgresql'; scope: 'CURRENT_CREATOR_TENANT'; page_size_limit: 100 }
interface Attempt { payload: string; key: string; state: 'uncertain' | 'accepted' | 'rejected' }
export interface ContactImportState { page: ContactImportPage | null; selected: ContactImportJob | null; receipt: ContactImportReceipt | null; attempt: Attempt | null; paged: boolean; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy = <T>(v: T): T => structuredClone(v)
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const bad = () => new ApiError(0, 'INVALID_CONTACT_IMPORT', '导入范围、状态、原请求或回执核对失败；未知写入请保留原请求恢复', '—')
const integer = (v: unknown, min: number, max = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && Number(v) >= min && Number(v) <= max
const date = (v: unknown): number => { if (typeof v !== 'string' || !v.endsWith('Z')) throw bad(); try { return worklogInstant(v) } catch { throw bad() } }
const equals = (a: unknown, b: unknown): boolean => { if (a === b) return true; if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => equals(v, b[i])); if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const aa = a as Record<string, unknown>, bb = b as Record<string, unknown>; return Object.keys(aa).length === Object.keys(bb).length && Object.keys(aa).every(k => Object.hasOwn(bb, k) && equals(aa[k], bb[k])) } return false }
const keys = ['id', 'tenant_id', 'created_by', 'accepted_permission_version', 'input_sha256', 'mode', 'row_count', 'status', 'step', 'attempts', 'version', 'completed_rows', 'error', 'next_attempt_at', 'created_at', 'updated_at', 'worker_liveness', 'notification_dispatched', 'identity_binding_created', 'contact_verification', 'source', 'scope']
const failures = ['AUTHORIZATION_REVOKED', 'PAYLOAD_INVALID', 'LAST_ENABLED_DECISION_MAKER']
export function contactImportInput(text: string, acknowledge: boolean): ContactImportInput {
 if (typeof acknowledge !== 'boolean') throw bad()
 const parsed = parseContactPreviewText(text), rows = parsed.rows.map(readContactFacts)
 if (!acknowledge && rows.some((row, i) => rows.slice(0, i).some(other => equals(row, other)))) throw new ApiError(409, 'CONTACT_IMPORT_DUPLICATE_ACK_REQUIRED', '存在完全相同行；请明确确认每行分别新建，或返回修改', '—')
 return { expected_version: 0, mode: 'CREATE_ONLY', acknowledge_exact_duplicate_rows: acknowledge, rows }
}
function validateSummary(raw: Record<string, unknown>, context: ContactContext): void {
 if (typeof raw.id !== 'string' || !uuid.test(raw.id) || raw.tenant_id !== context.tenantId || raw.created_by !== context.actorId || !integer(raw.accepted_permission_version, 1) || typeof raw.input_sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(raw.input_sha256) || raw.mode !== 'CREATE_ONLY' || !integer(raw.row_count, 1, 100) || !integer(raw.version, 1) || !integer(raw.attempts, 0) || raw.worker_liveness !== null || raw.notification_dispatched !== false || raw.identity_binding_created !== false || raw.contact_verification !== 'SELF_REPORTED_UNVERIFIED' || raw.source !== 'postgresql' || raw.scope !== 'CURRENT_CREATOR_TENANT' || date(raw.updated_at) < date(raw.created_at)) throw bad()
 if (raw.error !== null && (!exact(raw.error, ['code', 'message']) || typeof raw.error.code !== 'string' || !['UPSTREAM_UNAVAILABLE', ...failures].includes(raw.error.code) || typeof raw.error.message !== 'string' || !raw.error.message || raw.error.message.length > 500)) throw bad()
 const error = raw.error as { code: string; message: string } | null
 switch (raw.status) {
  case 'PENDING': if (raw.completed_rows !== null || (raw.attempts === 0 ? raw.step !== 'QUEUED' || error !== null || raw.version !== 1 : raw.step !== 'RETRY_WAIT' || error?.code !== 'UPSTREAM_UNAVAILABLE' || raw.version < 3)) throw bad(); date(raw.next_attempt_at); break
  case 'RUNNING': if (raw.step !== 'APPLYING' || raw.attempts < 1 || raw.version < 2 || raw.completed_rows !== null || error !== null || raw.next_attempt_at !== null) throw bad(); break
  case 'SUCCEEDED': if (raw.step !== 'APPLIED' || raw.attempts < 1 || raw.version < 3 || raw.completed_rows !== raw.row_count || error !== null || raw.next_attempt_at !== null) throw bad(); break
  case 'FAILED': if (typeof raw.step !== 'string' || !failures.includes(raw.step) || raw.attempts < 1 || raw.version < 3 || raw.completed_rows !== null || error?.code !== raw.step || raw.next_attempt_at !== null) throw bad(); break
  default: throw bad()
 }
}
export function readContactImportSummary(raw: unknown, context: ContactContext): ContactImportSummary { if (!exact(raw, keys)) throw bad(); validateSummary(raw, context); return copy(raw) as unknown as ContactImportSummary }
export function readContactImportJob(raw: unknown, context: ContactContext, id: string): ContactImportJob {
 if (!exact(raw, [...keys, 'results']) || raw.id !== id || !Array.isArray(raw.results)) throw bad(); validateSummary(raw, context)
 if (raw.status === 'SUCCEEDED' ? raw.results.length !== raw.row_count : raw.results.length !== 0) throw bad()
 const seen = new Set<string>(); for (const [index, result] of raw.results.entries()) { if (!exact(result, ['row_number', 'contact_id', 'contact_version']) || result.row_number !== index + 1 || typeof result.contact_id !== 'string' || !uuid.test(result.contact_id) || seen.has(result.contact_id) || result.contact_version !== 1) throw bad(); seen.add(result.contact_id) }
 return copy(raw) as unknown as ContactImportJob
}
export function readContactImportPage(raw: unknown, context: ContactContext, before?: { time: string; id: string }): ContactImportPage {
 if (!exact(raw, ['tenant_id', 'created_by', 'items', 'has_more', 'next_before_created_at', 'next_before_id', 'source', 'scope', 'page_size_limit']) || raw.tenant_id !== context.tenantId || raw.created_by !== context.actorId || !Array.isArray(raw.items) || raw.items.length > 15 || typeof raw.has_more !== 'boolean' || raw.source !== 'postgresql' || raw.scope !== 'CURRENT_CREATOR_TENANT' || raw.page_size_limit !== 100) throw bad()
 let cursor = before; const seen = new Set<string>(); for (const value of raw.items) { const item = readContactImportSummary(value, context); if (seen.has(item.id) || cursor && (date(item.created_at) > date(cursor.time) || date(item.created_at) === date(cursor.time) && item.id >= cursor.id)) throw bad(); seen.add(item.id); cursor = { time: item.created_at, id: item.id } }
 if (raw.has_more ? raw.items.length !== 15 || raw.next_before_created_at !== cursor?.time || raw.next_before_id !== cursor?.id : raw.next_before_created_at !== null || raw.next_before_id !== null) throw bad()
 return copy(raw) as unknown as ContactImportPage
}
export const emptyContactImportState = (): ContactImportState => ({ page: null, selected: null, receipt: null, attempt: null, paged: false, busy: null, error: null })
export function createContactImportWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: ContactImportState) => void, newKey: () => string = () => createRequestId()) {
 let scope: ContactContext | null = null, state = emptyContactImportState(), epoch = 0, abort: AbortController | null = null, disposed = false
 const known = new Map<string, ContactImportSummary>(), snapshot = () => ({ ...copy({ ...state, error: null }), error: state.error }), emit = () => notify(snapshot()), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
 function remember(job: ContactImportSummary) { const current = Object.fromEntries(keys.map(k => [k, job[k as keyof ContactImportSummary]])) as unknown as ContactImportSummary, prior = known.get(job.id); if (prior) { for (const key of ['tenant_id', 'created_by', 'accepted_permission_version', 'input_sha256', 'mode', 'row_count', 'created_at'] as const) if (prior[key] !== current[key]) throw bad(); if (current.version < prior.version || current.attempts < prior.attempts || date(current.updated_at) < date(prior.updated_at) || (current.version === prior.version || ['SUCCEEDED', 'FAILED'].includes(prior.status)) && !equals(prior, current)) throw bad() } known.set(job.id, copy(current)); if (known.size > 512) known.delete(known.keys().next().value!) }
 function reset(context: ContactContext | null) { abort?.abort(); abort = null; ++epoch; known.clear(); scope = !disposed && context && ['padmin', 'tadmin'].includes(context.role) && context.canRead === true && context.canWrite === true && uuid.test(context.tenantId) && uuid.test(context.actorId) && integer(context.permissionVersion, 1) ? { ...context } : null; state = emptyContactImportState(); emit() }
 async function read(id?: string, next = false) {
  if (!scope || disposed || state.busy) return false
  const before = next && state.page?.has_more ? { time: state.page.next_before_created_at!, id: state.page.next_before_id! } : undefined
  if (next && !before) return false
  if (id !== undefined && !uuid.test(id)) { state.selected = null; state.error = bad(); emit(); return false }
  const turn = ++epoch, context = { ...scope }, controller = new AbortController(); abort = controller; state = { ...state, busy: 'read', selected: null, ...(id ? {} : { page: null, paged: next }), error: null }; emit()
  try { const query = new URLSearchParams({ page_size: '15' }); if (before) { query.set('before_created_at', before.time); query.set('before_id', before.id) } const raw = await client.request<unknown>(id ? '/contact-imports/' + id : '/contact-imports?' + query, { signal: controller.signal }); if (turn !== epoch || disposed || controller.signal.aborted) return false
   if (id) { const job = readContactImportJob(raw, context, id); if (state.receipt?.operation_id === id && state.attempt && job.row_count !== JSON.parse(state.attempt.payload).rows.length) throw bad(); remember(job); state.selected = job } else { const page = readContactImportPage(raw, context, before); page.items.forEach(remember); state.page = page } return true
  } catch (error) { if (turn === epoch && !disposed && !isCancelled(error)) state.error = error; return false } finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
 }
 async function send(attempt: Attempt, retry: boolean) {
  if (!scope || disposed || state.busy) return false
  const turn = ++epoch, controller = new AbortController(); abort = controller; state = { ...state, page: null, selected: null, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
  try { const raw = await client.request<unknown>('/contact-imports', { method: 'POST', body: JSON.parse(attempt.payload), idempotencyKey: attempt.key, signal: controller.signal }); if (turn !== epoch || disposed || controller.signal.aborted) return false
   if (!exact(raw, ['operation_id', 'status']) || typeof raw.operation_id !== 'string' || !uuid.test(raw.operation_id) || raw.status !== 'PENDING') throw bad(); state.receipt = copy(raw) as unknown as ContactImportReceipt; state.attempt = { ...attempt, state: 'accepted' }; return true
  } catch (error) { if (turn === epoch && !disposed) { state.error = error; state.attempt = { ...attempt, state: writeFailureUncertain(retry, error) ? 'uncertain' : 'rejected' } } return false } finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
 }
 async function submit(text: string, acknowledge: boolean) {
  if (!scope || disposed || state.busy || state.attempt) return false
  try { const input = contactImportInput(text, acknowledge), key = newKey(); if (typeof key !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(key)) throw bad(); return send({ payload: JSON.stringify(input), key, state: 'uncertain' }, false) } catch (error) { state.error = error; emit(); return false }
 }
 emit(); return { reset, state: snapshot, unresolved, submit, list: (next = false) => read(undefined, next), select: (id: string) => read(id), retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), begin: () => { if (!scope || disposed || state.busy || unresolved()) return false; state = { ...state, receipt: null, attempt: null, error: null }; emit(); return true }, dispose: () => { disposed = true; reset(null) } }
}
