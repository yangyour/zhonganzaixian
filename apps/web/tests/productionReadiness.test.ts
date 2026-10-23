import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../src/api/client'
import { createProductionReadiness, type ProductionReadiness } from '../src/utils/productionReadiness'

const value = (): ProductionReadiness => ({ tenant_id: 'tenant-unit', actor_id: 'actor-unit', permission_version: 8,
  runtime_mode: 'remote_test', runtime_database_restricted: true, database_observed_at: '2026-10-23T12:00:00Z',
  database_version: 'SYNTHETIC_UNIT_PG', production_database_kind: null, production_isolation_strategy: null,
  production_ready: false, activation_performed: false,
  checks: [
    ...['DATABASE_NAMESPACE', 'DATABASE_ROLE', 'DATABASE_DDL', 'MIGRATION_IDENTITY', 'GLOBAL_DATABASE_CAPABILITIES']
      .map(id => ({ id, status: 'PASS' as const, code: 'UNIT_ONLY' })),
    { id: 'SECURE_SESSION_SETTINGS', status: 'BLOCKED', code: 'UNIT_ONLY' },
    { id: 'PUBLIC_TLS_CERTIFICATE', status: 'NOT_RUN', code: 'PUBLIC_ENTRY_PROBE_REQUIRED' },
    { id: 'PRODUCTION_DATABASE_ISOLATION', status: 'CUSTOMER_GATED', code: 'Q02_PENDING' },
    { id: 'PRODUCTION_RUNTIME', status: 'BLOCKED', code: 'REMOTE_TEST_IS_NOT_PRODUCTION' },
  ],
})
const setup = () => {
  const request = vi.fn().mockResolvedValue(value())
  return { request, flow: createProductionReadiness(() => ({ tenantId: 'tenant-unit', actorId: 'actor-unit', permissionVersion: 8 }), { request }) }
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
})
