import { computed, readonly, shallowRef, watch, type DeepReadonly } from 'vue'
import { api, ApiError, isCancelled, type ApiClient } from '../api/client'
import type { AssetImportSource } from '../api/assetImportTypes'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createRequestId } from './browserCrypto'
import { CMDB_IMPORT_COLUMNS } from './cmdbImportPreflight'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const sha = /^[0-9a-f]{64}$/
const safe = (v: unknown, min = 1): v is number => Number.isSafeInteger(v) && Number(v) >= min
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const id = (v: unknown): v is string => typeof v === 'string' && uuid.test(v)
const failure = () => new ApiError(200, 'INVALID_CMDB_IMPORT_RECEIPT', '导入回执未通过来源、资产或数量核对，仍不能确认结果', '—')
export interface CmdbImportReceipt {
  tenant_id: string; source_id: string; source_version: number; artifact_id: string; parse_hash: string
  status: 'IMPORTED_DRAFTS'; imported_count: number
  assets: { record: number; source_row: number; asset_id: string; asset_version: number }[]
  configuration_releases: Record<'asset.import_steps' | 'asset.ip_scope' | 'tenant.contract_device_quota', { id: string; version: number }>
  quota: { limit: number; before: number; after: number }
  extended_attributes: 'RETAINED_IN_IMMUTABLE_SOURCE'; monitoring_started: false; production_approved: false
}
export interface CmdbImportAttempt {
  actorId: string; tenantId: string; sourceId: string; artifactId: string; key: string
  body: { expected_version: number; source_parse_hash: string }
  rows: { record: number; sourceRow: number }[]
  state: 'UNCERTAIN' | 'REJECTED' | 'COMPLETE'
}

export function isCmdbExecutionSource(source: DeepReadonly<AssetImportSource> | null): boolean {
  const artifact = source?.parsed_artifact
  return !!source && source.storage_status === 'READY' && source.parse_status === 'PARSED' && artifact?.state === 'PARSED'
    && artifact.columns.length === 33 && CMDB_IMPORT_COLUMNS.every(column => artifact.columns.some(c => c.index === column.position && c.header === column.label))
    && id(source.id) && id(artifact.id) && artifact.id === source.latest_artifact_id && artifact.source_id === source.id
    && artifact.tenant_id === source.tenant_id && artifact.source_sha256 === source.sha256 && sha.test(artifact.parse_hash)
    && safe(source.version) && artifact.rows.length > 0 && artifact.rows.length <= 500
}

export function readCmdbImportReceipt(value: unknown, tenantId: string, sourceId: string, attempt?: DeepReadonly<CmdbImportAttempt> | null): CmdbImportReceipt {
  if (!object(value) || value.tenant_id !== tenantId || value.source_id !== sourceId || value.status !== 'IMPORTED_DRAFTS'
      || !safe(value.source_version) || !id(value.artifact_id) || typeof value.parse_hash !== 'string' || !sha.test(value.parse_hash)
      || value.monitoring_started !== false || value.production_approved !== false || value.extended_attributes !== 'RETAINED_IN_IMMUTABLE_SOURCE'
      || !safe(value.imported_count) || value.imported_count > 500 || !Array.isArray(value.assets) || value.assets.length !== value.imported_count
      || !object(value.quota) || !safe(value.quota.limit, 0) || !safe(value.quota.before, 0) || !safe(value.quota.after, 0)
      || value.quota.after !== value.quota.before + value.imported_count || value.quota.after > value.quota.limit
      || !object(value.configuration_releases)) throw failure()
  const keys = ['asset.import_steps', 'asset.ip_scope', 'tenant.contract_device_quota']
  if (Object.keys(value.configuration_releases).length !== 3 || keys.some(key => {
    const release = (value.configuration_releases as Record<string, unknown>)[key]
    return !object(release) || !id(release.id) || !safe(release.version)
  })) throw failure()
  const records = new Set<number>(), assets = new Set<string>(), rows = new Set<number>()
  for (const item of value.assets) {
    if (!object(item) || !safe(item.record) || !safe(item.source_row) || !id(item.asset_id) || !safe(item.asset_version)
        || records.has(item.record) || assets.has(item.asset_id) || rows.has(item.source_row)) throw failure()
    records.add(item.record); assets.add(item.asset_id); rows.add(item.source_row)
  }
  if (attempt && (attempt.tenantId !== tenantId || attempt.sourceId !== sourceId || attempt.artifactId !== value.artifact_id
      || attempt.body.expected_version !== value.source_version || attempt.body.source_parse_hash !== value.parse_hash
      || attempt.rows.length !== value.imported_count || attempt.rows.some(row => !(value.assets as CmdbImportReceipt['assets'])
        .some(asset => asset.record === row.record && asset.source_row === row.sourceRow)))) throw failure()
  return JSON.parse(JSON.stringify(value)) as CmdbImportReceipt
}

/** Store only the original command identity, versions and row coordinates; never cells, receipts, credentials or CSRF. */
export function readCmdbImportAttempt(raw: string, actorId: string, tenantId: string, sourceId: string): CmdbImportAttempt {
  if (raw.length > 40000) throw failure()
  const v: unknown = JSON.parse(raw)
  if (!object(v) || Object.keys(v).sort().join() !== ['actorId','tenantId','sourceId','artifactId','key','body','rows','state'].sort().join()
      || v.actorId !== actorId || v.tenantId !== tenantId || v.sourceId !== sourceId || !id(v.artifactId)
      || typeof v.key !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(v.key) || v.state !== 'UNCERTAIN'
      || !object(v.body) || Object.keys(v.body).sort().join() !== 'expected_version,source_parse_hash'
      || !safe(v.body.expected_version) || typeof v.body.source_parse_hash !== 'string' || !sha.test(v.body.source_parse_hash)
      || !Array.isArray(v.rows) || v.rows.length < 1 || v.rows.length > 500) throw failure()
  const records = new Set<number>(), lines = new Set<number>()
  for (const row of v.rows) {
    if (!object(row) || Object.keys(row).sort().join() !== 'record,sourceRow' || !safe(row.record) || !safe(row.sourceRow)
        || records.has(row.record) || lines.has(row.sourceRow)) throw failure()
    records.add(row.record); lines.add(row.sourceRow)
  }
  return v as unknown as CmdbImportAttempt
}

export function useCmdbImportExecution(source: () => DeepReadonly<AssetImportSource> | null,
    options: { client?: Pick<ApiClient, 'request'>; storage?: Storage; newKey?: () => string } = {}) {
  const client = options.client ?? api
  const receipt = shallowRef<CmdbImportReceipt | null>(null), attempt = shallowRef<CmdbImportAttempt | null>(null)
  const error = shallowRef<unknown>(null), storageError = shallowRef<unknown>(null)
  const busy = shallowRef<'read' | 'write' | null>(null), lookup = shallowRef<'NOT_READ' | 'NO_COMMITTED_RECEIPT' | 'COMPLETED'>('NOT_READ')
  const permitted = computed(() => session.value?.mode === 'remote_test' && can('assets:write') && !switchingTenant.value
    && ['padmin', 'msp'].includes(session.value.user.role) && source()?.tenant_id === session.value.tenant.id && source()?.created_by === session.value.user.id)
  const supported = computed(() => isCmdbExecutionSource(source()))
  const unresolved = computed(() => busy.value === 'write' || attempt.value?.state === 'UNCERTAIN')
  const canSubmit = computed(() => permitted.value && supported.value && !busy.value && !attempt.value && !storageError.value && lookup.value === 'NO_COMMITTED_RECEIPT')
  let epoch = 0, disposed = false, abort: AbortController | undefined
  const scope = () => ({ actor: session.value?.user.id ?? '', tenant: session.value?.tenant.id ?? '', source: source()?.id ?? '' })
  const storageKey = () => { const s = scope(); return `mop.cmdb-import.v1:${s.actor}:${s.tenant}:${s.source}` }
  const storage = () => options.storage ?? window.sessionStorage
  function removePending() { try { storage().removeItem(storageKey()) } catch { /* A successful receipt remains visible; recovery still checks the server. */ } }
  function persist(value: CmdbImportAttempt) { storage().setItem(storageKey(), JSON.stringify({ ...value, state: 'UNCERTAIN' })) }
  function clear() { ++epoch; abort?.abort(); abort = undefined; busy.value = null; receipt.value = null; attempt.value = null; error.value = null; storageError.value = null; lookup.value = 'NOT_READ' }
  function accept(value: unknown) {
    const s = scope(), verified = readCmdbImportReceipt(value, s.tenant, s.source, attempt.value?.state === 'UNCERTAIN' ? attempt.value : undefined)
    receipt.value = verified; lookup.value = 'COMPLETED'
    if (attempt.value) attempt.value = { ...attempt.value, state: 'COMPLETE' }
    removePending()
  }
  async function refresh() {
    if (disposed || !permitted.value || busy.value) return false
    const s = scope(), turn = epoch, controller = new AbortController(); abort = controller; busy.value = 'read'; error.value = null
    lookup.value = receipt.value ? 'COMPLETED' : 'NOT_READ'
    try {
      if (disposed || turn !== epoch || controller.signal.aborted || !permitted.value) return false
      const value = await client.request<unknown>(`/assets/import-sources/${s.source}/cmdb-imports`, { signal: controller.signal })
      if (disposed || turn !== epoch || controller.signal.aborted) return false
      if (!object(value) || value.tenant_id !== s.tenant || value.source_id !== s.source || value.absence_proves_failure !== false) throw failure()
      if (value.status === 'COMPLETED') accept(value.receipt)
      else if (value.status === 'NO_COMMITTED_RECEIPT' && value.receipt === null && !receipt.value) lookup.value = 'NO_COMMITTED_RECEIPT'
      else throw failure()
      return true
    } catch (cause) { if (!disposed && turn === epoch && !isCancelled(cause)) error.value = cause; return false }
    finally { if (!disposed && turn === epoch) { busy.value = null; abort = undefined } }
  }
  async function send(original: CmdbImportAttempt, retry: boolean) {
    if (disposed || !permitted.value || busy.value) return false
    try { persist(original) } catch {
      storageError.value = new ApiError(0, 'CMDB_RECOVERY_STORAGE_UNAVAILABLE', '无法保存本次原请求恢复信息，尚未发送；请允许当前标签页存储后重新读取来源', '—')
      return false
    }
    const turn = epoch, controller = new AbortController(); abort = controller; busy.value = 'write'; error.value = null
    attempt.value = { ...original, state: 'UNCERTAIN' }
    try {
      if (disposed || turn !== epoch || controller.signal.aborted || !permitted.value) return false
      const result = await client.request<unknown>(`/assets/import-sources/${original.sourceId}/cmdb-imports`, {
        method: 'POST', body: { ...original.body }, idempotencyKey: original.key, signal: controller.signal, timeoutMs: 75000,
      })
      if (disposed || turn !== epoch || controller.signal.aborted) return false
      accept(result); return true
    } catch (cause) {
      if (!disposed && turn === epoch) {
        error.value = cause
        const definite = !retry && cause instanceof ApiError && [400,401,403,404,409,413,415,422].includes(cause.status)
        if (definite) { attempt.value = { ...original, state: 'REJECTED' }; removePending() }
      }
      return false
    } finally { if (!disposed && turn === epoch) { busy.value = null; abort = undefined } }
  }
  async function submit() {
    if (!canSubmit.value) return false
    const current = source()!, s = scope(), artifact = current.parsed_artifact!
    return send({ actorId: s.actor, tenantId: s.tenant, sourceId: s.source, artifactId: artifact.id,
      key: (options.newKey ?? createRequestId)(), body: { expected_version: current.version, source_parse_hash: artifact.parse_hash },
      rows: artifact.rows.map(row => ({ record: row.logical_record_number, sourceRow: row.source_row_number })), state: 'UNCERTAIN' }, false)
  }
  async function retry() { return attempt.value?.state === 'UNCERTAIN' ? send(attempt.value, true) : false }
  function stopWaiting() { if (!busy.value) return; ++epoch; abort?.abort(); abort = undefined; busy.value = null; error.value = null }
  async function prepareAgain() {
    if (busy.value || attempt.value?.state !== 'REJECTED') return false
    if (!await refresh() || lookup.value !== 'NO_COMMITTED_RECEIPT') return false
    attempt.value = null; error.value = null; return true
  }
  const stop = watch(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id,
    session.value?.permission_version, session.value?.account_version, session.value?.user.role, session.value?.capabilities,
    permitted.value, source()?.id, source()?.version, source()?.parsed_artifact?.parse_hash]), () => {
    clear()
    if (!permitted.value || !source()) return
    try { const raw = storage().getItem(storageKey()); if (raw) { const s = scope(); attempt.value = readCmdbImportAttempt(raw, s.actor, s.tenant, s.source) } }
    catch { storageError.value = new ApiError(0, 'CMDB_RECOVERY_INVALID', '本标签页恢复信息无法核验，禁止新建导入请求；请先读取服务端回执', '—') }
    void refresh()
  }, { immediate: true, flush: 'sync' })
  function dispose() { disposed = true; stop(); clear() }
  return { receipt: readonly(receipt), attempt: readonly(attempt), error, storageError, busy: readonly(busy), lookup: readonly(lookup),
    permitted, supported, unresolved, canSubmit, refresh, submit, retry, stopWaiting, prepareAgain, dispose }
}
