// @vitest-environment jsdom
import { createApp, h, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Panel from '../src/components/ReportReadershipPanel.vue'
import { api } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { expected, id, readership, tenant } from './reportReadership.fixture'

const auth: Session = { user: { id: id(999), display_name: 'SYNTHETIC', role: 'padmin' },
  tenant: { id: tenant, name: 'SYNTHETIC' }, allowed_tenants: [], permission_version: 7, csrf_token: 'SYNTHETIC',
  mode: 'remote_test', identity_source: 'synthetic-unit-only', capabilities: ['dashboard:read', 'inspection_quarterly_reports:write'] }
let app: App | undefined, host: HTMLElement
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Panel, { id: expected.id, hash: expected.hash, version: expected.version }) })
  app.mount(host); await settle()
}
function button(label: string) { return [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === label)! }
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value++ })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('shows real page totals, switches filters from first page, and issues GETs only', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(readership())
  await mount()
  expect(host.textContent).toContain('接收人 52 · 已读 1 · 未读 51 · 超过 7 天未读 51')
  request.mockResolvedValueOnce({ ...readership(), items: [], next_after: null })
  button('阅读记录下一页').click(); await settle()
  expect(request.mock.calls.at(-1)![0]).toContain('after=' + id(50))
  request.mockResolvedValueOnce({ ...readership(), filter: 'UNREAD', matching_count: 51,
    items: readership().items.slice(1).concat({ customer_id: id(51), display_name: null, first_read_at: null, followup_due: true }), next_after: id(51) })
  const select = host.querySelector('select')!; select.value = 'UNREAD'; select.dispatchEvent(new Event('change')); await settle()
  expect(request.mock.calls.at(-1)![0]).toBe(`/report-publications/${expected.id}/readership?filter=UNREAD`)
  expect(request.mock.calls.every(([, options]) => options?.method === undefined)).toBe(true)
})
it('retries the failed current cursor and never falls back to the first page', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(readership())
  await mount(); request.mockRejectedValueOnce(new Error('synthetic timeout'))
  button('阅读记录下一页').click(); await settle()
  const failedPath = request.mock.calls.at(-1)![0]
  button('重试当前阅读页').click(); await settle()
  expect(request.mock.calls.at(-1)![0]).toBe(failedPath)
})
it('aborts and discards late recipient names after forced revocation', async () => {
  let finish!: (value: unknown) => void
  const request = vi.spyOn(api, 'request').mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await mount(); session.value = { ...auth, capabilities: ['dashboard:read'] }; await settle()
  expect(request.mock.calls[0][1]?.signal?.aborted).toBe(true)
  finish(readership()); await settle()
  expect(host.textContent).not.toContain('SYNTHETIC 接收人')
})
it('customer roles never request another recipient list', async () => {
  session.value = { ...auth, capabilities: ['dashboard:read'], user: { ...auth.user, role: 'viewer' } }
  const request = vi.spyOn(api, 'request')
  await mount(); expect(request).not.toHaveBeenCalled()
})
