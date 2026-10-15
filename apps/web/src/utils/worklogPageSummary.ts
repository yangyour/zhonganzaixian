import type { WorklogEntry, WorklogKind } from './worklogWorkspace'

export const worklogKindLabels: Record<WorklogKind, string> = { REMOTE: '远程处理', ONSITE: '现场处理', INSPECTION: '巡检', COMMUTE: '通勤' }
/** Adds only the explicitly loaded intervals; overlapping entries remain additive, never a payroll total. */
export function summarizeWorklogPage(rows: readonly WorklogEntry[] | null) {
  if (rows === null) return null
  const groups = Object.entries(worklogKindLabels).map(([kind, label]) => ({ kind: kind as WorklogKind, label, count: 0, millis: 0n }))
  let totalMillis = 0n
  for (const row of rows) {
    if (!Number.isSafeInteger(row.elapsed_millis) || row.elapsed_millis < 0) throw new Error('工时记录时长未通过核对')
    const group = groups.find(g => g.kind === row.kind)
    if (!group) throw new Error('工时类型未通过核对')
    const millis = BigInt(row.elapsed_millis); group.count++; group.millis += millis; totalMillis += millis
  }
  return { count: rows.length, ticketCount: new Set(rows.map(r => r.ticket_id)).size, kindCount: groups.filter(g => g.count).length, totalMillis: rows.length ? totalMillis : null, groups }
}
export function formatWorklogDuration(value: bigint | null): string {
  if (value === null) return '本页无记录'
  if (value < 0n) throw new Error('时长不能为负数')
  const hours = value / 3600000n, minutes = value % 3600000n / 60000n, seconds = value % 60000n / 1000n, millis = value % 1000n
  return `${hours} 小时 ${minutes} 分 ${seconds}${millis ? '.' + millis.toString().padStart(3, '0') : ''} 秒`
}
