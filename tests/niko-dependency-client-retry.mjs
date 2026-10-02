// Actual frontend workspace against real PG/OIDC HTTP, with one deliberately dropped successful response.
// No browser/DOM, fixture repository, monitoring operation or storage provider is used.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { createDependencyWorkspace } from '../.local/niko-test/dependency-client.mjs';
const root=new URL('../',import.meta.url);let stage='initialization';
try{
  const sessions=Object.fromEntries(['msp','padmin'].map(role=>{const s=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`,root),'utf8'));assert.equal(s.environment,'niko-local-test');return [role,s];}));
  let httpRequests=0,dropNext=true,originalTrace;const posts=[];
  async function http(role,path,options={}){
    const method=options.method??'GET';const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers:{Cookie:sessions[role].cookie,'X-CSRF-Token':sessions[role].csrf_token,'Idempotency-Key':options.idempotencyKey??randomUUID(),'Content-Type':'application/json'},body:options.body===undefined?undefined:JSON.stringify(options.body),signal:options.signal??AbortSignal.timeout(15000),redirect:'manual'});
    httpRequests++;assert.equal(response.status,200);return response.json();
  }
  stage='real_context';const current=(await http('msp','/session')).data;
  async function asset(){const run=randomUUID();return (await http('msp','/assets',{method:'POST',body:{name:'DEPENDENCY-CLIENT-'+run,type:'switch',management_ip:`2001:db8:${run.slice(0,4)}:${run.slice(4,8)}::1`,site_id:'site-a-main'}})).data.id;}
  const a=await asset(),b=await asset();let state;
  const client={async request(path,options={}){
    const result=await http('msp',path,options);
    if(options.method==='POST'){
      assert.equal(path,`/assets/${a}/dependencies`);posts.push({key:options.idempotencyKey,payload:JSON.stringify(options.body)});
      if(dropNext){dropNext=false;originalTrace=result.trace_id;throw new TypeError('Synthetic response loss after confirmed server 200');}
    }
    return result.data;
  }};
  const workspace=createDependencyWorkspace(client,value=>{state=value});
  workspace.reset({tenantId:current.tenant.id,assetId:a,actorId:current.user.id,permissionVersion:current.permission_version,canWrite:true});
  stage='load_real_graph';assert.equal(await workspace.refresh(),true);const before=structuredClone(state.draft);
  const proposed={parent_id:a,child_id:b,proposed_type:null,proposed_suppression_scope:null,proposed_enabled:null};
  stage='drop_successful_write_response';assert.equal(await workspace.submit(proposed),false);assert.equal(workspace.unresolved(),true);assert.equal(state.receipt,null);assert.equal(posts.length,1);
  proposed.proposed_type='must-not-replace-original-request';
  stage='readonly_refresh_preserves_uncertainty';assert.equal(await workspace.refresh(),true);assert.equal(state.draft.version,before.version+1);assert.equal(workspace.unresolved(),true);assert.equal(posts.length,1);
  stage='explicit_original_request_retry';assert.equal(await workspace.retry(),true);assert.equal(posts.length,2);assert.deepEqual(posts[0],posts[1]);assert.equal(JSON.parse(posts[1].payload).expected_version,before.version);assert.equal(JSON.parse(posts[1].payload).additions[0].proposed_type,null);
  assert.equal(workspace.unresolved(),false);assert.equal(state.receipt.version,before.version+1);assert.equal(state.draft.version,before.version+1);assert.equal(state.draft.proposed_edges.length,before.proposed_edges.length+1);assert.equal(state.draft.monitoring_applied,false);assert.equal(state.draft.effective_rules,null);
  stage='one_durable_audit';const audit=(await http('padmin','/audit')).data.items;assert.equal(audit.filter(x=>x.action==='asset_dependency.draft.append'&&x.trace_id===originalTrace).length,1);workspace.dispose();
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',scope:'actual frontend workspace with real HTTP/PG and controlled response-loss injection',passed:true,http_requests:httpRequests,dependency_post_attempts:2,committed_graph_versions:1,original_key_and_body_reused:true,readonly_refresh_did_not_resubmit:true,caller_edit_did_not_replace_original_body:true,matching_original_audit_records:1,monitoring_applied:false,effective_rules:null,fault_injection:'Client intentionally discards first successful server 200; not a physical network outage',browser_ui_tested:false,credentials_output:false,remaining:['DOM controls and visual acceptance','Actual network/Worker fault recovery','Monitoring publication']};
  writeFileSync(new URL('docs/evidence/niko-dependency-client-retry-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_requests:httpRequests,dependency_post_attempts:2,committed_graph_versions:1,browser_ui_tested:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
