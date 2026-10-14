// @vitest-environment jsdom
// Actual SFC setup and command state, with isolated transport; not browser or server acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
import ConfigurationsPage from '../src/pages/ConfigurationsPage.vue'
import ConfigurationRevisions from '../src/components/ConfigurationRevisions.vue'
import { api } from '../src/api/client'
import { contextGeneration, session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import type { RegistryDraft } from '../src/utils/configurationDraftWriter'

const id = (n: number) => '55000000-0000-4000-8000-' + n.toString(16).padStart(12, '0')
const auth: Session = { user: { id: id(2), role: 'padmin', display_name: '合成管理员' }, tenant: { id: id(1), name: '合成租户' }, allowed_tenants: [], capabilities: ['configurations:read', 'configurations:write'], permission_version: 1, account_version: 1, csrf_token: 'synthetic', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
const entry = (): RegistryDraft => ({ key: 'synthetic.setting', description: '合成设计登记', type: 'integer', scope: 'tenant', unit: null, value: null, registered_value: null, effective_value: null, decision_status: 'pending', source: 'design-document', question_ids: ['Q08'], activation_gate: 'PENDING', notes: null, allowed_range: null, contract_value_kind: 'pending_customer_decision', version: 1, draft_value: 31, lifecycle_status: 'DRAFT', mode: 'remote_test', source_registry_status: 'design_registry_not_runtime_config' })
const proposal = { proposed_value: 60, reason: '合成提案', evidence_refs: [{ reference: 'document-ref:synthetic', title: '未核验资料', sha256: 'a'.repeat(64) }] }
let app: App | undefined, host: HTMLElement | undefined, state: any
async function settle() { for (let i = 0; i < 5; i++) { await Promise.resolve(); await nextTick() } }
function mount(component: any, props: Record<string, unknown> = {}) {
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp({ ...component, render: () => null }, props)
  const vm = app.mount(host); state = (vm.$ as unknown as { setupState: any }).setupState
}
function transport() { return vi.spyOn(api, 'request').mockImplementation(async (_path, options) => { if (options?.method === 'POST') throw new TypeError('synthetic lost response'); return { items: [] } }) }
async function prepareDraft(write = false) {
  mount(ConfigurationsPage); await settle(); state.openDraft(entry()); state.proposed = '42'
  if (write) await state.saveDraft()
}
async function prepareRevision(write = false) {
  mount(ConfigurationRevisions, { configuration: entry(), modelValue: true }); await settle()
  state.createOpen = true; Object.assign(state.form, { proposed: '60', reason: proposal.reason, evidence_refs: structuredClone(proposal.evidence_refs) })
  state.actionComment = '未提交审核意见'
  if (write) await state.commands.submit('create', null, proposal)
}
beforeEach(() => { contextGeneration.value = 0; switchingTenant.value = false; session.value = structuredClone(auth) })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); host = undefined; session.value = null; vi.restoreAllMocks() })

it('keeps unsent registry draft values after a same-scope session refresh', async () => {
  transport(); await prepareDraft()
  session.value = { ...structuredClone(session.value!), csrf_token: 'new-synthetic' }; await settle()
  expect(state.editing?.key).toBe('synthetic.setting'); expect(state.proposed).toBe('42'); expect(state.draftChanged).toBe(true)
})
it('keeps the original uncertain registry draft request and exact retry after session refresh', async () => {
  const request = transport(); await prepareDraft(true)
  const original = structuredClone(state.draftState.attempt)
  expect(original.state).toBe('uncertain')
  session.value = { ...structuredClone(session.value!), csrf_token: 'new-synthetic' }; await settle()
  expect(state.draftState.attempt).toEqual(original); expect(state.draftWriter.unresolved()).toBe(true)
  await state.retryDraft()
  const posts = request.mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(posts).toHaveLength(2); expect(posts[1]![1]?.idempotencyKey).toBe(posts[0]![1]?.idempotencyKey); expect(posts[1]![1]?.body).toEqual(posts[0]![1]?.body)
})
it('keeps unsent revision evidence and review text after a same-scope session refresh', async () => {
  transport(); await prepareRevision()
  session.value = { ...structuredClone(session.value!), csrf_token: 'new-synthetic' }; await settle()
  expect(state.createOpen).toBe(true); expect(state.form.proposed).toBe('60'); expect(state.form.evidence_refs).toEqual(proposal.evidence_refs); expect(state.actionComment).toBe('未提交审核意见')
})
it('keeps the original uncertain revision request and exact retry after session refresh', async () => {
  const request = transport(); await prepareRevision(true)
  const original = structuredClone(state.commandState.attempt)
  expect(original.state).toBe('uncertain')
  session.value = { ...structuredClone(session.value!), csrf_token: 'new-synthetic' }; await settle()
  expect(state.commandState.attempt).toEqual(original); expect(state.commands.unresolved()).toBe(true)
  await state.commands.retry()
  const posts = request.mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(posts).toHaveLength(2); expect(posts[1]![1]?.idempotencyKey).toBe(posts[0]![1]?.idempotencyKey); expect(posts[1]![1]?.body).toEqual(posts[0]![1]?.body)
})
for (const [name, change] of [
  ['tenant', () => { session.value = { ...session.value!, tenant: { id: id(9), name: '另一合成租户' } } }],
  ['actor', () => { session.value = { ...session.value!, user: { ...session.value!.user, id: id(9) } } }],
  ['permission', () => { session.value = { ...session.value!, permission_version: 2 } }],
  ['revoked capability', () => { session.value = { ...session.value!, capabilities: ['configurations:read'] } }],
  ['account version', () => { session.value = { ...session.value!, account_version: 2 } }],
  ['epoch', () => { contextGeneration.value++ }],
  ['pending tenant transition', () => { switchingTenant.value = true }],
] as const) {
  it(`clears registry drafts and original requests on a real ${name} change`, async () => {
    transport(); await prepareDraft(true); change()
    expect(state.draftState.attempt).toBeNull(); expect(state.editing).toBeNull(); expect(state.proposed).toBe('')
  })
  it(`clears revision drafts and original requests on a real ${name} change`, async () => {
    transport(); await prepareRevision(true); change()
    expect(state.commandState.attempt).toBeNull(); expect(state.createOpen).toBe(false); expect(state.form.proposed).toBe(''); expect(state.actionComment).toBe('')
  })
}
