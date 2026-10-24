import { describe, expect, it } from 'vitest'
import { readReportReadership } from '../src/utils/reportReadership'
import { expected, id, readership } from './reportReadership.fixture'

describe('published readership integrity', () => {
  it('accepts complete counts and stable recipient pages', () => {
    const page = readership()
    expect(readReportReadership(page, expected)).toEqual(page)
    const second = { ...page, items: [51, 52].map(value => ({ customer_id: id(value), display_name: null,
      first_read_at: null, followup_due: true })), next_after: null }
    expect(readReportReadership(second, { ...expected, after: id(50) }).items).toHaveLength(2)
  })
  it.each([
    ['tenant_id', id(99)], ['document_id', id(99)], ['document_sha256', 'c'.repeat(64)], ['state_version', 6],
    ['filter', 'UNREAD'], ['total_recipients', 51], ['read_count', 3], ['unread_count', -1],
    ['followup_due_count', 52], ['matching_count', 51], ['next_after', id(49)], ['observed_at', 'invalid'],
  ])('rejects mismatched %s', (key, value) => {
    expect(() => readReportReadership({ ...readership(), [key]: value }, expected)).toThrow()
  })
  it('rejects duplicate, unsorted, foreign, future and incorrectly classified receipts', () => {
    for (const mutate of [
      (page: ReturnType<typeof readership>) => { page.items[1] = { ...page.items[0] } },
      (page: ReturnType<typeof readership>) => { page.items.reverse() },
      (page: ReturnType<typeof readership>) => { page.items[1].first_read_at = '2026-10-25T00:00:00Z' },
      (page: ReturnType<typeof readership>) => { page.items[0].first_read_at = '2026-10-14T00:00:00Z' },
      (page: ReturnType<typeof readership>) => { page.items[1].followup_due = false },
    ]) {
      const page = readership(); mutate(page)
      expect(() => readReportReadership(page, expected)).toThrow()
    }
  })
  it('rejects nonmatching filter rows and page truncation', () => {
    const page = readership()
    expect(() => readReportReadership({ ...page, filter: 'UNREAD', matching_count: 51 }, { ...expected, filter: 'UNREAD' })).toThrow()
    expect(() => readReportReadership({ ...page, items: page.items.slice(0, 1) }, expected)).toThrow()
    expect(() => readReportReadership(page, { ...expected, after: id(49) })).toThrow()
  })
})
