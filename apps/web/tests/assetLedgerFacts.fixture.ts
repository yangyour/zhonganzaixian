import type { Asset } from '../src/api/types'
import type { AssetLedgerFact } from '../src/utils/assetLedgerFacts'

export const ledgerTenant = '21000000-0000-4000-8000-000000000001'
export const ledgerActor = '22000000-0000-4000-8000-000000000001'
export const ledgerId = (index: number) => '23000000-0000-4000-8000-' + String(index).padStart(12, '0')
/** Explicit synthetic browser/source fixtures. Never written to a live tenant. */
export function ledgerAssets(version = 3): Asset[] {
  return [1, 2].map(index => ({ id: ledgerId(index), tenant_id: ledgerTenant, name: '合成设备 ' + index, type: 'switch',
    management_ip: '192.0.2.' + index, site_id: 'synthetic-site-' + index, status: 'unknown', lifecycle_status: 'DRAFT', source: 'manual',
    sampled_at: null, unknown_reason: 'NOT_CONNECTED', version, created_at: '2026-10-23T00:00:00Z', updated_at: '2026-10-23T01:00:00Z',
    availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING' }))
}
export function ledgerFacts(ids: string[], version = 3) {
  return { tenant_id: ledgerTenant, observed_at: '2026-10-23T02:00:00Z', source: 'postgresql', scope: 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS',
    business_relationship_verified: false, proxy_verified: false, items: [...ids].reverse().map(id => {
      const index = id === ledgerId(1) ? 1 : 2
      return { asset_id: id, asset_version: version, site_id: 'synthetic-site-' + index, site_name: '合成站点名称 ' + index,
        metadata_version: 2, metadata_recorded_at: '2026-10-23T01:00:00Z', metadata_source: 'MANUAL_DECLARATION',
        business_description: '合成业务登记说明 ' + index, owner: { id: ledgerActor, display_name: '合成登记负责人 ' + index, status: 'ACTIVE' } } satisfies AssetLedgerFact
    }) }
}
