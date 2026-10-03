import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readMetadataCurrent, readMetadataHistory, validMetadataDate, validateMetadataInput, type MetadataContext, type MetadataCurrent, type MetadataInput, type MetadataSnapshot, type MetadataRevision, type MetadataHistory } from './assetMetadataWorkspace'
export interface WarrantyDetails { warranty_start: string | null; warranty_supplier: string | null }
export interface WarrantyInput extends WarrantyDetails { warranty_expiry: string | null; overdue_service_date: string | null; note: string }
export interface WarrantySnapshot extends MetadataSnapshot { warranty_details: WarrantyDetails | null }
export interface WarrantyCurrent extends MetadataCurrent { warranty_details: WarrantyDetails | null; warranty_coverage_verified: false; warranty_supplier_verification: 'UNVERIFIED' }
export interface WarrantyRevision extends Omit<MetadataRevision, 'before'> { before: WarrantySnapshot; warranty_details: WarrantyDetails | null; warranty_coverage_verified: false; warranty_supplier_verification: 'UNVERIFIED' }
export interface WarrantyHistory extends Omit<MetadataHistory, 'items'> { items: WarrantyRevision[] }
interface Attempt { key: string; payload: string; baseline: WarrantyCurrent; state: 'uncertain' | 'accepted' | 'rejected' }
export interface WarrantyState { current: WarrantyCurrent | null; history: WarrantyHistory | null; receipt: WarrantyCurrent | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const object = (v: unknown): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v))
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => object(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k))
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const baseKeys = ['name', 'type', 'site_id', 'brand', 'model', 'serial_number', 'location', 'owner_id', 'business_description', 'purchase_date', 'warranty_expiry', 'overdue_service_date', 'collection_method_note', 'note'] as const
const inputKeys = ['warranty_start', 'warranty_expiry', 'warranty_supplier', 'overdue_service_date', 'note']
const invalid = () => new ApiError(200, 'INVALID_WARRANTY_RESPONSE', '维保资料范围、版本、历史或未核验标记异常，当前显示已清除', '—')
const badInput = () => new Error('请明确维保资料与资产资料备注；未知项留空，日期有效且起始不晚于到期，供应商最多200字符')
function details(v: unknown, expiry: string | null): asserts v is WarrantyDetails | null {
  if (v === null) return
  if (!exact(v, ['warranty_start', 'warranty_supplier']) || !validMetadataDate(v.warranty_start)) throw invalid()
  const s = v.warranty_supplier
  if (s !== null && (typeof s !== 'string' || !s.replace(/[\s\u001c-\u001f\u0085]+/gu, '') || Array.from(s).length > 200 || /[\u0000-\u001f\u007f-\u009f]/.test(s) || Array.from(s).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c)))) throw invalid()
  if (v.warranty_start !== null && expiry !== null && String(v.warranty_start) > expiry) throw invalid()
}
export function validateWarrantyInput(v: unknown): asserts v is WarrantyInput {
  try {
    if (!exact(v, inputKeys)) throw badInput()
    validateMetadataInput({ name: 'validation', type: 'other', site_id: 'validation', brand: null, model: null, serial_number: null, location: null, owner_id: null, business_description: null, purchase_date: null, warranty_expiry: v.warranty_expiry, overdue_service_date: v.overdue_service_date, collection_method_note: null, note: v.note })
    details({ warranty_start: v.warranty_start, warranty_supplier: v.warranty_supplier }, v.warranty_expiry as string | null)
  } catch { throw badInput() }
}
const sameDetails = (a: WarrantyDetails | null, b: WarrantyDetails | null) => a === null || b === null ? a === b : a.warranty_start === b.warranty_start && a.warranty_supplier === b.warranty_supplier
function splitCurrent(v: unknown) {
  if (!object(v) || !Object.hasOwn(v, 'warranty_details') || v.warranty_coverage_verified !== false || v.warranty_supplier_verification !== 'UNVERIFIED') throw invalid()
  const { warranty_details: d, warranty_coverage_verified: _coverage, warranty_supplier_verification: _supplier, ...base } = v
  return { base, d }
}
export function readWarrantyCurrent(v: unknown, scope: MetadataContext): WarrantyCurrent {
  try { const { base, d } = splitCurrent(v); const current = readMetadataCurrent(base, scope); details(d, current.metadata?.warranty_expiry ?? null); if (!current.metadata && d !== null) throw invalid(); return clone(v) as WarrantyCurrent } catch { throw invalid() }
}
function readWarrantyHistory(v: unknown, scope: MetadataContext, current: WarrantyCurrent, before: number | null): WarrantyHistory {
  try {
    if (!object(v) || !Array.isArray(v.items)) throw invalid()
    const stripped = v.items.map(item => { const { base, d } = splitCurrent(item); if (!object(base.before) || !Object.hasOwn(base.before, 'warranty_details') || !object(base.metadata)) throw invalid(); const { warranty_details: previous, ...snapshot } = base.before; details(d, base.metadata.warranty_expiry as string | null); details(previous, object(snapshot.metadata) ? snapshot.metadata.warranty_expiry as string | null : null); if (snapshot.metadata === null && previous !== null || previous !== null && d === null) throw invalid(); return { ...base, before: snapshot } })
    readMetadataHistory({ ...v, items: stripped }, scope, current, before)
    const result = clone(v) as unknown as WarrantyHistory
    if (!before && result.items[0] && !sameDetails(result.items[0].warranty_details, current.warranty_details)) throw invalid()
    for (let i = 1; i < result.items.length; i++) if (!sameDetails(result.items[i - 1]!.before.warranty_details, result.items[i]!.warranty_details)) throw invalid()
    return result
  } catch { throw invalid() }
}
const sameSnapshot = (a: WarrantyCurrent, b: WarrantyCurrent) => a.metadata_version === b.metadata_version && ['name', 'type', 'site_id', 'management_ip', 'source', 'status', 'lifecycle_status', 'sampled_at', 'availability', 'sync_state', 'created_at', 'updated_at'].every(k => a.asset[k as keyof typeof a.asset] === b.asset[k as keyof typeof b.asset]) && (a.metadata === null || b.metadata === null ? a.metadata === b.metadata : baseKeys.every(k => a.metadata![k] === b.metadata![k])) && sameDetails(a.warranty_details, b.warranty_details)
export const emptyWarrantyState = (): WarrantyState => ({ current: null, history: null, receipt: null, attempt: null, busy: null, error: null })
export function createWarrantyWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: WarrantyState) => void, newKey: () => string = () => crypto.randomUUID()) {
  let context: MetadataContext | null = null, state = emptyWarrantyState(), epoch = 0, controller: AbortController | undefined, known: WarrantyCurrent | null = null, visible = true
  const emit = () => changed({ ...state }), path = () => `/assets/${context!.assetId}/manual-metadata/warranty`, unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: MetadataContext | null) { controller?.abort(); ++epoch; const valid = next && [next.tenantId, next.assetId, next.actorId].every(v => uuid.test(v)) && version(next.permissionVersion) && typeof next.canWrite === 'boolean'; context = valid ? { ...next! } : null; known = null; state = emptyWarrantyState(); if (next && !valid) state.error = invalid(); emit() }
  function setVisible(value: boolean) { visible = value; if (!visible && state.busy !== 'write') { controller?.abort(); ++epoch; state = { ...state, current: null, history: null, busy: null }; emit() } }
  function observe(current: WarrantyCurrent) { if (known && (current.asset.version < known.asset.version || current.asset.version === known.asset.version && !sameSnapshot(current, known))) throw invalid(); known = clone(current) }
  async function refresh(more = false) {
    if (!visible || !context || state.busy === 'write' || more && (state.busy || !state.history?.has_more)) return false
    const scope = { ...context }, previous = state.current, before = more ? state.history!.next_before_version : null, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    state = { ...state, current: null, history: null, busy: 'read', error: null }; emit()
    try {
      const current = readWarrantyCurrent(await client.request<unknown>(path(), { signal: abort.signal }), scope); if (turn !== epoch || abort.signal.aborted) return false; observe(current)
      const boundary = more && previous?.asset.version === current.asset.version ? before : null
      const history = readWarrantyHistory(await client.request<unknown>(path() + '/history?page_size=15' + (boundary ? '&before_version=' + boundary : ''), { signal: abort.signal }), scope, current, boundary)
      if (turn !== epoch || abort.signal.aborted) return false; state = { ...state, current, history }; return true
    } catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt) {
    if (!visible || !context?.canWrite || state.busy) return false
    const scope = { ...context }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as WarrantyInput & { expected_version: number }
      const receipt = readWarrantyCurrent(await client.request<unknown>(path(), { method: 'PATCH', body, idempotencyKey: attempt.key, signal: abort.signal }), scope)
      if (turn !== epoch || abort.signal.aborted) return false
      const baseline = attempt.baseline, m = receipt.metadata
      if (receipt.asset.version !== body.expected_version + 1 || receipt.metadata_version !== receipt.asset.version || !m || !sameDetails(receipt.warranty_details, { warranty_start: body.warranty_start, warranty_supplier: body.warranty_supplier }) || m.warranty_expiry !== body.warranty_expiry || m.overdue_service_date !== body.overdue_service_date || m.note !== body.note || receipt.recorded_by !== scope.actorId || receipt.asset.management_ip !== baseline.asset.management_ip || receipt.asset.created_at !== baseline.asset.created_at || !receipt.can_edit) throw invalid()
      for (const k of baseKeys.filter(k => !['warranty_expiry', 'overdue_service_date', 'note'].includes(k))) { const expected = baseline.metadata ? baseline.metadata[k] : ['name', 'type', 'site_id'].includes(k) ? baseline.asset[k as 'name' | 'type' | 'site_id'] : null; if (m[k] !== expected) throw invalid() }
      if (!known || receipt.asset.version >= known.asset.version) observe(receipt)
      state = { ...state, receipt, current: null, history: null, busy: null, attempt: { ...attempt, state: 'accepted' } }; emit(); if (visible) await refresh(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, current: null, history: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(input: WarrantyInput, expectedVersion: number) { if (!visible || !context?.canWrite || state.busy || unresolved() || !state.current?.can_edit || state.current.asset.version !== expectedVersion) return false; validateWarrantyInput(input); if (expectedVersion >= Number.MAX_SAFE_INTEGER) throw badInput(); return send({ key: newKey(), payload: JSON.stringify({ expected_version: expectedVersion, ...clone(input) }), baseline: clone(state.current), state: 'uncertain' }) }
  emit(); return { reset, setVisible, refresh, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt) : Promise.resolve(false), unresolved, dispose: () => reset(null), state: () => state }
}
