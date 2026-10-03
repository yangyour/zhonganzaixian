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
  const evidenceFile=new URL(environment==='designated-server'?'docs/evidence/local-customer-contact-import-preview-http-20261003.json':'docs/evidence/niko-customer-contact-import-preview-http-20261003.json',root);
  const catalogFile=new URL(environment==='designated-server'?'.local/designated-test/contact-preview-api-catalog-snapshot.json':'.local/niko-test/contact-preview-api-catalog-snapshot.json',root);
  assert.equal(existsSync(evidenceFile)||existsSync(catalogFile),false,'Existing evidence must be preserved');
  const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));assert.equal(contract.info.version,'0.31.0');
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:contact-preview-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:contact-preview-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/contacts/import-preview',current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  const row={name:'SYNTHETIC_PREVIEW_ONLY',roles:['REPORTER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:null,quiet_window:null,enabled:true};
  const body={rows:[row]};
  async function post(name,b=body,expected=200,options={}){return (await request('padmin','POST',route,route,expected,name,b,options)).value;}
  function checkPreview(value,valid,invalid,identical){assert.equal(value.valid_rows,valid);assert.equal(value.invalid_rows,invalid);assert.equal(value.identical_rows,identical);assert.equal(value.rows.length,valid+invalid);assert.deepEqual(value.rows.map(r=>r.row_number),value.rows.map((_,i)=>i+1));for(const key of ['can_import','writes_performed','notification_dispatched','preview_saved','job_created'])assert.equal(value[key],false);assert.equal(value.existing_contact_check,'NOT_RUN');assert.equal(value.last_decision_maker_check,'NOT_RUN');for(const r of value.rows){if(r.status==='INVALID'){assert.equal(r.contact,null);assert.equal(r.identical_to_row,null);assert.equal(r.code,'VALIDATION_ERROR');}else{assert.equal(r.contact.source,'EXPLICIT_INPUT');assert.equal(r.contact.record_source,'EXPLICIT_NEW_CONTACT_INPUT');assert.equal(r.contact.channel_verification,'NOT_VERIFIED');assert.equal(r.contact.identity_binding_created,false);assert.equal(r.contact.notification_dispatched,false);assert.equal(r.contact.delivery_eligible,r.contact.enabled?null:false);assert.ok(!('id' in r.contact));}}}
  const first=await post('one_format_valid_no_write');checkPreview(first,1,0,0);assert.equal(first.tenant_id,current.padmin.tenant.id);
  for(const role of ['reviewer','tadmin'])checkPreview((await request(role,'POST',route,route,200,'administrator_'+role,body)).value,1,0,0);
  for(const role of ['msp','viewerA','viewerB'])await request(role,'POST',route,route,403,'denied_'+role,body);
  await request(null,'POST',route,route,[401,403],'anonymous_denied',body);
  await request('padmin','POST',route,route,403,'csrf_required',body,{noCsrf:true});
  const badRows=[null,{},[],true,7,{...row,enabled:null},{...row,enabled:'true'},{...row,roles:['REPORTER','REPORTER']},{...row,roles:[null]},{...row,roles:['padmin']},{...row,roles:[]},{...row,preferred_priorities:['P1','P1']},{...row,preferred_priorities:['P5']},{...row,mobile:'13800000000'},{...row,email:'broken'},{...row,name:'\n'},{...row,tenant_id:current.viewerB.tenant.id},{...row,id:randomUUID()},{...row,expected_version:0},{...row,contact_verification:'VERIFIED'}];
  const invalid=await post('bad_rows_keep_positions_and_redact',{rows:[row,...badRows,row]});checkPreview(invalid,2,badRows.length,1);assert.equal(invalid.rows.at(-1).identical_to_row,1);
  const rows=[{...row,preferred_priorities:null,quiet_window:null},{...row,preferred_priorities:[],quiet_window:null},{...row,quiet_window:{enabled:null,start:null,end:null}},{...row,quiet_window:{enabled:false,start:null,end:null}},{...row,quiet_window:{enabled:true,start:'22:00',end:'07:00'}},{...row,enabled:false},{...row,mobile:'+8613800000000',email:'Example+tag@example.test',wecom_user_id:'SYNTHETIC_USER',name:'  显式名称🙂  '}];
  const meanings=await post('unknown_empty_quiet_disabled_positive_channels',{rows});checkPreview(meanings,7,0,0);assert.equal(meanings.rows[6].contact.name,rows[6].name);assert.equal(meanings.rows[0].contact.preferred_priorities,null);assert.deepEqual(meanings.rows[1].contact.preferred_priorities,[]);
  const a={...row,roles:['REPORTER','DECISION_MAKER'],preferred_priorities:['P1','P2']},b={...row,roles:['DECISION_MAKER','REPORTER'],preferred_priorities:['P2','P1']};checkPreview(await post('equal_sets_warn_only_no_skip',{rows:[a,b,a,row]}),4,0,2);
  checkPreview(await post('maximum_100_rows',{rows:Array(100).fill(row)}),100,0,99);
  for(const quiet_window of [{enabled:true,start:'12:00',end:'12:00'},{enabled:true,start:'7:00',end:'08:00'},{enabled:true,start:'24:00',end:'08:00'},{enabled:null,start:'12:00',end:null},{enabled:false,start:'12:00',end:null}])checkPreview(await post('bad_quiet_'+checks.length,{rows:[{...row,quiet_window}]}),0,1,0);
  for(const b of [{},{rows:[]},{rows:null},{rows:Array(101).fill(row)},{...body,tenant_id:current.viewerB.tenant.id}])await post('bad_root_'+checks.length,b,400);
  for(const raw of ['{"rows":[],"rows":[]}',JSON.stringify(body)+' {}',JSON.stringify(body).replace('"name":"SYNTHETIC_PREVIEW_ONLY"','"name":"a","name":"b"'),'[[[[[[1]]]]]]',' '.repeat(524289)])await post('bounded_json_'+checks.length,undefined,400,{raw});
  await request('padmin','POST',route+'?tenant_id='+current.viewerB.tenant.id,route,400,'query_scope_override',body);
  await request('padmin','POST','/session/tenant','/session/tenant',200,'switch_authorized_tenant',{tenant_id:current.viewerB.tenant.id});try{const other=await post('actual_other_tenant_format_only');assert.equal(other.tenant_id,current.viewerB.tenant.id);checkPreview(other,1,0,0);}finally{await request('padmin','POST','/session/tenant','/session/tenant',200,'restore_original_tenant',{tenant_id:current.padmin.tenant.id});}
  const catalog=(await request('padmin','GET','/authorization/capability-catalog','/authorization/capability-catalog',200,'unchanged_catalog')).value;assert.equal(catalog.entry_count,26);
  const canonical=['mop-capability-declarations-v1','viewer|tadmin|msp|padmin',...catalog.entries.map(e=>e.id+'|'+e.dimension_ids.join(',')+'|'+catalog.roles.map(r=>e.declared_by_role[r.id]?'1':'0').join(''))].join('\n')+'\n';assert.equal(catalog.declaration_hash,createHash('sha256').update(canonical).digest('hex'));
  async function contactsDigest(label){const items=[];let cursor='';for(let page=0;page<100;page++){const d=(await request('padmin','GET','/contacts?view=ALL&page_size=100'+cursor,'/contacts',200,label+'_'+page)).value;items.push(...d.items);if(!d.has_more)return {count:items.length,sha256:createHash('sha256').update(JSON.stringify(items)).digest('hex')};cursor='&before_created_at='+encodeURIComponent(d.next_before_created_at)+'&before_id='+d.next_before_id;}throw Error('Contact snapshot pagination exceeded safety bound');}
  const before=await contactsDigest('contacts_before');const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_before')).value.snapshot_sequence;
  for(let i=0;i<3;i++)checkPreview(await post('readonly_repeat_'+i),1,0,0);
  assert.deepEqual(await contactsDigest('contacts_after'),before);assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'audit_after')).value.snapshot_sequence,tail);
  for(const role of Object.keys(sessions)){const after=(await request(role,'GET','/session','/session',200,'unchanged_binding_'+role)).value;assert.deepEqual(after.capabilities,current[role].capabilities);assert.equal(after.permission_version,current[role].permission_version);}
  writeFileSync(catalogFile,JSON.stringify(catalog,null,2)+'\n',{flag:'wx'});
  const evidence={verified_at:new Date().toISOString(),environment,environment_instance:environment==='niko-local-test'?'rebuilt-20261003-after-executor-loss':'designated-server',api_version:'0.31.0',http_requests:checks.length,checks,preview_only:true,business_writes:0,synthetic_contacts_created:0,readonly_success_audit_tail_unchanged:true,existing_contacts_snapshot_unchanged:before,catalog_entries:26,catalog_declaration_hash:catalog.declaration_hash,existing_member_bindings_changed:false,authorized_tenant_switches:2,browser_dom_tested:false,storage_integration_tested:false,import_job_created:false,can_import:false,credentials_output:false,notification_delivery:'NOT_RUN',contract_done:false};writeFileSync(evidenceFile,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:checks.length,business_writes:0,contacts_snapshot_count:before.count}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
