export function addExportPublicationContract(contract) {
  const object=(properties)=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
  const ref=name=>({$ref:`#/components/schemas/${name}`});
  const uuid={type:'string',format:'uuid'},date={type:'string',format:'date-time'},hash={type:'string',pattern:'^[0-9a-f]{64}$'};
  const version={type:'integer',minimum:1,maximum:9007199254740991};
  const envelope=data=>object({data,trace_id:{type:'string'}});
  const preparedFields={file_sha256:{anyOf:[hash,{type:'null'}]},size_bytes:{type:['integer','null'],minimum:1,maximum:16777216},row_count:{type:['integer','null'],minimum:0,maximum:1000}};
  const publication=object({job_id:uuid,tenant_id:uuid,created_by:uuid,permission_version:version,version:{...version,minimum:0},status:{enum:['NOT_PUBLISHED','PUBLISHED']},prepared:{type:'boolean'},...preparedFields,stored_at:{type:['string','null'],format:'date-time'},observed_at:date,publication_enabled:{type:'boolean'},download_grant_available:{type:'boolean'},notification_sent:{const:false},source:{const:'postgresql'}});
  publication.allOf=[
    {if:{properties:{prepared:{const:false}}},then:{properties:{file_sha256:{type:'null'},size_bytes:{type:'null'},row_count:{type:'null'}}},else:{properties:{file_sha256:hash,size_bytes:{type:'integer',minimum:1,maximum:16777216},row_count:{type:'integer',minimum:0,maximum:1000}}}},
    {if:{properties:{status:{const:'PUBLISHED'}}},then:{properties:{prepared:{const:true},stored_at:date,version}},else:{properties:{stored_at:{type:'null'},download_grant_available:{const:false}}}},
    {if:{properties:{publication_enabled:{const:false}}},then:{properties:{download_grant_available:{const:false}}}}
  ];
  const grant=object({job_id:uuid,content_url:{type:'string',pattern:'^/api/v1/asset-export-jobs/[0-9a-f-]{36}/content\\?grant=[1-9][0-9]{0,15}\\.[A-Za-z0-9_-]{43}$'},expires_at:date,size_bytes:{type:'integer',minimum:1,maximum:16777216},file_sha256:hash});
  grant['x-mop-binding']='URL job, current actor/tenant/permission version, published version and file digest; HMAC; expiry from database clock, 900 seconds; same-key replay never extends expiry';
  Object.assign(contract.components.schemas,{AssetExportPublication:publication,AssetExportPublicationResult:envelope(ref('AssetExportPublication')),AssetExportPublishInput:object({expected_version:{...version,maximum:9007199254740989},file_sha256:hash,action:{const:'PUBLISH_PREPARED_FILE'}}),AssetExportDownloadGrantResult:envelope(grant)});
  const id={name:'id',in:'path',required:true,schema:uuid};
  const headers=[{name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',pattern:'^[A-Za-z0-9._:-]{1,128}$'}},{name:'X-CSRF-Token',in:'header',required:true,schema:{type:'string'}}];
  const errors=Object.fromEntries([400,401,403,404,409,413,415,422,500,503].map(s=>[s,{description:'明确错误；503或响应未知时保留原请求，不能推断存储未写入',content:{'application/json':{schema:ref('Error')}}}]));
  const operation=(operationId,summary,schema,extra={})=>({operationId,summary,parameters:[id],responses:{200:{description:'本次动作或授权读取结果；不表示通知已发送',content:{'application/json':{schema}}},...errors},'x-mop-roles':['padmin','msp'],'x-mop-capabilities':['assets:read'],'x-mop-object-scope':'CURRENT_CREATOR_TENANT','x-mop-current-membership-recheck':true,...extra});
  const mutation=(operationId,summary,input,output)=>operation(operationId,summary,output,{parameters:[id,...headers],requestBody:{required:true,content:{'application/json':{schema:input}}},'x-mop-current-membership-before-replay':true,'x-mop-max-raw-body-bytes':8192,'x-mop-reject-unknown-query-keys':true});
  contract.paths['/asset-export-jobs/{id}/publication']={
    get:operation('getOwnExportPublication','独立读取当前发布记录；不读取MinIO、不返回存储坐标或签名链接',ref('AssetExportPublicationResult')),
    post:mutation('publishPreparedExport','显式同步发布既有异步作业文件；等待原准备租约结束，条件PUT并核验原字节，成功与审计/幂等回执同事务。未知结果可用原请求核查，不自动重传新内容',ref('AssetExportPublishInput'),ref('AssetExportPublicationResult'))
  };
  contract.paths['/asset-export-jobs/{id}/content-grants']={post:mutation('grantOwnExportDownload','明确取得本人当前授权的900秒签名链接；不会自动触发浏览器下载，不发送站内信',object({}),ref('AssetExportDownloadGrantResult'))};
  const content=operation('downloadOwnPublishedExport','校验链接、当前权限与对象范围，MinIO读回大小/摘要，返回前再次复核授权及有效期',{type:'string',format:'binary'},{parameters:[id,{name:'grant',in:'query',required:true,schema:{type:'string',maxLength:80,pattern:'^[1-9][0-9]{0,15}\\.[A-Za-z0-9_-]{43}$'}}],'x-mop-download-audited':true,'x-mop-store-before-and-after-authorization':true});
  content.responses[200]={description:'附件XLSX；Cache-Control:no-store；存储凭据不返回浏览器',content:{'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':{schema:{type:'string',format:'binary'}}}};
  contract.paths['/asset-export-jobs/{id}/content']={get:content};
  contract.components.schemas.ReadinessResult.properties.data.properties.export_publication=object({enabled:{type:'boolean'},ready:{type:'boolean'},configured:{type:'boolean'},provider:{enum:['minio',null]},reasons:{type:'array',items:{enum:['EXPORT_PUBLICATION_DISABLED','EXPORT_PUBLICATION_CONFIG_INVALID','EXPORT_STORAGE_UNAVAILABLE']}}});
  // Additive optional readiness field keeps earlier readiness sample documents valid.
  contract.info.version='0.47.0';
}
