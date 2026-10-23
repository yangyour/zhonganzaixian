import {describe, expect, it} from 'vitest'
import {monitoringRecurrenceLabel} from '../src/utils/monitoringRecurrence'
import {eventLabel} from '../src/utils/ticketNotifications'

describe('durable monitoring recurrence display', () => {
  const event = '10000000-0000-4000-8000-000000000001'
  it.each([50, 80, 100])('labels recovery %s without recalculating a deadline', percent => {
    expect(monitoringRecurrenceLabel(`recovery:${percent}:monitor:${event}:3`)).toBe(`恢复 SLA ${percent}% · 故障周期 3`)
  })
  it('keeps legacy and malformed keys as raw evidence', () => {
    for (const key of ['recovery:50', 'response:50', `recovery:50:monitor:${event}:01`, `recovery:50:monitor:${event}:2147483648`]) {
      expect(monitoringRecurrenceLabel(key)).toBeNull()
    }
  })
  it('distinguishes a monitor recurrence from customer reopen', () => {
    expect(eventLabel('ticket.monitoring_recurred')).toBe('监控故障复发')
    expect(eventLabel('ticket.reopened')).toBe('客户重开')
  })
})
