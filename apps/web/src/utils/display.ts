import type { AssetType, Role } from '../api/types'
export const assetTypes: { value: AssetType; label: string }[] = [
  { value: 'router', label: '路由器' }, { value: 'switch', label: '交换机' },
  { value: 'firewall', label: '防火墙' }, { value: 'ap', label: '无线 AP' },
  { value: 'wireless_controller', label: '无线控制器' }, { value: 'other', label: '其他' },
]
export const typeLabel = (type: string) => assetTypes.find(item => item.value === type)?.label || '其他'
export const roleLabel: Record<Role, string> = { viewer: '客户只读', tadmin: '客户管理员', msp: 'MSP 工程师', padmin: '平台管理员' }
export const displayValue = (value: unknown) => value === null || value === undefined ? '待确认' : typeof value === 'object' ? JSON.stringify(value) : typeof value === 'boolean' ? value ? '是' : '否' : String(value)
export const displayTime = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
export const sourceLabel = (source: string) => ({ test_seed: '测试种子 · 未采集', manual: '手动草稿 · 未采集', manual_draft: '手动草稿 · 未采集', fixture: '历史单元夹具 · 未采集' } as Record<string, string>)[source] || source
