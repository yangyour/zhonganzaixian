import { expect, it, vi } from 'vitest'
import { createDeliveryJournalReader, emptyDeliveryRead, readDeliveryPage } from '../src/utils/deliveryJournal'
import { deliveryScope as scope, deliveryPage } from './deliveryJournal.fixture'

const target = { kind: 'list' as const, pool: null, size: 10, cursor: null, attention: 'ATTENTION' as const }
export const attentionPage = () => ({ ...deliveryPage(), status_filter: 'ATTENTION', scanned_roots: 12,
  root_scan_limit: 1000, revision_scan_limit: 24002, population_scope: 'COMPLETE_BOUNDED_VERIFIED_JOURNAL_HEADS' })

it('uses complete filtered population and explicitly read-only GET for attention', async () => {
  let state = emptyDeliveryRead()
  const request = vi.fn().mockResolvedValue(attentionPage())
  const reader = createDeliveryJournalReader({ request }, value => { state = value })
  reader.reset({ tenant: scope.tenant, actor: scope.actor, permissionVersion: 1 })
  expect(await reader.load(target)).toBe(true)
  expect(request.mock.calls[0][0]).toBe('/notification-deliveries/attention?page_size=10&status=ATTENTION')
  expect(request.mock.calls[0][1].method).toBe('GET')
  expect(state.page?.total_count).toBe(1)
})
it('refuses truncated populations, mismatched status, and promoted receipt verification', () => {
  for (const patch of [{ scanned_roots: 0 }, { scanned_roots: 1001 }, { root_scan_limit: 20 },
    { population_scope: 'CURRENT_PAGE_ONLY' }, { status_filter: 'UNKNOWN' }, { total_count: 2 },
    { provider_receipt_verified: true }]) {
    expect(() => readDeliveryPage({ ...attentionPage(), ...patch }, scope.tenant, target)).toThrow()
  }
  const wrong = attentionPage(); wrong.items[0].recorded_status = 'ACCEPTED'
  expect(() => readDeliveryPage(wrong, scope.tenant, target)).toThrow()
})
it('retries the same status and cursor after budget or network failures without clearing to success', async () => {
  let state = emptyDeliveryRead()
  const request = vi.fn().mockRejectedValue(new Error('synthetic read budget'))
  const reader = createDeliveryJournalReader({ request }, value => { state = value })
  reader.reset({ tenant: scope.tenant, actor: scope.actor, permissionVersion: 1 })
  await reader.load({ ...target, cursor: { before: '2026-10-24T00:00:00Z', before_id: scope.id } })
  await reader.retry()
  expect(request.mock.calls[0][0]).toBe(request.mock.calls[1][0])
  expect(state.page).toBeNull()
  expect(state.error).toBeTruthy()
})
