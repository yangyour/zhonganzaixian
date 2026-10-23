import type { AssetType, Role } from '../api/types'
export const assetTypes: { value: AssetType; label: string }[] = [
  { value: 'router', label: '路由器' }, { value: 'switch', label: '交换机' },
  { value: 'firewall', label: '防火墙' }, { value: 'ap', label: '无线 AP' },
  { value: 'wireless_controller', label: '无线控制器' }, { value: 'other', label: '其他' },
]
export const typeLabel = (type: string) => assetTypes.find(item => item.value === type)?.label || '其他'
export const roleLabel: Record<Role, string> = { viewer: '客户只读成员', tadmin: '客户管理员', msp: 'MSP 工程师', padmin: '平台管理员' }
export const displayValue = (value: unknown) => value === null || value === undefined ? '待确认' : typeof value === 'object' ? JSON.stringify(value) : typeof value === 'boolean' ? value ? '是' : '否' : String(value)
/** Contract-fixed business.timezone (04 registry), not the browser timezone or a selected report period. */
export const businessTimeZone = 'Asia/Shanghai' as const
export const displayTime = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: businessTimeZone, hour12: false }) : '—'
const compactFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: businessTimeZone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const clockFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: businessTimeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
/** Dense table cells: "10/23 13:35" in the contract business time zone; the full value stays in displayTime. */
export const compactTime = (value?: string | null) => { if (!value) return '—'; const date = new Date(value); return Number.isFinite(date.getTime()) ? compactFormat.format(date) : '—' }
/** Axis ticks: "13:35" in the contract business time zone. */
export const clockTime = (value?: string | number | null) => { if (value === null || value === undefined || value === '') return '—'; const date = new Date(value); return Number.isFinite(date.getTime()) ? clockFormat.format(date) : '—' }
export const sourceLabel = (source: string) => ({ test_seed: '测试预置 · 未采集', manual: '手动草稿 · 未采集', manual_draft: '人工草稿 · 未采集', fixture: '测试夹具 · 未采集' } as Record<string, string>)[source] || source
