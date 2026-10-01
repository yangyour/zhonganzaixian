import { markRaw, ref } from 'vue'
import { api, ApiError, isCancelled } from '../api/client'
import type { CustomerReportAcceptance, FileContentGrant, FileUploadOperation, ReportPhoto, ReportPhotoPolicy } from '../api/types'
import { session } from '../stores/session'
import { isTicketUuid } from './ticket'
import { fileContentPath, fileIdFromResultRef, photoMaxBytes, photoSelectionError, preparePhotoAttempt, readyPhotoIds, type PhotoUploadAttempt } from './photo'

export interface PhotoSelection {
  uid: number; file: File; previewUrl: string; attempt: PhotoUploadAttempt | null
  status: 'SELECTED' | 'UPLOADING' | 'UNCERTAIN' | 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'
  operationId: string | null; record: ReportPhoto | null; uploading: boolean; reading: boolean; error: unknown; message: string; generation: number
}
export function createPhotoQueue() {
  const rows = ref<PhotoSelection[]>([]), policy = ref<ReportPhotoPolicy | null>(null), policyLoading = ref(false), policyError = ref<unknown>(null)
  const contentUrls = ref<Record<string, string>>({}), contentBusy = ref<Record<string, boolean>>({}), contentErrors = ref<Record<string, unknown>>({})
  const controllers = new Map<number | string, AbortController>()
  let generation = 0
  const current = (row: PhotoSelection, version: number, epoch: number) => epoch === generation && row.generation === version && rows.value.some(item => item.uid === row.uid)
  async function loadPolicy() {
    controllers.get('policy')?.abort(); const controller = new AbortController(); controllers.set('policy', controller)
    const epoch = generation
    policyLoading.value = true; policyError.value = null
    try { const result = await api.request<ReportPhotoPolicy>('/files/policy', { signal: controller.signal }); if (epoch === generation && !controller.signal.aborted) policy.value = result }
    catch (cause) { if (!isCancelled(cause) && epoch === generation) { policy.value = null; policyError.value = cause } }
    finally { if (epoch === generation && !controller.signal.aborted) policyLoading.value = false }
  }
  function add(file: File, uid: number, existingCount: number) {
    const error = photoSelectionError(file, policy.value, existingCount + rows.value.length)
    if (error) return error
    rows.value.push({ uid, file: markRaw(file), previewUrl: URL.createObjectURL(file), attempt: null, status: 'SELECTED', operationId: null, record: null, uploading: false, reading: false, error: null, message: '', generation: 0 })
    return null
  }
  function remove(uid: number) {
    const row = rows.value.find(item => item.uid === uid)
    if (!row || row.uploading || row.status === 'UNCERTAIN') return
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
        const operation = await api.request<FileUploadOperation>(`/operations/${encodeURIComponent(row.operationId)}`, { signal: controller.signal })
        if (!current(row, version, epoch)) return
        const fileId = fileIdFromResultRef(operation.result_ref)
        if (operation.kind !== 'FILE_UPLOAD' || operation.id.toLowerCase() !== row.operationId || !['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(operation.status) || !fileId) throw new ApiError(200, 'INVALID_UPLOAD_RESULT', '上传结果返回异常，请手动重查，不会再次上传', '—')
        row.status = operation.status === 'RUNNING' ? 'RUNNING' : operation.status === 'FAILED' ? 'FAILED' : 'PENDING'
        if (operation.error) row.message = operation.error.message
        const record = await api.request<ReportPhoto>(`/files/${fileId}`, { signal: controller.signal })
        if (!current(row, version, epoch)) return
        if (record.id.toLowerCase() !== fileId || record.operation_id.toLowerCase() !== row.operationId) throw new ApiError(200, 'INVALID_UPLOAD_RESULT', '文件详情与上传操作不一致，请手动重查', '—')
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
    row.attempt = preparePhotoAttempt(row.attempt, row.file)
    const controller = new AbortController(); controllers.get(uid)?.abort(); controllers.set(uid, controller)
    const epoch = generation, version = ++row.generation
    const form = new FormData(); form.append('file', row.file, row.file.name); form.append('purpose', 'customer_report_photo')
    row.uploading = true; row.status = 'UPLOADING'; row.error = null
    try {
      const accepted = await api.request<CustomerReportAcceptance>('/files', { method: 'POST', body: form, idempotencyKey: row.attempt.key, signal: controller.signal })
      if (!current(row, version, epoch)) return
      if (!isTicketUuid(accepted.operation_id) || accepted.status !== 'PENDING') throw new ApiError(200, 'INVALID_RESPONSE', '上传响应无法确认，请使用原文件与请求标识重试', '—')
      row.attempt.state = 'accepted'; row.operationId = accepted.operation_id.toLowerCase(); row.status = 'PENDING'; row.uploading = false
      void refresh(uid, true)
    } catch (cause) {
      if (!isCancelled(cause) && current(row, version, epoch)) {
        const uncertain = cause instanceof ApiError && (cause.status === 0 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE')
        row.attempt.state = uncertain ? 'uncertain' : 'ready'; row.status = uncertain ? 'UNCERTAIN' : 'SELECTED'; row.error = cause
      }
    } finally { if (current(row, version, epoch)) row.uploading = false }
  }
  function readyIds(ticketId: string | null = null) {
    return readyPhotoIds(rows.value.filter(row => row.status === 'READY' && row.record).map(row => row.record!), { tenantId: session.value?.tenant.id || '', actorId: session.value?.user.id || '' }, ticketId)
  }
  async function view(record: ReportPhoto) {
    if (contentBusy.value[record.id] || record.upload_status !== 'READY') return
    const controller = new AbortController(), key = `content:${record.id}`; controllers.get(key)?.abort(); controllers.set(key, controller)
    const epoch = generation
    contentBusy.value[record.id] = true; contentErrors.value[record.id] = null
    try {
      const grant = await api.request<FileContentGrant>(`/files/${encodeURIComponent(record.id)}/content-grants`, { method: 'POST', body: {}, signal: controller.signal })
      if (epoch !== generation || controller.signal.aborted) return
      const path = fileContentPath(grant.content_url, record.id)
      if (!path) throw new ApiError(200, 'INVALID_CONTENT_REFERENCE', '图片授权地址返回异常，未打开外部地址', '—')
      const blob = await api.requestBlob(path, { signal: controller.signal })
      if (epoch !== generation || controller.signal.aborted) return
      if (!['image/jpeg', 'image/png'].includes(blob.type) || blob.type !== record.media_type || blob.size < 1 || blob.size > photoMaxBytes) throw new ApiError(200, 'INVALID_IMAGE_CONTENT', '图片内容返回异常，未显示为成功读取', '—')
      if (contentUrls.value[record.id]) URL.revokeObjectURL(contentUrls.value[record.id]!)
      contentUrls.value[record.id] = URL.createObjectURL(blob)
    } catch (cause) { if (!isCancelled(cause) && epoch === generation) contentErrors.value[record.id] = cause }
    finally { if (epoch === generation) contentBusy.value[record.id] = false }
  }
  function clear() {
    ++generation; for (const controller of controllers.values()) controller.abort(); controllers.clear()
    for (const row of rows.value) URL.revokeObjectURL(row.previewUrl)
    for (const url of Object.values(contentUrls.value)) URL.revokeObjectURL(url)
    rows.value = []; contentUrls.value = {}; contentBusy.value = {}; contentErrors.value = {}; policy.value = null; policyError.value = null; policyLoading.value = false
  }
  return { rows, policy, policyLoading, policyError, contentUrls, contentBusy, contentErrors, loadPolicy, add, remove, upload, refresh, readyIds, view, clear }
}
export type PhotoQueue = ReturnType<typeof createPhotoQueue>
