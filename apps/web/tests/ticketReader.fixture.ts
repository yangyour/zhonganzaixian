// Explicitly synthetic pure/frontend-test input, never an integration substitute.
import type { RegisteredTicket, ReportPhoto, TicketPage } from '../src/api/types'
import type { TicketReadContext } from '../src/utils/ticketReader'
export const id = (n: number) => `78000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
export const context: TicketReadContext = { tenantId: id(1), actorId: id(2), role: 'viewer', permissionVersion: 7 }
export const time = '2026-10-04T01:00:00.123456789Z'
export function ticket(n = 10): RegisteredTicket {
  return { id: id(n), tenant_id: id(1), receipt_no: 'REG-'+id(n), title: '合成报障', description: '纯前端测试描述\n不是联调证据', source: 'customer_report', category: '网络不通', impact_scope: 'company', urgency: 'normal', asset_id: null, asset_name: null, reporter_id: id(2), contact_name: '合成联系人', contact_phone: '000000', contact_binding_status: 'UNVERIFIED', registration_status: 'REGISTERED', state: null, external_id: null, external_state: null, sync_state: 'NOT_APPLICABLE', authority_source: 'MOP', authority_status: 'RULES_PENDING', priority: null, response_due_at: null, recovery_due_at: null, operation_id: id(n+1000), version: 1, created_at: time, updated_at: time, attachments: [], history: [{ action: 'REGISTER', actor_id: id(2), occurred_at: time, version: 1 }] }
}
export function photo(n = 30): ReportPhoto { return { id: id(n), tenant_id: id(1), purpose: 'customer_report_photo', original_name: '合成照片.png', media_type: 'image/png', size_bytes: 100, sha256: 'a'.repeat(64), upload_status: 'READY', scan_status: 'UNSCANNED', retention_until: null, production_approval: 'UNVERIFIED', policy_source: 'USER_DEVELOPMENT_DIRECTION', created_by: id(2), ticket_id: id(10), operation_id: id(n+1000), version: 3, created_at: time, updated_at: '2026-10-04T01:00:01Z', source: 'postgresql' } }
export function page(items = [ticket()], more = false): TicketPage { return { items, has_more: more, next_cursor: more ? 'opaque.signed-cursor' : null, source: 'postgresql', page_size_limit: 100, page_size_limit_kind: 'test_technical_limit' } }
