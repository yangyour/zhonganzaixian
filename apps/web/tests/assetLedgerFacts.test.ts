// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { nextTick, shallowRef, watch } from 'vue'
import type { Asset } from '../src/api/types'
import { ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import { readAssetLedgerFacts, useAssetLedgerFacts, type AssetLedgerFact } from '../src/utils/assetLedgerFacts'
import { assetLedgerCell } from '../src/utils/assetLedgerColumns'

const tenant = '21000000-0000-4000-8000-000000000001', actor = '22000000-0000-4000-8000-000000000001', id = '23000000-0000-4000-8000-000000000001'
const asset: Asset = { id, tenant_id: tenant, name: 'Synthetic asset', type: 'switch', management_ip: '192.0.2.1', site_id: 'site-a',
  status: 'unknown', lifecycle_status: 'DRAFT', source: 'manual', sampled_at: null, unknown_reason: 'NOT_CONNECTED',
  version: 3, created_at: '2026-10-23T00:00:00Z', updated_at: '2026-10-23T01:00:00Z', availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING' }
const facts = (): AssetLedgerFact => ({ asset_id: id, asset_version: 3, site_id: 'site-a', site_name: 'Declared site', metadata_version: 2,
  metadata_recorded_at: '2026-10-23T01:00:00Z', metadata_source: 'MANUAL_DECLARATION', business_description: 'Declared business',
  owner: { id: actor, display_name: 'Declared owner', status: 'ACTIVE' } })
const response = () => ({ tenant_id: tenant, observed_at: '2026-10-23T02:00:00Z', items: [facts()], source: 'postgresql',
  scope: 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS', business_relationship_verified: false, proxy_verified: false })
const assets = shallowRef<Asset[]>([]), request = vi.fn(), flows: ReturnType<typeof useAssetLedgerFacts>[] = []
const open = () => { const flow = useAssetLedgerFacts(() => assets.value, () => true, { request } as any); flows.push(flow); return flow }
async function settle() { for (let i = 0; i < 7; i++) { await Promise.resolve(); await nextTick() } }
beforeEach(() => {
  assets.value = [{ ...asset }]; contextGeneration.value = 0; switchingTenant.value = false
  session.value = { user: { id: actor, display_name: 'Synthetic', role: 'viewer' }, tenant: { id: tenant, name: 'Synthetic' }, allowed_tenants: [],
    capabilities: ['assets:read'], permission_version: 7, account_version: 1, csrf_token: 'unit', mode: 'remote_test', identity_source: 'project_local' }
  request.mockReset().mockResolvedValue(response())
})
afterEach(() => { flows.splice(0).forEach(f => f.dispose()); session.value = null; vi.restoreAllMocks() })

it('projects only verified source/version-bound fields and leaves monitoring/proxy unknown', () => {
  const read = readAssetLedgerFacts(response(), tenant, [asset]).items[id]!
  expect(assetLedgerCell(asset, 'site', read).text).toBe('Declared site')
  expect(assetLedgerCell(asset, 'site', read).detail).toContain('Proxy：—')
  expect(assetLedgerCell(asset, 'business', read).text).toBe('Declared business')
  expect(assetLedgerCell(asset, 'business', read).detail).toContain('未核验业务系统关联')
  expect(assetLedgerCell(asset, 'owner', read).text).toBe('Declared owner')
  expect(assetLedgerCell(asset, 'owner', read).detail).toContain('当前活动成员')
  for (const key of ['cpu','memory','problems'] as const) expect(assetLedgerCell(asset, key, read).text).toBe('—')
})
it('inactive ownership is explicit and absent metadata never becomes an invented owner', () => {
  const data = response(); data.items[0]!.owner!.status = 'INACTIVE'
  expect(assetLedgerCell(asset, 'owner', readAssetLedgerFacts(data, tenant, [asset]).items[id]).detail).toContain('已停用')
  data.items[0] = { ...facts(), metadata_source: 'NOT_RECORDED', metadata_version: null, metadata_recorded_at: null, business_description: null, owner: null }
  expect(assetLedgerCell(asset, 'owner', readAssetLedgerFacts(data, tenant, [asset]).items[id]).detail).toBe('当前未登记负责人')
})
it('rejects foreign, additional private fields, unverified flags and corrupt metadata', () => {
  for (const data of [{ ...response(), tenant_id: actor }, { ...response(), proxy_verified: true }, { ...response(), note: 'private' },
    { ...response(), items: [{ ...facts(), note: 'private' }] }, { ...response(), items: [{ ...facts(), metadata_version: 4 }] },
    { ...response(), items: [{ ...facts(), metadata_source: 'NOT_RECORDED' }] }, { ...response(), items: [{ ...facts(), owner: { ...facts().owner, email: 'private' } }] }]) {
    expect(() => readAssetLedgerFacts(data, tenant, [asset])).toThrow()
  }
})
it('requires metadata timestamps no later than the observed snapshot with nanosecond precision', () => {
  const same = response(); same.observed_at = '2026-10-23T02:00:00.123456789Z'; same.items[0]!.metadata_recorded_at = same.observed_at
  expect(readAssetLedgerFacts(same, tenant, [asset]).items[id]?.metadata_recorded_at).toBe(same.observed_at)
  for (const recorded of ['2026-10-23T02:00:00.123456790Z', '2026-10-23T02:00:01Z', '2026-02-30T00:00:00Z']) {
    expect(() => readAssetLedgerFacts({ ...same, items: [{ ...facts(), metadata_recorded_at: recorded }] }, tenant, [asset])).toThrow()
  }
})
it('validates the entire batch before exposing any valid row beside future metadata', () => {
  const second = '23000000-0000-4000-8000-000000000002'
  const data = { ...response(), items: [facts(), { ...facts(), asset_id: second, metadata_recorded_at: '2026-10-23T02:00:00.000000001Z' }] }
  expect(() => readAssetLedgerFacts(data, tenant, [asset, { ...asset, id: second }])).toThrow()
})
it.each([0, -1, 1.5, 9007199254740991])('rejects unsafe requested asset version %s', version => {
  expect(() => readAssetLedgerFacts(response(), tenant, [{ ...asset, version }])).toThrow()
})
it('isolates changed asset versions instead of attaching newer facts to an old row', () => {
  const value = response(); value.items[0]!.asset_version = 4
  const read = readAssetLedgerFacts(value, tenant, [asset]); expect(read.stale).toEqual([id]); expect(read.items).toEqual({})
  expect(assetLedgerCell(asset, 'owner', { ...facts(), asset_version: 4 }).text).toBe('—')
})
it('rejects unexpected IDs, duplicated rows and same-version site mismatch', () => {
  for (const value of [{ ...response(), items: [] }, { ...response(), items: [facts(), facts()] },
    { ...response(), items: [{ ...facts(), asset_id: actor }] }, { ...response(), items: [{ ...facts(), site_id: 'other-site' }] }]) {
    expect(() => readAssetLedgerFacts(value, tenant, [asset])).toThrow()
  }
  expect(() => readAssetLedgerFacts(response(), tenant, [{ ...asset, source: 'test_seed' }])).toThrow()
})
it('reads the whole displayed page in one GET without any write or internal metadata endpoint', async () => {
  const flow = open(); await settle(); expect(request).toHaveBeenCalledOnce()
  expect(request.mock.calls[0]![0]).toBe('/assets/ledger-facts?ids=' + id)
  expect(request.mock.calls[0]![1].method).toBeUndefined(); expect(flow.find(asset)?.site_name).toBe('Declared site')
})
it('binds a multi-asset response by ID and preserves missing metadata without inventing ownership', async () => {
  const second = '23000000-0000-4000-8000-000000000002'
  assets.value = [asset, { ...asset, id: second, version: 1, source: 'test_seed' }]
  request.mockResolvedValue({ ...response(), items: [
    { ...facts(), asset_id: second, asset_version: 1, metadata_source: 'NOT_RECORDED', metadata_version: null, metadata_recorded_at: null, business_description: null, owner: null }, facts(),
  ] })
  const flow = open(); await settle()
  expect(request).toHaveBeenCalledOnce()
  expect(request.mock.calls[0]![0]).toBe('/assets/ledger-facts?ids=' + id + ',' + second)
  expect(flow.find(asset)?.owner?.display_name).toBe('Declared owner')
  expect(flow.find(assets.value[1]!)?.owner).toBeNull()
  expect(flow.find({ ...asset, tenant_id: actor })).toBeNull()
})
it('empty lists issue no request and an explicit failure cannot retain old values', async () => {
  assets.value = []; const flow = open(); await settle(); expect(request).not.toHaveBeenCalled()
  assets.value = [{ ...asset }]; await settle(); expect(flow.find(asset)).not.toBeNull()
  request.mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', 'unit', 'unit')); await flow.refresh()
  expect(flow.find(asset)).toBeNull(); expect(flow.error.value).not.toBeNull()
  await flow.refresh(); expect(flow.find(asset)).not.toBeNull()
})
it('cancellation clears results and ignores a late reply, with explicit retry available', async () => {
  let resolve!: (value: unknown) => void; request.mockImplementationOnce(() => new Promise(r => { resolve = r }))
  const flow = open(); flow.cancel(); expect(flow.cancelled.value).toBe(true); resolve(response()); await settle()
  expect(flow.find(asset)).toBeNull(); await flow.refresh(); expect(flow.find(asset)).not.toBeNull(); expect(flow.cancelled.value).toBe(false)
})
it('permission changes clear visible facts and stop further requests', async () => {
  const flow = open(); await settle(); session.value = { ...session.value!, capabilities: [] }
  expect(flow.find(asset)).toBeNull(); await flow.refresh(); expect(request).toHaveBeenCalledOnce()
})
it('asset replacement does not let an older request overwrite a newer batch', async () => {
  let resolve!: (value: unknown) => void; request.mockImplementationOnce(() => new Promise(r => { resolve = r }))
  const flow = open(); assets.value = [{ ...asset, version: 4 }]
  const newer = response(); newer.items[0]!.asset_version = 4; request.mockResolvedValue(newer)
  resolve(response()); await settle(); await flow.refresh(); expect(flow.find(assets.value[0]!)?.asset_version).toBe(4)
})
it('same-scope refresh retains facts while synchronous revocation prevents stale dispatch', async () => {
  const flow = open(); await settle(); session.value = JSON.parse(JSON.stringify(session.value)); await settle(); expect(request).toHaveBeenCalledOnce()
  const stop = watch(flow.busy, busy => { if (busy) session.value = { ...session.value!, capabilities: [] } }, { flush: 'sync' })
  try { await flow.refresh(); expect(request).toHaveBeenCalledOnce() } finally { stop() }
})
it.each(['actor', 'role', 'tenant', 'permission', 'account', 'mode', 'switching', 'generation'])('aborts and discards a late batch after %s changes', async kind => {
  let resolve!: (value: unknown) => void
  request.mockImplementationOnce(() => new Promise(done => { resolve = done })).mockImplementation(() => new Promise(() => {}))
  const flow = open()
  if (kind === 'actor') session.value = { ...session.value!, user: { ...session.value!.user, id: '22000000-0000-4000-8000-000000000002' } }
  if (kind === 'role') session.value = { ...session.value!, user: { ...session.value!.user, role: 'msp' } }
  if (kind === 'tenant') session.value = { ...session.value!, tenant: { id: actor, name: 'Other synthetic' } }
  if (kind === 'permission') session.value = { ...session.value!, permission_version: 8 }
  if (kind === 'account') session.value = { ...session.value!, account_version: 2 }
  if (kind === 'mode') session.value = { ...session.value!, mode: 'local_fixture' }
  if (kind === 'switching') switchingTenant.value = true
  if (kind === 'generation') contextGeneration.value++
  expect(request.mock.calls[0]![1].signal.aborted).toBe(true)
  resolve(response()); await settle()
  expect(flow.find(asset)).toBeNull()
  expect(Object.keys(flow.items.value)).toEqual([])
})
it('does not expose an old successful batch after future metadata or stale asset versions arrive', async () => {
  const flow = open(); await settle(); expect(flow.find(asset)).not.toBeNull()
  const future = response(); future.items[0]!.metadata_recorded_at = '2026-10-23T02:00:01Z'
  request.mockResolvedValueOnce(future); expect(await flow.refresh()).toBe(false)
  expect(flow.find(asset)).toBeNull(); expect(flow.error.value).toBeTruthy()
  const stale = response(); stale.items[0]!.asset_version = 4
  request.mockResolvedValueOnce(stale); expect(await flow.refresh()).toBe(true)
  expect(flow.find(asset)).toBeNull(); expect(flow.stale.value).toEqual([id])
})
it('same-id foreign tenant replacement immediately removes public facts', async () => {
  const flow = open(); await settle(); expect(flow.find(asset)).not.toBeNull()
  assets.value = [{ ...asset, tenant_id: actor }]
  await settle(); expect(flow.find(assets.value[0]!)).toBeNull(); expect(flow.items.value).toEqual({})
  expect(flow.error.value).not.toBeNull()
})
