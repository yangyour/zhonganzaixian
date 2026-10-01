import { computed, ref } from 'vue'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { createPhotoAppender } from './photoAttachments'
import type { PhotoQueue } from './photoQueue'

/** Retain the original ticket while any local photo or write outcome is unresolved. */
export function createTicketPhotoWorkspace(queue: PhotoQueue, client: Pick<typeof api, 'request'> = api) {
  const targetId = ref(''), ticket = ref<RegisteredTicket | null>(null)
  const appender = createPhotoAppender(ticket, queue, client)
  const unresolved = computed(() => appender.busy.value || appender.reading.value || appender.uncertain.value || queue.rows.value.some(row => !queue.canRemove(row.uid) || !ticket.value?.attachments.some(photo => photo.id === row.record?.id)))
  const canDiscard = computed(() => !appender.busy.value && !appender.reading.value && !appender.uncertain.value && queue.rows.value.every(row => queue.canRemove(row.uid)))
  function replaceTarget(id: string) {
    queue.clear(); appender.reset(); ticket.value = null; targetId.value = id
  }
  function visit(id: string) {
    if (id !== targetId.value) {
      queue.releaseContent()
      if (!unresolved.value) replaceTarget(id)
    }
    return id === targetId.value
  }
  function accept(result: RegisteredTicket) {
    if (result.id !== targetId.value) return false
    ticket.value = result; queue.reconcile(result.attachments)
    return true
  }
  function discard(nextId: string) {
    if (!canDiscard.value) return false
    replaceTarget(nextId)
    return true
  }
  function reset() { replaceTarget('') }
  function dispose() { appender.dispose() }
  return { targetId, ticket, appender, unresolved, canDiscard, visit, accept, discard, reset, dispose }
}
