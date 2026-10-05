import { describe, expect, it } from 'vitest'
import type { Session } from '../src/api/types'
import { landingRoute, navigationGroupVisible, routeAllowed, type AccessRoute } from '../src/utils/routeAccess'
const auth = (capabilities: string[], role: Session['user']['role'] = 'padmin') => ({ user: { role }, capabilities }) as Session
const routes: AccessRoute[] = [
  { name: 'login', meta: {} },
  { name: 'access-unavailable', meta: { group: '账号' } },
  { name: 'dashboard', meta: { capability: 'dashboard:read', group: '资源' } },
  { name: 'assets', meta: { capability: 'assets:read', group: '资源' } },
  { name: 'customer-contacts', meta: { capability: 'contacts:read', group: '管理' } },
  { name: 'tenant-inventory', meta: { capability: 'tenants:read_inventory', role: 'padmin', group: '管理' } },
  { name: 'my-worklogs', meta: { capability: 'worklogs:read_own', role: 'msp', group: '服务' } },
  { name: 'asset-export-jobs', meta: { capability: 'assets:read', roles: ['padmin', 'msp'], group: '服务' } },
]
describe('effective capability navigation', () => {
  it('keeps assets as the historical landing only when permitted', () => {
    expect(landingRoute(auth(['assets:read', 'dashboard:read']), routes)).toBe('assets')
    expect(landingRoute(auth(['dashboard:read']), routes)).toBe('dashboard')
  })
  it('lands directly on an independently granted management page', () => {
    expect(landingRoute(auth(['contacts:read']), routes)).toBe('customer-contacts')
    expect(navigationGroupVisible(auth(['contacts:read']), routes, ['管理'])).toBe(true)
  })
  it('no capabilities land on a stable informational route, not a denied asset redirect', () => {
    expect(landingRoute(auth([]), routes)).toBe('access-unavailable')
    expect(navigationGroupVisible(auth([]), routes, ['管理', '资源', '服务'])).toBe(false)
  })
  it('anonymous navigation goes to login', () => expect(landingRoute(null, routes)).toBe('login'))
  it('does not derive grants from padmin or treat pages without a capability as business access', () => {
    expect(routeAllowed({ capability: 'assets:read' }, auth([]))).toBe(false)
    expect(routeAllowed({}, auth(['assets:read']))).toBe(false)
  })
  it('role and role-list restrictions remain in addition to the selected grant', () => {
    expect(routeAllowed(routes[5]!.meta, auth(['tenants:read_inventory'], 'viewer'))).toBe(false)
    expect(routeAllowed(routes[6]!.meta, auth(['worklogs:read_own'], 'padmin'))).toBe(false)
    expect(routeAllowed(routes[7]!.meta, auth(['assets:read'], 'viewer'))).toBe(false)
    expect(routeAllowed(routes[7]!.meta, auth(['assets:read'], 'msp'))).toBe(true)
  })
  it('unsupported roles and unknown capability names do not gain a landing', () => {
    expect(landingRoute(auth(['assets:read'], 'unknown' as Session['user']['role']), routes)).toBe('access-unavailable')
    expect(landingRoute(auth(['invented:all']), routes)).toBe('access-unavailable')
  })
  it('permission removal immediately changes both group visibility and landing', () => {
    const before = auth(['contacts:read']), after = auth([])
    expect(navigationGroupVisible(before, routes, ['管理'])).toBe(true)
    expect(navigationGroupVisible(after, routes, ['管理'])).toBe(false)
    expect(landingRoute(after, routes)).toBe('access-unavailable')
  })
  it('malformed capability lists and route role restrictions fail closed', () => {
    for (const capabilities of ['assets:read', null, ['assets:read', 1]]) {
      expect(routeAllowed({ capability: 'assets:read' }, { ...auth([]), capabilities } as unknown as Session)).toBe(false)
    }
    expect(routeAllowed({ capability: 'assets:read', roles: 'padmin' }, auth(['assets:read']))).toBe(false)
    expect(routeAllowed({ capability: 'assets:read', roles: ['padmin', null] }, auth(['assets:read']))).toBe(false)
  })

})
