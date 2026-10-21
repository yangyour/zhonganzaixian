import { createRequestId, digestSha256 } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, StaleRequestError, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'

export interface TicketComment { id: string; tenant_id: string; ticket_id: string; author_kind: 'REPORTER' | 'ENGINEER'; author_label: '报障人' | '工程师'; observed_ticket_version: number; text: string; text_sha256: string; created_at: string; visibility: 'REPORTER_AND_INTERNAL'; record_source: 'MANUAL_TICKET_COMMENT'; verification_status: 'SELF_REPORTED'; source: 'postgresql'; immutable: true; ticket_state_changed: false; sla_changed: false; itsm_applied: false; itsm_sync_status: 'NOT_REQUESTED'; itsm_sync_available: false }
export interface CommentContext { tenantId: string; ticketId: string; actorId: string; permissionVersion: number; canWrite: boolean; authorKind: 'REPORTER' | 'ENGINEER' }
interface CommentPage { items: TicketComment[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; source: 'postgresql'; scope: 'REPORTER_OR_CURRENT_TENANT_INTERNAL'; page_size_limit: 100 }
interface Attempt { key: string; payload: string; state: 'uncertain' | 'accepted' | 'rejected' }
export interface CommentState { page: CommentPage | null; parentVersion: number | null; receipt: TicketComment | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown; paged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const invalid = () => new ApiError(200, 'INVALID_TICKET_COMMENT_RESPONSE', '客户可见评论范围、原文摘要或版本回读异常，当前显示已清除', '—')
export function validateTicketCommentText(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !value.replace(/[\s\u0085]+/gu, '') || Array.from(value).length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(value) || Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))) throw new Error('请填写1至2000个字符的客户可见文字，允许换行和制表；不要填写密码或密钥')
}
export async function ticketCommentHash(text: string): Promise<string> { const bytes = new TextEncoder().encode(text); return Array.from(new Uint8Array(await digestSha256( bytes)), v => v.toString(16).padStart(2, '0')).join('') }
export async function readTicketComment(value: unknown, scope: CommentContext): Promise<TicketComment> {
  try {
    if (!exact(value, ['id', 'tenant_id', 'ticket_id', 'author_kind', 'author_label', 'observed_ticket_version', 'text', 'text_sha256', 'created_at', 'visibility', 'record_source', 'verification_status', 'source', 'immutable', 'ticket_state_changed', 'sla_changed', 'itsm_applied', 'itsm_sync_status', 'itsm_sync_available']) || typeof value.id !== 'string' || !uuid.test(value.id) || value.tenant_id !== scope.tenantId || value.ticket_id !== scope.ticketId || !((value.author_kind === 'REPORTER' && value.author_label === '报障人') || (value.author_kind === 'ENGINEER' && value.author_label === '工程师')) || !version(value.observed_ticket_version) || typeof value.created_at !== 'string' || !value.created_at.endsWith('Z') || !Number.isFinite(worklogInstant(value.created_at)) || value.visibility !== 'REPORTER_AND_INTERNAL' || value.record_source !== 'MANUAL_TICKET_COMMENT' || value.verification_status !== 'SELF_REPORTED' || value.source !== 'postgresql' || value.immutable !== true || value.ticket_state_changed !== false || value.sla_changed !== false || value.itsm_applied !== false || value.itsm_sync_available !== false || value.itsm_sync_status !== 'NOT_REQUESTED' || typeof value.text_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.text_sha256)) throw invalid()
    validateTicketCommentText(value.text); if (await ticketCommentHash(value.text) !== value.text_sha256) throw invalid(); return copy(value) as unknown as TicketComment
  } catch { throw invalid() }
}
async function readPage(value: unknown, scope: CommentContext, parentVersion: number, boundary: { time: string; id: string } | null): Promise<CommentPage> {
  if (!exact(value, ['items', 'has_more', 'next_before_created_at', 'next_before_id', 'source', 'scope', 'page_size_limit']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'REPORTER_OR_CURRENT_TENANT_INTERNAL' || value.page_size_limit !== 100) throw invalid()
  const items = await Promise.all(value.items.map(v => readTicketComment(v, scope)))
  if (new Set(items.map(v => v.id)).size !== items.length || items.some(v => v.observed_ticket_version > parentVersion)) throw invalid()
  let previous = boundary
  for (const note of items) { if (previous && (worklogInstant(note.created_at) > worklogInstant(previous.time) || worklogInstant(note.created_at) === worklogInstant(previous.time) && note.id >= previous.id)) throw invalid(); previous = { time: note.created_at, id: note.id } }
  const tail = items.at(-1)
  if (value.has_more ? items.length !== 15 || value.next_before_created_at !== tail?.created_at || value.next_before_id !== tail?.id : value.next_before_created_at !== null || value.next_before_id !== null) throw invalid()
  return copy({ ...value, items }) as unknown as CommentPage
}
export const emptyCommentState = (): CommentState => ({ page: null, parentVersion: null, receipt: null, attempt: null, busy: null, error: null, paged: false })
interface ReadTarget { next:boolean; boundary:{time:string;id:string}|null }
export function createTicketCommentWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: CommentState) => void, newKey: () => string = () => createRequestId()) {
  let scope: CommentContext | null = null, state = emptyCommentState(), epoch = 0, disposed = false, lastRead: ReadTarget | null = null, controller: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), path = (context: CommentContext) => `/tickets/${context.ticketId}/comments`, unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: CommentContext | null) { controller?.abort(); controller=undefined; ++epoch; lastRead=null; const valid = !disposed && next && [next.tenantId, next.ticketId, next.actorId].every(id => uuid.test(id)) && version(next.permissionVersion) && typeof next.canWrite === 'boolean' && ['REPORTER', 'ENGINEER'].includes(next.authorKind); scope = valid ? { ...next! } : null; state = emptyCommentState(); if (next && !valid && !disposed) state.error = invalid(); emit() }
  async function refresh(next = false, recovery?: ReadTarget) {
    if (!scope || disposed || state.busy === 'write' || next && !recovery && (state.busy || !state.page?.has_more)) return false
    const context = { ...scope }, boundary = recovery ? recovery.boundary : next ? { time: state.page!.next_before_created_at!, id: state.page!.next_before_id! } : null, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    const query = '?page_size=15' + (boundary ? '&before_created_at=' + encodeURIComponent(boundary.time) + '&before_id=' + boundary.id : '')
    lastRead=structuredClone({next,boundary});state = { ...state, page: null, parentVersion: null, busy: 'read', error: null, paged: next }; emit()
    try {
      const request=(url:string)=>turn!==epoch||disposed||abort.signal.aborted?Promise.reject(new StaleRequestError()):client.request<unknown>(url,{signal:abort.signal})
      const [parent, raw] = await Promise.all([request(`/tickets/${context.ticketId}`), request(path(context) + query)])
      if (turn !== epoch || abort.signal.aborted) return false
      if (!object(parent) || parent.id !== context.ticketId || parent.tenant_id !== context.tenantId || !version(parent.version) || context.authorKind === 'REPORTER' && parent.reporter_id !== context.actorId || state.receipt && parent.version < state.receipt.observed_ticket_version) throw invalid()
      const page = await readPage(raw, context, parent.version, boundary)
      if (turn !== epoch || abort.signal.aborted) return false
      if (state.receipt) { const known = page.items.find(v => v.id === state.receipt!.id); if (!next && !page.has_more && !known || known && Object.keys(known).some(key => known[key as keyof TicketComment] !== state.receipt![key as keyof TicketComment])) throw invalid() }
      state = { ...state, page, parentVersion: parent.version }; lastRead=null;return true
    } catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!scope?.canWrite || disposed || state.busy) return false
    lastRead=null;const context = { ...scope }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as { expected_ticket_version: number; text: string }
      if(turn!==epoch||disposed||abort.signal.aborted)return false
      const receipt = await readTicketComment(await client.request<unknown>(path(context), { method: 'POST', body, idempotencyKey: attempt.key, signal: abort.signal }), context)
      if (turn !== epoch || abort.signal.aborted) return false
      if (receipt.author_kind !== context.authorKind || receipt.observed_ticket_version !== body.expected_ticket_version || receipt.text !== body.text) throw invalid()
      state = { ...state, receipt, attempt: { ...attempt, state: 'accepted' }, busy: null }; emit(); if(turn===epoch&&!disposed&&!abort.signal.aborted)await refresh(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, page: null, parentVersion: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(text: string, expectedParentVersion: number) {
    if (!scope?.canWrite || disposed || state.busy || unresolved() || !state.parentVersion || state.parentVersion !== expectedParentVersion) return false
    validateTicketCommentText(text);const turn=epoch
    try{const key=newKey();if(turn!==epoch||disposed||!scope?.canWrite||state.busy||unresolved())return false;if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw Error('INVALID_KEY');return send({key,payload:JSON.stringify({expected_ticket_version:expectedParentVersion,text}),state:'uncertain'})}
    catch{if(turn===epoch&&!disposed){state={...state,error:new ApiError(0,'REQUEST_ID_UNAVAILABLE','无法生成有效请求标识；没有发送文字保存请求，请稍后重试','—')};emit()}return false}
  }
  function canRetryRead(){return !!scope&&!disposed&&!state.busy&&lastRead!==null}
  function cancelRead(){if(disposed||state.busy!=='read')return false;++epoch;controller?.abort();controller=undefined;state={...state,busy:null,error:null};emit();return true}
  async function retryRead(){if(!canRetryRead()||!lastRead)return false;const target=structuredClone(lastRead);return refresh(target.next,target)}
  emit(); return { reset, refresh:(next=false)=>refresh(next), cancelRead, retryRead, canRetryRead, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, dispose: () => { disposed=true;reset(null) }, state: () => detachedWorkspaceState(state) }
}
