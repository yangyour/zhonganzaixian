import { describe, it, expect, vi } from 'vitest'
import { ApiError, type ApiClient } from '../src/api/client'
import { createMetadataWorkspace, emptyMetadataState, readMetadataCurrent, validMetadataDate, validateMetadataInput, type MetadataContext, type MetadataCurrent, type MetadataInput, type MetadataRevision, type MetadataState } from '../src/utils/assetMetadataWorkspace'
const id = (n: number) => `55000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
const context: MetadataContext = { tenantId: id(1), assetId: id(2), actorId: id(3), permissionVersion: 1, canWrite: true }
const input = (): MetadataInput => ({ name: '虚构资料', type: 'router', site_id: 'site-a', brand: null, model: null, serial_number: null, location: null, owner_id: null, business_description: null, purchase_date: null, warranty_expiry: null, overdue_service_date: null, collection_method_note: null, note: '明确备注' })
const current = (version = 1, facts = input()): MetadataCurrent => ({ asset: { id: context.assetId, tenant_id: context.tenantId, name: facts.name, type: facts.type, management_ip: '192.0.2.1', site_id: facts.site_id, source: 'manual', status: 'unknown', lifecycle_status: 'DRAFT', sampled_at: null, unknown_reason: 'NOT_ONBOARDED', availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING', version, created_at: '2026-10-02T00:00:00Z', updated_at: '2026-10-02T00:00:00Z' }, metadata: version > 1 ? facts : null, metadata_version: version > 1 ? version : null, recorded_by: version > 1 ? context.actorId : null, recorded_at: version > 1 ? '2026-10-02T00:00:00Z' : null, can_edit: true, edit_blockers: [], scope: 'INTERNAL_MANUAL_DRAFT', verification_status: version > 1 ? 'SELF_REPORTED' : null, source: 'postgresql', monitoring_applied: false, cmdb_applied: false, management_ip_change_status: 'CONFIG_PENDING' })
const revision = (n: number, facts = input()): MetadataRevision => ({ asset_version: n, previous_metadata_version: n > 2 ? n - 1 : null, actor_id: context.actorId, recorded_at: '2026-10-02T00:00:00Z', before: { asset: current(n - 1).asset, metadata: n > 2 ? input() : null, metadata_version: n > 2 ? n - 1 : null }, metadata: facts, management_ip: '192.0.2.1', verification_status: 'SELF_REPORTED', monitoring_applied: false, cmdb_applied: false })
const history = (n = 1) => ({ items: Array.from({ length: n - 1 }, (_, i) => revision(n - i)), has_more: false, next_before_version: null, source: 'postgresql', scope: 'INTERNAL_MANUAL_DRAFT', page_size_limit: 100 })
const refs = (query = '') => ({ sites: [{ id: 'site-a', label: '站点' }], owners: [{ id: context.actorId, label: '负责人' }], sites_truncated: false, owners_truncated: false, query, limit_per_kind: 100, scope: 'CURRENT_TENANT_ACTIVE_REFERENCES', source: 'postgresql', reference_validation: 'RECHECKED_ON_WRITE', default_owner_id: null })
type Options = Parameters<ApiClient['request']>[1]
function harness(handler: (path: string, options: Options) => unknown | Promise<unknown>) {
  let state: MetadataState = emptyMetadataState()
  const request = vi.fn(async (path: string, options?: Options) => handler(path, options))
  const workspace = createMetadataWorkspace({ request } as unknown as Pick<ApiClient, 'request'>, next => { state = next }, () => 'original-key')
  workspace.reset(context); return { workspace, request, state: () => state }
}
const reads = (url: string, n = 1) => url.includes('/history') ? history(n) : current(n)
describe('manual asset metadata facts', () => {
  it('preserves explicit unknowns and accepts strict date boundaries without inference', () => { validateMetadataInput(input()); for (const date of [null, '0001-01-01', '9999-12-31', '2024-02-29']) expect(validMetadataDate(date)).toBe(true); for (const date of ['0000-01-01', '2026-02-29', '2026-10-02T00:00:00Z', 123]) expect(validMetadataDate(date)).toBe(false) })
  it('rejects missing or forged fields, scalar coercion, blank and malformed Unicode', () => {
    for (const changes of [{ type: ['router'] }, { note: '\u00a0' }, { name: '\ud800' }, { brand: 'x\u0000' }, { name: '😀'.repeat(101) }, { management_ip: '192.0.2.2' }, { owner_id: '' }, { purchase_date: '2026-02-30' }]) expect(() => validateMetadataInput({ ...input(), ...changes })).toThrow()
    expect(() => validateMetadataInput({ ...input(), note: ' 😀\n\t '.repeat(20) })).not.toThrow(); const missing: Partial<MetadataInput> = input(); delete missing.owner_id; expect(() => validateMetadataInput(missing)).toThrow()
  })
  it('keeps absent metadata unverified and rejects false normal/configured facts', () => {
    expect(readMetadataCurrent(current(), context).metadata).toBeNull(); for (const changes of [{ verification_status: 'SELF_REPORTED' }, { metadata_version: 2 }, { monitoring_applied: true }, { cmdb_applied: true }, { can_edit: false }, { scope: 'ALL' }]) expect(() => readMetadataCurrent({ ...current(), ...changes }, context)).toThrow()
  })
  it('rejects foreign scope and missing source blockers while accepting explicitly blocked seeds', () => {
    for (const changes of [{ tenant_id: id(9) }, { id: id(9) }, { source: 'test_seed' }, { status: 'ok' }]) expect(() => readMetadataCurrent({ ...current(), asset: { ...current().asset, ...changes } }, context)).toThrow()
    expect(readMetadataCurrent({ ...current(), asset: { ...current().asset, source: 'test_seed' }, can_edit: false, edit_blockers: ['MANUAL_DRAFT_REQUIRED'] }, context).can_edit).toBe(false)
  })
})
describe('metadata request state', () => {
  it('refresh only reads and validates actual empty history', async () => { const h = harness(url => reads(url)); expect(await h.workspace.refresh()).toBe(true); expect(h.state().history?.items).toEqual([]); expect(h.request.mock.calls.every(([, o]) => !o?.method)).toBe(true) })
  it('rejects inconsistent revision snapshots and clears current display', async () => {
    const h = harness(url => url.includes('/history') ? { ...history(2), items: [{ ...revision(2), before: { ...revision(2).before, asset: { ...current().asset, version: 2 } } }] } : current(2)); expect(await h.workspace.refresh()).toBe(false); expect(h.state().current).toBeNull()
  })
  it('successful receipt is retained when the following readonly refresh fails', async () => {
    let saved = false; const h = harness((url, o) => { if (o?.method) { saved = true; return current(2) } if (saved) throw new ApiError(503, 'UNAVAILABLE', 'isolated', 't'); return reads(url) }); await h.workspace.refresh(); expect(await h.workspace.submit(input(), 1)).toBe(true); expect(h.state().receipt?.asset.version).toBe(2); expect(h.state().current).toBeNull(); expect(h.state().attempt?.state).toBe('accepted')
  })
  it('uncertainty preserves original key/body/version through readonly refresh and exact retry', async () => {
    let writes = 0; const h = harness((url, o) => { if (o?.method) { if (++writes === 1) throw new ApiError(0, 'NETWORK_ERROR', 'isolated', 't'); return current(2) } return reads(url, writes ? 2 : 1) }); await h.workspace.refresh(); const facts = input(); expect(await h.workspace.submit(facts, 1)).toBe(false); facts.note = 'must not replace original'; const original = h.state().attempt?.payload; expect(h.workspace.unresolved()).toBe(true); await h.workspace.refresh(); expect(writes).toBe(1); expect(h.state().attempt?.payload).toBe(original); expect(await h.workspace.submit(input(), 2)).toBe(false); expect(await h.workspace.retry()).toBe(true); const commands = h.request.mock.calls.filter(([, o]) => o?.method); expect(commands[0]![1]?.body).toEqual(commands[1]![1]?.body); expect(commands[0]![1]?.idempotencyKey).toBe(commands[1]![1]?.idempotencyKey)
  })
  it('409 is a rejection and never silently retried or replaced with fresh CAS', async () => { const h = harness((url, o) => { if (o?.method) throw new ApiError(409, 'VERSION_CONFLICT', 'isolated', 't'); return reads(url) }); await h.workspace.refresh(); expect(await h.workspace.submit(input(), 1)).toBe(false); expect(h.state().attempt?.state).toBe('rejected'); expect(await h.workspace.retry()).toBe(false); expect(h.state().current).toBeNull() })
  it('does not allow an old form baseline after a newer readonly refresh', async () => { let v = 1; const h = harness(url => reads(url, v)); await h.workspace.refresh(); v = 2; await h.workspace.refresh(); expect(await h.workspace.submit(input(), 1)).toBe(false); expect(h.request.mock.calls.filter(([, o]) => o?.method)).toHaveLength(0) })
  it('rejects a changed management IP in a purported successful receipt as uncertain', async () => { const h = harness((url, o) => o?.method ? { ...current(2), asset: { ...current(2).asset, management_ip: '192.0.2.9' } } : reads(url)); await h.workspace.refresh(); expect(await h.workspace.submit(input(), 1)).toBe(false); expect(h.workspace.unresolved()).toBe(true); expect(h.state().receipt).toBeNull() })
  it('invalidates late reads on tenant/permission change and clears retained receipts', async () => { let resolve!: (v: unknown) => void; const h = harness(() => new Promise(r => { resolve = r })); const pending = h.workspace.refresh(); h.workspace.reset({ ...context, tenantId: id(9), permissionVersion: 2 }); resolve(current()); expect(await pending).toBe(false); expect(h.state().current).toBeNull(); expect(h.state().receipt).toBeNull() })
  it('invalidates late writes without publishing old context results', async () => { let resolve!: (v: unknown) => void; const h = harness((url, o) => o?.method ? new Promise(r => { resolve = r }) : reads(url)); await h.workspace.refresh(); const pending = h.workspace.submit(input(), 1); h.workspace.reset(null); resolve(current(2)); expect(await pending).toBe(false); expect(h.state().receipt).toBeNull(); expect(await h.workspace.retry()).toBe(false) })
  it('loads bounded reference labels without default owner and encodes literal search', async () => { const h = harness(() => refs('%_')); expect(await h.workspace.references('%_')).toBe(true); expect(h.request.mock.calls[0]![0]).toContain('q=%25_'); expect(h.state().references?.default_owner_id).toBeNull(); expect(h.state().current).toBeNull() })
  it('rejects overfull, duplicate or falsely defaulted references', async () => { for (const changes of [{ default_owner_id: id(3) }, { owners: Array(101).fill(refs().owners[0]) }, { sites_truncated: true }, { owners: [refs().owners[0], refs().owners[0]] }]) { const h = harness(() => ({ ...refs(), ...changes })); expect(await h.workspace.references()).toBe(false); expect(h.state().references).toBeNull() } })
  it('readonly scope and non-pristine assets cannot submit', async () => { const h = harness(url => reads(url)); h.workspace.reset({ ...context, canWrite: false }); await h.workspace.refresh(); expect(await h.workspace.submit(input(), 1)).toBe(false); const blocked = harness(url => url.includes('/history') ? history() : { ...current(), asset: { ...current().asset, source: 'test_seed' }, can_edit: false, edit_blockers: ['MANUAL_DRAFT_REQUIRED'] }); await blocked.workspace.refresh(); expect(await blocked.workspace.submit(input(), 1)).toBe(false) })
  it('pages older immutable revisions with a validated bounded version cursor', async () => {
    const h = harness(url => url.includes('/history') ? url.includes('before_version=3') ? { ...history(2) } : { ...history(17), items: history(17).items.slice(0, 15), has_more: true, next_before_version: 3 } : current(17));
    expect(await h.workspace.refresh()).toBe(true); expect(h.state().history?.items).toHaveLength(15); expect(await h.workspace.refresh(true)).toBe(true); expect(h.state().history?.items.map(v => v.asset_version)).toEqual([2]); expect(h.state().current?.asset.version).toBe(17)
  })
  it('rejects missing historical links and substituted latest facts', async () => {
    for (const items of [[{ ...revision(3), previous_metadata_version: null }, revision(2)], [{ ...revision(2), metadata: { ...input(), note: 'substituted' } }]]) {
      const h = harness(url => url.includes('/history') ? { ...history(), items } : current(items[0]!.asset_version)); expect(await h.workspace.refresh()).toBe(false); expect(h.state().history).toBeNull(); expect(h.state().current).toBeNull()
    }
  })
  it('rejects equal-version changed data after a known receipt instead of overwriting it', async () => {
    let changed = false; const h = harness(url => url.includes('/history') ? history(2) : current(2, { ...input(), note: changed ? 'silent change' : input().note })); await h.workspace.refresh(); changed = true; expect(await h.workspace.refresh()).toBe(false); expect(h.state().current).toBeNull()
  })
  it('does not accept a receipt whose contents differ from the original command', async () => {
    const h = harness((url, o) => o?.method ? current(2, { ...input(), note: 'wrong receipt facts' }) : reads(url)); await h.workspace.refresh(); expect(await h.workspace.submit(input(), 1)).toBe(false); expect(h.workspace.unresolved()).toBe(true); expect(h.state().attempt?.payload).toContain('明确备注'); expect(h.state().receipt).toBeNull()
  })

})
