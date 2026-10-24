import { api, ApiError } from '../api/client'
import { createRequestId } from './browserCrypto'
import { createDurableCommand } from './durableCommand'
export interface Enrollment {
 state:{version:number;phase:'REQUESTED'|'APPROVED'|'REGISTERED_DISABLED'|'CANCELLED';reason:string|null;reviewer:string|null;approval_policy:string|null;updated_at:string;
 document:{id:string;tenant_id:string;created_by:string;created_at:string;catalog_sha256:string;configuration_sha256:string;configuration:string;assets:{id:string;version:number;management_ip:string}[]};
 last_probe:{target_id:string;configuration_sha256:string;status:string;sample_count:number|null;matched_metrics:string[];observed_at:string;reason:string|null}|null}
 targets:{target_id:string;configuration_sha256:string;configuration:{version:1;targets:Record<string,unknown>[]};test_authorized:boolean}[]
 catalog_current:boolean;external_configuration_applied:false;continuous_collection_verified:false;device_identity_verified:false;health_verified:false
}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const version=(v:unknown)=>Number.isSafeInteger(v)&&(v as number)>=1&&(v as number)<=9007199254740990
const invalid=()=>new ApiError(200,'INVALID_MONITORING_ENROLLMENT','监控登记范围、停用状态或原请求回执未通过核对','—')
export function readEnrollment(raw:unknown,tenant:string,id?:string):Enrollment{
 if(!object(raw)||typeof raw.catalog_current!=='boolean'||!object(raw.state)||!object(raw.state.document)||!Array.isArray(raw.targets)||raw.targets.length<1||raw.targets.length>64)throw invalid()
 for(const flag of ['external_configuration_applied','continuous_collection_verified','device_identity_verified','health_verified'])if(raw[flag]!==false)throw invalid()
 const s=raw.state,d=s.document
 if(!object(d))throw invalid()
 if(!uuid(d.id)||d.tenant_id!==tenant||id!==undefined&&d.id!==id||!uuid(d.created_by)||!hash(d.catalog_sha256)||!hash(d.configuration_sha256)||typeof d.configuration!=='string'||!Array.isArray(d.assets)||!version(s.version)||!['REQUESTED','APPROVED','REGISTERED_DISABLED','CANCELLED'].includes(String(s.phase)))throw invalid()
 const assets=d.assets
 for(const asset of assets)if(!object(asset)||!uuid(asset.id)||!version(asset.version)||typeof asset.management_ip!=='string')throw invalid()
 const ids=new Set<string>();for(const target of raw.targets){if(!object(target)||!uuid(target.target_id)||!hash(target.configuration_sha256)||typeof target.test_authorized!=='boolean'||!object(target.configuration)||target.configuration.version!==1||!Array.isArray(target.configuration.targets)||target.configuration.targets.length!==1)throw invalid()
  const row=target.configuration.targets[0];if(!object(row)||row.enabled!==false||row.id!==target.target_id||row.tenant_id!==tenant||ids.has(target.target_id)||!assets.some(a=>object(a)&&a.id===row.asset_id)||target.test_authorized&&s.phase!=='REGISTERED_DISABLED')throw invalid();ids.add(target.target_id)
 }
 if(s.last_probe!==null){const p=s.last_probe;if(!object(p)||!uuid(p.target_id)||!ids.has(p.target_id)||!hash(p.configuration_sha256)||!['NOT_RUN','FAILED','TCP_REACHABLE_PROTOCOL_UNVERIFIED','SAMPLE_VERIFIED_ONCE'].includes(String(p.status))||!Array.isArray(p.matched_metrics))throw invalid()}
 return raw as unknown as Enrollment
}
interface Command{key:string;path:string;body:Record<string,unknown>}
export function createEnrollmentCommands(scope:()=>{key:string;tenant:string},storage:Pick<Storage,'getItem'|'setItem'|'removeItem'>,client:Pick<typeof api,'request'>=api){
 const commands=createDurableCommand<Command>({scope:()=>scope().key,prefix:'mop.monitoring-enrollment.pending:',storage,
 validate(raw){if(!object(raw)||!uuid(raw.key)||typeof raw.path!=='string'||!object(raw.body))throw invalid();const b=raw.body
  if(raw.path==='/monitoring-onboarding/enrollments'){if(Object.keys(b).sort().join(',')!=='expected_catalog_sha256,expected_configuration_sha256,plan_request'||!hash(b.expected_catalog_sha256)||!hash(b.expected_configuration_sha256)||!object(b.plan_request))throw invalid()}
  else {if(!/^\/monitoring-onboarding\/enrollments\/[a-f0-9-]{36}\/(actions|probe)$/.test(raw.path)||!uuid(raw.path.split('/')[3])||!version(b.expected_version)||Number(b.expected_version)>=9007199254740990)throw invalid()
   if(raw.path.endsWith('/probe')){if(Object.keys(b).sort().join(',')!=='configuration_sha256,expected_version,target_id'||!uuid(b.target_id)||!hash(b.configuration_sha256))throw invalid()}
   else if(Object.keys(b).sort().join(',')!=='action,expected_version,reason'||!['APPROVE','REGISTER_DISABLED','CANCEL'].includes(String(b.action))||(b.reason!==null&&(typeof b.reason!=='string'||b.reason.length>1000)))throw invalid()
  }return raw as unknown as Command},
 send:(c,signal)=>client.request(c.path,{method:'POST',body:c.body,idempotencyKey:c.key,signal}),
 acknowledge(raw,c){const id=c.path==='/monitoring-onboarding/enrollments'?undefined:c.path.split('/')[3];const result=readEnrollment(raw,scope().tenant,id)
  if(!id){if(result.state.version!==1||result.state.phase!=='REQUESTED'||result.state.document.configuration_sha256!==c.body.expected_configuration_sha256||result.state.document.catalog_sha256!==c.body.expected_catalog_sha256)throw invalid()}
  else {if(result.state.version!==Number(c.body.expected_version)+1)throw invalid();if(c.path.endsWith('/probe')){if(result.state.phase!=='REGISTERED_DISABLED'||result.state.last_probe?.target_id!==c.body.target_id||result.state.last_probe?.configuration_sha256!==c.body.configuration_sha256)throw invalid()}
   else if(result.state.phase!==({APPROVE:'APPROVED',REGISTER_DISABLED:'REGISTERED_DISABLED',CANCEL:'CANCELLED'} as Record<string,string>)[String(c.body.action)])throw invalid()}
 }})
 return{...commands,send:(path:string,body:Record<string,unknown>)=>commands.submit({key:createRequestId(),path,body})}
}

/** Only asset/profile parameters go to the server planner; local URLs, credentials and enabled flags never do. */
export function enrollmentPlanRequest(draft:import('./monitoringOnboarding').MonitoringDraft,collector:string,presets:readonly {id:string;test_kind:string}[]){
 if(!uuid(draft.assetId))throw new Error('请先选择有效资产 ID')
 const preset=presets.find(p=>p.id===draft.presetId);if(!preset)throw new Error('场景不受支持')
 const number=(s:string,min:number,max:number)=>{if(!/^[0-9]+$/.test(s)||!Number.isSafeInteger(Number(s))||Number(s)<min||Number(s)>max)throw new Error('端口、周期或超时须为明确有效整数');return Number(s)}
 const profile:Record<string,unknown>={preset_id:preset.id}
 if(['metrics','tcp','configuration_only'].includes(preset.test_kind))profile.port=number(draft.port,1,65535)
 if(preset.test_kind==='metrics')profile.scheme=draft.scheme
 if(['blackbox','snmp'].includes(preset.test_kind)){if(!/^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$/.test(collector))throw new Error('请填写服务器已配置的采集器名称');profile.collector=collector}
 if(preset.id==='http-service'){profile.scheme=draft.targetScheme;profile.path=draft.httpPath;if(draft.targetPort)profile.port=number(draft.targetPort,1,65535)}
 if(preset.id==='tcp-service')profile.port=number(draft.targetPort,1,65535)
 return{version:1,assets:[{asset_id:draft.assetId,profiles:[profile]}],defaults:{interval_seconds:draft.interval?number(draft.interval,5,3600):null,timeout_seconds:number(draft.timeout,1,10),exporter_scheme:draft.scheme}}
}
