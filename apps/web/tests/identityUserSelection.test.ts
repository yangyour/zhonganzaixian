// @vitest-environment jsdom
// Mounted Vue/Element Plus DOM with deferred HTTP doubles; no live service or browser acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ElButton, ElTag, ElTabs, ElTabPane, ElAlert, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import type { ProjectUser } from '../src/utils/projectIdentity'
import Page from '../src/pages/IdentityUsersPage.vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const id = (n: number) => `59000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const user = (n: number): ProjectUser => ({ id: id(n), display_name: `合成用户${n}`, username: `test${n}`, active: true, local_login_enabled: true, must_change_password: null, locked_until: null, last_login_at: null, version: 1, base_role: 'viewer', member_active: true, permission_version: 1, role_id: id(8), role_name: '观察者', permissions: [] })
const auth: Session = { user: { id: id(2), display_name: '合成管理员', role: 'padmin' }, tenant: { id: id(1), name: '合成租户' }, allowed_tenants: [], capabilities: ['users:read', 'users:write', 'roles:read'], permission_version: 1, csrf_token: 'SYNTHETIC', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
function deferred() { let resolve!: (v: unknown) => void, reject!: (v: unknown) => void; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
let app: App | undefined, host: HTMLElement
const pending = new Map<string, ReturnType<typeof deferred>>()
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
function button(text: string, index = 0) { const matches = [...host.querySelectorAll('button')].filter(x => x.textContent?.trim() === text); if (!matches[index]) throw new Error(`Missing button ${text}`); return matches[index]! }
function nameField() { return [...host.querySelectorAll('label')].find(x => x.textContent?.startsWith('显示名称'))!.querySelector('input')! }
async function mount() { host = document.createElement('div'); document.body.appendChild(host); app = createApp(Page); for (const c of [ElButton, ElTag, ElTabs, ElTabPane, ElAlert]) app.component(c.name!, c); app.directive('loading', {}); app.mount(host); await settle() }
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0; pending.clear(); vi.spyOn(api, 'request').mockImplementation((path: string) => {
 if (path.startsWith('/identity/users?')) return Promise.resolve({ items: [user(3), user(4)], tenant_id: id(1), page: 1, page_size: 50, has_more: false }) as never
 if (path === '/identity/roles') return Promise.resolve({ items: [], tenant_id: id(1), permission_catalog: { entries: [], dimensions: [] } }) as never
 const value = deferred(); pending.set(path, value); return value.promise as never
}) })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })
function complete(n: number) { pending.get(`/identity/users/${id(n)}/memberships`)!.resolve({ items: [] }); pending.get(`/identity/users/${id(n)}/external-identities`)!.resolve({ items: [] }) }
it('new-user selection cannot be overwritten by a prior user detail response', async () => {
 await mount(); button('管理账号', 0).click(); await settle(); button('新建用户').click(); await settle(); expect(nameField().value).toBe('')
 complete(3); await settle(); expect(nameField().value).toBe(''); expect(host.querySelector('section.identity-form h2')?.textContent).toBe('新建用户')
})
it('new-user selection ignores an obsolete detail error', async () => {
 await mount(); button('管理账号', 0).click(); await settle(); button('新建用户').click(); await settle()
 pending.get(`/identity/users/${id(3)}/memberships`)!.reject(new ApiError(503, 'SYNTHETIC_OLD_DETAIL', '过时详情错误', 'synthetic'))
 await settle(); expect(host.textContent).not.toContain('过时详情错误'); expect(nameField().value).toBe('')
})
it('a newer existing selection wins regardless of response order', async () => {
 await mount(); button('管理账号', 0).click(); await settle(); button('管理账号', 1).click(); await settle()
 complete(4); await settle(); expect(nameField().value).toBe('合成用户4'); complete(3); await settle(); expect(nameField().value).toBe('合成用户4')
})
it('does not expose the previous form while a newly selected account is loading', async () => {
 await mount(); button('管理账号', 0).click(); await settle(); complete(3); await settle(); expect(nameField().value).toBe('合成用户3')
 button('管理账号', 1).click(); await settle(); expect(host.querySelector('section.identity-form')).toBeNull(); expect(host.textContent).toContain('正在读取所选账号详情')
 complete(4); await settle(); expect(nameField().value).toBe('合成用户4')
})
it('a current detail failure leaves no writable form for an unverified selection', async () => {
 await mount(); button('管理账号', 0).click(); await settle()
 pending.get(`/identity/users/${id(3)}/memberships`)!.reject(new ApiError(503, 'SYNTHETIC_CURRENT', '当前详情错误', 'synthetic'))
 await settle(); expect(host.textContent).toContain('当前详情错误'); expect(host.querySelector('section.identity-form')).toBeNull(); expect(host.textContent).not.toContain('正在读取所选账号详情')
})
it('an obsolete list role lookup cannot start a fresh read for the newly selected user', async () => {
 const roles = deferred(), request = vi.mocked(api.request), original = request.getMockImplementation()!
 request.mockImplementation((path, options) => path === '/identity/roles' ? roles.promise as never : original(path, options))
 await mount(); button('管理账号', 0).click(); await settle()
 roles.resolve({ items: [], tenant_id: id(1), permission_catalog: { entries: [], dimensions: [] } }); await settle()
 expect(request.mock.calls.some(([path]) => path === `/identity/users/${id(3)}`)).toBe(false)
 complete(3); await settle(); expect(nameField().value).toBe('合成用户3')
})

it('does not read users without the effective users read grant', async () => {
 session.value = { ...auth, capabilities: [] }; await mount(); expect(api.request).not.toHaveBeenCalled(); expect(host.textContent).not.toContain('合成用户3')
})
it('removing read permission clears user rows and any unsaved password field', async () => {
 await mount(); button('新建用户').click(); await settle(); const password = host.querySelector('input[type="password"]') as HTMLInputElement; password.value = 'synthetic-not-a-real-secret'; password.dispatchEvent(new Event('input', { bubbles: true }))
 session.value = { ...auth, capabilities: [], permission_version: 2 }; await settle()
 expect(host.textContent).not.toContain('合成用户3'); expect(host.querySelector('input[type="password"]')).toBeNull(); expect(host.querySelector('section.identity-form')).toBeNull()
})
it('old detail cannot reappear after permission changes without a component remount', async () => {
 await mount(); button('管理账号', 0).click(); await settle(); session.value = { ...auth, capabilities: [], permission_version: 2 }; complete(3); await settle()
 expect(host.textContent).not.toContain('合成用户3'); expect(host.querySelector('section.identity-form')).toBeNull()
})
it('switching tenant clears the currently visible user list immediately', async () => {
 await mount(); switchingTenant.value = true; await settle(); expect(host.textContent).not.toContain('合成用户3'); expect(host.textContent).not.toContain('合成用户4')
})
it('context invalidation clears user details even if the current tenant ID has not changed', async () => {
 await mount(); button('管理账号', 0).click(); await settle(); complete(3); await settle(); contextGeneration.value++; await settle()
 expect(host.textContent).not.toContain('合成用户3'); expect(host.querySelector('section.identity-form')).toBeNull()
})

it('a newly restored read grant does not redisplay old cached rows until an explicit query', async () => {
 await mount(); session.value = { ...auth, capabilities: [], permission_version: 2 }; await settle(); session.value = { ...auth, permission_version: 3 }; await settle()
 expect(host.textContent).not.toContain('合成用户3'); button('查询').click(); await settle(); expect(host.textContent).toContain('合成用户3')
})
it('an edit waiting for discard confirmation cannot reopen after authorization changes', async () => {
 await mount(); button('新建用户').click(); await settle(); const input = nameField(); input.value = '合成未保存'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
 const confirm = deferred(); vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => confirm.promise as never)
 button('管理账号', 0).click(); await settle(); session.value = { ...auth, capabilities: [], permission_version: 2 }; confirm.resolve('confirm'); await settle()
 expect(host.querySelector('section.identity-form')).toBeNull(); expect([...pending.keys()]).toHaveLength(0)
})
it('a changed allowed-tenant set also invalidates the open membership context', async () => {
 session.value = { ...auth, allowed_tenants: [{ id: id(1), name: '当前' }, { id: id(9), name: '合成目标' }] }; await mount(); button('管理账号', 0).click(); await settle(); complete(3); await settle()
 session.value = { ...session.value!, allowed_tenants: [{ id: id(1), name: '当前' }] }; await settle()
 expect(host.querySelector('section.identity-form')).toBeNull(); expect(host.textContent).not.toContain('合成用户3')
})
