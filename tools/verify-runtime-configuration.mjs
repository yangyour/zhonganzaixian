// Explicitly labelled configuration tests against the designated API through the pinned SSH forward.
// No provider/device invocation; no cookies, credentials or complete request bodies are evidence.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'
import Ajv from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
const root=resolve(import.meta.dirname,'..'),cfg=JSON.parse(readFileSync(resolve(root,'deploy/private/lan-test-runtime.json'),'utf8').replace(/^\uFEFF/,''))
assert.equal(cfg.environment,'lan-test-20261008')
const base=process.env.MOP_RUNTIME_API_BASE||'http://127.0.0.1:21815';assert.ok(['http://127.0.0.1:21815','http://192.168.1.215:10010'].includes(base))
const spec=JSON.parse(readFileSync(resolve(root,'contracts/local-api.openapi.json'),'utf8')),ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:runtime-config',components:spec.components})
const runId=randomUUID(),out=resolve(root,'docs/evidence/runtime-config-20261015');mkdirSync(out,{recursive:true})
const evidence={run_id:runId,base,started_at:new Date().toISOString(),requests:[],checks:[],credentials_recorded:false}
const save=()=>writeFileSync(resolve(out,'http-'+runId+'.json'),JSON.stringify(evidence,null,2)+'\n')
function check(label,condition){assert.ok(condition,label);evidence.checks.push({label,status:'PASS'});save()}
class Client{
 constructor(label){this.label=label;this.cookies=new Map();this.csrf=null}
 async call(label,path,method='GET',body,expected=200,options={}){
  const headers={Accept:'application/json',Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; ')}
  if(method!=='GET'){headers.Origin=base;if(!options.noCsrf&&this.csrf)headers['X-CSRF-Token']=this.csrf;headers['Idempotency-Key']=options.key||randomUUID()}
  if(body!==undefined)headers['Content-Type']='application/json'
  const response=await fetch(base+'/api/v1'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)})
  for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
  const payload=await response.json();evidence.requests.push({label,role:this.label,method,path:path.split('?')[0],status:response.status,code:payload.error?.code??null});save()
  if(response.status!==expected)throw Error(label+': expected '+expected+', got '+response.status+' '+(payload.error?.code??''))
  if(expected>=400)assert.ok(ajv.getSchema('urn:mop:runtime-config#/components/schemas/Error')(payload),'error envelope')
  if(expected===200&&path.startsWith('/runtime-configurations')){
   const pathname=path.split('?')[0],routeKey=spec.paths[pathname]?pathname:pathname.replace(/^\/runtime-configurations\/[^/]+/,'/runtime-configurations/{key}')
   const schema=spec.paths[routeKey][method.toLowerCase()].responses['200'].content['application/json'].schema
   const qualify=v=>Array.isArray(v)?v.map(qualify):v&&typeof v==='object'?Object.fromEntries(Object.entries(v).map(([k,x])=>[k,k==='$ref'&&x.startsWith('#/')?'urn:mop:runtime-config'+x:qualify(x)])):v
   const valid=ajv.compile(qualify(schema));if(!valid(payload))throw Error(label+': response contract mismatch '+ajv.errorsText(valid.errors))
  }
  return payload.data
 }
 async login(role){const user=cfg.test_users.find(u=>u.role===role);assert.ok(user,'Explicit test user exists');this.csrf=(await this.call('csrf','/auth/csrf')).csrf_token;this.session=await this.call('login','/auth/local/login','POST',{username:user.username,password:user.password});this.csrf=this.session.csrf_token}
}
const key='scope.extensions',admin=new Client('padmin'),reviewer=new Client('reviewer'),viewer=new Client('viewerA'),anonymous=new Client('anonymous')
let definitionHash='';
const command=(expected,revision,patch={})=>({expected_version:expected,expected_definition_hash:definitionHash,action:'PUBLISH',revision_id:revision.id,revision_version:revision.version,target_release_id:null,activation:'IMMEDIATE',effective_at:null,expires_at:null,reason:'SYNTHETIC_RUNTIME_CONFIG '+runId,...patch})
const current=()=>admin.call('read current','/runtime-configurations/'+key)
async function proposal(value){const r=await admin.call('create proposal','/configurations/'+key+'/revisions','POST',{proposed_value:value,reason:'SYNTHETIC_RUNTIME_CONFIG '+runId,evidence_refs:[{reference:'document-ref:synthetic-runtime-'+runId,title:'Explicit synthetic test; no customer production approval',sha256:'a'.repeat(64)}]});const s=await admin.call('submit proposal','/configuration-revisions/'+r.id+'/submit','POST',{expected_version:r.version});await admin.call('self review refused','/configuration-revisions/'+r.id+'/review','POST',{expected_version:s.version,decision:'approve',comment:'synthetic'},403);return reviewer.call('independent review','/configuration-revisions/'+r.id+'/review','POST',{expected_version:s.version,decision:'approve',comment:'SYNTHETIC_RUNTIME_CONFIG '+runId})}
try{
 const ready=await anonymous.call('readiness','/health/ready');check('real PG and runtime publication schema ready',ready.ready&&ready.durable&&ready.runtime_configurations?.schema_access_ready&&ready.production_ready===false)
 await anonymous.call('anonymous catalog refused','/runtime-configurations/catalog','GET',undefined,401)
 await admin.login('padmin');await reviewer.login('reviewer');await viewer.login('viewerA');assert.equal(admin.session.tenant.id,reviewer.session.tenant.id)
 await viewer.call('viewer catalog refused','/runtime-configurations/catalog','GET',undefined,403)
 const catalog=await admin.call('catalog','/runtime-configurations/catalog');definitionHash=catalog.items.find(x=>x.key===key).definition_hash;check('185 definitions cover all 31 questions',catalog.items.length===185&&new Set(catalog.items.flatMap(x=>x.question_ids)).size===31)
 check('pending decisions stay null',catalog.items.filter(x=>x.contract_value_kind==='pending_customer_decision').every(x=>x.registered_value===null))
 await admin.call('extra tenant query refused','/runtime-configurations/'+key+'?tenant_id='+admin.session.tenant.id,'GET',undefined,400)
 await admin.call('unknown configuration hidden','/runtime-configurations/not.registered','GET',undefined,404)
 await admin.call('invalid scalar bound','/runtime-configurations/monitor.page_refresh_sec/validate','POST',{value:0},400)
 await admin.call('invalid enum','/runtime-configurations/monitor.sample_interval_sec/validate','POST',{value:90},400)
 await admin.call('forbidden secret','/runtime-configurations/notification.provider_credentials/validate','POST',{value:{token:'synthetic-placeholder'}},400)
 await admin.call('missing csrf','/runtime-configurations/'+key+'/validate','POST',{value:{label:'synthetic'}},403,{noCsrf:true})
 const baseline=await current();if(baseline.selected_release)assert.ok(baseline.selected_release.reason.startsWith('SYNTHETIC_RUNTIME_CONFIG'),'Refuse to overwrite non-test settings')
 const beforeRegistry=(await admin.call('registry before','/configurations')).items.find(x=>x.key===key)
 const p=await proposal({synthetic_run:runId,enabled:false,version:1});await admin.call('candidate validate','/runtime-configurations/'+key+'/validate','POST',{value:p.proposed_value})
 const input=command(baseline.latest_version,p),idempotency=randomUUID();await admin.call('changed definition refused','/runtime-configurations/'+key+'/releases','POST',{...input,expected_definition_hash:'0'.repeat(64)},409);const first=await admin.call('publish','/runtime-configurations/'+key+'/releases','POST',input,200,{key:idempotency})
 check('release is immutable test scope with distinct proposal',first.configuration_key===key&&first.revision_id===p.id&&first.customer_confirmation_status==='UNVERIFIED'&&first.production_approved===false)
 const replay=await admin.call('replay original publication','/runtime-configurations/'+key+'/releases','POST',input,200,{key:idempotency});assert.deepEqual(replay,first);check('replay returns original receipt',true)
 await admin.call('same key different payload refused','/runtime-configurations/'+key+'/releases','POST',{...input,reason:'changed'},409,{key:idempotency})
 await admin.call('stale publication version refused','/runtime-configurations/'+key+'/releases','POST',input,409)
 const active=await current();check('effective value reflects published false without fallback',active.status==='ACTIVE'&&active.effective_value.enabled===false&&active.selected_release.id===first.id)
 const p2=await proposal({synthetic_run:runId,enabled:true,version:2}),second=await admin.call('second publication','/runtime-configurations/'+key+'/releases','POST',command(active.latest_version,p2))
 const rollback=await admin.call('rollback as new immutable version','/runtime-configurations/'+key+'/releases','POST',command(second.version,p,{action:'ROLLBACK',revision_id:null,revision_version:null,target_release_id:first.id}));assert.deepEqual(rollback.value,first.value);check('rollback preserves source and creates a later version',rollback.version>second.version)
 await admin.call('forged publisher refused','/runtime-configurations/'+key+'/releases','POST',{...command(rollback.version,p),published_by:reviewer.session.user.id},400)
 const disabled=await admin.call('disable','/runtime-configurations/'+key+'/releases','POST',command(rollback.version,p,{action:'DISABLE',revision_id:null,revision_version:null}));const after=await current();check('disabled never resurrects an old value',after.status==='DISABLED'&&after.effective_value===null&&after.latest_version===disabled.version)
 const history=await admin.call('history','/runtime-configurations/'+key+'/history');check('publication history retains original values',history.items.some(x=>x.id===first.id)&&history.items.some(x=>x.id===second.id)&&history.items.some(x=>x.id===rollback.id))
 const afterRegistry=(await admin.call('registry after','/configurations')).items.find(x=>x.key===key);assert.deepEqual(afterRegistry,beforeRegistry);check('design registry and customer decision remain unchanged',true)
 evidence.finished_at=new Date().toISOString();evidence.status='PASS';evidence.cleanup='Synthetic test runtime key explicitly disabled; proposals and immutable history retained';save();console.log(JSON.stringify({status:evidence.status,requests:evidence.requests.length,checks:evidence.checks.length,run_id:runId}))
}catch(error){evidence.status='FAILED';evidence.failure={type:error.name,message:error.message?.slice(0,180)};save();console.error(JSON.stringify(evidence.failure));process.exitCode=1}
