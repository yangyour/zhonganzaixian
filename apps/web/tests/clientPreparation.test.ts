import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient, type RequestOptions } from '../src/api/client'

const ok = () => new Response(JSON.stringify({ data: {}, trace_id: 'test' }))
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('request preparation and cancellation boundaries', () => {
  it.each(['json', 'clock', 'blob'] as const)('does not dispatch an already cancelled %s request', async kind => {
    const transport = vi.fn(async () => ok()), client = new ApiClient(transport)
    const controller = new AbortController(); controller.abort()
    const options = { signal: controller.signal }
    const pending = kind === 'blob' ? client.requestBlob('/files/id/content', options) : kind === 'clock' ? client.requestWithClock('/assets', options) : client.request('/assets', options)
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    expect(transport).not.toHaveBeenCalled()
  })
  it('removes the listener and controller when secure randomness fails before dispatch', async () => {
    vi.stubGlobal('crypto', {})
    const signal = new AbortController().signal, remove = vi.spyOn(signal, 'removeEventListener')
    const transport = vi.fn(async () => ok()), client = new ApiClient(transport)
    const abort = vi.spyOn(AbortController.prototype, 'abort')
    await expect(client.request('/assets', { method: 'POST', body: {}, signal })).rejects.toBeInstanceOf(Error)
    expect(transport).not.toHaveBeenCalled(); expect(remove).toHaveBeenCalledTimes(1)
    client.invalidateContext(); expect(abort).not.toHaveBeenCalled()
  })
  it.each(['invalidate', 'abort'] as const)('does not dispatch after body serialization causes %s', async action => {
    const controller = new AbortController(), transport = vi.fn(async () => ok()), client = new ApiClient(transport)
    const body = { toJSON() { action === 'invalidate' ? client.invalidateContext() : controller.abort(); return {} } }
    await expect(client.request('/assets', { method: 'POST', body, signal: controller.signal, idempotencyKey: 'same-key' })).rejects.toMatchObject({ name: 'AbortError' })
    expect(transport).not.toHaveBeenCalled()
  })
  it('cleans up the originally registered signal when options mutate during serialization', async () => {
    const original = new AbortController().signal, replacement = new AbortController().signal
    const removeOriginal = vi.spyOn(original, 'removeEventListener'), removeReplacement = vi.spyOn(replacement, 'removeEventListener')
    const client = new ApiClient(vi.fn(async () => ok()))
    const options: RequestOptions = { method: 'POST', signal: original, idempotencyKey: 'same-key' }
    options.body = { toJSON() { options.signal = replacement; return {} } }
    await client.request('/assets', options)
    expect(removeOriginal).toHaveBeenCalledTimes(1); expect(removeReplacement).not.toHaveBeenCalled()
  })
  it('cleans up when JSON serialization fails without leaking exception details', async () => {
    const signal = new AbortController().signal, remove = vi.spyOn(signal, 'removeEventListener')
    const transport = vi.fn(async () => ok()), client = new ApiClient(transport)
    const abort = vi.spyOn(AbortController.prototype, 'abort')
    const body = { toJSON() { throw new Error('private body detail') } }
    await expect(client.request('/assets', { method: 'POST', body, signal, idempotencyKey: 'same-key' })).rejects.toMatchObject({ code: 'NETWORK_ERROR', traceId: '—' })
    expect(transport).not.toHaveBeenCalled(); expect(remove).toHaveBeenCalledTimes(1)
    client.invalidateContext(); expect(abort).not.toHaveBeenCalled()
  })
  it('retains uncertain network failure when the transport throws after invocation', async () => {
    const client = new ApiClient(vi.fn(() => { throw new Error('private transport detail') }))
    await expect(client.request('/assets', { method: 'POST', body: {}, idempotencyKey: 'same-key' })).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })
})
