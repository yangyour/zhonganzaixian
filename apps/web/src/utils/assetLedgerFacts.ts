import { computed, readonly, shallowRef, watch } from 'vue'
import { api, ApiError, isCancelled, type ApiClient } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { isUtcInstant } from './utcInstant'

export interface AssetLedgerFact {
  asset_id: string; asset_version: number; site_id: string; site_name: string
  metadata_version: number | null; metadata_recorded_at: string | null
  metadata_source: 'NOT_RECORDED' | 'MANUAL_DECLARATION'; business_description: string | null
  owner: { id: string; display_name: string; status: 'ACTIVE' | 'INACTIVE' } | null
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => object(v) && Object.keys(v).sort().join() === [...keys].sort().join()
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.trim().length > 0 && [...v].length <= max && !/[\u0000-\u001f\u007f]/.test(v)
const invalid = () => new ApiError(200, 'INVALID_ASSET_LEDGER_FACTS', '台账资料未通过当前租户、资产版本或来源核验，未展示替代值', '—')

export function readAssetLedgerFacts(value: unknown, tenant: string, requested: readonly Asset[]) {
  if (!exact(value, ['tenant_id','observed_at','items','source','scope','business_relationship_verified','proxy_verified'])
      || value.tenant_id !== tenant || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS'
      || value.business_relationship_verified !== false || value.proxy_verified !== false
      || typeof value.observed_at !== 'string' || !isUtcInstant(value.observed_at) || !Array.isArray(value.items)
      || value.items.length !== requested.length || requested.length < 1 || requested.length > 100) throw invalid()
  const expected = new Map(requested.map(a => [a.id, a])), items: Record<string, AssetLedgerFact> = {}, stale: string[] = []
  if (expected.size !== requested.length) throw invalid()
  for (const item of value.items) {
    if (!exact(item, ['asset_id','asset_version','site_id','site_name','metadata_version','metadata_recorded_at','metadata_source','business_description','owner'])
        || typeof item.asset_id !== 'string' || !uuid.test(item.asset_id) || !expected.has(item.asset_id)
        || Object.hasOwn(items, item.asset_id) || stale.includes(item.asset_id) || !positive(item.asset_version)
        || !text(item.site_id, 64) || !text(item.site_name, 100)) throw invalid()
    if (item.metadata_source === 'NOT_RECORDED') {
      if (item.metadata_version !== null || item.metadata_recorded_at !== null || item.business_description !== null || item.owner !== null) throw invalid()
    } else if (item.metadata_source === 'MANUAL_DECLARATION') {
      if (!positive(item.metadata_version) || item.metadata_version < 2 || item.metadata_version > item.asset_version
          || typeof item.metadata_recorded_at !== 'string' || !isUtcInstant(item.metadata_recorded_at)
          || item.business_description !== null && !text(item.business_description, 200)) throw invalid()
      if (item.owner !== null && (!exact(item.owner, ['id','display_name','status']) || typeof item.owner.id !== 'string'
          || !uuid.test(item.owner.id) || !text(item.owner.display_name, 100) || !['ACTIVE','INACTIVE'].includes(String(item.owner.status)))) throw invalid()
    } else throw invalid()
    const asset = expected.get(item.asset_id)!
    if (asset.tenant_id !== tenant) throw invalid()
    if (asset.version !== item.asset_version) { stale.push(item.asset_id); continue }
    if (item.metadata_source === 'MANUAL_DECLARATION' && asset.source !== 'manual') throw invalid()
    if (asset.site_id !== item.site_id) throw invalid()
    items[item.asset_id] = JSON.parse(JSON.stringify(item)) as AssetLedgerFact
  }
  return { items, stale, observedAt: value.observed_at }
}

/** A single bounded read for the displayed assets, with version binding and explicit failure/refresh. */
export function useAssetLedgerFacts(assets: () => readonly Asset[], enabled: () => boolean,
    client: Pick<ApiClient, 'request'> = api) {
  const items = shallowRef<Record<string, AssetLedgerFact>>({}), stale = shallowRef<string[]>([])
  const observedAt = shallowRef<string | null>(null), error = shallowRef<unknown>(null), busy = shallowRef(false), cancelled = shallowRef(false)
  const permitted = computed(() => enabled() && session.value?.mode === 'remote_test' && can('assets:read') && !switchingTenant.value)
  let epoch = 0, disposed = false, abort: AbortController | undefined
  function clear() { ++epoch; abort?.abort(); abort = undefined; items.value = {}; stale.value = []; observedAt.value = null; error.value = null; busy.value = false; cancelled.value = false }
  async function refresh() {
    if (disposed || !permitted.value || busy.value || !assets().length) return false
    const selected = assets().map(a => ({ ...a })), tenant = session.value!.tenant.id
    if (selected.length > 100 || selected.some(a => !uuid.test(a.id) || a.tenant_id !== tenant || !positive(a.version))) { clear(); error.value = invalid(); return false }
    clear(); const turn = epoch, controller = new AbortController(); abort = controller; busy.value = true
    try {
      if (turn !== epoch || disposed || controller.signal.aborted || !permitted.value) return false
      const value = await client.request<unknown>('/assets/ledger-facts?ids=' + selected.map(a => a.id).join(','), { signal: controller.signal })
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      const verified = readAssetLedgerFacts(value, tenant, selected)
      items.value = verified.items; stale.value = verified.stale; observedAt.value = verified.observedAt; return true
    } catch (cause) { if (turn === epoch && !disposed && !isCancelled(cause)) error.value = cause; return false }
    finally { if (turn === epoch && !disposed) { busy.value = false; abort = undefined } }
  }
  function cancel() { clear(); cancelled.value = true }
  const stop = watch(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role,
    session.value?.permission_version, session.value?.account_version, session.value?.capabilities, permitted.value,
    assets().map(a => [a.id, a.version, a.site_id, a.tenant_id, a.source])]), () => { clear(); void refresh() }, { immediate: true, flush: 'sync' })
  function dispose() { disposed = true; stop(); clear() }
  function find(asset: Asset) {
    const value = items.value[asset.id]
    return permitted.value && asset.tenant_id === session.value?.tenant.id && value?.asset_version === asset.version && value.site_id === asset.site_id ? value : null
  }
  return { items: readonly(items), stale: readonly(stale), observedAt: readonly(observedAt), cancelled: readonly(cancelled), error, busy: readonly(busy), refresh, cancel, dispose, find }
}
