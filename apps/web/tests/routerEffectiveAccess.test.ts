// @vitest-environment jsdom
// Real Vue Router in a simulated DOM; no mounted business page or live API.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { router } from '../src/router'
import { forgetSession, session } from '../src/stores/session'
import { api } from '../src/api/client'
import type { Session } from '../src/api/types'
const auth = (capabilities: string[], role: Session['user']['role'] = 'padmin'): Session => ({ user: { id: 'unit-actor', display_name: '合成', role }, tenant: { id: 'unit-tenant', name: '合成租户' }, allowed_tenants: [], capabilities, permission_version: 1, csrf_token: 'unit-not-live', mode: 'remote_test', identity_source: 'unit-not-live' })
beforeEach(async () => { forgetSession(); session.value = auth([]); await router.replace('/access-unavailable'); vi.spyOn(api, 'request').mockRejectedValue(new Error('No network expected')) })
afterEach(() => { forgetSession(); vi.restoreAllMocks() })
it('a session without business grants reaches a stable informational page from assets', async () => {
  await router.push('/assets'); expect(router.currentRoute.value.name).toBe('access-unavailable'); expect(api.request).not.toHaveBeenCalled()
})
it('an authenticated login redirect does not loop back to forbidden assets', async () => {
  await router.push('/login'); expect(router.currentRoute.value.name).toBe('access-unavailable')
})
it('a contacts-only session reaches its permitted page from the login path', async () => {
  session.value = auth(['contacts:read'], 'tadmin'); await router.push('/login'); expect(router.currentRoute.value.name).toBe('customer-contacts')
})
it('keeps the legacy asset landing for authorized members', async () => {
  session.value = auth(['assets:read', 'dashboard:read']); await router.push('/login'); expect(router.currentRoute.value.name).toBe('assets')
})
it('wrong-role navigation cannot use a selected grant to bypass the category restriction', async () => {
  session.value = auth(['tenants:read_inventory', 'contacts:read'], 'tadmin'); await router.push('/tenant-inventory'); expect(router.currentRoute.value.name).toBe('customer-contacts')
})
it('root and unknown paths settle without infinite redirects for an empty grant set', async () => {
  await router.push('/'); expect(router.currentRoute.value.name).toBe('access-unavailable')
  await router.push('/not-a-real-route'); expect(router.currentRoute.value.name).toBe('access-unavailable')
})
it('an anonymous visitor cannot use the informational route to establish a session', async () => {
  forgetSession(); await router.push('/access-unavailable?check=1'); expect(router.currentRoute.value.name).toBe('login'); expect(session.value).toBeNull()
})

it('restored grants leave the no-access page when its URL is rechecked', async () => {
  session.value = auth(['contacts:read'], 'tadmin'); await router.push('/access-unavailable?recheck=1'); expect(router.currentRoute.value.name).toBe('customer-contacts')
})

it('a customer asset-read grant cannot bypass the export route internal-role list', async () => {
  session.value = auth(['assets:read'], 'viewer'); await router.push('/asset-export-jobs'); expect(router.currentRoute.value.name).toBe('assets')
})
