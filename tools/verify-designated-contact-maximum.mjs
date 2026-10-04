// One explicit 100-row synthetic batch through real 202/API/dedicated Windows Worker.
import assert from 'node:assert/strict';
import {existsSync,readFileSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
const root=new URL('../',import.meta.url),base='http://127.0.0.1:8080/api/v1';
const output=new URL('docs/evidence/local-contact-maximum-worker-20261004.json',root),controlFile=new URL('.local/designated-test/contact-maximum-control.json',root);
let auth,stage='preconditions',requests=0,job=null,body,key;
const control={pending:null,job_id:null,cleanup_complete:false};
const save=()=>writeFileSync(controlFile,JSON.stringify(control,null,2)+'\n');
async function req(method,path,value,status=200,idem=randomUUID()){
 requests++;const response=await fetch(base+path,{method,headers:{Cookie:auth.cookie,'X-CSRF-Token':auth.csrf_token,'Idempotency-Key':idem,'Content-Type':'application/json'},...(value?{body:JSON.stringify(value)}:{}),signal:AbortSignal.timeout(30000)});
 assert.equal(response.status,status);const result=await response.json();assert.ok(result.data);assert.equal(typeof result.trace_id,'string');return result.data;
}
try{
 assert.equal(existsSync(output),false);assert.equal(existsSync(controlFile),false);
 auth=JSON.parse(readFileSync(new URL('.local/designated-test/private/sessions/qa-padmin.json',root),'utf8'));assert.equal(auth.environment,'designated-server');
 const processState=JSON.parse(readFileSync(new URL('.local/processes.json',root),'utf8').replace(/^\uFEFF/,''));assert.equal(processState.contact_import_worker_enabled,true);
 const session=await req('GET','/session');auth.csrf_token=session.csrf_token;assert.equal(session.user.role,'padmin');assert.equal(session.mode,'remote_test');
 const runtime=JSON.parse(readFileSync(new URL('deploy/private/runtime.json',root),'utf8').replace(/^\uFEFF/,''));assert.equal(runtime.environment.MOP_CONTACT_IMPORT_LEASE_MILLIS,'300000');
 const marker='SYNTHETIC_MAXIMUM_WINDOWS_'+randomUUID();body={expected_version:0,mode:'CREATE_ONLY',acknowledge_exact_duplicate_rows:false,rows:Array.from({length:100},(_,i)=>({name:marker+'_'+i,roles:['REPORTER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:null,quiet_window:null,enabled:false}))};key=randomUUID();
 control.pending={body,key,actor_id:session.user.id,tenant_id:session.tenant.id};writeFileSync(controlFile,JSON.stringify(control)+'\n',{flag:'wx'});
 stage='real_202';const start=Date.now(),receipt=await req('POST','/contact-imports',body,202,key);assert.equal(receipt.status,'PENDING');control.job_id=receipt.operation_id;save();
 stage='actual_dedicated_worker';const deadline=Date.now()+330000;let observedRunning=false;
 do{job=await req('GET','/contact-imports/'+receipt.operation_id);observedRunning||=job.status==='RUNNING';assert.notEqual(job.status,'FAILED');if(job.status==='SUCCEEDED')break;assert.ok(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,2000))}while(true);
 const elapsed=Date.now()-start;assert.equal(job.completed_rows,100);assert.equal(job.results.length,100);assert.equal(new Set(job.results.map(x=>x.contact_id)).size,100);assert.ok(job.results.every((x,i)=>x.row_number===i+1&&x.contact_version===1));
 stage='original_receipt_replay';assert.deepEqual(await req('POST','/contact-imports',body,202,key),receipt);control.pending=null;save();
 stage='archive_only_own_synthetic_contacts';for(const row of job.results){const current=await req('GET','/contacts/'+row.contact_id);assert.ok(current.name.startsWith(marker));assert.equal(current.enabled,false);if(!current.archived)await req('POST','/contacts/'+row.contact_id+'/archive',{expected_version:current.version})}
 control.cleanup_complete=true;save();
 const evidence={verified_at:new Date().toISOString(),environment:'designated-server-from-Windows',api_contract_version:JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8')).info.version,actual_http_requests:requests,actual_job_rows:100,all_100_distinct_first_revisions:true,observed_running:observedRunning,observed_202_to_completion_ms:elapsed,measurement_is_not_sla_or_capacity_benchmark:true,technical_worker_lease_millis:300000,original_202_receipt_replayed_after_completion:true,all_own_synthetic_contacts_archived:true,notifications_sent:0,identity_bindings_created:0,formal_acceptance:'NOT_RUN'};
 writeFileSync(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,rows:100,requests,all_own_synthetic_contacts_archived:true}));
}catch{console.log(JSON.stringify({status:'FAILED',stage,details:'redacted',pending_request_preserved:Boolean(control.pending),known_job:control.job_id!==null}));process.exitCode=1}
