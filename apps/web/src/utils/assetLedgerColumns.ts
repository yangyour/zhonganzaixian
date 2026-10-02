import type { Asset } from '../api/types'
import { displayTime, sourceLabel, typeLabel } from './display'

/** M3-2 / T00R027. Fixed order; extra internal metadata remains in its own view. */
export const assetLedgerColumns = [
  { key: 'name', label: '资产名称', minWidth: 220 },
  { key: 'type', label: '设备类型', minWidth: 130 },
  { key: 'site', label: '站点·Proxy', minWidth: 170 },
  { key: 'business', label: '所属业务系统', minWidth: 150 },
  { key: 'cpu', label: 'CPU', minWidth: 110 },
  { key: 'memory', label: '内存', minWidth: 110 },
  { key: 'availability', label: '可用性', minWidth: 130 },
  { key: 'problems', label: '当前问题数', minWidth: 130 },
  { key: 'sync', label: '配置同步状态', minWidth: 160 },
  { key: 'owner', label: '负责人', minWidth: 130 },
  { key: 'sampled', label: '最近采集时间', minWidth: 180 },
  { key: 'actions', label: '操作', minWidth: 80 },
] as const
export type AssetLedgerColumnKey = typeof assetLedgerColumns[number]['key']
export interface LedgerCell { text: string; detail?: string }
const missing = (): LedgerCell => ({ text: '—', detail: '当前接口未提供' })
function explicitState(value: string | null | undefined, known: Record<string, string>): LedgerCell {
  if (!value) return missing()
  return { text: known[value] || '未识别状态', detail: known[value] ? undefined : value }
}

/** Project only the public Asset DTO. No internal owner lookup, inferred zero or invented metrics. */
export function assetLedgerCell(asset: Asset, key: AssetLedgerColumnKey): LedgerCell {
  switch (key) {
    case 'name': return { text: asset.name || '—', detail: `${asset.management_ip || '—'} · ${sourceLabel(asset.source)} · ${asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status}` }
    case 'type': return { text: typeLabel(asset.type) }
    case 'site': return { text: asset.site_name || asset.site_id || '—', detail: 'Proxy：— 当前接口未提供' }
    case 'availability': return explicitState(asset.availability, { UNKNOWN: '未知' })
    case 'sync': return explicitState(asset.sync_state, { PENDING_ONBOARDING: '待接入' })
    case 'sampled': return !asset.sampled_at ? { text: '—', detail: '未取得采样时间' } : Number.isNaN(Date.parse(asset.sampled_at)) ? { text: '—', detail: '采样时间无效' } : { text: displayTime(asset.sampled_at) }
    case 'actions': return { text: '详情' }
    default: return missing()
  }
}
