// @vitest-environment jsdom
// Vue conditional rendering with slot-only UI stubs. Not CSS, actual drawer, browser or live API acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App as VueApp } from 'vue'
import type { Session } from '../src/api/types'
const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(async () => undefined), resolve: vi.fn(({ name }: { name: string }) => ({ href: '/' + name })), getRoutes: vi.fn() }))
vi.mock('vue-router', async importOriginal => ({ ...await importOriginal<typeof import('vue-router')>(), useRouter: () => navigation, useRoute: () => ({ name: 'access-unavailable', fullPath: '/access-unavailable', meta: { group: '账号', title: '暂无业务权限' }, query: {} }) }))
vi.mock('../src/components/GlobalSearch.vue', () => ({ default: { render: () => null } }))
import App from '../src/App.vue'
import { forgetSession, session } from '../src/stores/session'
import { api } from '../src/api/client'
const adminEntries = [
  ['contacts:read', 'customer-contacts'], ['contacts:write', 'contact-imports'],
  ['backup_plan_drafts:read', 'backup-plan-drafts'], ['inspection_templates:read', 'inspection-templates'],
  ['inspection_source_collections:read', 'inspection-source-collections'], ['tenants:read_inventory', 'tenant-inventory'],
] as const
const auth = (capabilities: string[], role: Session['user']['role'] = 'padmin'): Session => ({ user: { id: 'unit-actor', display_name: '合成用户', role }, tenant: { id: 'unit-tenant', name: '合成租户' }, allowed_tenants: [], capabilities, permission_version: 1, csrf_token: 'unit-not-live', mode: 'remote_test', identity_source: 'unit-not-live' })
let app: VueApp | undefined, host: HTMLElement
beforeEach(() => {
  vi.clearAllMocks(); forgetSession(); host = document.createElement('div'); document.body.append(host)
  navigation.getRoutes.mockReturnValue([
    { name: 'assets', meta: { group: '资源', capability: 'assets:read' } },
    ...adminEntries.map(([capability, name]) => ({ name, meta: { group: '管理', capability, ...(name.startsWith('contact') || name === 'customer-contacts' ? {} : { role: 'padmin' }) } })),
  ])
})
afterEach(() => { app?.unmount(); app = undefined; host.remove(); forgetSession(); vi.restoreAllMocks() })
function mount() {
  app = createApp(App)
  for (const name of ['ElConfigProvider', 'ElMenu', 'ElMenuItem', 'ElSubMenu', 'ElDrawer', 'ElBreadcrumb', 'ElBreadcrumbItem', 'ElSelect', 'ElOption', 'ElSkeleton', 'ElAlert', 'RouterView']) {
    app.component(name, defineComponent({ inheritAttrs: false, setup(_, { attrs, slots }) { return () => h('div', { index: attrs.index, class: attrs.class, 'data-mode': attrs.mode }, [slots.title?.(), slots.default?.()]) } }))
  }
  app.component('ElButton', defineComponent({ inheritAttrs: false, setup(_, { attrs, slots }) { return () => h('button', { onClick: attrs.onClick as () => void, 'aria-label': attrs['aria-label'] as string }, slots.default?.()) } }))
  app.mount(host)
}
it.each(adminEntries)('shows the independent %s management entry without config/audit grants', async (grant, name) => {
  session.value = auth([grant], name === 'customer-contacts' || name === 'contact-imports' ? 'tadmin' : 'padmin'); mount(); await nextTick()
  expect(host.querySelectorAll(`[index="${name}"]`)).toHaveLength(1)
  expect(host.querySelectorAll('[index="assets"]')).toHaveLength(0)
  expect(host.querySelector('.module-navigation [data-mode="horizontal"]')).not.toBeNull()
  expect(host.querySelector('a.brand')?.getAttribute('href')).toBe('/' + name)
})
it('an empty capability set shows no business items and has a stable brand destination', async () => {
  session.value = auth([]); mount(); await nextTick()
  expect(host.querySelectorAll('[index]')).toHaveLength(0)
  expect(host.querySelector('a.brand')?.getAttribute('href')).toBe('/access-unavailable')
  expect([...host.querySelectorAll('button')].some(button => button.textContent === '退出')).toBe(true)
})
it('removing permissions hides the shared responsive navigation immediately', async () => {
  session.value = auth(['contacts:read']); mount(); await nextTick(); expect(host.querySelectorAll('[index="customer-contacts"]')).toHaveLength(1)
  session.value = auth([]); await nextTick(); expect(host.querySelectorAll('[index="customer-contacts"]')).toHaveLength(0)
})
it('a wrong base role cannot display a selected administrator-only entry', async () => {
  session.value = auth(['tenants:read_inventory'], 'viewer'); mount(); await nextTick()
  expect(host.querySelectorAll('[index="tenant-inventory"]')).toHaveLength(0)
})
it('a session without grants can still explicitly log out', async () => {
  session.value = auth([]); const request = vi.spyOn(api, 'request').mockResolvedValue({ logged_out: true, redirect_url: null }); mount()
  ;[...host.querySelectorAll('button')].find(button => button.textContent === '退出')!.click()
  for (let i = 0; i < 6; i++) await nextTick()
  expect(request).toHaveBeenCalledWith('/session', { method: 'DELETE' }); expect(navigation.replace).toHaveBeenCalledWith('/login')
})
