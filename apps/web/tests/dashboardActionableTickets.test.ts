import { describe, expect, it, vi } from 'vitest'
import { actionableClockLabel, createActionableTickets, emptyActionableTickets, readActionableTickets } from '../src/utils/dashboardActionableTickets'
import { actionableClock, actionableContext as context, actionableId as id, actionableItem, actionablePage, actionableSnapshot, unknownActionableClock } from './dashboardActionable.fixture'

describe('complete actionable population and paginated SLA evidence', () => {
  it('accepts both persisted workflow models and keeps pending customer actionable', () => {
    const raw = actionableSnapshot({ items: [actionableItem(), actionableItem(19, 'LIFECYCLE_V42', 'PENDING_CUSTOMER')] })
    const result = readActionableTickets(raw, context)
    expect(result).toEqual(raw)
    expect(result).not.toBe(raw)
    expect(result.items[1]?.state).toBe('PENDING_CUSTOMER')
  })
  it.each(['WORKFLOW_V33', 'LIFECYCLE_V42'] as const)('rejects resolved and closed rows for %s instead of silently filtering the top five', model => {
    for (const state of ['RESOLVED', 'CLOSED', 'CANCELLED']) {
      expect(() => readActionableTickets(actionableSnapshot({ items: [actionableItem(20, model, state), actionableItem(19)] }), context)).toThrow()
    }
  })
  it('accepts server-selected first five replacements and every later page without truncating the total', () => {
    const first = actionablePage(1, 5, 42)
    expect(readActionableTickets(first, context).items.map(item => item.ticket_id)).toEqual([500, 499, 498, 497, 496].map(id))
    const all = Array.from({ length: 9 }, (_, index) => readActionableTickets(actionablePage(index + 1, 5, 42), context, index + 1).items).flat()
    expect(new Set(all.map(item => item.ticket_id)).size).toBe(42)
    expect(() => readActionableTickets({ ...first, items: first.items.slice(0, 4) }, context)).toThrow()
  })
  it.each(['viewer', 'tadmin'] as const)('requires reporter-only scope for %s', role => {
    expect(readActionableTickets(actionableSnapshot({ scope: 'REPORTER_ONLY' }), { ...context, role }).scope).toBe('REPORTER_ONLY')
    expect(() => readActionableTickets(actionableSnapshot(), { ...context, role })).toThrow()
  })
  it('keeps verified zero, missing workflow, and missing SLA separate', () => {
    const empty = actionablePage(1, 5, 0)
    expect(readActionableTickets(empty, context).pending_tickets).toBe(0)
    expect(readActionableTickets(empty, context).overdue_sla).toBe(0)
    const partial = actionableSnapshot({ registrations: 4, unconfigured: 1, pending_tickets: null, overdue_sla: null,
      unknown_sla: 1, unavailable_reasons: ['REGISTRATIONS_WITHOUT_WORKFLOW'] })
    expect(readActionableTickets(partial, context).pending_tickets).toBeNull()
    const unknown = actionableSnapshot({ overdue_sla: null, unknown_sla: 1, unavailable_reasons: ['SLA_INPUTS_OR_READER_UNAVAILABLE'],
      items: [actionableItem(20, 'WORKFLOW_V33', 'OPEN', { overdue: null, response_clock: unknownActionableClock() }), actionableItem(19)] })
    expect(readActionableTickets(unknown, context).pending_tickets).toBe(2)
    expect(readActionableTickets(unknown, context).overdue_sla).toBeNull()
  })
  it('accepts a known breach when the other clock is unknown and rejects false complete totals', () => {
    const row = actionableItem(20, 'WORKFLOW_V33', 'OPEN', { overdue: true, response_clock: unknownActionableClock(), recovery_clock: actionableClock({ breached: true, status: 'BREACHED', remaining_millis: 0 }) })
    const raw = actionableSnapshot({ overdue_sla: 1, known_overdue: 1, items: [row, actionableItem(19)] })
    expect(readActionableTickets(raw, context).items[0]?.overdue).toBe(true)
    for (const patch of [{ overdue_sla: 0 }, { known_overdue: 0 }, { unknown_sla: 1 }, { evaluated_pending: 1 }, { unevaluated_pending: 1 }, { evaluation_limit: 100 }]) {
      expect(() => readActionableTickets({ ...raw, ...patch }, context)).toThrow()
    }
  })
  it('retains the shared cancelled-clock schema as unknown without accepting a cancelled ticket state', () => {
    const cancelled = actionableClock({ status: 'CANCELLED', breached: null, missing_reason: null, remaining_millis: null, remaining_basis: null })
    const raw = actionableSnapshot({ overdue_sla: null, unknown_sla: 1, unavailable_reasons: ['SLA_INPUTS_OR_READER_UNAVAILABLE'],
      items: [actionableItem(20, 'WORKFLOW_V33', 'OPEN', { overdue: null, response_clock: cancelled }), actionableItem(19)] })
    expect(readActionableTickets(raw, context).items[0]?.response_clock.status).toBe('CANCELLED')
    expect(actionableClockLabel(cancelled)).toBe('已取消 · SLA未知')
    expect(() => readActionableTickets({ ...raw, items: [{ ...raw.items[0]!, state: 'CANCELLED' }, raw.items[1]!] }, context)).toThrow()
  })
  it.each([
    { tenant_id: id(9) }, { actor_id: id(9) }, { permission_version: 8 }, { scope: 'REPORTER_ONLY' }, { source: 'fixture' },
    { metric_version: 'MOP_VISIBLE_NONTERMINAL_TICKETS_V1' }, { projection_version: 'MOP_PENDING_TICKETS_SLA_V2' },
    { excluded_states: ['CLOSED', 'CANCELLED'] }, { registrations: 4 }, { excluded: -1 }, { unconfigured: 1 },
    { page: 2 }, { page_size: 20 }, { total_pages: 2 }, { item_limit: 20 }, { observed_at: '2026-02-30T00:00:00Z' },
    { known_pending: 9007199254740991 }, { writes_performed: true }, { extra: true }, { unavailable_reasons: ['EVALUATION_LIMIT_EXCEEDED'] },
  ])('rejects mismatched or incomplete metadata %j', patch => {
    expect(() => readActionableTickets({ ...actionableSnapshot(), ...patch }, context)).toThrow()
  })
  it.each([
    { sla_version: 2 }, { overdue: true }, { version: 0 }, { state: 'NEW' }, { title: '' }, { ticket_id: 'bad-id' },
    { registered_at: '2026-10-24T00:00:00Z' }, { unexpected: true },
  ])('rejects invalid or mismatched item evidence %j', patch => {
    expect(() => readActionableTickets(actionableSnapshot({ items: [{ ...actionableItem(), ...patch } as any, actionableItem(19)] }), context)).toThrow()
  })
  it.each([
    { breached: 'false' }, { status: 'NORMAL' }, { remaining_millis: -1 }, { remaining_millis: 0, remaining_basis: null },
    { remaining_millis: null }, { completed_at: '2026-10-24T00:00:00Z' }, { status: 'BREACHED' },
    { status: 'CONFIG_PENDING' }, { breached: null, missing_reason: null }, { deadline_at: '2026-02-30T00:00:00Z' },
  ])('rejects malformed clock data %j', patch => {
    expect(() => readActionableTickets(actionableSnapshot({ items: [actionableItem(20, 'WORKFLOW_V33', 'OPEN', { response_clock: actionableClock(patch as any) }), actionableItem(19)] }), context)).toThrow()
  })
  it('preserves exact ordering and rejects duplicate, reversed and nanosecond-reordered rows', () => {
    for (const items of [[actionableItem(), actionableItem()], [actionableItem(19), actionableItem()], [actionableItem(), { ...actionableItem(19), registered_at: '2026-10-22T12:00:00.000000002Z' }]]) {
      expect(() => readActionableTickets(actionableSnapshot({ items }), context)).toThrow()
    }
  })
  it('accepts an empty page after population shrink while validating its new complete total', () => {
    expect(readActionableTickets(actionablePage(3, 20, 2), context, 3, 20).items).toEqual([])
  })
})

describe('server-only SLA display', () => {
  it('does not infer breach from a past deadline or browser time', () => {
    expect(actionableClockLabel(actionableClock({ deadline_at: '2000-01-01T00:00:00Z', remaining_millis: 0 }))).toBe('剩余 0秒')
    expect(actionableClockLabel(actionableClock({ remaining_millis: 61_000 }))).toBe('剩余 1分1秒')
    expect(actionableClockLabel(actionableClock({ remaining_millis: 3_600_000 }))).toBe('剩余 1小时0分')
  })
  it('labels breached, unknown, paused and completed clocks explicitly', () => {
    expect(actionableClockLabel(actionableClock({ breached: true, status: 'BREACHED' }))).toBe('已超时')
    expect(actionableClockLabel(unknownActionableClock())).toContain('未知')
    expect(actionableClockLabel(actionableClock({ status: 'PAUSED', remaining_millis: 0 }))).toContain('已暂停')
    expect(actionableClockLabel(actionableClock({ status: 'COMPLETED', completed_at: '2026-10-23T11:00:00Z' }))).toContain('已停钟')
    expect(actionableClockLabel(actionableClock({ remaining_millis: null, remaining_basis: null }))).toBe('剩余未知')
  })
})

function harness(answer: () => unknown | Promise<unknown>, pageSize = 5) {
  let state = emptyActionableTickets(pageSize)
  const request = vi.fn(async () => answer())
  const workspace = createActionableTickets({ request } as any, next => { state = next }, pageSize)
  workspace.reset(context)
  return { workspace, request, state: () => state }
}
describe('actionable read ownership and original-page recovery', () => {
  it('issues only the bounded GET and retries the original failed page', async () => {
    let failed = false
    const h = harness(() => { if (failed) throw Error('synthetic outage'); return actionablePage(2, 20) }, 20)
    expect(await h.workspace.refresh(2)).toBe(true)
    failed = true
    expect(await h.workspace.refresh()).toBe(false)
    expect(h.state().current).toBeNull()
    failed = false
    expect(await h.workspace.retry()).toBe(true)
    expect(h.request.mock.calls).toHaveLength(3)
    for (const call of h.request.mock.calls as unknown[][]) expect(call).toEqual(['/dashboard/actionable-tickets?page=2&page_size=20', expect.objectContaining({ method: 'GET', signal: expect.any(AbortSignal) })])
  })
  it('prevents busy double-dispatch and suppresses late results after cancellation', async () => {
    let resolve!: (value: unknown) => void
    const h = harness(() => new Promise(done => { resolve = done }))
    const waiting = h.workspace.refresh()
    expect(await h.workspace.refresh(2)).toBe(false)
    h.workspace.cancel(); resolve(actionableSnapshot())
    expect(await waiting).toBe(false)
    expect(h.state().current).toBeNull()
    expect(h.state().cancelled).toBe(true)
  })
  it('clears ownership and retry authority on scope revocation and disposal', async () => {
    let resolve!: (value: unknown) => void
    const h = harness(() => new Promise(done => { resolve = done }))
    const waiting = h.workspace.refresh()
    h.workspace.reset(null); resolve(actionableSnapshot()); await waiting
    expect(h.state()).toEqual(emptyActionableTickets())
    expect(await h.workspace.retry()).toBe(false)
    h.workspace.dispose(); h.workspace.reset(context)
    expect(await h.workspace.refresh()).toBe(false)
  })
  it('rechecks ownership after synchronous callbacks and rejects invalid requested pages before dispatch', async () => {
    const request = vi.fn()
    let workspace: ReturnType<typeof createActionableTickets> | undefined
    workspace = createActionableTickets({ request } as any, state => { if (state.busy) workspace!.reset(null) })
    workspace.reset(context)
    expect(await workspace.refresh()).toBe(false)
    expect(request).not.toHaveBeenCalled()
    const h = harness(() => actionableSnapshot())
    for (const page of [0, -1, 1.5, NaN, 9007199254740990]) expect(await h.workspace.refresh(page)).toBe(false)
    expect(h.request).not.toHaveBeenCalled()
  })
  it('rejects an older snapshot across page requests and clears detached results', async () => {
    let older = false
    const h = harness(() => actionableSnapshot({ observed_at: older ? '2026-10-23T11:59:59Z' : '2026-10-23T12:00:00Z' }))
    await h.workspace.refresh()
    h.state().current!.items.length = 0
    await h.workspace.refresh()
    expect(h.state().current?.items).toHaveLength(2)
    older = true
    expect(await h.workspace.refresh()).toBe(false)
    expect(h.state().error).toBeTruthy()
  })
})
