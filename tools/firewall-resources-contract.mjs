export function addFirewallResourcesContract(c){
  const obj=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false})
  const nullable={type:['string','null']},date={type:'string',format:'date-time'},uuid={type:'string',format:'uuid'}
  const metric=obj({value:nullable,unit:{const:'%'}})
  const snapshot=obj({asset_id:uuid,management_ip:{const:'192.169.11.1'},generated_at:date,state:{enum:['FRESH','STALE','NO_DATA','COLLECTOR_ERROR','CLOCK_SKEW']},reason:nullable,metrics:obj({cpu:metric,memory:metric}),received_at:{type:['string','null'],format:'date-time'},source_sampled_at:{const:null},source:{const:'SANGFOR_AF_API'},timestamp_meaning:{const:'COLLECTOR_RECEIPT_TIME'},sample_interval_seconds:{const:60},freshness_seconds:{const:180},tls_mode:{enum:['SYSTEM','CA_FILE','TEST_INSECURE']},tls_trust_verified:{const:false},alert_rule_status:{const:'CONFIG_PENDING'}})
  const values={type:'string',pattern:'^(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,12})?$'}
  snapshot.allOf=[{if:{properties:{state:{const:'FRESH'}}},then:{properties:{received_at:date,reason:{const:null},metrics:{properties:{cpu:{properties:{value:values}},memory:{properties:{value:values}}}}}},else:{properties:{metrics:{properties:{cpu:{properties:{value:{const:null}}},memory:{properties:{value:{const:null}}}}}}}}]
  const history=structuredClone(c.components.schemas.NetworkMonitoringHistory)
  history.properties.metric={enum:['cpu','memory']};history.properties.unit={const:'%'};history.properties.gap_after_seconds={const:90};history.properties.timestamp_meaning={const:'COLLECTOR_RECEIPT_TIME'};history.properties.source={const:'SANGFOR_AF_API'}
  c.components.schemas.FirewallResourceSnapshot=snapshot;c.components.schemas.FirewallResourceHistory=history
  c.components.schemas.FirewallResourceResult=obj({data:{$ref:'#/components/schemas/FirewallResourceSnapshot'},trace_id:{type:'string'}})
  c.components.schemas.FirewallResourceHistoryResult=obj({data:{$ref:'#/components/schemas/FirewallResourceHistory'},trace_id:{type:'string'}})
  c.components.schemas.NetworkMonitoringSnapshot.properties.devices.items.properties.vendor_metrics_status={enum:['DOCUMENTATION_PENDING','PARTIAL_API']}
  const action=(id,schema,parameters)=>({operationId:id,summary:'当前租户授权防火墙资源采样；时间为采集接收时刻，缺失值不补零',parameters,'x-mop-capability':'assets:read','x-mop-read-only':true,'x-mop-current-membership-recheck':true,'x-mop-provider-ids-accepted':false,responses:{'200':{description:'Source state and actual samples; errors/stale values remain null',content:{'application/json':{schema:{$ref:'#/components/schemas/'+schema}}}},'400':{description:'Invalid or duplicate query'},'401':{description:'Session or membership changed'},'403':{description:'Missing assets:read'},'404':{description:'Wrong tenant, IP, or no explicit resource binding'},'422':{description:'History exceeds 2000 points'},'503':{description:'Provider/configuration unavailable; no fallback'}}})
  const asset={name:'assetId',in:'path',required:true,schema:uuid}
  c.paths['/network-monitoring/{assetId}/resources']={get:action('readFirewallResources','FirewallResourceResult',[asset])}
  c.paths['/network-monitoring/{assetId}/resources/history']={get:action('readFirewallResourceHistory','FirewallResourceHistoryResult',[asset,{name:'metric',in:'query',schema:{enum:['cpu','memory'],default:'cpu'}},{name:'window',in:'query',schema:{enum:['HOUR_1','HOURS_24'],default:'HOUR_1'}}])}
  for (const path of ['/network-monitoring/{assetId}/resources','/network-monitoring/{assetId}/resources/history']) {
    for (const [status, response] of Object.entries(c.paths[path].get.responses)) {
      if (status !== '200') response.content = { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } }
    }
  }
  c.info.version='0.49.0'
}
