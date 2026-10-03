import type { ContactFacts, ContactRole, ContactAction } from './customerContactWorkspace'
export const contactRoleLabels: Record<ContactRole, string> = { REPORTER: '报障人', DECISION_MAKER: '决策人', TECHNICAL_CONTACT: '技术对接人' }
export const contactActionLabels: Record<ContactAction, string> = { CREATE: '新建', UPDATE: '修改', ARCHIVE: '归档停用', RESTORE: '恢复' }
export function contactQuietLabel(value: ContactFacts['quiet_window']): string { if (value === null) return '未提供'; if (value.enabled === null) return '已声明，方式未知'; if (!value.enabled) return '明确关闭'; return value.start + ' 至 ' + value.end + '（Asia/Shanghai，' + (value.start! > value.end! ? '跨午夜' : '同日') + '）' }
export function contactPrioritiesLabel(value: ContactFacts['preferred_priorities']): string { return value === null ? '未知' : value.length ? value.join('、') : '明确未选订阅级别' }
