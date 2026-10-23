import { describe, expect, it, vi } from 'vitest'
import { createPendingTickets, emptyPendingTickets, pendingTicketTarget, readPendingTickets } from '../src/utils/dashboardPendingTickets'
import { pendingContext as context, pendingId as id, pendingItem, pendingSnapshot } from './dashboardPending.fixture'

describe('authorized complete and partial pending snapshots', () => {
  it('accepts mixed live models and preserves the server total and order', () => {
    const raw = pendingSnapshot(), result = readPendingTickets(raw, context)
    expect(result).toEqual(raw)
    expect(result).not.toBe(raw)
    expect(result.items[1]?.workflow_model).toBe('LIFECYCLE_V42')
    expect(pendingTicketTarget(result.items[0]!)).toEqual({ path: '/ticket-workflows', query: { ticket_id: id(20) } })
    expect(pendingTicketTarget(result.items[1]!)).toEqual({ path: '/tickets', query: { ticket_id: id(19) } })
  })
  it.each(['viewer', 'tadmin'] as const)('requires reporter-only evidence for %s', role => {
    expect(readPendingTickets(pendingSnapshot({ scope: 'REPORTER_ONLY' }), { ...context, role }).scope).toBe('REPORTER_ONLY')
    expect(() => readPendingTickets(pendingSnapshot(), { ...context, role })).toThrow()
  })
  it('keeps a complete zero distinct from missing workflow registrations', () => {
    expect(readPendingTickets(pendingSnapshot({ registrations: 0, known_pending: 0, terminal: 0, pending_tickets: 0, items: [] }), context).pending_tickets).toBe(0)
    const partial = pendingSnapshot({ registrations: 4, unconfigured: 1, pending_tickets: null, state: 'PARTIAL', unavailable_reason: 'REGISTRATIONS_WITHOUT_WORKFLOW' })
    expect(readPendingTickets(partial, context).pending_tickets).toBeNull()
    expect(() => readPendingTickets({ ...partial, pending_tickets: 2 }, context)).toThrow()
  })
  it.each([
    { tenant_id: id(9) }, { actor_id: id(9) }, { permission_version: 8 }, { scope: 'REPORTER_ONLY' },
    { source: 'fixture' }, { metric_version: 'unknown' }, { item_limit: 6 }, { sort: 'TITLE' },
    { registrations: 4 }, { terminal: -1 }, { unconfigured: 1 }, { pending_tickets: 3 },
    { known_pending: 9007199254740991 }, { registrations: NaN }, { registrations: true },
    { observed_at: '2026-02-30T00:00:00Z' }, { overdue_sla: 0 }, { writes_performed: true }, { extra: true },
  ])('rejects ambiguous or out-of-scope metadata %j', patch => {
    expect(() => readPendingTickets({ ...pendingSnapshot(), ...patch }, context)).toThrow()
  })
  it('checks count arithmetic without floating-point overflow', () => {
    expect(() => readPendingTickets(pendingSnapshot({ registrations: 9007199254740990, known_pending: 9007199254740990, terminal: 1 }), context)).toThrow()
  })
  it('requires exactly the first five known rows without duplicates or partial truncation', () => {
    const five = Array.from({ length: 5 }, (_, index) => pendingItem(30 - index))
    const page = pendingSnapshot({ registrations: 8, known_pending: 7, pending_tickets: 7, items: five })
    expect(readPendingTickets(page, context).items).toHaveLength(5)
    for (const items of [five.slice(1), [...five, pendingItem(20)], [pendingItem(), pendingItem()]]) {
      expect(() => readPendingTickets({ ...page, items }, context)).toThrow()
    }
  })
  it.each([
    { state: 'CLOSED' }, { state: 'CANCELLED' }, { workflow_model: 'UNKNOWN' }, { state: 'NEW' },
    { version: 0 }, { ticket_id: 'not-an-id' }, { registered_at: '2026-10-24T00:00:00Z' },
    { title: '' }, { receipt_no: '' }, { unexpected: true },
  ])('rejects invalid and terminal rows %j', patch => {
    expect(() => readPendingTickets({ ...pendingSnapshot(), items: [{ ...pendingItem(), ...patch }, pendingItem(19)] }, context)).toThrow()
  })
  it('preserves nanosecond ordering and deterministic ID ties', () => {
    for (const items of [[pendingItem(19), pendingItem(20)], [pendingItem(), { ...pendingItem(19), registered_at: '2026-10-22T12:00:00.000000002Z' }]]) {
      expect(() => readPendingTickets({ ...pendingSnapshot(), items }, context)).toThrow()
    }
  })
})

function harness(answer: () => unknown | Promise<unknown>) {
  let state = emptyPendingTickets()
  const request = vi.fn(async () => answer())
  const workspace = createPendingTickets({ request } as any, next => { state = next })
  workspace.reset(context)
  return { workspace, request, state: () => state }
}

describe('read-only pending request ownership', () => {
  it('uses only the fixed GET and reports a detached current snapshot', async () => {
    const h = harness(() => pendingSnapshot())
    expect(await h.workspace.refresh()).toBe(true)
    expect(h.request).toHaveBeenCalledWith('/dashboard/pending-tickets', expect.objectContaining({ method: 'GET', signal: expect.any(AbortSignal) }))
    h.state().current!.items.length = 0
    await h.workspace.refresh()
    expect(h.state().current?.items).toHaveLength(2)
  })
  it('clears stale data on errors and retries with the same readonly request', async () => {
    let failed = false
    const h = harness(() => { if (failed) throw new Error('synthetic outage'); return pendingSnapshot() })
    await h.workspace.refresh(); failed = true
    expect(await h.workspace.refresh()).toBe(false)
    expect(h.state().current).toBeNull()
    expect(h.state().canRetry).toBe(true)
    failed = false; expect(await h.workspace.retry()).toBe(true)
    expect(h.request.mock.calls).toHaveLength(3)
  })
  it('does not dispatch twice while busy and ignores a late success after cancellation', async () => {
    let resolve!: (value: unknown) => void
    const h = harness(() => new Promise(done => { resolve = done }))
    const pending = h.workspace.refresh()
    expect(await h.workspace.refresh()).toBe(false)
    h.workspace.cancel(); resolve(pendingSnapshot())
    expect(await pending).toBe(false)
    expect(h.state().cancelled).toBe(true)
    expect(h.state().current).toBeNull()
    expect(h.state().canRetry).toBe(true)
  })
  it('scope revocation cancels old results and clears retry authority', async () => {
    let resolve!: (value: unknown) => void
    const h = harness(() => new Promise(done => { resolve = done }))
    const pending = h.workspace.refresh()
    h.workspace.reset(null); resolve(pendingSnapshot())
    await pending
    expect(h.state()).toEqual(emptyPendingTickets())
    expect(await h.workspace.retry()).toBe(false)
    expect(await h.workspace.refresh()).toBe(false)
  })
  it('rechecks ownership after synchronous request-state callbacks', async () => {
    const request = vi.fn()
    let workspace: ReturnType<typeof createPendingTickets> | undefined
    workspace = createPendingTickets({ request } as any, state => { if (state.busy) workspace!.reset(null) })
    workspace.reset(context)
    expect(await workspace.refresh()).toBe(false)
    expect(request).not.toHaveBeenCalled()
  })
  it('rejects an older server snapshot within one scope', async () => {
    let older = false
    const h = harness(() => pendingSnapshot({ observed_at: older ? '2026-10-23T11:59:59Z' : '2026-10-23T12:00:00Z' }))
    await h.workspace.refresh(); older = true
    expect(await h.workspace.refresh()).toBe(false)
    expect(h.state().current).toBeNull()
    expect(h.state().error).toBeTruthy()
  })
  it('disposal or malformed scopes cannot issue requests', async () => {
    const h = harness(() => pendingSnapshot())
    h.workspace.reset({ ...context, role: 'unknown' } as any)
    expect(await h.workspace.refresh()).toBe(false)
    h.workspace.dispose(); h.workspace.reset(context)
    expect(await h.workspace.refresh()).toBe(false)
    expect(h.request).not.toHaveBeenCalled()
  })
})
