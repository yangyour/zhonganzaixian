import { ApiError } from '../api/client'
import { inspectionMaxVersion, inspectionUuid, validInspectionContext, validInspectionText, type InspectionContext } from './inspectionTemplates'
import { inspectionSourceBytes, readInspectionSourceRevision, readInspectionSourceShape, type InspectionSourceRevision } from './inspectionSourceRevision'
import { worksheetTime } from './inspectionWorksheets'
export type SourceCollectionContext = InspectionContext
export interface SourceCollectionSelection { worksheet_id: string; version: number; expected_source_sha256: string }
export interface SourceCollectionPayload { expected_version: 0; label: string; sources: SourceCollectionSelection[] }
export interface SourceCollectionSummary {
  id: string; tenant_id: string; created_by: string; recorded_at: string; version: 1; label: string; source_count: number
  document_format: 'MOP_INSPECTION_SOURCE_DOCUMENT_V1'; document_sha256: string; source_manifest_format: 'MOP_INSPECTION_SOURCE_MANIFEST_V1'; source_manifest_sha256: string
  projection: 'METADATA_ONLY'; record_source: 'EXPLICIT_IMMUTABLE_SOURCE_COLLECTION'; verification_status: 'SELF_REPORTED_UNVERIFIED'; scope: 'CURRENT_TENANT_PADMIN'; source: 'postgresql'; evidence_access_granted: false
  period_time_zone: null; quarter_selection_rule: null; archived_source_policy: null; metric_version: null; report_template_version: null; tenant_asset_population: null; generation_status: 'CONFIG_PENDING'; formal_report_generatable: false; report_published: false
}
export interface SourceCollectionReference { worksheet_id: string; version: number; source_snapshot_sha256: string; content_sha256: string }
export interface SourceCollectionDiagnostics { distinct_selected_assets: number; unknown_inspection_time: SourceCollectionReference[]; incompletely_entered: SourceCollectionReference[]; archived_selected_revisions: SourceCollectionReference[]; assets_with_multiple_sources: {asset_id:string;sources:SourceCollectionReference[]}[] }
export interface SourceCollectionDetail extends Omit<SourceCollectionSummary,'projection'> { projection:'FULL_SOURCES'; document_integrity:'VERIFIED_SERVER_SIDE'; sources:InspectionSourceRevision[]; diagnostics:SourceCollectionDiagnostics }
export interface SourceCollectionPage { tenant_id:string; items:SourceCollectionSummary[]; has_more:boolean; next_before_recorded_at:string|null; next_before_id:string|null; page_size_limit:20; scope:'CURRENT_TENANT_PADMIN'; source:'postgresql' }
export interface SourceCollectionCursor { time:string; id:string }
export const sourceCollectionBad=()=>new ApiError(0,'INVALID_INSPECTION_SOURCE_COLLECTION','来源集合范围、明确修订、完整内容或摘要核对失败；未知写入保留原请求，不自动重选来源','—')
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{64}$/.test(v)
const version=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=1&&Number(v)<=inspectionMaxVersion
const summaryKeys=['id','tenant_id','created_by','recorded_at','version','label','source_count','document_format','document_sha256','source_manifest_format','source_manifest_sha256','projection','record_source','verification_status','scope','source','evidence_access_granted','period_time_zone','quarter_selection_rule','archived_source_policy','metric_version','report_template_version','tenant_asset_population','generation_status','formal_report_generatable','report_published']
const unset=['period_time_zone','quarter_selection_rule','archived_source_policy','metric_version','report_template_version','tenant_asset_population']
const key=(s:{worksheet_id:string;version:number})=>s.worksheet_id+'/'+s.version
const order=(a:{worksheet_id:string;version:number},b:{worksheet_id:string;version:number})=>a.worksheet_id===b.worksheet_id?a.version-b.version:a.worksheet_id<b.worksheet_id?-1:1
const i4=(n:number)=>{const b=new Uint8Array(4);new DataView(b.buffer).setInt32(0,n,false);return b}
const i8=(n:number)=>{const b=new Uint8Array(8);new DataView(b.buffer).setBigInt64(0,BigInt(n),false);return b}
const bytes=(s:string)=>new TextEncoder().encode(s)
const join=(parts:Uint8Array[])=>{const b=new Uint8Array(parts.reduce((n,p)=>n+p.length,0));let at=0;for(const p of parts){b.set(p,at);at+=p.length}return b}
const field=(s:string)=>{const b=bytes(s);return join([i4(b.length),b])}
export async function sourceCollectionSha(value:Uint8Array):Promise<string>{const digest=await crypto.subtle.digest('SHA-256',value as Uint8Array<ArrayBuffer>);return Array.from(new Uint8Array(digest)).map(n=>n.toString(16).padStart(2,'0')).join('')}
export function readSourceCollectionPayload(raw:unknown):SourceCollectionPayload{
  if(!exact(raw,['expected_version','label','sources'])||raw.expected_version!==0||!validInspectionText(raw.label,200)||!Array.isArray(raw.sources)||raw.sources.length>1000)throw sourceCollectionBad()
  const seen=new Set<string>(),sources:SourceCollectionSelection[]=[]
  for(const s of raw.sources){if(!exact(s,['worksheet_id','version','expected_source_sha256'])||!inspectionUuid(s.worksheet_id)||!version(s.version)||!hash(s.expected_source_sha256)||seen.has(key(s as unknown as SourceCollectionSelection)))throw sourceCollectionBad();seen.add(key(s as unknown as SourceCollectionSelection));sources.push({worksheet_id:s.worksheet_id,version:s.version,expected_source_sha256:s.expected_source_sha256})}
  const result:SourceCollectionPayload={expected_version:0,label:raw.label as string,sources};if(bytes(JSON.stringify(result)).length>262144)throw sourceCollectionBad();return result
}
export function sourceCollectionManifestBytes(tenantId:string,selections:SourceCollectionSelection[]):Uint8Array{
  if(!inspectionUuid(tenantId))throw sourceCollectionBad();const selected=readSourceCollectionPayload({expected_version:0,label:'manifest',sources:selections}).sources.sort(order)
  return join([field('MOP_INSPECTION_SOURCE_MANIFEST_V1'),field(tenantId),i4(selected.length),...selected.flatMap(s=>[field(s.worksheet_id),i8(s.version),field(s.expected_source_sha256)])])
}
export function readSourceCollectionSummary(raw:unknown,context:SourceCollectionContext):SourceCollectionSummary{
  if(!validInspectionContext(context)||!exact(raw,summaryKeys)||!inspectionUuid(raw.id)||raw.tenant_id!==context.tenantId||!inspectionUuid(raw.created_by)||worksheetTime(raw.recorded_at)===null||raw.version!==1||!validInspectionText(raw.label,200)||!Number.isSafeInteger(raw.source_count)||Number(raw.source_count)<0||Number(raw.source_count)>1000||raw.document_format!=='MOP_INSPECTION_SOURCE_DOCUMENT_V1'||raw.source_manifest_format!=='MOP_INSPECTION_SOURCE_MANIFEST_V1'||!hash(raw.document_sha256)||!hash(raw.source_manifest_sha256)||raw.projection!=='METADATA_ONLY'||raw.record_source!=='EXPLICIT_IMMUTABLE_SOURCE_COLLECTION'||raw.verification_status!=='SELF_REPORTED_UNVERIFIED'||raw.scope!=='CURRENT_TENANT_PADMIN'||raw.source!=='postgresql'||raw.evidence_access_granted!==false||unset.some(k=>raw[k]!==null)||raw.generation_status!=='CONFIG_PENDING'||raw.formal_report_generatable!==false||raw.report_published!==false)throw sourceCollectionBad()
  return structuredClone(raw) as unknown as SourceCollectionSummary
}
/** Metadata pages do not fetch or attest to the full document body. */
export function readSourceCollectionPage(raw:unknown,context:SourceCollectionContext,before?:SourceCollectionCursor):SourceCollectionPage{
  if(!validInspectionContext(context)||!exact(raw,['tenant_id','items','has_more','next_before_recorded_at','next_before_id','page_size_limit','scope','source'])||raw.tenant_id!==context.tenantId||!Array.isArray(raw.items)||raw.items.length>20||typeof raw.has_more!=='boolean'||raw.page_size_limit!==20||raw.scope!=='CURRENT_TENANT_PADMIN'||raw.source!=='postgresql')throw sourceCollectionBad()
  if(before&&(!inspectionUuid(before.id)||worksheetTime(before.time)===null))throw sourceCollectionBad()
  const items=raw.items.map(v=>readSourceCollectionSummary(v,context)),seen=new Set<string>();let previous=before
  for(const item of items){const time=worksheetTime(item.recorded_at)!;if(seen.has(item.id)||previous&&(time>worksheetTime(previous.time)!||time===worksheetTime(previous.time)&&item.id>=previous.id))throw sourceCollectionBad();seen.add(item.id);previous={time:item.recorded_at,id:item.id}}
  const last=items.at(-1)
  if(raw.has_more?items.length===0||!last||raw.next_before_recorded_at!==last.recorded_at||raw.next_before_id!==last.id:raw.next_before_recorded_at!==null||raw.next_before_id!==null)throw sourceCollectionBad()
  return structuredClone({...raw,items}) as unknown as SourceCollectionPage
}
const reference=(s:InspectionSourceRevision):SourceCollectionReference=>({worksheet_id:s.worksheet_id,version:s.version,source_snapshot_sha256:s.source_snapshot_sha256,content_sha256:s.content_sha256})
export function sourceCollectionDiagnostics(sources:InspectionSourceRevision[]):SourceCollectionDiagnostics{
  const groups=new Map<string,SourceCollectionReference[]>();for(const s of sources){const group=groups.get(s.asset_id)??[];group.push(reference(s));groups.set(s.asset_id,group)}
  return {distinct_selected_assets:groups.size,unknown_inspection_time:sources.filter(s=>s.reported_performed_at===null).map(reference),incompletely_entered:sources.filter(s=>!s.summary.all_results_entered).map(reference),archived_selected_revisions:sources.filter(s=>s.archived).map(reference),assets_with_multiple_sources:[...groups.entries()].filter(([,refs])=>refs.length>1).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([asset_id,sources])=>({asset_id,sources}))}
}
function readRefs(raw:unknown):SourceCollectionReference[]{if(!Array.isArray(raw)||raw.length>1000)throw sourceCollectionBad();return raw.map(r=>{if(!exact(r,['worksheet_id','version','source_snapshot_sha256','content_sha256'])||!inspectionUuid(r.worksheet_id)||!version(r.version)||!hash(r.source_snapshot_sha256)||!hash(r.content_sha256))throw sourceCollectionBad();return {worksheet_id:r.worksheet_id,version:r.version,source_snapshot_sha256:r.source_snapshot_sha256,content_sha256:r.content_sha256}})}
function readDiagnostics(raw:unknown,sources:InspectionSourceRevision[]):SourceCollectionDiagnostics{
  if(!exact(raw,['distinct_selected_assets','unknown_inspection_time','incompletely_entered','archived_selected_revisions','assets_with_multiple_sources'])||!Number.isSafeInteger(raw.distinct_selected_assets)||!Array.isArray(raw.assets_with_multiple_sources)||raw.assets_with_multiple_sources.length>500)throw sourceCollectionBad()
  const result:SourceCollectionDiagnostics={distinct_selected_assets:raw.distinct_selected_assets as number,unknown_inspection_time:readRefs(raw.unknown_inspection_time),incompletely_entered:readRefs(raw.incompletely_entered),archived_selected_revisions:readRefs(raw.archived_selected_revisions),assets_with_multiple_sources:raw.assets_with_multiple_sources.map(v=>{if(!exact(v,['asset_id','sources'])||!inspectionUuid(v.asset_id))throw sourceCollectionBad();return {asset_id:v.asset_id,sources:readRefs(v.sources)}})}
  if(JSON.stringify(result)!==JSON.stringify(sourceCollectionDiagnostics(sources)))throw sourceCollectionBad();return result
}
/** Java Instant renders integral seconds without .000, and nonzero milliseconds with exactly three digits. */
const instant=(value:string)=>{const nanos=worksheetTime(value);if(nanos===null)throw sourceCollectionBad();return new Date(Number(nanos/1_000_000n)).toISOString().replace('.000Z','Z')}
export function sourceCollectionDocumentBytes(tenantId:string,manifestHash:string,sources:InspectionSourceRevision[]):Uint8Array{
  if(!inspectionUuid(tenantId)||!hash(manifestHash)||sources.length>1000)throw sourceCollectionBad()
  const document={format:'MOP_INSPECTION_SOURCE_DOCUMENT_V1',tenant_id:tenantId,manifest_sha256:manifestHash,sources:sources.map(s=>({worksheet_id:s.worksheet_id,version:s.version,created_by:s.created_by,created_at:instant(s.created_at),recorded_by:s.recorded_by,recorded_at:instant(s.recorded_at),action:s.action,archived:s.archived,source_sha256:s.source_snapshot_sha256,content:{asset_id:s.asset_id,asset_name:s.asset_name,reported_performed_at:s.reported_performed_at===null?null:instant(s.reported_performed_at),content_sha256:s.content_sha256,template:{id:s.template_id,version:s.template_version,device_type:s.template_device_type,name:s.template_name,content_sha256:s.template_content_sha256,items:s.template_items.map(i=>({key:i.key,section:i.section,label:i.label,instruction:i.instruction}))},observations:s.template_items.flatMap(i=>{const o=s.observations.find(o=>o.key===i.key);return o?[{key:o.key,result:o.result,note:o.note,evidence_ids:[]}]:[]})}}))}
  const result=bytes(JSON.stringify(document));if(result.length>32*1024*1024)throw sourceCollectionBad();return result
}
/** Clone and bound all sources before the first asynchronous digest; no caller-owned object survives verification. */
export function readSourceCollectionDetailShape(raw:unknown,context:SourceCollectionContext,id:string):SourceCollectionDetail{
  if(!inspectionUuid(id)||!exact(raw,[...summaryKeys,'document_integrity','sources','diagnostics'])||raw.id!==id||raw.projection!=='FULL_SOURCES'||raw.document_integrity!=='VERIFIED_SERVER_SIDE'||!Array.isArray(raw.sources)||raw.sources.length>1000)throw sourceCollectionBad()
  const {sources:input,diagnostics:inputDiagnostics,document_integrity:_integrity,...metadata}=raw,summary=readSourceCollectionSummary({...metadata,projection:'METADATA_ONLY'},context)
  if(input.length!==summary.source_count)throw sourceCollectionBad();const sources:InspectionSourceRevision[]=[],seen=new Set<string>();let total=0
  for(const value of input){if(!value||typeof value!=='object'||Array.isArray(value))throw sourceCollectionBad();const s=readInspectionSourceShape(value,context,{worksheetId:value.worksheet_id,version:value.version});if(seen.has(key(s))||sources.length&&order(sources.at(-1)!,s)>=0||worksheetTime(s.recorded_at)!>worksheetTime(summary.recorded_at)!)throw sourceCollectionBad();seen.add(key(s));total+=inspectionSourceBytes(s).length;if(total>16*1024*1024)throw sourceCollectionBad();sources.push(s)}
  return {...summary,projection:'FULL_SOURCES',document_integrity:'VERIFIED_SERVER_SIDE',sources,diagnostics:readDiagnostics(inputDiagnostics,sources)}
}
export async function readSourceCollectionDetail(raw:unknown,context:SourceCollectionContext,id:string,stillCurrent:()=>boolean=()=>true):Promise<SourceCollectionDetail>{
  const scope={...context},result=readSourceCollectionDetailShape(raw,scope,id)
  const current=()=>{if(!stillCurrent()){const e=new Error('授权上下文已变化');e.name='AbortError';throw e}}
  current();for(const source of result.sources){await readInspectionSourceRevision(source,scope,{worksheetId:source.worksheet_id,version:source.version});current()}
  const selections=result.sources.map(s=>({worksheet_id:s.worksheet_id,version:s.version,expected_source_sha256:s.source_snapshot_sha256}))
  if(await sourceCollectionSha(sourceCollectionManifestBytes(result.tenant_id,selections))!==result.source_manifest_sha256)throw sourceCollectionBad();current()
  if(await sourceCollectionSha(sourceCollectionDocumentBytes(result.tenant_id,result.source_manifest_sha256,result.sources))!==result.document_sha256)throw sourceCollectionBad();current();return result
}
