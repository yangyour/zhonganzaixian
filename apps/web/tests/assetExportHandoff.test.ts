import { describe, expect, it } from 'vitest'
import { assetExportHandoffScope, createAssetExportHandoff, readAssetExportHandoff } from '../src/utils/assetExportHandoff'
import type { Session } from '../src/api/types'

describe('asset list export handoff', () => {
  it('carries only applied filters and the original list cursor, without turning pagination into export limits', () => {
    const raw = createAssetExportHandoff('scope', { q: '设备', type: 'switch', status: 'unknown', site_id: 'site', cursor: 'page2', page_size: '25', asset_id: 'detail-ignored' })
    const value = readAssetExportHandoff(raw, 'scope')!
    expect(value.filters).toEqual({ query: '设备', type: 'switch', status: 'unknown', site_id: 'site' })
    expect(value.listQuery).toEqual({ q: '设备', type: 'switch', status: 'unknown', site_id: 'site', cursor: 'page2', page_size: '25' })
    expect(value.filters).not.toHaveProperty('page_size')
  })
  it('keeps unrestricted filters explicit and rejects another account or tenant scope', () => {
    const raw = createAssetExportHandoff('owner', {})
    expect(readAssetExportHandoff(raw, 'owner')?.filters).toEqual({ query: null, type: null, status: null, site_id: null })
    expect(readAssetExportHandoff(raw, 'other')).toBeNull()
  })
  it('rejects tampered filter/query mismatches, unexpected return paths and malformed inputs', () => {
    const valid = JSON.parse(createAssetExportHandoff('owner', { q: 'original' }))
    expect(readAssetExportHandoff(JSON.stringify({ ...valid, filters: { ...valid.filters, query: 'different' } }), 'owner')).toBeNull()
    expect(readAssetExportHandoff(JSON.stringify({ ...valid, listQuery: { returnTo: 'https://external.invalid' } }), 'owner')).toBeNull()
    for (const input of [null, {}, '{', 'x'.repeat(8193)]) expect(readAssetExportHandoff(input, 'owner')).toBeNull()
    expect(() => createAssetExportHandoff('owner', { type: 'unsupported' })).toThrow()
    expect(() => createAssetExportHandoff('owner', { q: ['first', 'second'] })).toThrow()
  })
  it('binds the handoff to account revision, actor, permission revision and session generation', () => {
    const session = { tenant: { id: 'tenant' }, user: { id: 'actor', role: 'padmin' }, permission_version: 1, account_version: 1 } as Session
    const owner = assetExportHandoffScope(session, 1)
    for (const changed of [{ ...session, account_version: 2 }, { ...session, permission_version: 2 }, { ...session, user: { ...session.user, id: 'other' } }]) {
      expect(assetExportHandoffScope(changed, 1)).not.toBe(owner)
    }
    expect(assetExportHandoffScope(session, 2)).not.toBe(owner)
  })
})
