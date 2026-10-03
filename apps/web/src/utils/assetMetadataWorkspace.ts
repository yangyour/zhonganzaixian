import { ApiError, isCancelled, type ApiClient } from '../api/client'
import type { Asset, AssetType } from '../api/types'

export interface MetadataInput { name: string; type: AssetType; site_id: string; brand: string | null; model: string | null; serial_number: string | null; location: string | null; owner_id: string | null; business_description: string | null; purchase_date: string | null; warranty_expiry: string | null; overdue_service_date: string | null; collection_method_note: string | null; note: string }
export interface MetadataAsset extends Asset { availability: string; sync_state: string }
export interface MetadataSnapshot { asset: MetadataAsset; metadata: MetadataInput | null; metadata_version: number | null }
export interface MetadataCurrent extends MetadataSnapshot { recorded_by: string | null; recorded_at: string | null; can_edit: boolean; edit_blockers: string[]; scope: 'INTERNAL_MANUAL_DRAFT'; verification_status: 'SELF_REPORTED' | null; source: 'postgresql'; monitoring_applied: false; cmdb_applied: false; management_ip_change_status: 'CONFIG_PENDING' }
export interface MetadataRevision { asset_version: number; previous_metadata_version: number | null; actor_id: string; recorded_at: string; before: MetadataSnapshot; metadata: MetadataInput; management_ip: string; verification_status: 'SELF_REPORTED'; monitoring_applied: false; cmdb_applied: false }
export interface MetadataHistory { items: MetadataRevision[]; has_more: boolean; next_before_version: number | null; source: 'postgresql'; scope: 'INTERNAL_MANUAL_DRAFT'; page_size_limit: 100 }
export interface MetadataReferences { sites: { id: string; label: string }[]; owners: { id: string; label: string }[]; sites_truncated: boolean; owners_truncated: boolean; query: string; limit_per_kind: 100; scope: 'CURRENT_TENANT_ACTIVE_REFERENCES'; source: 'postgresql'; reference_validation: 'RECHECKED_ON_WRITE'; default_owner_id: null }
export interface MetadataContext { tenantId: string; assetId: string; actorId: string; permissionVersion: number; canWrite: boolean }
interface Attempt { key: string; payload: string; baseline: MetadataCurrent; state: 'uncertain' | 'accepted' | 'rejected' }
export interface MetadataState { current: MetadataCurrent | null; history: MetadataHistory | null; references: MetadataReferences | null; receipt: MetadataCurrent | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const types = ['router', 'switch', 'firewall', 'ap', 'wireless_controller', 'other']
const inputKeys = ['name', 'type', 'site_id', 'brand', 'model', 'serial_number', 'location', 'owner_id', 'business_description', 'purchase_date', 'warranty_expiry', 'overdue_service_date', 'collection_method_note', 'note']
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const invalid = () => new ApiError(200, 'INVALID_ASSET_METADATA_RESPONSE', '资料范围、来源、版本或回读事实异常，当前显示已清除', '—')
const inputInvalid = () => new Error('请明确填写名称、类型、站点与维护备注；可选未知项留空，日期使用有效的YYYY-MM-DD')
function text(value: unknown, max: number, multiline = false): value is string {
  return typeof value === 'string' && Boolean(value.replace(/[\s\u0085]+/gu, '')) && Array.from(value).length <= max && !(multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(value) && !Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))
}
export function validMetadataDate(value: unknown): boolean {
  if (value === null) return true
  if (typeof value !== 'string' || !/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const millis = Date.parse(value + 'T00:00:00.000Z')
  return Number.isFinite(millis) && new Date(millis).toISOString().slice(0, 10) === value
}
export function validateMetadataInput(value: unknown): asserts value is MetadataInput {
  if (!exact(value, inputKeys) || !text(value.name, 100) || typeof value.type !== 'string' || !types.includes(value.type) || !text(value.site_id, 64) || !text(value.note, 2000, true)) throw inputInvalid()
  for (const [key, max] of Object.entries({ brand: 100, model: 100, serial_number: 128, location: 200, business_description: 200, collection_method_note: 100 })) if (value[key] !== null && !text(value[key], max)) throw inputInvalid()
  if (value.owner_id !== null && (typeof value.owner_id !== 'string' || !uuid.test(value.owner_id))) throw inputInvalid()
  if (['purchase_date', 'warranty_expiry', 'overdue_service_date'].some(key => !validMetadataDate(value[key]))) throw inputInvalid()
}
const timestamp = (value: unknown) => typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
function readSnapshot(value: unknown, context: MetadataContext): MetadataSnapshot {
  if (!exact(value, ['asset', 'metadata', 'metadata_version']) || !object(value.asset)) throw invalid()
  const a = value.asset
  if (a.id !== context.assetId || a.tenant_id !== context.tenantId || !version(a.version) || !text(a.name, 100) || typeof a.type !== 'string' || !types.includes(a.type) || !text(a.site_id, 64) || !text(a.management_ip, 45) || !timestamp(a.created_at) || !timestamp(a.updated_at) || typeof a.source !== 'string' || typeof a.status !== 'string' || typeof a.lifecycle_status !== 'string' || typeof a.availability !== 'string' || typeof a.sync_state !== 'string' || !(a.sampled_at === null || timestamp(a.sampled_at))) throw invalid()
  if (value.metadata === null) { if (value.metadata_version !== null) throw invalid() }
  else { validateMetadataInput(value.metadata); if (!version(value.metadata_version) || value.metadata_version < 2 || value.metadata_version > a.version) throw invalid() }
  return clone(value) as unknown as MetadataSnapshot
}
export function readMetadataCurrent(value: unknown, context: MetadataContext): MetadataCurrent {
  try {
    if (!exact(value, ['asset', 'metadata', 'metadata_version', 'recorded_by', 'recorded_at', 'can_edit', 'edit_blockers', 'scope', 'verification_status', 'source', 'monitoring_applied', 'cmdb_applied', 'management_ip_change_status'])) throw invalid()
    const snapshot = readSnapshot({ asset: value.asset, metadata: value.metadata, metadata_version: value.metadata_version }, context)
    if (value.scope !== 'INTERNAL_MANUAL_DRAFT' || value.source !== 'postgresql' || value.monitoring_applied !== false || value.cmdb_applied !== false || value.management_ip_change_status !== 'CONFIG_PENDING' || typeof value.can_edit !== 'boolean' || !Array.isArray(value.edit_blockers) || new Set(value.edit_blockers).size !== value.edit_blockers.length || value.edit_blockers.some(b => !['MANUAL_DRAFT_REQUIRED', 'MONITORING_STATE_NOT_PRISTINE', 'MANUAL_METADATA_CORE_DRIFT'].includes(String(b))) || value.can_edit !== (value.edit_blockers.length === 0)) throw invalid()
    if (snapshot.metadata === null ? value.recorded_by !== null || value.recorded_at !== null || value.verification_status !== null : typeof value.recorded_by !== 'string' || !uuid.test(value.recorded_by) || !timestamp(value.recorded_at) || value.verification_status !== 'SELF_REPORTED') throw invalid()
    const a = snapshot.asset
    if ((a.source !== 'manual' || a.lifecycle_status !== 'DRAFT') !== value.edit_blockers.includes('MANUAL_DRAFT_REQUIRED') || (a.status !== 'unknown' || a.sampled_at !== null || a.availability !== 'UNKNOWN' || a.sync_state !== 'PENDING_ONBOARDING') !== value.edit_blockers.includes('MONITORING_STATE_NOT_PRISTINE')) throw invalid()
    if (snapshot.metadata && ['name', 'type', 'site_id'].some(k => snapshot.metadata![k as keyof MetadataInput] !== a[k as keyof MetadataAsset]) && !value.edit_blockers.includes('MANUAL_METADATA_CORE_DRIFT')) throw invalid()
    return clone(value) as unknown as MetadataCurrent
  } catch { throw invalid() }
}
export function readMetadataHistory(value: unknown, context: MetadataContext, current: MetadataCurrent, before: number | null): MetadataHistory {
  if (!exact(value, ['items', 'has_more', 'next_before_version', 'source', 'scope', 'page_size_limit']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'INTERNAL_MANUAL_DRAFT' || value.page_size_limit !== 100) throw invalid()
  let prior = before ?? current.asset.version + 1, priorRevision: MetadataRevision | null = null
  for (const item of value.items) {
    if (!exact(item, ['asset_version', 'previous_metadata_version', 'actor_id', 'recorded_at', 'before', 'metadata', 'management_ip', 'verification_status', 'monitoring_applied', 'cmdb_applied']) || !version(item.asset_version) || item.asset_version < 2 || item.asset_version >= prior || typeof item.actor_id !== 'string' || !uuid.test(item.actor_id) || !timestamp(item.recorded_at) || item.verification_status !== 'SELF_REPORTED' || item.monitoring_applied !== false || item.cmdb_applied !== false) throw invalid()
    const snapshot = readSnapshot(item.before, context); validateMetadataInput(item.metadata)
    if (snapshot.asset.version !== item.asset_version - 1 || snapshot.metadata_version !== item.previous_metadata_version || snapshot.asset.management_ip !== item.management_ip) throw invalid()
    if (snapshot.asset.source !== 'manual' || snapshot.asset.lifecycle_status !== 'DRAFT' || snapshot.asset.status !== 'unknown' || snapshot.asset.sampled_at !== null || snapshot.asset.availability !== 'UNKNOWN' || snapshot.asset.sync_state !== 'PENDING_ONBOARDING') throw invalid()
    if (priorRevision && (priorRevision.previous_metadata_version !== item.asset_version || !priorRevision.before.metadata || inputKeys.some(key => priorRevision!.before.metadata![key as keyof MetadataInput] !== (item.metadata as MetadataInput)[key as keyof MetadataInput]))) throw invalid()
    prior = item.asset_version; priorRevision = item as unknown as MetadataRevision
  }
  if (!before && (current.metadata_version === null ? value.items.length !== 0 : !value.items.length || value.items[0].asset_version !== current.metadata_version)) throw invalid()
  const first = value.items[0] as MetadataRevision | undefined
  if (!before && current.metadata && first && inputKeys.some(key => first.metadata[key as keyof MetadataInput] !== current.metadata![key as keyof MetadataInput])) throw invalid()
  if (!value.has_more && priorRevision && priorRevision.previous_metadata_version !== null) throw invalid()
  if (value.has_more ? value.items.length !== 15 || value.next_before_version !== prior : value.next_before_version !== null) throw invalid()
  return clone(value) as unknown as MetadataHistory
}
function readReferences(value: unknown, query: string): MetadataReferences {
  if (!exact(value, ['sites', 'owners', 'sites_truncated', 'owners_truncated', 'query', 'limit_per_kind', 'scope', 'source', 'reference_validation', 'default_owner_id']) || !Array.isArray(value.sites) || !Array.isArray(value.owners) || typeof value.sites_truncated !== 'boolean' || typeof value.owners_truncated !== 'boolean' || value.query !== query || value.limit_per_kind !== 100 || value.scope !== 'CURRENT_TENANT_ACTIVE_REFERENCES' || value.source !== 'postgresql' || value.reference_validation !== 'RECHECKED_ON_WRITE' || value.default_owner_id !== null) throw invalid()
  for (const kind of ['sites', 'owners'] as const) { const list = value[kind] as unknown[]; if (list.length > 100 || value[kind + '_truncated'] && list.length !== 100 || new Set(list.map(v => object(v) ? v.id : null)).size !== list.length) throw invalid(); for (const item of list) if (!exact(item, ['id', 'label']) || !text(item.id, kind === 'sites' ? 64 : 36) || kind === 'owners' && !uuid.test(item.id) || !text(item.label, 100)) throw invalid() }
  return clone(value) as unknown as MetadataReferences
}
export const emptyMetadataState = (): MetadataState => ({ current: null, history: null, references: null, receipt: null, attempt: null, busy: null, error: null })
export function createMetadataWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: MetadataState) => void, newKey: () => string = () => crypto.randomUUID()) {
  let context: MetadataContext | null = null, state = emptyMetadataState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed({ ...state }), path = () => `/assets/${context!.assetId}/manual-metadata`, unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: MetadataContext | null) { controller?.abort(); ++epoch; const valid = next && [next.tenantId, next.assetId, next.actorId].every(id => uuid.test(id)) && version(next.permissionVersion) && typeof next.canWrite === 'boolean'; context = valid ? { ...next! } : null; state = emptyMetadataState(); if (next && !valid) state.error = invalid(); emit() }
  async function refresh(moreHistory = false) {
    if (!context || state.busy === 'write' || moreHistory && (state.busy || !state.history?.has_more)) return false
    const scope = { ...context }, previous = state.current, before = moreHistory ? state.history!.next_before_version : null, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    state = { ...state, current: null, history: null, busy: 'read', error: null }; emit()
    try {
      const current = readMetadataCurrent(await client.request<unknown>(path(), { signal: abort.signal }), scope)
      if (turn !== epoch || abort.signal.aborted) return false
      if (previous && current.asset.version < previous.asset.version || state.receipt && current.asset.version < state.receipt.asset.version) throw invalid()
      for (const known of [previous, state.receipt]) if (known && current.asset.version === known.asset.version && (current.metadata_version !== known.metadata_version || current.asset.management_ip !== known.asset.management_ip || current.metadata && known.metadata && inputKeys.some(key => current.metadata![key as keyof MetadataInput] !== known.metadata![key as keyof MetadataInput]))) throw invalid()
      const boundary = moreHistory && previous?.asset.version === current.asset.version ? before : null
      const history = readMetadataHistory(await client.request<unknown>(path() + '/history?page_size=15' + (boundary ? '&before_version=' + boundary : ''), { signal: abort.signal }), scope, current, boundary)
      if (turn !== epoch || abort.signal.aborted) return false
      state = { ...state, current, history }; return true
    } catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function references(query = '') {
    if (!context || state.busy || Array.from(query).length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(query)) return false
    const turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, references: null, busy: 'read', error: null }; emit()
    try { const refs = readReferences(await client.request<unknown>(path() + '/references?q=' + encodeURIComponent(query), { signal: abort.signal }), query); if (turn !== epoch || abort.signal.aborted) return false; state = { ...state, references: refs }; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!context?.canWrite || state.busy) return false
    const scope = { ...context }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as MetadataInput & { expected_version: number }
      const receipt = readMetadataCurrent(await client.request<unknown>(path(), { method: 'PATCH', body, idempotencyKey: attempt.key, signal: abort.signal }), scope)
      if (turn !== epoch || abort.signal.aborted) return false
      if (receipt.asset.version !== body.expected_version + 1 || receipt.metadata_version !== receipt.asset.version || !receipt.metadata || inputKeys.some(key => receipt.metadata![key as keyof MetadataInput] !== body[key as keyof MetadataInput]) || receipt.recorded_by !== scope.actorId || receipt.asset.management_ip !== attempt.baseline.asset.management_ip || receipt.asset.created_at !== attempt.baseline.asset.created_at || !receipt.can_edit) throw invalid()
      state = { ...state, receipt, current: receipt, history: null, busy: null, attempt: { ...attempt, state: 'accepted' } }; emit(); await refresh(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, current: null, history: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(input: MetadataInput, expectedVersion: number) {
    if (!context?.canWrite || state.busy || unresolved() || !state.current?.can_edit || state.current.asset.version !== expectedVersion) return false
    validateMetadataInput(input); if (expectedVersion >= Number.MAX_SAFE_INTEGER) throw inputInvalid()
    return send({ key: newKey(), payload: JSON.stringify({ expected_version: expectedVersion, ...clone(input) }), baseline: clone(state.current), state: 'uncertain' })
  }
  emit(); return { reset, refresh, references, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, dispose: () => reset(null), state: () => state }
}
