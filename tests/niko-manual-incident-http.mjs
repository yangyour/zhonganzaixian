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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:manual-incident-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:manual-incident-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }

  assert.equal(contract.info.version,'0.19.0');
  const route='/manual-incidents',detail=route+'/{id}',created=[];
  const current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  for(const role of ['msp','padmin','reviewer'])assert.ok(current[role].capabilities.includes('manual_incidents:write'));
  for(const role of ['tadmin','viewerA','viewerB'])assert.ok(!current[role].capabilities.includes('manual_incidents:read'));
  const unique=randomUUID();
  const asset=(await request('msp','POST','/assets','/assets',200,'create_synthetic_asset',{name:'MANUAL-HTTP-'+unique,type:'router',management_ip:'2001:db8:'+unique.slice(0,4)+'::81',site_id:'site-a-main'})).value;
  const body={title:'  合成电话登记😀  ',severity:'warning',asset_id:asset.id,expected_asset_version:asset.version},key=randomUUID();
  const first=(await request('msp','POST',route,route,200,'register_manual_fact',body,{key})).value,path=route+'/'+first.id;created.push(first);
  assert.equal(first.title,body.title);assert.equal(first.asset_name_at_registration,asset.name);assert.equal(first.observed_asset_version,1);assert.equal(first.source_state,'MANUAL');assert.equal(first.registration_status,'REGISTERED_ONLY');assert.equal(first.manual_recovery_status,'NOT_CONFIRMED');assert.equal(first.monitoring_recovery_applicable,false);assert.equal(first.ticket_automatically_created,false);
  for(const field of ['monitor_state','disposition_state','ticket_priority','observed_at','recovered_at'])assert.equal(first[field],null);
  assert.deepEqual((await request('msp','POST',route,route,200,'original_receipt',body,{key})).value,first);
  const reordered={expected_asset_version:body.expected_asset_version,asset_id:body.asset_id,severity:body.severity,title:body.title};assert.deepEqual((await request('msp','POST',route,route,200,'same_key_reordered_json',reordered,{key})).value,first);
  await request('msp','POST',route,route,409,'same_key_changed_title',{...body,title:'different'},{key});
  assert.deepEqual((await request('padmin','GET',path,detail,200,'admin_reads_internal_fact')).value,first);
  assert.deepEqual((await request('msp','GET','/assets/'+asset.id,'/assets/{id}',200,'registration_did_not_change_asset')).value,asset);
  for(const role of ['viewerA','viewerB','tadmin']){await request(role,'GET',path,detail,403,role+'_detail_denied');await request(role,'GET',route,route,403,role+'_list_denied');await request(role,'POST',route,route,403,role+'_create_denied',body);}
  await request(null,'GET',path,detail,401,'anonymous');await request('msp','POST',route,route,403,'csrf_required',body,{noCsrf:true});await request('msp','POST',route,route,400,'key_required',body,{noKey:true});
  for(const field of ['tenant_id','actor_id','source_state','registration_status','monitor_state','disposition_state','ticket_priority','observed_at','recovered_at','asset_name_at_registration','manual_recovery_status','ticket_automatically_created'])await request('msp','POST',route,route,400,'reject_claim_'+field,{...body,[field]:'forged'});
  for(const patch of [{title:null},{title:' \ufeff\u3000'},{title:'x\n'},{title:'😀'.repeat(201)},{severity:'none'},{severity:null},{asset_id:'bad'},{expected_asset_version:0},{expected_asset_version:null},{expected_asset_version:'1'},{expected_asset_version:1.5},{expected_asset_version:9007199254740992}])await request('msp','POST',route,route,400,'invalid_input_'+checks.length,{...body,...patch});
  for(const raw of ['{"title":"a","title":"b"}','{} {}','[[[1]]]',' '.repeat(8193)])await request('msp','POST',route,route,400,'strict_json_'+checks.length,undefined,{raw});
  const missing={...body};delete missing.severity;await request('msp','POST',route,route,400,'missing_explicit_severity',missing);
  const foreignAssets=(await request('viewerB','GET','/assets?page_size=10','/assets',200,'foreign_asset_own_tenant')).value.items;assert.ok(foreignAssets.length);
  await request('msp','POST',route,route,404,'foreign_asset_before_cas',{...body,asset_id:foreignAssets[0].id,expected_asset_version:999});
  await request('msp','POST',route,route,404,'missing_asset',{...body,asset_id:randomUUID()});
  await request('msp','POST',route,route,409,'wrong_observed_version',{...body,expected_asset_version:2});
  const facts={expected_version:1,name:asset.name+' revised',type:asset.type,site_id:asset.site_id,brand:null,model:null,serial_number:null,location:null,owner_id:null,business_description:null,purchase_date:null,warranty_expiry:null,overdue_service_date:null,collection_method_note:null,note:'合成资料修订，用于验证原始登记快照'};
  const updated=(await request('msp','PATCH','/assets/'+asset.id+'/manual-metadata','/assets/{id}/manual-metadata',200,'asset_rename_after_registration',facts)).value;
  assert.equal(updated.asset.version,2);
  assert.deepEqual((await request('msp','POST',route,route,200,'original_receipt_after_asset_rename',body,{key})).value,first);
  assert.deepEqual((await request('msp','GET',path,detail,200,'immutable_asset_name_snapshot')).value,first);
  await request('msp','POST',route,route,409,'new_request_old_asset_version',body);
  const latestBody={...body,expected_asset_version:2},sameKey=randomUUID();
  const same=await Promise.all([request('msp','POST',route,route,200,'same_key_concurrent_a',latestBody,{key:sameKey}),request('msp','POST',route,route,200,'same_key_concurrent_b',latestBody,{key:sameKey})]);assert.deepEqual(same[0].value,same[1].value);created.push(same[0].value);assert.equal(same[0].value.asset_name_at_registration,updated.asset.name);
  const independent=await Promise.all(['msp','padmin'].map((role,i)=>request(role,'POST',route,route,200,'independent_registration_'+i,{...latestBody,title:'独立来源'+i})));assert.notEqual(independent[0].value.id,independent[1].value.id);created.push(...independent.map(r=>r.value));
  for(const severity of ['disaster','high','average','warning','info'])created.push((await request('padmin','POST',route,route,200,'severity_'+severity,{...latestBody,severity,title:'😀'.repeat(200)})).value);
  for(let i=0;i<5;i++)created.push((await request('msp','POST',route,route,200,'pagination_seed_'+i,{...latestBody,title:'合成分页登记'+i})).value);
  assert.equal(created.length,14);
  const p1=(await request('msp','GET',route+'?page_size=10',route,200,'page_10')).value;assert.equal(p1.items.length,10);assert.equal(p1.has_more,true);
  const p2=(await request('msp','GET',route+'?page_size=10&before_registered_at='+encodeURIComponent(p1.next_before_registered_at)+'&before_id='+p1.next_before_id,route,200,'next_page_no_overlap')).value;assert.ok(p1.items.every(a=>!p2.items.some(b=>a.id===b.id)));const combined=[...p1.items,...p2.items];assert.ok(created.every(a=>combined.some(b=>b.id===a.id)));assert.ok(combined.every(a=>a.tenant_id===current.msp.tenant.id));
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?page_size=10&page_size=15','?page_size=101','?before_id='+first.id,'?before_registered_at=2026-10-03T00:00:00.0001Z&before_id='+first.id])await request('msp','GET',route+q,route,400,'invalid_query_'+checks.length);
  await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_other_tenant',{tenant_id:current.viewerB.tenant.id});
  try{await request('padmin','GET',path,detail,404,'foreign_registration_hidden');await request('padmin','POST',route,route,404,'foreign_registration_asset_hidden',latestBody);const other=(await request('padmin','GET',route,route,200,'other_tenant_list')).value;assert.ok(other.items.every(a=>a.tenant_id===current.viewerB.tenant.id));assert.ok(other.items.every(a=>!created.some(b=>a.id===b.id)));}
  finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_original_tenant',{tenant_id:current.padmin.tenant.id});}
  let auditCount=0;for(const item of created){const audit=(await request('padmin','GET','/audit/records?resource_id='+item.id+'&action=manual_incident.register','/audit/records',200,'exact_one_registration_audit_'+auditCount)).value.items;assert.equal(audit.length,1);auditCount++;}
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'manual_schema_readiness')).value;assert.deepEqual(ready.manual_incidents,{schema_access_ready:true,source_state:'MANUAL',registration_status:'REGISTERED_ONLY',monitoring_recovery_applicable:false,ticket_automatically_created:false});
  assert.deepEqual((await request('msp','GET','/assets/'+asset.id,'/assets/{id}',200,'later_registrations_do_not_mutate_asset')).value,updated.asset);
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'read_tail_before')).value.snapshot_sequence;
  await request('msp','GET',path,detail,200,'readonly_final_detail');await request('msp','GET',route,route,200,'readonly_final_list');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'read_tail_after')).value.snapshot_sequence,tail);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,created_manual_registrations:14,successful_registration_audits:14,original_asset_snapshot_preserved:true,same_key_two_receipts_one_registration:true,distinct_explicit_sources_independent:true,readonly_reads_no_success_audit:true,monitoring_recovery_applicable:false,ticket_automatically_created:false,foreign_tenant_objects_404:true,original_tenant_restored:true,credentials_output:false,browser_dom_tested:false,limitations:['No UI/DOM acceptance','No human recovery, formal event disposition, event-to-ticket or SLA workflow','No live membership revocation or PG server outage']};
  writeFileSync(new URL('docs/evidence/niko-manual-incident-api-http-20261003.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_checks:checks.length,created_registrations:14}));
}catch{console.error(JSON.stringify({passed:false,stage,details:'redacted'}));process.exitCode=1;}
