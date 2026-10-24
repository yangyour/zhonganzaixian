import { ApiError } from '../api/client'

export type ReadershipFilter = 'ALL' | 'UNREAD' | 'FOLLOWUP_DUE'
export interface ReportReadership {
  tenant_id: string
  document_id: string
  document_sha256: string
  state_version: number
  published_at: string
  observed_at: string
  filter: ReadershipFilter
  total_recipients: number
  read_count: number
  unread_count: number
  followup_due_count: number
  matching_count: number
  items: { customer_id: string; display_name: string | null; first_read_at: string | null; followup_due: boolean }[]
  next_after: string | null
}

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const invalid = () => new ApiError(200, 'INVALID_REPORT_READERSHIP', '报告阅读统计未通过范围或计数核对', '—')

/** Validate the entire page before displaying personal readership or applying its cursor. */
export function readReportReadership(raw: unknown, expected: {
  tenant: string; id: string; hash: string; version: number; filter: ReadershipFilter; after: string | null
}): ReportReadership {
  if (!object(raw) || raw.tenant_id !== expected.tenant || raw.document_id !== expected.id
    || raw.document_sha256 !== expected.hash || raw.state_version !== expected.version || raw.filter !== expected.filter
    || !date(raw.published_at) || !date(raw.observed_at) || Date.parse(raw.observed_at) < Date.parse(raw.published_at)
    || !Array.isArray(raw.items) || raw.items.length > 50) throw invalid()
  const counts = ['total_recipients', 'read_count', 'unread_count', 'followup_due_count', 'matching_count'] as const
  if (counts.some(key => !Number.isInteger(raw[key]) || (raw[key] as number) < 0 || (raw[key] as number) > 1000)) throw invalid()
  const total = raw.total_recipients as number, read = raw.read_count as number, unread = raw.unread_count as number
  const due = raw.followup_due_count as number, matching = raw.matching_count as number
  if (total < 1 || read + unread !== total || due > unread
    || matching !== (expected.filter === 'ALL' ? total : expected.filter === 'UNREAD' ? unread : due)
    || raw.items.length > matching) throw invalid()
  let previous = expected.after
  for (const item of raw.items) {
    if (!object(item) || !uuid(item.customer_id) || previous !== null && item.customer_id <= previous
      || item.display_name !== null && (typeof item.display_name !== 'string' || item.display_name.length > 300)
      || typeof item.followup_due !== 'boolean' || item.first_read_at !== null && !date(item.first_read_at)) throw invalid()
    const first = item.first_read_at as string | null
    const followup = first === null && Date.parse(raw.observed_at) - Date.parse(raw.published_at) > 7 * 86400000
    if (item.followup_due !== followup || first !== null && (Date.parse(first) < Date.parse(raw.published_at)
      || Date.parse(first) > Date.parse(raw.observed_at)) || expected.filter === 'UNREAD' && first !== null
      || expected.filter === 'FOLLOWUP_DUE' && !followup) throw invalid()
    previous = item.customer_id
  }
  if (raw.next_after !== null && (!uuid(raw.next_after) || raw.items.length !== 50 || raw.next_after !== previous)) throw invalid()
  if (expected.after === null && raw.items.length !== Math.min(50, matching)) throw invalid()
  if (expected.after === null && (raw.next_after !== null) !== (matching > 50)) throw invalid()
  return raw as unknown as ReportReadership
}
