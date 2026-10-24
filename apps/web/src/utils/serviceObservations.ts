import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {utcInstantNanos} from './utcInstant'
export interface ServiceScope {tenantId:string;actorId:string;permissionVersion:number;canRead:boolean}
export interface ServiceSpan {kind:'FAULT'|'MAINTENANCE'|'UNKNOWN'|'AVAILABLE';severity:'INFO'|'WARNING'|'AVERAGE'|'HIGH'|'DISASTER'|null;start:string;end:string}
export interface ObservedService {id:string;name:string;assets:{id:string;name:string;version:number}[];failure_mode:'ANY_ASSET_UNAVAILABLE'|'ALL_ASSETS_UNAVAILABLE';maintenance_mode:'ANY_ASSET_APPROVED'|'ALL_ASSETS_APPROVED';target_pct:number|null;verified_availability_pct:number|null;known_available_millis:number;effective_millis:number;unavailable_millis:number;unknown_millis:number;exclusion_millis:number;active_problems:number;high_problems:number;health_state:'UNKNOWN'|'NOT_MET'|'WARNING'|'MET';coverage_status:string;timeline_included:boolean;timeline:ServiceSpan[]}
export interface MonthlyServiceSummary {percentage:number|null;known_available_millis:number;effective_service_millis:number;service_count:number;qualified_service_count:number;coverage_status:'EMPTY_MONTH_WINDOW'|'INSUFFICIENT_COVERAGE'|'ALL_SERVICES_QUALIFIED'}
export interface ServiceSnapshot {monthly_summary:MonthlyServiceSummary;tenant_id:string;actor_id:string;permission_version:number;observed_at:string;month_start:string;timeline_start:string;mapping_sha256:string;services:ObservedService[];metric_version:string;source:string;timezone:string;mapping_status:string;positive_health_coverage_loaded:false;timeline_row_limit:8;service_count:number;writes_performed:false}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>object(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const count=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0
const label=(v:unknown):v is string=>typeof v==='string'&&!!v.trim()&&v.length<=200&&!/[\u0000-\u001f\u007f]/u.test(v)
const pct=(v:unknown):v is number|null=>v===null||typeof v==='number'&&Number.isFinite(v)&&v>=0&&v<=100
const bad=()=>new ApiError(200,'INVALID_SERVICE_OBSERVATIONS','服务观测范围或证据未通过核对','—')
const instant=(v:unknown)=>{const n=typeof v==='string'?utcInstantNanos(v):null;if(n===null)throw bad();return n}
export function readServiceObservations(raw:unknown,scope:ServiceScope):ServiceSnapshot {
 if(!exact(raw,['tenant_id','actor_id','permission_version','observed_at','month_start','timeline_start','mapping_sha256','services','metric_version','source','timezone','mapping_status','positive_health_coverage_loaded','timeline_row_limit','service_count','writes_performed','monthly_summary'])||raw.tenant_id!==scope.tenantId||raw.actor_id!==scope.actorId||raw.permission_version!==scope.permissionVersion||raw.metric_version!=='MOP_CONFIGURED_SERVICE_OBSERVATIONS_V1'||raw.source!=='POSTGRESQL_CONFIGURED_SERVICE_OBSERVATIONS'||raw.timezone!=='Asia/Shanghai'||raw.mapping_status!=='EXPLICIT_SERVER_CONFIGURATION_NOT_CUSTOMER_ACCEPTANCE'||raw.positive_health_coverage_loaded!==false||raw.timeline_row_limit!==8||raw.writes_performed!==false||typeof raw.mapping_sha256!=='string'||!/^[0-9a-f]{64}$/.test(raw.mapping_sha256))throw bad()
 const observed=instant(raw.observed_at),month=instant(raw.month_start),start=instant(raw.timeline_start)
 if(observed%1000000n!==0n||start!==observed-86400000000000n||month>observed)throw bad()
 const date=new Date(Number(observed/1000000n)+8*3600000),expectedMonth=BigInt(Date.UTC(date.getUTCFullYear(),date.getUTCMonth(),1)-8*3600000)*1000000n
 if(month!==expectedMonth||!Array.isArray(raw.services)||!raw.services.length||raw.services.length>100||raw.service_count!==raw.services.length)throw bad()
 const ids=new Set<string>();let spans=0
 for(const [index,s] of raw.services.entries()) {
  if(!exact(s,['id','name','assets','failure_mode','maintenance_mode','target_pct','verified_availability_pct','known_available_millis','effective_millis','unavailable_millis','unknown_millis','exclusion_millis','active_problems','high_problems','health_state','coverage_status','timeline_included','timeline'])||!uuid(s.id)||ids.has(s.id)||!label(s.name)||!['ANY_ASSET_UNAVAILABLE','ALL_ASSETS_UNAVAILABLE'].includes(String(s.failure_mode))||!['ANY_ASSET_APPROVED','ALL_ASSETS_APPROVED'].includes(String(s.maintenance_mode))||!pct(s.target_pct)||!pct(s.verified_availability_pct)||!Array.isArray(s.assets)||!s.assets.length||s.assets.length>100)throw bad()
  ids.add(s.id);const assetIds=new Set<string>()
  for(const a of s.assets){if(!exact(a,['id','name','version'])||!uuid(a.id)||assetIds.has(a.id)||!label(a.name)||!count(a.version)||a.version<1)throw bad();assetIds.add(a.id)}
  if(s.known_available_millis!==0||!count(s.effective_millis)||!count(s.unavailable_millis)||!count(s.unknown_millis)||!count(s.exclusion_millis)||!count(s.active_problems)||!count(s.high_problems)||s.high_problems>s.active_problems||s.known_available_millis>s.effective_millis||s.effective_millis>Number((observed-month)/1000000n))throw bad()
  const total=Number((observed-month)/1000000n)
  if(Math.abs(total-s.effective_millis-s.unknown_millis-s.exclusion_millis)>2||Math.abs(s.effective_millis-s.known_available_millis-s.unavailable_millis)>1)throw bad()
  const expectedCoverage=total===0?'EMPTY_MONTH_WINDOW':total===s.exclusion_millis?'NO_EFFECTIVE_WINDOW':s.effective_millis===0?'NO_OBSERVATIONS':s.verified_availability_pct===null?'PARTIAL':'SUFFICIENT'
  if(s.coverage_status!==expectedCoverage||s.verified_availability_pct!==null&&(s.effective_millis===0||s.effective_millis*10<(total-s.exclusion_millis)*9||Math.abs(s.verified_availability_pct-s.known_available_millis/s.effective_millis*100)>0.000051))throw bad()
  const health=s.verified_availability_pct===null||s.target_pct===null?'UNKNOWN':s.verified_availability_pct<s.target_pct?'NOT_MET':s.high_problems>0?'WARNING':'MET'
  if(s.health_state!==health||s.timeline_included!==(index<8)||!Array.isArray(s.timeline)||s.timeline.length>2000||index>=8&&s.timeline.length!==0)throw bad()
  let cursor=start
  for(const span of s.timeline){if(!exact(span,['kind','severity','start','end'])||!['FAULT','MAINTENANCE','UNKNOWN'].includes(String(span.kind))||(span.kind==='FAULT'?!['INFO','WARNING','AVERAGE','HIGH','DISASTER'].includes(String(span.severity)):span.severity!==null))throw bad();const from=instant(span.start),to=instant(span.end);if(from!==cursor||to<=from||to>observed)throw bad();cursor=to}
  if(index<8&&cursor!==observed)throw bad();spans+=s.timeline.length;if(spans>10000)throw bad()
 }
 const summary=raw.monthly_summary
 if(!exact(summary,['percentage','known_available_millis','effective_service_millis','service_count','qualified_service_count','coverage_status'])||!pct(summary.percentage)||!count(summary.effective_service_millis)||summary.known_available_millis!==0||summary.service_count!==raw.services.length)throw bad()
 const services=raw.services as unknown as ObservedService[],effective=services.reduce((sum,s)=>sum+s.effective_millis,0),qualified=services.filter(s=>s.verified_availability_pct!==null).length
 const sufficient=qualified===services.length&&effective>0,coverage=observed===month?'EMPTY_MONTH_WINDOW':sufficient?'ALL_SERVICES_QUALIFIED':'INSUFFICIENT_COVERAGE'
 if(summary.effective_service_millis!==effective||summary.qualified_service_count!==qualified||summary.coverage_status!==coverage||(sufficient?summary.percentage!==0:summary.percentage!==null))throw bad()
 return structuredClone(raw) as unknown as ServiceSnapshot
}
export interface ServiceState {current:ServiceSnapshot|null;busy:boolean;error:unknown;cancelled:boolean}
export const emptyServiceState=():ServiceState=>({current:null,busy:false,error:null,cancelled:false})
export function createServiceReader(client:Pick<ApiClient,'request'>,changed:(state:ServiceState)=>void) {
 let scope:ServiceScope|null=null,epoch=0,abort:AbortController|null=null,disposed=false,state=emptyServiceState()
 const emit=()=>changed({...state})
 const clear=()=>{epoch++;abort?.abort();abort=null;state=emptyServiceState()}
 return {
  reset(next:ServiceScope|null){clear();scope=!disposed&&next?.canRead&&uuid(next.tenantId)&&uuid(next.actorId)&&count(next.permissionVersion)&&next.permissionVersion>0?{...next}:null;emit()},
  async load(){if(disposed||!scope||state.busy)return false;clear();const turn=epoch,expected={...scope},controller=new AbortController();abort=controller;state.busy=true;emit();try {const raw=await client.request('/dashboard/service-observations',{method:'GET',signal:controller.signal});if(disposed||turn!==epoch||controller.signal.aborted)return false;state.current=readServiceObservations(raw,expected);return true}catch(error){if(!disposed&&turn===epoch&&!isCancelled(error))state.error=error;return false}finally{if(!disposed&&turn===epoch){state.busy=false;abort=null;emit()}}},
  cancel(){if(!state.busy)return;clear();state.cancelled=true;emit()},
  dispose(){clear();scope=null;disposed=true;emit()},
 }
}
