import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, existsSync, unlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

// Run only after the new application was built and started against designated components.
const root=resolve(fileURLToPath(new URL('../',import.meta.url))),run=randomUUID();
const key='mop.fake.diagnostic.http.'+run.replaceAll('-',''),fakeMarker='MOP-FAKE-DIAGNOSTIC-'+run;
const marker=join(root,'.local/postgres-forward-disabled'),exec=promisify(execFile);
const A='55000000-0000-4000-8000-000000000001',B='55000000-0000-4000-8000-000000000002';
let stage='initialization',lastHttpStatus,markerOwned=false,sessionChanged=false,passed=false,sessions={};
const checks=[],contract=JSON.parse(readFileSync(join(root,'contracts/local-api.openapi.json'),'utf8'));
const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:configuration-diagnostic-http',components:contract.components});const validators=new Map();
const evidence={mode:'remote_test',passed:false,checks,credential_output:'none',migrations_executed:false,existing_registry_draft_or_members_modified:false,
  environment:{postgresql:'17.9',keycloak:'26.2.4',minio:'RELEASE.2025-09-07T16-13-09Z',path:'designated_project_services_via_SSH'},full_D04_or_runtime_publication_complete:false};
const privatePath=name=>join(root,'deploy/private',name),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const progress=value=>console.log(JSON.stringify(value));
async function request(role,method,path,route,status,name,body){
  stage=name;const headers={'Idempotency-Key':randomUUID()};if(role){headers.Cookie=sessions[role].cookie;headers['X-CSRF-Token']=sessions[role].csrf_token;}if(body!==undefined)headers['Content-Type']='application/json';
  const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers,redirect:'manual',signal:AbortSignal.timeout(25000),body:body===undefined?undefined:JSON.stringify(body)});lastHttpStatus=response.status;assert.equal(response.status,status);
  const payload=await response.json(),schema=contract.paths[route][method.toLowerCase()]?.responses[String(status)]?.content?.['application/json']?.schema;assert.ok(schema);const schemaKey=JSON.stringify(schema);
  if(!validators.has(schemaKey))validators.set(schemaKey,ajv.compile(schema.$ref?{$ref:'urn:mop:configuration-diagnostic-http'+schema.$ref}:schema));assert.ok(validators.get(schemaKey)(payload));
  checks.push({stage:name,http_status:status,contract_valid:true});return payload.data??payload;
}
async function capture(role){
  stage='real_oidc_capture_'+role;await exec(process.execPath,[join(root,'tools/capture-test-session.mjs'),role],{cwd:root,timeout:60000,maxBuffer:8192});
  sessions[role]=JSON.parse(readFileSync(privatePath('qa-session.json'),'utf8').replace(/^\uFEFF/,''));
  const session=await request(role,'GET','/session','/session',200,'real_oidc_session_'+role);assert.equal(session.mode,'remote_test');assert.equal(session.identity_source,'test_oidc');
  assert.equal(session.user.role,role.startsWith('viewer')?'viewer':role==='reviewer'?'padmin':role);assert.equal(session.tenant.id,role==='viewerB'?B:A);
  writeFileSync(privatePath(`qa-diagnostic-${role}.json`),JSON.stringify(sessions[role])+'\n');return session;
}
async function fixture(action){
  stage='scoped_registry_fixture_'+action;const python=join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');assert.ok(existsSync(python));
  const {stdout}=await exec(python,[join(root,'tools/configuration-diagnostic-http-fixture.py'),action],{cwd:root,timeout:45000,maxBuffer:8192,env:{...process.env,PYTHONPATH:join(root,'.local/python-deps')}});
  const result=JSON.parse(stdout);assert.equal(result.status,'passed');return result;
}
async function switchTenant(tenant,name){
  sessionChanged=true;const result=await request('padmin','POST','/session/tenant','/session/tenant',200,name,{tenant_id:tenant});assert.equal(result.tenant.id,tenant);sessions.padmin.csrf_token=result.csrf_token;return result;
}
function verifyDiagnostic(data,tenant){
  assert.equal(data.key,key);assert.equal(data.context.tenant_id,tenant);assert.equal(data.context.environment,'remote_test');assert.equal(data.status,'CONFIG_PENDING');assert.equal(data.effective_value,null);
  assert.equal(data.registry.origin,'DESIGN_REGISTRY');assert.deepEqual(data.question_ids,['Q28']);assert.equal(data.registry.version,1);
  for(const slot of ['runtime_definition','publication','customer_verification'])assert.equal(data[slot].availability,'NOT_IMPLEMENTED');
  assert.equal(data.publication.record_ref,null);assert.equal(data.publication.production_approval_verified,null);assert.equal(data.runtime_definition.precedence,null);assert.equal(data.customer_verification.authenticity_verified,null);
  assert.ok(data.reasons.some(reason=>reason.code==='DESIGN_REGISTRY_NOT_RUNTIME_CONFIG'));assert.ok(data.recent_proposals.items.length<=5);
  assert.ok(!JSON.stringify(data).includes(fakeMarker));
  for(const forbidden of ['registered_value','draft_value','proposed_value','review_comment','evidence_refs','selected_revision'])assert.ok(!JSON.stringify(data).includes('"'+forbidden+'"'));
}
async function diagnostic(name){return request('padmin','GET','/settings/effective?key='+encodeURIComponent(key),'/settings/effective',200,name);}
function removeOwnedMarker(){
  if(!markerOwned){assert.ok(!existsSync(marker));return;}
  const flag=JSON.parse(readFileSync(marker,'utf8'));assert.equal(flag.run_id,run);assert.equal(flag.version,1);assert.equal(flag.component,'postgresql');assert.equal(flag.scope,'owned-loopback-only');assert.equal(resolve(flag.project_root),root);unlinkSync(marker);markerOwned=false;
}
async function waitReady(){
  for(let attempt=0;attempt<30;attempt++){
    try{const response=await fetch('http://127.0.0.1:8080/api/v1/health/ready',{redirect:'manual',signal:AbortSignal.timeout(4000)});if(response.status===200&&(await response.json()).data.photos.ready===true)return;}catch{/* Bounded reconnect; no fallback. */}await delay(400);
  }throw new Error('Designated dependencies did not recover');
}
try{
  assert.ok(!existsSync(marker));assert.ok(!existsSync(join(root,'.local/minio-forward-disabled')));
  for(const role of ['padmin','reviewer','viewerA','msp','tadmin','viewerB'])await capture(role);
  await request(null,'GET','/settings/effective?key=business.timezone','/settings/effective',401,'anonymous_diagnostic_denied');
  for(const role of ['viewerA','msp','tadmin','viewerB'])await request(role,'GET','/settings/effective?key=business.timezone','/settings/effective',403,role+'_cannot_upgrade_configuration_read');
  for(const suffix of ['', '?key=', '?key=../invalid', '?key=business.timezone&key=business.timezone', '?key=business.timezone&tenant_id='+B, '?key=business.timezone&scope=object', '?key=business.timezone&object_id='+B, '?key=business.timezone&unknown=1'])
    await request('padmin','GET','/settings/effective'+suffix,'/settings/effective',400,'invalid_single_key_query_rejected');
  await request('padmin','GET','/settings/effective?key=mop.fake.unknown.'+run.replaceAll('-',''),'/settings/effective',404,'unknown_registry_not_fabricated');
  const known=await request('padmin','GET','/settings/effective?key=business.timezone','/settings/effective',200,'real_existing_design_registry_stays_pending');assert.equal(known.status,'CONFIG_PENDING');assert.equal(known.effective_value,null);assert.equal(known.registry.origin,'DESIGN_REGISTRY');
  writeFileSync(join(root,'.local/configuration-diagnostic-http-request.json'),JSON.stringify({run_id:run,key,fake_marker:fakeMarker})+'\n');await fixture('seed');
  let data=await diagnostic('fresh_fictitious_registry_metadata');verifyDiagnostic(data,A);assert.equal(data.recent_proposals.items.length,0);
  const ownIds=[],versions=new Map();let approved;
  for(let index=0;index<7;index++){
    const created=await request('padmin','POST',`/configurations/${key}/revisions`,'/configurations/{key}/revisions',200,'create_fictitious_internal_proposal',{proposed_value:{fake_text:fakeMarker+'-proposal-'+index},reason:fakeMarker+'-reason-'+index,evidence_refs:[{reference:'document-ref:mop-fake-diagnostic-http',title:fakeMarker+'-evidence-'+index,sha256:'a'.repeat(64)}]});ownIds.push(created.id);versions.set(created.id,created.version);
    if(index===6){
      const submitted=await request('padmin','POST',`/configuration-revisions/${created.id}/submit`,'/configuration-revisions/{id}/submit',200,'submit_fictitious_internal_proposal',{expected_version:created.version});
      approved=await request('reviewer','POST',`/configuration-revisions/${created.id}/review`,'/configuration-revisions/{id}/review',200,'approve_internal_proposal_without_publication',{expected_version:submitted.version,decision:'approve',comment:fakeMarker+'-internal-review'});assert.equal(approved.workflow_status,'APPROVED');assert.equal(approved.effective_value,null);versions.set(approved.id,approved.version);
    }
  }
  data=await diagnostic('approved_internal_metadata_is_not_effective');verifyDiagnostic(data,A);assert.equal(data.recent_proposals.items.length,5);assert.equal(data.recent_proposals.truncated,true);assert.ok(data.recent_proposals.items.some(item=>item.id===approved.id&&item.workflow_status==='APPROVED'&&item.confirmation_status==='UNVERIFIED'&&item.evidence_input_status==='REFERENCE_ONLY'));
  assert.deepEqual(data.recent_proposals.items.map(item=>item.id),ownIds.slice(-5).reverse());assert.equal(new Set(data.recent_proposals.items.map(item=>item.id)).size,5);
  for(const proposal of data.recent_proposals.items){assert.equal(proposal.version,versions.get(proposal.id));assert.equal(proposal.evidence_reference_count,1);}
  await switchTenant(B,'switch_only_authorized_current_tenant');data=await diagnostic('tenant_b_cannot_see_tenant_a_proposals');verifyDiagnostic(data,B);assert.equal(data.recent_proposals.items.length,0);
  const foreign=await request('padmin','POST',`/configurations/${key}/revisions`,'/configurations/{key}/revisions',200,'create_fictitious_other_tenant_proposal',{proposed_value:{fake_text:fakeMarker+'-tenant-b'},reason:fakeMarker+'-tenant-b-reason',evidence_refs:[{reference:'document-ref:mop-fake-diagnostic-http-b',title:fakeMarker+'-tenant-b-evidence',sha256:'b'.repeat(64)}]});
  data=await diagnostic('tenant_b_sees_only_its_metadata');verifyDiagnostic(data,B);assert.deepEqual(data.recent_proposals.items.map(item=>item.id),[foreign.id]);assert.ok(ownIds.every(id=>!JSON.stringify(data).includes(id)));
  await switchTenant(A,'restore_padmin_current_tenant_a');data=await diagnostic('tenant_a_does_not_leak_tenant_b_metadata');verifyDiagnostic(data,A);assert.ok(!JSON.stringify(data).includes(foreign.id));
  await fixture('verify');
  writeFileSync(marker,JSON.stringify({version:1,run_id:run,component:'postgresql',scope:'owned-loopback-only',project_root:root})+'\n',{flag:'wx'});markerOwned=true;await delay(1800);
  const down=await request('padmin','GET','/settings/effective?key='+encodeURIComponent(key),'/settings/effective',503,'real_pg_forward_failure_has_no_cached_or_fixture_diagnostic');assert.equal(down.error.code,'UPSTREAM_UNAVAILABLE');assert.ok(!down.data);
  await request(null,'GET','/health/ready','/health/ready',503,'pg_forward_failure_not_ready');await request(null,'GET','/health/live','/health/live',200,'pg_forward_failure_keeps_owned_api_alive');
  const runtime=JSON.parse(readFileSync(privatePath('runtime.json'),'utf8').replace(/^\uFEFF/,''));const identity=await fetch(runtime.environment.MOP_OIDC_ISSUER+'/.well-known/openid-configuration',{redirect:'manual',signal:AbortSignal.timeout(5000)});assert.equal(identity.status,200);assert.equal((await identity.json()).issuer,runtime.environment.MOP_OIDC_ISSUER);
  checks.push({stage:'only_postgres_forward_disabled_identity_source_kept',http_status:200,other_component_path_preserved:true});
  removeOwnedMarker();await waitReady();data=await diagnostic('same_context_and_registry_recover_without_false_activation');verifyDiagnostic(data,A);await fixture('verify');
  evidence.result={status:'PASS',real_oidc_current_role_and_context:true,only_padmin_configuration_read:true,strict_single_key_validation:true,unknown_registry_404:true,existing_design_registry_not_runtime_configuration:true,
    approved_internal_proposal_not_published_or_effective:true,current_tenant_metadata_isolated:true,recent_metadata_limit:5,truncated:true,raw_registry_draft_proposal_review_evidence_bodies_absent:true,
    source_unimplemented_fields_remain_null:true,actual_pg_forward_failure_http_status:503,no_cached_fixture_or_fake_success:true,identity_forward_remains_available:true,recovered_same_context_registry_and_pending_state:true,existing_185_registry_rows_never_updated:true};passed=true;
}catch{
  evidence.failed_stage=stage;evidence.last_http_status=lastHttpStatus;progress({stage,status:'failed',http_status:lastHttpStatus,details:'redacted; no credentials, bodies, SQL or protocol URLs printed'});
}finally{
  const restored={owned_pg_marker_removed:false,padmin_tenant_restored:!sessionChanged,dependencies_ready:false,sessions_recaptured:false};
  try{removeOwnedMarker();restored.owned_pg_marker_removed=true;}catch{passed=false;}
  try{await waitReady();restored.dependencies_ready=true;}catch{passed=false;}
  if(sessionChanged)try{await switchTenant(A,'finally_restore_padmin_tenant');restored.padmin_tenant_restored=true;}catch{passed=false;}
  try{for(const role of ['padmin','reviewer','viewerA','msp','tadmin','viewerB'])await capture(role);restored.sessions_recaptured=true;}catch{passed=false;}
  evidence.passed=passed&&Object.values(restored).every(Boolean);evidence.restoration=restored;evidence.http_exchanges=checks.length;evidence.verified_at=new Date().toISOString();
  if(!evidence.result)evidence.result={status:'NOT_RUN',reason:'A prior guarded stage failed; no diagnostic or failure recovery success inferred'};
  try{writeFileSync(join(root,'docs/evidence/local-configuration-diagnostic-http.json'),JSON.stringify(evidence,null,2)+'\n');}catch{evidence.passed=false;}
  progress({stage:'complete',status:evidence.passed?'passed':'failed',http_exchanges:checks.length,restoration:restored,credential_output:'none'});if(!evidence.passed)process.exitCode=1;
}
