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
  const evidenceFile=new URL(environment==='designated-server'?'docs/evidence/local-customer-contact-api-http-20261003.json':'docs/evidence/niko-customer-contact-api-http-20261003.json',root);
  const catalogFile=new URL(environment==='designated-server'?'.local/designated-test/contact-api-catalog-snapshot.json':'.local/niko-test/contact-api-catalog-snapshot.json',root);
  assert.equal(existsSync(evidenceFile)||existsSync(catalogFile),false,'Existing evidence must be preserved');
  const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));assert.equal(contract.info.version,'0.30.0');
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:contact-http',components:contract.components});
  const validators=new Map(),checks=[],traces=new Map();
  const sessions=Object.fromEntries(['msp','tadmin','viewerA','viewerB','padmin','reviewer'].map(role=>{
    const session=JSON.parse(readFileSync(new URL(`${privateDirectory}sessions/qa-${role}.json`,root),'utf8'));
    assert.equal(session.environment,environment);return [role,session];
  }));
  async function request(role,method,path,route,expected,name,body,options={}){
    stage=name;const headers={'Content-Type':options.contentType??'application/json'};
    if(role){headers.Cookie=sessions[role].cookie;if(!options.noCsrf)headers['X-CSRF-Token']=sessions[role].csrf_token;}
    if(!options.noKey)headers['Idempotency-Key']=options.key??randomUUID();
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers,body:options.raw??(body===undefined?undefined:JSON.stringify(body)),redirect:'manual',signal:AbortSignal.timeout(20000)});
    stage=`${name}: actual=${response.status}`;assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status));
    const payload=await response.json();const schema=contract.paths[route]?.[method.toLowerCase()]?.responses[response.status]?.content?.['application/json']?.schema;assert.ok(schema);
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:contact-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/contacts',current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  for(const role of Object.keys(sessions))for(const cap of ['contacts:read','contacts:write'])assert.equal(current[role].capabilities.includes(cap),['padmin','reviewer','tadmin'].includes(role));
  const prefix='SYNTHETIC_CONTACT_HTTP_'+randomUUID(),owned=[];let baseline=null,baselineOriginal=null,baselineTouched=false;
  const facts=row=>Object.fromEntries(['name','roles','mobile','email','wecom_user_id','preferred_priorities','quiet_window','enabled'].map(k=>[k,structuredClone(row[k])]));
  const body={expected_version:0,name:prefix,roles:['TECHNICAL_CONTACT','REPORTER'],mobile:'+8613800000000',email:'Example+tag@example.test',wecom_user_id:'SYNTHETIC_USER_ONLY',preferred_priorities:['P4','P1'],quiet_window:{enabled:true,start:'22:30',end:'07:15'},enabled:true};
  const get=async(id,name='get_contact')=>(await request('padmin','GET',route+'/'+id,'/contacts/{id}',200,name)).value;
  async function create(name,overrides={},role='padmin',key=randomUUID()){const b={...structuredClone(body),name,...overrides};const value=(await request(role,'POST',route,route,200,'create_'+checks.length,b,{key})).value;owned.push(value.id);assert.equal(value.tenant_id,current[role].tenant.id);return {value,body:b,key};}
  async function archive(id,version,name='archive_owned'){return (await request('padmin','POST',route+'/'+id+'/archive','/contacts/{id}/archive',200,name,{expected_version:version})).value;}
  async function cleanup(){
    if(baselineTouched&&baseline){const now=await get(baseline.id,'baseline_cleanup_read');assert.equal(now.name,baselineOriginal.name);assert.equal(now.created_by,baselineOriginal.created_by);if(now.archived)await request('padmin','POST',route+'/'+now.id+'/restore','/contacts/{id}/restore',200,'restore_only_own_synthetic_baseline',{expected_version:now.version,...facts(baselineOriginal)});else assert.equal(now.enabled,true);baselineTouched=false;}
    for(const id of owned){const now=await get(id,'cleanup_read_'+checks.length);assert.ok(now.name.startsWith(prefix));if(!now.archived)await archive(id,now.version,'cleanup_archive_'+checks.length);}
  }
  try{
    const ready=(await request('padmin','GET','/health/ready','/health/ready',200,'actual_contact_schema_ready')).value;assert.equal(ready.customer_contacts.schema_access_ready,true);assert.equal(ready.customer_contacts.notification_dispatched,false);assert.equal(ready.photos.enabled,false);
    const first=await create(prefix),one=first.value;assert.deepEqual(one.roles,['REPORTER','TECHNICAL_CONTACT']);assert.deepEqual(one.preferred_priorities,['P1','P4']);assert.equal(one.mobile,'+8613800000000');assert.equal(one.email,'Example+tag@example.test');assert.equal(one.channel_verification,'NOT_VERIFIED');assert.equal(one.identity_binding_created,false);assert.equal(one.notification_dispatched,false);assert.equal(one.delivery_eligible,null);
    const reordered={...first.body,roles:[...first.body.roles].reverse(),preferred_priorities:[...first.body.preferred_priorities].reverse()};assert.deepEqual((await request('padmin','POST',route,route,200,'set_order_original_create_receipt',reordered,{key:first.key})).value,one);
    await request('padmin','POST',route,route,409,'same_key_changed_facts',{...first.body,name:prefix+'_different'},{key:first.key});
    const patch={expected_version:1,...facts(one),mobile:null,email:null,preferred_priorities:[],quiet_window:{enabled:null,start:null,end:null},enabled:false},patchKey=randomUUID();
    const two=(await request('tadmin','PATCH',route+'/'+one.id,'/contacts/{id}',200,'tenant_admin_update',patch,{key:patchKey})).value;assert.equal(two.version,2);assert.equal(two.delivery_eligible,false);assert.deepEqual(two.preferred_priorities,[]);assert.deepEqual(two.quiet_window,{enabled:null,start:null,end:null});
    assert.deepEqual((await request('tadmin','PATCH',route+'/'+one.id,'/contacts/{id}',200,'update_original_receipt',patch,{key:patchKey})).value,two);await request('padmin','PATCH',route+'/'+one.id,'/contacts/{id}',409,'stale_version_rejected',patch);
    assert.deepEqual((await request('padmin','POST',route,route,200,'create_receipt_not_current_projection',first.body,{key:first.key})).value,one);assert.equal((await get(one.id,'current_version_after_original_receipt')).version,2);
    const archiveKey=randomUUID(),archived=(await request('padmin','POST',route+'/'+one.id+'/archive','/contacts/{id}/archive',200,'archive_preserves_history',{expected_version:2},{key:archiveKey})).value;assert.equal(archived.version,3);assert.equal(archived.archived,true);assert.equal(archived.enabled,false);
    await request('padmin','PATCH',route+'/'+one.id,'/contacts/{id}',409,'archived_requires_explicit_restore',{...patch,expected_version:3});
    const restored=(await request('padmin','POST',route+'/'+one.id+'/restore','/contacts/{id}/restore',200,'explicit_full_restore',{expected_version:3,...facts(one)})).value;assert.equal(restored.version,4);assert.equal(restored.archived,false);
    assert.deepEqual((await request('padmin','POST',route+'/'+one.id+'/archive','/contacts/{id}/archive',200,'archive_receipt_not_reapplied',{expected_version:2},{key:archiveKey})).value,archived);assert.equal((await get(one.id,'current_still_restored')).version,4);
    await request('padmin','POST',route+'/'+one.id+'/restore','/contacts/{id}/restore',409,'cannot_restore_current',{expected_version:4,...facts(one)});
    const history=(await request('padmin','GET',route+'/'+one.id+'/history','/contacts/{id}/history',200,'immutable_full_history')).value;assert.deepEqual(history.items.map(x=>x.version),[4,3,2,1]);assert.deepEqual(history.items.map(x=>x.action),['RESTORE','ARCHIVE','UPDATE','CREATE']);
    assert.deepEqual((await request('padmin','GET',route+'/'+one.id+'/history?before_version=3','/contacts/{id}/history',200,'history_version_cursor')).value.items.map(x=>x.version),[2,1]);
    await request('reviewer','GET',route+'/'+one.id,'/contacts/{id}',200,'second_admin_read');await request('tadmin','GET',route+'/'+one.id,'/contacts/{id}',200,'tenant_admin_read');
    await create(prefix+'_tadmin',{},'tadmin');
    for(const role of ['msp','viewerA','viewerB']){await request(role,'GET',route,route,403,'read_denied_'+role);await request(role,'POST',route,route,403,'write_denied_'+role,body);}
    await request(null,'GET',route,route,401,'anonymous_read');await request('padmin','POST',route,route,403,'csrf_required',body,{noCsrf:true});await request('padmin','POST',route,route,400,'idempotency_required',body,{noKey:true});
    for(const patch of [{expected_version:1},{roles:[]},{roles:['REPORTER','REPORTER']},{roles:[null]},{preferred_priorities:['P1','P1']},{preferred_priorities:['P5']},{enabled:null},{mobile:'13800000000'},{email:'broken'},{tenant_id:current.viewerB.tenant.id},{channel_verification:'VERIFIED'},{notification_dispatched:true},{quiet_window:{enabled:true,start:'12:00',end:'12:00'}},{quiet_window:{enabled:false,start:'12:00',end:null}},{quiet_window:{enabled:true,start:'24:00',end:'07:00'}}])await request('padmin','POST',route,route,400,'invalid_field_'+checks.length,{...body,...patch});
    for(const field of ['email','enabled','quiet_window','preferred_priorities']){const b=structuredClone(body);delete b[field];await request('padmin','POST',route,route,400,'missing_'+field,b);}
    for(const raw of ['{} {}','{"enabled":true,"enabled":false}','{"quiet_window":{"enabled":true,"enabled":false}}','[[[[1]]]]',' '.repeat(16385)])await request('padmin','POST',route,route,400,'bounded_json_'+checks.length,undefined,{raw});
    await request('padmin','POST',route,route,415,'wrong_media_type',body,{contentType:'text/plain'});
    for(const query of ['?tenant_id='+current.viewerB.tenant.id,'?page_size=10&page_size=15','?view=INVALID','?before_id='+one.id,'?page_size=101'])await request('padmin','GET',route+query,route,400,'invalid_query_'+checks.length);
    await request('padmin','POST',route+'?tenant_id='+current.viewerB.tenant.id,route,400,'write_query_scope_override',body);
    await request('padmin','PATCH',route+'/'+one.id,'/contacts/{id}',400,'version_cannot_increment_beyond_safe_integer',{...patch,expected_version:9007199254740991});
    await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_authorized_tenant',{tenant_id:current.viewerB.tenant.id});try{await request('padmin','GET',route+'/'+one.id,'/contacts/{id}',404,'foreign_contact404');await request('padmin','PATCH',route+'/'+one.id,'/contacts/{id}',404,'foreign_contact_write404',{...patch,expected_version:4});}finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_original_tenant',{tenant_id:current.padmin.tenant.id});}
    const pagePrefix=prefix+'_PAGE_';for(let i=0;i<11;i++)await create(pagePrefix+i,{roles:['REPORTER'],preferred_priorities:null,quiet_window:null});
    const page=(await request('padmin','GET',route+'?page_size=10&name_query='+encodeURIComponent(pagePrefix),route,200,'first_10_of_11')).value;assert.equal(page.items.length,10);assert.equal(page.has_more,true);
    const next=(await request('padmin','GET',route+'?page_size=10&name_query='+encodeURIComponent(pagePrefix)+'&before_created_at='+encodeURIComponent(page.next_before_created_at)+'&before_id='+page.next_before_id,route,200,'last_1_of_11')).value;assert.equal(next.items.length,1);assert.equal(new Set([...page.items,...next.items].map(x=>x.id)).size,11);
    const outcomes=await Promise.all([0,1].map(i=>request('padmin','PATCH',route+'/'+one.id,'/contacts/{id}',[200,409],'same_version_concurrent_'+i,{expected_version:4,...facts(restored),name:prefix+'_CAS_'+i})));assert.deepEqual(outcomes.map(x=>x.status).sort(),[200,409]);assert.equal((await get(one.id,'same_version_only_one_revision')).version,5);
    const candidates=(await request('padmin','GET',route+'?page_size=100&view=CURRENT',route,200,'verify_only_owned_baseline_decision')).value;assert.equal(candidates.has_more,false);const decisions=candidates.items.filter(x=>x.enabled&&x.roles.includes('DECISION_MAKER'));assert.equal(decisions.length,1);baseline=decisions[0];assert.equal(baseline.name,'SYNTHETIC_CONTACT_REPOSITORY_BASELINE');assert.equal(baseline.created_by,current.padmin.user.id);baselineOriginal=structuredClone(baseline);
    await request('padmin','POST',route+'/'+baseline.id+'/archive','/contacts/{id}/archive',409,'last_enabled_decision_archive_denied',{expected_version:baseline.version});await request('padmin','PATCH',route+'/'+baseline.id,'/contacts/{id}',409,'last_enabled_decision_disable_denied',{expected_version:baseline.version,...facts(baseline),enabled:false});
    const second=(await create(prefix+'_DECISION',{roles:['DECISION_MAKER'],preferred_priorities:null,quiet_window:null})).value;baselineTouched=true;
    const removed=await Promise.all([baseline,second].map((x,i)=>request('padmin','POST',route+'/'+x.id+'/archive','/contacts/{id}/archive',[200,409],'concurrent_last_decision_'+i,{expected_version:x.version})));assert.deepEqual(removed.map(x=>x.status).sort(),[200,409]);const after=(await request('padmin','GET',route+'?page_size=100&view=CURRENT',route,200,'one_decision_remains_after_race')).value;assert.equal(after.items.filter(x=>x.enabled&&x.roles.includes('DECISION_MAKER')).length,1);
    await cleanup();
    const catalog=(await request('padmin','GET','/authorization/capability-catalog','/authorization/capability-catalog',200,'current_26_capabilities')).value;assert.equal(catalog.entry_count,26);for(const id of ['contacts:read','contacts:write'])assert.deepEqual(catalog.entries.find(e=>e.id===id).declared_by_role,{viewer:false,tadmin:true,msp:false,padmin:true});assert.deepEqual(catalog.dimensions.find(d=>d.id==='MEMBER_ADMIN').capability_ids,['contacts:read','contacts:write']);
    const canonical=['mop-capability-declarations-v1','viewer|tadmin|msp|padmin',...catalog.entries.map(e=>e.id+'|'+e.dimension_ids.join(',')+'|'+catalog.roles.map(r=>e.declared_by_role[r.id]?'1':'0').join(''))].join('\n')+'\n';assert.equal(catalog.declaration_hash,createHash('sha256').update(canonical).digest('hex'));
    const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_before_readonly')).value.snapshot_sequence;await get(one.id,'readonly_archived_detail');await request('padmin','GET',route+'?view=ARCHIVED&name_query='+encodeURIComponent(prefix),route,200,'readonly_archived_list');await request('padmin','GET',route+'/'+one.id+'/history','/contacts/{id}/history',200,'readonly_history');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_after_readonly')).value.snapshot_sequence,tail);
    for(const role of Object.keys(sessions)){const after=(await request(role,'GET','/session','/session',200,'unchanged_membership_'+role)).value;assert.deepEqual(after.capabilities,current[role].capabilities);assert.equal(after.permission_version,current[role].permission_version);}
    writeFileSync(catalogFile,JSON.stringify(catalog,null,2)+'\n',{flag:'wx'});
    const evidence={verified_at:new Date().toISOString(),environment,environment_instance:environment==='niko-local-test'?'rebuilt-20261003-after-executor-loss':'designated-server',api_version:'0.30.0',http_requests:checks.length,checks,synthetic_contacts_created:owned.length,own_created_contacts_archived_with_history:true,owned_synthetic_decision_baseline_restored:true,permanent_deletes:0,new_identity_bindings_or_accounts:0,concurrent_version_outcomes:[200,409],concurrent_last_decision_outcomes:[200,409],last_decision_count_after_race:1,original_receipts_and_current_state_distinguished:true,readonly_audit_tail_unchanged:true,catalog_entries:26,catalog_declaration_hash:catalog.declaration_hash,existing_role_bindings_changed:false,notification_dispatched:false,channel_delivery_verified:false,browser_dom_tested:false,storage_integration_tested:false,credentials_output:false,contract_done:false};writeFileSync(evidenceFile,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:checks.length,synthetic_contacts:owned.length,catalog_entries:26,concurrent_outcomes:[200,409]}));
  }catch(error){const failedStage=stage;try{await cleanup();}catch{stage=failedStage+'; owned sample cleanup incomplete';throw error;}stage=failedStage;throw error;}
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
