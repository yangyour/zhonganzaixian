import { describe, it, expect, vi } from 'vitest'
import { ApiError, type ApiClient } from '../src/api/client'
import { createWorklogWorkspace, emptyWorklogState, readWorklog, readWorklogPage, validateWorklogInput, worklogInstant, type WorklogContext, type WorklogEntry, type WorklogInput, type WorklogState } from '../src/utils/worklogWorkspace'
const id = (n: number) => `55000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
const context: WorklogContext = { tenantId: id(1), ticketId: id(2), actorId: id(3), permissionVersion: 1, canWrite: true }
const input = (): WorklogInput => ({ kind: 'REMOTE', started_at: '2026-10-02T08:00:00+08:00', ended_at: '2026-10-04T09:00:00+08:00', note: null })
const entry = (n = 4, version = 1): WorklogEntry => ({ id: id(n), tenant_id: context.tenantId, ticket_id: context.ticketId, owner_id: context.actorId, version, created_at: '2026-10-02T00:00:00Z', updated_at: '2026-10-02T00:00:00Z', kind: 'REMOTE', started_at: '2026-10-02T00:00:00Z', ended_at: '2026-10-04T01:00:00Z', note: null, elapsed_millis: 49 * 3600000, record_source: 'MANUAL_INTERVAL', source: 'postgresql', verification_status: 'SELF_REPORTED', financial_status: 'CONFIG_PENDING', default_minutes: null, labor_cost: null, transport_cost: null, gross_margin: null })
const page = (items: WorklogEntry[] = [], more = false) => ({ items, has_more: more, next_before_created_at: more ? items.at(-1)!.created_at : null, next_before_id: more ? items.at(-1)!.id : null, source: 'postgresql', scope: 'OWN', page_size_limit: 100 })
const revision = (value: WorklogEntry) => { const { id: _id, tenant_id: _tenant, ticket_id: _ticket, owner_id: _owner, created_at: _created, updated_at: _updated, ...facts } = value; return { ...facts, actor_id: context.actorId, action: value.version === 1 ? 'CREATE' : 'UPDATE', recorded_at: value.updated_at } }
const history = (items: ReturnType<typeof revision>[]) => ({ items, has_more: false, next_before_version: null, source: 'postgresql', scope: 'OWN', page_size_limit: 100 })
type Options = Parameters<ApiClient['request']>[1]
function harness(handler: (path: string, options: Options) => unknown | Promise<unknown>) {
  let state: WorklogState = emptyWorklogState()
  const request = vi.fn(async (path: string, options?: Options) => handler(path, options))
  const workspace = createWorklogWorkspace({ request } as unknown as Pick<ApiClient, 'request'>, next => { state = next }, () => 'original-key')
  workspace.reset(context)
  return { workspace, request, state: () => state }
}
const parent = { id: context.ticketId, tenant_id: context.tenantId, version: 1 }
const path = `/tickets/${context.ticketId}/worklogs`
const reads = (url: string, items: WorklogEntry[] = []) => url === `/tickets/${context.ticketId}` ? parent : page(items)
describe('manual worklog contract and input', () => {
  it('accepts explicit zero and49h without using default minutes', () => { validateWorklogInput(input()); const zero = { ...input(), ended_at: input().started_at }; validateWorklogInput(zero); expect(readWorklog(entry(), context).elapsed_millis).toBe(49 * 3600000) })
  it('validates leap dates, offsets, protocol bounds and millisecond precision', () => {
    expect(worklogInstant('2024-03-10T03:30:00-04:00') - worklogInstant('2024-03-10T01:30:00-05:00')).toBe(3600000)
    expect(worklogInstant('0001-01-01T00:00:00Z')).toBe(-62135596800000)
    expect(worklogInstant('9999-12-31T23:59:59.999Z')).toBe(253402300799999)
    for (const value of ['2026-02-29T00:00:00Z', '2026-10-02T24:00:00Z', '2026-10-02T00:00:00', '2026-10-02T00:00:00.1234Z', '2026-10-02T00:00:00+18:01', '0001-01-01T00:00:00+01:00']) expect(() => worklogInstant(value)).toThrow()
  })
  it('rejects missing, backwards, inferred, spoofed and malformed text facts', () => {
    for (const value of [{ ...input(), kind: ['REMOTE'] }, { ...input(), kind: '' }, { ...input(), ended_at: '2026-10-01T00:00:00Z' }, { ...input(), note: '\u00a0' }, { ...input(), note: '\ud800' }, { ...input(), note: 'x\u0000' }, { ...input(), note: '😀'.repeat(2001) }, { ...input(), default_minutes: 0 }]) expect(() => validateWorklogInput(value)).toThrow()
    expect(() => validateWorklogInput({ ...input(), note: '😀'.repeat(2000) })).not.toThrow()
    expect(() => validateWorklogInput({ ...input(), note: ' 原始\n文字\t ' })).not.toThrow()
  })
  it('rejects foreign owner/tenant/ticket, costs, verified attendance or wrong physical duration', () => {
    for (const changes of [{ owner_id: id(9) }, { tenant_id: id(9) }, { ticket_id: id(9) }, { elapsed_millis: 0 }, { labor_cost: 0 }, { default_minutes: 240 }, { verification_status: 'VERIFIED' }, { version: Number.MAX_SAFE_INTEGER + 1 }, { unexpected: true }]) expect(() => readWorklog({ ...entry(), ...changes }, context)).toThrow()
  })
  it('requires sorted unique pages and truthful paired continuation boundaries', () => {
    const items = Array.from({ length: 15 }, (_, i) => entry(30 - i)); expect(readWorklogPage(page(items, true), context).items).toHaveLength(15)
    for (const value of [page([entry(), entry()]), page([...items].reverse(), true), { ...page(items, true), next_before_id: id(99) }, { ...page([]), has_more: true }, { ...page([entry()]), scope: 'TEAM' }]) expect(() => readWorklogPage(value, context)).toThrow()
  })
})
describe('manual worklog request state', () => {
  it('refresh is GET-only, parent scope verified and returns actual empty list', async () => {
    const h = harness(url => reads(url)); expect(await h.workspace.refresh()).toBe(true); expect(h.state().page?.items).toEqual([]); expect(h.state().parentVersion).toBe(1); expect(h.request.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  })
  it('rejects foreign parent and clears stale list before failed refresh', async () => {
    let wrong = false; const h = harness(url => url.endsWith('/' + context.ticketId) && wrong ? { ...parent, tenant_id: id(9) } : reads(url, [entry()])); await h.workspace.refresh(); wrong = true; expect(await h.workspace.refresh()).toBe(false); expect(h.state().page).toBeNull(); expect(h.state().parentVersion).toBeNull()
  })
  it('successful save verifies normalized facts and preserves a durable receipt across read failure', async () => {
    let saved = false; const h = harness((url, options) => { if (options?.method) { saved = true; return entry() } if (saved) throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'isolated', 't'); return reads(url) }); await h.workspace.refresh(); expect(await h.workspace.submit(input())).toBe(true); expect(h.state().receipt?.id).toBe(id(4)); expect(h.state().attempt?.state).toBe('accepted'); expect(h.state().page).toBeNull(); expect(h.workspace.unresolved()).toBe(false)
  })
  it('network uncertainty retains original key/body/CAS while refresh never writes or replaces it', async () => {
    let writes = 0; const h = harness((url, options) => { if (options?.method) { if (++writes === 1) throw new ApiError(0, 'NETWORK_ERROR', 'isolated', 't'); return entry() } return reads(url, writes ? [entry()] : []) }); await h.workspace.refresh(); const draft = input(); expect(await h.workspace.submit(draft)).toBe(false); draft.note = 'changed outside'; expect(h.workspace.unresolved()).toBe(true); await h.workspace.refresh(); expect(writes).toBe(1); expect(await h.workspace.submit(draft)).toBe(false); expect(await h.workspace.retry()).toBe(true)
    const sent = h.request.mock.calls.filter(([, options]) => options?.method); expect(sent).toHaveLength(2); expect(sent[0]![1]!.body).toEqual(sent[1]![1]!.body); expect(sent[0]![1]!.idempotencyKey).toBe(sent[1]![1]!.idempotencyKey); expect((sent[1]![1]!.body as WorklogInput).note).toBeNull()
  })
  it('a malformed success is uncertain and may only retry the original command', async () => {
    const h = harness((url, options) => options?.method ? { ...entry(), elapsed_millis: 0 } : reads(url)); await h.workspace.refresh(); expect(await h.workspace.submit(input())).toBe(false); expect(h.state().receipt).toBeNull(); expect(h.state().attempt?.state).toBe('uncertain')
  })
  it('409 is rejected rather than auto-overwritten or auto-retried', async () => {
    const h = harness((url, options) => { if (options?.method) throw new ApiError(409, 'VERSION_CONFLICT', 'isolated', 't'); return reads(url) }); await h.workspace.refresh(); await h.workspace.submit(input()); expect(h.state().attempt?.state).toBe('rejected'); expect(await h.workspace.retry()).toBe(false); expect(await h.workspace.submit(input())).toBe(false); expect(h.request.mock.calls.filter(([, options]) => options?.method)).toHaveLength(1)
  })
  it('reads own immutable contiguous history without writing', async () => {
    const current = entry(4, 2); const h = harness(url => url.endsWith('/history?page_size=15') ? history([revision(current), revision(entry())]) : url.endsWith('/' + id(4)) ? current : reads(url, [current])); await h.workspace.refresh(); expect(await h.workspace.show(id(4))).toBe(true); expect(h.state().history?.items.map(x => x.version)).toEqual([2, 1]); expect(h.request.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  })
  it('rejects skipped history versions, foreign actor or a mismatched latest revision', async () => {
    for (const changes of [{ version: 1 }, { actor_id: id(9) }, { note: 'not current' }]) { const current = entry(4, 2); const h = harness(url => url.includes('/history?') ? history([{ ...revision(current), ...changes }]) : current); expect(await h.workspace.show(id(4))).toBe(false); expect(h.state().selected).toBeNull(); expect(h.state().history).toBeNull() }
  })
  it('requires the explicitly selected edit identity/version and keeps immutable creation timestamp', async () => {
    let current = entry(), changed = false; const h = harness((url, options) => { if (options?.method) { changed = true; current = entry(4, 2); return current } if (url.includes('/history?')) return history(changed ? [revision(current), revision(entry())] : [revision(current)]); if (url.endsWith('/' + id(4))) return current; return reads(url, [current]) }); await h.workspace.refresh(); await h.workspace.show(id(4)); expect(await h.workspace.submit(input(), { id: id(9), version: 1 })).toBe(false); expect(await h.workspace.submit(input(), { id: id(4), version: 2 })).toBe(false); expect(await h.workspace.submit(input(), { id: id(4), version: 1 })).toBe(true); expect(h.state().receipt?.version).toBe(2)
    const call = h.request.mock.calls.find(([, options]) => options?.method); expect(call?.[1]?.method).toBe('PATCH'); expect(call?.[1]?.body).toHaveProperty('expected_version', 1); expect(call?.[1]?.body).not.toHaveProperty('expected_ticket_version')
  })
  it('rejects old or conflicting readback after a confirmed write while keeping receipt', async () => {
    let saved = false; const h = harness((url, options) => { if (options?.method) { saved = true; return entry() } return reads(url, saved ? [{ ...entry(), note: 'conflicting same version' }] : []) }); await h.workspace.refresh(); expect(await h.workspace.submit(input())).toBe(true); expect(h.state().page).toBeNull(); expect(h.state().receipt?.note).toBeNull()
  })
  it('context reset discards late write responses and prevents cross-context retry', async () => {
    let release!: (value: unknown) => void; const h = harness((url, options) => options?.method ? new Promise(resolve => { release = resolve }) : reads(url)); await h.workspace.refresh(); const pending = h.workspace.submit(input()); h.workspace.reset({ ...context, tenantId: id(8) }); release(entry()); expect(await pending).toBe(false); expect(h.state().receipt).toBeNull(); expect(await h.workspace.retry()).toBe(false)
  })
  it('read-only/invalid contexts cannot submit, retry or issue requests', async () => {
    const h = harness(url => reads(url)); h.workspace.reset({ ...context, canWrite: false }); await h.workspace.refresh(); expect(await h.workspace.submit(input())).toBe(false); const count = h.request.mock.calls.length; h.workspace.reset({ ...context, ticketId: 'bad' }); await h.workspace.refresh(); expect(h.request.mock.calls).toHaveLength(count); expect(h.state().error).not.toBeNull()
  })
  it('next-page uses paired boundaries and rejects returned earlier-page records', async () => {
    const items = Array.from({ length: 15 }, (_, i) => entry(30 - i)); let next = false; const h = harness(url => { if (url.endsWith('/' + context.ticketId)) return parent; if (url.includes('before_id=')) { next = true; return page([entry(99)]) } return page(items, true) }); await h.workspace.refresh(); expect(await h.workspace.refresh(true)).toBe(false); expect(next).toBe(true); expect(h.state().page).toBeNull(); expect(h.workspace.unresolved()).toBe(false)
  })
})
