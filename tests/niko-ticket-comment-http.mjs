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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:ticket-comment-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:ticket-comment-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/tickets/{ticketId}/comments',detail=route+'/{id}',current={};
  for(const role of Object.keys(sessions)){current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;assert.ok(current[role].capabilities.includes('ticket_comments:read'));assert.ok(current[role].capabilities.includes('ticket_comments:write'));}
  async function parent(role,label){const accepted=(await request(role,'POST','/tickets','/tickets',202,label+'_register',{title:'COMMENT-HTTP-'+randomUUID(),source:'customer_report',description:'明确合成客户可见评论父登记',category:'网络不通',impact_scope:'individual',contact_name:'虚构联系人',contact_phone:'12345',urgency:'normal'})).value;const op=(await request(role,'GET','/operations/'+accepted.operation_id,'/operations/{id}',200,label+'_operation')).value;return (await request(role,'GET',op.result_ref.replace('/api/v1',''),'/tickets/{id}',200,label+'_parent')).value;}
  const original=await parent('viewerA','own'),second=await parent('viewerA','own_second'),colleague=await parent('tadmin','other_reporter'),foreign=await parent('viewerB','foreign');
  const path='/tickets/'+original.id+'/comments',all=[],body={expected_ticket_version:original.version,text:'  明确发给报障人的说明😀\n第二行\t '};
  const privateText='INTERNAL-ONLY-SYNTHETIC-'+randomUUID();
  await request('msp','POST','/tickets/'+original.id+'/internal-notes','/tickets/{ticketId}/internal-notes',200,'prepare_private_note',{expected_ticket_version:original.version,text:privateText});
  assert.deepEqual((await request('viewerA','GET',path,route,200,'private_note_not_in_public_comments')).value.items,[]);
  const key=randomUUID(),first=(await request('msp','POST',path,route,200,'create_engineer_comment',body,{key})).value;all.push(first);
  assert.equal(first.author_kind,'ENGINEER');assert.equal(first.author_label,'工程师');assert.equal(first.text,body.text);assert.equal(Object.hasOwn(first,'actor_id'),false);assert.equal(Object.hasOwn(first,'actor_name'),false);
  assert.deepEqual((await request('viewerA','GET',path+'/'+first.id,detail,200,'reporter_reads_engineer')).value,first);
  assert.deepEqual((await request('padmin','GET',path+'/'+first.id,detail,200,'internal_reads_same_public_projection')).value,first);
  assert.deepEqual((await request('msp','POST',path,route,200,'same_original_key_replay',body,{key})).value,first);
  await request('msp','POST',path,route,409,'changed_key_conflict',{...body,text:'changed'},{key});
  const reply=(await request('viewerA','POST',path,route,200,'reporter_comment',{...body,text:'报障人明确回复'})).value;all.push(reply);assert.equal(reply.author_kind,'REPORTER');assert.equal(reply.author_label,'报障人');
  const other=(await request('tadmin','POST','/tickets/'+colleague.id+'/comments',route,200,'other_reporter_own_comment',{expected_ticket_version:colleague.version,text:'另一个报障人自己的评论'})).value;
  for(const role of ['tadmin','viewerB']){await request(role,'GET',path,route,404,role+'_cannot_read_others');await request(role,'POST',path,route,404,role+'_cannot_comment_on_others',body);}
  await request('viewerA','GET','/tickets/'+colleague.id+'/comments/'+other.id,detail,404,'same_tenant_other_reporter_hidden');
  await request('padmin','GET','/tickets/'+foreign.id+'/comments',route,404,'internal_foreign_parent_hidden');
  await request('viewerA','GET','/tickets/'+second.id+'/comments/'+first.id,detail,404,'wrong_owned_parent_hidden');
  await request(null,'GET',path,route,401,'anonymous_read');await request('viewerA','POST',path,route,403,'missing_csrf',body,{noCsrf:true});await request('viewerA','POST',path,route,400,'missing_key',body,{noKey:true});
  await request('viewerA','POST',path,route,409,'stale_parent_cas',{...body,expected_ticket_version:original.version+1});
  for(const field of ['tenant_id','actor_id','author_kind','author_label','visibility','state','attachment_ids','itsm_sync_requested'])await request('viewerA','POST',path,route,400,'spoof_'+field,{...body,[field]:'forged'});
  for(const text of ['',null,'\ufeff','x\u0000','😀'.repeat(2001)])await request('viewerA','POST',path,route,400,'invalid_text_'+checks.length,{...body,text});
  for(const raw of ['{"text":"a","text":"b"}','{} {}',' '.repeat(32769)])await request('viewerA','POST',path,route,400,'invalid_json_'+checks.length,undefined,{raw});
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?page_size=10&page_size=15','?page_size=1000','?before_id='+first.id,'?before_created_at=2026-10-02T00:00:00Z'])await request('viewerA','GET',path+q,route,400,'invalid_list_query_'+checks.length);
  for(let i=0;i<10;i++)all.push((await request(i%2?'viewerA':'padmin','POST',path,route,200,'page_prepare_'+i,{...body,text:'分页客户可见评论 '+i})).value);
  const firstPage=(await request('viewerA','GET',path+'?page_size=10',route,200,'first_ten_comments')).value;assert.equal(firstPage.items.length,10);assert.equal(firstPage.has_more,true);
  const next=new URLSearchParams({page_size:'10',before_created_at:firstPage.next_before_created_at,before_id:firstPage.next_before_id});
  const lastPage=(await request('viewerA','GET',path+'?'+next,route,200,'next_two_comments')).value;assert.equal(lastPage.items.length,2);assert.equal(lastPage.has_more,false);assert.deepEqual([...firstPage.items,...lastPage.items].map(c=>c.id).sort(),all.map(c=>c.id).sort());
  const concurrentKey=randomUUID();const concurrent=await Promise.all([1,2].map(i=>request('viewerA','POST',path,route,200,'same_key_concurrent_'+i,{...body,text:'并发明确追加一次'},{key:concurrentKey})));assert.deepEqual(concurrent[0].value,concurrent[1].value);all.push(concurrent[0].value);
  const longest=(await request('viewerA','POST',path,route,200,'maximum_unicode_comment',{...body,text:'😀'.repeat(2000)})).value;assert.equal([...longest.text].length,2000);all.push(longest);
  assert.deepEqual((await request('viewerA','GET','/tickets/'+original.id,'/tickets/{id}',200,'parent_customer_dto_unchanged')).value,original);
  const final=(await request('reviewer','GET',path,route,200,'fourteen_public_comments')).value;assert.equal(final.items.length,14);assert.equal(JSON.stringify(final).includes(privateText),false);assert.ok(final.items.every(c=>c.visibility==='REPORTER_AND_INTERNAL'&&!Object.hasOwn(c,'actor_id')&&!Object.hasOwn(c,'actor_name')&&c.itsm_applied===false&&c.itsm_sync_available===false&&c.itsm_sync_status==='NOT_REQUESTED'));
  const audits=(await request('padmin','GET','/audit/records?action=ticket.comment.create&page_size=100','/audit/records',200,'comment_audit_once')).value.items;assert.equal(audits.filter(a=>all.some(c=>c.id===a.resource_id)).length,14);assert.equal(audits.filter(a=>a.resource_id===first.id&&a.trace_id===traces.get('create_engineer_comment')).length,1);assert.equal(audits.filter(a=>a.resource_id===first.id&&a.trace_id===traces.get('same_original_key_replay')).length,0);
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'comment_schema_readiness')).value;assert.equal(ready.ticket_comments.schema_access_ready,true);assert.equal(ready.ticket_comments.internal_identity_exposed,false);assert.equal(ready.ticket_comments.itsm_applied,false);
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_before')).value.snapshot_sequence;await request('viewerA','GET',path,route,200,'readonly_reporter_list');await request('msp','GET',path+'/'+first.id,detail,200,'readonly_internal_detail');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_after')).value.snapshot_sequence,tail);
  for(const method of ['PATCH','DELETE']){stage='immutable_method_'+method;const response=await fetch('http://127.0.0.1:8080/api/v1'+path+'/'+first.id,{method,headers:{Cookie:sessions.viewerA.cookie,'X-CSRF-Token':sessions.viewerA.csrf_token,'Idempotency-Key':randomUUID(),'Content-Type':'application/json'},body:JSON.stringify({text:'not supported'}),redirect:'manual',signal:AbortSignal.timeout(15000)});assert.equal(response.status,405);assert.ok(ajv.compile({$ref:'urn:mop:ticket-comment-http#/components/schemas/Error'})(await response.json()));checks.push({name:stage,status:405,contract_valid:true,schema:'Error; no mutation endpoint'});}
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,main_parent_comments:14,other_parent_comments:1,main_parent_success_audits:14,concurrent_receipts:2,concurrent_rows:1,customer_scope:'own reporter only',internal_scope:'current authorized tenant only',private_note_excluded:true,structured_internal_author_identity_excluded:true,parent_state_sla_version_and_customer_dto_unchanged:true,successful_reads_did_not_append_audit:true,credentials_output:false,browser_dom_tested:false,limitations:['No UI, comment notifications, attachment or ITSM delivery','No formal workflow activation or customer resolution confirmation','No live user role revocation; guarded-replay repository tests cover guard-before-cache']};
  writeFileSync(new URL('docs/evidence/niko-ticket-comment-api-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_checks:checks.length,main_parent_comments:14}));
}catch{console.error(JSON.stringify({passed:false,stage,details:'redacted'}));process.exitCode=1;}
