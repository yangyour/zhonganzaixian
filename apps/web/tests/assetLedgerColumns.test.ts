import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import type { Asset } from '../src/api/types'
import { assetLedgerCell, assetLedgerColumns } from '../src/utils/assetLedgerColumns'

const asset: Asset = { id: 'synthetic-id', tenant_id: 'synthetic-tenant', name: '明确资产', type: 'router', management_ip: '192.0.2.1', site_id: 'site-a', status: 'unknown', lifecycle_status: 'DRAFT', source: 'manual', sampled_at: null, unknown_reason: 'NOT_ONBOARDED', availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING', version: 1, created_at: '2026-10-02T00:00:00Z', updated_at: '2026-10-02T00:00:00Z' }
const source = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')
describe('M3-2 fixed ledger columns and explicit missing facts', () => {
  it('matches the extracted contract exact twelve labels and order, not a local restatement', () => {
    const contract = JSON.parse(source('../../../doc/development/evidence/contract_requirements.json'))
    const requirement = contract.requirements.find((item: { id: string }) => item.id === 'M3-2')
    const labels = requirement.text.match(/12列：([^。]+)。/)[1].split('/')
    expect(assetLedgerColumns.map(c => c.label)).toEqual(labels)
    expect(labels).toHaveLength(12)
    expect(new Set(assetLedgerColumns.map(c => c.key)).size).toBe(12)
  })
  it('page renders only the ordered definition and keeps the actions fixed on the right', () => {
    const page = source('../src/pages/AssetsPage.vue')
    expect(page.match(/<el-table-column\b/g)).toHaveLength(1)
    expect(page).toContain('v-for="column in assetLedgerColumns"')
    expect(page).toContain(':label="column.label"')
    expect(page).toContain(":fixed=\"column.key === 'actions' ? 'right' : undefined\"")
    expect(page).toContain('assetLedgerCell(row, column.key, ledgerFacts.find(row)).text')
    expect(page).toContain('assetLedgerCell(row, column.key, ledgerFacts.find(row)).detail')
    expect(page).not.toContain('v-html')
  })
  it('keeps draft/source/address facts without adding columns', () => {
    expect(assetLedgerCell(asset, 'name')).toEqual({ text: '明确资产', detail: '192.0.2.1 · 手动草稿 · 未采集 · 草稿' })
    expect(assetLedgerCell(asset, 'type').text).toBe('路由器')
    expect(assetLedgerCell(asset, 'actions').text).toBe('详情')
  })
  it('uses explicit site name or existing site id but never invents a proxy', () => {
    expect(assetLedgerCell(asset, 'site')).toEqual({ text: 'site-a', detail: 'Proxy：— 当前接口未提供' })
    expect(assetLedgerCell({ ...asset, site_name: '明确站点' }, 'site').text).toBe('明确站点')
    expect(assetLedgerCell({ ...asset, site_id: '' }, 'site').text).toBe('—')
  })
  it('never infers zero problems, percentages, ownership or business from unrelated facts', () => {
    const injected = { ...asset, status: 'ok' as const, cpu: 0, memory: 0, problem_count: 0, owner_id: 'private-owner', business_description: 'private-business' }
    for (const key of ['business', 'cpu', 'memory', 'problems', 'owner'] as const) expect(assetLedgerCell(injected, key)).toEqual({ text: '—', detail: '当前接口未提供' })
  })
  it('uses availability independently of aggregate status or lifecycle', () => {
    expect(assetLedgerCell({ ...asset, status: 'ok', lifecycle_status: 'ACTIVE' }, 'availability').text).toBe('未知')
    expect(assetLedgerCell({ ...asset, availability: null, status: 'ok' }, 'availability')).toEqual({ text: '—', detail: '当前接口未提供' })
  })
  it('does not invent semantics for unexpected monitoring or sync codes', () => {
    expect(assetLedgerCell({ ...asset, availability: 'FUTURE_VALUE' }, 'availability')).toEqual({ text: '未识别状态', detail: 'FUTURE_VALUE' })
    expect(assetLedgerCell({ ...asset, sync_state: 'FUTURE_VALUE' }, 'sync')).toEqual({ text: '未识别状态', detail: 'FUTURE_VALUE' })
    expect(assetLedgerCell(asset, 'sync').text).toBe('待接入')
    expect(assetLedgerCell({ ...asset, sync_state: undefined }, 'sync').text).toBe('—')
  })
  it('keeps null/invalid collection time explicit instead of falling back to modification time', () => {
    expect(assetLedgerCell(asset, 'sampled')).toEqual({ text: '—', detail: '未取得采样时间' })
    expect(assetLedgerCell({ ...asset, sampled_at: 'not-a-date' }, 'sampled')).toEqual({ text: '—', detail: '采样时间无效' })
    expect(assetLedgerCell({ ...asset, sampled_at: '2026-10-02T01:02:03Z' }, 'sampled').text).toContain('09:02:03')
  })
  it('projection is pure and does not mutate or fetch internal metadata', () => {
    const before = JSON.stringify(asset)
    Object.freeze(asset)
    for (const column of assetLedgerColumns) expect(assetLedgerCell(asset, column.key).text.length).toBeGreaterThan(0)
    expect(JSON.stringify(asset)).toBe(before)
    expect(source('../src/utils/assetLedgerColumns.ts')).not.toMatch(/fetch\(|api\.request|manual-metadata/)
  })
})
it('rejects normalized impossible or non-UTC collection timestamps instead of displaying another day',()=>{for(const sampled_at of ['2026-02-30T00:00:00Z','2026-10-02T24:00:00Z','0000-01-01T00:00:00Z','2026-10-02T01:02:03+08:00','2026-10-02'])expect(assetLedgerCell({...asset,sampled_at},'sampled')).toEqual({text:'—',detail:'采样时间无效'})})
it('accepts precise leap-day collection timestamps without replacing their source',()=>{const sampled_at='2024-02-29T01:02:03.123456789Z';const value={...asset,sampled_at};expect(assetLedgerCell(value,'sampled').text).toContain('09:02:03');expect(value.sampled_at).toBe(sampled_at)})
