import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError, StaleRequestError } from '../src/api/client'

function response(data: unknown, status = 200) { return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } }) }
describe('API context and error boundaries', () => {
  it('aborts active requests and rejects a late result even when the transport ignores abort', async () => {
    let finish!: (response: Response) => void
    let signal: AbortSignal | undefined
    const transport = vi.fn(async (_url, options) => { signal = options?.signal as AbortSignal; return await new Promise<Response>(resolve => { finish = resolve }) })
    const client = new ApiClient(transport)
    const pending = client.request('/assets')
    const rejected = expect(pending).rejects.toBeInstanceOf(StaleRequestError)
    client.invalidateContext()
    expect(signal?.aborted).toBe(true)
    finish(response({ data: { items: [{ tenant_id: 'old' }] }, trace_id: 'old-trace' }))
    await rejected
  })
  it('an old tenant 401 cannot log out the current tenant', async () => {
    let finish!: (response: Response) => void
    const client = new ApiClient(vi.fn(() => new Promise<Response>(resolve => { finish = resolve })))
    client.onUnauthorized = vi.fn()
    const pending = client.request('/assets')
    const rejected = expect(pending).rejects.toBeInstanceOf(StaleRequestError)
    client.invalidateContext()
    finish(response({ error: { code: 'SESSION_EXPIRED', message: 'expired' }, trace_id: 'old-trace' }, 401))
    await rejected
    expect(client.onUnauthorized).not.toHaveBeenCalled()
  })
  it('a current 401 invokes login handling and preserves error trace_id', async () => {
    const client = new ApiClient(vi.fn(async () => response({ error: { code: 'SESSION_EXPIRED', message: '会话失效' }, trace_id: 'tr-auth' }, 401)))
    client.onUnauthorized = vi.fn(() => client.invalidateContext())
    await expect(client.request('/session')).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED', traceId: 'tr-auth' })
    expect(client.onUnauthorized).toHaveBeenCalledTimes(1)
  })
  it('writes use the current CSRF token and independent idempotency keys', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => response({ data: { id: 'new' }, trace_id: 'tr-write' }))
    const client = new ApiClient(transport); client.setCsrfToken('csrf-a')
    await client.request('/assets', { method: 'POST', body: { name: 'A' } })
    client.setCsrfToken('csrf-b')
    await client.request('/assets', { method: 'POST', body: { name: 'B' } })
    const first = transport.mock.calls[0]![1] as RequestInit
    const second = transport.mock.calls[1]![1] as RequestInit
    expect(first.credentials).toBe('same-origin')
    expect(first.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-a' })
    expect(second.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-b' })
    expect((first.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
    expect((first.headers as Record<string, string>)['Idempotency-Key']).not.toEqual((second.headers as Record<string, string>)['Idempotency-Key'])
  })
  it('keeps CONFIG_PENDING details and null configuration values intact', async () => {
    const client = new ApiClient(vi.fn(async () => response({ error: { code: 'CONFIG_PENDING', message: '待客户确认', details: [{ question_ids: ['Q02', 'Q03'] }] }, trace_id: 'tr-config' }, 422)))
    await expect(client.request('/configurations/a/publish', { method: 'POST', body: { expected_version: 1 } })).rejects.toMatchObject({ code: 'CONFIG_PENDING', traceId: 'tr-config', details: [{ question_ids: ['Q02', 'Q03'] }] })
  })
  it('cannot accept malformed successful responses as completed writes', async () => {
    const client = new ApiClient(vi.fn(async () => response({ unexpected: true })))
    await expect(client.request('/assets', { method: 'POST', body: {} })).rejects.toBeInstanceOf(ApiError)
  })
  it('polls the documented status field and terminates at FAILED without assuming success', async () => {
    const transport = vi.fn().mockResolvedValueOnce(response({ data: { status: 'RUNNING' }, trace_id: 'tr-op1' })).mockResolvedValueOnce(response({ data: { status: 'FAILED', error: { code: 'ADAPTER_UNAVAILABLE' } }, trace_id: 'tr-op2' }))
    const client = new ApiClient(transport)
    const operation = await client.pollOperation('op-1', { intervalMs: 1 })
    expect(operation.status).toBe('FAILED'); expect(transport).toHaveBeenCalledTimes(2)
  })
})
