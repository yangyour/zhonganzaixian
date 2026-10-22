import { detachedWorkspaceState } from './detachedWorkspaceState'
import { utcInstantNanos } from './utcInstant'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readMetadataCurrent, validMetadataDate, type MetadataCurrent } from './assetMetadataWorkspace'

export const metadataQueryFields = ['name', 'type', 'site_id', 'brand', 'model', 'serial_number', 'location', 'owner_id', 'business_description', 'purchase_date', 'warranty_expiry', 'overdue_service_date', 'collection_method_note', 'note'] as const
export type MetadataQueryField = typeof metadataQueryFields[number]
export type MetadataFilters = Partial<Record<MetadataQueryField | 'unknown_fields', string>>
export interface MetadataQueryContext { tenantId: string; actorId: string; permissionVersion: number }
export interface MetadataQueryPage { items: MetadataCurrent[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; filters: MetadataFilters; source: 'postgresql'; scope: 'CURRENT_TENANT_INTERNAL_MANUAL_SOURCE'; page_size_limit: 100; pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY'; unknown_semantics: 'EXPLICIT_NULL_NO_INFERENCE' }
export interface MetadataQueryReadTarget { filters: MetadataFilters; boundary: { time: string; id: string } | null }
export interface MetadataQueryState { readTarget: MetadataQueryReadTarget | null; cancelledRead: boolean; page: MetadataQueryPage | null; filters: MetadataFilters; busy: boolean; error: unknown; paged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const limits: Record<MetadataQueryField, number> = { name: 100, type: 32, site_id: 64, brand: 100, model: 100, serial_number: 128, location: 200, owner_id: 36, business_description: 200, purchase_date: 10, warranty_expiry: 10, overdue_service_date: 10, collection_method_note: 100, note: 2000 }
const invalid = () => new ApiError(200, 'INVALID_ASSET_QUERY_RESPONSE', '资料查询范围、条件或分页回读异常，结果已清除', '—')
const inputInvalid = () => new Error('筛选字段、未知项或日期无效；未知不能同时填值，编码查询总长不能超过4096字节，请缩小筛选文字')
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k))
export function normalizeMetadataFilters(value: unknown): MetadataFilters {
  if (!object(value)) throw inputInvalid()
  const result: MetadataFilters = {}
  for (const [rawKey, v] of Object.entries(value)) {
    if (rawKey === 'unknown_fields') continue
    if (!metadataQueryFields.includes(rawKey as MetadataQueryField)) throw inputInvalid()
    const key = rawKey as MetadataQueryField
    if (typeof v !== 'string' || !v.replace(/[\s\u0085]+/gu, '') || Array.from(v).length > limits[key] || (key === 'note' ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(v) || Array.from(v).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))) throw inputInvalid()
    if (key === 'type' && !['router', 'switch', 'firewall', 'ap', 'wireless_controller', 'other'].includes(v) || key === 'owner_id' && !uuid.test(v.toLowerCase()) || ['purchase_date', 'warranty_expiry', 'overdue_service_date'].includes(key) && !validMetadataDate(v)) throw inputInvalid()
    result[key] = v
  }
  if (Object.hasOwn(value, 'unknown_fields')) {
    if (typeof value.unknown_fields !== 'string' || !value.unknown_fields) throw inputInvalid()
    const unknown = value.unknown_fields.split(',')
    if (new Set(unknown).size !== unknown.length || unknown.some(k => !metadataQueryFields.includes(k as MetadataQueryField) || ['name', 'type', 'site_id'].includes(k) || Object.hasOwn(result, k))) throw inputInvalid()
    result.unknown_fields = metadataQueryFields.filter(k => unknown.includes(k)).join(',')
  }
  return result
}
function query(filters: MetadataFilters, boundary: { time: string; id: string } | null) {
  const params = new URLSearchParams({ page_size: '15', ...filters })
  if (boundary) { params.set('before_created_at', boundary.time); params.set('before_id', boundary.id) }
  const encoded = params.toString(); if (new TextEncoder().encode(encoded).length > 4096) throw inputInvalid(); return encoded
}
const sameFilters = (a: Record<string, unknown>, b: MetadataFilters) => Object.keys(a).length === Object.keys(b).length && Object.entries(b).every(([key, value]) => a[key] === value)
function readPage(value: unknown, scope: MetadataQueryContext, filters: MetadataFilters, boundary: { time: string; id: string } | null): MetadataQueryPage {
  if (!exact(value, ['items', 'has_more', 'next_before_created_at', 'next_before_id', 'filters', 'source', 'scope', 'page_size_limit', 'pagination_consistency', 'unknown_semantics']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || !object(value.filters) || !sameFilters(value.filters, filters) || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_INTERNAL_MANUAL_SOURCE' || value.page_size_limit !== 100 || value.pagination_consistency !== 'CURRENT_REQUEST_SNAPSHOT_ONLY' || value.unknown_semantics !== 'EXPLICIT_NULL_NO_INFERENCE') throw invalid()
  const items = value.items.map(raw => { if (!object(raw) || !object(raw.asset) || typeof raw.asset.id !== 'string' || !uuid.test(raw.asset.id) || raw.asset.source !== 'manual') throw invalid(); return readMetadataCurrent(raw, { ...scope, assetId: raw.asset.id, canWrite: true }) })
  if (new Set(items.map(v => v.asset.id)).size !== items.length) throw invalid()
  let previous = boundary
  for (const row of items) {
    const a = row.asset, currentTime = utcInstantNanos(a.created_at)
    if (currentTime === null) throw invalid()
    if (previous) {
      const previousTime = utcInstantNanos(previous.time)
      if (previousTime === null || currentTime > previousTime || currentTime === previousTime && a.id >= previous.id) throw invalid()
    }
    previous = { time: a.created_at, id: a.id }
  }
  const tail = items.at(-1)?.asset
  if (value.has_more ? items.length !== 15 || value.next_before_created_at !== tail?.created_at || value.next_before_id !== tail?.id : value.next_before_created_at !== null || value.next_before_id !== null) throw invalid()
  return { ...value, items } as unknown as MetadataQueryPage
}
export const emptyMetadataQueryState = (): MetadataQueryState => ({ page: null, filters: {}, busy: false, error: null, paged: false, readTarget: null, cancelledRead: false })
export function createMetadataQuery(client: Pick<ApiClient, 'request'>, changed: (state: MetadataQueryState) => void) {
  let scope: MetadataQueryContext | null = null, state = emptyMetadataQueryState(), epoch = 0, controller: AbortController | undefined, disposed = false
  const emit = () => changed(detachedWorkspaceState(state))
  function reset(next: MetadataQueryContext | null) { controller?.abort(); controller = undefined; ++epoch; scope = !disposed && next && uuid.test(next.tenantId) && uuid.test(next.actorId) && Number.isSafeInteger(next.permissionVersion) && next.permissionVersion > 0 ? { ...next } : null; state = emptyMetadataQueryState(); if (!disposed && next && !scope) state.error = invalid(); emit() }
  async function load(rawFilters: MetadataFilters, boundary: { time: string; id: string } | null) {
    if (disposed || !scope) return false
    const context = scope, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    let filters: MetadataFilters, encoded: string
    try { filters = normalizeMetadataFilters(rawFilters); boundary = boundary ? {...boundary} : null; encoded = query(filters,boundary) }
    catch (error) { state = { ...state,page:null,busy:false,error,paged:false,readTarget:null,cancelledRead:false };controller=undefined;emit();return false }
    state = { ...state, page: null, filters, busy: true, error: null, paged: boundary !== null, readTarget:{filters:{...filters},boundary},cancelledRead:false }; emit()
    if (disposed || turn !== epoch || abort.signal.aborted || scope !== context) return false
    try {
      const raw = await client.request<unknown>('/assets/manual-metadata?' + encoded, { signal: abort.signal })
      if (disposed || turn !== epoch || abort.signal.aborted || scope !== context) return false
      const page = readPage(raw, context, filters, boundary)
      state = { ...state, page }; return true
    } catch (error) { if (!disposed && turn === epoch) state = { ...state, ...(isCancelled(error)?{cancelledRead:true}:{error}) }; return false }
    finally { if (!disposed && turn === epoch) { controller=undefined;state = { ...state, busy: false }; emit() } }
  }
  const canRetryRead=()=>!disposed&&!!scope&&!state.busy&&!!state.readTarget&&!!(state.error||state.cancelledRead)
  function retryRead(){const target=state.readTarget;return canRetryRead()&&target?load(target.filters,target.boundary):Promise.resolve(false)}
  function cancelRead(){if(disposed||!state.busy)return false;++epoch;controller?.abort();controller=undefined;state={...state,page:null,busy:false,error:null,cancelledRead:true};emit();return true}
  emit(); return { reset, cancelRead, retryRead, canRetryRead, apply: (filters: MetadataFilters) => load(filters, null), refresh: () => load(state.filters, null), next: () => !state.busy && state.page?.has_more ? load(state.filters, { time: state.page.next_before_created_at!, id: state.page.next_before_id! }) : Promise.resolve(false), dispose: () => { disposed=true;reset(null) }, state: () => detachedWorkspaceState(state) }
}
