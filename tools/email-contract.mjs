export function addEmailContract(contract) {
 const str={type:'string'},bool={type:'boolean'},uuid={type:'string',format:'uuid'},time={type:'string',format:'date-time'},email={type:'string',format:'email',maxLength:254}
 const nullable=s=>({anyOf:[s,{type:'null'}]}),obj=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false}),ref=name=>({$ref:`#/components/schemas/${name}`}),envelope=data=>obj({data,trace_id:str})
 const check={type:'object',properties:{ready:bool,reason:nullable(str),smtp_authenticated:bool,checked_at:time},required:['ready','reason'],additionalProperties:false}
 const binding=obj({id:uuid,user_id:uuid,email,active:bool,version:{type:'integer',minimum:1,maximum:9007199254740991},updated_at:time})
 const delivery=obj({id:uuid,tenant_id:uuid,actor_id:uuid,source_message_id:nullable(uuid),sender:email,recipient:email,kind:{enum:['TEST','NOTIFICATION']},status:{enum:['QUEUED','PROCESSING','ACCEPTED','FAILED','UNKNOWN','CANCELLED']},version:{enum:[0,1,2]},message_id:nullable(str),error_code:nullable(str),smtp_code:nullable({type:'integer',minimum:200,maximum:599}),created_at:time,started_at:nullable(time),finished_at:nullable(time)})
 Object.assign(contract.components.schemas,{
  TargetEmailBindingResult:envelope(obj({tenant_id:uuid,user_id:uuid,sender:email,binding:nullable({...binding,properties:{...binding.properties,display_name:str},required:[...binding.required,'display_name']}),source:{const:'postgresql'}})),

  EmailDelivery:delivery,EmailDeliveryResult:envelope(delivery),EmailDeliveryPageResult:envelope(obj({items:{type:'array',items:delivery,maxItems:100},source:{const:'postgresql'}})),
  EmailBindingResult:envelope(binding),EmailBindingPageResult:envelope(obj({items:{type:'array',items:{...binding,properties:{...binding.properties,display_name:str},required:[...binding.required,'display_name']},maxItems:200},source:{const:'postgresql'}})),
  OwnEmailBindingResult:envelope(obj({tenant_id:uuid,owner_id:uuid,binding:nullable(obj({id:uuid,email,active:bool,version:{type:'integer',minimum:1}})),source:{const:'postgresql'}})),
  EmailStatusResult:envelope(obj({enabled:{const:true},tenant_id:uuid,sender:email,sender_name:str,smtp_host:str,smtp_port:{const:465},tls_mode:{const:'IMPLICIT_TLS'},certificate_verification:{const:true},secret_configured:{const:true},allowed_recipients:{type:'array',items:email,minItems:1,maxItems:100},test_recipient:email,dispatch_enabled:bool,automatic_notifications_enabled:{const:false},receipt_query_supported:{const:false},last_check:check,source:{const:'configured_provider'}})),
  EmailCheckResult:envelope(check),EmailBindingInput:obj({expected_version:{type:'integer',minimum:0,maximum:9007199254740991},email,active:bool}),EmailEnqueueInput:obj({expected_version:{const:0}})
 })
 const errors=Object.fromEntries([400,401,403,404,409,413,415,422,503].map(code=>[code,{description:'参数、当前租户授权、版本、邮件或持久化依赖错误；秘密与原始供应商诊断不输出',content:{'application/json':{schema:ref('Error')}}}]))
 const path=name=>({name,in:'path',required:true,schema:uuid}),headers=[{name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',pattern:'^[A-Za-z0-9._:-]{1,128}$'}},{name:'X-CSRF-Token',in:'header',required:true,schema:str}]
 const base=(operationId,summary,schema,status=200)=>({operationId,summary,'x-mop-current-membership-recheck':true,'x-mop-credentials-output':false,'x-mop-cross-tenant':'404',responses:{[status]:{description:summary,content:{'application/json':{schema:ref(schema)}}},...errors}})
 const write=(id,summary,input,output,status=200,params=[])=>({...base(id,summary,output,status),parameters:[...params,...headers],requestBody:{required:true,content:{'application/json':{schema:typeof input==='string'?ref(input):input}}}})
 contract.paths['/email/status']={get:{...base('getEmailStatus','读取当前租户邮件通道状态与脱敏检查结果','EmailStatusResult'),'x-mop-capability':'users:read'}}
 contract.paths['/email/check']={post:{...write('checkEmailConnection','检查实际SMTP加密认证和持久化依赖；不发送邮件',obj({}),'EmailCheckResult'),'x-mop-capability':'users:write'}}
 contract.paths['/email/bindings']={get:{...base('listEmailBindings','读取管理员明确配置的本租户收件地址','EmailBindingPageResult'),'x-mop-capability':'users:read'}}
 contract.paths['/email/bindings/{userId}']={get:{...base('getTargetEmailBinding','读取指定本租户账号的精确绑定版本；不依赖200条列表，不调用供应商','TargetEmailBindingResult'),parameters:[path('userId')],'x-mop-capability':'users:read'},put:{...write('setEmailBinding','按版本绑定或停用白名单收件地址；该绑定不是邮箱归属验证','EmailBindingInput','EmailBindingResult',200,[path('userId')]),'x-mop-capability':'users:write','x-mop-user-global-scope-required':true}}
 contract.paths['/my/email-binding']={get:{...base('getOwnEmailBinding','读取当前本人邮箱绑定；未配置返回null','OwnEmailBindingResult'),'x-mop-capability':'tickets:read'}}
 contract.paths['/email/test-messages']={post:{...write('enqueueEmailTest','向服务端固定测试收件人发送固定测试内容；先耐久入队','EmailEnqueueInput','EmailDeliveryResult',202),'x-mop-capability':'users:write'}}
 contract.paths['/my/notifications/{id}/email']={post:{...write('enqueueOwnEmailNotification','明确转发本站本人通知到本人绑定邮箱；来源与绑定永久去重','EmailEnqueueInput','EmailDeliveryResult',202,[path('id')]),'x-mop-capability':'tickets:read'}}
 contract.paths['/email/deliveries']={get:{...base('listEmailDeliveries','本人记录；users:read可读本租户当前发件账号的台账','EmailDeliveryPageResult'),'x-mop-capability':'tickets:read'}}
 contract.paths['/email/deliveries/{id}']={get:{...base('getEmailDelivery','读取SMTP提交结果；ACCEPTED不是DELIVERED，UNKNOWN不自动重发','EmailDeliveryResult'),parameters:[path('id')],'x-mop-capability':'tickets:read'}}
 contract.info.version='0.59.0'
}
