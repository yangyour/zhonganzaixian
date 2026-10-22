import{isCancelled,type ApiClient}from'../api/client'
import{monitoringPresets,monitoringIpv4,monitoringConfigDigest,readProbeReport,type MonitoringConfig,type MonitoringTarget,type ProbeReport}from'./monitoringOnboarding'
export interface RegisteredScope{tenantId:string;actorId:string;permissionVersion:number;canTest:boolean}
export interface RegisteredTarget{target_id:string;asset_name:string;configuration:MonitoringConfig;configuration_sha256:string;test_authorized:boolean}
export interface RegisteredState{rows:RegisteredTarget[]|null;busy:'list'|'test'|null;activeId:string|null;report:ProbeReport|null;error:unknown;cancelled:boolean}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const bad=()=>new Error('登记监控配置或测试响应未通过当前范围核对')
function configuration(raw:unknown,scope:RegisteredScope,id:string):MonitoringConfig{
 if(!exact(raw,['version','targets'])||raw.version!==1||!Array.isArray(raw.targets)||raw.targets.length!==1)throw bad()
 const t=raw.targets[0]
 if(!exact(t,['id','tenant_id','asset_id','preset_id','enabled','endpoint','target','auth_profile','interval_seconds','timeout_seconds'])||t.id!==id||t.tenant_id!==scope.tenantId||!uuid(t.asset_id)||typeof t.enabled!=='boolean'||typeof t.endpoint!=='string'||t.endpoint.length>512||!Number.isSafeInteger(t.timeout_seconds)||Number(t.timeout_seconds)<1||Number(t.timeout_seconds)>10)throw bad()
 const p=monitoringPresets.find(p=>p.id===t.preset_id);if(!p)throw bad()
 if(t.interval_seconds!==null&&(!Number.isSafeInteger(t.interval_seconds)||Number(t.interval_seconds)<5||Number(t.interval_seconds)>3600||Number(t.timeout_seconds)>=Number(t.interval_seconds))||t.enabled&&t.interval_seconds===null)throw bad()
 const e=new URL(t.endpoint),port=e.port||(e.protocol==='http:'?'80':e.protocol==='https:'?'443':'');const scheme=p.test_kind==='tcp'?'tcp:':p.test_kind==='configuration_only'?'udp:':null
 if(!monitoringIpv4(e.hostname)||!port||e.username||e.password||e.search||e.hash||(scheme?e.protocol!==scheme:!['http:','https:'].includes(e.protocol))||e.pathname!==(p.path??'')||t.endpoint!==`${e.protocol}//${e.hostname}:${port}${e.pathname}`)throw bad()
 if(['blackbox','snmp'].includes(p.test_kind)){
  if(typeof t.target!=='string'||t.target.length>512||!/^[\x21-\x7e]+$/.test(t.target)||/[\\%]/.test(t.target))throw bad()
  if(p.id==='http-service'||p.id==='tcp-service'){const u=new URL(p.id==='tcp-service'?'tcp://'+t.target:t.target);if(!monitoringIpv4(u.hostname)||u.username||u.password||u.search||u.hash||p.id==='http-service'&&!['http:','https:'].includes(u.protocol)||p.id==='tcp-service'&&(!u.port||u.pathname))throw bad()}else if(!monitoringIpv4(t.target))throw bad()
 }else if(t.target!==null)throw bad()
 if(p.test_kind==='snmp'){if(typeof t.auth_profile!=='string'||!/^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$/.test(t.auth_profile))throw bad()}else if(t.auth_profile!==null)throw bad()
 return structuredClone(raw) as unknown as MonitoringConfig
}
export async function readRegisteredTargets(raw:unknown,scope:RegisteredScope):Promise<RegisteredTarget[]>{
 if(!exact(raw,['tenant_id','actor_id','permission_version','items','source','continuous_collection_verified'])||raw.tenant_id!==scope.tenantId||raw.actor_id!==scope.actorId||raw.permission_version!==scope.permissionVersion||raw.source!=='SERVER_EXPLICIT_CONFIGURATION'||raw.continuous_collection_verified!==false||!Array.isArray(raw.items)||raw.items.length>64)throw bad()
 const ids=new Set<string>(),bindings=new Set<string>(),rows:RegisteredTarget[]=[]
 for(const item of raw.items){if(!exact(item,['target_id','asset_name','configuration','configuration_sha256','test_authorized'])||!uuid(item.target_id)||typeof item.asset_name!=='string'||item.asset_name.length>500||typeof item.configuration_sha256!=='string'||!/^[0-9a-f]{64}$/.test(item.configuration_sha256)||typeof item.test_authorized!=='boolean'||ids.has(item.target_id))throw bad();ids.add(item.target_id);const c=configuration(item.configuration,scope,item.target_id),t=c.targets[0]!,binding=t.asset_id+':'+t.preset_id;if(bindings.has(binding)||await monitoringConfigDigest(c)!==item.configuration_sha256)throw bad();bindings.add(binding);rows.push({...item,configuration:c} as unknown as RegisteredTarget)}return rows
}
export function createRegisteredMonitoring(api:ApiClient,emit:(s:RegisteredState)=>void){
 const empty=():RegisteredState=>({rows:null,busy:null,activeId:null,report:null,error:null,cancelled:false})
 let state=empty(),scope:RegisteredScope|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 function reset(next:RegisteredScope|null){epoch++;abort?.abort();abort=null;scope=next?{...next}:null;state=empty();emit(state)}
 async function load(){if(!scope||disposed||state.busy)return false;const owner={...scope},turn=++epoch;abort=new AbortController();state={...empty(),busy:'list'};emit(state);try{const raw=await api.request('/monitoring-onboarding/registered-targets',{signal:abort.signal});const rows=await readRegisteredTargets(raw,owner);if(disposed||turn!==epoch)return false;state={...state,rows};return true}catch(error){if(!disposed&&turn===epoch&&!isCancelled(error))state={...state,error};return false}finally{if(!disposed&&turn===epoch){state={...state,busy:null};abort=null;emit(state)}}}
 async function probe(id:string){if(!scope?.canTest||disposed||state.busy)return false;const row=state.rows?.find(r=>r.target_id===id);if(!row?.test_authorized)return false;const owner={...scope},turn=++epoch;abort=new AbortController();state={...state,busy:'test',activeId:id,report:null,error:null,cancelled:false};emit(state);try{const raw=await api.request('/monitoring-onboarding/registered-targets/'+id+'/test',{method:'POST',body:{configuration_sha256:row.configuration_sha256},signal:abort.signal,timeoutMs:15000});if(disposed||turn!==epoch)return false;if(!exact(raw,['tenant_id','actor_id','permission_version','report','source'])||raw.tenant_id!==owner.tenantId||raw.actor_id!==owner.actorId||raw.permission_version!==owner.permissionVersion||raw.source!=='SERVER_ONE_SHOT_PROBE')throw bad();const report=readProbeReport(raw.report,row.configuration,row.configuration_sha256);state={...state,report};return true}catch(error){if(!disposed&&turn===epoch&&!isCancelled(error))state={...state,error};return false}finally{if(!disposed&&turn===epoch){state={...state,busy:null};abort=null;emit(state)}}}
 function cancel(){if(!state.busy)return;epoch++;abort?.abort();abort=null;state={...state,busy:null,report:null,error:null,cancelled:true};emit(state)}
 emit(state);return{reset,load,probe,cancel,dispose(){disposed=true;reset(null)}}
}
