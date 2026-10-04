import { ApiError } from '../api/client'
import { utcInstantNanos, utcNotBefore } from './utcInstant'
import { inspectionUuid, validInspectionContext, validInspectionText, type InspectionContext } from './inspectionTemplates'
export type BackupPlanContext = InspectionContext
export type BackupPlanAction = 'CREATE' | 'UPDATE' | 'ARCHIVE' | 'RESTORE'
export interface BackupPlanDefinition { name: string; target_asset_ids: string[]; daily_local_time: string; credential_ref: string | null; requested_enabled: boolean; note: string | null }
interface BackupPlanFacts { name: string; target_count: number; daily_local_time: string; timezone: 'Asia/Shanghai'; credential_ref: string | null; credential_verification: 'NOT_SUPPLIED' | 'NOT_VERIFIED_BY_THIS_MODEL'; requested_enabled: boolean; enabled: false; effective: false; lifecycle_status: 'DRAFT'; approval_status: 'UNVERIFIED'; device_execution_allowed: false; next_execution_at: null; proposed_retry_count: 2; proposed_retry_interval_minutes: 10; retry_policy_source: 'CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE'; source: 'postgresql'; record_source: 'UNPUBLISHED_BACKUP_PLAN_DRAFT' }
export interface BackupPlanSummary extends BackupPlanFacts { id: string; tenant_id: string; version: number; archived: boolean; recorded_by: string; created_at: string; updated_at: string; recorded_at: string }
export interface BackupPlanDetail extends BackupPlanSummary { target_asset_ids: string[]; note: string | null }
export interface BackupPlanRevision extends BackupPlanFacts { plan_id: string; tenant_id: string; version: number; previous_version: number | null; action: BackupPlanAction; archived: boolean; recorded_by: string; recorded_at: string; target_asset_ids: string[]; note: string | null }
export interface BackupPlanPage { tenant_id: string; items: BackupPlanSummary[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; scope: 'CURRENT_TENANT_PADMIN'; source: 'postgresql'; page_size_limit: 20 }
export interface BackupPlanHistory { tenant_id: string; plan_id: string; items: BackupPlanRevision[]; has_more: boolean; next_before_version: number | null; scope: 'CURRENT_TENANT_PADMIN'; source: 'postgresql'; page_size_limit: 20 }
export type BackupPlanPayload = { expected_version: number } & Partial<BackupPlanDefinition>
export const backupPlanMaxVersion = 9_007_199_254_740_990
export const backupPlanUuid = inspectionUuid
export const validBackupPlanContext = validInspectionContext
export const backupPlanBad = () => new ApiError(0, 'INVALID_BACKUP_PLAN_DRAFT', '备份计划草稿范围、版本或未生效标记未通过核对；未知写入请保留原请求重试', '—')
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1 && Number(v) <= backupPlanMaxVersion
const instant = (v: unknown): boolean => { const nanos = utcInstantNanos(v); return nanos !== null && nanos % 1_000_000n === 0n }
const definitionKeys = ['name','target_asset_ids','daily_local_time','credential_ref','requested_enabled','note']
const metadataKeys = ['id','tenant_id','version','archived','recorded_by','created_at','updated_at','recorded_at']
const factKeys = ['name','target_count','daily_local_time','timezone','credential_ref','credential_verification','requested_enabled','enabled','effective','lifecycle_status','approval_status','device_execution_allowed','next_execution_at','proposed_retry_count','proposed_retry_interval_minutes','retry_policy_source','source','record_source']
export function readBackupPlanDefinition(v: unknown): BackupPlanDefinition {
  if (!exact(v, definitionKeys) || !validInspectionText(v.name,100) || !Array.isArray(v.target_asset_ids) || v.target_asset_ids.length < 1 || v.target_asset_ids.length > 1000 || !v.target_asset_ids.every(backupPlanUuid) || new Set(v.target_asset_ids).size !== v.target_asset_ids.length || typeof v.daily_local_time !== 'string' || !/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(v.daily_local_time) || v.credential_ref !== null && !backupPlanUuid(v.credential_ref) || typeof v.requested_enabled !== 'boolean' || !validInspectionText(v.note,4000,true,true,false)) throw backupPlanBad()
  return structuredClone(v) as unknown as BackupPlanDefinition
}
export function backupPlanDefinition(v: BackupPlanDetail | BackupPlanRevision): BackupPlanDefinition { return readBackupPlanDefinition({ name:v.name,target_asset_ids:v.target_asset_ids,daily_local_time:v.daily_local_time,credential_ref:v.credential_ref,requested_enabled:v.requested_enabled,note:v.note }) }
export function readBackupPlanPayload(v: unknown, action: BackupPlanAction): BackupPlanPayload {
  if (!['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(action) || !exact(v,['expected_version',...(['CREATE','UPDATE'].includes(action)?definitionKeys:[])]) || !Number.isSafeInteger(v.expected_version) || (action === 'CREATE' ? v.expected_version !== 0 : !version(v.expected_version) || v.expected_version >= backupPlanMaxVersion)) throw backupPlanBad()
  const { expected_version, ...definition } = v
  return { expected_version: expected_version as number, ...(['CREATE','UPDATE'].includes(action) ? readBackupPlanDefinition(definition) : {}) }
}
function facts(v: Record<string, unknown>) {
  if (!validInspectionText(v.name,100) || !Number.isSafeInteger(v.target_count) || Number(v.target_count)<1 || Number(v.target_count)>1000 || typeof v.daily_local_time !== 'string' || !/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(v.daily_local_time) || v.timezone !== 'Asia/Shanghai' || v.credential_ref !== null && !backupPlanUuid(v.credential_ref) || v.credential_verification !== (v.credential_ref===null?'NOT_SUPPLIED':'NOT_VERIFIED_BY_THIS_MODEL') || typeof v.requested_enabled !== 'boolean' || v.enabled !== false || v.effective !== false || v.lifecycle_status !== 'DRAFT' || v.approval_status !== 'UNVERIFIED' || v.device_execution_allowed !== false || v.next_execution_at !== null || v.proposed_retry_count !== 2 || v.proposed_retry_interval_minutes !== 10 || v.retry_policy_source !== 'CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE' || v.source !== 'postgresql' || v.record_source !== 'UNPUBLISHED_BACKUP_PLAN_DRAFT') throw backupPlanBad()
}
export function readBackupPlanSummary(v: unknown, c: BackupPlanContext): BackupPlanSummary {
  if (!validBackupPlanContext(c) || !exact(v,[...metadataKeys,...factKeys]) || v.tenant_id !== c.tenantId || !backupPlanUuid(v.id) || !backupPlanUuid(v.recorded_by) || !version(v.version) || typeof v.archived !== 'boolean' || v.version===1 && v.archived || !instant(v.created_at) || !instant(v.updated_at) || !instant(v.recorded_at) || !utcNotBefore(v.updated_at,v.created_at) || !utcNotBefore(v.recorded_at,v.created_at)) throw backupPlanBad()
  facts(v); return structuredClone(v) as unknown as BackupPlanSummary
}
export function readBackupPlanDetailShape(v: unknown, c: BackupPlanContext): BackupPlanDetail {
  if (!exact(v,[...metadataKeys,...factKeys,'target_asset_ids','note'])) throw backupPlanBad()
  const { target_asset_ids, note, ...summary }=v, row=readBackupPlanSummary(summary,c), definition=readBackupPlanDefinition({ name:row.name,target_asset_ids,daily_local_time:row.daily_local_time,credential_ref:row.credential_ref,requested_enabled:row.requested_enabled,note })
  if (definition.target_asset_ids.length!==row.target_count) throw backupPlanBad()
  return { ...row,target_asset_ids:definition.target_asset_ids,note:definition.note }
}
/** Detached validated fields only; no cryptographic provenance or device proof is claimed. */
export async function readBackupPlanDetail(v: unknown,c: BackupPlanContext): Promise<BackupPlanDetail> { return readBackupPlanDetailShape(v,c) }
export function readBackupPlanPage(v: unknown,c: BackupPlanContext,before?:{time:string;id:string}): BackupPlanPage {
  if (!validBackupPlanContext(c) || before && (!backupPlanUuid(before.id)||!instant(before.time)) || !exact(v,['tenant_id','items','has_more','next_before_created_at','next_before_id','scope','source','page_size_limit']) || v.tenant_id!==c.tenantId || !Array.isArray(v.items) || v.items.length>15 || typeof v.has_more!=='boolean' || v.scope!=='CURRENT_TENANT_PADMIN' || v.source!=='postgresql' || v.page_size_limit!==20) throw backupPlanBad()
  const items=v.items.map(r=>readBackupPlanSummary(r,c)),seen=new Set<string>();let cursor=before
  for (const row of items) { if (seen.has(row.id) || cursor && (utcInstantNanos(row.created_at)!>utcInstantNanos(cursor.time)! || utcInstantNanos(row.created_at)===utcInstantNanos(cursor.time)&&row.id>=cursor.id)) throw backupPlanBad(); seen.add(row.id);cursor={time:row.created_at,id:row.id} }
  if (v.has_more ? items.length!==15 || v.next_before_id!==cursor?.id || v.next_before_created_at!==cursor?.time : v.next_before_id!==null || v.next_before_created_at!==null) throw backupPlanBad()
  return structuredClone({...v,items}) as unknown as BackupPlanPage
}
export async function readBackupPlanHistory(v: unknown,c: BackupPlanContext,id:string,before?:number): Promise<BackupPlanHistory> {
  if (!validBackupPlanContext(c) || !backupPlanUuid(id) || before!==undefined&&!version(before) || !exact(v,['tenant_id','plan_id','items','has_more','next_before_version','scope','source','page_size_limit']) || v.tenant_id!==c.tenantId || v.plan_id!==id || !Array.isArray(v.items) || v.items.length>15 || before===undefined&&v.items.length===0 || typeof v.has_more!=='boolean' || v.scope!=='CURRENT_TENANT_PADMIN' || v.source!=='postgresql' || v.page_size_limit!==20) throw backupPlanBad()
  const snapshot=structuredClone(v);let previous=before,time:string|undefined,newer:BackupPlanRevision|undefined
  for (const raw of snapshot.items as unknown[]) {
    if (!exact(raw,['plan_id','tenant_id','version','previous_version','action','archived','recorded_by','recorded_at',...factKeys,'target_asset_ids','note']) || raw.plan_id!==id || raw.tenant_id!==c.tenantId || !version(raw.version) || !backupPlanUuid(raw.recorded_by) || !instant(raw.recorded_at) || !['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(raw.action as string) || raw.archived!==(raw.action==='ARCHIVE') || (raw.version===1?raw.action!=='CREATE':raw.action==='CREATE') || raw.previous_version!==(raw.version===1?null:raw.version-1) || previous!==undefined&&raw.version!==previous-1 || time&&!utcNotBefore(time,raw.recorded_at)) throw backupPlanBad()
    facts(raw);const row=raw as unknown as BackupPlanRevision;if (backupPlanDefinition(row).target_asset_ids.length!==row.target_count) throw backupPlanBad();if(newer && ((newer.action==='RESTORE')!==row.archived || ['ARCHIVE','RESTORE'].includes(newer.action)&&JSON.stringify(backupPlanDefinition(newer))!==JSON.stringify(backupPlanDefinition(row)))) throw backupPlanBad();newer=row;previous=row.version;time=row.recorded_at
  }
  if (snapshot.has_more ? (snapshot.items as unknown[]).length!==15 || previous===undefined || previous<=1 || snapshot.next_before_version!==previous : snapshot.next_before_version!==null || (snapshot.items as unknown[]).length>0&&previous!==1 || before!==undefined&&before>1&&(snapshot.items as unknown[]).length===0) throw backupPlanBad()
  return snapshot as unknown as BackupPlanHistory
}
