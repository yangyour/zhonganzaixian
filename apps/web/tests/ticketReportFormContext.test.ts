// @vitest-environment jsdom
// Actual form/session handlers with synthetic HTTP responses and a null render; not live API acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import TicketReportForm from '../src/components/TicketReportForm.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { context, id } from './ticketReader.fixture'
const auth: Session = { user: { id: context.actorId, role: 'viewer', display_name: '合成报障用户' }, tenant: { id: context.tenantId, name: '合成租户' }, allowed_tenants: [], capabilities: ['tickets:read', 'tickets:report'], permission_version: 7, account_version: 1, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'project_local' }
const policy = { purpose: 'customer_report_photo', allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5_000_000, max_attachments: 3, policy_source: 'USER_DEVELOPMENT_DIRECTION', production_approval: 'UNVERIFIED', scan_status: 'UNSCANNED', retention_until: null }
let app: App | undefined, host: HTMLElement, state: any, props: { modelValue: boolean }
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
function mount() {
  host = document.createElement('div'); document.body.appendChild(host); props = reactive({ modelValue: true })
  const component = { ...TicketReportForm, render: () => null } as unknown as Component
  app = createApp({ render: () => h(component, { ...props, ref: (vm: any) => { if (vm) state = vm.$.setupState }, 'onUpdate:modelValue': (value: boolean) => { props.modelValue = value } }) }); app.mount(host)
  Object.assign(state.form, { title: '待登记故障', description: '合成报障描述', category: '网络不通', impact_scope: 'company', contact_name: '合成联系人', contact_phone: '123456', urgency: 'normal' })
  state.photos.policy.value = policy
}
function addPhoto() { const file = new File(['synthetic'], 'synthetic.jpg', { type: 'image/jpeg' }); expect(state.photos.add(file, 1, 0)).toBeNull(); return file }
const writes = () => vi.mocked(api.request).mock.calls.filter(([, options]) => options?.method)
beforeEach(() => {
  session.value = structuredClone(auth); contextGeneration.value = 0
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:synthetic'), revokeObjectURL: vi.fn() })
  vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path === '/session') return { ...structuredClone(auth), csrf_token: 'SYNTHETIC_REFRESHED_NOT_CREDENTIAL' }
    throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'synthetic unavailable read', 'test')
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('unchanged session read preserves entered report fields and the selected original photo', async () => {
  mount(); const photo = addPhoto(); await refreshProjectSession(); await settle()
  expect(props.modelValue).toBe(true); expect(state.form.description).toBe('合成报障描述'); expect(state.form.contact_phone).toBe('123456')
  expect(state.photos.rows.value[0]?.file).toBe(photo); expect(URL.revokeObjectURL).not.toHaveBeenCalled(); expect(writes()).toHaveLength(0)
})
it('unchanged session read preserves an uncertain registration body/key for a manual original retry', async () => {
  mount(); vi.mocked(api.request).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'synthetic lost response', 'test')); await state.submitReport()
  const first = writes()[0]!, key = state.attempt.key, body = JSON.stringify(state.attempt.body)
  await refreshProjectSession(); await settle()
  expect(state.uncertain).toBe(true); expect(state.attempt.key).toBe(key); expect(JSON.stringify(state.attempt.body)).toBe(body)
  expect(state.form.description).toBe('合成报障描述'); expect(props.modelValue).toBe(true)
  vi.mocked(api.request).mockRejectedValueOnce(new ApiError(403, 'ACTION_FORBIDDEN', 'synthetic denied retry', 'test')); await state.submitReport()
  expect(writes()).toHaveLength(2); expect(writes()[1]![1]!.body).toEqual(first[1]!.body); expect(writes()[1]![1]!.idempotencyKey).toBe(key)
  expect(state.uncertain).toBe(true)
})
it('a durable accepted registration survives same-scope refresh without permitting another POST', async () => {
  mount(); vi.mocked(api.request).mockResolvedValueOnce({ operation_id: id(1010), status: 'PENDING' }); await state.submitReport()
  expect(state.acceptedOperationId).toBe(id(1010)); expect(state.complete).toBe(false)
  await refreshProjectSession(); await settle(); await state.submitReport()
  expect(state.acceptedOperationId).toBe(id(1010)); expect(state.attempt.state).toBe('accepted'); expect(writes()).toHaveLength(1)
})
it.each(['tenant', 'actor', 'role', 'permission', 'logout'])('actual %s context change aborts pending registration and clears sensitive local input immediately', async change => {
  mount(); addPhoto(); let finish!: (result: unknown) => void
  vi.mocked(api.request).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = state.submitReport(), signal = writes()[0]![1]!.signal!
  if (change === 'logout') session.value = null
  else if (change === 'tenant') session.value = { ...auth, tenant: { ...auth.tenant, id: id(99) } }
  else if (change === 'actor') session.value = { ...auth, user: { ...auth.user, id: id(99) } }
  else if (change === 'role') session.value = { ...auth, user: { ...auth.user, role: 'msp' } }
  else session.value = { ...auth, permission_version: auth.permission_version + 1, capabilities: [] }
  expect(signal.aborted).toBe(true); expect(state.attempt).toBeNull(); expect(state.form.description).toBe(''); expect(state.photos.rows.value).toEqual([])
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic')
  finish({ operation_id: id(1010), status: 'PENDING' }); await pending; await settle()
  expect(props.modelValue).toBe(false); expect(state.acceptedOperationId).toBe(''); expect(state.submitting).toBe(false)
})
