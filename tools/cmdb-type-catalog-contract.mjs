export function addCmdbTypeCatalogContract(contract) {
 const object=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
 const ref=name=>({$ref:'#/components/schemas/'+name});
 const string={type:'string',minLength:1,maxLength:200};
 const list=(items,count)=>({type:'array',items,minItems:count,maxItems:count});
 const primary=['SW','RT','FW','WL','SV','HC','VD','ST','DS'];
 const subtype=['sw_core','sw_access','rt_gateway','fw_ngfw','fw_ips','fw_audit','fw_adc','wl_ac','wl_ap','sv_physical','sv_vm','hc_node','vd_host','st_san','st_nas','ds_node'];
 const choice=codes=>object({code:{enum:codes},label:string});
 const source={source_sheet:string,source_row:{type:'integer',minimum:2,maximum:999}};
 const dimensions={lifecycle_state:['planned','procured','onboarding','in_service','maintenance','suspended','retired','disposed'],sync_state:['synced','syncing','failed','drifted'],health_state:['normal','problem','unknown','maintenance'],billable:['yes','no','addon']};
 const data=object({tenant_id:{type:'string',format:'uuid'},catalog_version:{const:'customer-cmdb-v1-20261021'},catalog_hash:{type:'string',pattern:'^[0-9a-f]{64}$'},scope:{const:'REFERENCE_ONLY_NOT_ASSET_DATA'},
  primary_types:list(choice(primary),9),subtypes:list(object({code:{enum:subtype},label:string,parent:{enum:primary},monitoring_mapping_resolved:{type:'boolean'},...source}),16),
  attributes:list(object({code:{type:'string',pattern:'^[a-z][a-z0-9_]{0,63}$'},label:string,group:string,data_type:string,required_note:string,derived:{type:'boolean'},...source}),95),
  collection_methods:list(choice(['agent_active','agent_passive','snmpv3','http','sender']),5),
  state_dimensions:list({oneOf:Object.entries(dimensions).map(([field,codes])=>object({field:{const:field},values:list(choice(codes),codes.length)}))},4),
  unresolved:list(object({code:{enum:['L2_COUNT_CONFLICT','MONITORING_TYPE_MAPPING_PENDING','IMPORT_COLLECTION_METHOD_CONFLICT','NOTE_REQUIREDNESS_CONFLICT']},resolution:{type:'null'}}),4),
  runtime_publication:{const:false},asset_mutations:{const:false},production_approved:{const:false}});
 contract.components.schemas.CmdbTypeCatalog=data;
 contract.components.schemas.CmdbTypeCatalogResult=object({data:ref('CmdbTypeCatalog'),trace_id:{type:'string'}});
 contract.paths['/cmdb/type-catalog']={get:{operationId:'readCustomerCmdbTypeCatalog',summary:'读取客户 CMDB 类型、属性及待确认差异；不是资产数据或运行发布',parameters:[],
  'x-mop-capabilities':['assets:read'],'x-mop-read-only':true,
  responses:{200:{description:'当前会话租户绑定的只读参考目录',content:{'application/json':{schema:ref('CmdbTypeCatalogResult')}}},
  ...Object.fromEntries([400,401,403,503].map(code=>[code,{description:'输入、会话、权限或资源不可用',content:{'application/json':{schema:ref('Error')}}}]))}}};
}
