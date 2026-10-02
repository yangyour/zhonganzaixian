// Actual bundled frontend state and ApiClient with real PG/OIDC; controlled loss of successful responses.
// Does not operate browser controls or claim physical network failure recovery.
import assert from 'node:assert/strict';
import { readFileSync,writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { ApiClient,createWorklogWorkspace } from '../.local/niko-test/worklog-client.mjs';
const root=new URL('../',import.meta.url);let stage='initialization';
try{
 const sessions=Object.fromEntries(['msp','viewerA','padmin'].map(role=>{const value=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`,root),'utf8'));assert.equal(value.environment,'niko-local-test');return [role,value];}));
 let requests=0,dropMethod='POST';const writes=[],droppedTraces=[];
 function client(role,inject=false){const session=sessions[role];const value=new ApiClient(async(url,options)=>{
  const response=await fetch(url,{...options,headers:{...options.headers,Cookie:session.cookie},signal:options.signal??AbortSignal.timeout(15000)});requests++;
  if(inject&&['POST','PATCH'].includes(options.method)&&String(url).includes('/worklogs')){
   writes.push({method:options.method,key:options.headers['Idempotency-Key'],payload:options.body,status:response.status});
   if(options.method===dropMethod&&response.status===200){droppedTraces.push((await response.clone().json()).trace_id);dropMethod='';throw new TypeError('Synthetic loss after server200');}
  }
  return response;
 },'http://127.0.0.1:8080/api/v1');value.setCsrfToken(session.csrf_token);return value;}
 const api=client('msp',true),other=client('msp'),viewer=client('viewerA'),admin=client('padmin');
 stage='real_context';const current=await api.request('/session');assert.equal(current.capabilities.includes('worklogs:write_own'),true);
 const accepted=await viewer.request('/tickets',{method:'POST',body:{title:'WORKLOG-CLIENT-'+randomUUID(),source:'customer_report',description:'虚构前端状态联调父登记',category:'网络不通',impact_scope:'individual',contact_name:'虚构联系人',contact_phone:'+00 000',urgency:'normal',attachment_ids:[]}});
 const operation=await viewer.request('/operations/'+accepted.operation_id),ticket=operation.result_ref.split('/').at(-1),path='/tickets/'+ticket+'/worklogs';
 let state;const workspace=createWorklogWorkspace(api,value=>{state=value});workspace.reset({tenantId:current.tenant.id,ticketId:ticket,actorId:current.user.id,permissionVersion:current.permission_version,canWrite:true});
 stage='read_first_page';assert.equal(await workspace.refresh(),true);assert.equal(state.page.items.length,0);
 const facts={kind:'REMOTE',started_at:'2026-10-02T09:00:00+08:00',ended_at:'2026-10-02T10:30:00+08:00',note:null};
 stage='drop_created_response';assert.equal(await workspace.submit(facts),false);assert.equal(workspace.unresolved(),true);facts.note='must-not-replace-original';
 stage='readonly_refresh';assert.equal(await workspace.refresh(),true);assert.equal(writes.length,1);assert.equal(state.page.items.length,1);assert.equal(workspace.unresolved(),true);
 stage='retry_creation';assert.equal(await workspace.retry(),true);assert.deepEqual(writes[0],writes[1]);assert.equal(state.receipt.note,null);assert.equal(state.receipt.version,1);const id=state.receipt.id;assert.equal(workspace.unresolved(),false);
 stage='read_record_and_history';assert.equal(await workspace.show(id),true);assert.deepEqual(state.history.items.map(x=>x.version),[1]);
 stage='independent_client_update';const changed=await other.request(path+'/'+id,{method:'PATCH',body:{expected_version:1,...facts,note:'另一客户端明确修改'}});assert.equal(changed.version,2);
 stage='stale_editor_gets409';assert.equal(await workspace.submit({...facts,note:'旧版本修改'}, {id,version:1}),false);assert.equal(state.attempt.state,'rejected');assert.equal(writes.at(-1).status,409);assert.equal(await workspace.retry(),false);
 stage='refresh_then_new_explicit_edit';assert.equal(await workspace.refresh(),true);assert.equal(await workspace.show(id),true);assert.equal(state.selected.version,2);dropMethod='PATCH';
 assert.equal(await workspace.submit({...facts,kind:'ONSITE',note:'新版本明确修改'}, {id,version:2}),false);assert.equal(workspace.unresolved(),true);
 stage='readonly_refresh_after_lost_update';assert.equal(await workspace.refresh(),true);const beforeRetry=writes.length;assert.equal(state.page.items[0].version,3);assert.equal(workspace.unresolved(),true);
 stage='retry_original_update';assert.equal(await workspace.retry(),true);assert.equal(writes.length,beforeRetry+1);assert.deepEqual(writes.at(-1),writes.at(-2));assert.equal(state.receipt.version,3);assert.equal(state.receipt.kind,'ONSITE');assert.equal(state.receipt.labor_cost,null);
 stage='immutable_history';assert.equal(await workspace.show(id),true);assert.deepEqual(state.history.items.map(x=>x.version),[3,2,1]);assert.equal(state.history.items[2].note,null);
 stage='audit_once_per_committed_mutation';const audit=(await admin.request('/audit')).items;for(const trace of droppedTraces)assert.equal(audit.filter(x=>x.resource_id===id&&x.trace_id===trace).length,1);assert.equal(audit.filter(x=>x.resource_id===id&&x.resource_type==='worklog').length,3);
 workspace.dispose();assert.equal(state.receipt,null);assert.equal(await workspace.retry(),false);
 const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',passed:true,http_requests:requests,frontend:'actual WorklogWorkspace state + ApiClient bundle',write_attempts:writes.length,worklog_versions:3,original_create_and_update_key_body_reused:true,readonly_refresh_never_wrote:true,stale_editor_http_status:409,history_versions:[3,2,1],matching_mutation_audits:3,fault_injection:'Intentionally discard two successful server200 responses in client transport; not physical network failure',browser_ui_tested:false,credentials_output:false,remaining:['DOM controls and390px visual acceptance','OSS and original server network','Full M5-21/M10-13 and customer financial decisions']};
 writeFileSync(new URL('docs/evidence/niko-worklog-client-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_requests:requests,write_attempts:writes.length,worklog_versions:3,browser_ui_tested:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
