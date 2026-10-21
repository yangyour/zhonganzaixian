import { createRequestId } from './browserCrypto'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'
import { writeFailureUncertain } from './writeFailure'
export type ContactRole = 'REPORTER' | 'DECISION_MAKER' | 'TECHNICAL_CONTACT'
export type ContactPriority = 'P1' | 'P2' | 'P3' | 'P4'
export type ContactAction = 'CREATE' | 'UPDATE' | 'ARCHIVE' | 'RESTORE'
export type ContactView = 'CURRENT' | 'ARCHIVED' | 'ALL'
export interface ContactFacts { name: string; roles: ContactRole[]; mobile: string | null; email: string | null; wecom_user_id: string | null; preferred_priorities: ContactPriority[] | null; quiet_window: { enabled: boolean | null; start: string | null; end: string | null } | null; enabled: boolean }
interface ContactFlags { quiet_timezone: 'Asia/Shanghai'; contact_verification: 'SELF_REPORTED_UNVERIFIED'; channel_verification: 'NOT_VERIFIED'; notification_routing_status: 'CONFIG_PENDING'; delivery_eligible: false | null; identity_binding_created: false; notification_dispatched: false; source: 'postgresql'; record_source: 'SELF_REPORTED_CONTACT' }
export interface CustomerContact extends ContactFacts, ContactFlags { id: string; tenant_id: string; created_by: string; version: number; created_at: string; updated_at: string; recorded_by: string; recorded_at: string; action: ContactAction; archived: boolean }
export interface ContactRevision extends ContactFacts, ContactFlags { version: number; previous_version: number | null; recorded_by: string; recorded_at: string; action: ContactAction; archived: boolean }
export interface ContactContext { tenantId: string; actorId: string; permissionVersion: number; role: string; canRead: boolean; canWrite: boolean }
export interface ContactPage { tenant_id: string; items: CustomerContact[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; view: ContactView; source: 'postgresql'; scope: 'CURRENT_TENANT'; page_size_limit: 100 }
export interface ContactHistory { tenant_id: string; contact_id: string; items: ContactRevision[]; has_more: boolean; next_before_version: number | null; source: 'postgresql'; scope: 'CURRENT_TENANT'; page_size_limit: 100 }
export type ContactPayload = { expected_version: number } & Partial<ContactFacts>
interface Attempt { path: string; method: 'POST' | 'PATCH'; action: ContactAction; payload: string; key: string; baseline: CustomerContact | null; state: 'uncertain' | 'accepted' | 'rejected' }
export interface ContactState { page: ContactPage | null; selected: CustomerContact | null; history: ContactHistory | null; receipt: CustomerContact | null; attempt: Attempt | null; query: string; view: ContactView; paged: boolean; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const factKeys = ['name', 'roles', 'mobile', 'email', 'wecom_user_id', 'preferred_priorities', 'quiet_window', 'enabled']
const flagKeys = ['quiet_timezone', 'contact_verification', 'channel_verification', 'notification_routing_status', 'delivery_eligible', 'identity_binding_created', 'notification_dispatched', 'source', 'record_source']
const entryKeys = ['id', 'tenant_id', 'created_by', 'version', 'created_at', 'updated_at', 'recorded_by', 'recorded_at', 'action', 'archived']
const copy = <T>(value: T): T => structuredClone(value)
const exact = (v: unknown, keys: readonly string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const bad = () => new ApiError(0, 'INVALID_CUSTOMER_CONTACT', '联系人字段、范围、版本或回执未通过核对；未知写入须保留原请求重试', '—')
const safeVersion = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const scalar = (v: unknown, max: number, nullable = false) => v === null && nullable || typeof v === 'string' && Array.from(v).length <= max && Boolean(v.replace(/[\s\u001c-\u001f\u0085]+/gu, '')) && !/[\u0000-\u001f\u007f-\u009f]/.test(v) && !Array.from(v).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))
const date = (v: unknown) => { if (typeof v !== 'string' || !v.endsWith('Z')) throw bad(); try { return worklogInstant(v) } catch { throw bad() } }
function equal(a: unknown, b: unknown): boolean { if (a === b) return true; if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => equal(v, b[i])); if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const aa = a as Record<string, unknown>, bb = b as Record<string, unknown>; return Object.keys(aa).length === Object.keys(bb).length && Object.keys(aa).every(k => Object.hasOwn(bb, k) && equal(aa[k], bb[k])) } return false }
function set(v: unknown, allowed: string[], nullable: boolean, nonempty: boolean): string[] | null { if (v === null && nullable) return null; if (!Array.isArray(v) || v.length > allowed.length || nonempty && v.length === 0 || new Set(v).size !== v.length || !v.every(x => typeof x === 'string' && allowed.includes(x))) throw bad(); return [...v].sort() }
function email(v: unknown): boolean { if (v === null) return true; if (typeof v !== 'string' || v.length > 254) return false; const parts = v.split('@'); if (parts.length !== 2) return false; const local = parts[0]!, labels = parts[1]!.split('.'); return local.length > 0 && local.length <= 64 && !local.startsWith('.') && !local.endsWith('.') && !local.includes('..') && /^[A-Za-z0-9.!#$%&'*+/=?^_\x60{|}~-]+$/.test(local) && labels.length >= 2 && labels.every(x => /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/.test(x)) }
export function contactFacts(v: CustomerContact | ContactRevision): ContactFacts { return Object.fromEntries(factKeys.map(k => [k, copy(v[k as keyof ContactFacts])])) as unknown as ContactFacts }
export function readContactFacts(v: unknown): ContactFacts {
 if (!exact(v, factKeys) || !scalar(v.name, 200) || !scalar(v.wecom_user_id, 256, true) || typeof v.enabled !== 'boolean' || !(v.mobile === null || typeof v.mobile === 'string' && /^[+][1-9][0-9]{1,14}$/.test(v.mobile)) || !email(v.email)) throw bad()
 const roles = set(v.roles, ['REPORTER', 'DECISION_MAKER', 'TECHNICAL_CONTACT'], false, true) as ContactRole[], priorities = set(v.preferred_priorities, ['P1', 'P2', 'P3', 'P4'], true, false) as ContactPriority[] | null
 const q = v.quiet_window; if (q !== null) { if (!exact(q, ['enabled', 'start', 'end']) || !(q.enabled === null || typeof q.enabled === 'boolean')) throw bad(); if (q.enabled === true) { if (typeof q.start !== 'string' || typeof q.end !== 'string' || !/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(q.start) || !/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(q.end) || q.start === q.end) throw bad() } else if (q.start !== null || q.end !== null) throw bad() }
 return copy({ ...v, roles, preferred_priorities: priorities }) as unknown as ContactFacts
}
export function readContactPayload(v: unknown, action: ContactAction): ContactPayload { if (!['CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE'].includes(action) || !exact(v, action === 'ARCHIVE' ? ['expected_version'] : ['expected_version', ...factKeys])) throw bad(); if (action === 'CREATE' ? v.expected_version !== 0 : !safeVersion(v.expected_version) || v.expected_version >= Number.MAX_SAFE_INTEGER) throw bad(); if (action === 'ARCHIVE') return { expected_version: v.expected_version as number }; return { expected_version: v.expected_version as number, ...readContactFacts(Object.fromEntries(factKeys.map(k => [k, v[k]]))) } }
function validateFactsAndFlags(v: Record<string, unknown>) { const facts = readContactFacts(Object.fromEntries(factKeys.map(k => [k, v[k]]))); if (!equal(facts, Object.fromEntries(factKeys.map(k => [k, v[k]]))) || v.quiet_timezone !== 'Asia/Shanghai' || v.contact_verification !== 'SELF_REPORTED_UNVERIFIED' || v.channel_verification !== 'NOT_VERIFIED' || v.notification_routing_status !== 'CONFIG_PENDING' || v.delivery_eligible !== (facts.enabled ? null : false) || v.identity_binding_created !== false || v.notification_dispatched !== false || v.source !== 'postgresql' || v.record_source !== 'SELF_REPORTED_CONTACT') throw bad() }
function lifecycle(v: Record<string, unknown>) { if (!safeVersion(v.version) || typeof v.archived !== 'boolean' || typeof v.action !== 'string' || !['CREATE', 'UPDATE', 'ARCHIVE', 'RESTORE'].includes(v.action) || v.archived !== (v.action === 'ARCHIVE') || v.archived && v.enabled !== false || (v.version === 1 ? v.action !== 'CREATE' : v.action === 'CREATE')) throw bad() }
export function readCustomerContact(v: unknown, scope: ContactContext): CustomerContact {
 if (!exact(v, [...entryKeys, ...factKeys, ...flagKeys]) || v.tenant_id !== scope.tenantId || !['id', 'created_by', 'recorded_by'].every(k => typeof v[k] === 'string' && uuid.test(v[k] as string))) throw bad()
 validateFactsAndFlags(v); lifecycle(v); if (date(v.updated_at) < date(v.created_at)) throw bad(); date(v.recorded_at); return copy(v) as unknown as CustomerContact
}
export function readContactPage(v: unknown, scope: ContactContext, view: ContactView, before?: { time: string; id: string }): ContactPage {
 if (!exact(v, ['tenant_id', 'items', 'has_more', 'next_before_created_at', 'next_before_id', 'view', 'source', 'scope', 'page_size_limit']) || v.tenant_id !== scope.tenantId || !Array.isArray(v.items) || v.items.length > 15 || typeof v.has_more !== 'boolean' || v.view !== view || v.source !== 'postgresql' || v.scope !== 'CURRENT_TENANT' || v.page_size_limit !== 100) throw bad()
 const items = v.items.map(x => readCustomerContact(x, scope)); let cursor = before; const seen = new Set<string>()
 for (const item of items) { if (seen.has(item.id) || view === 'CURRENT' && item.archived || view === 'ARCHIVED' && !item.archived || cursor && (date(item.created_at) > date(cursor.time) || date(item.created_at) === date(cursor.time) && item.id >= cursor.id)) throw bad(); seen.add(item.id); cursor = { time: item.created_at, id: item.id } }
 if (v.has_more ? items.length !== 15 || v.next_before_id !== cursor?.id || v.next_before_created_at !== cursor?.time : v.next_before_id !== null || v.next_before_created_at !== null) throw bad()
 return copy(v) as unknown as ContactPage
}
export function readContactHistory(v: unknown, scope: ContactContext, id: string, before?: number): ContactHistory {
 if (!exact(v, ['tenant_id', 'contact_id', 'items', 'has_more', 'next_before_version', 'source', 'scope', 'page_size_limit']) || v.tenant_id !== scope.tenantId || v.contact_id !== id || !Array.isArray(v.items) || v.items.length > 15 || !before && v.items.length === 0 || typeof v.has_more !== 'boolean' || v.source !== 'postgresql' || v.scope !== 'CURRENT_TENANT' || v.page_size_limit !== 100) throw bad()
 let previous = before
 for (let i = 0; i < v.items.length; i++) { const item: unknown = v.items[i]; if (!exact(item, ['version', 'previous_version', 'recorded_by', 'recorded_at', 'action', 'archived', ...factKeys, ...flagKeys]) || typeof item.recorded_by !== 'string' || !uuid.test(item.recorded_by)) throw bad(); validateFactsAndFlags(item); lifecycle(item); date(item.recorded_at); const version = item.version as number; if (item.previous_version !== (version === 1 ? null : version - 1) || previous !== undefined && version >= previous || i > 0 && version !== previous! - 1) throw bad(); previous = version }
 if (v.has_more ? v.items.length !== 15 || previous === undefined || previous <= 1 || v.next_before_version !== previous : v.next_before_version !== null || v.items.length > 0 && previous !== 1) throw bad()
 return copy(v) as unknown as ContactHistory
}
export const emptyContactState = (): ContactState => ({ page: null, selected: null, history: null, receipt: null, attempt: null, query: '', view: 'CURRENT', paged: false, busy: null, error: null })
export function createContactWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: ContactState) => void, newKey: () => string = () => createRequestId()) {
 let scope: ContactContext | null = null, state = emptyContactState(), epoch = 0, abort: AbortController | null = null, disposed = false
 const snapshot = () => ({ ...copy({ ...state, error: null }), error: state.error }), emit = () => notify(snapshot()), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
 function reset(context: ContactContext | null) { abort?.abort(); abort = null; ++epoch; scope = !disposed && context && ['padmin', 'tadmin'].includes(context.role) && context.canRead === true && typeof context.canWrite === 'boolean' && typeof context.tenantId === 'string' && typeof context.actorId === 'string' && uuid.test(context.tenantId) && uuid.test(context.actorId) && safeVersion(context.permissionVersion) ? { ...context } : null; state = emptyContactState(); emit() }
 async function read(kind: 'list' | 'detail' | 'history', query: string, view: ContactView, next: boolean, id?: string) {
  if (!scope || disposed || state.busy) return false
  const before = kind === 'list' && next && state.page?.has_more && query === state.query && view === state.view ? { time: state.page.next_before_created_at!, id: state.page.next_before_id! } : undefined
  const historyBefore = kind === 'history' && next && state.history !== null && state.history.contact_id === id && state.history.has_more ? state.history.next_before_version! : undefined
  if (next && !(kind === 'list' ? before : historyBefore)) return false
  if (!['CURRENT', 'ARCHIVED', 'ALL'].includes(view) || typeof query !== 'string' || Array.from(query).length > 200 || /[\u0000-\u001f\u007f-\u009f]/.test(query) || Array.from(query).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c)) || kind !== 'list' && (!id || !uuid.test(id))) { state = { ...state, error: bad(), ...(kind === 'list' ? { page: null, selected: null, history: null } : kind === 'detail' ? { selected: null, history: null } : { history: null }) }; emit(); return false }
  const turn = ++epoch, context = { ...scope }, controller = new AbortController(); abort = controller; state = { ...state, busy: 'read', error: null, ...(kind === 'list' ? { page: null, selected: null, history: null, query, view, paged: next } : kind === 'detail' ? { selected: null, history: null } : { history: null }) }; emit()
  try { const params = new URLSearchParams({ page_size: '15' }); let path: string
   if (kind === 'list') { params.set('name_query', query); params.set('view', view); if (before) { params.set('before_created_at', before.time); params.set('before_id', before.id) } path = '/contacts?' + params } else if (kind === 'history') { if (historyBefore) params.set('before_version', String(historyBefore)); path = '/contacts/' + id + '/history?' + params } else path = '/contacts/' + id
   if (turn !== epoch || disposed || controller.signal.aborted) return false
   const raw = await client.request<unknown>(path, { signal: controller.signal }); if (turn !== epoch || disposed || controller.signal.aborted) return false
   if (kind === 'list') state.page = readContactPage(raw, context, view, before)
   else if (kind === 'history') state.history = readContactHistory(raw, context, id!, historyBefore)
   else { const row = readCustomerContact(raw, context); if (row.id !== id || state.receipt?.id === id && (row.version < state.receipt.version || row.version === state.receipt.version && !equal(row, state.receipt))) throw bad(); state.selected = row }
   return true
  } catch (error) { if (turn === epoch && !disposed && !isCancelled(error)) state.error = error; return false } finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
 }
 async function send(attempt: Attempt, retry: boolean) {
  if (!scope?.canWrite || disposed || state.busy) return false
  const turn = ++epoch, context = { ...scope }, controller = new AbortController(); abort = controller; state = { ...state, busy: 'write', attempt: { ...attempt, state: 'uncertain' }, page: null, selected: null, history: null, error: null }; emit()
  try { if (turn !== epoch || disposed || controller.signal.aborted) return false; const payload = readContactPayload(JSON.parse(attempt.payload), attempt.action), raw = await client.request<unknown>(attempt.path, { method: attempt.method, body: payload, idempotencyKey: attempt.key, signal: controller.signal }); if (turn !== epoch || disposed || controller.signal.aborted) return false
   const row = readCustomerContact(raw, context), base = attempt.baseline, expectedFacts = attempt.action === 'ARCHIVE' ? { ...contactFacts(base!), enabled: false } : readContactFacts(Object.fromEntries(factKeys.map(k => [k, payload[k as keyof ContactPayload]])))
   if (row.version !== payload.expected_version + 1 || row.action !== attempt.action || row.recorded_by !== context.actorId || !equal(contactFacts(row), expectedFacts) || (base ? row.id !== base.id || row.created_by !== base.created_by || row.created_at !== base.created_at : row.created_by !== context.actorId)) throw bad()
   state = { ...state, receipt: row, attempt: { ...attempt, state: 'accepted' } }; return true
  } catch (error) { if (turn === epoch && !disposed) state = { ...state, error, attempt: { ...attempt, state: writeFailureUncertain(retry, error) ? 'uncertain' : 'rejected' } }; return false } finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
 }
 async function submit(action: ContactAction, input: unknown, baseline: unknown = null) {
  if (!scope?.canWrite || disposed || state.busy || state.attempt) return false
  const turn = epoch
  try { const payload = readContactPayload(input, action), base = baseline === null ? null : readCustomerContact(baseline, scope); if (action === 'CREATE' ? base !== null : !base || payload.expected_version !== base.version || base.archived !== (action === 'RESTORE')) throw bad()
   const key = newKey(); if (turn !== epoch || disposed || !scope?.canWrite || state.busy || state.attempt) return false; if (typeof key !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(key)) throw bad()
   const path = action === 'CREATE' ? '/contacts' : '/contacts/' + base!.id + (action === 'ARCHIVE' ? '/archive' : action === 'RESTORE' ? '/restore' : '')
   return send({ path, method: action === 'UPDATE' ? 'PATCH' : 'POST', action, key, payload: JSON.stringify(payload), baseline: base, state: 'uncertain' }, false)
  } catch (error) { if (turn === epoch && !disposed) { state.error = error; emit() } return false }
 }
 emit(); return { reset, state: snapshot, unresolved, list: (query = '', view: ContactView = 'CURRENT', next = false) => read('list', query, view, next), select: (id: string) => read('detail', state.query, state.view, false, id), history: (id: string, next = false) => read('history', state.query, state.view, next, id), submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), begin: () => { if (!scope?.canWrite || disposed || state.busy || unresolved()) return false; state = { ...state, receipt: null, attempt: null, error: null }; emit(); return true }, dispose: () => { disposed = true; reset(null) } }
}
