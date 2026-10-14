import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient, StaleRequestError } from '../src/api/client'
import { errorPresentation } from '../src/utils/errorPresentation'

afterEach(() => vi.useRealTimers())
describe('bounded requests and reliable failure feedback', () => {
  it('bypasses HTTP cache for both protected JSON and binary reads', async () => {
    const transport = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ data: {} }))).mockResolvedValueOnce(new Response('image bytes'))
    const client = new ApiClient(transport)
    await client.request('/session'); await client.requestBlob('/files/x/content')
    expect(transport.mock.calls.map(call => call[1].cache)).toEqual(['no-store', 'no-store'])
  })
  it('times out an uncooperative write without automatic retry or success', async () => {
    vi.useFakeTimers()
    const transport = vi.fn(() => new Promise<Response>(() => {})), client = new ApiClient(transport)
    const request = client.request('/assets', { method: 'POST', body: {}, timeoutMs: 500 })
    const assertion = expect(request).rejects.toMatchObject({ status: 0, code: 'REQUEST_TIMEOUT' })
    await vi.advanceTimersByTimeAsync(500); await assertion
    expect(transport).toHaveBeenCalledOnce()
  })
  it('bounds response body reading too', async () => {
    vi.useFakeTimers()
    const client = new ApiClient(vi.fn(async () => ({ ok: true, text: () => new Promise<string>(() => {}) }) as Response))
    const assertion = expect(client.request('/assets', { timeoutMs: 100 })).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT' })
    await vi.advanceTimersByTimeAsync(100); await assertion
  })
  it('cancels an old tenant immediately even when fetch ignores abort', async () => {
    const client = new ApiClient(vi.fn(() => new Promise<Response>(() => {})))
    const assertion = expect(client.request('/assets')).rejects.toBeInstanceOf(StaleRequestError)
    client.invalidateContext(); await assertion
  })
  it('retains a non-JSON 401 and invokes login handling', async () => {
    const client = new ApiClient(vi.fn(async () => new Response('<html>expired</html>', { status: 401 })))
    client.onUnauthorized = vi.fn()
    await expect(client.request('/assets')).rejects.toMatchObject({ status: 401, code: 'HTTP_401' })
    expect(client.onUnauthorized).toHaveBeenCalledOnce()
  })
  it('retains gateway 503 without showing HTML or declaring a business result', async () => {
    const client = new ApiClient(vi.fn(async () => new Response('<html>upstream private text</html>', { status: 503 })))
    try { await client.request('/assets'); throw new Error('expected rejection') } catch (error) {
      const display = errorPresentation(error)
      expect(display.value).toMatchObject({ status: 503, code: 'HTTP_503' })
      expect(display.title).toBe('服务暂时不可用'); expect(display.guidance).toContain('避免重复提交')
      expect(display.value.message).not.toContain('private')
    }
  })
  it('normalizes malformed error fields before presentation', async () => {
    const client = new ApiClient(vi.fn(async () => new Response(JSON.stringify({ error: { message: {}, details: [null, 'bad', { question_id: 'Q01' }] } }), { status: 422 })))
    await expect(client.request('/assets')).rejects.toMatchObject({ code: 'HTTP_422', details: [{ question_id: 'Q01' }] })
  })
  it('rejects contradictory success/error envelopes', async () => {
    const client = new ApiClient(vi.fn(async () => new Response(JSON.stringify({ data: {}, error: { code: 'FAILED' } }))))
    await expect(client.request('/assets')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('bounds binary requests without leaking late data', async () => {
    vi.useFakeTimers()
    const client = new ApiClient(vi.fn(() => new Promise<Response>(() => {})))
    const assertion = expect(client.requestBlob('/files/x/content', { timeoutMs: 100 })).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT' })
    await vi.advanceTimersByTimeAsync(100); await assertion
  })
  it('uses the same failure normalization for binary reads', async () => {
    const client = new ApiClient(vi.fn(async () => new Response(JSON.stringify({ error: { message: [], details: 'malformed' } }), { status: 403 })))
    await expect(client.requestBlob('/files/x/content')).rejects.toMatchObject({ status: 403, code: 'HTTP_403', details: [] })
  })
})
