// @vitest-environment jsdom
// Pure component/state checks. Network substitutes here are not integration evidence.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import TicketDetailOverview from '../src/components/TicketDetailOverview.vue'
import { session, contextGeneration } from '../src/stores/session'
import { api, ApiError } from '../src/api/client'
import type { Session } from '../src/api/types'
import { ticket, id } from './ticketReader.fixture'
import { row } from './ownWorklog.fixture'

const auth: Session = { user: { id: id(2), role: 'msp', display_name: '合成处理人' }, tenant: { id: id(1), name: '合成客户' }, allowed_tenants: [], capabilities: ['tickets:read', 'ticket_notes:read_internal', 'ticket_comments:read', 'worklogs:read_own'], permission_version: 7, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'project_local' }
const empty = (scope: string) => ({ items: [], has_more: false, next_before_created_at: null, next_before_id: null, source: 'postgresql', scope, page_size_limit: 100 })
let app: App | undefined, host: HTMLElement, state: any
let props: { ticket: ReturnType<typeof ticket>; refreshKey: number }
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
function mount() {
  host = document.createElement('div'); document.body.appendChild(host)
  const component = { ...TicketDetailOverview, render: () => null } as unknown as Component
  app = createApp({ render: () => h(component, { ...props, ref: (value: any) => { if (value) state = value.$.setupState } }) }); app.mount(host)
}
beforeEach(() => {
  session.value = structuredClone(auth); contextGeneration.value = 0; props = reactive({ ticket: ticket(), refreshKey: 0 })
  vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path.includes('/internal-notes')) return empty('CURRENT_TENANT_INTERNAL')
    if (path.includes('/comments')) return empty('REPORTER_OR_CURRENT_TENANT_INTERNAL')
    if (path.includes('/worklogs')) return { ...empty('OWN'), items: [row({ tenant_id: id(1), ticket_id: id(10), owner_id: id(2) })] }
    return ticket()
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('reads all authorized panels and sums only this user’s validated worklogs', async () => {
  mount(); await settle()
  expect(state.activity).toHaveLength(1)
  expect(state.summary.totalMillis).toBe(49n * 3600000n)
  expect(state.handling).toBe(49n * 3600000n)
  expect(state.commute).toBe(0n)
  expect(state.exportReady).toBe(true)
  expect(vi.mocked(api.request).mock.calls.every(([, options]) => !options?.method && !options?.body)).toBe(true)
})

it('never requests or exposes internal notes or worklogs to customer roles', async () => {
  session.value = { ...auth, user: { ...auth.user, role: 'viewer' } }
  mount(); await settle()
  expect(vi.mocked(api.request).mock.calls.map(([path]) => path).filter(path => path.includes('/internal-notes') || path.includes('/worklogs'))).toEqual([])
  expect(state.summary).toBeNull()
  expect(state.canNotes).toBe(false)
  expect(state.canWorklogs).toBe(false)
})

it('clears old totals and prevents export after a failed refresh', async () => {
  mount(); await settle(); expect(state.exportReady).toBe(true)
  vi.mocked(api.request).mockRejectedValue(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成网络失败', 'test'))
  await state.refresh(); await settle()
  expect(state.summary).toBeNull(); expect(state.exportReady).toBe(false)
  expect(state.worklogs.error.status).toBe(503)
  expect(state.activity).toHaveLength(1) // independently known registration history survives
})

it('ignores late responses after tenant or permission context changes and masks the contact again', async () => {
  let finish!: (value: unknown) => void
  vi.mocked(api.request).mockImplementation(path => path.includes('/worklogs') ? new Promise(resolve => { finish = resolve }) : Promise.resolve(path.includes('/internal-notes') ? empty('CURRENT_TENANT_INTERNAL') : path.includes('/comments') ? empty('REPORTER_OR_CURRENT_TENANT_INTERNAL') : ticket()))
  mount(); await settle(); state.showPhone = true
  session.value = { ...auth, tenant: { id: id(99), name: '另一个合成客户' } }; await settle()
  finish({ ...empty('OWN'), items: [row({ tenant_id: id(1), ticket_id: id(10), owner_id: id(2) })] }); await settle()
  expect(state.permitted).toBe(false); expect(state.summary).toBeNull(); expect(state.activity).toEqual([]); expect(state.showPhone).toBe(false)
})

it('refreshes after a confirmed saved event without issuing any writes', async () => {
  mount(); await settle(); const before = vi.mocked(api.request).mock.calls.length
  props.refreshKey++; await settle()
  expect(vi.mocked(api.request).mock.calls.length).toBe(before + 6)
  expect(state.exportReady).toBe(true)
})

it('does not accept another user’s worklogs into either the table or totals', async () => {
  vi.mocked(api.request).mockImplementation(async path => path.includes('/worklogs') ? { ...empty('OWN'), items: [row({ tenant_id: id(1), ticket_id: id(10), owner_id: id(99) })] } : path.includes('/internal-notes') ? empty('CURRENT_TENANT_INTERNAL') : path.includes('/comments') ? empty('REPORTER_OR_CURRENT_TENANT_INTERNAL') : ticket())
  mount(); await settle()
  expect(state.summary).toBeNull(); expect(state.exportReady).toBe(false)
  expect(state.worklogs.error.code).toBe('INVALID_WORKLOG_RESPONSE')
})
