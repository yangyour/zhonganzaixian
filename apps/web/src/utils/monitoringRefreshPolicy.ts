import { computed, onMounted, onUnmounted, ref, shallowRef, watch, type ComputedRef } from 'vue'
import { api, isCancelled, ApiError } from '../api/client'
import { session, contextGeneration, refreshProjectSession } from '../stores/session'
import { utcInstantNanos } from './utcInstant'
export interface MonitoringRefreshPolicy { tenant_id:string;actor_id:string;permission_version:number;configuration_key:'monitor.page_refresh_sec';interval_seconds:number|null;auto_refresh_enabled:boolean;source:'CONTRACT_DEFAULT'|'RUNTIME_PUBLICATION'|'RUNTIME_BLOCKED';reason:null|'DISABLED'|'EXPIRED'|'DEFINITION_CHANGED'|'INVALID_VALUE';publication_id:string|null;publication_version:number|null;checked_at:string;expires_at:string|null;definition_hash:string;production_approved:false }
const uuid=(v:unknown)=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
export function readMonitoringRefreshPolicy(raw:unknown,scope:{tenantId:string;actorId:string;permissionVersion:number}):MonitoringRefreshPolicy{
 const p=raw as MonitoringRefreshPolicy,keys=['tenant_id','actor_id','permission_version','configuration_key','interval_seconds','auto_refresh_enabled','source','reason','publication_id','publication_version','checked_at','expires_at','definition_hash','production_approved']
 const invalid=()=>{throw new ApiError(0,'INVALID_REFRESH_POLICY','刷新配置未通过授权或状态核对，自动刷新已暂停','—')}
 if(!p||typeof p!=='object'||Object.keys(p).length!==keys.length||keys.some(k=>!Object.hasOwn(p,k))||p.tenant_id!==scope.tenantId||p.actor_id!==scope.actorId||p.permission_version!==scope.permissionVersion||p.configuration_key!=='monitor.page_refresh_sec'||p.production_approved!==false||typeof p.definition_hash!=='string'||!/^[a-f0-9]{64}$/.test(p.definition_hash)||utcInstantNanos(p.checked_at)===null||p.expires_at!==null&&utcInstantNanos(p.expires_at)===null)invalid()
 if(p.source==='CONTRACT_DEFAULT'){if(p.interval_seconds!==30||p.auto_refresh_enabled!==true||p.reason!==null||p.publication_id!==null||p.publication_version!==null||p.expires_at!==null)invalid()}
 else if(['RUNTIME_PUBLICATION','RUNTIME_BLOCKED'].includes(p.source)){if(!uuid(p.publication_id)||!Number.isSafeInteger(p.publication_version)||Number(p.publication_version)<1)invalid();if(p.source==='RUNTIME_PUBLICATION'?(p.auto_refresh_enabled!==true||!Number.isInteger(p.interval_seconds)||Number(p.interval_seconds)<1||Number(p.interval_seconds)>3600||p.reason!==null):(p.auto_refresh_enabled!==false||p.interval_seconds!==null||!['DISABLED','EXPIRED','DEFINITION_CHANGED','INVALID_VALUE'].includes(p.reason??'')))invalid()}
 else invalid()
 return structuredClone(p)
}
/** Poll only policy metadata every 30s, independently of whether data auto-refresh is enabled. */
export const refreshPermissionRejected=(e:unknown)=>e instanceof ApiError&&([401,403].includes(e.status)||e.code==='INVALID_REFRESH_POLICY')
export function useMonitoringRefreshPolicy(permitted:ComputedRef<boolean>){
 const snapshot=shallowRef<MonitoringRefreshPolicy|null>(null),error=shallowRef<unknown>(null),busy=ref(false),clock=ref(performance.now()),loaded=ref(0)
 let epoch=0,alive=true,abort:AbortController|null=null,timer:ReturnType<typeof setInterval>|undefined,ticks=0
 const expired=computed(()=>!!snapshot.value&&(clock.value-loaded.value>90000||snapshot.value.expires_at!==null&&Date.parse(snapshot.value.checked_at)+Math.max(0,clock.value-loaded.value)>=Date.parse(snapshot.value.expires_at)))
 const intervalSeconds=computed(()=>!error.value&&!expired.value&&snapshot.value?.auto_refresh_enabled?snapshot.value.interval_seconds:null)
 const label=computed(()=>error.value?'刷新设置读取失败，自动刷新暂停':!snapshot.value?'正在读取刷新设置':expired.value?'刷新设置已过期，自动刷新暂停':snapshot.value.source==='RUNTIME_BLOCKED'?`自动刷新暂停：${({DISABLED:'已禁用',EXPIRED:'已到期',DEFINITION_CHANGED:'配置定义已变更',INVALID_VALUE:'值不符合刷新要求'} as const)[snapshot.value.reason as 'DISABLED']}`:`每${snapshot.value.interval_seconds}秒 · ${snapshot.value.source==='CONTRACT_DEFAULT'?'合同默认':'运行配置 v'+snapshot.value.publication_version}`)
 async function refresh(){const s=session.value;if(!alive||!permitted.value||!s||busy.value)return;const scope={tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version},turn=++epoch;abort=new AbortController();busy.value=true;try{const raw=await api.request<unknown>('/network-monitoring/refresh-policy',{signal:abort.signal});if(!alive||turn!==epoch)return;snapshot.value=readMonitoringRefreshPolicy(raw,scope);loaded.value=performance.now();clock.value=loaded.value;error.value=null}catch(e){if(alive&&turn===epoch&&!isCancelled(e)){snapshot.value=null;error.value=e;if(refreshPermissionRejected(e)&&['project_local','oidc'].includes(session.value?.identity_source??''))void refreshProjectSession().catch(()=>{})}}finally{if(alive&&turn===epoch){busy.value=false;abort=null}}}
 function reset(){++epoch;abort?.abort();abort=null;snapshot.value=null;error.value=null;busy.value=false;loaded.value=0;ticks=0}
 const contextKey=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,contextGeneration.value,permitted.value]))
 watch(contextKey,()=>{reset();if(permitted.value)void refresh()},{immediate:true,flush:'sync'})
 function visible(){clock.value=performance.now();if(!document.hidden)void refresh()}
 onMounted(()=>{timer=setInterval(()=>{clock.value=performance.now();if(++ticks%30===0&&!document.hidden)void refresh()},1000);document.addEventListener('visibilitychange',visible)})
 onUnmounted(()=>{alive=false;reset();clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
 return{intervalSeconds,label,error,refresh}
}
