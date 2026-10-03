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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:manual-recovery-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:manual-recovery-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }

  assert.equal(contract.info.version,'0.20.0');
  const route='/manual-incidents/{incidentId}/recovery',manualRoute='/manual-incidents',manualDetail=manualRoute+'/{id}',confirmed=[];
  const current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  for(const role of ['msp','padmin','reviewer'])assert.ok(current[role].capabilities.includes('manual_recovery:confirm'));for(const role of ['viewerA','viewerB','tadmin'])assert.ok(!current[role].capabilities.includes('manual_recovery:read'));
  const unique=randomUUID(),asset=(await request('msp','POST','/assets','/assets',200,'create_synthetic_asset',{name:'RECOVERY-HTTP-'+unique,type:'router',management_ip:'2001:db8:'+unique.slice(0,4)+'::83',site_id:'site-a-main'})).value;
  async function parent(suffix){const body={title:'人工恢复API合成父登记-'+suffix+'-'+unique,severity:'warning',asset_id:asset.id,expected_asset_version:asset.version},key=randomUUID();return {value:(await request('msp','POST',manualRoute,manualRoute,200,'register_parent_'+suffix,body,{key})).value,body,key};}
  const original=await parent('main'),path=manualRoute+'/'+original.value.id+'/recovery',body={expected_version:0,note:'  电话人工确认😀\r\n说明原文\t ',reported_recovered_at:null};
  const empty=(await request('msp','GET',path,route,200,'initial_unconfirmed')).value;assert.equal(empty.version,0);assert.equal(empty.manual_recovery_status,'NOT_CONFIRMED');assert.equal(empty.confirmation,null);
  for(const role of ['viewerA','viewerB','tadmin']){await request(role,'GET',path,route,403,role+'_read_denied');await request(role,'POST',path,route,403,role+'_confirm_denied',body);}
  await request(null,'GET',path,route,401,'anonymous');await request('msp','POST',path,route,403,'csrf_required',body,{noCsrf:true});await request('msp','POST',path,route,400,'key_required',body,{noKey:true});
  for(const field of ['tenant_id','confirmed_by','recorded_at','monitor_state','disposition_state','manual_recovery_status','verification_status','monitoring_recovery_applicable','ticket_state_changed','sla_changed'])await request('msp','POST',path,route,400,'reject_claim_'+field,{...body,[field]:'forged'});
  for(const patch of [{expected_version:-1},{expected_version:2},{expected_version:'0'},{expected_version:null},{note:null},{note:' \ufeff\u3000'},{note:'x\u0085'},{note:'😀'.repeat(2001)},{reported_recovered_at:'2026-10-03'},{reported_recovered_at:'2026-10-03T00:00:00.0001Z'},{reported_recovered_at:'0000-01-01T00:00:00Z'}])await request('msp','POST',path,route,400,'invalid_input_'+checks.length,{...body,...patch});
  for(const raw of ['{"note":"one","note":"two"}','{} {}','[[[1]]]',' '.repeat(32769)])await request('msp','POST',path,route,400,'strict_json_'+checks.length,undefined,{raw});
  const missing={...body};delete missing.reported_recovered_at;await request('msp','POST',path,route,400,'explicit_nullable_time_required',missing);
  await request('msp','POST',path,route,400,'future_recovery_not_asserted_as_completed',{...body,reported_recovered_at:'9999-12-31T23:59:59.999Z'});await request('msp','POST',path,route,409,'wrong_initial_version',{...body,expected_version:1});
  assert.deepEqual((await request('msp','GET',path,route,200,'invalid_requests_left_no_confirmation')).value,empty);
  const key=randomUUID(),first=(await request('msp','POST',path,route,200,'explicit_human_confirmation',body,{key})).value;confirmed.push(first);assert.equal(first.version,1);assert.equal(first.manual_recovery_status,'HUMAN_CONFIRMED');assert.equal(first.confirmation.note,body.note);assert.equal(first.confirmation.reported_recovered_at,null);assert.equal(first.confirmation.confirmed_by,current.msp.user.id);assert.equal(first.confirmation.verification_status,'SELF_REPORTED');assert.equal(first.confirmation.monitor_state,null);assert.equal(first.confirmation.disposition_state,null);assert.equal(first.confirmation.monitoring_recovery_applicable,false);assert.equal(first.confirmation.ticket_state_changed,false);assert.equal(first.confirmation.sla_changed,false);
  assert.deepEqual((await request('msp','POST',path,route,200,'original_confirmation_receipt',body,{key})).value,first);
  assert.deepEqual((await request('msp','POST',path,route,200,'same_key_reordered_fields',{reported_recovered_at:null,note:body.note,expected_version:0},{key})).value,first);
  await request('msp','POST',path,route,409,'same_key_changed_note',{...body,note:'changed'},{key});await request('padmin','POST',path,route,409,'different_key_old_version',body);await request('padmin','POST',path,route,422,'already_confirmed_cannot_overwrite',{...body,expected_version:1});
  assert.deepEqual((await request('reviewer','GET',path,route,200,'other_internal_reads_current')).value,first);
  assert.deepEqual((await request('msp','GET',manualRoute+'/'+original.value.id,manualDetail,200,'original_registration_unchanged')).value,original.value);
  assert.deepEqual((await request('msp','POST',manualRoute,manualRoute,200,'original_registration_receipt_unchanged',original.body,{key:original.key})).value,original.value);
  assert.deepEqual((await request('msp','GET',path,route,200,'original_registration_replay_did_not_reset_recovery')).value,first);
  const sameParent=await parent('same-key'),samePath=manualRoute+'/'+sameParent.value.id+'/recovery',sameKey=randomUUID();
  const same=await Promise.all([request('msp','POST',samePath,route,200,'same_key_concurrent_a',body,{key:sameKey}),request('msp','POST',samePath,route,200,'same_key_concurrent_b',body,{key:sameKey})]);assert.deepEqual(same[0].value,same[1].value);confirmed.push(same[0].value);
  const raceParent=await parent('human-race'),racePath=manualRoute+'/'+raceParent.value.id+'/recovery';const race=await Promise.all([request('msp','POST',racePath,route,[200,409],'different_key_human_a',{...body,note:'合成人工甲'}),request('padmin','POST',racePath,route,[200,409],'different_key_human_b',{...body,note:'合成人工乙'})]);assert.deepEqual(race.map(r=>r.status).sort(),[200,409]);confirmed.push(race.find(r=>r.status===200).value);
  const historicalParent=await parent('historical'),historicalPath=manualRoute+'/'+historicalParent.value.id+'/recovery';const historical=(await request('padmin','POST',historicalPath,route,200,'historical_offset_max_unicode',{...body,note:'😀'.repeat(2000),reported_recovered_at:'2020-02-29T08:00:00.123+08:00'})).value;confirmed.push(historical);assert.equal(historical.confirmation.reported_recovered_at,'2020-02-29T00:00:00.123Z');assert.equal(historical.confirmation.note,'😀'.repeat(2000));
  for(const query of ['?tenant_id='+current.viewerB.tenant.id,'?expected_version=0','?x=1&x=2']){await request('msp','GET',path+query,route,400,'query_rejected_get_'+checks.length);await request('msp','POST',path+query,route,400,'query_rejected_post_'+checks.length,body);}
  await request('msp','GET',manualRoute+'/'+randomUUID()+'/recovery',route,404,'missing_parent');
  const foreignAsset=(await request('viewerB','GET','/assets?page_size=10','/assets',200,'foreign_visible_asset')).value.items[0];assert.ok(foreignAsset);let foreign;
  await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_other_tenant',{tenant_id:current.viewerB.tenant.id});
  try{await request('padmin','GET',path,route,404,'foreign_human_evidence_hidden');await request('padmin','POST',path,route,404,'foreign_parent_confirm_hidden',body);foreign=(await request('padmin','POST',manualRoute,manualRoute,200,'foreign_synthetic_parent',{title:'跨租户合成恢复父登记-'+unique,severity:'info',asset_id:foreignAsset.id,expected_asset_version:foreignAsset.version})).value;}
  finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_original_tenant',{tenant_id:current.padmin.tenant.id});}
  await request('msp','GET',manualRoute+'/'+foreign.id+'/recovery',route,404,'foreign_unconfirmed_parent_hidden');await request('msp','POST',manualRoute+'/'+foreign.id+'/recovery',route,404,'foreign_parent_hidden_before_cas',{...body,expected_version:1});
  for(let i=0;i<confirmed.length;i++){const value=confirmed[i],records=(await request('padmin','GET','/audit/records?resource_id='+value.confirmation.id+'&action=manual_incident.recovery.confirm','/audit/records',200,'one_success_audit_'+i)).value.items;assert.equal(records.length,1);assert.deepEqual((await request('padmin','GET',manualRoute+'/'+value.incident_id+'/recovery',route,200,'immutable_confirmation_'+i)).value,value);}
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'human_recovery_schema_ready')).value;assert.deepEqual(ready.manual_recovery,{schema_access_ready:true,source:'HUMAN_CONFIRMATION',monitoring_recovery_applicable:false,ticket_state_changed:false,sla_changed:false});
  assert.deepEqual((await request('msp','GET','/assets/'+asset.id,'/assets/{id}',200,'asset_unchanged')).value,asset);
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_before')).value.snapshot_sequence;await request('msp','GET',path,route,200,'readonly_current');await request('msp','GET',manualRoute+'/'+original.value.id,manualDetail,200,'readonly_original_snapshot');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_after')).value.snapshot_sequence,tail);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,synthetic_manual_parents:5,human_confirmations:4,matching_success_audits:4,same_key_two_receipts_one_confirmation:true,different_keys_two_humans_one_winner:true,original_registration_and_receipt_unchanged:true,unknown_recovery_time_not_filled:true,explicit_historical_time_preserved:true,future_time_rejected:true,current_and_original_snapshots_separate:true,foreign_objects_404:true,original_tenant_restored:true,readonly_success_audit_tail_unchanged:true,monitoring_callback_tested:false,ticket_state_changed:false,sla_changed:false,credentials_output:false,browser_dom_tested:false,limitations:['No recovery UI/DOM acceptance yet','No live role revocation or PG process failure','No correction/withdrawal, formal disposition, event-to-ticket or SLA conclusion']};
  writeFileSync(new URL('docs/evidence/niko-manual-recovery-api-http-20261003.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_checks:checks.length,human_confirmations:4}));
}catch{console.error(JSON.stringify({passed:false,stage,details:'redacted'}));process.exitCode=1;}
