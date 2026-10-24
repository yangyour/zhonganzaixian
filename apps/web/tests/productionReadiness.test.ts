import { describe, expect, it, vi } from 'vitest'
import { watch } from 'vue'
import { ApiError } from '../src/api/client'
import { createProductionReadiness, readProductionReadiness, type ProductionReadiness } from '../src/utils/productionReadiness'

const scope = { tenantId: '79000000-0000-4000-8000-000000000002', actorId: '79000000-0000-4000-8000-000000000001', permissionVersion: 8 }
const databaseCodes = {
  DATABASE_NAMESPACE: ['DEDICATED_TEST_SCOPE', 'RUNTIME_SCOPE_REJECTED'],
  DATABASE_ROLE: ['ROLE_RESTRICTED', 'ELEVATED_ROLE_REJECTED'],
  DATABASE_DDL: ['RUNTIME_DDL_DENIED', 'RUNTIME_DDL_PRIVILEGE_REJECTED'],
  MIGRATION_IDENTITY: ['MIGRATION_IDENTITY_SEPARATE', 'MIGRATION_ROLE_MEMBERSHIP_REJECTED'],
  GLOBAL_DATABASE_CAPABILITIES: ['GLOBAL_CAPABILITIES_DENIED', 'GLOBAL_CAPABILITIES_REJECTED'],
} as const

const value = (): ProductionReadiness => ({ tenant_id: '79000000-0000-4000-8000-000000000002', actor_id: '79000000-0000-4000-8000-000000000001', permission_version: 8,
  runtime_mode: 'remote_test', runtime_database_restricted: true, database_observed_at: '2026-10-23T12:00:00Z',
  database_version: 'SYNTHETIC_UNIT_PG', production_database_kind: null, production_isolation_strategy: null,
  production_ready: false, activation_performed: false,
  checks: [
    ...Object.entries(databaseCodes).map(([id, codes]) => ({ id, status: 'PASS' as const, code: codes[0] })),
    { id: 'SECURE_SESSION_SETTINGS', status: 'BLOCKED', code: 'SECURE_SESSION_NOT_CONFIGURED' },
    { id: 'PUBLIC_TLS_CERTIFICATE', status: 'NOT_RUN', code: 'PUBLIC_ENTRY_PROBE_REQUIRED' },
    { id: 'PRODUCTION_DATABASE_ISOLATION', status: 'CUSTOMER_GATED', code: 'Q02_PENDING' },
    { id: 'PRODUCTION_RUNTIME', status: 'BLOCKED', code: 'REMOTE_TEST_IS_NOT_PRODUCTION' },
  ],
})
const unobserved = (): ProductionReadiness => ({ ...value(), runtime_database_restricted: false, database_observed_at: null, database_version: null,
  checks: [{ id: 'DATABASE_OBSERVATION', status: 'NOT_READY', code: 'DATABASE_OBSERVATION_UNAVAILABLE' }, ...value().checks.slice(5)] })
const setup = () => {
  const request = vi.fn().mockResolvedValue(value())
  return { request, flow: createProductionReadiness(() => ({ tenantId: '79000000-0000-4000-8000-000000000002', actorId: '79000000-0000-4000-8000-000000000001', permissionVersion: 8 }), { request }) }
}
describe('production readiness diagnostic', () => {
  it('is explicit read-only and keeps production and Q02 unapproved', async () => {
    const { request, flow } = setup(); expect(request).not.toHaveBeenCalled(); await flow.inspect()
    expect(flow.result.value?.production_ready).toBe(false); expect(flow.result.value?.production_database_kind).toBeNull()
    expect(request.mock.calls[0][0]).toBe('/operations/production-readiness')
    expect(request.mock.calls[0][1]?.method).toBeUndefined()
  })
  it('rejects wrong scope and invented production success', async () => {
    for (const patch of [{ tenant_id: 'other' }, { actor_id: 'other' }, { permission_version: 9 }, { production_ready: true }]) {
      const { request, flow } = setup(); request.mockResolvedValue({ ...value(), ...patch }); await flow.inspect()
      expect(flow.result.value).toBeNull(); expect(flow.error.value).toBeInstanceOf(ApiError)
    }
  })
  it('cancellation and context reset discard a late observation', async () => {
    const { request, flow } = setup(); let resolve!: (value: ProductionReadiness) => void
    request.mockImplementation(() => new Promise(done => { resolve = done }))
    const pending = flow.inspect(); flow.reset(); resolve(value()); await pending
    expect(flow.result.value).toBeNull(); expect(flow.busy.value).toBe(false)
  })
  it('failed refresh removes the previous successful test observation', async () => {
    const { request, flow } = setup(); await flow.inspect(); request.mockRejectedValue(new Error('SYNTHETIC_OFFLINE'))
    await flow.inspect(); expect(flow.result.value).toBeNull(); expect(flow.error.value).not.toBeNull()
  })
  it('duplicate or unknown check IDs cannot be presented as a valid diagnostic', async () => {
    for (const checks of [[value().checks[0]!, value().checks[0]!], [{ id: 'MADE_UP', status: 'PASS', code: 'x' }]]) {
      const { request, flow } = setup(); request.mockResolvedValue({ ...value(), checks }); await flow.inspect()
      expect(flow.result.value).toBeNull()
    }
  })
  it('cannot claim restricted runtime without an actual timestamp and version observation',async()=>{
    const {request,flow}=setup();request.mockResolvedValue({...value(),database_observed_at:null,database_version:null});await flow.inspect();expect(flow.result.value).toBeNull()
  })
  it('rechecks callback scope even when a caller has not yet reset its workspace',async()=>{
    let tenant='79000000-0000-4000-8000-000000000002',resolve!:(v:ProductionReadiness)=>void
    const request=vi.fn(()=>new Promise<ProductionReadiness>(r=>{resolve=r}))
    const flow=createProductionReadiness(()=>({tenantId:tenant,actorId:'79000000-0000-4000-8000-000000000001',permissionVersion:8}),{request} as any)
    const pending=flow.inspect();tenant='79000000-0000-4000-8000-000000000003';resolve(value());await pending;expect(flow.result.value).toBeNull()
  })
  it('rejects a false observation PASS and clears a prior valid result', async () => {
    const { request, flow } = setup(); await flow.inspect(); expect(flow.result.value).not.toBeNull()
    const forged = unobserved(); forged.checks[0]!.status = 'PASS'
    request.mockResolvedValue(forged); await flow.inspect()
    expect(flow.result.value).toBeNull(); expect(flow.error.value).toBeInstanceOf(ApiError)
  })
  it('does not dispatch after synchronous reset triggered by the busy state', async () => {
    const { request, flow } = setup()
    const stop = watch(flow.busy, busy => { if (busy) flow.reset() }, { flush: 'sync' })
    try { await flow.inspect(); expect(request).not.toHaveBeenCalled(); expect(flow.result.value).toBeNull() } finally { stop() }
  })
  it('captures an independent scope before awaiting and refuses unsafe permission versions', async () => {
    let resolve!: (value: ProductionReadiness) => void
    const mutable = { ...scope }, request = vi.fn(() => new Promise<ProductionReadiness>(done => { resolve = done }))
    const flow = createProductionReadiness(() => mutable, { request } as any)
    const pending = flow.inspect(); mutable.actorId = '79000000-0000-4000-8000-000000000009'; resolve(value()); await pending
    expect(flow.result.value).toBeNull()
    const invalidRequest = vi.fn()
    const invalid = createProductionReadiness(() => ({ ...scope, permissionVersion: 9007199254740991 }), { request: invalidRequest } as any)
    await invalid.inspect(); expect(invalidRequest).not.toHaveBeenCalled()
  })
})

describe('exact production readiness evidence branches', () => {
  it('accepts the nine observed checks and returns detached evidence', () => {
    const raw = value(), parsed = readProductionReadiness(raw, scope)
    expect(parsed).toEqual(raw); expect(parsed).not.toBe(raw)
    raw.checks[0]!.status = 'BLOCKED'; expect(parsed.checks[0]!.status).toBe('PASS')
  })
  it('accepts only five checks with explicit missing observation when the database is unavailable', () => {
    const raw = unobserved()
    expect(readProductionReadiness(raw, scope).checks).toHaveLength(5)
    expect(readProductionReadiness(raw, scope).runtime_database_restricted).toBe(false)
    expect(readProductionReadiness(raw, scope).database_version).toBeNull()
  })
  it.each(Object.entries(databaseCodes))('accepts an observed blocked %s only with the matching failure code and aggregate false', (id, codes) => {
    const raw = value(); raw.runtime_database_restricted = false
    Object.assign(raw.checks.find(check => check.id === id)!, { status: 'BLOCKED', code: codes[1] })
    expect(readProductionReadiness(raw, scope).runtime_database_restricted).toBe(false)
    expect(() => readProductionReadiness({ ...raw, runtime_database_restricted: true }, scope)).toThrow(ApiError)
    raw.checks.find(check => check.id === id)!.code = codes[0]
    expect(() => readProductionReadiness(raw, scope)).toThrow(ApiError)
  })
  it('requires the runtime aggregate to agree in both directions, independently of TLS settings', () => {
    expect(() => readProductionReadiness({ ...value(), runtime_database_restricted: false }, scope)).toThrow(ApiError)
    const raw = value(); Object.assign(raw.checks.find(check => check.id === 'SECURE_SESSION_SETTINGS')!, { status: 'PASS', code: 'DIRECT_TLS_SETTINGS_VALID' })
    expect(readProductionReadiness(raw, scope).production_ready).toBe(false)
  })
  it.each(['DATABASE_OBSERVATION', 'PUBLIC_TLS_CERTIFICATE', 'PRODUCTION_DATABASE_ISOLATION', 'PRODUCTION_RUNTIME'])('rejects invented PASS for %s', id => {
    const raw = id === 'DATABASE_OBSERVATION' ? unobserved() : value()
    raw.checks.find(check => check.id === id)!.status = 'PASS'
    expect(() => readProductionReadiness(raw, scope)).toThrow(ApiError)
  })
  it('rejects code mismatches even when every status still looks correct', () => {
    for (const base of [value(), unobserved()]) for (const check of base.checks) {
      const raw = structuredClone(base); raw.checks.find(entry => entry.id === check.id)!.code = 'UNVERIFIED_CODE'
      expect(() => readProductionReadiness(raw, scope), check.id).toThrow(ApiError)
    }
  })
  it('rejects partial, hybrid, duplicate and private-field check sets', () => {
    const raw = value(), missing = unobserved()
    for (const checks of [raw.checks.slice(1), [...missing.checks, raw.checks[0]], raw.checks.map((check, index) => index === 0 ? missing.checks[0] : check),
      raw.checks.map((check, index) => index === 0 ? raw.checks[1] : check), raw.checks.map((check, index) => index === 0 ? { ...check, note: 'extra' } : check),
      raw.checks.map((check, index) => index === 0 ? { ...check, id: 'UNRECOGNIZED' } : check)]) {
      expect(() => readProductionReadiness({ ...raw, checks }, scope)).toThrow(ApiError)
    }
  })
  it.each([
    { database_observed_at: null }, { database_version: null }, { database_version: '' }, { database_version: ' ' },
    { database_observed_at: '2026-02-30T00:00:00Z' }, { database_observed_at: '2026-10-23T12:00:00+00:00' },
    { runtime_database_restricted: 'true' }, { runtime_mode: 'local_fixture' }, { production_database_kind: 'POSTGRESQL' },
    { production_isolation_strategy: 'SHARED' }, { activation_performed: true }, { production_ready: true }, { extra: 'unverified' },
  ])('rejects unsupported or contradictory envelope evidence %j', patch => {
    expect(() => readProductionReadiness({ ...value(), ...patch }, scope)).toThrow(ApiError)
  })
  it('does not mix either observation branch with the other branch metadata', () => {
    expect(() => readProductionReadiness({ ...unobserved(), database_version: 'PostgreSQL synthetic' }, scope)).toThrow(ApiError)
    expect(() => readProductionReadiness({ ...unobserved(), database_observed_at: value().database_observed_at, database_version: value().database_version }, scope)).toThrow(ApiError)
    expect(() => readProductionReadiness({ ...value(), runtime_database_restricted: false, database_observed_at: null, database_version: null }, scope)).toThrow(ApiError)
  })
})
