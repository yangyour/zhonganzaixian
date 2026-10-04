import { ApiError } from '../api/client'
import type { RegisteredTicket, ReportPhoto, TicketPage } from '../api/types'
import { isTicketUuid, ticketCategories } from './ticket'
import { utcInstantNanos, utcNotBefore } from './utcInstant'

export interface TicketReadContext { tenantId: string; actorId: string; role: string; permissionVersion: number }
const ticketKeys = ['id','tenant_id','receipt_no','title','description','source','category','impact_scope','urgency','asset_id','asset_name','reporter_id','contact_name','contact_phone','contact_binding_status','registration_status','state','external_id','external_state','sync_state','authority_source','authority_status','priority','response_due_at','recovery_due_at','operation_id','version','created_at','updated_at','attachments','history']
const photoKeys = ['id','tenant_id','purpose','scan_status','retention_until','production_approval','policy_source','original_name','media_type','size_bytes','sha256','upload_status','created_by','ticket_id','operation_id','version','created_at','updated_at','source']
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const uuid = (value: unknown): value is string => isTicketUuid(value) && value === value.toLowerCase()
const bad = () => new ApiError(200, 'INVALID_TICKET_READ_RESPONSE', '报障登记范围、来源或状态回读异常，未显示未经核对的结果，请重试', '—')
function checkContext(context: TicketReadContext) {
  if (!uuid(context.tenantId) || !uuid(context.actorId) || !positive(context.permissionVersion) || !['padmin','msp','tadmin','viewer'].includes(context.role)) throw bad()
}
function readPhoto(value: unknown, ticket: Record<string, unknown>): ReportPhoto {
  if (!exact(value, photoKeys) || !uuid(value.id) || !uuid(value.operation_id) || value.tenant_id !== ticket.tenant_id || value.created_by !== ticket.reporter_id || value.ticket_id !== ticket.id || value.purpose !== 'customer_report_photo' || value.scan_status !== 'UNSCANNED' || value.retention_until !== null || value.production_approval !== 'UNVERIFIED' || value.policy_source !== 'USER_DEVELOPMENT_DIRECTION' || value.source !== 'postgresql' || value.upload_status !== 'READY' || typeof value.media_type !== 'string' || !['image/jpeg','image/png'].includes(value.media_type) || typeof value.original_name !== 'string' || !value.original_name.length || Array.from(value.original_name).length > 200 || !positive(value.size_bytes) || value.size_bytes > 5_000_000 || typeof value.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.sha256) || !positive(value.version) || !utcNotBefore(value.updated_at, value.created_at)) throw bad()
  return structuredClone(value) as unknown as ReportPhoto
}
/** Validate the registration-only projection. This is defensive presentation, not a substitute for server authorization. */
export function readRegisteredTicket(value: unknown, context: TicketReadContext, expectedId?: string): RegisteredTicket {
  checkContext(context)
  if (!exact(value, ticketKeys) || !['id','reporter_id','operation_id'].every(key => uuid(value[key])) || value.tenant_id !== context.tenantId || expectedId !== undefined && (!isTicketUuid(expectedId) || value.id !== expectedId.toLowerCase()) || ['viewer','tadmin'].includes(context.role) && value.reporter_id !== context.actorId || !['receipt_no','title','description','contact_name','contact_phone'].every(key => typeof value[key] === 'string') || value.source !== 'customer_report' || !ticketCategories.includes(value.category as RegisteredTicket['category']) || typeof value.impact_scope !== 'string' || !['company','partial_area','individual'].includes(value.impact_scope) || typeof value.urgency !== 'string' || !['normal','urgent'].includes(value.urgency) || !(value.asset_id === null || uuid(value.asset_id)) || !(value.asset_name === null || typeof value.asset_name === 'string') || value.contact_binding_status !== 'UNVERIFIED' || value.registration_status !== 'REGISTERED' || value.sync_state !== 'NOT_APPLICABLE' || value.authority_source !== 'MOP' || value.authority_status !== 'RULES_PENDING' || !['state','external_id','external_state','priority','response_due_at','recovery_due_at'].every(key => value[key] === null) || !positive(value.version) || !utcNotBefore(value.updated_at, value.created_at) || !Array.isArray(value.attachments) || value.attachments.length > 3 || !Array.isArray(value.history) || value.history.length !== value.version) throw bad()
  let previous = value.created_at
  for (const [index, event] of value.history.entries()) {
    if (!exact(event, ['action','actor_id','occurred_at','version']) || event.action !== (index === 0 ? 'REGISTER' : 'ADD_ATTACHMENTS') || event.actor_id !== value.reporter_id || event.version !== index + 1 || !utcNotBefore(event.occurred_at, previous) || !utcNotBefore(value.updated_at, event.occurred_at)) throw bad()
    if (index === 0 && utcInstantNanos(event.occurred_at) !== utcInstantNanos(value.created_at)) throw bad()
    previous = event.occurred_at
  }
  if (utcInstantNanos(previous) !== utcInstantNanos(value.updated_at)) throw bad()
  const attachments = value.attachments.map(photo => readPhoto(photo, value))
  if (new Set(attachments.map(photo => photo.id)).size !== attachments.length) throw bad()
  return structuredClone({ ...value, attachments }) as unknown as RegisteredTicket
}
/** Cursor remains opaque: only the API can verify its signature and authorization binding. */
export function readTicketPage(value: unknown, context: TicketReadContext, pageSize: number, filters: { category?: string; urgency?: string } = {}): TicketPage {
  checkContext(context)
  if (![10,15,25,50,100].includes(pageSize) || !exact(value, ['items','next_cursor','has_more','source','page_size_limit','page_size_limit_kind']) || !Array.isArray(value.items) || value.items.length > pageSize || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.page_size_limit !== 100 || value.page_size_limit_kind !== 'test_technical_limit') throw bad()
  if (value.has_more ? value.items.length !== pageSize || typeof value.next_cursor !== 'string' || !value.next_cursor.length || value.next_cursor.length > 4096 || /[\s\u0000-\u001f\u007f]/.test(value.next_cursor) : value.next_cursor !== null) throw bad()
  const items = value.items.map(item => readRegisteredTicket(item, context))
  const ids = new Set<string>()
  let previous: RegisteredTicket | undefined
  for (const item of items) {
    if (ids.has(item.id) || filters.category && item.category !== filters.category || filters.urgency && item.urgency !== filters.urgency) throw bad()
    if (previous) {
      const time = utcInstantNanos(item.created_at)!, before = utcInstantNanos(previous.created_at)!
      if (time < before || time === before && item.id <= previous.id) throw bad()
    }
    ids.add(item.id); previous = item
  }
  return structuredClone({ ...value, items }) as unknown as TicketPage
}
