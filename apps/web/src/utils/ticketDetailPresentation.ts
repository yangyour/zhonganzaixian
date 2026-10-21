import type { RegisteredTicket } from '../api/types'
import type { InternalNote } from './internalNoteWorkspace'
import type { TicketComment } from './ticketCommentWorkspace'
import type { WorklogEntry } from './worklogWorkspace'
import { displayTime } from './display'
import { impactScopeLabel, urgencyLabel } from './ticket'
import { formatWorklogDuration, summarizeWorklogPage, worklogKindLabels } from './worklogPageSummary'

export interface TicketActivity { id: string; time: string; title: string; text: string; author: string; kind: 'registration' | 'note' | 'comment' }
/** These are recorded facts, never inferred workflow transitions or SLA warnings. */
export function ticketActivities(ticket: RegisteredTicket, notes: readonly InternalNote[], comments: readonly TicketComment[], actorId: string): TicketActivity[] {
  return [
    ...ticket.history.map(item => ({ id: `history:${item.version}`, time: item.occurred_at, title: item.action === 'ADD_ATTACHMENTS' ? '报障照片已追加' : item.action === 'REGISTER' ? '工单发起 · 报障已登记' : `工单发起 · ${ticketRegistrationOrigin(ticket)}`, text: item.action === 'ADD_ATTACHMENTS' ? '已追加照片，原报障信息保持不变。' : '已保存登记记录，后续处理阶段与通知送达以实际流程记录为准。', author: '', kind: 'registration' as const })),
    ...notes.map(note => ({ id: `note:${note.id}`, time: note.created_at, title: '内部处理记录', text: note.text, author: note.actor_id === actorId ? '我 · 仅内部可见' : '同租户内部人员 · 仅内部可见', kind: 'note' as const })),
    ...comments.map(comment => ({ id: `comment:${comment.id}`, time: comment.created_at, title: '客户沟通', text: comment.text, author: `${comment.author_label} · 客户可见`, kind: 'comment' as const })),
  ].sort((a, b) => Date.parse(b.time) - Date.parse(a.time) || a.id.localeCompare(b.id))
}

export function ticketRegistrationOrigin(ticket: RegisteredTicket): string {
  return ticket.history[0]?.action === 'MONITORING_REGISTER' ? '监控事件登记' : ticket.history[0]?.action === 'LEGACY_MONITORING_REGISTER' ? '历史监控事件登记（兼容投影）' : '客户报障已登记'
}

export function maskedContact(value: string): string {
  if (!value.trim()) return '未提供'
  return value.length > 7 ? `${value.slice(0, 3)}${'*'.repeat(Math.min(6, value.length - 7))}${value.slice(-4)}` : '••••'
}

/** Text download avoids spreadsheet formulas and active HTML, and identifies its exact visible scope. */
export function ticketDetailText(ticket: RegisteredTicket, tenantName: string, activities: readonly TicketActivity[], worklogs: readonly WorklogEntry[] | null, activityScope: string, worklogScope: string, exportedAt: string): string {
  const summary = summarizeWorklogPage(worklogs)
  return [
    '工单详情 · 当前可见信息', `导出时间：${exportedAt}`, `登记编号：${ticket.receipt_no}`, `标题：${ticket.title}`,
    `客户：${tenantName}`, `创建时间：${displayTime(ticket.created_at)}`, `设备：${ticket.asset_name || (ticket.asset_id ? '已关联（名称暂未提供）' : '未关联')}`,
    `问题类型：${ticket.category}`, `影响范围：${impactScopeLabel[ticket.impact_scope]}`, `紧急程度：${urgencyLabel[ticket.urgency]}（人工登记）`,
    `登记来源：${ticketRegistrationOrigin(ticket)}`, `联系人：${ticket.contact_name}`, `联系方式：${maskedContact(ticket.contact_phone)}`, '正式状态 / 优先级 / SLA：暂未提供',
    '', '问题描述', ticket.description, '', `处理进展（${activityScope}）`,
    ...activities.flatMap(item => [`${displayTime(item.time)} · ${item.title}${item.author ? ' · ' + item.author : ''}`, item.text, '']),
    `处理工时（${worklogScope}）`, '记录工程师实际作业起止时间（跨日记录按原始时段保存，不自动拆分；计费核算以商务结算规则为准）。',
    ...(worklogs === null ? ['工时未读取或无权限，不能视为零。'] : worklogs.flatMap(item => [
      `${worklogKindLabels[item.kind]} · ${displayTime(item.started_at)} — ${displayTime(item.ended_at)} · ${formatWorklogDuration(BigInt(item.elapsed_millis))}`,
      item.note || '未填写工作描述',
    ])),
    `本页累计：${summary ? formatWorklogDuration(summary.totalMillis) : '未读取'}`,
    '', '当前已完成故障受理入库；工单分派流转与 SLA 考核计时将在 ITSM 流程启用后自动关联。本文件仅包含上述读取范围，不是完整工单归档或验收证明。',
  ].join('\n')
}
