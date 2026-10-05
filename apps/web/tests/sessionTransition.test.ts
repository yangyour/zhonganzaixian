import { beforeEach, describe, it, expect, vi } from 'vitest'
import type { Session } from '../src/api/types'
const mocked = vi.hoisted(() => ({ request: vi.fn(), invalidate: vi.fn(), token: vi.fn(), approve: vi.fn() }))
vi.mock('../src/api/client', async original => ({ ...await original<typeof import('../src/api/client')>(), api: { request: mocked.request, invalidateContext: mocked.invalidate, setCsrfToken: mocked.token } }))
vi.mock('../src/utils/contextTransitionGuard', () => ({ contextTransitionGuards: { approve: mocked.approve } }))
beforeEach(() => { vi.resetModules(); vi.clearAllMocks(); mocked.approve.mockResolvedValue(true); mocked.invalidate.mockReturnValue(1) })
describe('session voluntary transitions', () => {
  it('denied transition neither invalidates context nor calls auth API', async () => { mocked.approve.mockResolvedValue(false); const store = await import('../src/stores/session'); expect(await store.switchTenant('next')).toBe(false); expect(await store.logout()).toBe('cancelled'); expect(mocked.invalidate).not.toHaveBeenCalled(); expect(mocked.request).not.toHaveBeenCalled(); expect(store.switchingTenant.value).toBe(false) })
  it('keeps current view while guard awaits and blocks overlapping logout', async () => { let resolve!: (v: boolean) => void; mocked.approve.mockImplementation(() => new Promise(r => { resolve = r })); const store = await import('../src/stores/session'); const pending = store.switchTenant('next'); expect(store.switchingTenant.value).toBe(false); expect(await store.logout()).toBe('cancelled'); expect(await store.switchTenant('other')).toBe(false); resolve(false); expect(await pending).toBe(false); expect(mocked.request).not.toHaveBeenCalled() })
  it('holds context lock through server request, not just confirmation', async () => { let resolve!: (v: unknown) => void; mocked.request.mockImplementation(() => new Promise(r => { resolve = r })); const store = await import('../src/stores/session'); const pending = store.logout(); await Promise.resolve(); await Promise.resolve(); expect(await store.switchTenant('next')).toBe(false); expect(await store.logout()).toBe('cancelled'); resolve({ redirect_url: null }); expect(await pending).toBe('local'); expect(mocked.request).toHaveBeenCalledTimes(1) })
  it('forced expiry clears protected data without asking unsaved draft guard', async () => { mocked.approve.mockResolvedValue(false); const store = await import('../src/stores/session'); store.forgetSession(); expect(mocked.invalidate).toHaveBeenCalledTimes(1); expect(mocked.approve).not.toHaveBeenCalled(); expect(store.session.value).toBeNull() })
  it('approved switch installs returned context and releases lock', async () => { const value = { tenant: { id: 'next' }, csrf_token: 'synthetic-test-only', capabilities: [], user: { role: 'padmin' } }; mocked.request.mockResolvedValue(value); const store = await import('../src/stores/session'); expect(await store.switchTenant('next')).toBe(true); expect(store.session.value).toEqual(value); expect(store.switchingTenant.value).toBe(false); expect(await store.switchTenant('next')).toBe(false); expect(mocked.request).toHaveBeenCalledTimes(1) })
})

const sessionValue = (tenant: string): Session => ({ tenant: { id: tenant, name: 'synthetic-' + tenant }, csrf_token: 'SYNTHETIC_ONLY', capabilities: [], user: { id: 'synthetic-actor', display_name: 'Synthetic unit user', role: 'padmin' }, permission_version: 1, allowed_tenants: [], mode: 'remote_test', identity_source: 'synthetic-unit-only' })
describe('tenant scope navigation after uncertain switch', () => {
  it('clears observed new-scope selections while preserving the original switch failure', async () => {
    const store = await import('../src/stores/session'), failure = new TypeError('synthetic lost response')
    store.session.value = sessionValue('old')
    mocked.request.mockRejectedValueOnce(failure).mockResolvedValueOnce(sessionValue('next'))
    const cleanup = vi.fn(async transition => { expect(store.switchingTenant.value).toBe(true); expect(transition).toEqual({ fromTenantId: 'old', toTenantId: 'next', originalReceiptConfirmed: false }) })
    await expect(store.switchTenant('next', cleanup)).rejects.toBe(failure)
    expect(cleanup).toHaveBeenCalledTimes(1); expect(store.session.value?.tenant.id).toBe('next'); expect(store.switchingTenant.value).toBe(false)
    expect(mocked.request.mock.calls.map(c => c[0])).toEqual(['/session/tenant', '/session'])
  })
  it('same-scope readback does not clear the existing view or claim the original mutation failed to commit', async () => {
    const store = await import('../src/stores/session'), failure = new Error('lost'), cleanup = vi.fn()
    store.session.value = sessionValue('old')
    mocked.request.mockRejectedValueOnce(failure).mockResolvedValueOnce(sessionValue('old'))
    await expect(store.switchTenant('next', cleanup)).rejects.toBe(failure); expect(cleanup).not.toHaveBeenCalled(); expect(store.session.value?.tenant.id).toBe('old')
  })
  it('cleans the actually observed tenant rather than inventing success for the requested target', async () => {
    const store = await import('../src/stores/session'), failure = new Error('lost'), cleanup = vi.fn()
    store.session.value = sessionValue('old')
    mocked.request.mockRejectedValueOnce(failure).mockResolvedValueOnce(sessionValue('third'))
    await expect(store.switchTenant('next', cleanup)).rejects.toBe(failure); expect(cleanup).toHaveBeenCalledWith({ fromTenantId: 'old', toTenantId: 'third', originalReceiptConfirmed: false })
  })
  it('keeps the view hidden and context action locked until navigation cleanup completes', async () => {
    const store = await import('../src/stores/session'); store.session.value = sessionValue('old')
    mocked.request.mockResolvedValueOnce(sessionValue('next'))
    let finish!: () => void; const cleanup = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    const pending = store.switchTenant('next', cleanup); await vi.waitFor(() => expect(cleanup).toHaveBeenCalledTimes(1))
    expect(store.switchingTenant.value).toBe(true); expect(await store.switchTenant('other')).toBe(false); expect(await store.logout()).toBe('cancelled')
    finish(); expect(await pending).toBe(true); expect(store.switchingTenant.value).toBe(false); expect(mocked.request).toHaveBeenCalledTimes(1)
  })
  it('initial successful response cleanup is explicitly receipt-confirmed', async () => {
    const store = await import('../src/stores/session'), cleanup = vi.fn(); store.session.value = sessionValue('old')
    mocked.request.mockResolvedValueOnce(sessionValue('next')); expect(await store.switchTenant('next', cleanup)).toBe(true)
    expect(cleanup).toHaveBeenCalledWith({ fromTenantId: 'old', toTenantId: 'next', originalReceiptConfirmed: true })
  })
  it('navigation error never retries the original POST or triggers another authoritative read', async () => {
    const store = await import('../src/stores/session'), navigationFailure = new Error('synthetic route failure'); store.session.value = sessionValue('old')
    mocked.request.mockResolvedValueOnce(sessionValue('next'))
    const failure = await store.switchTenant('next', async () => { throw navigationFailure }).catch(e => e)
    expect(failure).toBeInstanceOf(store.TenantViewSynchronizationError); expect(failure.originalReceiptConfirmed).toBe(true); expect(failure.navigationFailure).toBe(navigationFailure)
    expect(mocked.request).toHaveBeenCalledTimes(1); expect(store.switchingTenant.value).toBe(false)
  })
  it('dual switch/navigation failures preserve both causes without echoing raw exception text', async () => {
    const store = await import('../src/stores/session'), originalFailure = new Error('SYNTHETIC_PRIVATE_ERROR'), navigationFailure = new Error('SYNTHETIC_PRIVATE_ROUTE')
    store.session.value = sessionValue('old'); mocked.request.mockRejectedValueOnce(originalFailure).mockResolvedValueOnce(sessionValue('next'))
    const failure = await store.switchTenant('next', async () => { throw navigationFailure }).catch(e => e)
    expect(failure.originalFailure).toBe(originalFailure); expect(failure.navigationFailure).toBe(navigationFailure); expect(failure.originalReceiptConfirmed).toBe(false)
    expect(failure.message).not.toContain('SYNTHETIC_PRIVATE'); expect(mocked.request).toHaveBeenCalledTimes(2)
  })
  it('failed authoritative read clears session and does not resume old-scope navigation', async () => {
    const store = await import('../src/stores/session'), failure = new Error('lost'), cleanup = vi.fn(); store.session.value = sessionValue('old')
    mocked.request.mockRejectedValueOnce(failure).mockRejectedValueOnce(new Error('read unavailable'))
    await expect(store.switchTenant('next', cleanup)).rejects.toBe(failure); expect(store.session.value).toBeNull(); expect(cleanup).not.toHaveBeenCalled(); expect(store.switchingTenant.value).toBe(false)
  })
  it('denied guard never invokes cleanup or unmounts existing dirty editors', async () => {
    const store = await import('../src/stores/session'), cleanup = vi.fn(); store.session.value = sessionValue('old'); mocked.approve.mockResolvedValueOnce(false)
    expect(await store.switchTenant('next', cleanup)).toBe(false); expect(cleanup).not.toHaveBeenCalled(); expect(store.switchingTenant.value).toBe(false); expect(mocked.request).not.toHaveBeenCalled()
  })
  it('forced expiry during cleanup cannot return a current successful context', async () => {
    const store = await import('../src/stores/session'); store.session.value = sessionValue('old'); mocked.request.mockResolvedValueOnce(sessionValue('next')); mocked.invalidate.mockReturnValueOnce(1).mockReturnValueOnce(2)
    expect(await store.switchTenant('next', async () => { store.forgetSession() })).toBe(false); expect(store.session.value).toBeNull(); expect(store.switchingTenant.value).toBe(false)
  })
  it('a successful HTTP response for another tenant is not an original target receipt',async()=>{
    const store=await import('../src/stores/session'),cleanup=vi.fn();store.session.value=sessionValue('old')
    mocked.request.mockResolvedValueOnce(sessionValue('unexpected')).mockResolvedValueOnce(sessionValue('observed'))
    const failure=await store.switchTenant('next',cleanup).catch(error=>error)
    expect(failure.code).toBe('INVALID_TENANT_SWITCH_RESPONSE');expect(store.session.value?.tenant.id).toBe('observed')
    expect(cleanup).toHaveBeenCalledWith({fromTenantId:'old',toTenantId:'observed',originalReceiptConfirmed:false});expect(mocked.request).toHaveBeenCalledTimes(2)
  })
  it('navigation wrapper preserves the original API trace without exposing its message',async()=>{
    const store=await import('../src/stores/session'),{ApiError}=await import('../src/api/client');store.session.value=sessionValue('old')
    const original=new ApiError(503,'UPSTREAM_UNAVAILABLE','SYNTHETIC_PRIVATE_ERROR','original-trace')
    mocked.request.mockRejectedValueOnce(original).mockResolvedValueOnce(sessionValue('next'))
    const failure=await store.switchTenant('next',async()=>{throw new Error('SYNTHETIC_PRIVATE_ROUTE')}).catch(error=>error)
    expect(failure.traceId).toBe('original-trace');expect(failure.message).not.toContain('SYNTHETIC_PRIVATE');expect(failure.originalReceiptConfirmed).toBe(false)
  })

})
