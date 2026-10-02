// Real niko PG/OIDC API checks. No storage adapter, fixture route, browser or migration is invoked.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const root=new URL('../',import.meta.url);
let stage='initialization';
try {
  const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:dependency-http',components:contract.components});
  const validators=new Map(),checks=[],traces=new Map();
  const sessions=Object.fromEntries(['msp','tadmin','viewerA','viewerB','padmin'].map(role=>{
    const session=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`,root),'utf8'));
    assert.equal(session.environment,'niko-local-test');return [role,session];
  }));
  async function request(role,method,path,route,expected,name,body,options={}){
    stage=name;const headers={'Content-Type':'application/json'};
    if(role){headers.Cookie=sessions[role].cookie;if(!options.noCsrf)headers['X-CSRF-Token']=sessions[role].csrf_token;}
    if(!options.noKey)headers['Idempotency-Key']=options.key??randomUUID();
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers,body:options.raw??(body===undefined?undefined:JSON.stringify(body)),redirect:'manual',signal:AbortSignal.timeout(20000)});
    stage=`${name}: actual=${response.status}`;assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status));
    const payload=await response.json();const schema=contract.paths[route]?.[method.toLowerCase()]?.responses[response.status]?.content?.['application/json']?.schema;assert.ok(schema);
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:dependency-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/assets/{id}/dependencies';
  const edge=(parent,child)=>({parent_id:parent,child_id:child,proposed_type:null,proposed_suppression_scope:null,proposed_enabled:null});
  const append=(version,...edges)=>({expected_version:version,additions:edges});
  const path=id=>`/assets/${id}/dependencies`;
  const draft=value=>{assert.equal(value.source,'postgresql');assert.equal(value.complete,true);assert.equal(value.lifecycle_status,'DRAFT');assert.equal(value.configuration_status,'CONFIG_PENDING');assert.equal(value.effective_rules,null);assert.equal(value.monitoring_applied,false);assert.deepEqual(value.question_ids,['Q20']);assert.equal(value.version_kind,'tenant_dependency_graph');};
  for(const role of Object.keys(sessions)){const {value}=await request(role,'GET','/session','/session',200,'session_'+role);assert.equal(value.mode,'remote_test');assert.equal(value.identity_source,'test_oidc');}
  async function asset(label){const run=randomUUID();return (await request('msp','POST','/assets','/assets',200,'create_synthetic_'+label,{name:`DEPENDENCY-HTTP-${run}`,type:'switch',management_ip:`2001:db8:${run.slice(0,4)}:${run.slice(4,8)}::1`,site_id:'site-a-main'})).value.id;}
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'dependency_schema_readiness')).value;assert.equal(ready.asset_dependencies.schema_access_ready,true);assert.equal(ready.asset_dependencies.monitoring_applied,false);assert.equal(ready.asset_dependencies.effective_rules,null);
  const a=await asset('a'),b=await asset('b'),c=await asset('c');
  const foreign=(await request('viewerB','GET','/assets?page_size=10','/assets',200,'foreign_asset_lookup')).value.items[0].id;
  const before=(await request('msp','GET',path(a),route,200,'read_complete_graph')).value;draft(before);
  const viewed=(await request('viewerA','GET',path(a),route,200,'customer_read_draft_without_write')).value;assert.deepEqual(viewed,before);
  await request('padmin','GET','/audit','/audit',200,'audit_before');
  const key=randomUUID(),body=append(before.version,edge(a,b));
  const saved=(await request('msp','POST',path(a),route,200,'append_a_to_b',body,{key})).value;draft(saved);assert.equal(saved.version,before.version+1);assert.equal(saved.proposed_edges.length,before.proposed_edges.length+1);
  assert.deepEqual((await request('msp','POST',path(a),route,200,'same_key_exact_replay',body,{key})).value,saved);
  await request('msp','POST',path(a),route,409,'same_key_changed_body_rejected',append(before.version,edge(b,a)),{key});
  assert.deepEqual((await request('msp','GET',path(a),route,200,'read_saved_draft')).value,saved);
  const second=(await request('msp','POST',path(b),route,200,'append_b_to_c',append(saved.version,edge(b,c)))).value;draft(second);
  await request('msp','POST',path(c),route,400,'transitive_cycle_rejected',append(second.version,edge(c,a)));
  await request('msp','POST',path(a),route,400,'self_reference_rejected',append(second.version,edge(a,a)));
  await request('msp','POST',path(a),route,409,'stale_graph_version_rejected',append(before.version,edge(a,c)));
  await request('msp','POST',path(a),route,409,'duplicate_pair_not_overwritten',append(second.version,{...edge(a,b),proposed_type:'different-proposal'}));
  await request('viewerB','GET',path(a),route,404,'cross_tenant_read_hidden');
  await request('tadmin','GET',path(foreign),route,404,'foreign_path_asset_hidden');
  await request('tadmin','POST',path(foreign),route,404,'foreign_path_write_hidden',append(second.version,edge(foreign,a)));
  await request('msp','POST',path(a),route,404,'foreign_edge_hidden_before_stale_version',append(0,edge(a,foreign)));
  await request('viewerA','POST',path(a),route,403,'viewer_write_forbidden',append(second.version,edge(a,c)));
  await request(null,'GET',path(a),route,401,'anonymous_read_forbidden');
  await request('msp','POST',path(a),route,403,'missing_csrf_rejected',append(second.version,edge(a,c)),{noCsrf:true});
  await request('msp','POST',path(a),route,400,'missing_key_rejected',append(second.version,edge(a,c)),{noKey:true});
  await request('msp','POST',path(a),route,400,'client_tenant_spoof_rejected',{...append(second.version,edge(a,c)),tenant_id:foreign});
  await request('msp','GET',path(a)+'?tenant_id=spoof',route,400,'query_spoof_rejected');
  await request('msp','POST',path(a),route,400,'unanchored_batch_rejected',append(second.version,edge(b,c)));
  const missing=edge(a,c);delete missing.proposed_enabled;
  await request('msp','POST',path(a),route,400,'explicit_null_required',append(second.version,missing));
  await request('msp','POST',path(a),route,400,'blank_metadata_rejected',append(second.version,{...edge(a,c),proposed_type:'\u00a0\ufeff'}));
  await request('msp','POST',path(a),route,400,'batch_limit_rejected',{expected_version:second.version,additions:Array.from({length:101},()=>edge(a,c))});
  await request('msp','POST',path(a),route,400,'duplicate_json_key_rejected',undefined,{raw:'{"expected_version":0,"expected_version":1,"additions":[]}'});
  await request('msp','POST',path(a),route,400,'trailing_json_rejected',undefined,{raw:JSON.stringify(append(second.version,edge(a,c)))+' {}'});
  await request('msp','POST',path(a),route,400,'body_size_limit_rejected',undefined,{raw:' '.repeat(131073)});
  const after=(await request('msp','GET',path(a),route,200,'all_rejections_preserve_complete_graph')).value;assert.deepEqual(after,{...second,asset_id:a});
  const auditAfter=(await request('padmin','GET','/audit','/audit',200,'audit_after')).value;
  const auditItems=value=>Array.isArray(value)?value:value.items;
  for (const name of ['append_a_to_b','append_b_to_c']) assert.equal(auditItems(auditAfter).filter(x=>x.action==='asset_dependency.draft.append'&&x.trace_id===traces.get(name)).length,1);
  assert.equal(auditItems(auditAfter).filter(x=>x.action==='asset_dependency.draft.append'&&x.trace_id===traces.get('same_key_exact_replay')).length,0);
  const d=await asset('race_d'),e=await asset('race_e');
  const raceInputs=[{id:d,edge:edge(d,e)},{id:e,edge:edge(e,d)}];
  const results=await Promise.all(raceInputs.map((value,index)=>request('msp','POST',path(value.id),route,[200,409],'concurrent_opposite_'+index,append(second.version,value.edge))));
  assert.deepEqual(results.map(x=>x.status).sort(),[200,409]);
  const winner=results.find(x=>x.status===200).value;assert.equal(winner.version,second.version+1);draft(winner);
  const loser=raceInputs[results.findIndex(x=>x.status===409)];
  await request('msp','POST',path(loser.id),route,400,'concurrent_loser_retry_cycle_rejected',append(winner.version,loser.edge));
  const final=(await request('msp','GET',path(a),route,200,'final_readback')).value;assert.equal(final.version,winner.version);draft(final);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,http_checks:checks.length,all_passed:true,checks,postgres:'17.11',keycloak:'26.2.4',concurrent_result:[200,409],monitoring_applied:false,effective_rules:null,credentials_output:false,remaining:['Browser UI','Monitoring rule publication and confirmed Q20 impact','Full M3-9 acceptance','OSS verification']};
  writeFileSync(new URL('docs/evidence/niko-asset-dependency-api-final-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({status:'passed',http_checks:checks.length,contract_valid:true,environment:'niko-local-test',credentials_output:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
