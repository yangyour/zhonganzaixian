import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { createRequestId } from './browserCrypto'
import { utcInstantNanos } from './utcInstant'
export interface RuntimeScope { tenantId:string; actorId:string; permissionVersion:number }
export interface RuntimeRelease { id:string;tenant_id:string;configuration_key:string;version:number;action:'PUBLISH'|'ROLLBACK'|'DISABLE';revision_id:string|null;revision_version:number|null;target_release_id:string|null;value:unknown;definition_hash:string;environment:'remote_test';scope:'CURRENT_TENANT';effective_at:string;expires_at:string|null;published_by:string;published_permission_version:number;published_at:string;reason:string;customer_confirmation_status:'UNVERIFIED';production_approved:false;source:'postgresql' }
export interface RuntimeCurrent { tenant_id:string;configuration_key:string;environment:'remote_test';scope:'CURRENT_TENANT';observed_at:string;latest_version:number;definition_hash:string;status:'UNCONFIGURED'|'SCHEDULED'|'ACTIVE'|'DISABLED'|'EXPIRED'|'DEFINITION_CHANGED';effective_value:unknown;selected_release:RuntimeRelease|null;source:'postgresql';customer_confirmation_status:'UNVERIFIED';production_approved:false }
export interface RuntimeDefinition { key:string;description:string;type:string;scope:string;unit:string|null;allowed_range:string|null;contract_value_kind:string;question_ids:string[];source:string;editable:boolean;schema_version:1;definition_hash:string;schema:Record<string,unknown>;registered_value:unknown;runtime_scope:'CURRENT_TENANT';customer_confirmation_status:'UNVERIFIED' }
export interface RuntimeCommand { expected_version:number;expected_definition_hash:string;action:RuntimeRelease['action'];revision_id:string|null;revision_version:number|null;target_release_id:string|null;activation:'IMMEDIATE'|'SCHEDULED';effective_at:string|null;expires_at:string|null;reason:string }
export interface RuntimeAttempt { path:string;key:string;payload:RuntimeCommand;state:'uncertain'|'rejected'|'accepted' }
export interface RuntimeState { key:string;current:RuntimeCurrent|null;history:RuntimeRelease[];more:boolean;busy:'read'|'write'|null;error:unknown;attempt:RuntimeAttempt|null;receipt:RuntimeRelease|null }
export const emptyRuntimeState=():RuntimeState=>({key:'',current:null,history:[],more:false,busy:null,error:null,attempt:null,receipt:null})
const uuid=(s:unknown):s is string=>typeof s==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s)
const version=(n:unknown,min=1):n is number=>Number.isSafeInteger(n)&&Number(n)>=min&&Number(n)<=9007199254740990
const time=(s:unknown):s is string=>typeof s==='string'&&/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(s)&&utcInstantNanos(s)!==null
const bad=()=>new ApiError(0,'INVALID_RUNTIME_CONFIGURATION','运行配置返回未通过范围、版本或状态校验，请重新读取；未确认发布请重试原请求','—')
const exact=(v:unknown,keys:string[])=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const safeValue=(v:unknown,depth=0):boolean=>depth<=12&&(v===null||typeof v==='string'||typeof v==='boolean'||typeof v==='number'&&Number.isFinite(v)&&(!Number.isInteger(v)||Number.isSafeInteger(v))||Array.isArray(v)&&v.length<=1000&&v.every(x=>safeValue(x,depth+1))||!!v&&typeof v==='object'&&Object.keys(v).length<=1000&&Object.values(v).every(x=>safeValue(x,depth+1)))
export function readRuntimeRelease(raw:unknown,scope:RuntimeScope,key:string):RuntimeRelease {
 const r=raw as RuntimeRelease
 if(!exact(r,['id','tenant_id','configuration_key','version','action','revision_id','revision_version','target_release_id','value','definition_hash','environment','scope','effective_at','expires_at','published_by','published_permission_version','published_at','reason','customer_confirmation_status','production_approved','source'])||!safeValue(r.value))throw bad()
 if(!r||!uuid(r.id)||r.tenant_id!==scope.tenantId||r.configuration_key!==key||!version(r.version)||!['PUBLISH','ROLLBACK','DISABLE'].includes(r.action)||!uuid(r.published_by)||!version(r.published_permission_version)||!time(r.published_at)||!time(r.effective_at)||Date.parse(r.effective_at)<Date.parse(r.published_at)||r.expires_at!==null&&(!time(r.expires_at)||Date.parse(r.expires_at)<=Date.parse(r.effective_at))||r.environment!=='remote_test'||r.scope!=='CURRENT_TENANT'||r.source!=='postgresql'||r.production_approved!==false||r.customer_confirmation_status!=='UNVERIFIED'||!/^[a-f0-9]{64}$/.test(r.definition_hash)||typeof r.reason!=='string')throw bad()
 if(r.action==='DISABLE'&&(r.value!==null||r.revision_id!==null||r.revision_version!==null||r.target_release_id!==null))throw bad()
 if(r.action!=='DISABLE'&&(!uuid(r.revision_id)||!version(r.revision_version)||r.value==null||(r.action==='ROLLBACK'?!uuid(r.target_release_id):r.target_release_id!==null)))throw bad()
 return structuredClone(r)
}
export function readRuntimeCurrent(raw:unknown,scope:RuntimeScope,key:string):RuntimeCurrent {
 const r=raw as RuntimeCurrent
 if(!exact(r,['tenant_id','configuration_key','environment','scope','observed_at','latest_version','definition_hash','status','effective_value','selected_release','source','customer_confirmation_status','production_approved']))throw bad()
 if(!r||r.tenant_id!==scope.tenantId||r.configuration_key!==key||r.scope!=='CURRENT_TENANT'||r.environment!=='remote_test'||!time(r.observed_at)||!version(r.latest_version,0)||r.source!=='postgresql'||r.customer_confirmation_status!=='UNVERIFIED'||r.production_approved!==false||!['UNCONFIGURED','SCHEDULED','ACTIVE','DISABLED','EXPIRED','DEFINITION_CHANGED'].includes(r.status)||!/^[a-f0-9]{64}$/.test(r.definition_hash))throw bad()
 if(r.selected_release===null){if(!['UNCONFIGURED','SCHEDULED'].includes(r.status)||r.effective_value!==null||r.status==='UNCONFIGURED'&&r.latest_version!==0||r.status==='SCHEDULED'&&r.latest_version===0)throw bad()}
 else {const s=readRuntimeRelease(r.selected_release,scope,key);if(s.version>r.latest_version||Date.parse(s.effective_at)>Date.parse(r.observed_at))throw bad();const expected=s.action==='DISABLE'?'DISABLED':s.expires_at!==null&&Date.parse(s.expires_at)<=Date.parse(r.observed_at)?'EXPIRED':s.definition_hash!==r.definition_hash?'DEFINITION_CHANGED':'ACTIVE';if(r.status!==expected||JSON.stringify(r.effective_value)!==JSON.stringify(expected==='ACTIVE'?s.value:null))throw bad()}
 return structuredClone(r)
}
export function createRuntimeConfigurationWorkspace(client:Pick<ApiClient,'request'>,changed:(state:RuntimeState)=>void){
 let state=emptyRuntimeState(),scope:RuntimeScope|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 const emit=()=>changed({...state,current:state.current?structuredClone(state.current):null,history:structuredClone(state.history),attempt:state.attempt?structuredClone(state.attempt):null,receipt:state.receipt?structuredClone(state.receipt):null})
 const unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
 function reset(next:RuntimeScope|null){++epoch;abort?.abort();abort=null;scope=!disposed&&next?{...next}:null;state=emptyRuntimeState();emit()}
 async function load(key=state.key,before?:number){
  if(!scope||disposed||unresolved()||state.busy)return false
  if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(key))return false
  const turn=++epoch,currentScope={...scope};abort=new AbortController();const signal=abort.signal
  const switching=key!==state.key;state={...state,key,current:before?state.current:null,history:[],more:false,busy:'read',error:null,attempt:switching?null:state.attempt,receipt:switching?null:state.receipt};emit()
  try{const [current,history]=await Promise.all([client.request<unknown>('/runtime-configurations/'+key,{signal}),client.request<{items:unknown[];page_size:number;source:string}>('/runtime-configurations/'+key+'/history'+(before?'?before_version='+before:''),{signal})]);if(turn!==epoch||disposed)return false;const value=readRuntimeCurrent(current,currentScope,key);if(!history||history.source!=='postgresql'||history.page_size!==25||!Array.isArray(history.items)||history.items.length>25)throw bad();const rows=history.items.map(r=>readRuntimeRelease(r,currentScope,key));let previous=before??9007199254740991;for(const row of rows){if(row.version>=previous||row.version>value.latest_version)throw bad();previous=row.version}state.current=value;state.history=rows;state.more=rows.length===25;return true}
  catch(error){if(turn===epoch&&!disposed&&!isCancelled(error)){state.current=null;state.history=[];state.error=error}return false}
  finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 async function send(){
  if(!scope||disposed||state.busy||!state.attempt||state.attempt.state==='accepted')return false
  const turn=++epoch,currentScope={...scope},attempt=structuredClone(state.attempt),selected=state.key;abort=new AbortController();state.busy='write';state.current=null;state.error=null;state.attempt!.state='uncertain';emit()
  try{const raw=await client.request<unknown>(attempt.path,{method:'POST',body:attempt.payload,idempotencyKey:attempt.key,signal:abort.signal});if(turn!==epoch||disposed)return false;const receipt=readRuntimeRelease(raw,currentScope,selected),p=attempt.payload;if(receipt.definition_hash!==p.expected_definition_hash||receipt.version!==p.expected_version+1||receipt.action!==p.action||receipt.published_by!==currentScope.actorId||receipt.published_permission_version!==currentScope.permissionVersion||receipt.reason!==p.reason.trim()||receipt.target_release_id!==p.target_release_id||p.action==='PUBLISH'&&(receipt.revision_id!==p.revision_id||receipt.revision_version!==p.revision_version)||p.activation==='SCHEDULED'&&Date.parse(receipt.effective_at)!==Date.parse(p.effective_at!)||p.activation==='IMMEDIATE'&&receipt.effective_at!==receipt.published_at||((receipt.expires_at===null)!==(p.expires_at===null))||p.expires_at!==null&&Date.parse(receipt.expires_at!)!==Date.parse(p.expires_at))throw bad();state.receipt=receipt;state.attempt!.state='accepted';return true}
  catch(error){if(turn===epoch&&!disposed){state.error=error;if(error instanceof ApiError&&[400,403,404,409,422].includes(error.status))state.attempt!.state='rejected'}return false}
  finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 emit();return{reset,load,unresolved,retry:send,clearAttempt(){if(unresolved())return false;state.attempt=null;state.error=null;emit();return true},async publish(payload:RuntimeCommand){if(!scope||state.busy||state.attempt||!state.current||payload.expected_version!==state.current.latest_version)return false;state.attempt={path:'/runtime-configurations/'+state.key+'/releases',key:createRequestId(),payload:structuredClone(payload),state:'uncertain'};return send()},dispose(){disposed=true;reset(null)}}
}
