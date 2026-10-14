export function addNetworkMonitoringContract(contract) {
  const str={type:'string'},nullable={type:['string','null']},uuid={type:'string',format:'uuid'}
  const obj=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false})
  const metricNames=['icmp','loss','latency','tcp_22','tcp_80','tcp_443']
  const sample=obj({state:{enum:['FRESH','STALE','NO_DATA','COLLECTOR_ERROR','DISABLED','CLOCK_SKEW']},value:nullable,unit:{enum:['','s','%']},recorded_at:{type:['string','null'],format:'date-time'}})
  sample.allOf=[{if:{properties:{state:{const:'FRESH'}}},then:{properties:{value:{type:'string',pattern:'^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$'},recorded_at:{type:'string'}}},else:{properties:{value:{type:'null'}}}}]
  const device=obj({asset_id:uuid,name:str,management_ip:{type:'string',format:'ipv4'},brand:str,model:str,network_state:{enum:['REACHABLE','NO_RESPONSE','UNKNOWN']},metrics:obj(Object.fromEntries(metricNames.map(k=>[k,sample]))),sample_interval_seconds:{const:60},freshness_seconds:{const:180},vendor_metrics_status:{const:'DOCUMENTATION_PENDING'},device_identity_verified:{const:false},alert_rule_status:{const:'CONFIG_PENDING'}})
  const list=obj({tenant_id:uuid,generated_at:{type:'string',format:'date-time'},devices:{type:'array',maxItems:16,items:device},source:{const:'ZABBIX_SIMPLE_CHECK'},scope:{const:'CURRENT_TENANT_EXPLICIT_TEST_BINDINGS'},refresh_seconds:{const:30}})
  const history=obj({asset_id:uuid,metric:{enum:metricNames},unit:{enum:['','s','%']},window:{enum:['HOUR_1','HOURS_24']},from:{type:'string',format:'date-time'},to:{type:'string',format:'date-time'},points:{type:'array',maxItems:2000,items:obj({recorded_at:{type:'string',format:'date-time'},value:{type:'string',pattern:'^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$'}})},sample_interval_seconds:{const:60},gap_after_seconds:{const:180},timestamp_meaning:{const:'ZABBIX_RECORD_TIME'},complete_window_claimed:{const:false},source:{const:'ZABBIX_SIMPLE_CHECK'}})
  const envelope=data=>obj({data,trace_id:str}),ref=name=>({$ref:'#/components/schemas/'+name})
  Object.assign(contract.components.schemas,{NetworkMonitoringSnapshot:list,NetworkMonitoringResult:envelope(ref('NetworkMonitoringSnapshot')),NetworkMonitoringHistory:history,NetworkMonitoringHistoryResult:envelope(ref('NetworkMonitoringHistory'))})
  const action=(id,summary,schema,parameters=[])=>({operationId:id,summary,parameters,'x-mop-capability':'assets:read','x-mop-read-only':true,'x-mop-current-membership-recheck':true,'x-mop-provider-ids-accepted':false,responses:{'200':{description:'Current tenant authorized basic probe data',content:{'application/json':{schema:ref(schema)}}},'400':{description:'Unknown/duplicate query or invalid metric/window'},'401':{description:'Expired or changed membership'},'403':{description:'Missing assets:read'},'404':{description:'Unbound, wrong IP or cross-tenant asset'},'422':{description:'History exceeds 2000 points; no truncation'},'503':{description:'Not configured, provider unavailable, mapping drift or malformed source; no fallback'}}})
  contract.paths['/network-monitoring']={get:action('listBasicNetworkMonitoring','当前租户明确测试绑定的IP基础监控；网络响应不代表设备身份或正式健康','NetworkMonitoringResult')}
  contract.paths['/network-monitoring/{assetId}/history']={get:action('readBasicNetworkHistory','单资产单指标有界历史，保留空窗及纳秒来源时刻','NetworkMonitoringHistoryResult',[
    {name:'assetId',in:'path',required:true,schema:uuid},
    {name:'metric',in:'query',schema:{enum:metricNames,default:'latency'}},
    {name:'window',in:'query',schema:{enum:['HOUR_1','HOURS_24'],default:'HOUR_1'}}])}
  for (const path of ['/network-monitoring','/network-monitoring/{assetId}/history']) {
    for (const [status, response] of Object.entries(contract.paths[path].get.responses)) {
      if (status !== '200') response.content = { 'application/json': { schema: ref('Error') } }
    }
  }
  contract.info.version='0.46.0'
}
