import { expect, it, vi } from 'vitest'
import { createPageResumeGuard } from '../src/utils/pageResumeGuard'
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(done => { resolve = done }); return { promise, resolve } }
it('masks a protected snapshot synchronously and only reveals after fresh verification', async () => {
  const check = deferred(), mask = vi.fn(), changed = vi.fn()
  const guard = createPageResumeGuard({ hasSession: () => true, verify: () => check.promise, mask, changed })
  guard.suspend(); expect(mask).toHaveBeenLastCalledWith(true); expect(changed).toHaveBeenLastCalledWith('suspended')
  const result = guard.resume(); expect(changed).toHaveBeenLastCalledWith('checking')
  check.resolve(); expect(await result).toBe(true); expect(mask).toHaveBeenLastCalledWith(false)
})
it('keeps failed authentication reads masked and retries only on request', async () => {
  const verify = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(undefined), mask = vi.fn(), changed = vi.fn()
  const guard = createPageResumeGuard({ hasSession: () => true, verify, mask, changed })
  guard.suspend(); expect(await guard.resume()).toBe(false)
  expect(mask).not.toHaveBeenCalledWith(false); expect(changed).toHaveBeenLastCalledWith('failed'); expect(verify).toHaveBeenCalledOnce()
  expect(await guard.resume()).toBe(true); expect(verify).toHaveBeenCalledTimes(2)
})
it('allows the cleared login view after forced expiry without revealing the old session', async () => {
  let session = true
  const mask = vi.fn(), guard = createPageResumeGuard({ hasSession: () => session, verify: async () => { session = false; throw new Error('401') }, mask, changed: vi.fn() })
  guard.suspend(); await guard.resume(); expect(mask).toHaveBeenLastCalledWith(false)
})
it('does not duplicate a pending verification and rejects its late result after suspension', async () => {
  const check = deferred(), verify = vi.fn(() => check.promise), mask = vi.fn()
  const guard = createPageResumeGuard({ hasSession: () => true, verify, mask, changed: vi.fn() })
  guard.suspend(); const first = guard.resume(); expect(await guard.resume()).toBe(false)
  guard.suspend(); check.resolve(); expect(await first).toBe(false)
  expect(mask).not.toHaveBeenCalledWith(false); expect(verify).toHaveBeenCalledOnce()
})
it('does not turn an anonymous restored login form into an authenticated session', async () => {
  const verify = vi.fn(), mask = vi.fn(), guard = createPageResumeGuard({ hasSession: () => false, verify, mask, changed: vi.fn() })
  guard.suspend(); expect(await guard.resume()).toBe(false); expect(verify).not.toHaveBeenCalled(); expect(mask).not.toHaveBeenCalled()
})
it('ignores completion after disposal', async () => {
  const check = deferred(), changed = vi.fn(), guard = createPageResumeGuard({ hasSession: () => true, verify: () => check.promise, mask: vi.fn(), changed })
  guard.suspend(); const pending = guard.resume(); guard.dispose(); check.resolve(); expect(await pending).toBe(false)
  expect(changed).toHaveBeenLastCalledWith('checking')
})
