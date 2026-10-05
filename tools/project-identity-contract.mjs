export function addProjectIdentityContract(contract) {
  const s={type:'string'},b={type:'boolean'},uuid={type:'string',format:'uuid'},v={type:'integer',minimum:1,maximum:9007199254740990},n={type:['string','null']},date={type:['string','null'],format:'date-time'}
  const obj=(properties,required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false})
  const ref=name=>({$ref:'#/components/schemas/'+name}),envelope=data=>obj({data,trace_id:s}),array=items=>({type:'array',items})
  const role={enum:['viewer','tadmin','msp','padmin']},permission={type:'string',pattern:'^[a-z_]+:[a-z_]+$'}
  const policy=obj({version:v,minimum_password_length:{type:['integer','null'],minimum:1,maximum:128},password_history_count:{type:['integer','null'],minimum:1,maximum:20},failed_attempt_limit:{type:['integer','null'],minimum:1,maximum:100},lock_minutes:{type:['integer','null'],minimum:1,maximum:1440},idle_minutes:{type:['integer','null'],minimum:1,maximum:1440},force_change_on_reset:{type:['boolean','null']}})
  policy.description='用户暂不设置的策略初始均为null；无自动15位、历史5次、锁定或强制改密默认值。'
  const user=obj({id:uuid,display_name:s,active:b,username:n,local_login_enabled:b,must_change_password:{type:['boolean','null']},locked_until:date,last_login_at:date,version:v,base_role:role,member_active:b,permission_version:v,role_id:uuid,role_name:s,permissions:array(permission)})
  const projectRole=obj({id:uuid,tenant_id:uuid,name:s,description:n,base_role:role,permissions:array(permission),builtin:b,enabled:b,version:v,created_at:{type:'string',format:'date-time'},updated_at:{type:'string',format:'date-time'},assigned_members:{type:'integer',minimum:0}})
  const catalogue=obj({declaration_hash:{type:'string',pattern:'^[a-f0-9]{64}$'},roles:array(obj({id:role,label:s})),dimensions:array(obj({id:s,label:s,capability_ids:array(permission)})),entries:array(obj({id:permission,label:s,dimension_ids:array(s),declared_by_role:obj({viewer:b,tadmin:b,msp:b,padmin:b})}))})
  const secret={type:'string',minLength:1,writeOnly:true,'x-mop-max-utf8-bytes':1024,'x-mop-sensitive':true}
  const name={type:'string',minLength:1,maxLength:100},login={type:'string',pattern:'^[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}$'}
  const roleInput={name,description:{type:['string','null'],maxLength:2000},base_role:role,permissions:{...array(permission),uniqueItems:true,maxItems:256},enabled:b}
  const binding=obj({id:uuid,provider:{const:'oidc'},issuer:s,subject:s,active:b,version:v,created_at:{type:'string',format:'date-time'}})
  const schemas={
    ProjectIdentityPolicy:policy,ProjectIdentityPolicyResult:envelope(ref('ProjectIdentityPolicy')),
    ProjectUser:user,ProjectUserResult:envelope(ref('ProjectUser')),
    ProjectUserPageResult:envelope(obj({items:{...array(ref('ProjectUser')),maxItems:50},has_more:b,page:{type:'integer',minimum:1},page_size:{const:50},tenant_id:uuid})),
    ProjectRole:projectRole,ProjectRoleResult:envelope(ref('ProjectRole')),
    ProjectRolePageResult:envelope(obj({items:array(ref('ProjectRole')),tenant_id:uuid,permission_catalog:catalogue})),
    ProjectAccountResult:envelope(obj({id:uuid,display_name:s,active:b,username:n,must_change_password:{type:['boolean','null']},last_login_at:date,version:v,password_policy:ref('ProjectIdentityPolicy')})),
    ProjectCsrfResult:envelope(obj({csrf_token:s})),ProjectLocalLogin:obj({username:login,password:secret}),
    ProjectUserCreate:obj({expected_version:{const:0},username:login,display_name:name,password:secret,role_id:uuid,active:b}),
    ProjectUserUpdate:obj({expected_version:v,display_name:name,active:b}),
    ProjectRoleCreate:obj({expected_version:{const:0},...roleInput}),ProjectRoleUpdate:obj({expected_version:v,...roleInput}),
    ProjectPasswordReset:obj({expected_version:v,new_password:secret}),ProjectPasswordChange:obj({expected_version:v,current_password:secret,new_password:secret}),
    ProjectLocalAccountCreate:obj({expected_version:v,username:login,password:secret}),
    ProjectMembershipInput:obj({expected_version:{type:'integer',minimum:0,maximum:9007199254740990},role_id:uuid,active:b}),
    ProjectMembershipResult:envelope(obj({tenant_id:uuid,user_id:uuid,base_role:role,active:b,permission_version:v})),
    ProjectMembershipPageResult:envelope(obj({items:array(obj({tenant_id:uuid,tenant_name:s,base_role:role,active:b,permission_version:v,role_id:{...uuid,type:['string','null']}}))})),
    ProjectExternalIdentity:binding,ProjectExternalIdentityResult:envelope(ref('ProjectExternalIdentity')),ProjectExternalIdentityPageResult:envelope(obj({items:array(ref('ProjectExternalIdentity'))})),
    ProjectExternalIdentityCreate:obj({expected_version:{const:0},issuer:{type:'string',maxLength:2000},subject:{type:'string',minLength:1,maxLength:255}}),
    ProjectPolicyUpdate:obj({expected_version:v,...Object.fromEntries(Object.entries(policy.properties).filter(([key])=>key!=='version'))}),
    ProjectIdentityProvidersResult:envelope(obj({local_enabled:{const:true},unified_identity:obj({protocol:{const:'OIDC'},enabled:b,binding:{const:'EXPLICIT_ISSUER_SUBJECT'},automatic_user_matching:{const:false},external_roles_grant_permissions:{const:false}})})),
    ProjectPasswordResetResult:envelope(obj({password_reset:{const:true},sessions_revoked:{const:true},user_id:uuid})),
    ProjectPasswordChangeResult:envelope(obj({password_changed:{const:true},reauthenticate:{const:true}})),
    ProjectUnlockResult:envelope(obj({unlocked:{const:true},user_id:uuid})),ProjectSessionRevokeResult:envelope(obj({sessions_revoked:{const:true},user_id:uuid})),
    ProjectBindingDisabledResult:envelope(obj({binding_disabled:{const:true},sessions_revoked:{const:true}})),
  }
  Object.assign(contract.components.schemas,schemas)
  const session=contract.components.schemas.SessionResult.properties.data
  session.properties.identity_source={enum:['test_oidc','project_local','oidc']}
  session.properties.account_version=v;session.properties.password_change_required=b
  session['x-mop-local-session']='Project authentication revision is checked on every request; effective capabilities come from stored tenant roles.'
  const originalOptions=contract.paths['/auth/options'].get.responses['200'].content['application/json'].schema
  const localOptions=envelope(obj({mode:{const:'remote_test'},identity_source:{const:'project_local'},local_enabled:{const:true},sso_enabled:b,login_url:{type:['string','null'],enum:['/oauth2/authorization/mop',null]},local_login_url:{const:'/api/v1/auth/local/login'},csrf_url:{const:'/api/v1/auth/csrf'},sso_protocol:{const:'OIDC'}}))
  contract.paths['/auth/options'].get.responses['200'].content['application/json'].schema={oneOf:[originalOptions,localOptions]}
  const errors=Object.fromEntries([400,401,403,404,409,413,415,503].map(code=>[code,{description:'严格参数/当前身份、权限、CSRF、版本或依赖校验失败',content:{'application/json':{schema:ref('Error')}}}]))
  const param=(name,location,schema,required=true)=>({name,in:location,schema,required})
  const headers=[param('Idempotency-Key','header',{type:'string',pattern:'^[A-Za-z0-9._:-]{1,128}$'}),param('X-CSRF-Token','header',s)]
  function add(path,method,id,input,result,permission,parameters=[]){
    const operation={operationId:id,summary:id,responses:{200:{description:'项目内身份/授权结果；不表示客户正式签字或生产批准',content:{'application/json':{schema:ref(result)}}},...errors},parameters:[...parameters,...(method==='get'?[]:headers)],'x-mop-capabilities':permission?[permission]:[], 'x-mop-cross-tenant':'404','x-mop-current-membership-recheck':true}
    if(input)operation.requestBody={required:true,content:{'application/json':{schema:ref(input)}}}
    if(method!=='get'){operation['x-mop-idempotency']='Current authorization before durable original receipt; sensitive commands use private HMAC fingerprint; secrets never stored in receipt/audit';operation['x-mop-audit']=true}
    ;(contract.paths[path]??={})[method]=operation
  }
  const id=[param('id','path',uuid)],expected=Object.assign({},obj({expected_version:v}))
  contract.components.schemas.ProjectExpectedVersion=expected
  add('/auth/csrf','get','getProjectLoginCsrf',null,'ProjectCsrfResult',null);contract.paths['/auth/csrf'].get.security=[]
  add('/auth/local/login','post','loginProjectAccount','ProjectLocalLogin','SessionResult',null)
  contract.paths['/auth/local/login'].post.security=[];contract.paths['/auth/local/login'].post.parameters=[headers[1]];contract.paths['/auth/local/login'].post['x-mop-idempotency']='Authentication rotates server session and CSRF; no business command is created.'
  add('/account','get','getOwnProjectAccount',null,'ProjectAccountResult',null)
  add('/account/password','post','changeOwnProjectPassword','ProjectPasswordChange','ProjectPasswordChangeResult',null)
  add('/identity/users','get','listProjectUsers',null,'ProjectUserPageResult','users:read',[param('query','query',{type:'string',maxLength:100},false),param('include_inactive','query',b,false),param('page','query',{type:'integer',minimum:1,maximum:10000},false)])
  add('/identity/users','post','createProjectUser','ProjectUserCreate','ProjectUserResult','users:write')
  add('/identity/users/{id}','get','getProjectUser',null,'ProjectUserResult','users:read',id)
  add('/identity/users/{id}','patch','updateProjectUser','ProjectUserUpdate','ProjectUserResult','users:write',id)
  for(const [suffix,input,result,operation] of [['password','ProjectPasswordReset','ProjectPasswordResetResult','resetProjectPassword'],['local-account','ProjectLocalAccountCreate','ProjectUserResult','enableProjectLocalAccount'],['unlock','ProjectExpectedVersion','ProjectUnlockResult','unlockProjectAccount'],['revoke-sessions','ProjectExpectedVersion','ProjectSessionRevokeResult','revokeProjectUserSessions']])add('/identity/users/{id}/'+suffix,'post',operation,input,result,'users:write',id)
  add('/identity/users/{id}/memberships','get','listManagedProjectMemberships',null,'ProjectMembershipPageResult','users:read',id)
  add('/identity/users/{id}/memberships/{tenant}','put','setProjectMembership','ProjectMembershipInput','ProjectMembershipResult','users:write',[...id,param('tenant','path',uuid)])
  add('/identity/roles','get','listProjectRoles',null,'ProjectRolePageResult','roles:read',[{name:'tenant_id',in:'query',required:false,schema:uuid,description:'显式查询已授权目标租户的角色；未授权返回404'}])
  add('/identity/roles','post','createProjectRole','ProjectRoleCreate','ProjectRoleResult','roles:write')
  add('/identity/roles/{id}','patch','updateProjectRole','ProjectRoleUpdate','ProjectRoleResult','roles:write',id)
  add('/identity/policy','get','getProjectIdentityPolicy',null,'ProjectIdentityPolicyResult','roles:read')
  add('/identity/policy','put','setProjectIdentityPolicy','ProjectPolicyUpdate','ProjectIdentityPolicyResult','roles:write')
  add('/identity/providers','get','getProjectIdentityProviders',null,'ProjectIdentityProvidersResult','users:read')
  add('/identity/users/{id}/external-identities','get','listExternalIdentityBindings',null,'ProjectExternalIdentityPageResult','users:read',id)
  add('/identity/users/{id}/external-identities','post','bindExternalIdentity','ProjectExternalIdentityCreate','ProjectExternalIdentityResult','users:write',id)
  add('/identity/users/{id}/external-identities/{binding}/disable','post','disableExternalIdentityBinding','ProjectExpectedVersion','ProjectBindingDisabledResult','users:write',[...id,param('binding','path',uuid)])
  contract.info.version='0.45.0'
  contract.info.description+=' 默认项目本地账号和数据库角色授权；OIDC为可选显式issuer/sub接入。用户暂不设置的密码策略保持null。'
}
