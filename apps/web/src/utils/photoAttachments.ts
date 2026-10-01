import { computed, ref, watch, type Ref } from 'vue'
import { api, ApiError, isCancelled } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session } from '../stores/session'
import { prepareAttachmentAttempt, type AttachmentAttempt } from './photo'
import type { PhotoQueue } from './photoQueue'

/** A conflict requires an explicit read and comparison. Uncertain writes replay the original body and key. */
export function createPhotoAppender(ticket: Ref<RegisteredTicket | null>, queue: PhotoQueue, client: Pick<typeof api, 'request'> = api) {
  const attempt = ref<AttachmentAttempt | null>(null), busy = ref(false), error = ref<unknown>(null), conflict = ref(false)
  const comparison = ref(''), reading = ref(false)
  let generation = 0, controller: AbortController | undefined
  const permitted = computed(() => can('tickets:report') && ['viewer', 'tadmin'].includes(session.value?.user.role || '') && ticket.value?.tenant_id === session.value?.tenant.id && ticket.value?.reporter_id === session.value?.user.id)
  const uncertain = computed(() => attempt.value?.state === 'uncertain')
  const readyCount = computed(() => queue.readyIds(ticket.value?.id).filter(id => !ticket.value?.attachments.some(photo => photo.id.toLowerCase() === id)).length)
  function reset() { ++generation; controller?.abort(); attempt.value = null; busy.value = false; reading.value = false; error.value = null; conflict.value = false; comparison.value = '' }
  const stopContextWatch = watch(() => [contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version], reset, { flush: 'sync' })
  function validate(result: RegisteredTicket, id: string) {
    if (!result || result.id !== id || result.tenant_id !== session.value?.tenant.id || result.reporter_id !== session.value?.user.id || !Number.isSafeInteger(result.version) || result.version < 1 || !Array.isArray(result.attachments)) throw new ApiError(200, 'INVALID_ATTACHMENT_RESULT', '追加结果与当前登记不一致，请保留照片并查询结果', '—')
  }
  async function append() {
    if (!permitted.value || !ticket.value || busy.value || reading.value || conflict.value) return
    try {
      attempt.value = prepareAttachmentAttempt(attempt.value, ticket.value, queue.rows.value.filter(row => row.status === 'READY' && row.record).map(row => row.record!), { tenantId: session.value!.tenant.id, actorId: session.value!.user.id })
    } catch (cause) { error.value = new ApiError(0, 'PHOTO_INPUT_ERROR', (cause as Error).message, '—'); return }
    const original = attempt.value, epoch = ++generation
    controller?.abort(); controller = new AbortController(); const signal = controller.signal
    busy.value = true; error.value = null; comparison.value = ''
    try {
      const result = await client.request<RegisteredTicket>(`/tickets/${original.ticketId}/attachments`, { method: 'POST', body: original.body, idempotencyKey: original.key, signal })
      if (epoch !== generation || signal.aborted) return
      validate(result, original.ticketId)
      if (!original.body.attachment_ids.every(id => result.attachments.some(photo => photo.id.toLowerCase() === id))) throw new ApiError(200, 'INVALID_ATTACHMENT_RESULT', '回读未包含本次照片，请保留原请求并重查', '—')
      ticket.value = result; queue.reconcile(result.attachments); attempt.value = null
      comparison.value = `照片已关联至登记 v${result.version}；原描述与联系信息保持服务端记录。`
    } catch (cause) {
      if (!isCancelled(cause) && epoch === generation && !signal.aborted) {
        original.state = cause instanceof ApiError && (cause.status === 0 || cause.status >= 500 || ['INVALID_RESPONSE', 'INVALID_ATTACHMENT_RESULT'].includes(cause.code)) ? 'uncertain' : 'ready'
        conflict.value = cause instanceof ApiError && cause.status === 409 && cause.code === 'VERSION_CONFLICT'
        error.value = cause
      }
    } finally { if (epoch === generation) busy.value = false }
  }
  async function refreshComparison() {
    if (!permitted.value || !ticket.value || busy.value || reading.value) return
    const before = ticket.value, original = attempt.value, epoch = ++generation
    controller?.abort(); controller = new AbortController(); const signal = controller.signal
    reading.value = true; error.value = null
    try {
      const result = await client.request<RegisteredTicket>(`/tickets/${before.id}`, { signal })
      if (epoch !== generation || signal.aborted) return
      validate(result, before.id)
      ticket.value = result; queue.reconcile(result.attachments)
      const allIncluded = original?.body.attachment_ids.every(id => result.attachments.some(photo => photo.id.toLowerCase() === id))
      // Absence after GET does not establish that an uncertain write never committed.
      if (!original || original.state !== 'uncertain' || allIncluded) attempt.value = null
      conflict.value = false
      comparison.value = `已比较：v${before.version} → v${result.version}，现有照片 ${before.attachments.length} → ${result.attachments.length} 张。本地选择仍保留；请核对后手动追加。`
    } catch (cause) { if (!isCancelled(cause) && epoch === generation && !signal.aborted) error.value = cause }
    finally { if (epoch === generation) reading.value = false }
  }
  function dispose() { stopContextWatch(); reset() }
  return { busy, error, conflict, comparison, reading, permitted, uncertain, readyCount, attempt, append, refreshComparison, reset, dispose }
}
export type PhotoAppender = ReturnType<typeof createPhotoAppender>
