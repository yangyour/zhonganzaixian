// @vitest-environment jsdom
import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import ConnectionNotice from '../src/components/ConnectionNotice.vue'
it('announces offline state and removes it on reconnect without issuing requests', async () => {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  const host = document.createElement('div'), app = createApp(ConnectionNotice)
  app.mount(host)
  try {
    expect(host.textContent).toBe('')
    online.mockReturnValue(false); window.dispatchEvent(new Event('offline')); await nextTick()
    expect(host.textContent).toContain('页面不会自动重发')
    expect(host.querySelector('[role="status"]')).not.toBeNull()
    online.mockReturnValue(true); window.dispatchEvent(new Event('online')); await nextTick()
    expect(host.textContent).toBe('')
  } finally { app.unmount(); online.mockRestore() }
})
