import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { detachedReadState } from './detachedWorkspaceState'
import { inspectionMaxVersion, inspectionTemplateBytes, inspectionUuid, validInspectionContext } from './inspectionTemplates'
import { readWorksheetDetail, readWorksheetDetailShape, worksheetBytes, worksheetDefinition, worksheetTime, type WorksheetContext, type WorksheetDetail } from './inspectionWorksheets'
export interface InspectionSourceRevision extends Omit<WorksheetDetail, 'id' | 'updated_at'> {
  worksheet_id: string
  source_type: 'IMMUTABLE_INSPECTION_WORKSHEET_REVISION'
  source_format: 'MOP_INSPECTION_SOURCE_SNAPSHOT_V1'
  source_snapshot_sha256: string
  scope: 'CURRENT_TENANT_PADMIN'
  evidence_access_granted: false
}
export interface InspectionSourceTarget { worksheetId: string; version: number; contentSha256?: string; sourceSha256?: string }
const bad = () => new ApiError(0,'INVALID_INSPECTION_SOURCE','所选修订的租户、原始内容或来源摘要未通过核对；不会改查最新版本','—')
const hash = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v)
function target(value: InspectionSourceTarget): InspectionSourceTarget {
  if (!value || Object.keys(value).some(key=>!['worksheetId','version','contentSha256','sourceSha256'].includes(key)) || !inspectionUuid(value.worksheetId) || !Number.isSafeInteger(value.version) || value.version<1 || value.version>inspectionMaxVersion || value.contentSha256!==undefined&&!hash(value.contentSha256) || value.sourceSha256!==undefined&&!hash(value.sourceSha256)) throw bad()
  return {...value}
}
/** Reuse existing content validation; this local validation-only timestamp never leaves this projection. */
function detailProjection(source: InspectionSourceRevision): WorksheetDetail {
  const { worksheet_id, source_type: _type, source_format: _format, source_snapshot_sha256: _hash, scope: _scope, evidence_access_granted: _grant, ...facts }=source
  return {...facts,id:worksheet_id,updated_at:facts.recorded_at}
}
export function readInspectionSourceShape(raw: unknown, context: WorksheetContext, selected: InspectionSourceTarget): InspectionSourceRevision {
  const wanted=target(selected)
  if (!validInspectionContext(context) || !raw || typeof raw!=='object' || Array.isArray(raw)) throw bad()
  const source=raw as InspectionSourceRevision
  if (!['worksheet_id','source_type','source_format','source_snapshot_sha256','scope','evidence_access_granted'].every(key=>Object.hasOwn(source,key)) || Object.hasOwn(source,'id') || Object.hasOwn(source,'updated_at') || source.worksheet_id!==wanted.worksheetId || source.version!==wanted.version || source.tenant_id!==context.tenantId || source.source_type!=='IMMUTABLE_INSPECTION_WORKSHEET_REVISION' || source.source_format!=='MOP_INSPECTION_SOURCE_SNAPSHOT_V1' || source.scope!=='CURRENT_TENANT_PADMIN' || source.evidence_access_granted!==false || !hash(source.source_snapshot_sha256) || wanted.contentSha256!==undefined&&source.content_sha256!==wanted.contentSha256 || wanted.sourceSha256!==undefined&&source.source_snapshot_sha256!==wanted.sourceSha256) throw bad()
  // Exact full-detail validation rejects missing fields, extras, invalid counts and broken lifecycle/time facts.
  readWorksheetDetailShape(detailProjection(source),context)
  return structuredClone(source)
}
export function inspectionSourceBytes(source: InspectionSourceRevision): Uint8Array {
  const template=inspectionTemplateBytes(worksheetDefinition(detailProjection(source))),worksheet=worksheetBytes(detailProjection(source),source.tenant_id),parts:Uint8Array[]=[]
  const int=(n:number)=>{const b=new Uint8Array(4);new DataView(b.buffer).setInt32(0,n,false);return b},long=(n:bigint)=>{const b=new Uint8Array(8);new DataView(b.buffer).setBigInt64(0,n,false);return b},field=(s:string)=>{const b=new TextEncoder().encode(s);parts.push(int(b.length),b)}
  field('MOP_INSPECTION_SOURCE_SNAPSHOT_V1');field(source.tenant_id);field(source.worksheet_id);parts.push(long(BigInt(source.version)));field(source.created_by)
  const created=worksheetTime(source.created_at),recorded=worksheetTime(source.recorded_at);if(created===null||recorded===null)throw bad()
  parts.push(long(created/1_000_000n));field(source.recorded_by);parts.push(long(recorded/1_000_000n));field(source.action);parts.push(new Uint8Array([source.archived?1:0]),int(template.length),template,int(worksheet.length),worksheet)
  const result=new Uint8Array(parts.reduce((n,b)=>n+b.length,0));let offset=0;for(const p of parts){result.set(p,offset);offset+=p.length}return result
}
export async function inspectionSourceFingerprint(source: InspectionSourceRevision): Promise<string> {
  const bytes=inspectionSourceBytes(source),digest=await crypto.subtle.digest('SHA-256',bytes as Uint8Array<ArrayBuffer>)
  return Array.from(new Uint8Array(digest)).map(n=>n.toString(16).padStart(2,'0')).join('')
}
export async function readInspectionSourceRevision(raw:unknown,context:WorksheetContext,selected:InspectionSourceTarget):Promise<InspectionSourceRevision>{
  const scope={...context},source=readInspectionSourceShape(raw,scope,selected)
  await readWorksheetDetail(detailProjection(source),scope)
  if(await inspectionSourceFingerprint(source)!==source.source_snapshot_sha256)throw bad()
  return source
}
export interface InspectionSourceReadState { target: InspectionSourceTarget|null; data: InspectionSourceRevision|null; busy:boolean; error:unknown }
export const emptyInspectionSourceReadState=():InspectionSourceReadState=>({target:null,data:null,busy:false,error:null})
/** Separate read state: never acknowledges, edits or retries a worksheet mutation. */
export function createInspectionSourceReader(client:Pick<ApiClient,'request'>,changed:(value:InspectionSourceReadState)=>void){
  let context:WorksheetContext|null=null,state=emptyInspectionSourceReadState(),epoch=0,abort:AbortController|null=null,disposed=false
  const snapshot=()=>detachedReadState(state),emit=()=>changed(snapshot())
  function reset(next:WorksheetContext|null){abort?.abort();abort=null;++epoch;context=!disposed&&validInspectionContext(next)?{...next}:null;state=emptyInspectionSourceReadState();emit()}
  async function load(selected:InspectionSourceTarget){
    if(!context||disposed)return false
    abort?.abort();abort=null;const turn=++epoch,scope={...context};state=emptyInspectionSourceReadState()
    let wanted:InspectionSourceTarget;try{wanted=target(selected)}catch(error){state.error=error;emit();return false}
    const controller=new AbortController();abort=controller;state={target:wanted,data:null,busy:true,error:null};emit()
    try{const raw=await client.request('/inspection-worksheets/'+wanted.worksheetId+'/revisions/'+wanted.version,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
      const data=await readInspectionSourceRevision(raw,scope,wanted);if(turn!==epoch||disposed||controller.signal.aborted)return false;state.data=data;return true
    }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}
    finally{if(turn===epoch&&!disposed){state.busy=false;abort=null;emit()}}
  }
  return {reset,load,state:snapshot,dispose(){if(disposed)return;disposed=true;reset(null)}}
}
