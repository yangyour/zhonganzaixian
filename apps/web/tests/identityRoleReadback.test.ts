// @vitest-environment jsdom
// Real Vue/Element Plus rendering with synthetic HTTP responses, not live account authorization.
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ElButton, ElTag, ElAlert } from 'element-plus'
import { api, ApiError } from '../src/api/client'
import { session } from '../src/stores/session'
import type { Session } from '../src/api/types'
import Page from '../src/pages/IdentityRolesPage.vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const tenant = '59000000-0000-4000-8000-000000000001'
let app: App | undefined, host: HTMLElement
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })
it.each([true, false])('reports actual readback success=%s after accepted role creation', async succeeds => {
 session.value = { user: { id: tenant, display_name: '合成管理', role: 'padmin' }, tenant: { id: tenant, name: '合成租户' }, allowed_tenants: [], capabilities: ['roles:read','roles:write'], permission_version: 1, csrf_token: 'SYNTHETIC', mode: 'remote_test', identity_source: 'synthetic' } as Session
 const data = { items: [], tenant_id: tenant, permission_catalog: { entries: [], dimensions: [] } }
 const role = { id: '59000000-0000-4000-8000-000000000008', version: 1, tenant_id: tenant, name: '合成角色', base_role: 'viewer', enabled: true, permissions: [] }
 const request = vi.spyOn(api, 'request').mockResolvedValueOnce(data).mockResolvedValueOnce(role)
 if (succeeds) request.mockResolvedValueOnce(data); else request.mockRejectedValueOnce(new ApiError(503,'READ_UNAVAILABLE','合成回读不可用','synthetic'))
 host = document.createElement('div'); document.body.appendChild(host); app = createApp(Page); for (const c of [ElButton,ElTag,ElAlert]) app.component(c.name!,c); app.directive('loading',{}); app.mount(host); await settle()
 ;([...host.querySelectorAll('button')].find(x => x.textContent?.trim() === '新建角色')!).click(); await settle()
 const input = host.querySelector('form input') as HTMLInputElement; input.value = '合成角色'; input.dispatchEvent(new Event('input', { bubbles: true }))
 host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle()
 expect(request).toHaveBeenCalledTimes(3); expect(host.textContent).toContain('操作已保存')
 if (succeeds) expect(host.textContent).toContain('最新记录已回读')
 else { expect(host.textContent).toContain('最新记录尚未确认'); expect(host.textContent).not.toContain('最新记录已回读'); expect(host.querySelector('.el-alert--warning')).not.toBeNull() }
})
