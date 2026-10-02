// Execute the actual bundled frontend readback/workspace code against real HTTP.
// This is not a browser/DOM test and deliberately forbids all writes.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createDependencyWorkspace, readDependencyDraft } from '../.local/niko-test/dependency-client.mjs';
const root=new URL('../',import.meta.url);
let stage='initialization';
try{
  const session=JSON.parse(readFileSync(new URL('.local/niko-test/private/sessions/qa-msp.json',root),'utf8'));
  assert.equal(session.environment,'niko-local-test');let reads=0;
  const client={async request(path,options={}){
    assert.equal(options.method??'GET','GET');assert.equal(options.body,undefined);assert.match(path,/^\/(session|assets)(?:[/?]|$)/);
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{headers:{Cookie:session.cookie},signal:options.signal??AbortSignal.timeout(12000),redirect:'manual'});assert.equal(response.status,200);reads++;return (await response.json()).data;
  }};
  stage='trusted_session';const current=await client.request('/session');assert.equal(current.mode,'remote_test');assert.equal(current.identity_source,'test_oidc');
  const assets=await client.request('/assets?page_size=100&q=DEPENDENCY-HTTP-');assert.ok(assets.items.length);
  const asset=assets.items[0];assert.equal(asset.tenant_id,current.tenant.id);
  const context={tenantId:current.tenant.id,assetId:asset.id,actorId:current.user.id,permissionVersion:current.permission_version,canWrite:false};
  let state;const workspace=createDependencyWorkspace(client,value=>{state=value},()=>{throw new Error('Read-only test must not allocate a write key')});workspace.reset(context);
  stage='actual_frontend_readback';assert.equal(await workspace.refresh(),true);assert.ok(state.draft);const first=structuredClone(state.draft);assert.equal(first.monitoring_applied,false);assert.equal(first.effective_rules,null);assert.equal(first.complete,true);
  assert.equal(await workspace.refresh(),true);assert.deepEqual(state.draft,first);assert.equal(state.attempt,null);assert.equal(state.receipt,null);
  stage='response_binding';assert.throws(()=>readDependencyDraft({...first,tenant_id:'00000000-0000-4000-8000-000000000000'},context));assert.throws(()=>readDependencyDraft({...first,monitoring_applied:true},context));
  workspace.reset(null);assert.equal(await workspace.refresh(),false);assert.equal(state.draft,null);workspace.dispose();assert.equal(reads,4);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',scope:'actual frontend TypeScript workspace/readback over real PG/OIDC HTTP',http_gets:reads,http_writes:0,passed:true,checks:['Real response accepted by strict frontend binding/graph validator','Repeated refresh GET-only and identical snapshot','No write key/attempt/receipt manufactured','Wrong tenant and applied-monitoring claims rejected','Context reset removes displayed graph and prevents further read'],browser_ui_tested:false,visual_acceptance:'NOT_RUN',credentials_output:false};
  writeFileSync(new URL('docs/evidence/niko-dependency-client-readback-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_gets:reads,http_writes:0,browser_ui_tested:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
