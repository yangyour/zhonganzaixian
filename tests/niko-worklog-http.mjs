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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:worklog-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:worklog-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/tickets/{ticketId}/worklogs',detail=route+'/{id}',history=detail+'/history';
  const current={};for(const role of Object.keys(sessions)){current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;assert.equal(current[role].capabilities.includes('worklogs:read_own'),role==='msp');assert.equal(current[role].capabilities.includes('worklogs:write_own'),role==='msp');}
  const facts={kind:'REMOTE',started_at:'2026-10-02T08:00:00+08:00',ended_at:'2026-10-04T09:00:00+08:00',note:null};
  async function parent(role,label){const accepted=(await request(role,'POST','/tickets','/tickets',202,label+'_register',{title:'WORKLOG-HTTP-'+randomUUID(),source:'customer_report',description:'虚构工时父登记',category:'网络不通',impact_scope:'individual',contact_name:'虚构联系人',contact_phone:'+00 000',urgency:'normal',attachment_ids:[]})).value;const operation=(await request(role,'GET','/operations/'+accepted.operation_id,'/operations/{id}',200,label+'_receipt')).value;return operation.result_ref.split('/').at(-1);}
  const ticket=await parent('viewerA','own_parent'),second=await parent('viewerA','other_parent'),foreign=await parent('viewerB','foreign_parent');
  const path='/tickets/'+ticket+'/worklogs',body={expected_ticket_version:1,...facts};
  const before=(await request('msp','GET','/tickets/'+ticket,'/tickets/{id}',200,'parent_before')).value;
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'worklog_schema_readiness')).value;assert.equal(ready.worklogs.ready,true);assert.equal(ready.worklogs.financial_status,'CONFIG_PENDING');
  assert.deepEqual((await request('msp','GET',path,route,200,'initial_own_list')).value.items,[]);
  const key=randomUUID();const saved=(await request('msp','POST',path,route,200,'create_49h',body,{key})).value;
  assert.equal(saved.elapsed_millis,49*3600000);assert.equal(saved.owner_id,current.msp.user.id);assert.equal(saved.tenant_id,current.msp.tenant.id);assert.equal(saved.verification_status,'SELF_REPORTED');for(const field of ['default_minutes','labor_cost','transport_cost','gross_margin'])assert.equal(saved[field],null);
  assert.deepEqual((await request('msp','POST',path,route,200,'create_exact_replay',body,{key})).value,saved);
  await request('msp','POST',path,route,409,'create_changed_key_conflict',{...body,note:'更改'},{key});
  const itemPath=path+'/'+saved.id,update={expected_version:1,...facts,kind:'ONSITE',ended_at:facts.started_at,note:'明确实填零时长'};
  const updateKey=randomUUID();const changed=(await request('msp','PATCH',itemPath,detail,200,'update_explicit_zero',update,{key:updateKey})).value;assert.equal(changed.elapsed_millis,0);assert.equal(changed.version,2);assert.equal(changed.created_at,saved.created_at);
  assert.deepEqual((await request('msp','PATCH',itemPath,detail,200,'update_exact_replay',update,{key:updateKey})).value,changed);
  await request('msp','PATCH',itemPath,detail,409,'update_changed_key_conflict',{...update,note:'其他'},{key:updateKey});
  await request('msp','PATCH',itemPath,detail,409,'stale_record_cas',update);
  await request('msp','POST',path,route,409,'stale_parent_cas',{...body,expected_ticket_version:2});
  assert.deepEqual((await request('msp','GET',itemPath,detail,200,'current_readback')).value,changed);
  const revisions=(await request('msp','GET',itemPath+'/history',history,200,'immutable_history')).value.items;assert.deepEqual(revisions.map(r=>r.version),[2,1]);assert.equal(revisions[1].elapsed_millis,49*3600000);assert.equal(revisions[0].elapsed_millis,0);
  assert.deepEqual((await request('msp','GET',itemPath+'/history?before_version=2',history,200,'history_keyset')).value.items.map(r=>r.version),[1]);
  for(const role of ['viewerA','viewerB','tadmin','padmin','reviewer']){await request(role,'GET',itemPath,detail,403,role+'_read_not_granted');await request(role,'POST',path,route,403,role+'_write_not_granted',body);}
  await request('msp','GET','/tickets/'+foreign+'/worklogs',route,404,'cross_tenant_parent_hidden');
  await request('msp','POST','/tickets/'+foreign+'/worklogs',route,404,'cross_tenant_write_hidden',body);
  await request('msp','GET','/tickets/'+second+'/worklogs/'+saved.id,detail,404,'wrong_parent_hidden');
  await request('msp','PATCH','/tickets/'+second+'/worklogs/'+saved.id,detail,404,'wrong_parent_update_hidden',update);
  await request(null,'GET',path,route,401,'anonymous_read');
  await request('msp','POST',path,route,403,'missing_csrf',body,{noCsrf:true});await request('msp','POST',path,route,400,'missing_key',body,{noKey:true});
  await request('msp','POST',path,route,400,'duplicate_json',undefined,{raw:'{"expected_ticket_version":1,"kind":"REMOTE","kind":"ONSITE","started_at":"2026-10-02T00:00:00Z","ended_at":"2026-10-02T01:00:00Z","note":null}'});
  await request('msp','POST',path,route,400,'oversized_body',undefined,{raw:' '.repeat(32769)});
  for(const [name,value] of [['owner_spoof',{...body,owner_id:current.viewerA.user.id}],['tenant_spoof',{...body,tenant_id:current.viewerB.tenant.id}],['cost_spoof',{...body,labor_cost:0}],['default_spoof',{...body,default_minutes:0}],['no_offset',{...body,started_at:'2026-10-02T00:00:00'}],['microseconds',{...body,started_at:'2026-10-02T00:00:00.1234Z'}],['backwards',{...body,ended_at:'2026-10-01T00:00:00Z'}],['blank_note',{...body,note:'\u00a0'}],['unsafe_version',{...body,expected_ticket_version:9007199254740991}]])await request('msp','POST',path,route,400,name,value);
  for(const query of ['?tenant_id=spoof','?page_size=10&page_size=15','?before_id='+saved.id,'?page_size=101'])await request('msp','GET',path+query,route,400,'invalid_list_query_'+checks.length);
  const race=await Promise.all([1,2].map(i=>request('msp','PATCH',itemPath,detail,[200,409],'concurrent_update_'+i,{expected_version:2,...facts,note:'并发合成修订'+i})));assert.deepEqual(race.map(r=>r.status).sort(),[200,409]);
  for(let i=0;i<11;i++)await request('msp','POST',path,route,200,'paging_record_'+i,{...body,kind:i%2?'INSPECTION':'COMMUTE',note:'合成分页工时'+i});
  const page=(await request('msp','GET',path+'?page_size=10',route,200,'first_page')).value;assert.equal(page.items.length,10);assert.equal(page.has_more,true);const next=(await request('msp','GET',path+'?page_size=10&before_created_at='+encodeURIComponent(page.next_before_created_at)+'&before_id='+page.next_before_id,route,200,'second_page')).value;assert.equal(next.items.length,2);assert.equal(next.has_more,false);assert.equal(next.next_before_id,null);assert.equal(new Set([...page.items,...next.items].map(x=>x.id)).size,12);
  assert.deepEqual((await request('msp','GET','/tickets/'+ticket,'/tickets/{id}',200,'parent_unchanged')).value,before);
  const audit=(await request('padmin','GET','/audit','/audit',200,'audit_write_once')).value;const auditItems=Array.isArray(audit)?audit:audit.items;for(const name of ['create_49h','update_explicit_zero'])assert.equal(auditItems.filter(r=>r.trace_id===traces.get(name)&&r.resource_id===saved.id).length,1);for(const name of ['create_exact_replay','update_exact_replay'])assert.equal(auditItems.filter(r=>r.trace_id===traces.get(name)&&r.resource_id===saved.id).length,0);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,concurrent_statuses:[200,409],postgres:'17.11',keycloak:'26.2.4',costs_and_defaults:null,credentials_output:false,limitations:['No browser DOM or final page acceptance','Manual SELF_REPORTED facts are not independently verified attendance','No default hours/cost/margin or team/administrator override','Not full M5-21/M10-13 acceptance','No real OSS operations']};
  writeFileSync(new URL('docs/evidence/niko-worklog-api-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,api_version:contract.info.version,credentials_output:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
