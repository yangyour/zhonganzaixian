// @vitest-environment jsdom
// Actual SFC handlers with a null render and synthetic transport; not live API/browser acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import { context, id, ticket } from './ticketReader.fixture'
const mocks = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn(), leave: null as null | (() => boolean | Promise<boolean>) }))
vi.mock('element-plus', () => ({ ElTableColumn: {}, ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/utils/feedback', () => ({ feedback: { warning: mocks.warning } }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: (guard: () => boolean | Promise<boolean>) => { mocks.leave = guard } }))
import WorklogWorkspace from '../src/components/WorklogWorkspace.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, session } from '../src/stores/session'
import { contextTransitionGuards } from '../src/utils/contextTransitionGuard'
import type { Session } from '../src/api/types'
import type { WorklogEntry } from '../src/utils/worklogWorkspace'
const auth: Session = { user: { id: context.actorId, role: 'msp', display_name: '合成内部用户' }, tenant: { id: context.tenantId, name: '合成租户' }, allowed_tenants: [], capabilities: ['worklogs:read_own', 'worklogs:write_own'], permission_version: 7, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
const row = (): WorklogEntry => ({ id: id(20), tenant_id: context.tenantId, ticket_id: id(10), owner_id: context.actorId, version: 1, created_at: '2026-10-14T00:00:00Z', updated_at: '2026-10-14T00:00:00Z', kind: 'REMOTE', started_at: '2026-10-14T00:00:00Z', ended_at: '2026-10-14T01:00:00Z', note: '未保存输入', elapsed_millis: 3600000, record_source: 'MANUAL_INTERVAL', source: 'postgresql', verification_status: 'SELF_REPORTED', financial_status: 'CONFIG_PENDING', default_minutes: null, labor_cost: null, transport_cost: null, gross_margin: null })
const page = () => ({ items: [], has_more: false, next_before_created_at: null, next_before_id: null, source: 'postgresql', scope: 'OWN', page_size_limit: 100 })
let app: App | undefined, host: HTMLElement, state: any, props: { modelValue: boolean; ticket: ReturnType<typeof ticket> }
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
function mount() {
  host = document.createElement('div'); document.body.appendChild(host)
  props = reactive({ modelValue: true, ticket: ticket() })
  const component = { ...WorklogWorkspace, render: () => null } as unknown as Component
  app = createApp({ render: () => h(component, { ...props, ref: (vm: any) => { if (vm) state = vm.$.setupState }, 'onUpdate:modelValue': (value: boolean) => { props.modelValue = value } }) })
  app.mount(host)
}
function fill() { state.kind = 'REMOTE'; state.startedAt = '2026-10-14T00:00:00Z'; state.endedAt = '2026-10-14T01:00:00Z'; state.note = '未保存输入' }
function request() { return vi.spyOn(api, 'request').mockImplementation(async (path, options) => options?.method ? row() : path.includes('/worklogs') ? page() : { id: props.ticket.id, tenant_id: context.tenantId, version: 1 }) }
const mutations = () => vi.mocked(api.request).mock.calls.filter(([, options]) => options?.method)
beforeEach(() => { session.value = structuredClone(auth); contextGeneration.value = 0; mocks.confirm.mockReset().mockResolvedValue('confirm'); mocks.warning.mockReset(); mocks.leave = null })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it.each([['kind', 'REMOTE'], ['startedAt', '2026-10-14T00:00:00Z'], ['endedAt', '2026-10-14T01:00:00Z'], ['note', '仅写了一条备注']])('cancelled leave preserves a partial %s input', async (field, value) => {
  request(); mount(); await settle(); state[field] = value
  mocks.confirm.mockRejectedValue('cancel')
  expect(await mocks.leave!()).toBe(false)
  expect(state[field]).toBe(value)
  expect(await contextTransitionGuards.approve()).toBe(false)
  expect(state[field]).toBe(value)
  expect(mutations()).toHaveLength(0)
})
it('collapsed input still blocks unload and context change, while reopening keeps all fields', async () => {
  request(); mount(); await settle(); fill(); state.open = false; await settle()
  expect(props.modelValue).toBe(false)
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  mocks.confirm.mockRejectedValue('cancel'); expect(await contextTransitionGuards.approve()).toBe(false)
  state.open = true; await settle(); expect(state.note).toBe('未保存输入'); expect(state.kind).toBe('REMOTE')
})
it('approval does not discard inputs before a transition actually succeeds', async () => {
  request(); mount(); await settle(); fill()
  expect(await contextTransitionGuards.approve()).toBe(true)
  expect(mocks.confirm).toHaveBeenCalledOnce(); expect(state.note).toBe('未保存输入')
  contextGeneration.value++; await settle(); expect(state.note).toBe(''); expect(state.kind).toBe('')
})
it('a confirmed successful save clears the dirty state and does not prompt on leaving', async () => {
  request(); mount(); await settle(); fill(); await state.save(); await settle()
  expect(mutations()).toHaveLength(1); expect(state.note).toBe('')
  expect(await mocks.leave!()).toBe(true); expect(await contextTransitionGuards.approve()).toBe(true)
  expect(mocks.confirm).not.toHaveBeenCalled()
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(false)
})
it('clear form requires confirmation and cancellation preserves the editing input', async () => {
  request(); mount(); await settle(); fill()
  mocks.confirm.mockRejectedValueOnce('cancel'); await state.resetForm()
  expect(state.note).toBe('未保存输入'); expect(state.kind).toBe('REMOTE')
  await state.resetForm(); expect(state.note).toBe(''); expect(state.kind).toBe('')
  expect(await mocks.leave!()).toBe(true); expect(mocks.confirm).toHaveBeenCalledTimes(2); expect(mutations()).toHaveLength(0)
})
it('confirmation locks editing and duplicate transitions, and rejects a stale context approval', async () => {
  request(); mount(); await settle(); fill()
  let confirm!: () => void; mocks.confirm.mockImplementationOnce(() => new Promise<void>(resolve => { confirm = resolve }))
  const leaving = state.canLeave(); expect(state.locked).toBe(true)
  expect(await state.canLeave()).toBe(false); expect(mocks.confirm).toHaveBeenCalledOnce()
  contextGeneration.value++; await settle(); state.note = '新上下文输入'; confirm()
  expect(await leaving).toBe(false); expect(state.note).toBe('新上下文输入'); expect(state.confirming).toBe(false)
})
it('sent and uncertain requests block every destructive action without offering discard; original retry retains the command', async () => {
  const mock = request(); mount(); await settle(); fill()
  let reject!: (reason: unknown) => void
  mock.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
  const saving = state.save(); expect(await state.canLeave()).toBe(false)
  reject(new ApiError(0, 'NETWORK_ERROR', 'synthetic lost response', 'test')); await saving
  const first = mutations()[0]!
  expect(await mocks.leave!()).toBe(false); expect(await contextTransitionGuards.approve()).toBe(false)
  await state.resetForm(); expect(state.note).toBe('未保存输入'); expect(mocks.confirm).not.toHaveBeenCalled()
  state.open = false; await settle(); state.open = true; await settle()
  mock.mockRejectedValueOnce(new ApiError(403, 'ACTION_FORBIDDEN', 'synthetic denied retry', 'test')); await state.retryOriginal()
  expect(state.workspace.unresolved()).toBe(true); expect(mutations()).toHaveLength(2)
  expect(mutations()[1]![1]!.body).toEqual(first[1]!.body); expect(mutations()[1]![1]!.idempotencyKey).toBe(first[1]!.idempotencyKey)
  await state.retryOriginal(); expect(state.workspace.unresolved()).toBe(false); expect(state.note).toBe('')
  expect(await mocks.leave!()).toBe(true); expect(mocks.confirm).not.toHaveBeenCalled()
})
it('a definite rejection keeps unsaved input guarded without claiming it was saved', async () => {
  const mock = request(); mount(); await settle(); fill()
  mock.mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', 'synthetic conflict', 'test')); await state.save()
  expect(state.workspace.unresolved()).toBe(false); expect(state.state.receipt).toBeNull()
  mocks.confirm.mockRejectedValueOnce('cancel'); expect(await mocks.leave!()).toBe(false)
  expect(state.note).toBe('未保存输入'); expect(mutations()).toHaveLength(1)
})
it('a read-only edit baseline is clean, changed fields are guarded, and edit replacement requires confirmation', async () => {
  const mock = request(); mount(); await settle()
  mock.mockImplementation(async path => {
    if (path.includes('/history?')) {
      const { id: _id, tenant_id: _tenant, ticket_id: _ticket, owner_id: _owner, created_at: _created, updated_at: _updated, ...facts } = row()
      return { items: [{ ...facts, actor_id: context.actorId, action: 'CREATE', recorded_at: row().updated_at }], has_more: false, next_before_version: null, source: 'postgresql', scope: 'OWN', page_size_limit: 100 }
    }
    return row()
  })
  expect(await state.workspace.show(id(20))).toBe(true); await state.editSelected()
  expect(state.editingId).toBe(id(20)); expect(await mocks.leave!()).toBe(true); expect(mocks.confirm).not.toHaveBeenCalled()
  state.note = '尚未保存的修改'; mocks.confirm.mockRejectedValueOnce('cancel'); await state.editSelected()
  expect(state.note).toBe('尚未保存的修改'); expect(state.editingId).toBe(id(20))
  await state.editSelected(); expect(state.note).toBe('未保存输入'); expect(await mocks.leave!()).toBe(true)
  expect(mocks.confirm).toHaveBeenCalledTimes(2); expect(mutations()).toHaveLength(0)
})
it('a late accepted write readback cannot clear new-context draft fields', async () => {
  const mock = request(); mount(); await settle(); fill()
  let finishRead!: (value: unknown) => void, saved = false
  mock.mockImplementation(async (path, options) => {
    if (options?.method) { saved = true; return row() }
    if (saved && path.includes('/worklogs')) return new Promise(resolve => { finishRead = resolve })
    return { id: props.ticket.id, tenant_id: context.tenantId, version: 1 }
  })
  const saving = state.save(); await settle(); props.modelValue = false; await settle(); contextGeneration.value++; await settle()
  state.note = '新上下文输入'; finishRead(page()); await saving
  expect(state.note).toBe('新上下文输入'); expect(state.state.receipt).toBeNull()
})
