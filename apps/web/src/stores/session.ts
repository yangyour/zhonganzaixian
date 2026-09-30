import { computed, ref, shallowRef } from 'vue'
import { api, ApiError } from '../api/client'
import type { LogoutResult, Session } from '../api/types'

export const session = shallowRef<Session | null>(null)
export const switchingTenant = ref(false)
export const contextGeneration = ref(0)
let initialized = false
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
  if (switchingTenant.value || tenantId === session.value?.tenant.id) return
  switchingTenant.value = true
  contextGeneration.value = api.invalidateContext()
  try { installSession(await api.request<Session>('/session/tenant', { method: 'POST', body: { tenant_id: tenantId } })) }
  catch (error) {
    // Re-read the authoritative context after an uncertain network outcome.
    try { installSession(await api.request<Session>('/session')) } catch { forgetSession() }
    throw error
  } finally { switchingTenant.value = false }
}
export async function logout() {
  const result = await api.request<LogoutResult>('/session', { method: 'DELETE' })
  forgetSession()
  if (result.redirect_url === '/auth/logout') {
    window.location.assign(result.redirect_url)
    return true
  }
  return false
}
