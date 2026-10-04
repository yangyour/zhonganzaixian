// Real PG/OIDC preparation acceptance only. Does not start a Worker, publish, download, or change roles.
import assert from 'node:assert/strict'
import {existsSync,readFileSync,writeFileSync} from 'node:fs'
import {randomUUID} from 'node:crypto'
import Ajv from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
const root=new URL('../',import.meta.url),base='http://127.0.0.1:8080/api/v1',path='/asset-export-jobs'
const retry=process.env.MOP_EXPORT_HTTP_RETRY_SESSION_SHAPE==='true'
const suffix=retry?'-r2':'',output=new URL('docs/evidence/niko-export-jobs-http-20261004'+suffix+'.json',root),controlFile=new URL('.local/niko-test/export-jobs-api-control'+suffix+'.json',root)
if(retry){const previous=JSON.parse(readFileSync(new URL('docs/evidence/niko-export-jobs-http-20261004.json',root),'utf8')),old=JSON.parse(readFileSync(new URL('.local/niko-test/export-jobs-api-control.json',root),'utf8'));assert.equal(previous.completed,false);assert.equal(previous.write_requests,0);assert.equal(previous.failure?.stage,'actual_sessions');assert.equal(old.jobs.length,0);assert.equal(old.pending.length,0)}
const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));assert.equal(contract.info.version,'0.42.0');const ajv=new Ajv({strict:false});addFormats(ajv);ajv.addFormat('binary',true);ajv.addSchema({$id:'urn:mop:actual-api',components:contract.components})
let stage='init',initialized=false,requests=0,writes=0,statuses={},failure=null,auditStable=false,pagingFollowed=false,retainedPreparedSeen=false
const auth={},control={marker:'SYNTHETIC_EXPORT_HTTP_'+randomUUID(),jobs:[],pending:[],receipts:[],completed:false}
const save=()=>writeFileSync(controlFile,JSON.stringify(control,null,2)+'\n',{mode:0o600})
async function request(role,method,route,{body,raw,status=200,schema,key,csrf=true,contentType='application/json'}={}){
 const a=role?auth[role]:null,headers={Accept:'application/json'};if(a)headers.Cookie=a.cookie;if(method!=='GET'){headers['Content-Type']=contentType;if(csrf&&a)headers['X-CSRF-Token']=a.csrf_token;if(key!==null)headers['Idempotency-Key']=key??randomUUID()}
 const command=method==='GET'?null:{id:randomUUID(),role,method,path:route,key:headers['Idempotency-Key']??null,body:body??null,raw:raw??null};if(command){control.pending.push(command);writes++;save()}
 requests++;const response=await fetch(base+route,{method,headers,body:raw??(body===undefined?undefined:JSON.stringify(body)),redirect:'error',signal:AbortSignal.timeout(20000)});statuses[response.status]=(statuses[response.status]??0)+1;const value=await response.json();assert.equal(response.status,status,'unexpected status at '+stage)
 if(schema){const check=ajv.getSchema('urn:mop:actual-api#/components/schemas/'+schema);assert.ok(check);assert.equal(check(value),true,'schema mismatch '+schema)}
 if(command){control.pending=control.pending.filter(v=>v.id!==command.id);control.receipts.push({...command,status:response.status,data:value.data??null});save()}return value.data??value
}
const input=(query)=>({expected_version:0,mode:'PREPARE_ONLY_NO_PUBLICATION',filters:{type:null,status:null,site_id:null,query}})
const detail=(role,id)=>request(role,'GET',path+'/'+id,{schema:'AssetExportJobResult'})
const list=(role,q={})=>request(role,'GET',path+'?'+new URLSearchParams(q),{schema:'AssetExportJobListResult'})
try{
 assert.equal(existsSync(output),false);assert.equal(existsSync(controlFile),false);initialized=true;save()
 for(const role of ['padmin','msp','tadmin','viewerA','viewerB'])auth[role]=JSON.parse(readFileSync(new URL('.local/niko-test/private/sessions/qa-'+role+'.json',root),'utf8'))
 stage='actual_sessions';for(const role of Object.keys(auth)){const current=await request(role,'GET','/session');assert.equal(current.user.id,auth[role].user_id);assert.equal(current.mode,'remote_test');assert.equal(auth[role].environment,'niko-local-test');auth[role].session=current;auth[role].csrf_token=current.csrf_token}
 stage='preparation_only_acceptance';const body=input(control.marker),key=randomUUID();const first=await request('padmin','POST',path,{body,key,status:202,schema:'AssetExportReceipt'});control.jobs.push({id:first.job_id,role:'padmin'});save();assert.equal(first.status,'QUEUED');assert.equal(first.download_allowed,false)
 const replay=await request('padmin','POST',path,{body,key,status:202,schema:'AssetExportReceipt'});assert.deepEqual(replay,first)
 const reordered={filters:{query:body.filters.query,site_id:null,status:null,type:null},mode:body.mode,expected_version:0};assert.deepEqual(await request('padmin','POST',path,{body:reordered,key,status:202,schema:'AssetExportReceipt'}),first)
 await request('padmin','POST',path,{body:input(control.marker+'_changed'),key,status:409})
 const second=await request('msp','POST',path,{body:input(control.marker+'_msp'),status:202,schema:'AssetExportReceipt'});control.jobs.push({id:second.job_id,role:'msp'});save()
 const third=await request('padmin','POST',path,{body:{...input(control.marker+'_empty_site'),filters:{...body.filters,site_id:''}},status:202,schema:'AssetExportReceipt'});control.jobs.push({id:third.job_id,role:'padmin'});save()
 stage='readonly_progress_and_originals';const before=await request('padmin','GET','/audit/records?page_size=10');for(const item of control.jobs){const d=await detail(item.role,item.id);assert.equal(d.status,'QUEUED');assert.equal(d.version,0);assert.equal(d.attempts,0);assert.equal(d.prepared,null);assert.equal(d.error_code,null);assert.equal(d.worker_liveness,null);assert.equal(d.download_allowed,false);assert.equal(d.object_verification,'NOT_CHECKED_BY_PROGRESS_READER');assert.equal(d.created_by,auth[item.role].session.user.id)}
 const page=await list('padmin',{page_size:'10'});for(const item of control.jobs.filter(x=>x.role==='padmin'))assert.ok(page.items.some(x=>x.id===item.id));assert.equal(page.worker_liveness,null)
 if(page.has_more){const next=await list('padmin',{page_size:'10',before_created_at:page.next_before_created_at,before_id:page.next_before_id});assert.equal(next.items.some(r=>page.items.some(p=>p.id===r.id)),false);pagingFollowed=true
  for(const row of [...page.items,...next.items])if(['RUNNING','RECONCILING_STORAGE'].includes(row.status)){const progress=await detail('padmin',row.id);if(progress.prepared){assert.equal(progress.download_allowed,false);assert.equal(progress.worker_liveness,null);retainedPreparedSeen=true;break}}
 }
 const msp=await list('msp');assert.ok(msp.items.some(x=>x.id===second.job_id));assert.equal(msp.items.some(x=>x.id===first.job_id),false)
 const after=await request('padmin','GET','/audit/records?page_size=10');assert.equal(after.snapshot_sequence,before.snapshot_sequence);auditStable=true
 stage='role_scope_csrf_input_denials';await request('msp','GET',path+'/'+first.job_id,{status:404});await request('padmin','GET',path+'/'+second.job_id,{status:404});await request('padmin','GET',path+'/'+randomUUID(),{status:404})
 for(const role of ['tadmin','viewerA','viewerB']){await request(role,'GET',path,{status:403});await request(role,'GET',path+'/'+first.job_id,{status:403});await request(role,'POST',path,{body,status:403})}await request(null,'GET',path,{status:401});await request('padmin','POST',path,{body,csrf:false,status:403});await request('padmin','POST',path,{body,key:null,status:400})
 for(const q of ['owner_id='+first.job_id,'tenant_id='+auth.padmin.session.tenant.id,'download=true','page_size=100','page_size=10&page_size=15','before_id='+first.job_id,'before_created_at=bad&before_id='+first.job_id])await request('padmin','GET',path+'?'+q,{status:400})
 for(const invalid of [{...body,mode:'PUBLISH'},{...body,expected_version:1},{...body,tenant_id:auth.padmin.session.tenant.id},{...body,filters:{type:null}},{...body,filters:{...body.filters,status:'healthy'}}])await request('padmin','POST',path,{body:invalid,status:400})
 await request('padmin','POST',path,{raw:JSON.stringify(body)+' {}',status:400});await request('padmin','POST',path,{raw:'{"expected_version":0,"expected_version":0}',status:400});await request('padmin','POST',path,{raw:' '.repeat(8193),status:413})
 stage='original_receipt_after_reads';assert.deepEqual(await request('padmin','POST',path,{body,key,status:202,schema:'AssetExportReceipt'}),first);for(const item of control.jobs)assert.equal((await detail(item.role,item.id)).status,'QUEUED');control.completed=true
}catch(error){failure={stage,type:error?.constructor?.name??'Error',details:'Incomplete; preserve original requests and receipts, no blind rerun.'};process.exitCode=1}
finally{if(initialized){save();const result={retry_after_session_shape_failure:retry,verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:'0.42.0',actual_pg_oidc:true,fixture_adapter:false,http_requests:requests,write_requests:writes,statuses,completed:control.completed,pending_original_requests:control.pending.length,owned_jobs:control.jobs.length,successful_readonly_window_audit_unchanged:auditStable,actual_paging_followed:pagingFollowed,existing_prepared_descriptor_read:retainedPreparedSeen,worker_started:false,object_store_requests:0,download_requests:0,notification_sent:false,new_identity_or_credentials:false,new_migrations:0,browser:false,failure,formal_acceptance:'NOT_RUN'};writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result))}}
