import { describe, expect, it, vi } from 'vitest'
import { createPageReload } from '../src/utils/pageRecovery'
describe('explicit page recovery', () => {
  it('reloads only after every pending-write guard approves', async () => {
    const reload = vi.fn(), approve = vi.fn().mockResolvedValue(false)
    const refresh = createPageReload({ approve, generation: () => 1, reload })
    expect(await refresh()).toBe(false); expect(reload).not.toHaveBeenCalled()
    approve.mockResolvedValue(true)
    expect(await refresh()).toBe(true); expect(reload).toHaveBeenCalledOnce()
  })
  it('rejects repeat clicks while a discard confirmation is open', async () => {
    let complete!: (value: boolean) => void
    const approve = vi.fn(() => new Promise<boolean>(resolve => { complete = resolve })), reload = vi.fn()
    const refresh = createPageReload({ approve, generation: () => 1, reload })
    const first = refresh(); expect(await refresh()).toBe(false)
    complete(true); expect(await first).toBe(true)
    expect(approve).toHaveBeenCalledOnce(); expect(reload).toHaveBeenCalledOnce()
  })
  it('does not reuse approval from an expired tenant or session', async () => {
    let generation = 1
    const reload = vi.fn(), refresh = createPageReload({ approve: async () => { generation++; return true }, generation: () => generation, reload })
    expect(await refresh()).toBe(false); expect(reload).not.toHaveBeenCalled()
  })
  it('keeps the page when guard evaluation fails and allows another attempt', async () => {
    const reload = vi.fn(), approve = vi.fn().mockRejectedValueOnce(new Error('guard failure')).mockResolvedValueOnce(true)
    const refresh = createPageReload({ approve, generation: () => 1, reload })
    expect(await refresh()).toBe(false); expect(await refresh()).toBe(true)
    expect(reload).toHaveBeenCalledOnce()
  })
})
