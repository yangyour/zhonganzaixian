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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:asset-metadata-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:asset-metadata-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/assets/{id}/manual-metadata',history=route+'/history';
  const current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  const prefix='ASSET-METADATA-HTTP-'+randomUUID();
  async function createAsset(suffix){return (await request('msp','POST','/assets','/assets',200,'synthetic_asset_'+suffix,{name:prefix+'-'+suffix,type:'router',management_ip:'2001:db8:'+randomUUID().slice(0,4)+'::'+suffix,site_id:'site-a-main'})).value;}
  const asset=await createAsset('1'),second=await createAsset('2'),path='/assets/'+asset.id+'/manual-metadata';
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'metadata_schema_readiness')).value;assert.equal(ready.asset_manual_metadata.schema_access_ready,true);assert.equal(ready.asset_manual_metadata.monitoring_applied,false);
  const initial=(await request('msp','GET',path,route,200,'initial_unknown_metadata')).value;assert.equal(initial.metadata,null);assert.equal(initial.metadata_version,null);assert.equal(initial.verification_status,null);assert.equal(initial.can_edit,true);
  const facts={name:asset.name,type:'switch',site_id:'site-a-main',brand:null,model:null,serial_number:null,location:null,owner_id:null,business_description:null,purchase_date:null,warranty_expiry:null,overdue_service_date:null,collection_method_note:null,note:'虚构测试：仅维护未接入草稿资料'};
  const firstBody={expected_version:1,...facts},firstKey=randomUUID();const saved=(await request('msp','PATCH',path,route,200,'first_explicit_null_revision',firstBody,{key:firstKey})).value;
  assert.equal(saved.asset.version,2);assert.equal(saved.metadata_version,2);assert.equal(saved.metadata.type,'switch');assert.equal(saved.metadata.purchase_date,null);assert.equal(saved.metadata.owner_id,null);assert.equal(saved.asset.management_ip,asset.management_ip);assert.equal(saved.asset.created_at,asset.created_at);assert.equal(saved.asset.status,'unknown');assert.equal(saved.asset.availability,'UNKNOWN');assert.equal(saved.asset.sync_state,'PENDING_ONBOARDING');assert.equal(saved.asset.sampled_at,null);assert.equal(saved.cmdb_applied,false);
  assert.deepEqual((await request('msp','PATCH',path,route,200,'first_exact_replay',firstBody,{key:firstKey})).value,saved);
  await request('msp','PATCH',path,route,409,'same_key_changed_body',{...firstBody,note:'changed'},{key:firstKey});
  const secondBody={...firstBody,expected_version:2,name:asset.name+'-revised',owner_id:current.msp.user.id,brand:'原文品牌',purchase_date:'0001-01-01',warranty_expiry:'9999-12-31',overdue_service_date:'2026-10-02',note:'第二次资料维护'},secondKey=randomUUID();
  const changed=(await request('padmin','PATCH',path,route,200,'admin_second_revision',secondBody,{key:secondKey})).value;assert.equal(changed.metadata_version,3);assert.equal(changed.metadata.owner_id,current.msp.user.id);assert.equal(changed.metadata.purchase_date,'0001-01-01');assert.equal(changed.metadata.warranty_expiry,'9999-12-31');assert.equal(changed.asset.management_ip,asset.management_ip);
  assert.deepEqual((await request('padmin','PATCH',path,route,200,'second_exact_replay',secondBody,{key:secondKey})).value,changed);
  assert.deepEqual((await request('msp','PATCH',path,route,200,'old_key_original_receipt_after_new_revision',firstBody,{key:firstKey})).value,saved);
  assert.deepEqual((await request('msp','GET',path,route,200,'current_not_rolled_back_by_replay')).value,changed);
  const revisions=(await request('reviewer','GET',path+'/history',history,200,'immutable_before_after_history')).value.items;assert.deepEqual(revisions.map(r=>r.asset_version),[3,2]);assert.equal(revisions[1].before.metadata,null);assert.equal(revisions[1].before.asset.version,1);assert.equal(revisions[1].before.asset.type,'router');assert.equal(revisions[0].before.asset.version,2);assert.equal(revisions[0].before.metadata.type,'switch');assert.equal(revisions[0].previous_metadata_version,2);
  assert.deepEqual((await request('msp','GET',path+'/history?before_version=3',history,200,'history_keyset')).value.items.map(r=>r.asset_version),[2]);
  await request('msp','PATCH',path,route,409,'stale_asset_cas',secondBody);
  await request('msp','PATCH',path,route,409,'existing_name_conflict',{...secondBody,expected_version:3,name:second.name});
  await request('msp','PATCH',path,route,404,'foreign_owner_hidden_before_stale_cas',{...firstBody,owner_id:current.viewerB.user.id});
  await request('msp','PATCH',path,route,404,'unknown_site_hidden_before_stale_cas',{...firstBody,site_id:'not-a-real-site'});
  const foreignAssets=(await request('viewerB','GET','/assets?page_size=10','/assets',200,'foreign_visible_to_own_viewer')).value.items;assert.ok(foreignAssets.length>0);const foreignPath='/assets/'+foreignAssets[0].id+'/manual-metadata';
  await request('msp','GET',foreignPath,route,404,'cross_tenant_asset_hidden');await request('msp','PATCH',foreignPath,route,404,'cross_tenant_write_hidden',firstBody);await request('msp','GET',foreignPath+'/history',history,404,'cross_tenant_history_hidden');
  const seeds=(await request('msp','GET','/assets?page_size=100','/assets',200,'find_existing_seed')).value.items;const seed=seeds.find(a=>a.source==='test_seed');assert.ok(seed);const seedPath='/assets/'+seed.id+'/manual-metadata';assert.equal((await request('msp','GET',seedPath,route,200,'seed_read_shows_blocker')).value.can_edit,false);await request('msp','PATCH',seedPath,route,422,'seed_not_rewritten_as_manual',{...firstBody,expected_version:seed.version});
  for(const role of ['viewerA','viewerB','tadmin']){await request(role,'GET',path,route,403,role+'_read_denied');await request(role,'PATCH',path,route,403,role+'_write_denied',firstBody);await request(role,'GET',path+'/history',history,403,role+'_history_denied');}
  await request(null,'GET',path,route,401,'anonymous_denied');await request('msp','PATCH',path,route,403,'csrf_required',firstBody,{noCsrf:true});await request('msp','PATCH',path,route,400,'idempotency_key_required',firstBody,{noKey:true});
  for(const key of ['management_ip','tenant_id','source','status','actor_id','monitoring_applied'])await request('msp','PATCH',path,route,400,'forbidden_field_'+key,{...firstBody,[key]:'forged'});
  for(const date of ['0000-01-01','2026-02-30','2026-10-02T00:00:00Z',123])await request('msp','PATCH',path,route,400,'invalid_date_'+checks.length,{...firstBody,purchase_date:date});
  const missing={...firstBody};delete missing.owner_id;await request('msp','PATCH',path,route,400,'missing_explicit_null',missing);
  await request('msp','PATCH',path,route,400,'duplicate_json',undefined,{raw:'{"name":"a","name":"b"}'});await request('msp','PATCH',path,route,400,'trailing_json',undefined,{raw:'{} {}'});await request('msp','PATCH',path,route,400,'body_byte_bound',undefined,{raw:' '.repeat(32769)});
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?page_size=10&page_size=15','?page_size=1000','?before_version=1','?before_version=9007199254740991'])await request('msp','GET',path+'/history'+q,history,400,'invalid_query_'+checks.length);
  const concurrent=await Promise.all([1,2].map(i=>request('msp','PATCH',path,route,[200,409],'concurrent_'+i,{...secondBody,expected_version:3,note:'并发明确修订'+i})));assert.deepEqual(concurrent.map(r=>r.status).sort(),[200,409]);
  const final=(await request('msp','GET',path,route,200,'final_current')).value;assert.equal(final.asset.version,4);assert.equal(final.asset.management_ip,asset.management_ip);assert.equal(final.asset.source,'manual');assert.equal(final.asset.status,'unknown');const finalHistory=(await request('msp','GET',path+'/history',history,200,'final_three_revisions')).value.items;assert.deepEqual(finalHistory.map(r=>r.asset_version),[4,3,2]);
  const audit=(await request('padmin','GET','/audit/records?resource_id='+asset.id+'&action=asset.manual_metadata.update','/audit/records',200,'exactly_three_successful_update_audits')).value.items;assert.equal(audit.length,3);for(const label of ['first_explicit_null_revision','admin_second_revision'])assert.equal(audit.filter(r=>r.trace_id===traces.get(label)).length,1);for(const label of ['first_exact_replay','second_exact_replay','old_key_original_receipt_after_new_revision'])assert.equal(audit.filter(r=>r.trace_id===traces.get(label)).length,0);
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_before')).value.snapshot_sequence;await request('msp','GET',path,route,200,'readonly_current');await request('msp','GET',path+'/history',history,200,'readonly_history');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_after')).value.snapshot_sequence,tail);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,concurrent_statuses:[200,409],durable_metadata_revisions:3,successful_update_audits:3,management_ip_unchanged:true,monitoring_applied:false,cmdb_applied:false,successful_reads_did_not_append_audit:true,credentials_output:false,limitations:['Internal pristine manual draft facts only; customer maintenance Q09 pending','No management IP change or CMDB/monitoring configuration','No browser DOM, real OSS or full M3-5 acceptance']};
  writeFileSync(new URL('docs/evidence/niko-asset-metadata-api-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,api_version:contract.info.version,credentials_output:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
