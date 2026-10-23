import { ApiError, isCancelled, type ApiClient } from '../api/client'
import type { BackupInventoryScope } from './backupVersionInventory'

export interface BackupDifference {
  tenant_id: string; asset_id: string; before_version_id: string; after_version_id: string
  status: 'IDENTICAL' | 'CHANGED' | 'LIMIT_EXCEEDED' | 'CANCELLED' | 'UNAVAILABLE'
  changed: boolean | null; before_lines: number | null; after_lines: number | null
  hunks: { before_start: number; before_count: number; after_start: number; after_count: number }[]
  redaction: 'ALL_TEXT_WITHHELD'; noise_policy: 'NONE_APPLIED_UNCONFIRMED'
  change_approved: false; automatic_ticket_allowed: false; actual_recovery_verified: false
}
export type BackupContentRequest = { kind: 'download'; version: string; format: 'envelope' | 'plaintext' } | { kind: 'diff'; before: string; after: string }
export interface BackupContentState { busy: boolean; cancelled: boolean; error: unknown; difference: BackupDifference | null; request: BackupContentRequest | null; downloaded: boolean }
export const backupVersionId = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 1000
const failure = () => new ApiError(200, 'INVALID_BACKUP_CONTENT', '备份内容响应范围或结构异常，未展示内容', '—')
const exact = (raw: unknown, keys: string[]): raw is Record<string, unknown> => !!raw && typeof raw === 'object' && !Array.isArray(raw) && Object.keys(raw).length === keys.length && keys.every(key => Object.hasOwn(raw, key))
export function readBackupDifference(raw: unknown, scope: BackupInventoryScope, before: string, after: string): BackupDifference {
  if (!exact(raw, ['tenant_id','asset_id','before_version_id','after_version_id','status','changed','before_lines','after_lines','hunks','redaction','noise_policy','change_approved','automatic_ticket_allowed','actual_recovery_verified'])
    || raw.tenant_id !== scope.tenantId || raw.asset_id !== scope.assetId || raw.before_version_id !== before || raw.after_version_id !== after
    || !['IDENTICAL','CHANGED','LIMIT_EXCEEDED','CANCELLED','UNAVAILABLE'].includes(raw.status as string)
    || raw.redaction !== 'ALL_TEXT_WITHHELD' || raw.noise_policy !== 'NONE_APPLIED_UNCONFIRMED'
    || raw.change_approved !== false || raw.automatic_ticket_allowed !== false || raw.actual_recovery_verified !== false
    || !Array.isArray(raw.hunks) || raw.hunks.length > 2000) throw failure()
  const compared = raw.status === 'IDENTICAL' || raw.status === 'CHANGED'
  if (compared ? !integer(raw.before_lines) || !integer(raw.after_lines) || raw.changed !== (raw.status === 'CHANGED') || (raw.status === 'IDENTICAL') !== (raw.hunks.length === 0)
    : raw.changed !== null || raw.before_lines !== null || raw.after_lines !== null || raw.hunks.length !== 0) throw failure()
  let removed = 0, added = 0
  for (const hunk of raw.hunks) {
    if (!exact(hunk, ['before_start','before_count','after_start','after_count']) || !integer(hunk.before_start) || !integer(hunk.before_count)
      || !integer(hunk.after_start) || !integer(hunk.after_count) || hunk.before_count + hunk.after_count === 0
      || hunk.before_start + hunk.before_count > (raw.before_lines as number) || hunk.after_start + hunk.after_count > (raw.after_lines as number)) throw failure()
    removed += hunk.before_count; added += hunk.after_count
  }
  if (compared && ((raw.before_lines as number) - removed + added !== raw.after_lines || removed > (raw.before_lines as number) || added > (raw.after_lines as number))) throw failure()
  return structuredClone(raw) as unknown as BackupDifference
}

export function createBackupContentReader(client: Pick<ApiClient, 'request' | 'requestFile'>,
  notify: (state: BackupContentState) => void, deliver: (blob: Blob, filename: string) => void) {
  let scope: BackupInventoryScope | null = null, epoch = 0, controller: AbortController | null = null, disposed = false
  let state: BackupContentState = { busy: false, cancelled: false, error: null, difference: null, request: null, downloaded: false }
  const emit = () => notify({ ...state, request: state.request ? { ...state.request } : null })
  function cancel() {
    if (!state.busy) return false
    epoch++; controller?.abort(); controller = null
    state = { ...state, busy: false, cancelled: true, error: null, difference: null, downloaded: false }; emit(); return true
  }
  async function execute(request: BackupContentRequest) {
    if (!scope || ![scope.tenantId, scope.assetId, scope.actorId].every(backupVersionId) || !Number.isSafeInteger(scope.permissionVersion) || scope.permissionVersion < 1
      || disposed || state.busy || (request.kind === 'download' ? !backupVersionId(request.version) || !['envelope','plaintext'].includes(request.format) : !backupVersionId(request.before) || !backupVersionId(request.after))) return false
    controller?.abort(); const pending = new AbortController(), mine = ++epoch, original = { ...scope }; controller = pending
    const pinned = { ...request }
    state = { busy: true, cancelled: false, error: null, difference: null, request: pinned, downloaded: false }; emit()
    try {
      if (mine !== epoch || pending.signal.aborted) return false
      const root = `/assets/${original.assetId}/backup-versions`
      if (pinned.kind === 'download') {
        const blob = await client.requestFile(`${root}/${pinned.version}/content?format=${pinned.format}`, { signal: pending.signal })
        if (mine !== epoch || disposed || pending.signal.aborted) return false
        if (!(blob instanceof Blob) || blob.type !== 'application/octet-stream' || blob.size > 16787456 || (pinned.format === 'envelope' && blob.size < 247)) throw failure()
        deliver(blob, `backup-${pinned.version}.${pinned.format === 'plaintext' ? 'txt' : 'envelope'}`)
        if (mine !== epoch || disposed || pending.signal.aborted) return false
        state = { ...state, busy: false, downloaded: true }
      } else {
        const raw = await client.request<unknown>(`${root}/diff?before_version_id=${pinned.before}&after_version_id=${pinned.after}`, { signal: pending.signal })
        if (mine !== epoch || disposed || pending.signal.aborted) return false
        state = { ...state, busy: false, difference: readBackupDifference(raw, original, pinned.before, pinned.after) }
      }
      emit(); return true
    } catch (error) {
      if (mine !== epoch || disposed) return false
      state = { ...state, busy: false, cancelled: isCancelled(error), error: isCancelled(error) ? null : error, difference: null, downloaded: false }; emit(); return false
    } finally { if (mine === epoch) controller = null }
  }
  return {
    execute, cancel,
    retry: () => state.request ? execute(state.request) : Promise.resolve(false),
    reset(value: BackupInventoryScope | null) {
      epoch++; controller?.abort(); controller = null; scope = value ? { ...value } : null
      state = { busy: false, cancelled: false, error: null, difference: null, request: null, downloaded: false }; emit()
    },
    dispose() { disposed = true; epoch++; controller?.abort(); controller = null; scope = null },
  }
}
