import { expect, it } from 'vitest'
import { ticketActivities, ticketDetailText, maskedContact } from '../src/utils/ticketDetailPresentation'
import { ticket, id } from './ticketReader.fixture'
import { row } from './ownWorklog.fixture'
import type { InternalNote } from '../src/utils/internalNoteWorkspace'
import type { TicketComment } from '../src/utils/ticketCommentWorkspace'

it('combines actual records in descending order without inferring formal stages from their text', () => {
  const registration = ticket()
  const notes = [{ id: id(21), actor_id: id(2), created_at: '2026-10-05T01:00:00Z', text: '原因定位：检查线路；等待复测。' }] as InternalNote[]
  const comments = [{ id: id(22), created_at: '2026-10-06T01:00:00Z', author_label: '报障人', text: '已恢复，请继续观察。' }] as TicketComment[]
  const items = ticketActivities(registration, notes, comments, id(2))
  expect(items.map(item => item.kind)).toEqual(['comment', 'note', 'registration'])
  expect(items[1]?.author).toBe('我 · 仅内部可见')
  expect(items[1]?.title).toBe('内部处理记录')
  expect(registration.state).toBeNull()
  expect(notes[0]?.text).toBe('原因定位：检查线路；等待复测。')
})

it('masks contact details and does not reveal short contact values', () => {
  expect(maskedContact('13800005678')).toBe('138****5678')
  expect(maskedContact('123')).toBe('••••')
})

it('exports readable plain text with the visible scope, masked phone and exact subminute durations', () => {
  const t = { ...ticket(), contact_phone: '13800005678', description: '<script>alert(1)</script>\n=SUM(A1)' }
  const text = ticketDetailText(t, '合成客户', ticketActivities(t, [], [], id(2)), [row({ elapsed_millis: 1501, note: '合成工作' })], '登记记录', '仅本人 · 第一页', '2026-10-15T00:00:00Z')
  expect(text).toContain('138****5678')
  expect(text).not.toContain('13800005678')
  expect(text).toContain('仅本人 · 第一页')
  expect(text).toContain('0 小时 0 分 1.501 秒')
  expect(text).toContain('<script>alert(1)</script>\n=SUM(A1)')
  expect(text).toContain('不是完整工单归档')
})

it('keeps an unread or unauthorized worklog scope distinct from an empty loaded page', () => {
  const exportRows = (rows: ReturnType<typeof row>[] | null) => ticketDetailText(ticket(), '合成客户', [], rows, '登记记录', '仅本人', 'now')
  expect(exportRows(null)).toContain('不能视为零')
  expect(exportRows(null)).toContain('本页累计：未读取')
  expect(exportRows([])).toContain('本页累计：本页无记录')
  expect(exportRows([row({ elapsed_millis: 0 })])).toContain('本页累计：0 小时 0 分 0 秒')
})
