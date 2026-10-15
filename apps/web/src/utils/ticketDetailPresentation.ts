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
    ...ticket.history.map(item => ({ id: `history:${item.version}`, time: item.occurred_at, title: item.action === 'REGISTER' ? '工单发起 · 报障已登记' : '报障照片已追加', text: item.action === 'REGISTER' ? '已保存报障信息，后续处理阶段以正式流程记录为准。' : '已追加照片，原报障信息保持不变。', author: '', kind: 'registration' as const })),
    ...notes.map(note => ({ id: `note:${note.id}`, time: note.created_at, title: '内部处理记录', text: note.text, author: note.actor_id === actorId ? '我 · 仅内部可见' : '同租户内部人员 · 仅内部可见', kind: 'note' as const })),
    ...comments.map(comment => ({ id: `comment:${comment.id}`, time: comment.created_at, title: '客户沟通', text: comment.text, author: `${comment.author_label} · 客户可见`, kind: 'comment' as const })),
  ].sort((a, b) => Date.parse(b.time) - Date.parse(a.time) || a.id.localeCompare(b.id))
}

export function maskedContact(value: string): string {
  return value.length > 7 ? `${value.slice(0, 3)}${'*'.repeat(Math.min(6, value.length - 7))}${value.slice(-4)}` : '••••'
}

/** Text download avoids spreadsheet formulas and active HTML, and identifies its exact visible scope. */
export function ticketDetailText(ticket: RegisteredTicket, tenantName: string, activities: readonly TicketActivity[], worklogs: readonly WorklogEntry[] | null, activityScope: string, worklogScope: string, exportedAt: string): string {
  const summary = summarizeWorklogPage(worklogs)
  return [
    '工单详情 · 当前可见信息', `导出时间：${exportedAt}`, `登记编号：${ticket.receipt_no}`, `标题：${ticket.title}`,
    `客户：${tenantName}`, `创建时间：${displayTime(ticket.created_at)}`, `设备：${ticket.asset_name || (ticket.asset_id ? '已关联，名称未提供' : '未关联')}`,
    `问题类型：${ticket.category}`, `影响范围：${impactScopeLabel[ticket.impact_scope]}`, `紧急程度：${urgencyLabel[ticket.urgency]}`,
    `联系人：${ticket.contact_name}`, `联系方式：${maskedContact(ticket.contact_phone)}`, '正式状态 / 优先级 / SLA：未配置',
    '', '问题描述', ticket.description, '', `处理进展（${activityScope}）`,
    ...activities.flatMap(item => [`${displayTime(item.time)} · ${item.title}${item.author ? ' · ' + item.author : ''}`, item.text, '']),
    `处理工时（${worklogScope}）`, '工时为人工填报的时间区间，相加不扣除重叠；不代表核定工时或费用。',
    ...(worklogs === null ? ['工时未读取或无权限，不能视为零。'] : worklogs.flatMap(item => [
      `${worklogKindLabels[item.kind]} · ${displayTime(item.started_at)} — ${displayTime(item.ended_at)} · ${formatWorklogDuration(BigInt(item.elapsed_millis))}`,
      item.note || '未填写工作描述',
    ])),
    `本页累计：${summary ? formatWorklogDuration(summary.totalMillis) : '未读取'}`,
    '', '备注和评论不改变正式处理阶段；本文件只包含上述读取范围，不是完整工单归档或验收证明。',
  ].join('\n')
}
