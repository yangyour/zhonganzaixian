// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { nextTick, shallowRef, watch } from 'vue'
import { ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import { useCmdbImportExecution, isCmdbExecutionSource, readCmdbImportReceipt } from '../src/utils/cmdbImportExecution'
import { cmdbSource, actor } from './fixtures/cmdbSource'

const assetId = '64000000-0000-4000-8000-000000000009'
export function executionReceipt(source = cmdbSource()) {
  return { tenant_id: source.tenant_id, source_id: source.id, source_version: source.version,
    artifact_id: source.parsed_artifact!.id, parse_hash: source.parsed_artifact!.parse_hash,
    status: 'IMPORTED_DRAFTS', imported_count: 1,
    assets: [{ record: 2, source_row: 2, asset_id: assetId, asset_version: 3 }],
    quota: { limit: 20, before: 7, after: 8 }, extended_attributes: 'RETAINED_IN_IMMUTABLE_SOURCE',
    monitoring_started: false, production_approved: false,
    configuration_releases: Object.fromEntries(['asset.import_steps', 'asset.ip_scope', 'tenant.contract_device_quota']
      .map(key => [key, { id: assetId, version: 1 }])) }
}
const source = shallowRef(cmdbSource())
const absent = () => ({ tenant_id: source.value.tenant_id, source_id: source.value.id, status: 'NO_COMMITTED_RECEIPT', receipt: null, absence_proves_failure: false })
const completed = () => ({ ...absent(), status: 'COMPLETED', receipt: executionReceipt(source.value) })
const flows: ReturnType<typeof useCmdbImportExecution>[] = []
const request = vi.fn()
function open(storage: Storage = sessionStorage) {
  const flow = useCmdbImportExecution(() => source.value, { client: { request } as any, storage, newKey: () => 'original-key' })
  flows.push(flow); return flow
}
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
beforeEach(() => {
  source.value = cmdbSource(); sessionStorage.clear(); request.mockReset().mockResolvedValue(absent())
  switchingTenant.value = false; contextGeneration.value = 0
  session.value = { user: { id: actor, role: 'msp', display_name: 'Synthetic' }, tenant: { id: source.value.tenant_id, name: 'Synthetic' },
    allowed_tenants: [], capabilities: ['assets:write','assets:read'], permission_version: 7, account_version: 1,
    csrf_token: 'synthetic-secret-not-persisted', mode: 'remote_test', identity_source: 'project_local' }
})
afterEach(() => { flows.splice(0).forEach(f => f.dispose()); session.value = null; sessionStorage.clear(); vi.restoreAllMocks() })

it('recognizes exactly the 33 reviewed columns and not a 15-column mapping', () => {
  expect(isCmdbExecutionSource(source.value)).toBe(true)
  source.value.parsed_artifact!.columns.pop(); expect(isCmdbExecutionSource(source.value)).toBe(false)
})
it('requires a current owner-authorized read before a deliberate submit', async () => {
  const flow = open(); expect(flow.canSubmit.value).toBe(false); await settle(); expect(flow.canSubmit.value).toBe(true)
  request.mockResolvedValueOnce(executionReceipt()); expect(await flow.submit()).toBe(true)
  expect(request.mock.calls[1]![0]).toBe(`/assets/import-sources/${source.value.id}/cmdb-imports`)
  expect(request.mock.calls[1]![1]).toMatchObject({ method: 'POST', idempotencyKey: 'original-key', body: { expected_version: 3, source_parse_hash: source.value.parsed_artifact!.parse_hash } })
  expect(flow.receipt.value!.assets[0]!.asset_id).toBe(assetId); expect(flow.unresolved.value).toBe(false)
  expect(flow.canSubmit.value).toBe(false); expect(sessionStorage.length).toBe(0)
})
it('network ambiguity survives refresh and component recreation with the exact original request', async () => {
  const flow = open(); await settle(); request.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'unit', 'unit'))
  await flow.submit(); expect(flow.unresolved.value).toBe(true)
  const saved = sessionStorage.getItem(sessionStorage.key(0)!)!; expect(saved).not.toContain('synthetic-secret'); expect(saved).not.toContain('cells')
  flow.dispose(); const restored = open(); await settle(); expect(restored.attempt.value?.key).toBe('original-key')
  expect(restored.unresolved.value).toBe(true); expect(restored.lookup.value).toBe('NO_COMMITTED_RECEIPT')
  request.mockResolvedValueOnce(executionReceipt()); expect(await restored.retry()).toBe(true)
  expect(request.mock.calls[3]![1].body).toEqual(request.mock.calls[1]![1].body)
  expect(request.mock.calls[3]![1].idempotencyKey).toBe(request.mock.calls[1]![1].idempotencyKey)
})
it('an absent committed receipt never proves an uncertain request failed', async () => {
  const flow = open(); await settle(); request.mockRejectedValueOnce(new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'unit', 'unit'))
  await flow.submit(); request.mockResolvedValueOnce(absent()); await flow.refresh()
  expect(flow.unresolved.value).toBe(true); expect(flow.canSubmit.value).toBe(false)
})
it('GET recovery confirms the original committed receipt without another write', async () => {
  const flow = open(); await settle(); request.mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', 'unit', 'unit'))
  await flow.submit(); request.mockResolvedValueOnce(completed()); await flow.refresh()
  expect(flow.receipt.value).not.toBeNull(); expect(flow.attempt.value?.state).toBe('COMPLETE'); expect(sessionStorage.length).toBe(0)
  expect(request.mock.calls.filter(([, opts]) => opts.method === 'POST')).toHaveLength(1)
})
it('stopping a pending submit keeps uncertainty and suppresses a late success', async () => {
  const flow = open(); await settle(); let resolve!: (v: unknown) => void
  request.mockImplementationOnce(() => new Promise(r => { resolve = r }))
  const pending = flow.submit(); flow.stopWaiting(); expect(flow.busy.value).toBeNull(); expect(flow.unresolved.value).toBe(true)
  resolve(executionReceipt()); await pending; expect(flow.receipt.value).toBeNull(); expect(sessionStorage.length).toBe(1)
})
it('a rejection on an uncertain retry does not replace the original outcome with failure', async () => {
  const flow = open(); await settle(); request.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'unit', 'unit'))
  await flow.submit(); request.mockRejectedValueOnce(new ApiError(403, 'ACTION_FORBIDDEN', 'unit', 'unit')); await flow.retry()
  expect(flow.attempt.value?.state).toBe('UNCERTAIN'); expect(sessionStorage.length).toBe(1)
})
it('a first definite rejection requires explicit re-reading before preparing a new request', async () => {
  const flow = open(); await settle(); request.mockRejectedValueOnce(new ApiError(422, 'CMDB_IMPORT_CONFIGURATION_REQUIRED', 'unit', 'unit'))
  await flow.submit(); expect(flow.attempt.value?.state).toBe('REJECTED'); expect(flow.canSubmit.value).toBe(false)
  expect(sessionStorage.length).toBe(0); await flow.prepareAgain(); expect(flow.canSubmit.value).toBe(true)
})
it('failed reads remove an old negative observation from admission', async () => {
  const flow = open(); await settle(); expect(flow.canSubmit.value).toBe(true)
  request.mockRejectedValueOnce(new Error('offline')); await flow.refresh(); expect(flow.canSubmit.value).toBe(false)
})
it('storage failure prevents dispatch instead of losing the original key', async () => {
  const storage = { getItem: () => null, setItem: () => { throw new Error('disabled') }, removeItem: vi.fn() } as unknown as Storage
  const flow = open(storage); await settle(); await flow.submit()
  expect(request).toHaveBeenCalledTimes(1); expect(flow.storageError.value).not.toBeNull(); expect(flow.unresolved.value).toBe(false)
})
it('corrupted recovery records cannot become new writes or override current scope', async () => {
  sessionStorage.setItem(`mop.cmdb-import.v1:${actor}:${source.value.tenant_id}:${source.value.id}`, '{"actorId":"forged"}')
  const flow = open(); await settle(); expect(flow.storageError.value).not.toBeNull(); await flow.submit()
  expect(request).toHaveBeenCalledTimes(1)
})
it('revocation and tenant changes clear current receipt and block late writes', async () => {
  request.mockResolvedValueOnce(completed()); const flow = open(); await settle(); expect(flow.receipt.value).not.toBeNull()
  session.value = { ...session.value!, capabilities: [] }; expect(flow.receipt.value).toBeNull(); expect(flow.permitted.value).toBe(false)
  await flow.submit(); expect(request).toHaveBeenCalledTimes(1)
})
it('same-scope session refresh preserves a pending original command', async () => {
  const flow = open(); await settle(); request.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'unit', 'unit')); await flow.submit()
  session.value = JSON.parse(JSON.stringify(session.value)); await settle(); expect(flow.attempt.value?.key).toBe('original-key')
  expect(request).toHaveBeenCalledTimes(2)
})
it('synchronous scope invalidation before dispatch prevents the stale POST', async () => {
  const flow = open(); await settle()
  const stop = watch(flow.busy, v => { if (v === 'write') session.value = { ...session.value!, capabilities: [] } }, { flush: 'sync' })
  try { await flow.submit(); expect(request).toHaveBeenCalledTimes(1) } finally { stop() }
})
it('receipt mismatch never clears an uncertain command', async () => {
  const flow = open(); await settle(); request.mockResolvedValueOnce({ ...executionReceipt(), source_version: 7 }); await flow.submit()
  expect(flow.receipt.value).toBeNull(); expect(flow.unresolved.value).toBe(true); expect(sessionStorage.length).toBe(1)
})
it('validates scope, count, quota math, unique rows, releases and non-activation claims', () => {
  const value = executionReceipt()
  for (const bad of [{ ...value, tenant_id: assetId }, { ...value, imported_count: 2 }, { ...value, monitoring_started: true },
    { ...value, quota: { limit: 20, before: 7, after: 9 } }, { ...value, configuration_releases: {} },
    { ...value, assets: [{ ...value.assets[0], asset_id: '/other' }] }]) {
    expect(() => readCmdbImportReceipt(bad, source.value.tenant_id, source.value.id)).toThrow()
  }
})
