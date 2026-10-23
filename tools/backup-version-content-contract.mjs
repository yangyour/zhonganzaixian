export function addBackupVersionContentContract(contract) {
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const uuid = { type: 'string', format: 'uuid' }
  const count = { type: 'integer', minimum: 0, maximum: 1000 }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const hunk = object({ before_start: count, before_count: count, after_start: count, after_count: count })
  const summary = object({ tenant_id: uuid, asset_id: uuid, before_version_id: uuid, after_version_id: uuid,
    status: { enum: ['IDENTICAL','CHANGED','LIMIT_EXCEEDED','CANCELLED','UNAVAILABLE'] }, changed: nullable({type:'boolean'}),
    before_lines: nullable(count), after_lines: nullable(count), hunks: {type:'array',maxItems:2000,items:hunk},
    redaction: {const:'ALL_TEXT_WITHHELD'}, noise_policy: {const:'NONE_APPLIED_UNCONFIRMED'},
    change_approved: {const:false}, automatic_ticket_allowed: {const:false}, actual_recovery_verified: {const:false} })
  summary.allOf = [
    {if:{properties:{status:{enum:['LIMIT_EXCEEDED','CANCELLED','UNAVAILABLE']}}},then:{properties:{changed:{type:'null'},before_lines:{type:'null'},after_lines:{type:'null'},hunks:{maxItems:0}}}},
    {if:{properties:{status:{const:'IDENTICAL'}}},then:{properties:{changed:{const:false},before_lines:count,after_lines:count,hunks:{maxItems:0}}}},
    {if:{properties:{status:{const:'CHANGED'}}},then:{properties:{changed:{const:true},before_lines:count,after_lines:count,hunks:{minItems:1}}}},
  ]
  Object.assign(contract.components.schemas, {
    BackupVersionDiffResult: object({data:summary,trace_id:{type:'string'}}),
  })
  const asset = { name:'assetId',in:'path',required:true,schema:uuid }
  const errors = Object.fromEntries([400,401,403,404,405,409,413,415,422,503].map(status=>[status,{description:'当前会话、范围、独立授权、取消、文本边界或依赖检查未通过；不返回部分正文',content:{'application/json':{schema:ref('Error')}}}]))
  const common = { 'x-mop-roles':['padmin','msp'],'x-mop-capability':'assets:read','x-mop-read-only':true,
    'x-mop-business-writes-performed':false,'x-mop-success-writes-audit-or-receipts':true,
    'x-mop-current-membership-recheck':true,'x-mop-customer-roles-rejected':true }
  contract.paths['/assets/{assetId}/backup-versions/{versionId}/content'] = {get:{
    operationId:'downloadRegisteredBackupContent',summary:'独立授权下载已登记版本的密文信封或明文文件',...common,
    parameters:[asset,{name:'versionId',in:'path',required:true,schema:uuid},{name:'format',in:'query',required:false,schema:{enum:['envelope','plaintext'],default:'envelope'}}],
    responses:{200:{description:'完整核验后作为附件返回；审计仅表示授权读取，不代表浏览器下载或恢复成功',headers:{'Cache-Control':{schema:{const:'no-store'}},'Content-Disposition':{schema:{type:'string'}}},content:{'application/octet-stream':{schema:{type:'string',format:'binary'}}}},...errors},
    'x-mop-independent-authorization':'VERSION_READ + VERSION_DOWNLOAD; plaintext additionally requires VERSION_DECRYPT and current KMS permission; never inferred from inventory grants',
    'x-mop-allowed-query-keys':['format'],'x-mop-reject-duplicate-query-keys':true,
    description:'真实登记/父运行关系、同租户同设备、当前独立授权、密文摘要/信封范围核验；明文须完整AEAD认证。无公开或预签名URL，无正文日志。配置缺失503，外部对象/KMS未配不使用内存或本地密钥后备。',
  }}
  contract.paths['/assets/{assetId}/backup-versions/diff'] = {get:{
    operationId:'compareRegisteredBackupVersions',summary:'受保护的同设备版本脱敏差异',...common,
    parameters:[asset,{name:'before_version_id',in:'query',required:true,schema:uuid},{name:'after_version_id',in:'query',required:true,schema:uuid}],
    responses:{200:{description:'只返回位置和数量；未知/超限不归为无变化，也不批准变更或自动建单',content:{'application/json':{schema:ref('BackupVersionDiffResult')}}},...errors},
    'x-mop-independent-authorization':'VERSION_READ on both original versions + VERSION_DIFF + VERSION_DECRYPT; download authority is separate',
    'x-mop-max-encoded-query-bytes':2048,'x-mop-reject-duplicate-query-keys':true,'x-mop-reject-unknown-query-keys':true,
    'x-mop-idempotency':'Read-only immutable-version comparison; every request reauthorizes and writes its own access audit, no mutation receipt',
  }}
}
