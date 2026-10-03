import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'

export interface AuditContext { tenantId: string; actorId: string; permissionVersion: number }
export interface AuditFilters { actor_id?: string; action?: string; result?: string; resource_type?: string; resource_id?: string; trace_id?: string; occurred_from?: string; occurred_before?: string }
export interface AuditRecord { id: string; occurred_at: string; actor_id: string | null; actor_name: string; tenant_id: string; action: string; resource_type: string; resource_id: string | null; result: string; reason: string; trace_id: string; source: 'postgresql'; sequence: number; previous_hash: string; entry_hash: string }
export interface AuditPage { items: AuditRecord[]; snapshot_sequence: number; next_before_sequence: number | null; has_more: boolean; source: 'postgresql'; scope: 'CURRENT_TENANT'; page_size_limit: 100; filter_semantics: 'EXACT_MATCH_HALF_OPEN_TIME'; integrity_scope: 'EACH_RECORD_LOCAL_HASH_ONLY'; externally_anchored: false }
export interface AuditProof { status: 'EMPTY' | 'VALID' | 'INVALID'; valid: boolean | null; snapshot_sequence: number; from_sequence: number | null; to_sequence: number | null; verified_records: number; failure_sequence: number | null; failure: string; anchor_hash: string | null; final_hash: string | null; has_more: boolean; next_from_sequence: number | null; checked_at: string; source: 'postgresql'; scope: 'CURRENT_TENANT'; verification_scope: 'LOCAL_DATABASE_SNAPSHOT'; externally_anchored: false; max_range_records: 1000; hash_algorithm: 'SHA256_TOPLEVEL_SORTED_JSON_V1'; whole_snapshot_chain_checked: boolean }
export interface AuditState { page: AuditPage | null; filters: AuditFilters; pageSize: number; paged: boolean; loading: boolean; error: unknown; proof: AuditProof | null; verifying: boolean; proofError: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const hash = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const sequence = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 1
const upper = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const invalid = () => new ApiError(200, 'INVALID_AUDIT_RESPONSE', '审计范围、分页或校验结论异常，当前结果已清除', '—')
function timestamp(value: unknown) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false
  const millis = Date.parse(value), normalized = value.includes('.') ? value.replace(/\.(\d+)Z$/, (_, digits: string) => `.${digits.slice(0, 3).padEnd(3, '0')}Z`) : value.replace(/Z$/, '.000Z')
  return Number.isFinite(millis) && millis >= -62135596800000 && millis <= 253402300799999 && new Date(millis).toISOString() === normalized
}
export function readAuditPage(value: unknown, scope: AuditContext, size: number, bound?: { snapshot: number; before: number }): AuditPage {
  if (!exact(value, ['items', 'snapshot_sequence', 'next_before_sequence', 'has_more', 'source', 'scope', 'page_size_limit', 'filter_semantics', 'integrity_scope', 'externally_anchored']) || !Array.isArray(value.items) || value.items.length > size || !upper(value.snapshot_sequence) || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT' || value.page_size_limit !== 100 || value.filter_semantics !== 'EXACT_MATCH_HALF_OPEN_TIME' || value.integrity_scope !== 'EACH_RECORD_LOCAL_HASH_ONLY' || value.externally_anchored !== false || bound && value.snapshot_sequence !== bound.snapshot) throw invalid()
  let previous = bound?.before ?? value.snapshot_sequence + 1; const ids = new Set<string>()
  for (const row of value.items) {
    if (!exact(row, ['id', 'occurred_at', 'actor_id', 'actor_name', 'tenant_id', 'action', 'resource_type', 'resource_id', 'result', 'reason', 'trace_id', 'source', 'sequence', 'previous_hash', 'entry_hash']) || typeof row.id !== 'string' || !uuid.test(row.id) || ids.has(row.id) || row.tenant_id !== scope.tenantId || !sequence(row.sequence) || row.sequence >= previous || row.sequence > value.snapshot_sequence || !timestamp(row.occurred_at) || row.source !== 'postgresql' || row.actor_id !== null && (typeof row.actor_id !== 'string' || !uuid.test(row.actor_id)) || row.resource_id !== null && typeof row.resource_id !== 'string' || ['actor_name', 'action', 'resource_type', 'result', 'reason', 'trace_id'].some(key => typeof row[key] !== 'string') || !hash(row.previous_hash) || !hash(row.entry_hash)) throw invalid()
    ids.add(row.id); previous = row.sequence
  }
  if (value.has_more ? value.items.length !== size || value.next_before_sequence !== previous : value.next_before_sequence !== null) throw invalid()
  return copy(value) as unknown as AuditPage
}
const proofKeys = ['status', 'valid', 'snapshot_sequence', 'from_sequence', 'to_sequence', 'verified_records', 'failure_sequence', 'failure', 'anchor_hash', 'final_hash', 'has_more', 'next_from_sequence', 'checked_at', 'source', 'scope', 'verification_scope', 'externally_anchored', 'max_range_records', 'hash_algorithm', 'whole_snapshot_chain_checked']
const failures = ['MISSING_RECORD', 'SEQUENCE_MISMATCH', 'ROW_PAYLOAD_MISMATCH', 'HASH_MISMATCH', 'PREVIOUS_HASH_MISMATCH', 'ANCHOR_INVALID', 'DUPLICATE_ID', 'UNEXPECTED_RECORD']
export function readAuditProof(value: unknown, from: number, to: number | null): AuditProof {
  if (!exact(value, proofKeys) || !upper(value.snapshot_sequence) || !upper(value.verified_records) || value.verified_records > 1000 || typeof value.has_more !== 'boolean' || typeof value.whole_snapshot_chain_checked !== 'boolean' || typeof value.failure !== 'string' || !timestamp(value.checked_at) || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT' || value.verification_scope !== 'LOCAL_DATABASE_SNAPSHOT' || value.externally_anchored !== false || value.max_range_records !== 1000 || value.hash_algorithm !== 'SHA256_TOPLEVEL_SORTED_JSON_V1') throw invalid()
  if (value.status === 'EMPTY') {
    if (from !== 1 || to !== null || value.valid !== null || value.snapshot_sequence !== 0 || value.from_sequence !== null || value.to_sequence !== null || value.verified_records !== 0 || value.failure_sequence !== null || value.failure !== 'NONE' || value.anchor_hash !== null || value.final_hash !== null || value.has_more || value.next_from_sequence !== null || value.whole_snapshot_chain_checked) throw invalid()
  } else {
    if (!sequence(value.from_sequence) || value.from_sequence !== from || !sequence(value.to_sequence) || value.to_sequence !== (to ?? Math.min(value.snapshot_sequence, from + 999)) || value.to_sequence < from || value.to_sequence - from >= 1000 || value.to_sequence > value.snapshot_sequence || value.has_more !== (value.to_sequence < value.snapshot_sequence)) throw invalid()
    const valid = value.status === 'VALID'
    if (value.whole_snapshot_chain_checked !== (valid && from === 1 && value.to_sequence === value.snapshot_sequence) || value.next_from_sequence !== (valid && value.has_more ? value.to_sequence + 1 : null)) throw invalid()
    if (valid) { if (value.valid !== true || value.verified_records !== value.to_sequence - from + 1 || value.failure_sequence !== null || value.failure !== 'NONE' || !hash(value.anchor_hash) || !hash(value.final_hash) || from === 1 && value.anchor_hash !== '0'.repeat(64)) throw invalid() }
    else if (value.status !== 'INVALID' || value.valid !== false || !sequence(value.failure_sequence) || value.failure_sequence < Math.max(1, from - 1) || value.failure_sequence > value.to_sequence + 1 || value.verified_records > value.to_sequence - from + 1 || !failures.includes(String(value.failure)) || value.anchor_hash !== null && !hash(value.anchor_hash) || value.final_hash !== null && !hash(value.final_hash)) throw invalid()
  }
  return copy(value) as unknown as AuditProof
}
export function validateAuditFilters(value: AuditFilters): AuditFilters {
  const limits: Record<string, number> = { actor_id: 36, action: 200, result: 32, resource_type: 64, resource_id: 160, trace_id: 128, occurred_from: 35, occurred_before: 35 }
  const result: Record<string, string> = {}
  for (const [key, text] of Object.entries(value)) {
    if (!Object.hasOwn(limits, key) || typeof text !== 'string' || !text.trim() || Array.from(text).length > limits[key]! || /[\u0000-\u001f\u007f-\u009f]/.test(text) || Array.from(text).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))) throw new Error('审计筛选字段为空、过长或含非法字符')
    if (key === 'actor_id' && !uuid.test(text)) throw new Error('操作者必须为完整小写UUID')
    if (key.startsWith('occurred_') && (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(text) || !timestamp(text))) throw new Error('审计时间筛选请使用含秒的UTC时间（Z），最多3位毫秒')
    result[key] = text
  }
  if (result.occurred_from && result.occurred_before && Date.parse(result.occurred_before) <= Date.parse(result.occurred_from)) throw new Error('结束时间必须晚于开始时间，范围为左闭右开')
  return result
}
export const emptyAuditState = (): AuditState => ({ page: null, filters: {}, pageSize: 15, paged: false, loading: false, error: null, proof: null, verifying: false, proofError: null })
export function createAuditWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: AuditState) => void) {
  let scope: AuditContext | null = null, state = emptyAuditState(), listGeneration = 0, proofGeneration = 0, listAbort: AbortController | undefined, proofAbort: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state))
  function reset(next: AuditContext | null) { listAbort?.abort(); proofAbort?.abort(); ++listGeneration; ++proofGeneration; scope = next && [next.tenantId, next.actorId].every(id => uuid.test(id)) && sequence(next.permissionVersion) ? { ...next } : null; state = emptyAuditState(); if (next && !scope) state.error = invalid(); emit() }
  async function load(filters: AuditFilters = state.filters, size = state.pageSize, next = false) {
    if (!scope || next && (!state.page?.has_more || state.loading)) return false
    const context = { ...scope }, generation = ++listGeneration, prior = state.page; listAbort?.abort(); const abort = new AbortController(); listAbort = abort
    state = { ...state, page: null, loading: true, error: null, paged: next }; emit()
    try {
      if (!next) { const validated = validateAuditFilters(filters); if (![10, 15, 25, 50, 100].includes(size)) throw new Error('请选择允许的单页条数'); state = { ...state, filters: copy(validated), pageSize: size } }
      const params = new URLSearchParams({ ...state.filters, page_size: String(state.pageSize) }); const bound = next ? { snapshot: prior!.snapshot_sequence, before: prior!.next_before_sequence! } : undefined
      if (bound) { params.set('snapshot_sequence', String(bound.snapshot)); params.set('before_sequence', String(bound.before)) }
      const raw = await client.request<unknown>('/audit/records?' + params, { signal: abort.signal })
      if (generation !== listGeneration || abort.signal.aborted) return false
      state = { ...state, page: readAuditPage(raw, context, state.pageSize, bound) }; return true
    } catch (error) { if (generation === listGeneration && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (generation === listGeneration) { state = { ...state, loading: false }; emit() } }
  }
  async function verify(from = 1, to: number | null = null) {
    if (!scope) return false
    const generation = ++proofGeneration; proofAbort?.abort(); const abort = new AbortController(); proofAbort = abort
    state = { ...state, proof: null, verifying: true, proofError: null }; emit()
    try {
      if (!sequence(from) || to !== null && (!sequence(to) || to < from || to - from >= 1000)) throw new Error('校验区间必须是安全正整数，包含首尾最多1000条')
      const params = new URLSearchParams({ from_sequence: String(from) }); if (to !== null) params.set('to_sequence', String(to))
      const raw = await client.request<unknown>('/audit/integrity?' + params, { signal: abort.signal })
      if (generation !== proofGeneration || abort.signal.aborted) return false
      state = { ...state, proof: readAuditProof(raw, from, to) }; return true
    } catch (error) { if (generation === proofGeneration && !isCancelled(error)) state = { ...state, proofError: error }; return false }
    finally { if (generation === proofGeneration) { state = { ...state, verifying: false }; emit() } }
  }
  emit()
  return { reset, load, next: () => load(state.filters, state.pageSize, true), verify, verifyNext: () => state.proof?.status === 'VALID' && state.proof.next_from_sequence && !state.verifying ? verify(state.proof.next_from_sequence) : Promise.resolve(false), state: () => detachedWorkspaceState(state), dispose: () => reset(null) }
}
