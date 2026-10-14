import { afterEach, expect, it, vi } from 'vitest'
import { api } from '../src/api/client'
import { forgetSession, session, refreshProjectSession, revalidateSessionAfterRestore, switchingTenant, rejectUnauthorizedSession, sessionExpired, loginReturnPath } from '../src/stores/session'
import type { Session } from '../src/api/types'
const auth = (name: string): Session => ({ user: { id: 'synthetic-user', display_name: name, role: 'padmin' }, tenant: { id: 'synthetic-tenant', name: '合成测试' }, allowed_tenants: [], capabilities: ['assets:read'], csrf_token: 'synthetic-not-live', permission_version: 1, mode: 'remote_test', identity_source: 'project_local' })
afterEach(() => { switchingTenant.value = false; forgetSession(); vi.restoreAllMocks() })
it('issues a new session read after the pre-freeze response finishes', async () => {
  session.value = auth('before')
  let finish!: (value: Session) => void
  const request = vi.spyOn(api, 'request').mockImplementationOnce(() => new Promise(resolve => { finish = resolve as (value: Session) => void })).mockResolvedValueOnce(auth('fresh'))
  const oldRead = refreshProjectSession(), restore = revalidateSessionAfterRestore()
  expect(request).toHaveBeenCalledOnce()
  finish(auth('old-response')); await oldRead; await restore
  expect(request).toHaveBeenCalledTimes(2); expect(session.value?.user.display_name).toBe('fresh')
})
it('does not accept a skipped recheck while tenant switching is unresolved', async () => {
  session.value = auth('before'); switchingTenant.value = true
  const request = vi.spyOn(api, 'request')
  await expect(revalidateSessionAfterRestore()).rejects.toMatchObject({ code: 'SESSION_RECHECK_PENDING' })
  expect(request).not.toHaveBeenCalled()
})
it('propagates unavailable verification without claiming that the old session is fresh', async () => {
  session.value = auth('before'); vi.spyOn(api, 'request').mockRejectedValue(new Error('offline'))
  await expect(revalidateSessionAfterRestore()).rejects.toThrow('offline')
  expect(session.value?.user.display_name).toBe('before')
})
it('preserves the first expiry message when a second unauthorized reader settles on the login route', () => {
  session.value = auth('before')
  rejectUnauthorizedSession('/assets?asset_id=old')
  rejectUnauthorizedSession('/login')
  expect(session.value).toBeNull(); expect(sessionExpired.value).toBe(true); expect(loginReturnPath.value).toBe('/assets')
})
