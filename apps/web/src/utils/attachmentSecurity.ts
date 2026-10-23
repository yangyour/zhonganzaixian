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
  if (!value || value.file_id !== id || value.tenant_id !== tenant || !Number.isSafeInteger(value.version) || value.version < 0
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

/** Retain an uncertain mutation's original body and key until server replay resolves it. */
export function createAttachmentSecurityFlow(
  scope: () => { fileId: string; tenantId: string }, client: Pick<typeof api, 'request'> = api,
) {
  const snapshot = ref<AttachmentSecurity | null>(null), error = ref<unknown>(null)
  const busy = ref(false), reading = ref(false)
  const pending = ref<{ key: string; body: { expected_version: number; action: AttachmentAction } } | null>(null)
  let generation = 0, writeController: AbortController | null = null, readController: AbortController | null = null
  const path = (id: string) => `/files/${encodeURIComponent(id)}/security`
  async function refresh() {
    const current = scope(), epoch = generation
    readController?.abort()
    const controller = new AbortController(); readController = controller; reading.value = true
    try {
      const value = await client.request<AttachmentSecurity>(path(current.fileId), { signal: controller.signal })
      if (epoch !== generation || controller.signal.aborted) return
      snapshot.value = verified(value, current.fileId, current.tenantId)
      if (!pending.value) error.value = null
    } catch (cause) {
      if (epoch === generation && !isCancelled(cause)) { snapshot.value = null; error.value = cause }
    } finally { if (epoch === generation && readController === controller) reading.value = false }
  }
  async function perform(retry: boolean) {
    const attempt = pending.value
    if (!attempt || busy.value) return
    const current = scope(), epoch = generation, controller = new AbortController()
    writeController = controller; busy.value = true; error.value = null
    try {
      const value = await client.request<AttachmentSecurity>(`${path(current.fileId)}/actions`, {
        method: 'POST', body: { ...attempt.body }, idempotencyKey: attempt.key, signal: controller.signal,
      })
      if (epoch !== generation || controller.signal.aborted) return
      snapshot.value = verified(value, current.fileId, current.tenantId); pending.value = null
      await refresh()
    } catch (cause) {
      if (epoch !== generation) return
      if (!isCancelled(cause) && !writeFailureUncertain(retry, cause)) pending.value = null
      error.value = isCancelled(cause)
        ? new ApiError(0, 'REQUEST_OUTCOME_UNKNOWN', '已停止等待，服务端结果待确认；请使用原请求重查', '—') : cause
    } finally { if (epoch === generation) busy.value = false }
  }
  async function act(action: AttachmentAction) {
    if (pending.value || busy.value || reading.value || !snapshot.value) return
    if (action === 'RELEASE' && !snapshot.value.release_allowed) return
    if (action === 'QUARANTINE' && !snapshot.value.can_manage_release) return
    if (['REQUEST_SCAN', 'CANCEL_SCAN'].includes(action) && !snapshot.value.can_request_scan) return
    if (action === 'CANCEL_SCAN' && snapshot.value.state !== 'QUEUED') return
    pending.value = { key: createRequestId(), body: { expected_version: snapshot.value.version, action } }
    await perform(false)
  }
  function reset() {
    generation++; readController?.abort(); writeController?.abort()
    snapshot.value = null; pending.value = null; error.value = null; busy.value = false; reading.value = false
  }
  return { snapshot, error, busy, reading, pending, refresh, act, retry: () => perform(true),
    stopWaiting: () => writeController?.abort(), reset }
}
