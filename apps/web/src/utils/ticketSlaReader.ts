import { ApiError } from '../api/client'
import { utcInstantNanos } from './utcInstant'
export interface TicketSlaClock { status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CONFIG_PENDING'; deadline: string | null; stopped_at: string | null; elapsed_ms: number | null; budget_ms: number | null; breached: boolean | null; missing: string | null }
export interface TicketSlaProjection { tenant_id: string; ticket_id: string; tier: 'BASIC' | 'STANDARD' | 'ADVANCED'; priority: 'P1' | 'P2' | 'P3'; observed_at: string; lifecycle_version: number; configuration_reference: string; calculation_policy_reference: string | null; clock_policy: 'EXCLUDE_CONFIRMED_PAUSES' | 'INCLUDE_ALL_ELAPSED_TIME' | null; response: TicketSlaClock; recovery: TicketSlaClock; paused_at: string | null; completed_pauses: { start: string; end: string }[]; reminders_delivered: false; production_approved: false }
const exact = (v: unknown, keys: string[]): v is Record<string,unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v,k)))
const time = (v: unknown): v is string => typeof v === 'string' && utcInstantNanos(v) !== null
const integer = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0 && Number(v) <= 9007199254740990
const invalid = () => new ApiError(200,'INVALID_TICKET_SLA_RESPONSE','SLA范围、版本或口径回读不一致，请刷新工单流转','—')
function clock(v: unknown, observed: string): TicketSlaClock {
  if (!exact(v,['status','deadline','stopped_at','elapsed_ms','budget_ms','breached','missing']) || !['RUNNING','PAUSED','COMPLETED','CONFIG_PENDING'].includes(v.status as string) || v.budget_ms !== null && (!integer(v.budget_ms) || v.budget_ms < 1)) throw invalid()
  if (v.stopped_at !== null && (!time(v.stopped_at) || utcInstantNanos(v.stopped_at)! > utcInstantNanos(observed)!)) throw invalid()
  if (v.status === 'CONFIG_PENDING') { if (v.deadline !== null || v.elapsed_ms !== null || v.breached !== null || typeof v.missing !== 'string' || !v.missing) throw invalid() }
  else {
    if (!integer(v.elapsed_ms) || typeof v.breached !== 'boolean' || v.missing !== null || (v.status === 'PAUSED' ? v.deadline !== null : !time(v.deadline)) || (v.status === 'COMPLETED' ? !time(v.stopped_at) : v.stopped_at !== null)) throw invalid()
  }
  return v as unknown as TicketSlaClock
}
export function readTicketSla(value: unknown, scope: { tenantId: string; ticketId: string; expectedVersion: number }): TicketSlaProjection {
  if (!exact(value,['clock','status','source','production_approved']) || value.status !== 'PROJECTED' || value.source !== 'postgresql' || value.production_approved !== false) throw invalid()
  const v=value.clock
  if (!exact(v,['tenant_id','ticket_id','tier','priority','observed_at','lifecycle_version','configuration_reference','calculation_policy_reference','clock_policy','response','recovery','paused_at','completed_pauses','reminders_delivered','production_approved']) || v.tenant_id !== scope.tenantId || v.ticket_id !== scope.ticketId || !integer(v.lifecycle_version) || v.lifecycle_version < 1 || v.lifecycle_version !== scope.expectedVersion || !['BASIC','STANDARD','ADVANCED'].includes(v.tier as string) || !['P1','P2','P3'].includes(v.priority as string) || !time(v.observed_at) || typeof v.configuration_reference !== 'string' || !/^sha256:[0-9a-f]{64}$/.test(v.configuration_reference) || v.reminders_delivered !== false || v.production_approved !== false || !Array.isArray(v.completed_pauses) || v.completed_pauses.length > 10000) throw invalid()
  if (v.clock_policy === null ? v.calculation_policy_reference !== null : !['EXCLUDE_CONFIRMED_PAUSES','INCLUDE_ALL_ELAPSED_TIME'].includes(v.clock_policy as string) || typeof v.calculation_policy_reference !== 'string' || v.calculation_policy_reference.length > 256 || !/^(?:opening:sha256:[0-9a-f]{64}|binding:[0-9a-f-]{36}:[0-9a-f-]{36}:[1-9][0-9]*)$/.test(v.calculation_policy_reference)) throw invalid()
  if (typeof v.calculation_policy_reference === 'string') {
    if (v.calculation_policy_reference.startsWith('opening:') && v.calculation_policy_reference !== 'opening:' + v.configuration_reference || v.calculation_policy_reference.startsWith('binding:') && !v.calculation_policy_reference.startsWith('binding:' + scope.ticketId + ':')) throw invalid()
  }
  const response=clock(v.response,v.observed_at), recovery=clock(v.recovery,v.observed_at)
  if (response.status === 'PAUSED' || v.paused_at !== null && (!time(v.paused_at) || utcInstantNanos(v.paused_at)! > utcInstantNanos(v.observed_at)!) || recovery.status === 'PAUSED' && v.paused_at === null) throw invalid()
  let end: bigint | null=null
  for (const pause of v.completed_pauses) {
    if (!exact(pause,['start','end']) || !time(pause.start) || !time(pause.end)) throw invalid()
    const start=utcInstantNanos(pause.start)!, next=utcInstantNanos(pause.end)!
    if (start >= next || next > utcInstantNanos(v.observed_at)! || end !== null && start < end) throw invalid(); end=next
  }
  return structuredClone({...v,response,recovery}) as unknown as TicketSlaProjection
}
