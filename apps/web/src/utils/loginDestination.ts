import type { Session } from '../api/types'
import { landingRoute, routeAllowed, type AccessRoute } from './routeAccess'
type LoginRoute = AccessRoute & { path: string }
/** Only exact known page paths survive reauthentication; old object/query context never does. */
export function loginDestination(current: Session, requested: string | null, routes: readonly LoginRoute[]): string {
  if (current.password_change_required) return '/account'
  const candidate = routes.find(route => route.path === requested && !route.path.includes(':'))
  if (candidate && (candidate.name === 'account' || routeAllowed(candidate.meta, current))) return candidate.path
  const landing = landingRoute(current, routes)
  return routes.find(route => route.name === landing)?.path ?? '/account'
}
