import { readFileSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'
const cfg=JSON.parse(readFileSync('deploy/private/server-test-runtime.json','utf8')),control=JSON.parse(readFileSync(readFileSync('.local/project-identity-restart-control-path.txt','utf8'),'utf8')),base=cfg.public_origin
const cookies=new Map(Object.entries(control.cookies));let csrf=control.csrf,stage='old_session',checks=0
async function call(path,method='GET',body,expected=200,key=randomUUID()){
 const headers={Accept:'application/json',Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; ')};if(body!==undefined)headers['Content-Type']='application/json';if(method!=='GET'){headers['X-CSRF-Token']=csrf;headers['Idempotency-Key']=key;headers.Origin=base}
 const r=await fetch(base+'/api/v1'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});for(const raw of r.headers.getSetCookie()){const p=raw.split(';')[0],i=p.indexOf('=');cookies.set(p.slice(0,i),p.slice(i+1))}const result=await r.json();assert.equal(r.status,expected,stage);checks++;return result.data??result.error
}
try{
 await call('/session','GET',undefined,401);stage='fresh_local_login';csrf=(await call('/auth/csrf')).csrf_token;const user=cfg.test_users.find(x=>x.role===control.admin_role);const login=await call('/auth/local/login','POST',{username:user.username,password:user.password});csrf=login.csrf_token;assert.equal(login.identity_source,'project_local')
 stage='original_command_replay';assert.deepEqual(await call('/identity/roles','POST',control.roleBody,200,control.roleKey),control.roleReceipt)
 stage='current_roles_preserved';assert.deepEqual(await call('/identity/roles'),control.roleCurrent)
 stage='policy_nulls_preserved';assert.deepEqual(await call('/identity/policy'),control.policy)
 stage='ready';assert.equal((await call('/health/ready')).ready,true)
 const result={verified_at:new Date().toISOString(),environment:'designated-server',passed:true,requests:checks,old_session_rejected:true,fresh_local_login:true,original_role_receipt_identical:true,current_role_rows_identical:true,password_policy_unchanged:true};writeFileSync('docs/evidence/project-identity-restart-20261005.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result))
}catch(error){console.error(JSON.stringify({passed:false,stage,error_type:error.name}));process.exitCode=1}
