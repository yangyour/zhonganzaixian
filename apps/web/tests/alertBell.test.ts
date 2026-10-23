import { describe, expect, it, vi } from 'vitest'
import { createAlertBell, emptyAlertBell, readAlertBell } from '../src/utils/alertBell'
import { bellContext as context, bellId as id, bellItem, bellSnapshot } from './alertBell.fixture'

describe('exact current-tenant unacknowledged problem snapshot', () => {
  it('preserves suppressed NEW problems and actual zero without claiming delivery', () => {
    const raw = bellSnapshot(), result = readAlertBell(raw, context)
    expect(result).toEqual(raw); expect(result).not.toBe(raw)
    expect(result.items[1]?.suppression_state).toBe('MAINTENANCE_SILENCED')
    expect(readAlertBell(bellSnapshot({ total: 0, items: [] }), context).total).toBe(0)
  })
  it.each([
    { tenant_id: id(9) }, { actor_id: id(9) }, { permission_version: 8 }, { scope: 'ALL_TENANTS' },
    { source: 'fixture' }, { metric_version: 'unknown' }, { item_limit: 6 }, { sort: 'TITLE' },
    { total: -1 }, { total: true }, { total: NaN }, { total: 9007199254740991 }, { total: 3 },
    { observed_at: '2026-02-30T00:00:00Z' }, { writes_performed: true }, { extra: true },
    { filter: { monitor_state: 'PROBLEM', handling_state: 'ACKNOWLEDGED' } },
  ])('rejects unauthorized or inconsistent metadata %j', patch => {
    expect(() => readAlertBell({ ...bellSnapshot(), ...patch }, context)).toThrow()
  })
  it.each(['viewer', 'tadmin', '', null])('rejects noninternal context %s', role => {
    expect(() => readAlertBell(bellSnapshot(), { ...context, role } as any)).toThrow()
  })
  it.each([
    { monitor_state: 'RECOVERED' }, { handling_state: 'CLOSED' }, { handling_state: 'ACKNOWLEDGED' },
    { severity: 'CRITICAL' }, { suppression_state: 'UNKNOWN' }, { occurrence_count: 0 }, { occurrence_count: 2147483648 },
    { version: 0 }, { event_id: 'invalid' }, { asset_id: null }, { rule_name: '' }, { rule_name: 'x'.repeat(129) },
    { last_occurred_at: '2026-10-24T00:00:00Z' }, { unexpected: true },
  ])('rejects invalid state or field %j', patch => {
    expect(() => readAlertBell({ ...bellSnapshot(), items: [{ ...bellItem(), ...patch }, bellItem(19)] }, context)).toThrow()
  })
  it('requires exactly the most recent five and retains precise ordering', () => {
    const rows = Array.from({ length: 5 }, (_, i) => bellItem(30 - i))
    const raw = bellSnapshot({ total: 120, items: rows })
    expect(readAlertBell(raw, context).items).toHaveLength(5)
    for (const items of [rows.slice(1), [...rows, bellItem(20)], [bellItem(), bellItem()],
      [bellItem(19), bellItem()], [bellItem(), { ...bellItem(19), last_occurred_at: '2026-10-23T12:00:00.000000002Z' }]]) {
      expect(() => readAlertBell({ ...raw, items }, context)).toThrow()
    }
  })
})

function harness(answer: () => unknown | Promise<unknown>) {
  let state = emptyAlertBell()
  const request = vi.fn(async () => answer())
  const workspace = createAlertBell({ request } as any, next => { state = next })
  workspace.reset(context)
  return { workspace, request, state: () => state }
}
describe('bell request lifetime', () => {
  it('uses the fixed GET only, without overrides or mutation', async () => {
    const h = harness(() => bellSnapshot()); expect(await h.workspace.refresh()).toBe(true)
    expect(h.request).toHaveBeenCalledWith('/dashboard/alert-bell', { method: 'GET', signal: expect.any(AbortSignal) })
    h.state().current!.items.length = 0
    await h.workspace.refresh(); expect(h.state().current?.items).toHaveLength(2)
  })
  it('clears stale count on failure and recovers only after a real successful read', async () => {
    let fail = false
    const h = harness(() => { if (fail) throw new Error('synthetic outage'); return bellSnapshot() })
    await h.workspace.refresh(); fail = true
    expect(await h.workspace.refresh()).toBe(false); expect(h.state().current).toBeNull()
    expect(h.state().canRetry).toBe(true)
    fail = false; expect(await h.workspace.retry()).toBe(true)
  })
  it.each(['cancel', 'reset', 'dispose'] as const)('rejects late response after %s', async action => {
    let finish!: (result: unknown) => void
    const h = harness(() => new Promise(resolve => { finish = resolve }))
    const pending = h.workspace.refresh()
    expect(await h.workspace.refresh()).toBe(false)
    if (action === 'reset') h.workspace.reset(null); else h.workspace[action]()
    finish(bellSnapshot()); expect(await pending).toBe(false)
    expect(h.state().current).toBeNull()
    expect(h.state().canRetry).toBe(action === 'cancel')
    expect(h.request).toHaveBeenCalledTimes(1)
  })
  it('rejects regressing snapshot time but resets the watermark on context change', async () => {
    let answer = bellSnapshot()
    const h = harness(() => answer); await h.workspace.refresh()
    answer = bellSnapshot({ observed_at: '2026-10-23T13:00:00Z' })
    expect(await h.workspace.refresh()).toBe(false); expect(h.state().current).toBeNull()
    h.workspace.reset(context); expect(await h.workspace.refresh()).toBe(true)
  })
  it('rechecks scope after synchronous busy callbacks before dispatch', async () => {
    const request = vi.fn()
    let workspace: ReturnType<typeof createAlertBell> | undefined
    workspace = createAlertBell({ request } as any, state => { if (state.busy) workspace!.reset(null) })
    workspace.reset(context); expect(await workspace.refresh()).toBe(false); expect(request).not.toHaveBeenCalled()
  })
})
