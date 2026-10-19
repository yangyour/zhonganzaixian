// @vitest-environment jsdom
import { createApp, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import ConnectionNotice from '../src/components/ConnectionNotice.vue'

let cleanup = () => {}
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function mount(initialOnline = true) {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(initialOnline)
  const request = vi.fn(); vi.stubGlobal('fetch', request)
  const host = document.createElement('div'), input = document.createElement('input')
  document.body.append(input, host)
  const app = createApp(ConnectionNotice); app.mount(host)
  cleanup = () => { app.unmount(); host.remove(); input.remove() }
  const change = async (value: boolean) => { online.mockReturnValue(value); window.dispatchEvent(new Event(value ? 'online' : 'offline')); await nextTick() }
  return { host, input, online, request, change, app }
}
it('does not invent a recovery on initial online state or duplicate online events', async () => {
  const { host, change, request } = mount()
  expect(host.textContent).toBe('')
  await change(true)
  expect(host.textContent).toBe('')
  expect(request).not.toHaveBeenCalled()
})
it('announces offline and recovery without claiming the API or writes have recovered', async () => {
  const { host, change, request } = mount()
  await change(false)
  expect(host.textContent).toContain('网络连接已断开')
  expect(host.textContent).toContain('页面不会自动重发')
  expect(host.querySelector('[role="status"][aria-live="polite"]')).not.toBeNull()
  await change(true)
  expect(host.textContent).toContain('网络连接已恢复')
  expect(host.textContent).toContain('请手动刷新以获取最新数据')
  expect(host.textContent).toContain('请先核对结果')
  expect(host.querySelector('details')?.open).toBe(true)
  expect(request).not.toHaveBeenCalled()
})
it('respects an initially offline browser and reports its first recovery', async () => {
  const { host, change } = mount(false)
  expect(host.textContent).toContain('网络连接已断开')
  await change(true)
  expect(host.querySelector('details')?.open).toBe(true)
})
it('keeps a collapsed recovery hint collapsed on duplicate online events', async () => {
  const { host, change } = mount()
  await change(false); await change(true)
  const details = host.querySelector('details')!
  details.querySelector('summary')!.click()
  expect(details.open).toBe(false)
  await change(true)
  expect(host.querySelector('details')).toBe(details)
  expect(details.open).toBe(false)
})
it('opens fresh guidance after another real offline-to-online transition', async () => {
  const { host, change, request } = mount()
  await change(false); await change(true)
  host.querySelector('summary')!.click()
  await change(false)
  expect(host.querySelector('details')).toBeNull()
  expect(host.textContent).not.toContain('网络连接已恢复')
  await change(true)
  expect(host.querySelector('details')?.open).toBe(true)
  expect(request).not.toHaveBeenCalled()
})
it('does not move focus or clear an editor during network transitions', async () => {
  const { input, change, request } = mount()
  input.value = 'SYNTHETIC_UNSAVED_TEXT'; input.focus()
  await change(false); await change(true)
  expect(document.activeElement).toBe(input)
  expect(input.value).toBe('SYNTHETIC_UNSAVED_TEXT')
  expect(request).not.toHaveBeenCalled()
})
it('removes both network listeners when unmounted', async () => {
  const { host, input, online, app } = mount()
  app.unmount(); cleanup = () => { host.remove(); input.remove() }
  const calls = online.mock.calls.length
  window.dispatchEvent(new Event('offline')); window.dispatchEvent(new Event('online')); await nextTick()
  expect(online).toHaveBeenCalledTimes(calls)
})
