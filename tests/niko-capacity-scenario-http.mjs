// Real PG/OIDC API checks with separate named environments and immutable evidence files.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const root=new URL('../',import.meta.url);
let stage='initialization';
try {
  const environment=process.env.MOP_HTTP_ENVIRONMENT??'niko-local-test';
  assert.ok(['niko-local-test','designated-server'].includes(environment));
  const privateDirectory=environment==='designated-server'?'.local/designated-test/private/':'.local/niko-test/private/';
  const evidenceFile=new URL(environment==='designated-server'?'docs/evidence/local-capacity-scenario-api-http-20261003.json':'docs/evidence/niko-capacity-scenario-api-http-20261003.json',root);
  const catalogFile=new URL(environment==='designated-server'?'.local/designated-test/capacity-scenario-api-catalog-snapshot.json':'.local/niko-test/capacity-scenario-api-catalog-snapshot.json',root);
  assert.equal(existsSync(evidenceFile)||existsSync(catalogFile),false,'Existing evidence must be preserved');
  const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));assert.equal(contract.info.version,'0.28.0');
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:capacity-scenario-http',components:contract.components});
  const validators=new Map(),checks=[],traces=new Map();
  const sessions=Object.fromEntries(['msp','tadmin','viewerA','viewerB','padmin','reviewer'].map(role=>{
    const session=JSON.parse(readFileSync(new URL(`${privateDirectory}sessions/qa-${role}.json`,root),'utf8'));
    assert.equal(session.environment,environment);return [role,session];
  }));
  async function request(role,method,path,route,expected,name,body,options={}){
    stage=name;const headers={'Content-Type':'application/json'};
    if(role){headers.Cookie=sessions[role].cookie;if(!options.noCsrf)headers['X-CSRF-Token']=sessions[role].csrf_token;}
    if(!options.noKey)headers['Idempotency-Key']=options.key??randomUUID();
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers,body:options.raw??(body===undefined?undefined:JSON.stringify(body)),redirect:'manual',signal:AbortSignal.timeout(20000)});
    stage=`${name}: actual=${response.status}`;assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status));
    const payload=await response.json();const schema=contract.paths[route]?.[method.toLowerCase()]?.responses[response.status]?.content?.['application/json']?.schema;assert.ok(schema);
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:capacity-scenario-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/capacity/scenarios',current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  for(const role of Object.keys(sessions))for(const cap of ['capacity_scenarios:read','capacity_scenarios:write'])assert.equal(current[role].capabilities.includes(cap),['padmin','reviewer'].includes(role));
  const label='SYNTHETIC-CAPACITY-'+randomUUID();
  const body={expected_version:0,label,note:'合成容量草稿，不是测量或生产批准',calculation:{inputs:{hosts:'10',items_per_host:'100',interval_seconds:'10',history_days:2,bytes_per_value:'80',trend_ratio:'0.5',trend_days:3,headroom_ratio:'0.25'},model:{version:'SYNTHETIC-ONLY-v1',bytes_per_trend_row:'128',pool_nvps_limit:'60'},proxy_buffer:{site_nvps:'125',outage_seconds:'3600',bytes_per_value:'80',margin_factor:'1.4'}},benchmark_reference:null};
  const key=randomUUID();const saved=(await request('padmin','POST',route,route,200,'create_immutable_proposal',body,{key})).value;assert.equal(saved.tenant_id,current.padmin.tenant.id);assert.equal(saved.version,1);assert.equal(saved.lifecycle_status,'DRAFT');assert.equal(saved.effective,false);assert.equal(saved.snapshot.outputs.nvps,'100');assert.equal(saved.snapshot.proxy_buffer.estimated_bytes,'50400000');assert.deepEqual(saved.calculation,body.calculation);
  assert.deepEqual((await request('padmin','POST',route,route,200,'original_key_replays_original_receipt',body,{key})).value,saved);
  const reordered=structuredClone(body);const hosts=reordered.calculation.inputs.hosts;delete reordered.calculation.inputs.hosts;reordered.calculation.inputs.hosts=hosts;assert.deepEqual((await request('padmin','POST',route,route,200,'nested_order_same_receipt',reordered,{key})).value,saved);
  await request('padmin','POST',route,route,409,'same_key_changed_body_conflicts',{...body,note:'changed'},{key});
  const detailRoute=route+'/{id}',detail=route+'/'+saved.id;assert.deepEqual((await request('padmin','GET',detail,detailRoute,200,'durable_readback')).value,saved);assert.deepEqual((await request('reviewer','GET',detail,detailRoute,200,'same_tenant_other_admin_read')).value,saved);
  for(const role of ['msp','tadmin','viewerA','viewerB']){await request(role,'GET',detail,detailRoute,403,'read_denied_'+role);await request(role,'POST',route,route,403,'write_denied_'+role,body);}
  await request('padmin','POST',route,route,403,'csrf_required',body,{noCsrf:true});await request('padmin','POST',route,route,400,'key_required',body,{noKey:true});await request(null,'POST',route,route,403,'anonymous_denied',body);
  for(const patch of [{expected_version:1},{expected_version:4294967296},{expected_version:'0'},{effective:true},{label:''},{note:' '},{tenant_id:current.viewerB.tenant.id},{snapshot:{status:'ESTIMATED'}}])await request('padmin','POST',route,route,400,'strict_create_'+checks.length,{...body,...patch});
  for(const field of Object.keys(body)){const v=structuredClone(body);delete v[field];await request('padmin','POST',route,route,400,'missing_'+field,v);}
  const numeric=structuredClone(body);numeric.calculation.inputs.hosts=10;await request('padmin','POST',route,route,400,'numeric_input_not_coerced',numeric);
  for(const raw of ['{"label":1,"label":2}','{} {}','[[[[[1]]]]]',' '.repeat(32769)])await request('padmin','POST',route,route,400,'bounded_json_'+checks.length,undefined,{raw});
  await request('padmin','POST',route+'?tenant_id='+current.viewerB.tenant.id,route,400,'create_query_denied',body);
  for(const query of ['?page_size=2','?before_id='+saved.id,'?page_size=10&page_size=15','?label_query='+encodeURIComponent('x'.repeat(101)),'?note_query=x'])await request('padmin','GET',route+query,route,400,'list_query_denied_'+checks.length);
  await request('padmin','GET',route+'/'+randomUUID(),detailRoute,404,'unknown_id_404');await request('padmin','GET',detail+'?effective=true',detailRoute,400,'detail_query_denied');
  await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_tenant',{tenant_id:current.viewerB.tenant.id});try{await request('padmin','GET',detail,detailRoute,404,'cross_tenant_id_404');const empty=(await request('padmin','GET',route+'?label_query='+encodeURIComponent(label),route,200,'cross_tenant_list_empty')).value;assert.equal(empty.items.length,0);}finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_tenant',{tenant_id:current.padmin.tenant.id});}
  const unknownBody={...body,calculation:{inputs:null,model:null,proxy_buffer:null},benchmark_reference:{reference:'document-ref:synthetic-'+randomUUID(),title:'未核验基准引用',sha256:'A'.repeat(64)}};
  const unknown=(await request('padmin','POST',route,route,200,'explicit_unknown_and_unverified_reference',unknownBody)).value;assert.equal(unknown.snapshot.outputs,null);assert.equal(unknown.benchmark_verification,'REFERENCE_ONLY_UNVERIFIED');assert.equal(unknown.benchmark_reference.sha256,'a'.repeat(64));
  const largeBody=structuredClone(body);largeBody.calculation.inputs.hosts='9007199254740993';largeBody.calculation.inputs.items_per_host='1';largeBody.calculation.inputs.interval_seconds='3';const large=(await request('padmin','POST',route,route,200,'large_inputs_and_repeating_output',largeBody)).value;assert.equal(large.snapshot.outputs.total_items,'9007199254740993');assert.equal(large.snapshot.outputs.nvps,'3002399751580331');
  const concurrentKey=randomUUID();const copies=await Promise.all([request('padmin','POST',route,route,200,'concurrent_original_a',body,{key:concurrentKey}),request('padmin','POST',route,route,200,'concurrent_original_b',body,{key:concurrentKey})]);assert.deepEqual(copies[0].value,copies[1].value);
  const ids=new Set([saved.id,unknown.id,large.id,copies[0].value.id]);for(let i=0;i<7;i++){const v=(await request('padmin','POST',route,route,200,'independent_same_label_'+i,body)).value;assert.equal(ids.has(v.id),false);ids.add(v.id);}assert.equal(ids.size,11);
  const page=(await request('padmin','GET',route+'?page_size=10&label_query='+encodeURIComponent(label),route,200,'first_ten_keyset')).value;assert.equal(page.items.length,10);assert.equal(page.has_more,true);const next=(await request('padmin','GET',route+'?page_size=10&label_query='+encodeURIComponent(label)+'&before_recorded_at='+encodeURIComponent(page.next_before_recorded_at)+'&before_id='+page.next_before_id,route,200,'last_one_keyset')).value;assert.equal(next.items.length,1);assert.equal(next.has_more,false);assert.deepEqual(new Set([...page.items,...next.items].map(x=>x.id)),ids);
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_before_reads')).value.snapshot_sequence;await request('padmin','GET',detail,detailRoute,200,'read_only_detail_again');await request('padmin','GET',route,route,200,'read_only_default_list');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_after_reads')).value.snapshot_sequence,tail);
  const catalog=(await request('padmin','GET','/authorization/capability-catalog','/authorization/capability-catalog',200,'new_capability_catalog')).value;assert.equal(catalog.entry_count,23);for(const cap of ['capacity_scenarios:read','capacity_scenarios:write'])assert.deepEqual(catalog.entries.find(e=>e.id===cap).declared_by_role,{viewer:false,tadmin:false,msp:false,padmin:true});
  writeFileSync(catalogFile,JSON.stringify(catalog,null,2)+'\n',{flag:'wx'});
  writeFileSync(evidenceFile,JSON.stringify({verified_at:new Date().toISOString(),environment,passed:true,contract_version:'0.28.0',http_requests:checks.length,checks,synthetic_scenarios_created:11,concurrent_same_key_one_id:true,readonly_audit_tail_unchanged:true,current_capability_count:23,current_capability_hash:catalog.declaration_hash,original_key_and_nested_order_replay:true,cross_tenant_404:true,model_approved:false,measurement_verified:false,poc_executed:false,browser_dom_tested:false,credentials_output:false,all_128_done:false},null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:checks.length,synthetic_scenarios:11,catalog_entries:23}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
