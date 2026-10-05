import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { randomUUID, randomBytes } from 'node:crypto'
import assert from 'node:assert/strict'
const cfg=JSON.parse(readFileSync('deploy/private/server-test-runtime.json','utf8')),base=cfg.public_origin,checks=[],run=randomUUID()
const privatePath='.local/project-identity-http-'+run;mkdirSync(privatePath)
let stage='start'
class Client {
  cookies=new Map();csrf=null
  async call(path,method='GET',body,expected=200,key=randomUUID(),csrf=true){
    const headers={Accept:'application/json',Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; ')}
    if(body!==undefined)headers['Content-Type']='application/json'
    if(method!=='GET'){headers['Idempotency-Key']=key;headers.Origin=base;if(csrf&&this.csrf)headers['X-CSRF-Token']=this.csrf}
    const response=await fetch(base+'/api/v1'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),redirect:'manual',signal:AbortSignal.timeout(20000)})
    for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],index=pair.indexOf('=');this.cookies.set(pair.slice(0,index),pair.slice(index+1))}
    const value=await response.json();assert.equal(response.status,expected,`${stage}: expected ${expected}, got ${response.status}, code ${value.error?.code ?? 'none'}`)
    assert.ok(value.trace_id);checks.push({stage,method,path:path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g,'{id}').split('?')[0],status:response.status})
    return value.data??value.error
  }
  async login(user){this.csrf=(await this.call('/auth/csrf')).csrf_token;const session=await this.call('/auth/local/login','POST',{username:user.username,password:user.password});this.csrf=session.csrf_token;assert.equal(session.identity_source,'project_local');return session}
}
try{
  const clients=[];for(const user of cfg.test_users){stage='six_existing_account_login_'+user.role;const client=new Client();const session=await client.login(user);assert.equal(session.user.id,user.platform_user_id);clients.push({user,client,session})}
  const admin=clients.find(x=>x.session.capabilities.includes('roles:write'));assert.ok(admin);const a=admin.client,tenant=admin.session.tenant.id
  stage='local_default_optional_oidc';const options=await a.call('/auth/options');assert.equal(options.local_enabled,true);assert.equal(options.sso_enabled,false);assert.equal(options.login_url,null)
  stage='policies_remain_unset';const policy=await a.call('/identity/policy');for(const [k,v]of Object.entries(policy))if(k!=='version')assert.equal(v,null)
  stage='catalog_reports_project_rbac';assert.equal((await a.call('/authorization/capability-catalog')).dynamic_roles_status,'PROJECT_RBAC')
  stage='csrf_required';await a.call('/identity/roles','POST',{expected_version:0,name:'must-not-create'},403,randomUUID(),false)
  const stranger=new Client();stage='anonymous_admin_denied';await stranger.call('/identity/users','GET',undefined,401)
  const viewer=clients.find(x=>x.session.user.role==='viewer');stage='viewer_admin_denied';await viewer.client.call('/identity/users','GET',undefined,403)
  const roleKey=randomUUID(),roleBody={expected_version:0,name:'验收角色 '+run,description:'明确标识的虚构验收角色',base_role:'viewer',permissions:['assets:read'],enabled:true}
  stage='create_restricted_role';let role=await a.call('/identity/roles','POST',roleBody,200,roleKey);const roleReceipt=structuredClone(role)
  const account={username:'accept-'+run,password:randomBytes(20).toString('base64url')},create={expected_version:0,...account,display_name:'虚构权限验收 '+run,role_id:role.id,active:true},key=randomUUID()
  stage='create_user_and_replay';let user=await a.call('/identity/users','POST',create,200,key);assert.equal(user.version,1);assert.deepEqual(await a.call('/identity/users','POST',create,200,key),user)
  await a.call('/identity/users','POST',{...create,display_name:'changed request'},409,key)
  writeFileSync(privatePath+'/synthetic-account.json',JSON.stringify({account,user,role,tenant}))
  const restricted=new Client();stage='restricted_real_login';const first=await restricted.login(account);assert.deepEqual(first.capabilities,['assets:read']);await restricted.call('/assets');await restricted.call('/identity/roles','GET',undefined,403)
  stage='role_revocation_effective_without_restart';role=await a.call('/identity/roles/'+role.id,'PATCH',{expected_version:role.version,name:role.name,description:role.description,base_role:'viewer',permissions:[],enabled:true});await restricted.call('/assets','GET',undefined,403);assert.deepEqual((await restricted.call('/session')).capabilities,[])
  stage='cross_tenant_not_found';const other=clients.find(x=>x.session.tenant.id!==tenant&&x.session.user.role==='viewer');assert.ok(other);await a.call('/identity/users/'+other.session.user.id,'GET',undefined,404)
  stage='explicit_oidc_binding';const binding=await a.call('/identity/users/'+user.id+'/external-identities','POST',{expected_version:0,issuer:'https://identity.example.test',subject:'synthetic-'+run});await a.call('/identity/users/'+user.id+'/external-identities/'+binding.id+'/disable','POST',{expected_version:binding.version});await restricted.call('/session','GET',undefined,401)
  stage='reset_password_revokes_old_session';const beforeReset=new Client();await beforeReset.login(account);user=await a.call('/identity/users/'+user.id);const replacement=randomBytes(20).toString('base64url');await a.call('/identity/users/'+user.id+'/password','POST',{expected_version:user.version,new_password:replacement});await beforeReset.call('/session','GET',undefined,401)
  const old=new Client();old.csrf=(await old.call('/auth/csrf')).csrf_token;await old.call('/auth/local/login','POST',account,401)
  account.password=replacement;const resetClient=new Client();const resetSession=await resetClient.login(account);assert.equal(resetSession.password_change_required,false)
  stage='revoke_all_sessions';user=await a.call('/identity/users/'+user.id);await a.call('/identity/users/'+user.id+'/revoke-sessions','POST',{expected_version:user.version});await resetClient.call('/session','GET',undefined,401)
  stage='disable_account';user=await a.call('/identity/users/'+user.id);await a.call('/identity/users/'+user.id,'PATCH',{expected_version:user.version,display_name:user.display_name,active:false});const disabled=new Client();disabled.csrf=(await disabled.call('/auth/csrf')).csrf_token;await disabled.call('/auth/local/login','POST',account,401)
  stage='retire_synthetic_role';await a.call('/identity/roles/'+role.id,'PATCH',{expected_version:role.version,name:role.name,description:role.description,base_role:'viewer',permissions:[],enabled:false})
  stage='final_readiness_and_policy';assert.equal((await a.call('/health/ready')).ready,true);assert.deepEqual(await a.call('/identity/policy'),policy)
  writeFileSync(privatePath+'/restart-control.json',JSON.stringify({admin_role:admin.user.role,cookies:Object.fromEntries(a.cookies),csrf:a.csrf,roleKey,roleBody,roleReceipt,policy,roleCurrent:await a.call('/identity/roles')}))
  writeFileSync('.local/project-identity-restart-control-path.txt',privatePath+'/restart-control.json')
  const report={verified_at:new Date().toISOString(),environment:'designated-server',base,run,passed:true,requests:checks.length,checks,policies_unset:true,original_six_user_ids_preserved:true,synthetic_account_disabled:true,synthetic_role_disabled:true}
  writeFileSync('docs/evidence/project-identity-http-20261005.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({passed:true,requests:checks.length,policies_unset:true}))
}catch(error){writeFileSync(privatePath+'/failure.json',JSON.stringify({stage,checks,error_type:error.name,message:error.message},null,2));console.error(JSON.stringify({passed:false,stage,error_type:error.name,details:'private failure report'}));process.exitCode=1}
