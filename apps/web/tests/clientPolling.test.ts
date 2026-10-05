import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../src/api/client'
const response = (status: string) => new Response(JSON.stringify({ data: { status }, trace_id: 'test' }))
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })
describe('operation polling callback boundaries', () => {
  it.each(['SUCCEEDED', 'RUNNING'])('rejects %s when onUpdate invalidates context', async status => {
    const transport = vi.fn(async () => response(status)), client = new ApiClient(transport)
    await expect(client.pollOperation('op', { intervalMs: 1, onUpdate: () => client.invalidateContext() })).rejects.toMatchObject({ name: 'AbortError' })
    expect(transport).toHaveBeenCalledTimes(1)
  })
  it.each(['SUCCEEDED', 'RUNNING'])('rejects %s when onUpdate aborts the caller signal', async status => {
    const controller = new AbortController(), transport = vi.fn(async () => response(status)), client = new ApiClient(transport)
    await expect(client.pollOperation('op', { intervalMs: 1, signal: controller.signal, onUpdate: () => controller.abort() })).rejects.toMatchObject({ name: 'AbortError' })
    expect(transport).toHaveBeenCalledTimes(1)
  })
  it('cancels the inter-poll delay immediately when context is invalidated', async () => {
    vi.useFakeTimers()
    const transport = vi.fn(async () => response('RUNNING')), client = new ApiClient(transport), updated = vi.fn()
    const pending = client.pollOperation('op', { intervalMs: 60000, onUpdate: updated })
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0); expect(updated).toHaveBeenCalledTimes(1)
    client.invalidateContext(); await rejected
    expect(vi.getTimerCount()).toBe(0); expect(transport).toHaveBeenCalledTimes(1)
  })
  it('cleans up delay listeners after normal completion without aborting finished requests', async () => {
    vi.useFakeTimers()
    const signal = new AbortController().signal, add = vi.spyOn(signal, 'addEventListener'), remove = vi.spyOn(signal, 'removeEventListener')
    const transport = vi.fn().mockResolvedValueOnce(response('RUNNING')).mockResolvedValueOnce(response('SUCCEEDED'))
    const client = new ApiClient(transport), pending = client.pollOperation('op', { signal, intervalMs: 10 })
    await vi.advanceTimersByTimeAsync(10)
    await expect(pending).resolves.toMatchObject({ status: 'SUCCEEDED' })
    expect(add).toHaveBeenCalledTimes(3); expect(remove).toHaveBeenCalledTimes(3); expect(vi.getTimerCount()).toBe(0)
    const abort = vi.spyOn(AbortController.prototype, 'abort'); client.invalidateContext(); expect(abort).not.toHaveBeenCalled()
  })
  it('cancels an active delay from the caller without retaining timers', async () => {
    vi.useFakeTimers()
    const controller = new AbortController(), client = new ApiClient(vi.fn(async () => response('RUNNING')))
    const pending = client.pollOperation('op', { signal: controller.signal, intervalMs: 60000 })
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0); controller.abort(); await rejected
    expect(vi.getTimerCount()).toBe(0)
  })

})
