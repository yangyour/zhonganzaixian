import { ApiError, isCancelled, type ApiClient } from '../api/client'

export interface BackupInventoryScope { tenantId: string; assetId: string; actorId: string; permissionVersion: number }
export interface BackupInventoryCursor { registeredAt: string; versionId: string }
export interface BackupInventoryTarget { cursor: BackupInventoryCursor | null; pageSize: number }
interface UnverifiedFlags { current_object_health_verified: false; download_authorized: false; device_execution_authorized: false; retention_verified: false; recovery_verified: false }
export interface BackupInventoryItem extends UnverifiedFlags {
  version_id: string; asset_id: string; run_id: string; attempt_number: number; completed_run_version: number;
  object_observed_at: string; registered_at: string; encrypted_sha256: string; encrypted_bytes: number;
  registration_and_run_completion_recorded: true;
}
export interface BackupInventoryPage extends UnverifiedFlags {
  tenant_id: string; asset_id: string; actor_id: string; accepted_permission_version: number;
  items: BackupInventoryItem[]; has_more: boolean; next_before_registered_at: string | null; next_before_version_id: string | null;
  page_size: number; page_size_limit: 50; total_count: null; scope: 'CURRENT_AUTHORIZED_TENANT_ASSET';
  snapshot_scope: 'SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT'; source: 'postgresql'; object_storage_accessed: false;
}
export interface BackupInventoryState { current: BackupInventoryPage | null; target: BackupInventoryTarget | null; busy: boolean; error: unknown; cancelled: boolean }
const flags = ['current_object_health_verified','download_authorized','device_execution_authorized','retention_verified','recovery_verified']
const itemKeys = ['version_id','asset_id','run_id','attempt_number','completed_run_version','object_observed_at','registered_at','encrypted_sha256','encrypted_bytes','registration_and_run_completion_recorded',...flags]
const pageKeys = ['tenant_id','asset_id','actor_id','accepted_permission_version','items','has_more','next_before_registered_at','next_before_version_id','page_size','page_size_limit','total_count','scope','snapshot_scope','source','object_storage_accessed',...flags]
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const integer = (v: unknown, min: number, max = Number.MAX_SAFE_INTEGER): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v,k))
const copy = <T>(v: T): T => structuredClone(v)
const invalid = () => new ApiError(200,'INVALID_BACKUP_VERSION_INVENTORY','备份版本响应范围或结构异常，未展示部分结果，请重新读取','—')
export function backupInventoryInstant(v: unknown): number {
  if (typeof v !== 'string' || !/^(?!0000)\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,3})?Z$/.test(v)) throw invalid()
  const ms = Date.parse(v), normalized = v.replace(/(?:\.(\d{1,3}))?Z$/, (_, digits: string | undefined) => `.${(digits ?? '').padEnd(3,'0')}Z`)
  if (!Number.isFinite(ms) || new Date(ms).toISOString() !== normalized) throw invalid()
  return ms
}
function validScope(v: BackupInventoryScope): boolean { return uuid(v.assetId) && uuid(v.tenantId) && uuid(v.actorId) && integer(v.permissionVersion,1) }
function validTarget(v: BackupInventoryTarget): boolean {
  if (!integer(v.pageSize,1,50)) return false
  if (v.cursor === null) return true
  try { return uuid(v.cursor.versionId) && Number.isFinite(backupInventoryInstant(v.cursor.registeredAt)) } catch { return false }
}
// Canonical UUID text order matches PostgreSQL's unsigned UUID order, not signed Java UUID order.
function older(item: BackupInventoryCursor, boundary: BackupInventoryCursor): boolean {
  const left = backupInventoryInstant(item.registeredAt), right = backupInventoryInstant(boundary.registeredAt)
  return left < right || left === right && item.versionId < boundary.versionId
}
export function readBackupInventory(raw: unknown, scope: BackupInventoryScope, target: BackupInventoryTarget): BackupInventoryPage {
  if (!validScope(scope) || !validTarget(target) || !exact(raw,pageKeys) || raw.tenant_id !== scope.tenantId || raw.asset_id !== scope.assetId || raw.actor_id !== scope.actorId || raw.accepted_permission_version !== scope.permissionVersion || raw.page_size !== target.pageSize || raw.page_size_limit !== 50 || raw.total_count !== null || raw.scope !== 'CURRENT_AUTHORIZED_TENANT_ASSET' || raw.snapshot_scope !== 'SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT' || raw.source !== 'postgresql' || raw.object_storage_accessed !== false || flags.some(k => raw[k] !== false) || typeof raw.has_more !== 'boolean' || !Array.isArray(raw.items) || raw.items.length > target.pageSize) throw invalid()
  const ids = new Set<string>(); let boundary = target.cursor
  for (const item of raw.items) {
    if (!exact(item,itemKeys) || !uuid(item.version_id) || !uuid(item.run_id) || item.asset_id !== scope.assetId || ids.has(item.version_id) || !integer(item.attempt_number,1,3) || !integer(item.completed_run_version,2) || !integer(item.encrypted_bytes,247,16787456) || typeof item.encrypted_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.encrypted_sha256) || item.registration_and_run_completion_recorded !== true || flags.some(k => item[k] !== false)) throw invalid()
    if (backupInventoryInstant(item.object_observed_at) > backupInventoryInstant(item.registered_at)) throw invalid()
    const cursor = { registeredAt: item.registered_at as string, versionId: item.version_id }
    if (boundary && !older(cursor,boundary)) throw invalid()
    boundary = cursor; ids.add(item.version_id)
  }
  if (raw.has_more) {
    if (raw.items.length !== target.pageSize || !boundary || raw.next_before_registered_at !== boundary.registeredAt || raw.next_before_version_id !== boundary.versionId) throw invalid()
  } else if (raw.next_before_registered_at !== null || raw.next_before_version_id !== null) throw invalid()
  return copy(raw) as unknown as BackupInventoryPage
}

export function createBackupInventoryReader(client: Pick<ApiClient,'request'>, notify: (value: BackupInventoryState) => void) {
  let scope: BackupInventoryScope | null = null, epoch = 0, disposed = false, controller: AbortController | null = null
  let state: BackupInventoryState = { current: null, target: null, busy: false, error: null, cancelled: false }
  const snapshot = (): BackupInventoryState => ({ ...state, current: state.current ? copy(state.current) : null, target: state.target ? copy(state.target) : null })
  const emit = () => notify(snapshot())
  const abort = () => { epoch++; controller?.abort(); controller = null }
  async function load(target: BackupInventoryTarget) {
    if (disposed || !scope || state.busy || !validTarget(target)) return false
    abort(); const mine = epoch, context = { ...scope }, original = copy(target), pending = new AbortController(); controller = pending
    state = { current: null, target: original, busy: true, error: null, cancelled: false }; emit()
    // notify may synchronously revoke or replace the current scope.
    if (disposed || mine !== epoch || pending.signal.aborted) return false
    const params = new URLSearchParams({ page_size: String(original.pageSize) })
    if (original.cursor) { params.set('before_registered_at',original.cursor.registeredAt); params.set('before_version_id',original.cursor.versionId) }
    try {
      const raw = await client.request(`/assets/${context.assetId}/backup-versions?${params}`,{ signal: pending.signal })
      if (disposed || mine !== epoch) return false
      state.current = readBackupInventory(raw,context,original); return true
    } catch (error) { if (!disposed && mine === epoch) { if (isCancelled(error)) state.cancelled = true; else state.error = error }; return false }
    finally { if (!disposed && mine === epoch) { controller = null; state.busy = false; emit() } }
  }
  return {
    state: snapshot,
    reset(value: BackupInventoryScope | null) { abort(); scope = !disposed && value && validScope(value) ? { ...value } : null; state = { current: null, target: null, busy: false, error: null, cancelled: false }; emit() },
    first: () => load({ cursor: null, pageSize: 15 }),
    next() { const page = state.current; return page?.has_more && page.next_before_registered_at && page.next_before_version_id ? load({ cursor: { registeredAt: page.next_before_registered_at, versionId: page.next_before_version_id }, pageSize: page.page_size }) : Promise.resolve(false) },
    retry() { return state.target ? load(copy(state.target)) : Promise.resolve(false) },
    cancel() { if (!state.busy) return false; abort(); state = { ...state, current: null, busy: false, error: null, cancelled: true }; emit(); return true },
    dispose() { abort(); disposed = true; scope = null; state = { current: null, target: null, busy: false, error: null, cancelled: false }; emit() },
  }
}
