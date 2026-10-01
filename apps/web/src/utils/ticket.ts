import type { CustomerReportCreate, TicketCategory, TicketImpactScope, TicketUrgency } from '../api/types'

export const ticketCategories: TicketCategory[] = ['网络不通', '网速慢', 'WiFi问题', '设备异常', '其他']
export const impactScopeLabel: Record<TicketImpactScope, string> = { company: '全公司', partial_area: '部分区域', individual: '个人' }
export const urgencyLabel: Record<TicketUrgency, string> = { normal: '一般', urgent: '很急（仅供参考）' }
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const isTicketUuid = (value: unknown): value is string => typeof value === 'string' && uuidPattern.test(value)
export function ticketIdFromResultRef(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const match = /^\/api\/v1\/tickets\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/.exec(value)
  return match?.[1]?.toLowerCase() || null
}
export interface CustomerReportForm {
  title: string; description: string; category: string; impact_scope: string
  contact_name: string; contact_phone: string; urgency: string; asset_id: string
}
export const emptyReportForm = (): CustomerReportForm => ({ title: '', description: '', category: '', impact_scope: '', contact_name: '', contact_phone: '', urgency: '', asset_id: '' })
export class ReportValidationError extends Error {
  constructor(public fields: Record<string, string>) { super('请检查报障信息'); this.name = 'ReportValidationError' }
}
function unsupportedText(value: string, multiline = false): boolean {
  // Descriptions may contain newline and tab; other fields remain single-line.
  if ((multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(value)) return true
  return Array.from(value).some(character => character.length === 1 && /[\ud800-\udfff]/.test(character))
}
export function parseCustomerReport(form: CustomerReportForm, selectableAssetIds: readonly string[]): CustomerReportCreate {
  const fields: Record<string, string> = {}
  function text(key: 'title' | 'description' | 'contact_name' | 'contact_phone', max: number, required = true) {
    const raw = form[key], value = raw.trim()
    if (unsupportedText(raw, key === 'description')) fields[key] = '包含不支持的控制字符或不完整字符，请检查后再提交'
    else if ((required && !value) || Array.from(value).length > max) fields[key] = `${required ? '请填写，' : ''}最多 ${max} 个字符`
    return value
  }
  const title = text('title', 200, false), description = text('description', 3000)
  const contact_name = text('contact_name', 100), contact_phone = text('contact_phone', 64)
  if (contact_phone && (!/^[0-9 +()\-]+$/.test(contact_phone) || !/[0-9]/.test(contact_phone))) fields.contact_phone = '请填写联系电话，仅使用数字、空格、+、- 或括号，且至少含一位数字'
  if (!ticketCategories.includes(form.category as TicketCategory)) fields.category = '请选择故障分类'
  if (!Object.hasOwn(impactScopeLabel, form.impact_scope)) fields.impact_scope = '请选择影响范围'
  if (!Object.hasOwn(urgencyLabel, form.urgency)) fields.urgency = '请选择紧急程度'
  if (form.asset_id && (!isTicketUuid(form.asset_id) || !selectableAssetIds.includes(form.asset_id))) fields.asset_id = '请从当前可见设备中选择，或选择“不确定”'
  if (Object.keys(fields).length) throw new ReportValidationError(fields)
  return {
    source: 'customer_report', description, category: form.category as TicketCategory,
    impact_scope: form.impact_scope as TicketImpactScope, contact_name, contact_phone, urgency: form.urgency as TicketUrgency,
    ...(title ? { title } : {}), ...(form.asset_id ? { asset_id: form.asset_id.toLowerCase() } : {}),
  }
}
export interface ReportSubmissionAttempt { key: string; payload: string; body: CustomerReportCreate; state: 'ready' | 'uncertain' | 'accepted' }
export function prepareReportAttempt(previous: ReportSubmissionAttempt | null, body: CustomerReportCreate, newKey: () => string = () => crypto.randomUUID()): ReportSubmissionAttempt {
  const payload = JSON.stringify(body)
  if (previous?.state === 'accepted') throw new Error('本次报障已登记，请查询登记结果，不要再次创建')
  if (previous?.state === 'uncertain' && previous.payload !== payload) throw new Error('本次提交结果尚不确定，请保留原填写内容并使用原请求重试')
  if (previous?.payload === payload) return previous
  return { key: newKey(), payload, body: { ...body, ...(body.attachment_ids ? { attachment_ids: [...body.attachment_ids] } : {}) }, state: 'ready' }
}
