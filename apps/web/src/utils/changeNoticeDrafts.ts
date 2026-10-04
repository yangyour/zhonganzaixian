import { ApiError } from '../api/client'
import { utcInstantNanos, utcNotBefore } from './utcInstant'
import { inspectionUuid, validInspectionContext, validInspectionText, type InspectionContext } from './inspectionTemplates'
export type ChangeNoticeContext = InspectionContext
export type ChangeNoticeAction = 'CREATE' | 'UPDATE' | 'ARCHIVE' | 'RESTORE'
export interface ChangeNoticeDefinition { change_summary:string; asset_ids:string[]; planned_start_at:string; planned_end_at:string|null; ticket_id:string|null }
interface ChangeNoticeFacts { applicant_id:string; ticket_id:string|null; asset_count:number; planned_start_at:string; planned_end_at:string|null; content_sha256:string; timezone:'Asia/Shanghai'; effective:false; lifecycle_status:'DRAFT'; approval_status:null; approval_workflow_status:'CONFIG_PENDING'; matching_policy_status:'CONFIG_PENDING'; automatic_p3_decision:null; approval_evidence_verified:false; device_execution_allowed:false; alert_suppression_allowed:false; source:'postgresql'; record_source:'UNREVIEWED_CHANGE_NOTICE_DRAFT' }
export interface ChangeNoticeSummary extends ChangeNoticeFacts { id: string; tenant_id: string; version: number; archived: boolean; recorded_by: string; created_at: string; updated_at: string; recorded_at: string }
export interface ChangeNoticeDetail extends ChangeNoticeSummary { asset_ids:string[]; change_summary:string }
export interface ChangeNoticeRevision extends ChangeNoticeFacts { notice_id: string; tenant_id: string; version: number; previous_version: number | null; action: ChangeNoticeAction; archived: boolean; recorded_by: string; recorded_at: string; asset_ids:string[]; change_summary:string }
export interface ChangeNoticePage { tenant_id: string; items: ChangeNoticeSummary[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; scope: 'CURRENT_TENANT_PADMIN'; source: 'postgresql'; page_size_limit: 20 }
export interface ChangeNoticeHistory { tenant_id: string; notice_id: string; items: ChangeNoticeRevision[]; has_more: boolean; next_before_version: number | null; scope: 'CURRENT_TENANT_PADMIN'; source: 'postgresql'; page_size_limit: 20 }
export type ChangeNoticePayload = { expected_version: number } & Partial<ChangeNoticeDefinition>
export const changeNoticeMaxVersion = 9_007_199_254_740_990
export const changeNoticeUuid = inspectionUuid
export const validChangeNoticeContext = validInspectionContext
export const changeNoticeBad = () => new ApiError(0, 'INVALID_CHANGE_NOTICE_DRAFT', '变更报备草稿范围、版本或未生效标记未通过核对；未知写入请保留原请求重试', '—')
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1 && Number(v) <= changeNoticeMaxVersion
const instant = (v: unknown): boolean => { const nanos = utcInstantNanos(v); return nanos !== null && nanos % 1_000_000n === 0n }
const definitionKeys = ['change_summary','asset_ids','planned_start_at','planned_end_at','ticket_id']
const metadataKeys = ['id','tenant_id','version','archived','recorded_by','created_at','updated_at','recorded_at']
const factKeys = ['applicant_id','ticket_id','asset_count','planned_start_at','planned_end_at','content_sha256','timezone','effective','lifecycle_status','approval_status','approval_workflow_status','matching_policy_status','automatic_p3_decision','approval_evidence_verified','device_execution_allowed','alert_suppression_allowed','source','record_source']
const shanghaiYear=new Intl.DateTimeFormat('en-US-u-ca-gregory-nu-latn',{timeZone:'Asia/Shanghai',year:'numeric'})
function plannedTime(value:unknown):value is string {if(typeof value!=='string'||!/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(value))return false;const n=utcInstantNanos(value);if(n===null||n%1_000_000n!==0n)return false;try{const year=Number(shanghaiYear.formatToParts(new Date(Number(n/1_000_000n))).find(p=>p.type==='year')?.value);return year>=1&&year<=9999}catch{return false}}
function window(start:unknown,end:unknown){return plannedTime(start)&&(end===null||plannedTime(end)&&utcInstantNanos(end)!>utcInstantNanos(start)!)}
export function readChangeNoticeDefinition(v:unknown):ChangeNoticeDefinition{
  if(!exact(v,definitionKeys)||!validInspectionText(v.change_summary,2000,false,true)||!Array.isArray(v.asset_ids)||v.asset_ids.length<1||v.asset_ids.length>1000||!v.asset_ids.every(changeNoticeUuid)||new Set(v.asset_ids).size!==v.asset_ids.length||!window(v.planned_start_at,v.planned_end_at)||v.ticket_id!==null&&!changeNoticeUuid(v.ticket_id))throw changeNoticeBad()
  return structuredClone(v) as unknown as ChangeNoticeDefinition
}
export function changeNoticeDefinition(v:ChangeNoticeDetail|ChangeNoticeRevision):ChangeNoticeDefinition{return readChangeNoticeDefinition({change_summary:v.change_summary,asset_ids:v.asset_ids,planned_start_at:v.planned_start_at,planned_end_at:v.planned_end_at,ticket_id:v.ticket_id})}
/** Exact Java/PG framing. A content digest is not approval, authenticity or device proof. */
export function changeNoticeBytes(tenant:string,applicant:string,value:unknown):Uint8Array{
  if(!changeNoticeUuid(tenant)||!changeNoticeUuid(applicant))throw changeNoticeBad();const d=readChangeNoticeDefinition(value),parts:Uint8Array[]=[]
  const i4=(n:number)=>{const b=new Uint8Array(4);new DataView(b.buffer).setInt32(0,n,false);return b},i8=(n:bigint)=>{const b=new Uint8Array(8);new DataView(b.buffer).setBigInt64(0,n,false);return b}
  const field=(v:string|null)=>{if(v===null){parts.push(i4(-1));return}const b=new TextEncoder().encode(v);parts.push(i4(b.length),b)}
  field('MOP_CHANGE_NOTICE_DRAFT_V1');field(tenant);field(applicant);field(d.ticket_id);field(d.change_summary);parts.push(i8(utcInstantNanos(d.planned_start_at)!/1_000_000n));if(d.planned_end_at===null)parts.push(i4(-1));else parts.push(i4(8),i8(utcInstantNanos(d.planned_end_at)!/1_000_000n));parts.push(i4(d.asset_ids.length));for(const id of d.asset_ids)field(id)
  const result=new Uint8Array(parts.reduce((n,p)=>n+p.length,0));let offset=0;for(const p of parts){result.set(p,offset);offset+=p.length}return result
}
export async function changeNoticeFingerprint(tenant:string,applicant:string,value:unknown):Promise<string>{const bytes=changeNoticeBytes(tenant,applicant,value),hash=await crypto.subtle.digest('SHA-256',bytes as Uint8Array<ArrayBuffer>);return Array.from(new Uint8Array(hash)).map(n=>n.toString(16).padStart(2,'0')).join('')}
export function readChangeNoticePayload(v: unknown, action: ChangeNoticeAction): ChangeNoticePayload {
  if (!['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(action) || !exact(v,['expected_version',...(['CREATE','UPDATE'].includes(action)?definitionKeys:[])]) || !Number.isSafeInteger(v.expected_version) || (action === 'CREATE' ? v.expected_version !== 0 : !version(v.expected_version) || v.expected_version >= changeNoticeMaxVersion)) throw changeNoticeBad()
  const { expected_version, ...definition } = v
  return { expected_version: expected_version as number, ...(['CREATE','UPDATE'].includes(action) ? readChangeNoticeDefinition(definition) : {}) }
}
function facts(v:Record<string,unknown>){if(!changeNoticeUuid(v.applicant_id)||v.ticket_id!==null&&!changeNoticeUuid(v.ticket_id)||!Number.isSafeInteger(v.asset_count)||Number(v.asset_count)<1||Number(v.asset_count)>1000||!window(v.planned_start_at,v.planned_end_at)||typeof v.content_sha256!=='string'||!/^[0-9a-f]{64}$/.test(v.content_sha256)||v.timezone!=='Asia/Shanghai'||v.effective!==false||v.lifecycle_status!=='DRAFT'||v.approval_status!==null||v.automatic_p3_decision!==null||v.approval_workflow_status!=='CONFIG_PENDING'||v.matching_policy_status!=='CONFIG_PENDING'||v.approval_evidence_verified!==false||v.device_execution_allowed!==false||v.alert_suppression_allowed!==false||v.source!=='postgresql'||v.record_source!=='UNREVIEWED_CHANGE_NOTICE_DRAFT')throw changeNoticeBad()}
export function readChangeNoticeSummary(v: unknown, c: ChangeNoticeContext): ChangeNoticeSummary {
  if (!validChangeNoticeContext(c) || !exact(v,[...metadataKeys,...factKeys]) || v.tenant_id !== c.tenantId || !changeNoticeUuid(v.id) || !changeNoticeUuid(v.recorded_by) || !version(v.version) || typeof v.archived !== 'boolean' || v.version===1 && (v.archived||v.applicant_id!==v.recorded_by) || !instant(v.created_at) || !instant(v.updated_at) || !instant(v.recorded_at) || !utcNotBefore(v.updated_at,v.created_at) || !utcNotBefore(v.recorded_at,v.created_at)) throw changeNoticeBad()
  facts(v); return structuredClone(v) as unknown as ChangeNoticeSummary
}
export function readChangeNoticeDetailShape(v:unknown,c:ChangeNoticeContext):ChangeNoticeDetail{
  if(!exact(v,[...metadataKeys,...factKeys,'asset_ids','change_summary']))throw changeNoticeBad()
  const {asset_ids,change_summary,...summary}=v,row=readChangeNoticeSummary(summary,c),definition=readChangeNoticeDefinition({change_summary,asset_ids,planned_start_at:row.planned_start_at,planned_end_at:row.planned_end_at,ticket_id:row.ticket_id})
  if(definition.asset_ids.length!==row.asset_count||row.version===1&&row.recorded_by!==row.applicant_id)throw changeNoticeBad()
  return {...row,asset_ids:definition.asset_ids,change_summary:definition.change_summary}
}
export async function readChangeNoticeDetail(v:unknown,c:ChangeNoticeContext):Promise<ChangeNoticeDetail>{const row=readChangeNoticeDetailShape(v,c);if(await changeNoticeFingerprint(row.tenant_id,row.applicant_id,changeNoticeDefinition(row))!==row.content_sha256)throw changeNoticeBad();return row}
export function readChangeNoticePage(v: unknown,c: ChangeNoticeContext,before?:{time:string;id:string}): ChangeNoticePage {
  if (!validChangeNoticeContext(c) || before && (!changeNoticeUuid(before.id)||!instant(before.time)) || !exact(v,['tenant_id','items','has_more','next_before_created_at','next_before_id','scope','source','page_size_limit']) || v.tenant_id!==c.tenantId || !Array.isArray(v.items) || v.items.length>15 || typeof v.has_more!=='boolean' || v.scope!=='CURRENT_TENANT_PADMIN' || v.source!=='postgresql' || v.page_size_limit!==20) throw changeNoticeBad()
  const items=v.items.map(r=>readChangeNoticeSummary(r,c)),seen=new Set<string>();let cursor=before
  for (const row of items) { if (seen.has(row.id) || cursor && (utcInstantNanos(row.created_at)!>utcInstantNanos(cursor.time)! || utcInstantNanos(row.created_at)===utcInstantNanos(cursor.time)&&row.id>=cursor.id)) throw changeNoticeBad(); seen.add(row.id);cursor={time:row.created_at,id:row.id} }
  if (v.has_more ? items.length!==15 || v.next_before_id!==cursor?.id || v.next_before_created_at!==cursor?.time : v.next_before_id!==null || v.next_before_created_at!==null) throw changeNoticeBad()
  return structuredClone({...v,items}) as unknown as ChangeNoticePage
}
export async function readChangeNoticeHistory(v: unknown,c: ChangeNoticeContext,id:string,before?:number): Promise<ChangeNoticeHistory> {
  if (!validChangeNoticeContext(c) || !changeNoticeUuid(id) || before!==undefined&&!version(before) || !exact(v,['tenant_id','notice_id','items','has_more','next_before_version','scope','source','page_size_limit']) || v.tenant_id!==c.tenantId || v.notice_id!==id || !Array.isArray(v.items) || v.items.length>15 || before===undefined&&v.items.length===0 || typeof v.has_more!=='boolean' || v.scope!=='CURRENT_TENANT_PADMIN' || v.source!=='postgresql' || v.page_size_limit!==20) throw changeNoticeBad()
  const snapshot=structuredClone(v);let previous=before,time:string|undefined,newer:ChangeNoticeRevision|undefined
  for (const raw of snapshot.items as unknown[]) {
    if (!exact(raw,['notice_id','tenant_id','version','previous_version','action','archived','recorded_by','recorded_at',...factKeys,'asset_ids','change_summary']) || raw.notice_id!==id || raw.tenant_id!==c.tenantId || !version(raw.version) || !changeNoticeUuid(raw.recorded_by) || !instant(raw.recorded_at) || !['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(raw.action as string) || raw.archived!==(raw.action==='ARCHIVE') || (raw.version===1?raw.action!=='CREATE':raw.action==='CREATE') || raw.previous_version!==(raw.version===1?null:raw.version-1) || previous!==undefined&&raw.version!==previous-1 || time&&!utcNotBefore(time,raw.recorded_at)) throw changeNoticeBad()
    facts(raw);const row=raw as unknown as ChangeNoticeRevision;if (changeNoticeDefinition(row).asset_ids.length!==row.asset_count) throw changeNoticeBad();if(row.version===1&&row.recorded_by!==row.applicant_id||await changeNoticeFingerprint(row.tenant_id,row.applicant_id,changeNoticeDefinition(row))!==row.content_sha256)throw changeNoticeBad();if(newer && (newer.applicant_id!==row.applicant_id||(newer.action==='RESTORE')!==row.archived || ['ARCHIVE','RESTORE'].includes(newer.action)&&newer.content_sha256!==row.content_sha256)) throw changeNoticeBad();newer=row;previous=row.version;time=row.recorded_at
  }
  if (snapshot.has_more ? (snapshot.items as unknown[]).length!==15 || previous===undefined || previous<=1 || snapshot.next_before_version!==previous : snapshot.next_before_version!==null || (snapshot.items as unknown[]).length>0&&previous!==1 || before!==undefined&&before>1&&(snapshot.items as unknown[]).length===0) throw changeNoticeBad()
  return snapshot as unknown as ChangeNoticeHistory
}
