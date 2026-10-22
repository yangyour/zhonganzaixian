import {digestSha256} from './browserCrypto'
import catalog from '../../../../config/monitoring/presets.v1.json'
export type Preset = typeof catalog.presets[number]
export const monitoringPresets = catalog.presets
export interface MonitoringDraft { assetId:string;presetId:string;host:string;port:string;scheme:'http'|'https';targetScheme:'http'|'https';targetHost:string;targetPort:string;httpPath:string;authProfile:string;interval:string;timeout:string;enabled:boolean }
export interface MonitoringTarget { id:string;tenant_id:string;asset_id:string;preset_id:string;enabled:boolean;endpoint:string;target:string|null;auth_profile:string|null;interval_seconds:number|null;timeout_seconds:number }
export interface MonitoringConfig {version:1;targets:MonitoringTarget[]}
export function emptyMonitoringDraft():MonitoringDraft{return{assetId:'',presetId:'linux-node',host:'',port:'9100',scheme:'http',targetScheme:'https',targetHost:'',targetPort:'',httpPath:'/',authProfile:'',interval:'',timeout:'5',enabled:false}}
const uuid=(s:string)=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s)
export function monitoringIpv4(value:string):boolean {if(!/^(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}$/.test(value))return false;const a=value.split('.').map(Number);return a.every(v=>v<=255)&&value!=='0.0.0.0'&&!(a[0]!>=224&&a[0]!<=239)&&!(a[0]===169&&a[1]===254)}
function integer(s:string,min:number,max:number,label:string):number{if(!/^(0|[1-9]\d*)$/.test(s))throw Error(label+'必须为明确整数');const n=Number(s);if(!Number.isSafeInteger(n)||n<min||n>max)throw Error(label+`须为 ${min}–${max}`);return n}
export function buildMonitoringConfig(d:MonitoringDraft,tenantId:string,id:string):MonitoringConfig{
 if(!['http','https'].includes(d.scheme)||!['http','https'].includes(d.targetScheme)||typeof d.enabled!=='boolean')throw Error('协议或启用状态不正确')
 if(!uuid(tenantId)||!uuid(id)||!uuid(d.assetId))throw Error('租户、接入标识和资产 ID 必须有效')
 const p=monitoringPresets.find(x=>x.id===d.presetId);if(!p)throw Error('请选择受支持的预设')
 if(!monitoringIpv4(d.host))throw Error('采集端点须为明确 IPv4，不接受域名、链路本地或组播地址')
 const port=integer(d.port,1,65535,'端口'),timeout=integer(d.timeout,1,10,'超时秒数'),interval=d.interval===''?null:integer(d.interval,5,3600,'采集周期秒数')
 if(d.enabled&&interval===null)throw Error('启用采集配置前必须填写周期')
 if(interval!==null&&timeout>=interval)throw Error('超时必须小于采集周期')
 let target:string|null=null,auth:string|null=null
 if(p.test_kind==='blackbox'||p.test_kind==='snmp'){
  if(!monitoringIpv4(d.targetHost))throw Error('请填写明确的下游目标 IPv4')
  target=d.targetHost
  if(p.id==='http-service'){
   if(!/^\/[a-zA-Z0-9/_.-]*$/.test(d.httpPath)||d.httpPath.length>200)throw Error('HTTP 路径只接受 /、字母、数字、下划线、点和短横线')
   target=`${d.targetScheme}://${d.targetHost}:${integer(d.targetPort,1,65535,'下游端口')}${d.httpPath}`
  }else if(p.id==='tcp-service')target=`${d.targetHost}:${integer(d.targetPort,1,65535,'下游端口')}`
  if(p.test_kind==='snmp'){if(!/^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$/.test(d.authProfile))throw Error('请填写采集侧已配置的 SNMP 认证配置名，不填写密码');auth=d.authProfile}
 }
 const scheme=p.test_kind==='tcp'?'tcp':p.test_kind==='configuration_only'?'udp':d.scheme
 return{version:1,targets:[{id,tenant_id:tenantId,asset_id:d.assetId,preset_id:p.id,enabled:d.enabled,endpoint:`${scheme}://${d.host}:${port}${p.path??''}`,target,auth_profile:auth,interval_seconds:interval,timeout_seconds:timeout}]}
}
export function monitoringTestCommand(config:MonitoringConfig):string {const t=config.targets[0]!;return `python tools/monitoring_presets.py test --config monitoring-target-${t.id}.json --target-id ${t.id} --allow-endpoint '${t.endpoint}'${t.target===null?'':` --allow-target '${t.target}'`}`}
function canonical(value:unknown):string {if(value===null||typeof value!=='object')return JSON.stringify(value);if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical((value as Record<string,unknown>)[k])).join(',')+'}'}
export async function monitoringConfigDigest(config:MonitoringConfig):Promise<string>{const bytes=new TextEncoder().encode(canonical(config));return [...new Uint8Array(await digestSha256(bytes))].map(x=>x.toString(16).padStart(2,'0')).join('')}
export interface ProbeReport {target_id:string;configuration_sha256:string;probe_status:'NOT_RUN'|'FAILED'|'TCP_REACHABLE_PROTOCOL_UNVERIFIED'|'SAMPLE_VERIFIED_ONCE';continuous_collection_verified:false;device_identity_verified:false;health_verified:false;sample_count:number|null;matched_metrics:string[];reason?:string;metric_values_stored?:false}
export function readProbeReport(raw:unknown,config:MonitoringConfig,digest:string):ProbeReport {
 if(!raw||typeof raw!=='object'||Array.isArray(raw))throw Error('测试报告格式不正确')
 const r=raw as Record<string,unknown>,base=['target_id','configuration_sha256','probe_status','continuous_collection_verified','device_identity_verified','health_verified','sample_count','matched_metrics'],allowed=new Set([...base,'reason','metric_values_stored'])
 if(base.some(k=>!(k in r))||Object.keys(r).some(k=>!allowed.has(k)))throw Error('测试报告字段不受支持')
 if(r.target_id!==config.targets[0]!.id||r.configuration_sha256!==digest)throw Error('测试报告不属于当前配置，请使用当前配置重新测试')
 if(!['NOT_RUN','FAILED','TCP_REACHABLE_PROTOCOL_UNVERIFIED','SAMPLE_VERIFIED_ONCE'].includes(String(r.probe_status)))throw Error('测试状态不受支持')
 if(r.continuous_collection_verified!==false||r.device_identity_verified!==false||r.health_verified!==false||r.metric_values_stored!==undefined&&r.metric_values_stored!==false)throw Error('单次报告不能证明持续采集、设备身份或健康')
 const p=monitoringPresets.find(p=>p.id===config.targets[0]!.preset_id)!
 if(!Array.isArray(r.matched_metrics)||r.matched_metrics.some(k=>typeof k!=='string'||!new Set<string>(p.required_any_metrics).has(k))||new Set(r.matched_metrics).size!==r.matched_metrics.length)throw Error('报告指标不匹配预设')
 if(r.reason!==undefined&&(typeof r.reason!=='string'||!/^[A-Z_]{1,100}$/.test(r.reason)))throw Error('报告原因格式不正确')
 if(r.probe_status==='SAMPLE_VERIFIED_ONCE'){
  if(p.engine!=='prometheus'||!Number.isSafeInteger(r.sample_count)||Number(r.sample_count)<1||Number(r.sample_count)>100000||!r.matched_metrics.length||r.metric_values_stored!==false||r.reason!==undefined)throw Error('样本核验报告不完整')
 }else if(r.sample_count!==null||r.matched_metrics.length||r.metric_values_stored!==undefined||typeof r.reason!=='string')throw Error('未核验报告不能包含成功样本')
 if(r.probe_status==='TCP_REACHABLE_PROTOCOL_UNVERIFIED'&&p.test_kind!=='tcp')throw Error('TCP 报告不属于此预设')
 return structuredClone(r) as unknown as ProbeReport
}
