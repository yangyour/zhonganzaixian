import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readContactFacts, type ContactFacts, type ContactContext } from './customerContactWorkspace'
export interface ContactPreviewRow { row_number: number; status: 'FORMAT_VALID' | 'INVALID'; code: 'VALIDATION_ERROR' | null; contact: (ContactFacts & Record<string, unknown>) | null; identical_to_row: number | null }
export interface ContactPreviewResult { tenant_id: string; scope: 'NEW_CONTACT_FORMAT_PREVIEW_ONLY'; source: 'EXPLICIT_INPUT'; rows: ContactPreviewRow[]; valid_rows: number; invalid_rows: number; identical_rows: number; row_limit: 100; row_limit_kind: 'TECHNICAL_REQUEST_BOUND'; can_import: false; tenant_authorization: 'CURRENT_MEMBERSHIP_VERIFIED'; existing_contact_check: 'NOT_RUN'; last_decision_maker_check: 'NOT_RUN'; notification_routing_status: 'CONFIG_PENDING'; writes_performed: false; notification_dispatched: false; preview_saved: false; job_created: false }
export interface ContactPreviewState { text: string; result: ContactPreviewResult | null; busy: boolean; error: unknown }
const bad = () => new ApiError(0, 'INVALID_CONTACT_IMPORT_PREVIEW', '格式预览输入或响应未通过核对；不接受重复JSON键、超限或失真数字，未进行导入', '—')
const copy = <T>(v: T): T => structuredClone(v)
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
function same(a: unknown, b: unknown): boolean { if (a === b) return true; if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => same(v, b[i])); if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const aa = a as Record<string, unknown>, bb = b as Record<string, unknown>; return Object.keys(aa).length === Object.keys(bb).length && Object.keys(aa).every(k => Object.hasOwn(bb, k) && same(aa[k], bb[k])) } return false }
/** Check raw JSON before JSON.parse can discard duplicate keys or round a large numeric value. */
export function parseContactPreviewText(text: string): { rows: unknown[] } {
 if (typeof text !== 'string' || text.length > 524288 || new TextEncoder().encode(text).length > 524288) throw bad()
 let position = 0, tokens = 0
 const space = () => { while (/[\x20\t\r\n]/.test(text[position] ?? '\0')) position++ }
 const token = () => { if (++tokens > 8000) throw bad() }
 function string(): string { const start = position; if (text[position++] !== '"') throw bad(); let escaped = false; while (position < text.length) { const ch = text[position++]!; if (!escaped && ch === '"') { const result: unknown = JSON.parse(text.slice(start, position)); if (typeof result !== 'string' || result.length > 2048) throw bad(); return result } if (!escaped && ch === '\\') escaped = true; else escaped = false } throw bad() }
 function value(depth: number): void {
  space(); token(); const ch = text[position]
  if (ch === '{' || ch === '[') {
   if (depth > 5) throw bad(); position++; space(); const close = ch === '{' ? '}' : ']', keys = new Set<string>()
   if (text[position] === close) { position++; token(); return }
   while (true) { if (ch === '{') { space(); token(); const key = string(); if (keys.has(key)) throw bad(); keys.add(key); space(); if (text[position++] !== ':') throw bad() } value(depth + 1); space(); if (text[position] === close) { position++; token(); return } if (text[position++] !== ',') throw bad() }
  }
  if (ch === '"') { string(); return }
  const literal = /^(?:true|false|null)/.exec(text.slice(position)); if (literal) { position += literal[0].length; return }
  const number = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(position)); if (!number || number[0].length > 32) throw bad()
  const parsed = Number(number[0]); if (!/^(?:0|-?[1-9][0-9]*)$/.test(number[0]) || !Number.isSafeInteger(parsed)) throw bad(); position += number[0].length
 }
 try { value(1); space(); if (position !== text.length) throw bad(); const parsed: unknown = JSON.parse(text); if (!exact(parsed, ['rows']) || !Array.isArray(parsed.rows) || !parsed.rows.length || parsed.rows.length > 100) throw bad(); return parsed as { rows: unknown[] } } catch { throw bad() }
}
function facts(raw: unknown): (ContactFacts & Record<string, unknown>) | null {
 try { const value = readContactFacts(raw); return { ...value, quiet_timezone: 'Asia/Shanghai', contact_verification: 'SELF_REPORTED_UNVERIFIED', channel_verification: 'NOT_VERIFIED', notification_routing_status: 'CONFIG_PENDING', delivery_eligible: value.enabled ? null : false, identity_binding_created: false, notification_dispatched: false, source: 'EXPLICIT_INPUT', record_source: 'EXPLICIT_NEW_CONTACT_INPUT' } } catch { return null }
}
/** Independently compare an actual response with every submitted row; never return a local success fallback. */
export function readContactPreviewResult(raw: unknown, tenant: string, input: { rows: unknown[] }): ContactPreviewResult {
 if (!uuid.test(tenant) || !exact(input, ['rows']) || !Array.isArray(input.rows) || !input.rows.length || input.rows.length > 100) throw bad()
 let valid = 0, invalid = 0, duplicates = 0
 const rows: ContactPreviewRow[] = []
 for (const source of input.rows) { const contact = facts(source); const index = contact ? rows.findIndex(row => row.contact !== null && same(row.contact, contact)) : -1; if (contact) valid++; else invalid++; if (index >= 0) duplicates++; rows.push({ row_number: rows.length + 1, status: contact ? 'FORMAT_VALID' : 'INVALID', code: contact ? null : 'VALIDATION_ERROR', contact, identical_to_row: index < 0 ? null : index + 1 }) }
 const expected: ContactPreviewResult = { tenant_id: tenant, scope: 'NEW_CONTACT_FORMAT_PREVIEW_ONLY', source: 'EXPLICIT_INPUT', rows, valid_rows: valid, invalid_rows: invalid, identical_rows: duplicates, row_limit: 100, row_limit_kind: 'TECHNICAL_REQUEST_BOUND', can_import: false, tenant_authorization: 'CURRENT_MEMBERSHIP_VERIFIED', existing_contact_check: 'NOT_RUN', last_decision_maker_check: 'NOT_RUN', notification_routing_status: 'CONFIG_PENDING', writes_performed: false, notification_dispatched: false, preview_saved: false, job_created: false }
 if (!same(raw, expected)) throw bad(); return copy(raw) as ContactPreviewResult
}
export function createContactPreviewWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: ContactPreviewState) => void) {
 let scope: ContactContext | null = null, generation = 0, abort: AbortController | null = null, disposed = false, state: ContactPreviewState = { text: '', result: null, busy: false, error: null }
 let lastRead: string | null = null, readRetryable = false
 const snapshot = () => ({ ...state, result: state.result ? copy(state.result) : null }), emit = () => notify(snapshot())
 function clear() { lastRead = null; readRetryable = false; ++generation; abort?.abort(); abort = null; state = { ...state, result: null, busy: false, error: null } }
 async function preview() { if (!scope || disposed || state.busy) return false; clear(); const turn = generation, originalText = state.text, context = { ...scope }, controller = new AbortController(); abort = controller; state.busy = true; emit(); let dispatched = false; try { if (turn !== generation || disposed || controller.signal.aborted) return false; const input = parseContactPreviewText(originalText); lastRead = originalText; dispatched = true; const raw = await client.request('/contacts/import-preview', { method: 'POST', body: input, signal: controller.signal }); if (turn !== generation || disposed || controller.signal.aborted) return false; state.result = readContactPreviewResult(raw, context.tenantId, input); return true } catch (error) { if (turn === generation && !disposed && !isCancelled(error)) { state.error = error; readRetryable = dispatched } return false } finally { if (turn === generation && !disposed) { state.busy = false; abort = null; emit() } } }
 function cancelRead() { if (disposed || !state.busy) return false; ++generation; abort?.abort(); abort = null; state = {...state, result: null, busy: false, error: null}; readRetryable = lastRead !== null; emit(); return true }
 return {
  state: snapshot,
  reset(context: ContactContext | null) { clear(); scope = !disposed && context && ['padmin', 'tadmin'].includes(context.role) && context.canRead === true && context.canWrite === true && typeof context.tenantId === 'string' && typeof context.actorId === 'string' && uuid.test(context.tenantId) && uuid.test(context.actorId) && Number.isSafeInteger(context.permissionVersion) && context.permissionVersion > 0 ? { ...context } : null; state.text = ''; emit() },
  change(text: string) { if (disposed) return; clear(); state.text = text; emit() },
  preview,
  cancelRead, canRetryRead: () => !disposed && readRetryable && lastRead !== null && lastRead === state.text && !state.busy, retryRead: () => readRetryable && lastRead !== null && lastRead === state.text ? preview() : Promise.resolve(false),
  dispose() { clear(); disposed = true; scope = null; state.text = ''; emit() },
 }
}
