// Run only before/after the explicitly owned API process kill in run-stack fault mode.
// All business facts are synthetic; old/new sessions stay private and never enter evidence.
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {randomUUID,createHash} from 'node:crypto';
const root=new URL('../',import.meta.url),checkpoint=new URL('.local/niko-test/api-crash-http-before.json',root),output=new URL('docs/evidence/niko-api-crash-recovery-20261002.json',root);
const mode=process.argv[2];let stage='initialization';
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
const digest=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
try{
 assert.ok(['before','after'].includes(mode));assert.equal(existsSync(output),false);if(mode==='before')assert.equal(existsSync(checkpoint),false);
 const sessions=Object.fromEntries(['msp','viewerA','padmin'].map(role=>{const s=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`,root),'utf8'));assert.equal(s.environment,'niko-local-test');return[role,s];}));let requests=0;
 async function http(role,method,path,body,key){const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers:{Cookie:sessions[role].cookie,'X-CSRF-Token':sessions[role].csrf_token,'Idempotency-Key':key??randomUUID(),'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),redirect:'manual',signal:AbortSignal.timeout(20000)});requests++;assert.ok([200,202].includes(response.status));return (await response.json()).data;}
 if(mode==='before'){
  stage='create_synthetic_registration';const accepted=await http('viewerA','POST','/tickets',{title:'API-CRASH-'+randomUUID(),source:'customer_report',description:'虚构API突然中断持久性验证',category:'网络不通',impact_scope:'individual',contact_name:'虚构联系人',contact_phone:'+00 000',urgency:'normal',attachment_ids:[]});const operation=await http('viewerA','GET','/operations/'+accepted.operation_id);const ticket=operation.result_ref.split('/').at(-1);
  const facts={kind:'REMOTE',started_at:'2026-10-02T00:00:00Z',ended_at:'2026-10-02T01:00:00Z',note:'明确提交的合成工时'},createBody={expected_ticket_version:1,...facts},createKey=randomUUID();
  stage='commit_worklog_v1';const created=await http('msp','POST',`/tickets/${ticket}/worklogs`,createBody,createKey);
  const updateBody={expected_version:1,...facts,kind:'ONSITE',ended_at:'2026-10-02T02:00:00Z',note:'明确提交的第二版合成工时'},updateKey=randomUUID();stage='commit_worklog_v2';const updated=await http('msp','PATCH',`/tickets/${ticket}/worklogs/${created.id}`,updateBody,updateKey);assert.equal(updated.version,2);
  stage='confirmed_pre_crash_readback';const parent=await http('msp','GET','/tickets/'+ticket),history=await http('msp','GET',`/tickets/${ticket}/worklogs/${created.id}/history`);assert.deepEqual(history.items.map(x=>x.version),[2,1]);
  const audit=await http('padmin','GET','/audit/records?resource_type=worklog&resource_id='+created.id);assert.equal(audit.items.length,2);
  writeFileSync(checkpoint,JSON.stringify({recorded_at:new Date().toISOString(),ticket,id:created.id,createBody,createKey,updateBody,updateKey,created,updated,parent,history,worklog_audit_count:2,http_before:requests})+'\n',{mode:0o600,flag:'wx'});console.log(JSON.stringify({status:'pre_crash_committed',http_requests:requests,worklog_version:2,credentials_output:false}));
 }else{
  const before=JSON.parse(readFileSync(checkpoint,'utf8')),database=JSON.parse(readFileSync(new URL('.local/niko-test/api-crash-db-verified.json',root),'utf8'));assert.equal(database.database_fingerprints_equal,true);assert.equal(database.api_exit_code,137);
  const path=`/tickets/${before.ticket}/worklogs/${before.id}`;
  stage='post_crash_record_and_history';const current=await http('msp','GET',path),history=await http('msp','GET',path+'/history'),parent=await http('msp','GET','/tickets/'+before.ticket);assert.deepEqual(current,before.updated);assert.deepEqual(history,before.history);assert.deepEqual(parent,before.parent);
  stage='replay_original_create_after_restart';const replay=await http('msp','POST',`/tickets/${before.ticket}/worklogs`,before.createBody,before.createKey);assert.deepEqual(replay,before.created);assert.equal(replay.version,1);
  stage='replay_original_update_after_restart';const updatedReplay=await http('msp','PATCH',path,before.updateBody,before.updateKey);assert.deepEqual(updatedReplay,before.updated);assert.deepEqual(await http('msp','GET',path),before.updated);assert.deepEqual(await http('msp','GET',path+'/history'),before.history);
  stage='replays_do_not_duplicate_audits';const audit=await http('padmin','GET','/audit/records?resource_type=worklog&resource_id='+before.id);assert.equal(audit.items.length,before.worklog_audit_count);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',passed:true,fault:'SIGKILL of verified owned API child only; wait exit137, confirmed connection refusal, restart same built API',api_exit_code:137,owned_process_identity_guard:true,postgres_restarted:false,keycloak_restarted:false,api_auto_migration:false,http_before:before.http_before,http_after:requests,new_real_oidc_sessions_after_restart:true,database_fingerprints_equal:true,tables:database.tables,worklog_version_after:2,history_versions_after:[2,1],original_create_replay_version:1,original_update_replay_version:2,matching_worklog_audit_records:2,readback_sha256:digest({current,history,parent}),credentials_output:false,limitations:['Writes were confirmed committed before abrupt termination; not kill during an in-flight transaction','Not PostgreSQL/WAL crash recovery','No Worker or OSS fault/restart verification','No browser DOM/session UX acceptance','No original server lifecycle changes']};
  writeFileSync(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_before:before.http_before,http_after:requests,tables_equal:Object.keys(database.tables).length,api_exit_code:137,credentials_output:false}));
 }
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
