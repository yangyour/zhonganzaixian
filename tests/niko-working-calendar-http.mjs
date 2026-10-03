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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:working-calendar-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:working-calendar-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }

  assert.equal(contract.info.version,'0.18.0');
  const route='/working-calendars',detail=route+'/{id}',history=detail+'/history',preview=detail+'/preview';
  const current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  const unique=randomUUID(),body={name:'CALENDAR-HTTP-'+unique,coverage_start:'2026-10-01T00:00:00Z',coverage_end:'2026-10-03T00:00:00Z',working_windows:[{start:'2026-10-01T01:00:00Z',end:'2026-10-01T03:00:00Z'},{start:'2026-10-01T00:00:00Z',end:'2026-10-01T02:00:00Z'}],coverage_complete:true,note:'  明确合成未生效日历😀\n原始说明\t '};
  const key=randomUUID(),first=(await request('padmin','POST',route,route,200,'create_explicit_draft',body,{key})).value,path=route+'/'+first.id;
  assert.equal(first.effective,false);assert.equal(first.customer_confirmation,'UNVERIFIED');assert.deepEqual(first.draft.working_windows,body.working_windows);assert.equal(first.draft.note,body.note);
  assert.deepEqual((await request('padmin','POST',route,route,200,'create_original_receipt',body,{key})).value,first);
  await request('padmin','POST',route,route,409,'create_changed_payload_same_key',{...body,name:'different'},{key});
  assert.deepEqual((await request('reviewer','GET',path,detail,200,'second_admin_same_tenant')).value,first);
  for(const role of ['msp','tadmin','viewerA','viewerB']){await request(role,'GET',path,detail,403,role+'_cannot_read_config');await request(role,'POST',route,route,403,role+'_cannot_create',body);}
  await request(null,'GET',path,detail,401,'anonymous');
  await request('padmin','POST',route,route,403,'create_missing_csrf',body,{noCsrf:true});
  await request('padmin','POST',route,route,400,'create_missing_key',body,{noKey:true});
  for(const field of ['tenant_id','actor_id','timezone','effective','customer_confirmation','recurrence','default_hours'])await request('padmin','POST',route,route,400,'reject_'+field,{...body,[field]:'forged'});
  for(const raw of ['{"name":"a","name":"b"}','{} {}',' '.repeat(262145)])await request('padmin','POST',route,route,400,'strict_json_'+checks.length,undefined,{raw});
  for(const value of [{...body,coverage_complete:null},{...body,working_windows:null},{...body,name:'😀'.repeat(101)},{...body,note:'😀'.repeat(2001)},{...body,coverage_end:body.coverage_start},{...body,coverage_start:'2026-10-01T00:00:00.0001Z'},{...body,working_windows:[{...body.working_windows[0],extra:true}]}])await request('padmin','POST',route,route,400,'invalid_input_'+checks.length,value);
  const scenario={expected_version:1,start:body.coverage_start,required_seconds:10800};
  let calc=(await request('padmin','POST',path+'/preview',preview,200,'readonly_union_scenario',scenario,{noKey:true})).value;
  assert.equal(calc.status,'CALCULATED');assert.equal(calc.scenario_deadline,'2026-10-01T03:00:00Z');assert.equal(calc.effective_deadline,null);assert.equal(calc.consumed.length,1);
  let missing=(await request('padmin','POST',path+'/preview',preview,200,'unknown_duration',{...scenario,required_seconds:null},{noKey:true})).value;assert.equal(missing.reason,'DURATION_MISSING');assert.equal(missing.remaining_duration,null);
  missing=(await request('padmin','POST',path+'/preview',preview,200,'unknown_start',{...scenario,start:null},{noKey:true})).value;assert.equal(missing.reason,'START_MISSING');
  calc=(await request('padmin','POST',path+'/preview',preview,200,'explicit_zero',{...scenario,required_seconds:0},{noKey:true})).value;assert.equal(calc.scenario_deadline,body.coverage_start);assert.equal(calc.effective_deadline,null);
  missing=(await request('padmin','POST',path+'/preview',preview,200,'insufficient_windows',{...scenario,required_seconds:10801},{noKey:true})).value;assert.equal(missing.reason,'INSUFFICIENT_WORKING_TIME');assert.equal(missing.remaining_duration,'PT1S');
  await request('padmin','POST',path+'/preview',preview,403,'preview_missing_csrf',scenario,{noCsrf:true,noKey:true});
  await request('padmin','POST',path+'/preview',preview,400,'preview_negative_duration',{...scenario,required_seconds:-1});
  await request('padmin','POST',path+'/preview',preview,400,'preview_spoof_effective',{...scenario,effective:true});
  const update={...body,name:'second-'+unique,expected_version:1},updateKey=randomUUID();
  let latest=(await request('padmin','PATCH',path,detail,200,'update_version',update,{key:updateKey})).value;assert.equal(latest.version,2);assert.equal(latest.created_at,first.created_at);
  assert.deepEqual((await request('padmin','PATCH',path,detail,200,'update_replay',update,{key:updateKey})).value,latest);
  await request('reviewer','PATCH',path,detail,409,'stale_version',update);
  await request('padmin','POST',path+'/preview',preview,409,'preview_stale_version',scenario);
  const concurrentKey=randomUUID(),concurrentBody={...body,expected_version:2,name:'concurrent-'+unique};
  const same=await Promise.all([request('padmin','PATCH',path,detail,200,'same_key_a',concurrentBody,{key:concurrentKey}),request('padmin','PATCH',path,detail,200,'same_key_b',concurrentBody,{key:concurrentKey})]);assert.deepEqual(same[0].value,same[1].value);latest=same[0].value;assert.equal(latest.version,3);
  const cas=await Promise.all([request('padmin','PATCH',path,detail,[200,409],'cas_a',{...body,name:'cas-a-'+unique,expected_version:3}),request('reviewer','PATCH',path,detail,[200,409],'cas_b',{...body,name:'cas-b-'+unique,expected_version:3})]);assert.deepEqual(cas.map(x=>x.status).sort(),[200,409]);latest=cas.find(x=>x.status===200).value;
  for(let i=0;i<8;i++)latest=(await request('padmin','PATCH',path,detail,200,'history_seed_'+i,{...body,name:'history-'+unique+'-'+i,expected_version:latest.version})).value;
  assert.equal(latest.version,12);
  const h1=(await request('padmin','GET',path+'/history?page_size=10',history,200,'history_page_10')).value;assert.equal(h1.items.length,10);assert.equal(h1.has_more,true);assert.equal(h1.items[0].version,12);
  const h2=(await request('padmin','GET',path+'/history?page_size=10&before_version='+h1.next_before_version,history,200,'history_page_2')).value;assert.equal(h2.items.length,2);assert.equal(h2.has_more,false);assert.equal(h2.items[1].before,null);assert.deepEqual(h2.items[1].after,first.draft);
  const incomplete=(await request('padmin','POST',route,route,200,'create_incomplete_unicode',{...body,name:'😀'.repeat(100),note:'😀'.repeat(2000),working_windows:[],coverage_complete:false})).value;
  missing=(await request('padmin','POST',route+'/'+incomplete.id+'/preview',preview,200,'incomplete_no_deadline',{...scenario,expected_version:1},{noKey:true})).value;assert.equal(missing.reason,'COVERAGE_INCOMPLETE');assert.equal(missing.scenario_deadline,null);
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?page_size=10&page_size=15','?page_size=101','?before_id='+first.id])await request('padmin','GET',route+q,route,400,'invalid_query_'+checks.length);
  await request('padmin','GET',path+'/history?before_version=0',history,400,'invalid_history_cursor');
  await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_to_other_tenant',{tenant_id:current.viewerB.tenant.id});
  try{await request('padmin','GET',path,detail,404,'foreign_calendar_hidden');await request('padmin','PATCH',path,detail,404,'foreign_update_hidden',{...body,expected_version:latest.version});await request('padmin','POST',path+'/preview',preview,404,'foreign_preview_hidden',{...scenario,expected_version:latest.version});}
  finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_original_tenant',{tenant_id:current.padmin.tenant.id});}
  const audits=(await request('padmin','GET','/audit/records?resource_id='+first.id+'&page_size=100','/audit/records',200,'calendar_success_audits')).value.items.filter(x=>x.action.startsWith('working_calendar.draft.'));assert.equal(audits.length,12);
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'calendar_readiness')).value;assert.deepEqual(ready.working_calendars,{schema_access_ready:true,effective:false,customer_confirmation:'UNVERIFIED'});
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'read_tail_before')).value.snapshot_sequence;
  await request('padmin','GET',path,detail,200,'readonly_final_detail');await request('padmin','GET',path+'/history',history,200,'readonly_final_history');await request('padmin','GET',route+'?page_size=10',route,200,'readonly_final_list');await request('padmin','POST',path+'/preview',preview,200,'readonly_final_preview',{...scenario,expected_version:latest.version},{noKey:true});
  assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'read_tail_after')).value.snapshot_sequence,tail);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,main_calendar_revisions:12,main_calendar_success_audits:12,second_incomplete_calendar:true,same_key_two_receipts_one_revision:true,different_key_cas_one_winner:true,history_pages:[10,2],readonly_preview_no_success_audit:true,effective_deadline_always_null:true,customer_confirmation:'UNVERIFIED',foreign_tenant_get_update_preview_404:true,original_tenant_restored:true,credentials_output:false,browser_dom_tested:false,limitations:['No calendar activation, customer approval, holiday recurrence or formal SLA','No UI/DOM acceptance','No live role revocation or PG server outage']};
  writeFileSync(new URL('docs/evidence/niko-working-calendar-api-http-20261003.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_checks:checks.length,main_calendar_revisions:12}));
}catch{console.error(JSON.stringify({passed:false,stage,details:'redacted'}));process.exitCode=1;}
