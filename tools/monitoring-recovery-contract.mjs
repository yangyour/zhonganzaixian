export function addMonitoringRecoveryContract(contract) {
  const object = properties => ({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
  const ref = name => ({$ref:`#/components/schemas/${name}`});
  const uuid = {type:'string',format:'uuid'}, time = {type:'string',format:'date-time'};
  const version = {type:'integer',minimum:1,maximum:9007199254740990};
  const nullable = value => ({anyOf:[value,{type:'null'}]});
  const decimal = {type:'string',pattern:'^-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?$'};
  const envelope = schema => object({data:schema,trace_id:{type:'string'}});
  const scope = {tenant_id:uuid,actor_id:uuid,permission_version:version,event_id:uuid,event_version:version};
  const rule = object({metric:{enum:['icmp','loss','latency','tcp_22','tcp_80','tcp_443']},comparison:{enum:['GT','GE','LT','LE']},
    threshold:decimal,unit:{type:'string',maxLength:32},hold_seconds:decimal,max_gap_seconds:decimal,minimum_samples:{type:'integer',minimum:1,maximum:2000}});
  const context = object({...scope,asset_id:uuid,monitor_state:{enum:['PROBLEM','RECOVERED']},handling_state:{enum:['NEW','ACKNOWLEDGED','IN_PROGRESS','PENDING_REVIEW','RESOLVED','CLOSED']},recovery_evidence_status:{enum:['NOT_CONFIRMED','UNVERIFIED','CONFIRMED']},configuration_status:{enum:['ACTIVE','CONFIG_PENDING']},
    missing_reason:nullable({type:'string'}),policy_release_id:nullable(uuid),policy_release_version:nullable(version),rule:nullable(rule),
    observed_at:time,source:{const:'postgresql'},source_binding_available:{type:'boolean'}});
  const result = object({...scope,proof_id:uuid,status:{enum:['RECOVERY_CONFIRMED','NO_SAMPLES','UNKNOWN_LATEST','STALE_LATEST','NOT_RECOVERED','OBSERVING']},
    applied:{type:'boolean'},candidate_recovered_at:nullable(time),recovered_at:nullable(time),confirmed_at:nullable(time),policy_release_id:uuid,policy_release_version:version,observed_at:time,
    latest_sample_at:nullable(time),span_started_at:nullable(time),span_seconds:nullable(decimal),matching_samples:{type:'integer',minimum:0,maximum:2000},
    source_binding_sha256:{type:'string',pattern:'^[0-9a-f]{64}$'},source:{const:'ZABBIX_SIMPLE_CHECK'},production_approved:{const:false}});
  result.allOf=[{if:{properties:{applied:{const:true}}},then:{properties:{status:{const:'RECOVERY_CONFIRMED'},confirmed_at:time,candidate_recovered_at:time,recovered_at:time,latest_sample_at:time,span_started_at:time,span_seconds:decimal,matching_samples:{minimum:1}}},else:{properties:{status:{not:{const:'RECOVERY_CONFIRMED'}},confirmed_at:{type:'null'},recovered_at:{type:'null'}}}}];
  context.allOf=[{if:{properties:{configuration_status:{const:'CONFIG_PENDING'}}},then:{properties:{rule:{type:'null'},policy_release_id:{type:'null'},policy_release_version:{type:'null'}}},else:{properties:{rule,policy_release_id:uuid,policy_release_version:version,missing_reason:{type:'null'}}}}];
  Object.assign(contract.components.schemas,{
    MonitoringRecoveryContextResult:envelope(context),MonitoringRecoveryObservationResult:envelope(result),
    MonitoringRecoveryObserveInput:object({expected_version:version,policy_release_id:uuid,policy_release_version:version}),
  });
  const errors=Object.fromEntries([400,401,403,404,409,422,503].map(code=>[code,{description:'权限、事件/策略版本、来源或证据错误；未知提交保留原请求身份',content:{'application/json':{schema:ref('Error')}}}]));
  const base=(operationId,schema,capability)=>({operationId,'x-mop-roles':['msp','padmin'],'x-mop-capability':capability,
    'x-mop-current-membership-recheck':true,'x-mop-cross-tenant':'404',responses:{200:{description:'当前授权范围的真实持久化结果；恢复不代表工单关闭',content:{'application/json':{schema:ref(schema)}}},...errors}});
  const event={name:'eventId',in:'path',required:true,schema:uuid};
  contract.paths['/events/{eventId}/recovery-observations']={
    get:{...base('inspectMonitoringRecovery','MonitoringRecoveryContextResult','tickets:read'),summary:'读取恢复观察策略与来源绑定是否配置，不采样、不写入',parameters:[event]},
    post:{...base('observeMonitoringRecovery','MonitoringRecoveryObservationResult','ticket_notes:write_internal'),summary:'读取服务端绑定来源，按精确发布策略保存观察证据，满足条件才确认t2',
      parameters:[event,{name:'X-CSRF-Token',in:'header',required:true,schema:{type:'string'}},{name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',minLength:1,maxLength:128}}],
      requestBody:{required:true,content:{'application/json':{schema:ref('MonitoringRecoveryObserveInput')}}},
      'x-mop-idempotent-replay-reauthorization':true,'x-mop-client-samples-accepted':false,'x-mop-atomic-evidence-event-audit':true},
  };
  contract.paths['/events/{eventId}/recovery-observations/receipts/{commandSha256}']={
    get:{...base('readMonitoringRecoveryReceipt','MonitoringRecoveryObservationResult','tickets:read'),summary:'只读恢复原请求回执；尚未发现已提交证据时返回404，不重发写入',
      parameters:[event,{name:'commandSha256',in:'path',required:true,schema:{type:'string',pattern:'^[0-9a-f]{64}$'}}]},
  };
}
