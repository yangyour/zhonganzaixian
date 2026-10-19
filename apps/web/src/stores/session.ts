import { computed, ref, shallowRef } from 'vue'
import { api, ApiError } from '../api/client'
import type { LogoutResult, Session } from '../api/types'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { routeAllowed } from '../utils/routeAccess'

export const session = shallowRef<Session | null>(null)
export const switchingTenant = ref(false)
export const contextGeneration = ref(0)
export const sessionExpired = ref(false)
export const loginReturnPath = ref<string | null>(null)
let initialized = false
let contextActionPending = false
let initialization: Promise<void> | null = null
export const can = (capability: string) => routeAllowed({ capability }, session.value)
export const maySwitchTenant = computed(() => Array.isArray(session.value?.allowed_tenants) && session.value.allowed_tenants.length > 1)
function installSession(value: Session) { session.value = value; api.setCsrfToken(value.csrf_token); initialized = true }
export function forgetSession(expiry?: { reason: 'expired'; path: string }) {
  contextGeneration.value = api.invalidateContext()
  api.setCsrfToken(null); session.value = null; initialized = true
  sessionExpired.value = expiry?.reason === 'expired'
  const path = expiry?.path.split(/[?#]/)[0] ?? ''
  loginReturnPath.value = path.trim() === path && /^\/[a-zA-Z0-9/-]*$/.test(path) && !path.includes('//') ? path : null
}
/** Later anonymous 401s must not erase the original expiry explanation or safe return page. */
export function rejectUnauthorizedSession(path: string) {
  const expiry = session.value ? { reason: 'expired' as const, path } : sessionExpired.value ? { reason: 'expired' as const, path: loginReturnPath.value ?? '' } : undefined
  forgetSession(expiry)
}
export async function initializeSession() {
  if (initialized) return
  if (!initialization) initialization = api.request<Session>('/session').then(installSession).catch(error => {
    if (!(error instanceof ApiError && error.status === 401)) throw error
    initialized = true
  }).finally(() => { initialization = null })
  await initialization
}
let refreshing: Promise<void> | null = null
/** Re-read effective grants on navigation; a changed epoch disposes old page commands and data. */
export async function refreshProjectSession() {
  if (!session.value || !['project_local','oidc','wecom'].includes(session.value.identity_source) || switchingTenant.value) return
  if (!refreshing) refreshing = api.request<Session>('/session').then(value => {
    const previous = session.value
    if (!previous) return
    if (value.user.id !== previous.user.id || value.tenant.id !== previous.tenant.id || value.permission_version !== previous.permission_version || value.account_version !== previous.account_version) contextGeneration.value = api.invalidateContext()
    installSession(value)
  }).finally(() => { refreshing = null })
  await refreshing
}
/** Finish any pre-freeze read, then issue a new no-store read after restoration. */
export async function revalidateSessionAfterRestore() {
  if (refreshing) { try { await refreshing } catch { /* A new read still decides availability. */ } }
  if (!session.value) return
  if (switchingTenant.value || !['project_local', 'oidc', 'wecom'].includes(session.value.identity_source)) throw new ApiError(0, 'SESSION_RECHECK_PENDING', '当前会话切换或来源尚未确认，请稍后重新核对', '—')
  await refreshProjectSession()
}
export interface TenantViewTransition { fromTenantId: string | null; toTenantId: string; originalReceiptConfirmed: boolean }
export class TenantViewSynchronizationError extends ApiError {
  constructor(public readonly originalFailure: unknown, public readonly navigationFailure: unknown, public readonly originalReceiptConfirmed: boolean) {
    super(0, 'TENANT_VIEW_SYNC_FAILED', originalReceiptConfirmed ? '当前租户已由切换响应确认，但旧页面选项清理失败；请重新进入当前租户页面' : '原切换结果仍未确认；当前会话范围已回读，但旧页面选项清理失败', originalFailure instanceof ApiError ? originalFailure.traceId : '—')
  }
}
/** Keep the old page hidden until scope-bound navigation is cleared, including uncertain-write readback. */
export async function switchTenant(tenantId: string, beforeViewResume?: (transition: TenantViewTransition) => Promise<void>) {
  if (contextActionPending || switchingTenant.value || tenantId === session.value?.tenant.id) return false
  contextActionPending = true
  try {
    const generation = contextGeneration.value
    if (!await contextTransitionGuards.approve() || generation !== contextGeneration.value) return false
    const previousTenant = session.value?.tenant.id ?? null
    switchingTenant.value = true
    contextGeneration.value = api.invalidateContext()
    try {
      let failed = false, originalFailure: unknown
      try {
        const receipt = await api.request<Session>('/session/tenant', { method: 'POST', body: { tenant_id: tenantId } })
        if (receipt?.tenant?.id !== tenantId) throw new ApiError(200, 'INVALID_TENANT_SWITCH_RESPONSE', '切换响应未匹配原目标租户，结果未确认', '—')
        installSession(receipt)
      }
      catch (error) {
        failed = true; originalFailure = error
        // Current context readback is not an original switch receipt and cannot prove a late write did not commit.
        try { installSession(await api.request<Session>('/session')) } catch { forgetSession() }
      }
      const observedTenant = session.value?.tenant.id, observedGeneration = contextGeneration.value
      if (observedTenant && (!failed || observedTenant !== previousTenant)) {
        try { await beforeViewResume?.({ fromTenantId: previousTenant, toTenantId: observedTenant, originalReceiptConfirmed: !failed }) }
        catch (navigationFailure) { throw new TenantViewSynchronizationError(originalFailure, navigationFailure, !failed) }
      }
      if (failed) throw originalFailure
      return observedGeneration === contextGeneration.value && observedTenant === session.value?.tenant.id
    } finally { switchingTenant.value = false }
  } finally { contextActionPending = false }
}
export async function logout(): Promise<'cancelled' | 'redirected' | 'local'> {
  if (contextActionPending) return 'cancelled'
  contextActionPending = true
  try {
    const generation = contextGeneration.value
    if (!await contextTransitionGuards.approve() || generation !== contextGeneration.value) return 'cancelled'
    const result = await api.request<LogoutResult>('/session', { method: 'DELETE' })
    forgetSession()
    if (result.redirect_url === '/auth/logout') {
      window.location.assign(result.redirect_url)
      return 'redirected'
    }
    return 'local'
  } finally { contextActionPending = false }
}
