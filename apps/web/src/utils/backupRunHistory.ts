import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {backupInventoryInstant,type BackupInventoryScope} from './backupVersionInventory'
export interface BackupRunCursor{before_queued_at:string;before_run_id:string}
export interface BackupAttempt{number:number;started_at:string;observed_at:string;finished_at:string|null;outcome:'RUNNING'|'RESULT_UNKNOWN'|'SUCCEEDED'|'FAILED';failure:string|null}
export interface BackupRunRow{run_id:string;asset_id:string;version:number;state:'QUEUED'|'RUNNING'|'RETRY_WAIT'|'SUCCEEDED'|'FAILED';queued_at:string;finished_at:string|null;known_failed_attempt_count:number;final_run_failure_count:0|1|null;reconciliation_required:boolean;snapshot_sha256:string;attempts:BackupAttempt[]}
export interface BackupRunPage{tenant_id:string;actor_id:string;permission_version:number;asset_id:string;observed_at:string;page_size:number;total_count:number;items:BackupRunRow[];next:BackupRunCursor|null;failure_count_semantics:null;consecutive_failure_count:null;automatic_ticket_status:'CONFIG_PENDING_Q22';automatic_ticket_allowed:false;failure_ticket_threshold:2;ticket_deadline_seconds:600;snapshot_scope:'SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT';source:'CURRENT_AUTHORIZED_BACKUP_RUN_JOURNAL';snapshot_payload_verified:true;full_journal_chain_verified:false;object_storage_accessed:false;device_execution_authorized:false;writes_performed:false}
export interface BackupRunTarget{cursor:BackupRunCursor|null;pageSize:number}
export interface BackupRunState{current:BackupRunPage|null;target:BackupRunTarget|null;busy:boolean;error:unknown;cancelled:boolean}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const count=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0
const bad=()=>new ApiError(200,'INVALID_BACKUP_RUN_HISTORY','备份运行范围、快照字段或分页边界不一致，未展示部分数据','—')
const time=backupInventoryInstant
const older=(a:BackupRunCursor,b:BackupRunCursor)=>time(a.before_queued_at)<time(b.before_queued_at)||time(a.before_queued_at)===time(b.before_queued_at)&&a.before_run_id<b.before_run_id
const flags=['full_journal_chain_verified','object_storage_accessed','device_execution_authorized','writes_performed','automatic_ticket_allowed']
export function readBackupRunHistory(raw:unknown,scope:BackupInventoryScope,target:BackupRunTarget):BackupRunPage{
 if(!uuid(scope.tenantId)||!uuid(scope.actorId)||!uuid(scope.assetId)||!count(scope.permissionVersion)||scope.permissionVersion<1||!count(target.pageSize)||target.pageSize<1||target.pageSize>50)throw bad()
 if(target.cursor&&(!uuid(target.cursor.before_run_id)||!Number.isFinite(time(target.cursor.before_queued_at))))throw bad()
 if(!exact(raw,['tenant_id','actor_id','permission_version','asset_id','observed_at','page_size','total_count','items','next','failure_count_semantics','consecutive_failure_count','automatic_ticket_status','failure_ticket_threshold','ticket_deadline_seconds','snapshot_scope','source','snapshot_payload_verified',...flags])||raw.tenant_id!==scope.tenantId||raw.actor_id!==scope.actorId||raw.permission_version!==scope.permissionVersion||raw.asset_id!==scope.assetId||raw.page_size!==target.pageSize||!count(raw.total_count)||raw.failure_count_semantics!==null||raw.consecutive_failure_count!==null||raw.automatic_ticket_status!=='CONFIG_PENDING_Q22'||raw.failure_ticket_threshold!==2||raw.ticket_deadline_seconds!==600||raw.snapshot_scope!=='SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT'||raw.source!=='CURRENT_AUTHORIZED_BACKUP_RUN_JOURNAL'||raw.snapshot_payload_verified!==true||flags.some(k=>raw[k]!==false)||!Array.isArray(raw.items)||raw.items.length>target.pageSize||raw.items.length>raw.total_count)throw bad()
 if(!target.cursor&&(raw.items.length!==Math.min(raw.total_count,target.pageSize)||(raw.next!==null)!==(raw.total_count>target.pageSize)))throw bad()
 time(raw.observed_at);let boundary=target.cursor;const ids=new Set<string>()
 for(const row of raw.items){if(!exact(row,['run_id','asset_id','version','state','queued_at','finished_at','known_failed_attempt_count','final_run_failure_count','reconciliation_required','snapshot_sha256','attempts'])||!uuid(row.run_id)||ids.has(row.run_id)||row.asset_id!==scope.assetId||!count(row.version)||typeof row.snapshot_sha256!=='string'||!/^[a-f0-9]{64}$/.test(row.snapshot_sha256)||!Array.isArray(row.attempts)||row.attempts.length>3)throw bad()
  const queued=time(row.queued_at);const cursor={before_queued_at:row.queued_at as string,before_run_id:row.run_id};if(boundary&&!older(cursor,boundary))throw bad();boundary=cursor;ids.add(row.run_id)
  let prior:BackupAttempt|undefined,failed=0
  for(const [index,value] of row.attempts.entries()){
   if(!exact(value,['number','started_at','observed_at','finished_at','outcome','failure'])||value.number!==index+1||!['RUNNING','RESULT_UNKNOWN','SUCCEEDED','FAILED'].includes(String(value.outcome)))throw bad()
   const start=time(value.started_at),observed=time(value.observed_at),terminal=value.outcome==='SUCCEEDED'||value.outcome==='FAILED'
   if(start<queued||observed<start||terminal&&(value.finished_at===null||time(value.finished_at)!==observed)||!terminal&&value.finished_at!==null||value.outcome==='RUNNING'&&observed!==start||prior&&(prior.outcome!=='FAILED'||start<time(prior.finished_at)+600000))throw bad()
   if(value.outcome==='FAILED'){if(!['CONNECTION_UNAVAILABLE','READ_TIMEOUT','HOST_KEY_REJECTED','AUTHORIZATION_REJECTED','COMMAND_REJECTED','STORAGE_FAILED','VERIFICATION_FAILED'].includes(String(value.failure)))throw bad();failed++}else if(value.failure!==null)throw bad()
   prior=value as unknown as BackupAttempt
  }
  const state=!prior?'QUEUED':prior.outcome==='FAILED'?row.attempts.length===3?'FAILED':'RETRY_WAIT':prior.outcome==='SUCCEEDED'?'SUCCEEDED':'RUNNING'
  if(row.state!==state||row.known_failed_attempt_count!==failed||row.final_run_failure_count!==(state==='FAILED'?1:state==='SUCCEEDED'?0:null)||row.reconciliation_required!==(prior?.outcome==='RESULT_UNKNOWN')||row.finished_at!==(state==='FAILED'||state==='SUCCEEDED'?prior!.finished_at:null)||(!prior?row.version!==0:row.version<2*row.attempts.length-(prior.outcome==='RUNNING'?1:0)))throw bad()
 }
 if(raw.next!==null&&(!exact(raw.next,['before_queued_at','before_run_id'])||raw.items.length!==target.pageSize||raw.next.before_run_id!==boundary?.before_run_id||raw.next.before_queued_at!==boundary?.before_queued_at))throw bad()
 return structuredClone(raw) as unknown as BackupRunPage
}
export const emptyBackupRunHistory=():BackupRunState=>({current:null,target:null,busy:false,error:null,cancelled:false})
export function createBackupRunHistoryReader(client:Pick<ApiClient,'request'>,notify:(state:BackupRunState)=>void){
 let scope:BackupInventoryScope|null=null,state=emptyBackupRunHistory(),epoch=0,abort:AbortController|null=null,disposed=false
 const emit=()=>notify({...state}),clear=()=>{epoch++;abort?.abort();abort=null;state=emptyBackupRunHistory()}
 const reset=(next:BackupInventoryScope|null)=>{clear();scope=!disposed&&next&&uuid(next.tenantId)&&uuid(next.actorId)&&uuid(next.assetId)&&count(next.permissionVersion)&&next.permissionVersion>0?{...next}:null;emit()}
 async function load(target:BackupRunTarget){if(!scope||disposed||state.busy)return false;clear();const owner=epoch,identity={...scope},controller=new AbortController();abort=controller;state={...state,target:structuredClone(target),busy:true};emit()
  try{if(owner!==epoch||disposed||controller.signal.aborted)return false;if(!count(target.pageSize)||target.pageSize<1||target.pageSize>50||target.cursor&&(!uuid(target.cursor.before_run_id)||!Number.isFinite(time(target.cursor.before_queued_at))))throw bad();const query=new URLSearchParams({page_size:String(target.pageSize),...target.cursor});const raw=await client.request(`/assets/${identity.assetId}/backup-runs?${query}`,{method:'GET',signal:controller.signal});if(owner!==epoch||disposed||controller.signal.aborted)return false;state.current=readBackupRunHistory(raw,identity,target);return true
  }catch(error){if(owner===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}finally{if(owner===epoch&&!disposed){state.busy=false;abort=null;emit()}}
 }
 return{reset,first:()=>load({cursor:null,pageSize:20}),retry:()=>state.target?load(structuredClone(state.target)):Promise.resolve(false),next:()=>state.current?.next?load({cursor:structuredClone(state.current.next),pageSize:state.current.page_size}):Promise.resolve(false),cancel(){if(!state.busy)return;const target=state.target;clear();state.target=target;state.cancelled=true;emit()},dispose(){clear();scope=null;disposed=true;emit()}}
}
