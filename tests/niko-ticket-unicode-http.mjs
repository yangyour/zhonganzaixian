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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:ticket-unicode-http',components:contract.components});
  const validators=new Map(),checks=[],traces=new Map();
  const sessions=Object.fromEntries(['msp','tadmin','viewerA','viewerB','padmin','reviewer'].map(role=>{
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:ticket-unicode-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  const prefix='TICKET-READ-'+randomUUID()+'-ÄBC ΟΣ İ K É_%',own=[];
  async function report(role,suffix){
    const title=prefix+'-'+suffix,description='描述'+prefix+'-'+suffix;
    const accepted=(await request(role,'POST','/tickets','/tickets',202,'prepare_'+role+'_'+suffix,{title,source:'customer_report',description,category:'网络不通',impact_scope:'individual',contact_name:'虚构联系人',contact_phone:'12345',urgency:'normal'})).value;
    const operation=(await request(role,'GET','/operations/'+accepted.operation_id,'/operations/{id}',200,'receipt_'+role+'_'+suffix)).value;
    return {id:operation.result_ref.split('/').at(-1),title,description};
  }
  for(let index=0;index<12;index++)own.push(await report('viewerA',String(index).padStart(2,'0')));
  const colleague=await report('tadmin','same-tenant-other-reporter'),foreign=await report('viewerB','other-tenant');
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_before_reads')).value.snapshot_sequence;
  const query=new URLSearchParams({q:prefix.toLowerCase(),page_size:'10'});
  const first=(await request('viewerA','GET','/tickets?'+query,'/tickets',200,'unicode_lowercase_first_ten')).value;
  assert.equal(first.items.length,10);assert.equal(first.has_more,true);assert.deepEqual(first.items.map(t=>t.id),own.slice(0,10).map(t=>t.id));
  const next=new URLSearchParams(query);next.set('cursor',first.next_cursor);
  const last=(await request('viewerA','GET','/tickets?'+next,'/tickets',200,'unicode_lowercase_next_two')).value;
  assert.deepEqual(last.items.map(t=>t.id),own.slice(10).map(t=>t.id));assert.equal(last.has_more,false);
  for(const item of [...first.items,...last.items]){const original=own.find(o=>o.id===item.id);assert.equal(item.title,original.title);assert.equal(item.description,original.description);assert.equal(item.reporter_id,current.viewerA.user.id);assert.equal(item.tenant_id,current.viewerA.tenant.id);for(const key of ['state','priority','external_id','response_due_at','recovery_due_at'])assert.equal(item[key],null);assert.equal(Object.hasOwn(item,'internal_notes'),false);}
  for(const role of ['msp','padmin','reviewer']){const page=(await request(role,'GET','/tickets?'+new URLSearchParams({q:prefix.toLowerCase()}),'/tickets',200,role+'_internal_same_tenant_unicode')).value;assert.equal(page.items.length,13);assert.ok(page.items.every(t=>t.tenant_id===current[role].tenant.id));}
  for(const [role,expected] of [['tadmin',colleague],['viewerB',foreign]])assert.deepEqual((await request(role,'GET','/tickets?'+new URLSearchParams({q:prefix.toLowerCase()}),'/tickets',200,role+'_own_report_only')).value.items.map(t=>t.id),[expected.id]);
  for(const q of [prefix.replace('É','E\u0301'),prefix+"' OR '1'='1",prefix+'NOT-LITERAL', '😀'.repeat(100)])assert.equal((await request('viewerA','GET','/tickets?'+new URLSearchParams({q}),'/tickets',200,'literal_or_non_normalized_'+checks.length)).value.items.length,0);
  const description=(await request('viewerA','GET','/tickets?'+new URLSearchParams({q:own[0].description.toLowerCase()}),'/tickets',200,'unicode_description_search')).value;assert.deepEqual(description.items.map(t=>t.id),[own[0].id]);
  assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_after_reads')).value.snapshot_sequence,tail);
  await request('tadmin','GET','/tickets/'+own[0].id,'/tickets/{id}',404,'same_tenant_other_customer_hidden');await request('msp','GET','/tickets/'+foreign.id,'/tickets/{id}',404,'foreign_internal_detail_hidden');
  await request('tadmin','GET','/tickets?'+next,'/tickets',400,'cursor_wrong_actor');await request('viewerB','GET','/tickets?'+next,'/tickets',400,'cursor_wrong_tenant');await request('viewerA','GET','/tickets?'+new URLSearchParams({q:'other',cursor:first.next_cursor}),'/tickets',400,'cursor_wrong_filter');
  for(const q of ['?q='+encodeURIComponent('😀'.repeat(101)),'?q='+encodeURIComponent('x\u0000'),'?q='+encodeURIComponent('x\n'),'?q=a&q=b','?tenant_id='+current.viewerB.tenant.id,'?category=unknown','?urgency=P1','?page_size=500'])await request('viewerA','GET','/tickets'+q,'/tickets',400,'invalid_query_'+checks.length);
  await request(null,'GET','/tickets','/tickets',401,'anonymous_query');
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,synthetic_report_setup:14,unicode_page_sizes:[10,2],all_responses_contract_valid:true,customer_visibility:'own reporter only',internal_visibility:'current authorized tenant only',literal_percent_underscore_and_quote:true,unicode_normalization:false,formal_state_priority_sla_still_null:true,successful_read_queries_do_not_append_audit:true,denial_audit_checks_outside_readonly_window:true,credentials_output:false,browser_dom_tested:false,limitations:['Malformed server-side session values and inactive membership branches covered only by isolated controller tests','No production monitoring/ITSM activation','No browser DOM acceptance','No database global locale change']};
  writeFileSync(new URL('docs/evidence/niko-ticket-unicode-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_checks:checks.length,synthetic_reports:14}));
}catch{console.error(JSON.stringify({passed:false,stage,details:'redacted'}));process.exitCode=1;}
