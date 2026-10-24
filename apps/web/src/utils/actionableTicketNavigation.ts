import type { LocationQuery, Router } from 'vue-router'
import { createRequestId } from './browserCrypto'

// Per-tab route ownership only, never ticket data or persisted authentication. Each history entry carries an opaque key.
const owners = new WeakMap<Router, Map<string, string>>()
export function restoreActionableNavigation(router: Router, owner: string, query: LocationQuery) {
  let known = owners.get(router)
  if (!known) { known = new Map(); owners.set(router, known) }
  const existing = typeof query.view === 'string' && known.get(query.view) === owner ? query.view : null
  const page = typeof query.page === 'string' && /^[1-9][0-9]{0,15}$/.test(query.page) ? Number(query.page) : 1
  if (existing) return { view: existing, page: Number.isSafeInteger(page) && BigInt(page) * 20n <= 9_007_199_254_740_990n ? page : 1 }
  const view = createRequestId()
  // Old history can safely restart at page one after bounded ownership metadata is discarded.
  if (known.size >= 100) known.delete(known.keys().next().value!)
  known.set(view, owner)
  return { view, page: 1 }
}
