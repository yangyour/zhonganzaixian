import { createRequestId, digestSha256 } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'

export interface InternalNote { id: string; tenant_id: string; ticket_id: string; actor_id: string; observed_ticket_version: number; text: string; text_sha256: string; created_at: string; visibility: 'INTERNAL'; record_source: 'MANUAL_INTERNAL_NOTE'; verification_status: 'SELF_REPORTED'; source: 'postgresql'; immutable: true; ticket_state_changed: false; sla_changed: false }
export interface NoteContext { tenantId: string; ticketId: string; actorId: string; permissionVersion: number; canWrite: boolean }
interface NotePage { items: InternalNote[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; source: 'postgresql'; scope: 'CURRENT_TENANT_INTERNAL'; page_size_limit: 100 }
interface Attempt { key: string; payload: string; state: 'uncertain' | 'accepted' | 'rejected' }
export interface NoteState { page: NotePage | null; parentVersion: number | null; receipt: InternalNote | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown; paged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const invalid = () => new ApiError(200, 'INVALID_INTERNAL_NOTE_RESPONSE', '内部备注范围、原文摘要或版本回读异常，当前显示已清除', '—')
export function validateInternalNoteText(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !value.replace(/[\s\u0085]+/gu, '') || Array.from(value).length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(value) || Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))) throw new Error('请填写1至2000个字符的内部文字，允许换行和制表；不要填写密码或密钥')
}
export async function internalNoteHash(text: string): Promise<string> { const bytes = new TextEncoder().encode(text); return Array.from(new Uint8Array(await digestSha256( bytes)), v => v.toString(16).padStart(2, '0')).join('') }
export async function readInternalNote(value: unknown, scope: NoteContext): Promise<InternalNote> {
  try {
    if (!exact(value, ['id', 'tenant_id', 'ticket_id', 'actor_id', 'observed_ticket_version', 'text', 'text_sha256', 'created_at', 'visibility', 'record_source', 'verification_status', 'source', 'immutable', 'ticket_state_changed', 'sla_changed']) || typeof value.id !== 'string' || !uuid.test(value.id) || value.tenant_id !== scope.tenantId || value.ticket_id !== scope.ticketId || typeof value.actor_id !== 'string' || !uuid.test(value.actor_id) || !version(value.observed_ticket_version) || typeof value.created_at !== 'string' || !value.created_at.endsWith('Z') || !Number.isFinite(worklogInstant(value.created_at)) || value.visibility !== 'INTERNAL' || value.record_source !== 'MANUAL_INTERNAL_NOTE' || value.verification_status !== 'SELF_REPORTED' || value.source !== 'postgresql' || value.immutable !== true || value.ticket_state_changed !== false || value.sla_changed !== false || typeof value.text_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.text_sha256)) throw invalid()
    validateInternalNoteText(value.text); if (await internalNoteHash(value.text) !== value.text_sha256) throw invalid(); return copy(value) as unknown as InternalNote
  } catch { throw invalid() }
}
async function readPage(value: unknown, scope: NoteContext, parentVersion: number, boundary: { time: string; id: string } | null): Promise<NotePage> {
  if (!exact(value, ['items', 'has_more', 'next_before_created_at', 'next_before_id', 'source', 'scope', 'page_size_limit']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_INTERNAL' || value.page_size_limit !== 100) throw invalid()
  const items = await Promise.all(value.items.map(v => readInternalNote(v, scope)))
  if (new Set(items.map(v => v.id)).size !== items.length || items.some(v => v.observed_ticket_version > parentVersion)) throw invalid()
  let previous = boundary
  for (const note of items) { if (previous && (worklogInstant(note.created_at) > worklogInstant(previous.time) || worklogInstant(note.created_at) === worklogInstant(previous.time) && note.id >= previous.id)) throw invalid(); previous = { time: note.created_at, id: note.id } }
  const tail = items.at(-1)
  if (value.has_more ? items.length !== 15 || value.next_before_created_at !== tail?.created_at || value.next_before_id !== tail?.id : value.next_before_created_at !== null || value.next_before_id !== null) throw invalid()
  return copy({ ...value, items }) as unknown as NotePage
}
export const emptyNoteState = (): NoteState => ({ page: null, parentVersion: null, receipt: null, attempt: null, busy: null, error: null, paged: false })
export function createInternalNoteWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: NoteState) => void, newKey: () => string = () => createRequestId()) {
  let scope: NoteContext | null = null, state = emptyNoteState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), path = () => `/tickets/${scope!.ticketId}/internal-notes`, unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: NoteContext | null) { controller?.abort(); ++epoch; const valid = next && [next.tenantId, next.ticketId, next.actorId].every(id => uuid.test(id)) && version(next.permissionVersion) && typeof next.canWrite === 'boolean'; scope = valid ? { ...next! } : null; state = emptyNoteState(); if (next && !valid) state.error = invalid(); emit() }
  async function refresh(next = false) {
    if (!scope || state.busy === 'write' || next && (state.busy || !state.page?.has_more)) return false
    const context = { ...scope }, boundary = next ? { time: state.page!.next_before_created_at!, id: state.page!.next_before_id! } : null, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    const query = '?page_size=15' + (boundary ? '&before_created_at=' + encodeURIComponent(boundary.time) + '&before_id=' + boundary.id : '')
    state = { ...state, page: null, parentVersion: null, busy: 'read', error: null, paged: next }; emit()
    try {
      const [parent, raw] = await Promise.all([client.request<unknown>(`/tickets/${context.ticketId}`, { signal: abort.signal }), client.request<unknown>(path() + query, { signal: abort.signal })])
      if (turn !== epoch || abort.signal.aborted) return false
      if (!object(parent) || parent.id !== context.ticketId || parent.tenant_id !== context.tenantId || !version(parent.version) || state.receipt && parent.version < state.receipt.observed_ticket_version) throw invalid()
      const page = await readPage(raw, context, parent.version, boundary)
      if (turn !== epoch || abort.signal.aborted) return false
      if (state.receipt) { const known = page.items.find(v => v.id === state.receipt!.id); if (!next && !page.has_more && !known || known && Object.keys(known).some(key => known[key as keyof InternalNote] !== state.receipt![key as keyof InternalNote])) throw invalid() }
      state = { ...state, page, parentVersion: parent.version }; return true
    } catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!scope?.canWrite || state.busy) return false
    const context = { ...scope }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as { expected_ticket_version: number; text: string }
      const receipt = await readInternalNote(await client.request<unknown>(path(), { method: 'POST', body, idempotencyKey: attempt.key, signal: abort.signal }), context)
      if (turn !== epoch || abort.signal.aborted) return false
      if (receipt.actor_id !== context.actorId || receipt.observed_ticket_version !== body.expected_ticket_version || receipt.text !== body.text) throw invalid()
      state = { ...state, receipt, attempt: { ...attempt, state: 'accepted' }, busy: null }; emit(); await refresh(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, page: null, parentVersion: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(text: string, expectedParentVersion: number) {
    if (!scope?.canWrite || state.busy || unresolved() || !state.parentVersion || state.parentVersion !== expectedParentVersion) return false
    validateInternalNoteText(text); return send({ key: newKey(), payload: JSON.stringify({ expected_ticket_version: expectedParentVersion, text }), state: 'uncertain' })
  }
  emit(); return { reset, refresh, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, dispose: () => reset(null), state: () => detachedWorkspaceState(state) }
}
