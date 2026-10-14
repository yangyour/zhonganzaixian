// @vitest-environment jsdom
// Real Vue Router and two-level navigation with slot-only Element Plus stubs.
// Not CSS, actual drawer focus handling, browser or live API acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App as VueApp } from 'vue'
import { createMemoryHistory, createRouter, type RouteLocationNormalized, type Router } from 'vue-router'
import type { Session } from '../src/api/types'
vi.mock('../src/components/GlobalSearch.vue', () => ({ default: { render: () => null } }))
import App from '../src/App.vue'
import { forgetSession, session, switchingTenant } from '../src/stores/session'
import { navigationError, navigationTarget } from '../src/stores/navigation'
import { api } from '../src/api/client'

const adminEntries = [
  ['contacts:read', 'customer-contacts'], ['contacts:write', 'contact-imports'],
  ['backup_plan_drafts:read', 'backup-plan-drafts'], ['inspection_templates:read', 'inspection-templates'],
  ['inspection_source_collections:read', 'inspection-source-collections'], ['tenants:read_inventory', 'tenant-inventory'],
] as const
const auth = (capabilities: string[], role: Session['user']['role'] = 'padmin'): Session => ({ user: { id: 'unit-actor', display_name: '合成用户', role }, tenant: { id: 'unit-tenant', name: '合成租户' }, allowed_tenants: [], capabilities, permission_version: 1, csrf_token: 'unit-not-live', mode: 'remote_test', identity_source: 'unit-not-live' })
const emptyPage = defineComponent({ render: () => h('h1', '合成页面') })
let app: VueApp | undefined, host: HTMLElement, router: Router, mobile: boolean

beforeEach(() => {
  vi.clearAllMocks(); forgetSession(); switchingTenant.value = false; navigationError.value = null; navigationTarget.value = ''; mobile = false
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({ matches: mobile && query === '(max-width: 760px)', media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
  host = document.createElement('div'); document.body.append(host)
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/access-unavailable', name: 'access-unavailable', component: emptyPage, meta: { group: '账号', title: '暂无业务权限' } },
    { path: '/login', name: 'login', component: emptyPage },
    { path: '/account', name: 'account', component: emptyPage, meta: { group: '账号', title: '我的账号' } },
    { path: '/assets', name: 'assets', component: emptyPage, meta: { group: '资源', title: '资产管理', capability: 'assets:read' } },
    { path: '/dashboard', name: 'dashboard', component: emptyPage, meta: { group: '资源', title: '运营总览', capability: 'dashboard:read' } },
    ...adminEntries.map(([capability, name]) => ({ path: '/' + name, name, component: emptyPage, meta: { group: '管理', title: name, capability, ...(name.startsWith('contact') || name === 'customer-contacts' ? {} : { role: 'padmin' }) } })),
    { path: '/contact-import-preview', name: 'contact-import-preview', component: emptyPage, meta: { group: '管理', title: '联系人导入格式预览', capability: 'contacts:write' } },
  ] })
})
afterEach(() => { app?.unmount(); app = undefined; host.remove(); forgetSession(); switchingTenant.value = false; navigationError.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals() })

async function settle() { await nextTick(); await Promise.resolve(); await nextTick() }
async function mount(name = 'access-unavailable') {
  await router.push({ name })
  app = createApp(App)
  app.use(router)
  for (const name of ['ElConfigProvider', 'ElBreadcrumb', 'ElBreadcrumbItem', 'ElSelect', 'ElOption', 'ElSkeleton', 'ElAlert']) {
    app.component(name, defineComponent({ inheritAttrs: false, setup(_, { attrs, slots }) { return () => h('div', { class: attrs.class }, [slots.title?.(), slots.default?.()]) } }))
  }
  app.component('ElDrawer', defineComponent({
    inheritAttrs: false, props: { modelValue: Boolean },
    setup(props, { slots }) { return () => props.modelValue ? h('div', { 'data-testid': 'mobile-navigation', role: 'dialog' }, slots.default?.()) : null },
  }))
  app.component('ElButton', defineComponent({ inheritAttrs: false, setup(_, { attrs, slots }) { return () => h('button', { onClick: attrs.onClick as () => void, 'aria-label': attrs['aria-label'] as string }, slots.default?.()) } }))
  app.mount(host)
  await settle()
}
function sidebarLink(name: string) { return host.querySelector<HTMLAnchorElement>(`#workspace-sidebar [data-page="${name}"]`) }
function moduleLink(name: string) { return host.querySelector<HTMLAnchorElement>(`.module-navigation a[href="/${name}"]`)! }
function mobileDrawer() { return host.querySelector<HTMLElement>('[data-testid="mobile-navigation"]') }
function activeModule() { return host.querySelector('.module-navigation a.active')?.textContent }
function activePage() { return host.querySelector('#workspace-sidebar a[aria-current="page"]')?.getAttribute('data-page') }

it.each(adminEntries)('shows the independent %s management entry without config/audit grants', async (grant, name) => {
  session.value = auth([grant], name === 'customer-contacts' || name === 'contact-imports' ? 'tadmin' : 'padmin'); await mount()
  expect(sidebarLink(name)).not.toBeNull()
  expect(host.querySelectorAll(`[data-page="${name}"]`)).toHaveLength(1)
  expect(sidebarLink('assets')).toBeNull()
  expect(host.querySelector('nav.module-navigation[aria-label="模块导航"]')).not.toBeNull()
  expect(host.querySelector('#workspace-sidebar nav.secondary-navigation')).not.toBeNull()
  expect(moduleLink(name).getAttribute('href')).toBe('/' + name)
  expect(host.querySelector('a.brand')?.getAttribute('href')).toBe('/' + name)
})
it('an empty capability set shows no business items and has a stable brand destination', async () => {
  session.value = auth([]); await mount()
  expect(host.querySelectorAll('[data-page]')).toHaveLength(0)
  expect(host.querySelector('.module-navigation')).toBeNull()
  expect(host.querySelector('a.brand')?.getAttribute('href')).toBe('/access-unavailable')
  expect([...host.querySelectorAll('button')].some(button => button.textContent === '退出')).toBe(true)
})
it('removing permissions hides the shared responsive navigation immediately', async () => {
  session.value = auth(['contacts:read']); await mount(); expect(sidebarLink('customer-contacts')).not.toBeNull()
  session.value = auth([]); await settle(); expect(sidebarLink('customer-contacts')).toBeNull()
})
it('a wrong base role cannot display a selected administrator-only entry', async () => {
  session.value = auth(['tenants:read_inventory'], 'viewer'); await mount()
  expect(sidebarLink('tenant-inventory')).toBeNull()
})
it('a session without grants can still explicitly log out', async () => {
  session.value = auth([]); const request = vi.spyOn(api, 'request').mockResolvedValue({ logged_out: true, redirect_url: null }); await mount()
  ;[...host.querySelectorAll('button')].find(button => button.textContent === '退出')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
  expect(request).toHaveBeenCalledWith('/session', { method: 'DELETE' })
})
it('a guard rejecting another module keeps the active module and sidebar on the actual route', async () => {
  session.value = auth(['assets:read', 'contacts:read']); await mount('assets')
  const guard = vi.fn((to: RouteLocationNormalized) => to.name === 'customer-contacts' ? false : undefined)
  router.beforeEach(guard)
  moduleLink('customer-contacts').click()
  await vi.waitFor(() => expect(guard).toHaveBeenCalled()); await settle()
  expect(router.currentRoute.value.name).toBe('assets')
  expect(activeModule()).toContain('资源与监控')
  expect(activePage()).toBe('assets')
  expect(sidebarLink('customer-contacts')).toBeNull()
})
it('a module link returns from an account page to its first permitted business page', async () => {
  session.value = auth(['assets:read']); await mount('account')
  moduleLink('assets').click()
  await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('assets'))
  expect(activePage()).toBe('assets')
})
it.each([false, true])('contact import preview retains the customer module and import entry (mobile=%s)', async isMobile => {
  mobile = isMobile; session.value = auth(['assets:read', 'contacts:write']); await mount('contact-import-preview')
  expect(router.currentRoute.value.name).toBe('contact-import-preview')
  expect(activeModule()).toContain('客户与租户')
  expect(activePage()).toBe('contact-imports')
  expect(sidebarLink('assets')).toBeNull()
  expect(sidebarLink('contact-imports')?.getAttribute('href')).toBe('/contact-imports')
  if (isMobile) {
    const push = vi.spyOn(router, 'push')
    moduleLink('contact-imports').click(); await settle()
    expect(push).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('contact-import-preview')
    expect(mobileDrawer()?.querySelector('[data-page="contact-imports"][aria-current="page"]')).not.toBeNull()
  }
})
it('a guard rejecting a sibling page preserves the current page and open mobile menu', async () => {
  mobile = true; session.value = auth(['assets:read', 'dashboard:read']); await mount('assets')
  moduleLink('dashboard').click(); await settle()
  const guard = vi.fn((to: RouteLocationNormalized) => to.name === 'dashboard' ? false : undefined)
  router.beforeEach(guard)
  mobileDrawer()!.querySelector<HTMLAnchorElement>('[data-page="dashboard"]')!.click()
  await vi.waitFor(() => expect(guard).toHaveBeenCalled()); await settle()
  expect(router.currentRoute.value.name).toBe('assets')
  expect(activeModule()).toContain('资源与监控')
  expect(activePage()).toBe('assets')
  expect(mobileDrawer()).not.toBeNull()
})
it('checks fresh permissions when a previously rendered sidebar link is activated', async () => {
  session.value = auth(['assets:read', 'dashboard:read']); await mount('assets')
  const staleLink = sidebarLink('dashboard')!, push = vi.spyOn(router, 'push')
  session.value = auth(['assets:read'])
  staleLink.click(); await settle()
  expect(push).not.toHaveBeenCalled()
  expect(router.currentRoute.value.name).toBe('assets')
  expect(sidebarLink('dashboard')).toBeNull()
})
it('checks the base role again before following a previously rendered module link', async () => {
  session.value = auth(['assets:read', 'tenants:read_inventory']); await mount('assets')
  const staleLink = moduleLink('tenant-inventory'), push = vi.spyOn(router, 'push')
  session.value = auth(['assets:read', 'tenants:read_inventory'], 'viewer')
  staleLink.click(); await settle()
  expect(push).not.toHaveBeenCalled()
  expect(router.currentRoute.value.name).toBe('assets')
  expect(host.querySelector('.module-navigation a[href="/tenant-inventory"]')).toBeNull()
})
it.each(['capabilities', 'role', 'version', 'tenant-switch'] as const)('%s changes close an open mobile menu', async change => {
  mobile = true; session.value = auth(['assets:read', 'tenants:read_inventory']); await mount('assets')
  moduleLink('assets').click(); await settle(); expect(mobileDrawer()).not.toBeNull()
  if (change === 'capabilities') session.value = auth(['assets:read'])
  else if (change === 'role') session.value = auth(['assets:read', 'tenants:read_inventory'], 'viewer')
  else if (change === 'version') session.value = { ...session.value!, permission_version: 2 }
  else switchingTenant.value = true
  await settle()
  expect(mobileDrawer()).toBeNull()
  expect(host.querySelector('.mobile-sidebar-toggle')?.getAttribute('aria-expanded')).toBe('false')
})
it('activating the current module on mobile opens its menu without navigating to its first page', async () => {
  mobile = true; session.value = auth(['assets:read', 'dashboard:read']); await mount('assets')
  const push = vi.spyOn(router, 'push')
  moduleLink('dashboard').click(); await settle()
  expect(push).not.toHaveBeenCalled()
  expect(router.currentRoute.value.name).toBe('assets')
  expect(mobileDrawer()?.querySelector('[data-page="assets"][aria-current="page"]')).not.toBeNull()
  expect(host.querySelector('.mobile-sidebar-toggle')?.getAttribute('aria-expanded')).toBe('true')
})
it('a successful mobile page selection updates the active route and closes the menu', async () => {
  mobile = true; session.value = auth(['assets:read', 'dashboard:read']); await mount('assets')
  moduleLink('dashboard').click(); await settle()
  mobileDrawer()!.querySelector<HTMLAnchorElement>('[data-page="dashboard"]')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('dashboard')); await settle()
  expect(activePage()).toBe('dashboard')
  expect(mobileDrawer()).toBeNull()
})
