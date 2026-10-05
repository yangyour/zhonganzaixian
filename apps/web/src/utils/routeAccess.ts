import type { Session } from '../api/types'

export interface AccessRoute { name?: unknown; meta: { capability?: unknown; role?: unknown; roles?: unknown; group?: unknown } }
/** UI navigation only; API and protected repository checks remain authoritative. */
export function routeAllowed(meta: AccessRoute['meta'], current: Session | null): boolean {
  if (!current?.user || !['padmin', 'msp', 'tadmin', 'viewer'].includes(current.user.role) || !Array.isArray(current.capabilities) || !current.capabilities.every(value => typeof value === 'string')) return false
  if (meta.role !== undefined && meta.role !== current.user.role) return false
  if (meta.roles !== undefined && (!Array.isArray(meta.roles) || !meta.roles.every(value => typeof value === 'string') || !meta.roles.includes(current.user.role))) return false
  return typeof meta.capability === 'string' && current.capabilities.includes(meta.capability)
}
export function landingRoute(current: Session | null, routes: readonly AccessRoute[]): string {
  if (!current) return 'login'
  const allowed = routes.filter(route => typeof route.name === 'string' && routeAllowed(route.meta, current))
  return String(allowed.find(route => route.name === 'assets')?.name ?? allowed[0]?.name ?? 'access-unavailable')
}
export function navigationGroupVisible(current: Session | null, routes: readonly AccessRoute[], groups: readonly string[]): boolean {
  return routes.some(route => typeof route.meta.group === 'string' && groups.includes(route.meta.group) && routeAllowed(route.meta, current))
}
