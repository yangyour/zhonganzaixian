import type { ReportReadership } from '../src/utils/reportReadership'

export const tenant = '79000000-0000-4000-8000-000000000001'
export const reportId = '79000000-0000-4000-8000-0000000003e8'
export const id = (number: number) => `79000000-0000-4000-8000-${number.toString(16).padStart(12, '0')}`
export const expected = { tenant, id: reportId, hash: 'b'.repeat(64), version: 5, filter: 'ALL' as const, after: null }
export function readership(count = 52): ReportReadership {
  return { tenant_id: tenant, document_id: reportId, document_sha256: expected.hash, state_version: 5,
    published_at: '2026-10-15T00:00:00Z', observed_at: '2026-10-24T00:00:00Z', filter: 'ALL',
    total_recipients: count, read_count: 1, unread_count: count - 1, followup_due_count: count - 1, matching_count: count,
    items: Array.from({ length: Math.min(count, 50) }, (_, index) => ({ customer_id: id(index + 1),
      display_name: `SYNTHETIC 接收人 ${index + 1}`, first_read_at: index === 0 ? '2026-10-16T00:00:00Z' : null,
      followup_due: index !== 0 })), next_after: count > 50 ? id(50) : null }
}
