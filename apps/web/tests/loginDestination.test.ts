import { afterEach, expect, it } from 'vitest'
import { loginDestination } from '../src/utils/loginDestination'
import { forgetSession, sessionExpired, loginReturnPath } from '../src/stores/session'
import type { Session } from '../src/api/types'
const current = { user: { role: 'viewer' }, capabilities: ['assets:read'], password_change_required: false } as Session
const routes = [
  { path: '/assets', name: 'assets', meta: { capability: 'assets:read' } },
  { path: '/identity/users', name: 'users', meta: { capability: 'users:read' } },
  { path: '/account', name: 'account', meta: {} },
]
afterEach(() => forgetSession())
it('returns to an exact permitted page after reauthentication', () => {
  expect(loginDestination(current, '/assets', routes)).toBe('/assets')
  expect(loginDestination(current, '/account', routes)).toBe('/account')
})
it('does not restore forbidden pages, external locations or old object query state', () => {
  for (const path of ['/identity/users', '//example.invalid', 'https://example.invalid', '/assets?asset_id=old', '/assets#old', '/%2fexample.invalid']) {
    expect(loginDestination(current, path, routes)).toBe('/assets')
  }
})
it('honors password change and current granted landing even if the previous page was allowed', () => {
  expect(loginDestination({ ...current, password_change_required: true }, '/assets', routes)).toBe('/account')
  expect(loginDestination({ ...current, capabilities: ['users:read'] }, '/assets', routes)).toBe('/identity/users')
})
it('keeps only the page path in memory and clears expiry feedback on normal logout', () => {
  forgetSession({ reason: 'expired', path: '/assets?asset_id=private#detail' })
  expect(sessionExpired.value).toBe(true); expect(loginReturnPath.value).toBe('/assets')
  forgetSession(); expect(sessionExpired.value).toBe(false); expect(loginReturnPath.value).toBeNull()
})
it('does not retain malformed return paths', () => {
  for (const path of ['//example.invalid', '/assets/../account', '/assets%3fsecret', '/asset\n']) {
    forgetSession({ reason: 'expired', path }); expect(loginReturnPath.value).toBeNull()
  }
})
