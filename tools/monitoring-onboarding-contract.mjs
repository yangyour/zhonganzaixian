export function addMonitoringOnboardingContract(contract){
 const ref=n=>({$ref:'#/components/schemas/'+n}),object=properties=>({type:'object',additionalProperties:false,properties,required:Object.keys(properties)}),uuid={type:'string',format:'uuid'},hash={type:'string',pattern:'^[0-9a-f]{64}$'},nullable=s=>({anyOf:[s,{type:'null'}]}),boolFalse={const:false};
 const target=object({id:uuid,tenant_id:uuid,asset_id:uuid,preset_id:{enum:['linux-node','windows-exporter','network-snmp','http-service','tcp-service','icmp-device','postgresql','mysql','redis','containers','zabbix-linux','zabbix-windows','zabbix-snmp']},enabled:{type:'boolean'},endpoint:{type:'string',maxLength:512},target:nullable({type:'string',maxLength:512}),auth_profile:nullable({type:'string',pattern:'^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$'}),interval_seconds:nullable({type:'integer',minimum:5,maximum:3600}),timeout_seconds:{type:'integer',minimum:1,maximum:10}});
 const config=object({version:{const:1},targets:{type:'array',minItems:1,maxItems:1,items:target}});
 const report=object({target_id:uuid,configuration_sha256:hash,probe_status:{enum:['NOT_RUN','FAILED','TCP_REACHABLE_PROTOCOL_UNVERIFIED','SAMPLE_VERIFIED_ONCE']},continuous_collection_verified:boolFalse,device_identity_verified:boolFalse,health_verified:boolFalse,sample_count:nullable({type:'integer',minimum:1,maximum:100000}),matched_metrics:{type:'array',maxItems:2,uniqueItems:true,items:{type:'string',pattern:'^[a-zA-Z_:][a-zA-Z0-9_:]*$'}}});
 report.properties.reason={type:'string',pattern:'^[A-Z_]{1,100}$'};report.properties.metric_values_stored=boolFalse;
 report.allOf=[{if:{properties:{probe_status:{const:'SAMPLE_VERIFIED_ONCE'}}},then:{required:['metric_values_stored'],properties:{sample_count:{type:'integer',minimum:1,maximum:100000},matched_metrics:{minItems:1}},not:{required:['reason']}},else:{required:['reason'],properties:{sample_count:{type:'null'},matched_metrics:{maxItems:0}},not:{required:['metric_values_stored']}}}];
 report['x-mop-cross-field-checks']=['SAMPLE_VERIFIED_ONCE requires positive count, matching preset metrics and metric_values_stored=false; other states require null count, empty metrics, reason and no metric_values_stored','All reports remain one-shot, not identity, health, continuous collection, registration writes or deployment evidence'];
 const listed=object({tenant_id:uuid,actor_id:uuid,permission_version:{type:'integer',minimum:1},items:{type:'array',maxItems:64,items:object({target_id:uuid,asset_name:{type:'string'},configuration:config,configuration_sha256:hash,test_authorized:{type:'boolean'}})},source:{const:'SERVER_EXPLICIT_CONFIGURATION'},continuous_collection_verified:boolFalse});
 const tested=object({tenant_id:uuid,actor_id:uuid,permission_version:{type:'integer',minimum:1},report,source:{const:'SERVER_ONE_SHOT_PROBE'}});
 const envelope=data=>object({data,trace_id:{type:'string'}});
 Object.assign(contract.components.schemas,{MonitoringRegisteredTarget:target,MonitoringRegisteredTargetConfig:config,MonitoringRegisteredTargetList:listed,MonitoringRegisteredTargetListResult:envelope(ref('MonitoringRegisteredTargetList')),MonitoringRegisteredProbeReport:report,MonitoringRegisteredProbeResult:envelope(tested)});
 const errors=Object.fromEntries([400,401,403,404,409,429,503].map(code=>[code,{description:'Rejected scope, configuration, concurrency or disabled service; no fallback network probe',content:{'application/json':{schema:ref('Error')}}}]));
 const common={'x-mop-capabilities':['assets:read','configurations:read'],'x-mop-read-only':true,'x-mop-current-membership-recheck':true,'x-mop-arbitrary-destination-accepted':false,'x-mop-deployment-performed':false};
 contract.paths['/monitoring-onboarding/registered-targets']={get:{...common,operationId:'listRegisteredMonitoringTargets',parameters:[{name:'view',in:'query',required:false,schema:{type:'string',enum:['expanded']},description:'Explicit opt-in for multiple services of the same preset on one asset. Without this parameter, such a configuration returns 409 CONFIGURATION_VIEW_REQUIRED, never a partial list. Existing single-service configurations remain unchanged.'}],'x-mop-target-identity':'Stable target id plus exact destination; equivalent default HTTP ports/empty paths and TCP port formatting are deduplicated. Authorization still requires the exact original allowlist value.',summary:'当前租户已登记目标，校验授权资产与明确管理地址，不发起采集',responses:{200:{description:'Server-managed configuration only',content:{'application/json':{schema:ref('MonitoringRegisteredTargetListResult')}}},...errors}}};
 contract.paths['/monitoring-onboarding/registered-targets/{id}/test']={post:{...common,'x-mop-roles':['padmin'],'x-mop-capabilities':['assets:read','configurations:read','configurations:write'],'x-mop-csrf-required':true,'x-mop-network-io':'One explicitly allowlisted destination; Blackbox/SNMP require independent downstream authorization; one concurrent probe per process',operationId:'testRegisteredMonitoringTarget',summary:'按已登记ID及配置摘要执行一次有界只读探测，不接受URL/凭据/租户覆盖',parameters:[{name:'id',in:'path',required:true,schema:uuid},{name:'X-CSRF-Token',in:'header',required:true,schema:{type:'string'}}],requestBody:{required:true,content:{'application/json':{schema:object({configuration_sha256:hash})}}},responses:{200:{description:'Single probe outcome; FAILED is not success or collection health',content:{'application/json':{schema:ref('MonitoringRegisteredProbeResult')}}},...errors}}};
}

export function addMonitoringPlanningContract(contract) {
  const ref = name => ({ $ref: '#/components/schemas/' + name });
  const object = properties => ({ type: 'object', additionalProperties: false, properties, required: Object.keys(properties) });
  const uuid = { type: 'string', format: 'uuid' }, hash = { type: 'string', pattern: '^[0-9a-f]{64}$' };
  const nullable = value => ({ anyOf: [value, { type: 'null' }] }), falseValue = { const: false };
  const profile = object({ preset_id: { type: 'string', minLength: 1, maxLength: 64 }, key: { type: 'string', maxLength: 64 },
    port: { type: 'integer' }, scheme: { type: 'string', maxLength: 64 }, path: { type: 'string', maxLength: 512 }, collector: { type: 'string', maxLength: 64 } });
  profile.required = ['preset_id'];
  const defaults = object({ interval_seconds: nullable({ type: 'integer' }), timeout_seconds: { type: 'integer' }, exporter_scheme: { type: 'string', maxLength: 64 } });
  defaults.required = [];
  const request = object({ version: { const: 1 }, assets: { type: 'array', minItems: 1, maxItems: 64,
    items: object({ asset_id: uuid, profiles: { type: 'array', minItems: 1, maxItems: 16, items: { anyOf: [{ type: 'string', minLength: 1, maxLength: 64 }, profile] } } }) }, defaults });
  request.required = ['version', 'assets'];
  request['x-mop-cross-field-checks'] = ['At most 64 profiles across the request; duplicate asset ids rejected',
    'Tenant and addresses come only from the current session and authorized asset repository',
    'Client collector definitions, credential references, enabled flags, endpoint URLs and tenant overrides are forbidden',
    'Typed but incomplete/invalid profile settings return a positioned invalid plan; no partial configuration'];
  const diagnostic = object({ code: { type: 'string', pattern: '^[A-Z_]{1,100}$' }, asset_index: { type: 'integer', minimum: 1, maximum: 64 }, profile_index: { type: 'integer', minimum: 1, maximum: 16 } });
  diagnostic.required = ['code'];
  const configuration = object({ version: { const: 1 }, targets: { type: 'array', minItems: 1, maxItems: 64,
    items: { allOf: [ref('MonitoringRegisteredTarget'), { properties: { enabled: falseValue } }] } } });
  const plan = object({ valid: { type: 'boolean' }, diagnostics: { type: 'array', maxItems: 64, items: diagnostic },
    configuration: nullable(ref('MonitoringPreparedConfiguration')), configuration_sha256: nullable(hash), catalog_sha256: hash,
    target_count: { type: 'integer', minimum: 0, maximum: 64 }, network_accessed: falseValue, applied: falseValue,
    continuous_collection_verified: falseValue, device_identity_verified: falseValue });
  plan.allOf = [{ if: { properties: { valid: { const: true } } }, then: { properties: { diagnostics: { maxItems: 0 },
    configuration: ref('MonitoringPreparedConfiguration'), configuration_sha256: hash, target_count: { minimum: 1 } } },
    else: { properties: { diagnostics: { minItems: 1 }, configuration: { type: 'null' }, configuration_sha256: { type: 'null' }, target_count: { const: 0 } } } }];
  const prepared = object({ tenant_id: uuid, actor_id: uuid, permission_version: { type: 'integer', minimum: 1 },
    plan: ref('MonitoringPreparedPlan'), asset_scope_checked: { type: 'boolean' }, source: { const: 'SERVER_AUTHORIZED_PREPARATION' } });
  prepared.allOf = [{ if: { properties: { plan: { properties: { valid: { const: true } } } } }, then: { properties: { asset_scope_checked: { const: true } } } }];
  const schema = object({ type: { const: 'object' }, additionalProperties: { const: false }, properties: { type: 'object' },
    required: { type: 'array', uniqueItems: true, items: { type: 'string' } } });
  const preset = object({ id: { type: 'string', maxLength: 64 }, title: { type: 'string', maxLength: 200 }, scenario: { type: 'string', maxLength: 100 },
    engine: { enum: ['prometheus', 'zabbix'] }, test_kind: { enum: ['metrics', 'blackbox', 'snmp', 'tcp', 'configuration_only'] },
    suggested_port: { type: 'integer', minimum: 1, maximum: 65535 }, path: nullable({ type: 'string', maxLength: 64 }),
    required_any_metrics: { type: 'array', maxItems: 2, items: { type: 'string' } }, source_url: { type: 'string', format: 'uri', maxLength: 512 },
    module: { type: 'string', maxLength: 64 }, success_metric: { type: 'string', maxLength: 100 }, template: { type: 'string', maxLength: 200 },
    profile_schema: schema, server_collector_required: { type: 'boolean' }, server_collector_configured: { type: 'boolean' }, device_setup_verified: falseValue });
  preset.required = preset.required.filter(name => !['module', 'success_metric', 'template'].includes(name));
  const catalog = object({ version: { const: 1 }, catalog_sha256: hash, presets: { type: 'array', minItems: 1, maxItems: 64, items: preset } });
  const catalogData = object({ tenant_id: uuid, actor_id: uuid, permission_version: { type: 'integer', minimum: 1 },
    catalog: ref('MonitoringPresetCatalog'), source: { const: 'SERVER_PRESET_CATALOG' }, network_accessed: falseValue });
  const envelope = data => object({ data, trace_id: { type: 'string' } });
  Object.assign(contract.components.schemas, { MonitoringPlanRequest: request, MonitoringPreparedConfiguration: configuration,
    MonitoringPreparedPlan: plan, MonitoringPlanResult: envelope(prepared), MonitoringPresetCatalog: catalog, MonitoringPresetCatalogResult: envelope(catalogData) });
  const errors = Object.fromEntries([400, 401, 403, 404, 409].map(code => [code, { description: 'Rejected input or changed current scope; no preparation result released',
    content: { 'application/json': { schema: ref('Error') } } }]));
  const common = { 'x-mop-capabilities': ['assets:read', 'configurations:read'], 'x-mop-read-only': true,
    'x-mop-current-membership-recheck': true, 'x-mop-catalog-digest-scope': 'SHA256 of shared preset file bytes, not collector authorization or runtime configuration revision', 'x-mop-network-io': false, 'x-mop-deployment-performed': false,
    'x-mop-arbitrary-destination-accepted': false };
  contract.paths['/monitoring-onboarding/presets'] = { get: { ...common, operationId: 'readMonitoringPresetCatalog',
    summary: '读取预设与当前租户可用采集器参数规则，不暴露其他租户地址或凭据引用',
    responses: { 200: { description: 'Catalog and form schemas, not verified device setup', content: { 'application/json': { schema: ref('MonitoringPresetCatalogResult') } } }, ...errors } } };
  contract.paths['/monitoring-onboarding/plans'] = { post: { ...common, 'x-mop-roles': ['padmin'],
    'x-mop-capabilities': ['assets:read', 'configurations:read', 'configurations:write'], 'x-mop-csrf-required': true,
    'x-mop-max-body-bytes': 65536, 'x-mop-idempotency-receipt': false, operationId: 'prepareMonitoringOnboardingPlan',
    summary: '按当前资产与租户内采集器生成全禁用计划；不注册、不批准地址、不采集、不写业务数据',
    parameters: [{ name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } }],
    requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringPlanRequest') } } },
    responses: { 200: { description: 'Valid disabled configuration or positioned invalid plan; never a partial configuration',
      content: { 'application/json': { schema: ref('MonitoringPlanResult') } } }, ...errors } } };
}
