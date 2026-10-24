import { expect, it } from 'vitest'
import type { Router } from 'vue-router'
import { restoreActionableNavigation } from '../src/utils/actionableTicketNavigation'

it('restores only a page whose opaque history key belongs to the same authorization context', () => {
  const router = {} as Router
  const first = restoreActionableNavigation(router, 'tenant-a actor-a permission-1', {})
  expect(first.page).toBe(1)
  expect(first.view).toMatch(/^[0-9a-f-]{36}$/)
  expect(restoreActionableNavigation(router, 'tenant-a actor-a permission-1', { view: first.view, page: '3' })).toEqual({ view: first.view, page: 3 })
  const changed = restoreActionableNavigation(router, 'tenant-b actor-a permission-1', { view: first.view, page: '3' })
  expect(changed.page).toBe(1)
  expect(changed.view).not.toBe(first.view)
  expect(restoreActionableNavigation({} as Router, 'tenant-a actor-a permission-1', { view: first.view, page: '3' }).page).toBe(1)
})
it('rejects unowned, repeated, nonintegral and unsafe page queries without preserving an old page', () => {
  const router = {} as Router, owner = 'current-authorized-context'
  const first = restoreActionableNavigation(router, owner, {})
  for (const page of ['0', '-1', '1.5', '01', 'NaN', '9007199254740990', ['2', '3'], null]) {
    expect(restoreActionableNavigation(router, owner, { view: first.view, page }).page).toBe(1)
  }
  expect(restoreActionableNavigation(router, owner, { view: 'untrusted-history-key', page: '3' }).page).toBe(1)
  expect(restoreActionableNavigation(router, owner, { view: [first.view, first.view], page: '3' }).page).toBe(1)
})
it('retains independent history owners when a later tenant list is opened', () => {
  const router = {} as Router
  const before = restoreActionableNavigation(router, 'tenant-a', {})
  const after = restoreActionableNavigation(router, 'tenant-b', {})
  expect(restoreActionableNavigation(router, 'tenant-b', { view: after.view, page: '2' }).page).toBe(2)
  expect(restoreActionableNavigation(router, 'tenant-b', { view: before.view, page: '2' }).page).toBe(1)
})
