import { writeFailureUncertain } from './writeFailure'
import { markRaw, ref, watch } from 'vue'
import { api, ApiError, isCancelled } from '../api/client'
import type { CustomerReportAcceptance, FileContentGrant, FileUploadOperation, ReportPhoto, ReportPhotoPolicy } from '../api/types'
import { contextGeneration, session } from '../stores/session'
import { isTicketUuid } from './ticket'
import { fileContentPath, fileIdFromResultRef, photoMaxBytes, photoSelectionError, preparePhotoAttempt, readyPhotoIds, type PhotoUploadAttempt } from './photo'

export interface PhotoSelection {
  uid: number; file: File; previewUrl: string; attempt: PhotoUploadAttempt | null
  status: 'SELECTED' | 'UPLOADING' | 'UNCERTAIN' | 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'
  operationId: string | null; record: ReportPhoto | null; uploading: boolean; reading: boolean; error: unknown; message: string; generation: number
}
export function createPhotoQueue(client: Pick<typeof api, 'request' | 'requestBlob'> = api) {
  const rows = ref<PhotoSelection[]>([]), policy = ref<ReportPhotoPolicy | null>(null), policyLoading = ref(false), policyError = ref<unknown>(null)
  const contentUrls = ref<Record<string, string>>({}), contentBusy = ref<Record<string, boolean>>({}), contentErrors = ref<Record<string, unknown>>({})
  const controllers = new Map<number | string, AbortController>()
  let generation = 0
  const context = () => ({ tenantId: session.value?.tenant.id || '', actorId: session.value?.user.id || '' })
  const current = (row: PhotoSelection, version: number, epoch: number) => epoch === generation && row.generation === version && rows.value.some(item => item.uid === row.uid)
  async function loadPolicy() {
    controllers.get('policy')?.abort(); const controller = new AbortController(); controllers.set('policy', controller)
    const epoch = generation
    policyLoading.value = true; policyError.value = null
    try { const result = await client.request<ReportPhotoPolicy>('/files/policy', { signal: controller.signal }); if (epoch === generation && !controller.signal.aborted) policy.value = result }
    catch (cause) { if (!isCancelled(cause) && epoch === generation) { policy.value = null; policyError.value = cause } }
    finally { if (epoch === generation && !controller.signal.aborted) policyLoading.value = false }
  }
  function add(file: File, uid: number, existingCount: number, existingIds: readonly string[] = []) {
    if (rows.value.some(row => row.uid === uid)) return '图片选择标识重复，请重新选择'
    const error = photoSelectionError(file, policy.value, existingCount + rows.value.filter(row => !existingIds.includes(row.record?.id || '')).length)
    if (error) return error
    rows.value.push({ uid, file: markRaw(file), previewUrl: URL.createObjectURL(file), attempt: null, status: 'SELECTED', operationId: null, record: null, uploading: false, reading: false, error: null, message: '', generation: 0 })
    return null
  }
  function canRemove(uid: number) {
    const row = rows.value.find(item => item.uid === uid)
    if (!row || row.uploading || row.reading || row.status === 'UNCERTAIN') return false
    // A 202 is durable. Keep its original File/key until both operation and file were read to a terminal state.
    return !row.operationId || Boolean(row.record && ((row.status === 'READY' && row.record.upload_status === 'READY') || (row.status === 'FAILED' && row.record.upload_status === 'FAILED')))
  }
  function remove(uid: number) {
    const row = rows.value.find(item => item.uid === uid)
    if (!row || !canRemove(uid)) return
    ++row.generation; controllers.get(uid)?.abort(); controllers.delete(uid); URL.revokeObjectURL(row.previewUrl)
    rows.value = rows.value.filter(item => item.uid !== uid)
  }
  async function refresh(uid: number, bounded = false) {
    const row = rows.value.find(item => item.uid === uid)
    if (!row?.operationId || row.uploading || row.reading) return
    const controller = new AbortController(); controllers.get(uid)?.abort(); controllers.set(uid, controller)
    const epoch = generation, version = ++row.generation
    row.reading = true; row.error = null; row.message = ''
    try {
      for (let count = 0; count < (bounded ? 6 : 1); count++) {
        const operation: FileUploadOperation = await client.request<FileUploadOperation>(`/operations/${encodeURIComponent(row.operationId)}`, { signal: controller.signal })
        if (!current(row, version, epoch)) return
        const fileId = fileIdFromResultRef(operation?.result_ref)
        if (!operation || operation.kind !== 'FILE_UPLOAD' || !isTicketUuid(operation.id) || operation.id.toLowerCase() !== row.operationId || operation.tenant_id !== context().tenantId || !['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(operation.status) || !fileId) throw new ApiError(200, 'INVALID_UPLOAD_RESULT', '上传结果返回异常，请手动重查，不会再次上传', '—')
        row.status = operation.status === 'RUNNING' ? 'RUNNING' : operation.status === 'FAILED' ? 'FAILED' : 'PENDING'
        if (operation.error) row.message = operation.error.message
        const record = await client.request<ReportPhoto>(`/files/${fileId}`, { signal: controller.signal })
        if (!current(row, version, epoch)) return
        if (!record || !isTicketUuid(record.id) || !isTicketUuid(record.operation_id) || record.id.toLowerCase() !== fileId || record.operation_id.toLowerCase() !== row.operationId || record.tenant_id !== context().tenantId || record.created_by !== context().actorId || record.purpose !== 'customer_report_photo') throw new ApiError(200, 'INVALID_UPLOAD_RESULT', '文件详情与上传操作或当前账号不一致，请手动重查', '—')
        row.record = record
        if (operation.status === 'SUCCEEDED' && record.upload_status === 'READY') { row.status = 'READY'; row.message = '已上传，未扫描；关联报障以详情回读为准'; return }
        if (operation.status === 'FAILED' || record.upload_status === 'FAILED') { row.status = 'FAILED'; return }
        if (count + 1 < (bounded ? 6 : 1)) await new Promise<void>((resolve, reject) => {
          const cancel = () => { clearTimeout(timer); reject(new DOMException('已取消', 'AbortError')) }
          const timer = setTimeout(() => { controller.signal.removeEventListener('abort', cancel); resolve() }, 1000)
          controller.signal.addEventListener('abort', cancel, { once: true })
          if (controller.signal.aborted) cancel()
        })
      }
      if (current(row, version, epoch)) row.message = row.message || '仍待处理；可手动刷新状态，未判定为上传完成'
    } catch (cause) { if (!isCancelled(cause) && current(row, version, epoch)) row.error = cause }
    finally { if (current(row, version, epoch)) row.reading = false }
  }
  async function upload(uid: number) {
    const row = rows.value.find(item => item.uid === uid)
    if (!row || row.uploading || row.operationId || row.status === 'FAILED') return
    const validation = photoSelectionError(row.file, policy.value, 0)
    if (validation) { row.error = new ApiError(0, 'PHOTO_INPUT_ERROR', validation, '—'); return }
    const retry = row.attempt?.state === 'uncertain'
    row.attempt = preparePhotoAttempt(row.attempt, row.file)
    const controller = new AbortController(); controllers.get(uid)?.abort(); controllers.set(uid, controller)
    const epoch = generation, version = ++row.generation
    const form = new FormData(); form.append('file', row.file, row.file.name); form.append('purpose', 'customer_report_photo')
    row.uploading = true; row.status = 'UPLOADING'; row.error = null
    try {
      const accepted = await client.request<CustomerReportAcceptance>('/files', { method: 'POST', body: form, idempotencyKey: row.attempt.key, signal: controller.signal })
      if (!current(row, version, epoch)) return
      if (!accepted || !isTicketUuid(accepted.operation_id) || accepted.status !== 'PENDING') throw new ApiError(200, 'INVALID_RESPONSE', '上传响应无法确认，请使用原文件与请求标识重试', '—')
      row.attempt.state = 'accepted'; row.operationId = accepted.operation_id.toLowerCase(); row.status = 'PENDING'; row.uploading = false
      void refresh(uid, true)
    } catch (cause) {
      if (!isCancelled(cause) && current(row, version, epoch)) {
        const uncertain = writeFailureUncertain(retry, cause)
        row.attempt.state = uncertain ? 'uncertain' : 'ready'; row.status = uncertain ? 'UNCERTAIN' : 'SELECTED'; row.error = cause
      }
    } finally { if (current(row, version, epoch)) row.uploading = false }
  }
  function readyIds(ticketId: string | null = null) {
    return readyPhotoIds(rows.value.filter(row => row.status === 'READY' && row.record).map(row => row.record!), context(), ticketId)
  }
  async function view(record: ReportPhoto) {
    if (contentBusy.value[record.id] || record.upload_status !== 'READY' || !isTicketUuid(record.id) || record.tenant_id !== context().tenantId) return
    const controller = new AbortController(), key = `content:${record.id}`; controllers.get(key)?.abort(); controllers.set(key, controller)
    const epoch = generation
    contentBusy.value[record.id] = true; contentErrors.value[record.id] = null
    try {
      const grant = await client.request<FileContentGrant>(`/files/${encodeURIComponent(record.id)}/content-grants`, { method: 'POST', body: {}, signal: controller.signal })
      if (epoch !== generation || controller.signal.aborted) return
      const path = fileContentPath(grant?.content_url, record.id)
      if (!path) throw new ApiError(200, 'INVALID_CONTENT_REFERENCE', '图片授权地址返回异常，未打开外部地址', '—')
      if (!Number.isFinite(Date.parse(grant.expires_at)) || Date.parse(grant.expires_at) <= Date.now()) throw new ApiError(200, 'INVALID_CONTENT_REFERENCE', '图片授权已过期，请重新申请查看', '—')
      const blob = await client.requestBlob(path, { signal: controller.signal })
      if (epoch !== generation || controller.signal.aborted) return
      if (!['image/jpeg', 'image/png'].includes(blob.type) || blob.type !== record.media_type || blob.size !== record.size_bytes || blob.size < 1 || blob.size > photoMaxBytes) throw new ApiError(200, 'INVALID_IMAGE_CONTENT', '图片内容返回异常，未显示为成功读取', '—')
      if (contentUrls.value[record.id]) URL.revokeObjectURL(contentUrls.value[record.id]!)
      contentUrls.value[record.id] = URL.createObjectURL(blob)
    } catch (cause) { if (!isCancelled(cause) && epoch === generation && !controller.signal.aborted) contentErrors.value[record.id] = cause }
    finally { if (epoch === generation && controllers.get(key) === controller) contentBusy.value[record.id] = false }
  }
  function clear() {
    ++generation; for (const controller of controllers.values()) controller.abort(); controllers.clear()
    for (const row of rows.value) URL.revokeObjectURL(row.previewUrl)
    for (const url of Object.values(contentUrls.value)) URL.revokeObjectURL(url)
    rows.value = []; contentUrls.value = {}; contentBusy.value = {}; contentErrors.value = {}; policy.value = null; policyError.value = null; policyLoading.value = false
  }
  function reconcile(attachments: readonly ReportPhoto[]) {
    for (const row of rows.value) {
      const attached = attachments.find(file => file.id === row.record?.id)
      if (attached && attached.tenant_id === context().tenantId && attached.created_by === context().actorId) row.record = attached
    }
  }
  function releaseContent() {
    for (const [key, controller] of controllers) if (typeof key === 'string' && key.startsWith('content:')) { controller.abort(); controllers.delete(key) }
    for (const url of Object.values(contentUrls.value)) URL.revokeObjectURL(url)
    contentUrls.value = {}; contentBusy.value = {}; contentErrors.value = {}
  }
  function revokeContent(id: string) {
    const key = `content:${id}`
    controllers.get(key)?.abort(); controllers.delete(key)
    if (contentUrls.value[id]) URL.revokeObjectURL(contentUrls.value[id]!)
    delete contentUrls.value[id]; contentBusy.value[id] = false; contentErrors.value[id] = null
  }
  // A same-scope session refresh must retain the original File, preview and upload key.
  const stopContextWatch = watch([() => contextGeneration.value, () => session.value?.tenant.id, () => session.value?.user.id, () => session.value?.user.role, () => session.value?.permission_version], clear, { flush: 'sync' })
  function dispose() { stopContextWatch(); clear() }
  return { rows, policy, policyLoading, policyError, contentUrls, contentBusy, contentErrors, loadPolicy, add, canRemove, remove, upload, refresh, readyIds, view, reconcile, releaseContent, revokeContent, clear, dispose }
}
export type PhotoQueue = ReturnType<typeof createPhotoQueue>
