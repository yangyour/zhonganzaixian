import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError, type RequestOptions } from '../src/api/client'
import {
  canTransition, createMonitoringEventWorkspace, readMonitoringEventDetail, readMonitoringWorkspace,
  type EventTransition, type MonitoringEventContext, type MonitoringEventWorkspacePayload,
} from '../src/utils/monitoringEvents'
import { controllerFixture, context, sampleDetail, sampleWorkspace } from './monitoringEventControllerFixtures'

const OTHER = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const KEY = 'd15-original-request-0001'
const resolveInput = { expected_version: 1, action: 'RESOLVE' as const, root_cause_note: '人工核验线路恢复原因' }
function pendingWorkspace() {
  const data = sampleWorkspace(), e = data.events[0]!
  e.handling_state = 'PENDING_REVIEW'; e.version = 1
  return data
}
function deferred<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
function clientFor(data = pendingWorkspace()) {
  const request = vi.fn(async (_path: string, _options?: RequestOptions): Promise<unknown> => structuredClone(data))
  const workspace = createMonitoringEventWorkspace({ request: request as ApiClient['request'] }, () => {}, () => KEY)
  workspace.reset(context)
  return { workspace, request, data }
}
function receipt(data: MonitoringEventWorkspacePayload) {
  const e = structuredClone(data.events[0]!)
  return { data: { ...e, version: 2, handling_state: 'RESOLVED', root_cause_note: resolveInput.root_cause_note }, trace_id: 'resolve-receipt' }
}

describe('production-controller D15 DTO decoding', () => {
  it('accepts exactly the eight-field workspace, all backend states, and full detail', () => {
    expect(Object.keys(controllerFixture.workspace.data)).toHaveLength(8)
    const w = readMonitoringWorkspace(controllerFixture.workspace, context)
    expect(w).toEqual(controllerFixture.workspace.data)
    expect(readMonitoringWorkspace(controllerFixture.workspace.data, context)).toEqual(w)
    const d = readMonitoringEventDetail(sampleDetail(), context, controllerFixture.detail.data.event.id)
    expect(d).toEqual(controllerFixture.detail.data)
    expect(w.events.some(e => e.handling_state === 'PENDING_REVIEW')).toBe(true)
    expect(w.events.some(e => e.suppression_state === 'INFO_DEFAULT_SILENT')).toBe(true)
    expect(w.autoticket_attempts.some(a => a.mapped_priority === null)).toBe(true)
    expect(new Set(d.noise_records.map(n => n.layer)).size).toBe(6)
  })
  it('preserves nanosecond UTC timestamps without inventing policy status', () => {
    const w = sampleWorkspace(); w.observed_at = '2026-10-19T10:05:00.123456789Z'
    w.events[0]!.updated_at = w.observed_at
    expect(readMonitoringWorkspace(w, context).observed_at).toBe(w.observed_at)
    expect(w).not.toHaveProperty('review_policy_status')
    expect(w).not.toHaveProperty('mapping_policy_status')
  })
  const corruptions: Array<[string, (w: Record<string, any>) => void]> = [
    ['unknown top-level field', w => { w.review_policy_status = 'CONFIG_PENDING_Q29' }],
    ['missing top-level field', w => { delete w.source }],
    ['wrong tenant', w => { w.tenant_id = OTHER }],
    ['wrong source', w => { w.source = 'fixture' }],
    ['unknown event field', w => { w.events[0].extra = true }],
    ['missing event field', w => { delete w.events[0].version }],
    ['nested wrong tenant', w => { w.events[0].tenant_id = OTHER }],
    ['unknown handling state', w => { w.events[0].handling_state = 'AUTO_RESOLVED' }],
    ['unknown suppression state', w => { w.events[0].suppression_state = 'NORMAL' }],
    ['obsolete notification state', w => { w.events[0].notification_empty_state = 'RECOVERED_within_2MIN_CANCELLED' }],
    ['notification state/label mismatch', w => { w.events[0].notification_empty_state_label = '系统正常' }],
    ['wrong severity rank', w => { w.events[0].severity_rank = 0 }],
    ['invalid timestamp', w => { w.events[0].updated_at = '2026-02-30T00:00:00Z' }],
    ['unsafe version', w => { w.events[0].version = Number.MAX_SAFE_INTEGER + 1 }],
    ['duplicate event', w => { w.events.push(structuredClone(w.events[0])) }],
    ['funnel wrong tenant', w => { w.noise_funnel.tenant_id = OTHER }],
    ['window wrong tenant', w => { w.maintenance_windows[0].tenant_id = OTHER }],
    ['unknown window mode', w => { w.maintenance_windows[0].silence_mode = 'SILENCE_ALL_EXCEPT_DISASTER' }],
    ['quarantine wrong claimed tenant', w => { w.quarantine_items[0].claimed_tenant_id = OTHER }],
    ['unknown quarantine decision', w => { w.quarantine_items[0].decision = 'BOUND' }],
    ['quarantine missing field', w => { delete w.quarantine_items[0].source_phase }],
    ['attempt wrong tenant', w => { w.autoticket_attempts[0].tenant_id = OTHER }],
    ['unknown attempt status', w => { w.autoticket_attempts[0].status = 'CREATED' }],
    ['unknown attempt priority', w => { w.autoticket_attempts[0].mapped_priority = 'P4' }],
    ['unknown Q14 policy status', w => { w.autoticket_attempts[0].mapping_policy_status = 'APPROVED' }],
  ]
  it.each(corruptions)('rejects %s', (_name, corrupt) => {
    const w = structuredClone(controllerFixture.workspace.data); corrupt(w)
    expect(() => readMonitoringWorkspace(w, context)).toThrow()
  })
  it.each(['members', 'noise_records', 'autoticket_attempt'] as const)('validates nested %s scope and exact fields', field => {
    const d = sampleDetail().data
    const row = field === 'autoticket_attempt' ? d.autoticket_attempt! : d[field][0]!
    row.tenant_id = OTHER
    expect(() => readMonitoringEventDetail(d, context, d.event.id)).toThrow()
    row.tenant_id = context.tenantId; row.event_id = OTHER
    expect(() => readMonitoringEventDetail(d, context, d.event.id)).toThrow()
    row.event_id = d.event.id; Object.assign(row, { surprise: 1 })
    expect(() => readMonitoringEventDetail(d, context, d.event.id)).toThrow()
  })
  it('rejects wrong detail ID, missing detail fields and unknown noise layer', () => {
    expect(() => readMonitoringEventDetail(sampleDetail(), context, OTHER)).toThrow()
    const d = sampleDetail().data; (d.noise_records[0] as any).layer = 'MERGED_DEDUPLICATED'
    expect(() => readMonitoringEventDetail(d, context, d.event.id)).toThrow()
    const missing = sampleDetail().data as any; delete missing.members
    expect(() => readMonitoringEventDetail(missing, context, missing.event.id)).toThrow()
  })
})

describe('D15 guarded reads and transitions', () => {
  it('loads exact GET routes with abortable reads and no writes', async () => {
    const { workspace: w, request } = clientFor(sampleWorkspace())
    expect(await w.load('24h')).toBe(true)
    request.mockResolvedValueOnce(sampleDetail())
    const id = controllerFixture.detail.data.event.id
    expect(await w.loadEventDetail(id)).toBe(true)
    expect(request.mock.calls.map(([path]) => path)).toEqual(['/events?window=24h&page_size=25', `/events/${id}`])
    expect(request.mock.calls.every(([, options]) => options?.signal instanceof AbortSignal && !options.method && !options.body)).toBe(true)
  })
  it('clears stale data on failed refresh and rejects wrong requested funnel', async () => {
    const { workspace: w, request } = clientFor(); await w.load()
    request.mockRejectedValueOnce(new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'offline', 't'))
    expect(await w.load()).toBe(false); expect(w.state().workspace).toBeNull()
    expect(await w.load('7d')).toBe(false); expect(w.state().workspace).toBeNull()
  })
  it.each([
    ['tenant', { ...context, tenantId: OTHER }], ['actor', { ...context, actorId: OTHER }],
    ['permission version', { ...context, permissionVersion: 4 }], ['revoked write', { ...context, canWriteInternal: false }],
  ] as [string, MonitoringEventContext][])('discards delayed reads after %s changes', async (_name, next) => {
    const { workspace: w, request } = clientFor(), wait = deferred<unknown>()
    request.mockReturnValueOnce(wait.promise); const loading = w.load(); const signal = request.mock.calls[0]![1]!.signal!
    w.reset(next); expect(signal.aborted).toBe(true); wait.resolve(pendingWorkspace())
    expect(await loading).toBe(false); expect(w.state().workspace).toBeNull(); expect(w.state().error).toBeNull()
  })
  it('discards delayed detail and post-commit reload after reset or disposal', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    const detail = deferred<unknown>(); request.mockReturnValueOnce(detail.promise)
    const reading = w.loadEventDetail(data.events[0]!.id); w.reset(context); detail.resolve(sampleDetail())
    expect(await reading).toBe(false); expect(w.state().detail).toBeNull()
    await w.load(); const write = deferred<unknown>(); request.mockReturnValueOnce(write.promise)
    const sending = w.submitTransition(data.events[0]!.id, resolveInput); w.dispose(); write.resolve(receipt(data))
    expect(await sending).toBe(false); expect(request).toHaveBeenCalledTimes(4)
    expect(await w.retry()).toBe(false)
  })
  it('discards a late write response after forced permission change without reloading or replaying', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    const write = deferred<unknown>(); request.mockReturnValueOnce(write.promise)
    const sending = w.submitTransition(data.events[0]!.id, resolveInput)
    w.reset({ ...context, permissionVersion: 4, canWriteInternal: false })
    write.resolve(receipt(data)); expect(await sending).toBe(false)
    expect(request).toHaveBeenCalledTimes(2); expect(w.state().workspace).toBeNull()
    expect(w.state().attempt).toBeNull(); expect(await w.retry()).toBe(false)
  })
  it('requires an explicitly entered >=5 character root cause and legal PENDING_REVIEW state', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    for (const note of [null, '', '    ', '短说明', 'a'.repeat(501)]) {
      expect(await w.submitTransition(data.events[0]!.id, { ...resolveInput, root_cause_note: note })).toBe(false)
    }
    for (const state of ['NEW', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const) {
      data.events[0]!.handling_state = state; await w.load()
      expect(await w.submitTransition(data.events[0]!.id, resolveInput)).toBe(false)
    }
    expect(request.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  })
  it('rejects maintenance sub-millisecond write input instead of losing precision on PostgreSQL round-trip', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    const window = data.maintenance_windows[0]!
    expect(await w.submitWindowAction(window.id, { expected_version: window.version, action: 'EXTEND', new_ends_at: '2099-10-19T10:00:00.123456789Z' })).toBe(false)
    const create = { name: '合成窗口', asset_id: data.events[0]!.asset_id, starts_at: '2099-10-19T10:00:00.123456Z', ends_at: '2099-10-19T11:00:00Z', silence_mode: 'SUPPRESS_NOTIFY' as const, sla_exclusion_requested: false, reason: '人工明确维护' }
    expect(await w.submitMaintenanceWindow(create)).toBe(false)
    expect(await w.submitMaintenanceWindow({ ...create, starts_at: '2099-10-19T10:00:00Z', ends_at: '2099-10-19T11:00:00.999999Z' })).toBe(false)
    expect(request.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  })
  it('accepts exact millisecond maintenance create and EXTEND receipts without rewriting inputs', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    const baseline = data.maintenance_windows[0]!
    const create = { name: '合成毫秒窗口', asset_id: baseline.asset_id, starts_at: '2099-10-19T10:00:00.123Z', ends_at: '2099-10-19T11:00:00.456Z', silence_mode: 'SUPPRESS_NOTIFY' as const, sla_exclusion_requested: false, reason: '人工明确维护原因' }
    request.mockResolvedValueOnce({ ...baseline, ...create, version: 1, sla_exclusion_confirmed: false, sla_exclusion_confirmed_by: null, sla_exclusion_confirmed_at: null })
    expect(await w.submitMaintenanceWindow(create)).toBe(true)
    expect(request.mock.calls.find(([, options]) => options?.method === 'POST')?.[1]?.body).toEqual(create)
    const extend = { expected_version: baseline.version, action: 'EXTEND' as const, new_ends_at: '2099-10-19T12:00:00.789Z' }
    request.mockResolvedValueOnce({ ...baseline, ends_at: extend.new_ends_at, version: baseline.version + 1 })
    expect(await w.submitWindowAction(baseline.id, extend)).toBe(true)
    const posts = request.mock.calls.filter(([, options]) => options?.method === 'POST')
    expect(posts).toHaveLength(2); expect(posts[1]![1]?.body).toEqual(extend)
    expect(w.state().attempt).toBeNull()
  })
  it.each(['因', '𠮷'])('caps manual-ticket reasons at 492 trimmed code points (%s) for the stored success prefix', async character => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    const a = data.autoticket_attempts.find(a => a.status === 'QUARANTINED_MANUAL_REQUIRED')!
    expect(await w.submitManualAutoTicket(a.id, { expected_version: a.version, reason: character.repeat(493) })).toBe(false)
    expect(request).toHaveBeenCalledTimes(1)
    const reason = character.repeat(492)
    request.mockResolvedValueOnce({ ...a, version: a.version + 1, status: 'MANUAL_CREATED', ticket_id: OTHER, last_error: '人工补建成功: ' + reason })
    expect(await w.submitManualAutoTicket(a.id, { expected_version: a.version, reason })).toBe(true)
    expect(request.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
    expect(w.state().attempt).toBeNull()
  })
  it('rejects obsolete actions, malformed IDs, unknown fields, stale versions and unauthorized writes locally', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load(); const id = data.events[0]!.id
    expect(await w.submitTransition('../bad', resolveInput)).toBe(false)
    expect(await w.submitTransition(id, { ...resolveInput, expected_version: 2 })).toBe(false)
    expect(await w.submitTransition(id, { ...resolveInput, action: 'START_PROGRESS' as EventTransition })).toBe(false)
    expect(await w.submitTransition(id, { ...resolveInput, injected: true } as any)).toBe(false)
    expect(await w.submitWindowAction(data.maintenance_windows[0]!.id, { expected_version: 1, action: 'CANCEL', new_ends_at: null } as any)).toBe(false)
    expect(await w.submitQuarantineDecision(data.quarantine_items[0]!.quarantine_id, { decision: 'BOUND', bound_asset_id: id, reason: '核验' } as any)).toBe(false)
    w.reset({ ...context, canWriteInternal: false, canAdminConfirm: false }); await w.load()
    expect(await w.submitTransition(id, resolveInput)).toBe(false)
    expect(await w.submitWindowAction(data.maintenance_windows[0]!.id, { expected_version: 1, action: 'CONFIRM_SLA_EXCLUSION', new_ends_at: null })).toBe(false)
    expect(request.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  })
})

describe('D15 interrupted commands', () => {
  it('retains original path, method, body and key without auto retry; locks competing reads/writes', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    request.mockRejectedValueOnce(new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'unknown', 't'))
    const input = { ...resolveInput }; expect(await w.submitTransition(data.events[0]!.id, input)).toBe(false)
    input.root_cause_note = 'later edited value'
    const first = structuredClone(w.state().attempt)
    expect(first?.path).toBe(`/events/${data.events[0]!.id}/transitions`)
    expect(first?.body).toEqual(resolveInput); expect(first?.key).toBe(KEY)
    expect(await w.load()).toBe(false); expect(await w.loadEventDetail(data.events[0]!.id)).toBe(false)
    expect(await w.submitTransition(data.events[0]!.id, resolveInput)).toBe(false)
    await Promise.resolve(); expect(request).toHaveBeenCalledTimes(2)
    request.mockRejectedValueOnce(new ApiError(403, 'ACTION_FORBIDDEN', 'denied replay', 't'))
    expect(await w.retry()).toBe(false); expect(w.state().attempt).toEqual(first)
    request.mockResolvedValueOnce(receipt(data))
    expect(await w.retry()).toBe(true); expect(w.state().attempt).toBeNull()
    const posts = request.mock.calls.filter(([, o]) => o?.method === 'POST')
    expect(posts).toHaveLength(3); expect(posts.every(([p, o]) => p === first?.path && o?.idempotencyKey === KEY && JSON.stringify(o.body) === JSON.stringify(resolveInput))).toBe(true)
  })
  it.each(['malformed', 'wrong tenant', 'wrong ID', 'wrong version', 'wrong root cause'])('keeps uncertain request on %s receipt', async kind => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    const r = receipt(data)
    if (kind === 'wrong tenant') r.data.tenant_id = OTHER
    if (kind === 'wrong ID') r.data.id = OTHER
    if (kind === 'wrong version') r.data.version = 100
    if (kind === 'wrong root cause') r.data.root_cause_note = '服务端其他输入'
    request.mockResolvedValueOnce(kind === 'malformed' ? {} : r)
    expect(await w.submitTransition(data.events[0]!.id, resolveInput)).toBe(false)
    expect(w.state().attempt?.body).toEqual(resolveInput); expect(request).toHaveBeenCalledTimes(2)
  })
  it('a known initial 409 unlocks without retrying or upgrading expected version', async () => {
    const { workspace: w, request, data } = clientFor(); await w.load()
    request.mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', 'stale', 't'))
    expect(await w.submitTransition(data.events[0]!.id, resolveInput)).toBe(false)
    expect(w.state().attempt).toBeNull(); expect(await w.retry()).toBe(false)
    expect(request.mock.calls[1]![1]!.body).toEqual(resolveInput)
  })
})

// Actual controller exchanges determine the exact request path/body and returned DTO.
describe('D15 controller write exchanges through ApiClient', () => {
  for (const exchange of controllerFixture.exchanges.filter(x => x.method === 'POST' && !x.path.endsWith('/ingest'))) {
    it(`matches ${exchange.name} method, path, body, CSRF, credentials and original idempotency key`, async () => {
      const data = sampleWorkspace(), body = structuredClone(exchange.request), response = exchange.response.data
      const path = exchange.path.replace(/^\/api\/v1/, ''), id = path.split('/').at(-2)!
      if (path.includes('/events/')) {
        const before = { ACKNOWLEDGE: 'NEW', START: 'ACKNOWLEDGED', MARK_PENDING_REVIEW: 'IN_PROGRESS', RESOLVE: 'PENDING_REVIEW', CLOSE: 'RESOLVED' }
        data.events = [{ ...response, version: body.expected_version, handling_state: before[body.action as EventTransition] } as any]
      } else if (path.startsWith('/maintenance-windows/')) {
        data.maintenance_windows = [{ ...response, version: body.expected_version, ends_at: '2026-01-01T01:00:00Z', starts_at: '2026-01-01T00:00:00Z', sla_exclusion_confirmed: false, sla_exclusion_confirmed_by: null, sla_exclusion_confirmed_at: null } as any]
      } else if (path.startsWith('/monitoring/quarantine/')) {
        data.quarantine_items = [{ ...response, decision: null, bound_asset_id: null, result_event_id: null, decision_reason: null, decided_by: null, decided_at: null } as any]
      } else if (path.startsWith('/autoticket-attempts/')) {
        data.autoticket_attempts = [{ ...response, version: body.expected_version, status: 'QUARANTINED_MANUAL_REQUIRED' } as any]
      }
      const transport = vi.fn<typeof fetch>(async (_url, options) => new Response(JSON.stringify(options?.method === 'POST' ? exchange.response : { data, trace_id: 'load' }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      const client = new ApiClient(transport); client.setCsrfToken('SYNTHETIC-D15-CSRF')
      const w = createMonitoringEventWorkspace(client, () => {}, () => KEY); w.reset(context); expect(await w.load()).toBe(true)
      let result = false
      if (path.startsWith('/events/')) result = await w.submitTransition(id, body as any)
      else if (path === '/maintenance-windows') result = await w.submitMaintenanceWindow(body as any)
      else if (path.startsWith('/maintenance-windows/')) result = await w.submitWindowAction(id, body as any)
      else if (path.startsWith('/monitoring/quarantine/')) result = await w.submitQuarantineDecision(id, body as any)
      else if (path.startsWith('/autoticket-attempts/')) result = await w.submitManualAutoTicket(id, body as any)
      expect(result).toBe(true)
      const posts = transport.mock.calls.filter(([, options]) => options?.method === 'POST'); expect(posts).toHaveLength(1)
      expect(posts[0]![0]).toBe('/api/v1' + path)
      expect(posts[0]![1]).toMatchObject({ method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', body: JSON.stringify(body), headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-Token': 'SYNTHETIC-D15-CSRF', 'Idempotency-Key': KEY } })
    })
  }
})
