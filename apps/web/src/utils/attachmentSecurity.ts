import { ref } from 'vue'
import { api, ApiError, isCancelled } from '../api/client'
import { createRequestId } from './browserCrypto'
import { writeFailureUncertain } from './writeFailure'

export type AttachmentAction = 'REQUEST_SCAN' | 'CANCEL_SCAN' | 'RELEASE' | 'QUARANTINE'
export interface AttachmentSecurity {
  file_id: string; tenant_id: string; version: number
  state: 'QUARANTINED' | 'QUEUED' | 'CANCELLED' | 'CLEAN' | 'REJECTED' | 'UNKNOWN' | 'RELEASED'
  scan_request_id: string | null; content_sha256: string; scanned_at: string | null
  valid_until: string | null; recorded_at: string | null; release_allowed: boolean
  content_allowed: boolean; can_manage_release: boolean; can_request_scan: boolean
  scanner_status: 'NOT_CONFIGURED' | 'CONFIGURED_NOT_VERIFIED'
}

function verified(value: AttachmentSecurity, id: string, tenant: string): AttachmentSecurity {
  if (!value || value.file_id !== id || value.tenant_id !== tenant || !Number.isSafeInteger(value.version) || value.version < 0 || value.version > 9_007_199_254_740_990
    || !['QUARANTINED', 'QUEUED', 'CANCELLED', 'CLEAN', 'REJECTED', 'UNKNOWN', 'RELEASED'].includes(value.state)
    || !/^[a-f0-9]{64}$/.test(value.content_sha256) || typeof value.release_allowed !== 'boolean'
    || typeof value.content_allowed !== 'boolean' || typeof value.can_manage_release !== 'boolean' || typeof value.can_request_scan !== 'boolean'
    || !['NOT_CONFIGURED', 'CONFIGURED_NOT_VERIFIED'].includes(value.scanner_status)
    || (value.content_allowed && value.state !== 'RELEASED')
    || (value.release_allowed && (value.state !== 'CLEAN' || !value.can_manage_release))) {
    throw new ApiError(200, 'INVALID_ATTACHMENT_SECURITY', '附件安全状态与当前对象不一致，未允许读取原图', '—')
  }
  return value
}

export interface AttachmentScope { fileId: string; tenantId: string; actorId: string; permissionVersion: number; accountVersion: number }
type Pending = { key: string; body: { expected_version: number; action: AttachmentAction } }
type Journal = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const browserJournal: Journal = {
  getItem: key => window.sessionStorage.getItem(key),
  setItem: (key, value) => window.sessionStorage.setItem(key, value),
  removeItem: key => window.sessionStorage.removeItem(key),
}
const outcomes = { REQUEST_SCAN: 'QUEUED', CANCEL_SCAN: 'CANCELLED', RELEASE: 'RELEASED', QUARANTINE: 'QUARANTINED' }
/** Retain an uncertain mutation's original body and key until server replay resolves it. */
export function createAttachmentSecurityFlow(
  scope: () => AttachmentScope, client: Pick<typeof api, 'request'> = api, journal: Journal = browserJournal,
) {
  const snapshot = ref<AttachmentSecurity | null>(null), error = ref<unknown>(null)
  const busy = ref(false), reading = ref(false)
  const pending = ref<Pending | null>(null), restorationBlocked = ref(false)
  const scopeKey = () => { const s = scope(); return 'mop.attachment-command.v1:' + JSON.stringify([s.actorId, s.tenantId, s.permissionVersion, s.accountVersion, s.fileId]) }
  let owner = scopeKey()
  function restore() {
    owner = scopeKey(); restorationBlocked.value = false
    try {
      const raw = journal.getItem(owner)
      if (!raw) return
      const value = JSON.parse(raw) as Pending
      if (!value || Object.keys(value).sort().join(',') !== 'body,key' || typeof value.key !== 'string'
        || !/^[A-Za-z0-9._:-]{1,128}$/.test(value.key) || !value.body
        || Object.keys(value.body).sort().join(',') !== 'action,expected_version'
        || !Object.hasOwn(outcomes, value.body.action) || !Number.isSafeInteger(value.body.expected_version)
        || value.body.expected_version < 0 || value.body.expected_version >= 9_007_199_254_740_990) throw new Error('原附件命令记录无效，请核对后台记录')
      pending.value = value
    } catch (cause) { restorationBlocked.value = true; error.value = cause }
  }
  function clearPending() { journal.removeItem(owner); pending.value = null }
  restore()
  let generation = 0, writeController: AbortController | null = null, readController: AbortController | null = null
  const path = (id: string) => `/files/${encodeURIComponent(id)}/security`
  async function refresh() {
    if (owner !== scopeKey()) reset()
    const current = scope(), epoch = generation
    snapshot.value = null
    readController?.abort()
    const controller = new AbortController(); readController = controller; reading.value = true
    try {
      const value = await client.request<AttachmentSecurity>(path(current.fileId), { signal: controller.signal })
      if (epoch !== generation || controller.signal.aborted || owner !== scopeKey()) return
      snapshot.value = verified(value, current.fileId, current.tenantId)
      if (!pending.value) error.value = null
    } catch (cause) {
      if (epoch === generation && !isCancelled(cause)) { snapshot.value = null; error.value = cause }
    } finally { if (epoch === generation && readController === controller) reading.value = false }
  }
  async function perform(retry: boolean) {
    const attempt = pending.value
    if (!attempt || busy.value || restorationBlocked.value || owner !== scopeKey()) return
    const current = scope(), epoch = generation, controller = new AbortController()
    writeController = controller; busy.value = true; error.value = null
    try {
      const value = await client.request<AttachmentSecurity>(`${path(current.fileId)}/actions`, {
        method: 'POST', body: { ...attempt.body }, idempotencyKey: attempt.key, signal: controller.signal,
      })
      if (epoch !== generation || controller.signal.aborted || owner !== scopeKey()) return
      const result = verified(value, current.fileId, current.tenantId)
      if (result.version !== attempt.body.expected_version + 1 || result.state !== outcomes[attempt.body.action]) {
        throw new ApiError(200, 'INVALID_ATTACHMENT_ACK', '回执版本或动作不匹配，请按原请求核对', '—')
      }
      clearPending(); snapshot.value = result
      await refresh()
    } catch (cause) {
      if (epoch !== generation) return
      if (!isCancelled(cause) && !writeFailureUncertain(retry, cause)) {
        try { clearPending() } catch (failure) { restorationBlocked.value = true; error.value = failure; return }
      }
      error.value = isCancelled(cause)
        ? new ApiError(0, 'REQUEST_OUTCOME_UNKNOWN', '已停止等待，服务端结果待确认；请使用原请求重查', '—') : cause
    } finally { if (epoch === generation) busy.value = false }
  }
  async function act(action: AttachmentAction) {
    if (pending.value || busy.value || reading.value || restorationBlocked.value || owner !== scopeKey() || !snapshot.value) return
    if (action === 'RELEASE' && !snapshot.value.release_allowed) return
    if (action === 'QUARANTINE' && !snapshot.value.can_manage_release) return
    if (['REQUEST_SCAN', 'CANCEL_SCAN'].includes(action) && !snapshot.value.can_request_scan) return
    if (action === 'CANCEL_SCAN' && snapshot.value.state !== 'QUEUED') return
    const attempt = { key: createRequestId(), body: { expected_version: snapshot.value.version, action } }
    try { journal.setItem(owner, JSON.stringify(attempt)) } catch (cause) { restorationBlocked.value = true; error.value = cause; return }
    pending.value = attempt
    await perform(false)
  }
  function dispose() {
    generation++; readController?.abort(); writeController?.abort()
    snapshot.value = null; pending.value = null; error.value = null; busy.value = false; reading.value = false
  }
  function reset() { dispose(); restore() }
  return { snapshot, error, busy, reading, pending, restorationBlocked, refresh, act, dispose, retry: () => perform(true),
    stopWaiting: () => writeController?.abort(), reset }
}
