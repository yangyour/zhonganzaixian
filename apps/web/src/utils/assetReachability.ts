import {ApiError,isCancelled,type ApiClient} from '../api/client'
export interface ReachabilityScope {tenantId:string;actorId:string;permissionVersion:number;canRead:boolean}
export interface ReachabilityRow {id:string;name:string;version:number;state:'NORMAL'|'NO_RESPONSE'|'UNKNOWN';reason:string;sampled_at:string|null;valid_until:string|null}
export interface ReachabilitySnapshot {tenant_id:string;actor_id:string;permission_version:number;observed_at:string;total:number;normal:number;no_response:number;unknown:number;percentage:number|null;items:ReachabilityRow[];source_configured:boolean;metric_version:'MOP_ASSET_REACHABILITY_V1';source:'POSTGRESQL_LEDGER_AND_EXPLICIT_ZABBIX_ICMP';freshness_seconds:60;percentage_basis:'ALL_REGISTERED_ASSETS_ONLY_WHEN_NO_UNKNOWN';device_health_claimed:false;writes_performed:false}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const count=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0
const instant=(v:unknown):v is string=>typeof v==='string'&&/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(v)&&Number.isFinite(Date.parse(v))
const invalid=()=>new ApiError(200,'INVALID_ASSET_REACHABILITY','资产范围或采样统计未通过核对','—')
const reasons=['SOURCE_NOT_CONFIGURED','NO_EXPLICIT_BINDING','STALE','NO_DATA','COLLECTOR_ERROR','DISABLED','CLOCK_SKEW','FRESH_ICMP_SAMPLE']
export function projectReachability(value:ReachabilitySnapshot,elapsed:number){
 const at=Date.parse(value.observed_at)+Math.max(0,elapsed)
 const items=value.items.map(row=>row.state!=='UNKNOWN'&&Date.parse(row.valid_until!)<=at?{...row,state:'UNKNOWN' as const,reason:'STALE',valid_until:null}:row)
 const normal=items.filter(r=>r.state==='NORMAL').length,no_response=items.filter(r=>r.state==='NO_RESPONSE').length,unknown=items.length-normal-no_response
 return {items,total:items.length,normal,no_response,unknown,percentage:items.length&&unknown===0?Math.round(normal/items.length*1000000)/10000:null}
}
export function readReachability(raw:unknown,scope:ReachabilityScope):ReachabilitySnapshot {
 if(!exact(raw,['tenant_id','actor_id','permission_version','observed_at','total','normal','no_response','unknown','percentage','items','source_configured','metric_version','source','freshness_seconds','percentage_basis','device_health_claimed','writes_performed'])||raw.tenant_id!==scope.tenantId||raw.actor_id!==scope.actorId||raw.permission_version!==scope.permissionVersion||!instant(raw.observed_at)||raw.metric_version!=='MOP_ASSET_REACHABILITY_V1'||raw.source!=='POSTGRESQL_LEDGER_AND_EXPLICIT_ZABBIX_ICMP'||raw.freshness_seconds!==60||raw.percentage_basis!=='ALL_REGISTERED_ASSETS_ONLY_WHEN_NO_UNKNOWN'||raw.device_health_claimed!==false||raw.writes_performed!==false||typeof raw.source_configured!=='boolean'||!Array.isArray(raw.items)||raw.items.length>10000)throw invalid()
 const ids=new Set<string>(),at=Date.parse(raw.observed_at)
 for(const row of raw.items){
  if(!exact(row,['id','name','version','state','reason','sampled_at','valid_until'])||!uuid(row.id)||ids.has(row.id)||typeof row.name!=='string'||!row.name.trim()||row.name.length>200||!count(row.version)||row.version<1||!['NORMAL','NO_RESPONSE','UNKNOWN'].includes(String(row.state))||!reasons.includes(String(row.reason))||row.sampled_at!==null&&!instant(row.sampled_at)||row.valid_until!==null&&!instant(row.valid_until))throw invalid()
  ids.add(row.id)
  if(row.state==='UNKNOWN'){if(row.valid_until!==null||row.reason==='FRESH_ICMP_SAMPLE')throw invalid()}
  else if(row.reason!=='FRESH_ICMP_SAMPLE'||!instant(row.sampled_at)||!instant(row.valid_until)||Date.parse(row.sampled_at)>at||Date.parse(row.valid_until)!==Date.parse(row.sampled_at)+60000||Date.parse(row.valid_until)<=at)throw invalid()
  if(!raw.source_configured&&(row.state!=='UNKNOWN'||row.reason!=='SOURCE_NOT_CONFIGURED'||row.sampled_at!==null))throw invalid()
 }
 const result=raw as unknown as ReachabilitySnapshot,summary=projectReachability(result,0)
 for(const key of ['total','normal','no_response','unknown','percentage'] as const)if(raw[key]!==summary[key])throw invalid()
 return structuredClone(result)
}
export interface ReachabilityState {current:ReachabilitySnapshot|null;busy:boolean;error:unknown;cancelled:boolean;loadedAt:number}
export const emptyReachability=():ReachabilityState=>({current:null,busy:false,error:null,cancelled:false,loadedAt:0})
export function createReachabilityReader(client:Pick<ApiClient,'request'>,changed:(value:ReachabilityState)=>void,clock=()=>performance.now()){
 let state=emptyReachability(),scope:ReachabilityScope|null=null,epoch=0,controller:AbortController|null=null,disposed=false
 const emit=()=>changed({...state});const clear=()=>{epoch++;controller?.abort();controller=null;state=emptyReachability()}
 return {
  reset(next:ReachabilityScope|null){clear();scope=!disposed&&next?.canRead&&uuid(next.tenantId)&&uuid(next.actorId)&&count(next.permissionVersion)&&next.permissionVersion>0?{...next}:null;emit()},
  async load(){if(!scope||disposed||state.busy)return false;clear();const turn=epoch,expected={...scope},startedAt=clock(),abort=new AbortController();controller=abort;state.busy=true;emit();try{const value=await client.request('/dashboard/asset-reachability',{method:'GET',signal:abort.signal});if(disposed||turn!==epoch||abort.signal.aborted)return false;state.current=readReachability(value,expected);state.loadedAt=startedAt;return true}catch(error){if(!disposed&&turn===epoch&&!isCancelled(error))state.error=error;return false}finally{if(!disposed&&turn===epoch){state.busy=false;controller=null;emit()}}},
  cancel(){if(!state.busy)return;clear();state.cancelled=true;emit()},
  dispose(){disposed=true;clear();scope=null;emit()},
 }
}
