import { computed, ref, shallowRef } from 'vue'
import { api, ApiError } from '../api/client'
import type { LogoutResult, Session } from '../api/types'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'

export const session = shallowRef<Session | null>(null)
export const switchingTenant = ref(false)
export const contextGeneration = ref(0)
let initialized = false
let contextActionPending = false
let initialization: Promise<void> | null = null
export const can = (capability: string) => session.value?.capabilities.includes(capability) ?? false
export const maySwitchTenant = computed(() => ['msp', 'padmin'].includes(session.value?.user.role || ''))
function installSession(value: Session) { session.value = value; api.setCsrfToken(value.csrf_token); initialized = true }
export function forgetSession() {
  contextGeneration.value = api.invalidateContext()
  api.setCsrfToken(null); session.value = null; initialized = true
}
export async function initializeSession() {
  if (initialized) return
  if (!initialization) initialization = api.request<Session>('/session').then(installSession).catch(error => {
    if (!(error instanceof ApiError && error.status === 401)) throw error
    initialized = true
  }).finally(() => { initialization = null })
  await initialization
}
export async function switchTenant(tenantId: string) {
  if (contextActionPending || switchingTenant.value || tenantId === session.value?.tenant.id) return false
  contextActionPending = true
  try {
    const generation = contextGeneration.value
    if (!await contextTransitionGuards.approve() || generation !== contextGeneration.value) return false
    switchingTenant.value = true
    contextGeneration.value = api.invalidateContext()
    try { installSession(await api.request<Session>('/session/tenant', { method: 'POST', body: { tenant_id: tenantId } })) }
    catch (error) {
      // Re-read the authoritative context after an uncertain network outcome.
      try { installSession(await api.request<Session>('/session')) } catch { forgetSession() }
      throw error
    } finally { switchingTenant.value = false }
    return true
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
