import {ApiError} from '../api/client'
import {inspectionUuid,validInspectionContext,validInspectionText,type InspectionContext} from './inspectionTemplates'
import {worksheetTime} from './inspectionWorksheets'
import {sourceCollectionSha} from './inspectionSourceCollections'
export type ReportContext=InspectionContext
export interface ReportConfiguration {revision:string|null;time_zone:string|null;duplicate_rule:'REQUIRE_SINGLE_SOURCE'|'LATEST_REPORTED_PERFORMED_AT'|null;archived_rule:'INCLUDE'|'EXCLUDE'|null;metric_version:string|null;report_template_version:string|null}
export interface ReportPayload {expected_version:0;label:string;source_collection_id:string;expected_source_document_sha256:string;expected_source_manifest_sha256:string;year:number;quarter:number;configuration:ReportConfiguration}
export const reportBad=()=>new ApiError(0,'INVALID_INSPECTION_QUARTERLY_REPORT','季度草稿范围、冻结内容或摘要核对失败；结果未知时仅可恢复完全相同原请求','—')
export const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{64}$/.test(v)
const integer=(v:unknown,min:number,max:number):v is number=>Number.isSafeInteger(v)&&Number(v)>=min&&Number(v)<=max
const configKeys=['revision','time_zone','duplicate_rule','archived_rule','metric_version','report_template_version']
export const pendingReportConfiguration=():ReportConfiguration=>({revision:null,time_zone:null,duplicate_rule:null,archived_rule:null,metric_version:null,report_template_version:null})
export function readReportConfiguration(raw:unknown):ReportConfiguration {
 if(!exact(raw,configKeys)||['revision','metric_version','report_template_version'].some(k=>raw[k]!==null&&!validInspectionText(raw[k],200))||raw.time_zone!==null&&!validInspectionText(raw.time_zone,100)||![null,'REQUIRE_SINGLE_SOURCE','LATEST_REPORTED_PERFORMED_AT'].includes(raw.duplicate_rule as null)||![null,'INCLUDE','EXCLUDE'].includes(raw.archived_rule as null))throw reportBad()
 // Zone validity is authoritative on the server (Java TZDB); never substitute the browser/platform zone.
 return structuredClone(raw) as unknown as ReportConfiguration
}
export function readReportPayload(raw:unknown):ReportPayload {
 if(!exact(raw,['expected_version','label','source_collection_id','expected_source_document_sha256','expected_source_manifest_sha256','year','quarter','configuration'])||raw.expected_version!==0||!validInspectionText(raw.label,200)||!inspectionUuid(raw.source_collection_id)||!hash(raw.expected_source_document_sha256)||!hash(raw.expected_source_manifest_sha256)||!integer(raw.year,1,9998)||!integer(raw.quarter,1,4))throw reportBad()
 return {...structuredClone(raw),configuration:readReportConfiguration(raw.configuration)} as unknown as ReportPayload
}
const utf8=(s:string)=>new TextEncoder().encode(s)
const i4=(n:number)=>{const b=new Uint8Array(4);new DataView(b.buffer).setInt32(0,n,false);return b}
const join=(p:Uint8Array[])=>{const b=new Uint8Array(p.reduce((n,v)=>n+v.length,0));let at=0;for(const v of p){b.set(v,at);at+=v.length}return b}
const field=(s:string|null)=>s===null?i4(-1):join([i4(utf8(s).length),utf8(s)])
export function reportInputBytes(payload:ReportPayload):Uint8Array {const p=readReportPayload(payload),c=p.configuration;return join([field('MOP_INSPECTION_QUARTERLY_DRAFT_V1'),field(p.expected_source_manifest_sha256),i4(p.year),i4(p.quarter),...configKeys.map(k=>field(c[k as keyof ReportConfiguration])),field('COMPLETE_EXPLICIT_SELECTION')])}
export const reportInputSha=(payload:ReportPayload)=>sourceCollectionSha(reportInputBytes(payload))
export interface ReportSummary {id:string;tenant_id:string;created_by:string;recorded_at:string;version:1;label:string;source_collection_id:string;source_document_sha256:string;source_manifest_sha256:string;year:number;quarter:number;report_sha256:string;document_format:'MOP_INSPECTION_QUARTERLY_DOCUMENT_V1';document_sha256:string;projection:'METADATA_ONLY';configuration_source:'REQUEST_DRAFT_NOT_APPROVED';scope:'CURRENT_TENANT_PADMIN';source:'postgresql';generation_status:'DRAFT';report_publishable:false;report_published:false;evidence_access_granted:false;tenant_asset_population:null}
export interface ReportPage {tenant_id:string;items:ReportSummary[];has_more:boolean;next_before_recorded_at:string|null;next_before_id:string|null;page_size_limit:20;scope:'CURRENT_TENANT_PADMIN';source:'postgresql'}
export interface ReportCursor {time:string;id:string}
const summaryKeys=['id','tenant_id','created_by','recorded_at','version','label','source_collection_id','source_document_sha256','source_manifest_sha256','year','quarter','report_sha256','document_format','document_sha256','projection','configuration_source','scope','source','generation_status','report_publishable','report_published','evidence_access_granted','tenant_asset_population']
export function readReportSummary(raw:unknown,context:ReportContext):ReportSummary {
 if(!validInspectionContext(context)||!exact(raw,summaryKeys)||!inspectionUuid(raw.id)||raw.tenant_id!==context.tenantId||!inspectionUuid(raw.created_by)||worksheetTime(raw.recorded_at)===null||raw.version!==1||!validInspectionText(raw.label,200)||!inspectionUuid(raw.source_collection_id)||!['source_document_sha256','source_manifest_sha256','report_sha256','document_sha256'].every(k=>hash(raw[k]))||!integer(raw.year,1,9998)||!integer(raw.quarter,1,4)||raw.document_format!=='MOP_INSPECTION_QUARTERLY_DOCUMENT_V1'||raw.projection!=='METADATA_ONLY'||raw.configuration_source!=='REQUEST_DRAFT_NOT_APPROVED'||raw.scope!=='CURRENT_TENANT_PADMIN'||raw.source!=='postgresql'||raw.generation_status!=='DRAFT'||raw.report_publishable!==false||raw.report_published!==false||raw.evidence_access_granted!==false||raw.tenant_asset_population!==null)throw reportBad()
 return structuredClone(raw) as unknown as ReportSummary
}
export function readReportPage(raw:unknown,context:ReportContext,before?:ReportCursor):ReportPage {
 if(!validInspectionContext(context)||!exact(raw,['tenant_id','items','has_more','next_before_recorded_at','next_before_id','page_size_limit','scope','source'])||raw.tenant_id!==context.tenantId||!Array.isArray(raw.items)||raw.items.length>20||typeof raw.has_more!=='boolean'||raw.page_size_limit!==20||raw.scope!=='CURRENT_TENANT_PADMIN'||raw.source!=='postgresql')throw reportBad()
 if(before&&(!inspectionUuid(before.id)||worksheetTime(before.time)===null))throw reportBad()
 const items=raw.items.map(v=>readReportSummary(v,context)),seen=new Set<string>();let previous=before
 for(const item of items){const time=worksheetTime(item.recorded_at)!;if(seen.has(item.id)||previous&&(time>worksheetTime(previous.time)!||time===worksheetTime(previous.time)&&item.id>=previous.id))throw reportBad();seen.add(item.id);previous={time:item.recorded_at,id:item.id}}
 const last=items.at(-1);if(raw.has_more?!last||raw.next_before_recorded_at!==last.recorded_at||raw.next_before_id!==last.id:raw.next_before_recorded_at!==null||raw.next_before_id!==null)throw reportBad()
 return {...structuredClone(raw),items} as unknown as ReportPage
}
export interface ReportDetail extends Omit<ReportSummary,'projection'> {projection:'FROZEN_INPUT_OUTPUT';document_integrity:'VERIFIED_SERVER_SIDE';frozen_document:import('./inspectionReportDocument').FrozenReportDocument}
export async function readReportDetail(raw:unknown,context:ReportContext,id:string,stillCurrent:()=>boolean=()=>true):Promise<ReportDetail> {
 if(!inspectionUuid(id)||!exact(raw,[...summaryKeys,'document_integrity','frozen_document'])||raw.id!==id||raw.projection!=='FROZEN_INPUT_OUTPUT'||raw.document_integrity!=='VERIFIED_SERVER_SIDE')throw reportBad()
 const {frozen_document,document_integrity:_integrity,...meta}=structuredClone(raw),summary=readReportSummary({...meta,projection:'METADATA_ONLY'},context),scope={...context}
 const current=()=>{if(!stillCurrent()){const e=new Error('授权上下文已变化');e.name='AbortError';throw e}}
 const {readFrozenReport}=await import('./inspectionReportDocument');current();const frozen=await readFrozenReport(frozen_document,summary,scope,current);current()
 return {...summary,projection:'FROZEN_INPUT_OUTPUT',document_integrity:'VERIFIED_SERVER_SIDE',frozen_document:frozen}
}
