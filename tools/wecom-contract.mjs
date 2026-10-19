export function addWeComContract(contract) {
  const str={type:'string'},bool={type:'boolean'},uuid={type:'string',format:'uuid'},time={type:'string',format:'date-time'}
  const nullable=s=>({anyOf:[s,{type:'null'}]}),obj=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false})
  const ref=name=>({$ref:`#/components/schemas/${name}`}),envelope=data=>obj({data,trace_id:str})
  const version={type:'integer',minimum:0,maximum:9007199254740991}
  const errors=Object.fromEntries([400,401,403,404,409,413,415,422,429,503].map(code=>[code,{description:'参数、会话、租户授权、版本、供应商或持久化依赖错误；不回显原始供应商诊断与秘密',content:{'application/json':{schema:ref('Error')}}}]))
  const delivery=obj({id:uuid,tenant_id:uuid,actor_id:uuid,source_message_id:nullable(uuid),recipient:{type:'string',maxLength:64},kind:{enum:['TEST','NOTIFICATION']},status:{enum:['QUEUED','PROCESSING','ACCEPTED','FAILED','UNKNOWN','CANCELLED']},version:{enum:[0,1,2]},provider_message_id:nullable(str),error_code:nullable(str),provider_code:nullable({type:'integer'}),created_at:time,started_at:nullable(time),finished_at:nullable(time)})
  const binding=obj({id:uuid,user_id:uuid,wecom_user_id:str,active:bool,version:{...version,minimum:1},verified_at:time,updated_at:time})
  const bindingRead={...binding,properties:{...binding.properties,display_name:str},required:[...binding.required,'display_name']}
  const check={type:'object',properties:{ready:bool,reason:nullable(str),application_name:str,provider_code:nullable({type:'integer'}),checked_at:time},required:['ready','reason'],additionalProperties:false}
  Object.assign(contract.components.schemas,{
    WeComDelivery:delivery,WeComDeliveryResult:envelope(delivery),WeComDeliveryPageResult:envelope(obj({items:{type:'array',items:delivery,maxItems:100},source:{const:'postgresql'}})),
    WeComBindingResult:envelope(binding),WeComBindingPageResult:envelope(obj({items:{type:'array',items:bindingRead,maxItems:200},source:{const:'postgresql'}})),
    WeComCheckResult:envelope(check),
    WeComStatusResult:envelope(obj({enabled:{const:true},tenant_id:uuid,corp_id:str,agent_id:{type:'integer',minimum:1},secret_configured:{const:true},test_user_id:nullable(str),login_enabled:bool,callback_url:nullable(str),dispatch_enabled:bool,automatic_notifications_enabled:{const:false},last_check:check,source:{const:'configured_provider'}})),
    WeComBindingInput:obj({expected_version:version,wecom_user_id:{type:'string',pattern:'^[A-Za-z0-9_.@-]{1,64}$'},active:bool}),
    WeComEnqueueInput:obj({expected_version:{const:0}}),WeComLoginStartInput:obj({mode:{enum:['web','work']}}),
    WeComLoginStartResult:envelope(obj({authorization_url:{type:'string',format:'uri'},expires_in:{const:300}})),
    WeComLoginOptionsResult:envelope(obj({enabled:bool,start_url:{type:['string','null'],enum:['/api/v1/auth/wecom/start',null]}})),
  })
  const key={name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',pattern:'^[A-Za-z0-9._:-]{1,128}$'}},csrf={name:'X-CSRF-Token',in:'header',required:true,schema:str}
  const path=name=>({name,in:'path',required:true,schema:uuid})
  const base=(id,summary,schema,status=200)=>({operationId:id,summary,'x-mop-current-membership-recheck':true,'x-mop-credentials-output':false,responses:{[status]:{description:summary,content:{'application/json':{schema:ref(schema)}}},...errors}})
  const write=(id,summary,input,output,status=200,params=[])=>({...base(id,summary,output,status),parameters:[...params,key,csrf],requestBody:{required:true,content:{'application/json':{schema:typeof input==='string'?ref(input):input}}}})
  contract.paths['/wecom/status']={get:{...base('getWeComStatus','读取本租户企业微信配置状态与脱敏检查结果','WeComStatusResult'),'x-mop-capability':'users:read'}}
  contract.paths['/wecom/check']={post:{...write('checkWeComApplication','检查真实应用与持久化依赖，记录脱敏审计',obj({}),'WeComCheckResult'),'x-mop-capability':'users:write'}}
  contract.paths['/wecom/bindings']={get:{...base('listWeComBindings','读取本租户显式账号绑定','WeComBindingPageResult'),'x-mop-capability':'users:read'}}
  contract.paths['/wecom/bindings/{userId}']={put:{...write('setWeComBinding','核验成员后显式绑定或停用；停用推进认证版本撤销会话','WeComBindingInput','WeComBindingResult',200,[path('userId')]),'x-mop-capability':'users:write','x-mop-cross-tenant':'404','x-mop-user-global-scope-required':true}}
  contract.paths['/wecom/test-messages']={post:{...write('enqueueWeComTest','受理一条向服务端明确配置成员发送的固定测试消息；耐久入队不等于送达','WeComEnqueueInput','WeComDeliveryResult',202),'x-mop-capability':'users:write'}}
  contract.paths['/my/notifications/{id}/wecom']={post:{...write('enqueueOwnWeComNotification','将本人站内通知发送给本人已绑定成员；来源与绑定组合永久去重','WeComEnqueueInput','WeComDeliveryResult',202,[path('id')]),'x-mop-capability':'tickets:read','x-mop-cross-tenant':'404'}}
  contract.paths['/wecom/deliveries']={get:{...base('listWeComDeliveries','本人发送记录；拥有users:read者可读当前租户台账','WeComDeliveryPageResult'),'x-mop-capability':'tickets:read'}}
  contract.paths['/wecom/deliveries/{id}']={get:{...base('getWeComDelivery','读取已受理、失败、取消或未知状态；未知不自动重发','WeComDeliveryResult'),parameters:[path('id')],'x-mop-capability':'tickets:read'}}
  contract.paths['/auth/wecom/options']={get:{...base('getWeComLoginOptions','读取企业微信登录是否启用','WeComLoginOptionsResult'),security:[]}}
  contract.paths['/auth/wecom/start']={post:{...write('startWeComLogin','发起登录；独立Lax关联Cookie与数据库哈希绑定，5分钟内单次使用','WeComLoginStartInput','WeComLoginStartResult'),parameters:[csrf],security:[],'x-mop-authorization-initiation':true}}
  contract.paths['/auth/wecom/callback']={get:{operationId:'completeWeComLogin',summary:'消费一次性登录关联，验证企业成员与显式绑定，建立现有权限会话；不自动创建或匹配用户',security:[],parameters:[{name:'code',in:'query',required:true,schema:{type:'string',maxLength:512}},{name:'state',in:'query',required:true,schema:{type:'string',pattern:'^[A-Za-z0-9_-]{43}$'}}],responses:{302:{description:'成功返回本站/assets，失败返回本站/login及有限错误码',headers:{Location:{schema:str}}}}}}
  const session=contract.components.schemas.SessionResult?.properties?.data??contract.components.schemas.Session
  if(session?.properties?.identity_source?.enum&&!session.properties.identity_source.enum.includes('wecom'))session.properties.identity_source.enum.push('wecom')
  for(const option of contract.paths['/auth/options'].get.responses[200].content['application/json'].schema.oneOf??[]){const data=option.properties?.data;if(data?.properties?.local_enabled){data.properties.wecom_enabled=bool;data.properties.wecom_start_url={type:['string','null'],enum:['/api/v1/auth/wecom/start',null]}}}
  contract.info.version='0.58.0'
}
