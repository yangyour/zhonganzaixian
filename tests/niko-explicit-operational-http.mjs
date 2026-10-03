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
  const evidenceFile=new URL(environment==='designated-server'?'docs/evidence/local-explicit-operational-api-http-20261003.json':'docs/evidence/niko-explicit-operational-api-http-20261003.json',root);
  const catalogFile=new URL(environment==='designated-server'?'.local/designated-test/operational-api-catalog-snapshot.json':'.local/niko-test/operational-api-catalog-snapshot.json',root);
  assert.equal(existsSync(evidenceFile)||existsSync(catalogFile),false,'Existing evidence must be preserved');
  const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));assert.equal(contract.info.version,'0.29.0');
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:operational-http',components:contract.components});
  const validators=new Map(),checks=[],traces=new Map();
  const sessions=Object.fromEntries(['msp','tadmin','viewerA','viewerB','padmin','reviewer'].map(role=>{
    const session=JSON.parse(readFileSync(new URL(`${privateDirectory}sessions/qa-${role}.json`,root),'utf8'));
    assert.equal(session.environment,environment);return [role,session];
  }));
  async function request(role,method,path,route,expected,name,body,options={}){
    stage=name;const headers={'Content-Type':'application/json'};
    if(role){headers.Cookie=sessions[role].cookie;if(!options.noCsrf)headers['X-CSRF-Token']=sessions[role].csrf_token;}
    if(options.key||path==='/session/tenant')headers['Idempotency-Key']=options.key??randomUUID();
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers,body:options.raw??(body===undefined?undefined:JSON.stringify(body)),redirect:'manual',signal:AbortSignal.timeout(20000)});
    stage=`${name}: actual=${response.status}`;assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status));
    const payload=await response.json();const schema=contract.paths[route]?.[method.toLowerCase()]?.responses[response.status]?.content?.['application/json']?.schema;assert.ok(schema);
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:operational-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/operations/explicit-comparisons',current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  for(const role of Object.keys(sessions))assert.equal(current[role].capabilities.includes('operations:compare_explicit'),['padmin','reviewer'].includes(role));
  const body={elapsed_millis:['9','2','1','3'],staffing:{active_tenants:'30',on_call_fte:'1.5',second_line_fte:'1'}};
  const post=async(name,b=body,expected=200,options={})=>(await request('padmin','POST',route,route,expected,name,b,options)).value;
  const first=await post('explicit_hand_calculation');assert.equal(first.tenant_id,current.padmin.tenant.id);assert.deepEqual(first.input,body);assert.equal(first.sample_median.median_millis,'2.5');assert.equal(first.staffing.explicit_total_fte,'2.5');assert.equal(first.staffing.tenants_per_fte,'12');
  assert.deepEqual((await request('reviewer','POST',route,route,200,'second_platform_admin',body)).value,first);
  await request(null,'POST',route,route,[401,403],'anonymous_denied',body);await request('padmin','POST',route,route,403,'csrf_required',body,{noCsrf:true});
  for(const role of ['msp','tadmin','viewerA','viewerB'])await request(role,'POST',route,route,403,'role_denied_'+role,body);
  const unknown=await post('null_groups',{elapsed_millis:null,staffing:null});assert.equal(unknown.sample_median.reason,'INPUT_UNAVAILABLE');assert.equal(unknown.sample_median.sample_count,null);assert.equal(unknown.staffing.tenants_per_fte,null);
  const empty=await post('empty_samples',{...body,elapsed_millis:[]});assert.equal(empty.sample_median.reason,'EMPTY_SAMPLE');assert.equal(empty.sample_median.median_millis,null);
  const mixed=await post('unknown_not_filtered',{...body,elapsed_millis:['0',null,'3']});assert.equal(mixed.sample_median.unknown_count,1);assert.equal(mixed.sample_median.median_millis,null);
  assert.equal((await post('explicit_zero',{...body,elapsed_millis:['0','0'],staffing:{active_tenants:'0',on_call_fte:'1',second_line_fte:'0'}})).staffing.tenants_per_fte,'0');
  const zero=await post('zero_denominator',{...body,staffing:{active_tenants:'30',on_call_fte:'0',second_line_fte:'0'}});assert.equal(zero.staffing.reason,'ZERO_FTE');assert.equal(zero.staffing.tenants_per_fte,null);
  assert.deepEqual((await post('missing_fte',{...body,staffing:{...body.staffing,second_line_fte:null}})).staffing.missing_inputs,['second_line_fte']);
  const big=await post('unsafe_integer_exact',{elapsed_millis:['9007199254740993'],staffing:{active_tenants:'9007199254740993',on_call_fte:'1',second_line_fte:'0'}});assert.equal(big.sample_median.median_millis,'9007199254740993');assert.equal(big.staffing.tenants_per_fte,'9007199254740993');
  assert.equal((await post('one_final_decimal128',{...body,staffing:{active_tenants:'1',on_call_fte:'2',second_line_fte:'1'}})).staffing.tenants_per_fte,'0.3333333333333333333333333333333333');
  assert.equal((await post('max_10000_40_digit_samples',{elapsed_millis:Array(10000).fill('9'.repeat(40)),staffing:null})).sample_median.median_millis,'9'.repeat(40));
  await post('reject_10001',{elapsed_millis:Array(10001).fill('0'),staffing:null},400);
  for(const value of [1,'01','-1','1e2','1.5','1'.repeat(41),{},true])await post('bad_elapsed_'+checks.length,{...body,elapsed_millis:[value]},400);
  for(const value of [1,'01','-1','1e2','0.'+'1'.repeat(41),'1'.repeat(41),{},true])await post('bad_fte_'+checks.length,{...body,staffing:{...body.staffing,on_call_fte:value}},400);
  for(const b of [{},{elapsed_millis:null},{...body,tenant_id:current.viewerB.tenant.id},{...body,staffing:{active_tenants:'1',on_call_fte:'1'}}])await post('missing_or_forged_'+checks.length,b,400);
  for(const raw of ['{"elapsed_millis":null,"elapsed_millis":null,"staffing":null}','{} {}','[[[[1]]]]',' '.repeat(524289)])await post('bounded_json_'+checks.length,undefined,400,{raw});
  await request('padmin','POST',route+'?tenant_id='+current.viewerB.tenant.id,route,400,'query_scope_override',body);
  await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_authorized_tenant',{tenant_id:current.viewerB.tenant.id});try{const second=await post('actual_other_tenant_scope');assert.equal(second.tenant_id,current.viewerB.tenant.id);assert.deepEqual(second.staffing,first.staffing);}finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_original_tenant',{tenant_id:current.padmin.tenant.id});}
  const catalog=(await request('padmin','GET','/authorization/capability-catalog','/authorization/capability-catalog',200,'actual_updated_catalog')).value;assert.equal(catalog.entry_count,24);assert.deepEqual(catalog.entries.find(e=>e.id==='operations:compare_explicit').declared_by_role,{viewer:false,tadmin:false,msp:false,padmin:true});
  const canonical=['mop-capability-declarations-v1','viewer|tadmin|msp|padmin',...catalog.entries.map(e=>e.id+'|'+e.dimension_ids.join(',')+'|'+catalog.roles.map(r=>e.declared_by_role[r.id]?'1':'0').join(''))].join('\n')+'\n';assert.equal(catalog.declaration_hash,createHash('sha256').update(canonical).digest('hex'));
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_tail_before')).value.snapshot_sequence;for(let i=0;i<3;i++)await post('readonly_repeat_'+i);assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_tail_after')).value.snapshot_sequence,tail);
  for(const role of Object.keys(sessions)){const after=(await request(role,'GET','/session','/session',200,'unchanged_role_'+role)).value;assert.deepEqual(after.capabilities,current[role].capabilities);assert.equal(after.permission_version,current[role].permission_version);}
  writeFileSync(catalogFile,JSON.stringify(catalog,null,2)+'\n',{flag:'wx'});
  const evidence={verified_at:new Date().toISOString(),environment,environment_instance:environment==='niko-local-test'?'rebuilt-20261003-after-executor-loss':'designated-server',api_version:'0.29.0',http_requests:checks.length,checks,synthetic_explicit_inputs:true,formal_metrics_available:false,project_metrics_data_queried:false,business_writes:0,readonly_success_audit_tail_unchanged:true,catalog_entries:24,catalog_declaration_hash:catalog.declaration_hash,existing_member_bindings_changed:false,authorized_tenant_switches:2,browser_dom_tested:false,storage_integration_tested:false,credentials_output:false,q23_q27_q30:'UNVERIFIED',contract_done:false};writeFileSync(evidenceFile,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:checks.length,business_writes:0,catalog_entries:24}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
