export const metricNames = ['icmp','loss','latency','tcp_22','tcp_80','tcp_443'] as const
export type Metric = typeof metricNames[number]
export interface Sample {state:'FRESH'|'STALE'|'NO_DATA'|'COLLECTOR_ERROR'|'DISABLED'|'CLOCK_SKEW';value:string|null;unit:string;recorded_at:string|null}
export interface Device {asset_id:string;name:string;management_ip:string;brand:string;model:string;network_state:'REACHABLE'|'NO_RESPONSE'|'UNKNOWN';metrics:Record<Metric,Sample>;sample_interval_seconds:60;freshness_seconds:180;vendor_metrics_status:'DOCUMENTATION_PENDING'|'PARTIAL_API';device_identity_verified:false;alert_rule_status:'CONFIG_PENDING'}
export interface NetworkSnapshot {tenant_id:string;generated_at:string;devices:Device[];source:'ZABBIX_SIMPLE_CHECK';scope:'CURRENT_TENANT_EXPLICIT_TEST_BINDINGS';refresh_seconds:30}
export interface HistoryPoint {recorded_at:string;value:string}
export interface NetworkHistory {asset_id:string;metric:Metric;unit:string;window:'HOUR_1'|'HOURS_24';from:string;to:string;points:HistoryPoint[];sample_interval_seconds:60;gap_after_seconds:180;timestamp_meaning:'ZABBIX_RECORD_TIME';complete_window_claimed:false;source:'ZABBIX_SIMPLE_CHECK'}
const states=['FRESH','STALE','NO_DATA','COLLECTOR_ERROR','DISABLED','CLOCK_SKEW']
const number=/^(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,40})?$/
function invalid():never {throw new Error('监控返回数据不符合当前查询，请重新读取')}
function validValue(metric:Metric,value:string){return number.test(value)&&Number(value)<=(metric==='loss'?100:metric==='latency'?600:1)&&(metric==='loss'||metric==='latency'||value==='0'||value==='1')}
export function readSnapshot(value:NetworkSnapshot,tenant:string):NetworkSnapshot {
  if(!value||value.tenant_id!==tenant||value.source!=='ZABBIX_SIMPLE_CHECK'||!Number.isFinite(Date.parse(value.generated_at))||!Array.isArray(value.devices)||value.devices.length>16)invalid()
  const ids=new Set<string>()
  for(const d of value.devices){
    if(!d||typeof d.asset_id!=='string'||ids.has(d.asset_id)||typeof d.name!=='string'||typeof d.management_ip!=='string'||!['REACHABLE','NO_RESPONSE','UNKNOWN'].includes(d.network_state)||d.freshness_seconds!==180||d.sample_interval_seconds!==60||d.device_identity_verified!==false||!['DOCUMENTATION_PENDING','PARTIAL_API'].includes(d.vendor_metrics_status))invalid()
    ids.add(d.asset_id)
    for(const m of metricNames){const s=d.metrics?.[m];if(!s||!states.includes(s.state)||s.unit!==(m==='latency'?'s':m==='loss'?'%':'')||s.recorded_at!==null&&!Number.isFinite(Date.parse(s.recorded_at))||s.state==='FRESH'&&(s.recorded_at===null||typeof s.value!=='string'||!validValue(m,s.value))||s.state!=='FRESH'&&s.value!==null)invalid()}
    const expected=d.metrics.icmp.state==='FRESH'?(d.metrics.icmp.value==='1'?'REACHABLE':'NO_RESPONSE'):'UNKNOWN'
    if(d.network_state!==expected)invalid()
  }
  return value
}
export function readHistory(value:NetworkHistory,asset:string,metric:Metric,window:string):NetworkHistory {
  if(!value||value.asset_id!==asset||value.metric!==metric||value.window!==window||value.source!=='ZABBIX_SIMPLE_CHECK'||value.complete_window_claimed!==false||!Array.isArray(value.points)||value.points.length>2000||value.gap_after_seconds!==180)invalid()
  const from=Date.parse(value.from),to=Date.parse(value.to)
  if(!Number.isFinite(from)||!Number.isFinite(to)||from>=to)invalid()
  let previous=-Infinity
  for(const point of value.points){const at=Date.parse(point.recorded_at);if(!Number.isFinite(at)||at<from||at>=to+1000||at<previous||typeof point.value!=='string'||!validValue(metric,point.value))invalid();previous=at}
  return value
}
export function sampleText(d:Device,metric:Metric,locallyExpired=false):string {
  const s=d.metrics[metric]
  if(locallyExpired)return '数据过期'
  if(s.state!=='FRESH')return ({STALE:'数据过期',NO_DATA:'等待采样',COLLECTOR_ERROR:'采集异常',DISABLED:'采集停用',CLOCK_SKEW:'时钟异常'} as const)[s.state]
  if(metric==='latency')return d.metrics.icmp.state==='FRESH'&&d.metrics.icmp.value==='1'&&Number(s.value)>0?`${(Number(s.value)*1000).toFixed(2)} ms`:'—（未确认响应）'
  if(metric==='loss')return `${Number(s.value).toFixed(1)}%`
  return s.value==='1'?(metric==='icmp'?'可达':'开放'):(metric==='icmp'?'无回应':'未建立连接')
}
/** Explicit time window, isolated dots/gaps, and no line from provider's zero timeout latency. */
export function historySegments(history:NetworkHistory|ResourceHistory):{line:string;dots:{x:number;y:number}[]}[] {
  const start=Date.parse(history.from),duration=Date.parse(history.to)+1000-start
  const maximum=['loss','cpu','memory'].includes(history.metric)?100:history.metric==='latency'?Math.max(...history.points.map(p=>Number(p.value)),0.001):1
  const groups:{x:number;y:number}[][]=[];let current:{x:number;y:number}[]=[];let previous=0
  for(const p of history.points){const at=Date.parse(p.recorded_at),value=Number(p.value)
    if(at-previous>history.gap_after_seconds*1000||history.metric==='latency'&&value===0){if(current.length)groups.push(current);current=[]}
    if(!(history.metric==='latency'&&value===0))current.push({x:16+(at-start)/duration*688,y:154-value/maximum*130})
    previous=at
  }
  if(current.length)groups.push(current)
  return groups.map(dots=>({line:dots.map(d=>`${d.x},${d.y}`).join(' '),dots}))
}
export type ResourceMetric='cpu'|'memory'
export type HistoryMetric=Metric|ResourceMetric
export interface ResourceHistory extends Omit<NetworkHistory,'metric'|'source'|'timestamp_meaning'|'gap_after_seconds'> {metric:ResourceMetric;source:'SANGFOR_AF_API';timestamp_meaning:'COLLECTOR_RECEIPT_TIME';gap_after_seconds:90}
export interface ResourceSnapshot {asset_id:string;management_ip:string;generated_at:string;state:'FRESH'|'STALE'|'NO_DATA'|'COLLECTOR_ERROR'|'CLOCK_SKEW';reason:string|null;metrics:Record<ResourceMetric,{value:string|null;unit:'%'}>;received_at:string|null;source_sampled_at:null;source:'SANGFOR_AF_API';timestamp_meaning:'COLLECTOR_RECEIPT_TIME';sample_interval_seconds:60;freshness_seconds:180;tls_mode:'SYSTEM'|'CA_FILE'|'TEST_INSECURE';tls_trust_verified:false;alert_rule_status:'CONFIG_PENDING'}
function validPercent(value:unknown):value is string{return typeof value==='string'&&number.test(value)&&Number(value)<=100}
export function readResources(v:ResourceSnapshot,asset:string):ResourceSnapshot{
  if(!v||v.asset_id!==asset||v.management_ip!=='192.169.11.1'||v.source!=='SANGFOR_AF_API'||v.timestamp_meaning!=='COLLECTOR_RECEIPT_TIME'||v.source_sampled_at!==null||v.sample_interval_seconds!==60||v.freshness_seconds!==180||v.tls_trust_verified!==false||!['SYSTEM','CA_FILE','TEST_INSECURE'].includes(v.tls_mode)||!['FRESH','STALE','NO_DATA','COLLECTOR_ERROR','CLOCK_SKEW'].includes(v.state)||!Number.isFinite(Date.parse(v.generated_at)))invalid()
  if(v.reason!==null&&(typeof v.reason!=='string'||v.reason.length>128)||v.received_at!==null&&!Number.isFinite(Date.parse(v.received_at)))invalid()
  for(const m of ['cpu','memory'] as const){const s=v.metrics?.[m];if(!s||s.unit!=='%'||(v.state==='FRESH'?!validPercent(s.value):s.value!==null))invalid()}
  if(v.state==='FRESH'&&(v.received_at===null||v.reason!==null||Date.parse(v.generated_at)-Date.parse(v.received_at)<0||Date.parse(v.generated_at)-Date.parse(v.received_at)>180000))invalid()
  return v
}
export function readResourceHistory(v:ResourceHistory,asset:string,metric:ResourceMetric,window:string):ResourceHistory{
  if(!v||v.asset_id!==asset||v.metric!==metric||v.window!==window||v.source!=='SANGFOR_AF_API'||v.timestamp_meaning!=='COLLECTOR_RECEIPT_TIME'||v.unit!=='%'||v.sample_interval_seconds!==60||v.gap_after_seconds!==90||v.complete_window_claimed!==false||!Array.isArray(v.points)||v.points.length>2000)invalid()
  const from=Date.parse(v.from),to=Date.parse(v.to);if(!Number.isFinite(from)||!Number.isFinite(to)||from>=to)invalid();let prior=-Infinity
  for(const p of v.points){const at=Date.parse(p.recorded_at);if(!Number.isFinite(at)||at<from||at>=to+1000||at<prior||!validPercent(p.value))invalid();prior=at}return v
}
