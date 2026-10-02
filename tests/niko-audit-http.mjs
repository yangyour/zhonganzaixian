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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:audit-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:audit-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const records='/audit/records',integrity='/audit/integrity';const current={};
  for(const role of Object.keys(sessions)){current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;assert.equal(current[role].capabilities.includes('audit:read'),['padmin','reviewer'].includes(role));}
  const prefix='AUDIT-HTTP-'+randomUUID(),created=[];
  async function asset(i){const result=await request('padmin','POST','/assets','/assets',200,'synthetic_asset_'+i,{name:prefix+'-'+i,type:'switch',management_ip:`2001:db8:${randomUUID().slice(0,4)}::${i+1}`,site_id:'site-a-main'});created.push(result.value);return result.value;}
  for(let i=0;i<12;i++)await asset(i);
  const query=new URLSearchParams({action:'asset.create_draft',actor_id:current.padmin.user.id,occurred_from:created[0].created_at,page_size:'10'});
  const first=(await request('padmin','GET',records+'?'+query,records,200,'filtered_first_page')).value;assert.equal(first.items.length,10);assert.equal(first.has_more,true);assert.equal(first.externally_anchored,false);assert.ok(first.items.every(x=>created.some(asset=>asset.id===x.resource_id)));const snapshot=first.snapshot_sequence;
  await asset(12);
  const nextQuery=new URLSearchParams(query);nextQuery.set('snapshot_sequence',String(snapshot));nextQuery.set('before_sequence',String(first.next_before_sequence));
  const second=(await request('padmin','GET',records+'?'+nextQuery,records,200,'filtered_stable_second_page')).value;assert.equal(second.snapshot_sequence,snapshot);assert.equal(second.items.length,2);assert.equal(second.has_more,false);const ids=new Set([...first.items,...second.items].map(x=>x.resource_id));assert.equal(ids.size,12);assert.equal(ids.has(created[12].id),false);
  const refreshedQuery=new URLSearchParams(query);refreshedQuery.set('page_size','15');const refreshed=(await request('padmin','GET',records+'?'+refreshedQuery,records,200,'new_first_page_sees_append')).value;assert.equal(refreshed.items.length,13);assert.ok(refreshed.snapshot_sequence>snapshot);
  const target=first.items[0];const exact=new URLSearchParams({actor_id:target.actor_id,action:target.action,result:target.result,resource_type:target.resource_type,resource_id:target.resource_id,trace_id:target.trace_id,occurred_from:target.occurred_at,occurred_before:new Date(Date.parse(target.occurred_at)+1).toISOString()});
  const match=(await request('padmin','GET',records+'?'+exact,records,200,'all_exact_filters')).value;assert.equal(match.items.length,1);assert.equal(match.items[0].id,target.id);
  exact.set('occurred_from',new Date(Date.parse(target.occurred_at)-1000).toISOString());exact.set('occurred_before',target.occurred_at);assert.equal((await request('padmin','GET',records+'?'+exact,records,200,'exclusive_time_end')).value.items.length,0);
  assert.equal((await request('padmin','GET',records+'?action='+encodeURIComponent("x' OR '1'='1"),records,200,'parameterized_injection_text')).value.items.length,0);
  const low=Math.min(...[...first.items,...second.items].map(x=>x.sequence)),high=Math.max(...[...first.items,...second.items].map(x=>x.sequence));
  const proof=(await request('padmin','GET',integrity+`?from_sequence=${low}&to_sequence=${high}`,integrity,200,'local_interval_proof')).value;assert.equal(proof.valid,true);assert.equal(proof.verified_records,high-low+1);assert.equal(proof.externally_anchored,false);assert.equal(proof.whole_snapshot_chain_checked,false);
  const beginning=(await request('reviewer','GET',integrity,integrity,200,'default_bounded_proof')).value;assert.equal(beginning.valid,true);assert.ok(beginning.verified_records<=1000);assert.equal(beginning.whole_snapshot_chain_checked,false);assert.equal(beginning.has_more,true);
  const a=(await request('padmin','GET',records+'?page_size=10',records,200,'read_snapshot_before')).value;await request('padmin','GET',integrity+`?from_sequence=${low}&to_sequence=${high}`,integrity,200,'read_only_verification');const b=(await request('padmin','GET',records+'?page_size=10',records,200,'read_snapshot_after')).value;assert.equal(a.snapshot_sequence,b.snapshot_sequence);
  await request('padmin','GET',integrity+'?from_sequence='+String(b.snapshot_sequence+1),integrity,404,'future_range_not_invented');
  for(const role of ['msp','tadmin','viewerA','viewerB']){await request(role,'GET',records,records,403,role+'_records_denied');await request(role,'GET',integrity,integrity,403,role+'_integrity_denied');}
  await request(null,'GET',records,records,401,'anonymous_records');await request(null,'GET',integrity,integrity,401,'anonymous_integrity');
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?namespace=platform','?page_size=10&page_size=15','?before_sequence=10','?snapshot_sequence=9007199254740992','?page_size=1000','?actor_id=unknown','?occurred_from=2026-02-29T00:00:00Z','?occurred_from=2026-10-02T00:00:00','?occurred_from=2026-10-02T00:00:00.1234Z'])await request('padmin','GET',records+q,records,400,'invalid_query_'+checks.length);
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?from_sequence=1&to_sequence=1001','?from_sequence=0','?from_sequence=2&to_sequence=1','?from_sequence=1&from_sequence=2'])await request('padmin','GET',integrity+q,integrity,400,'invalid_integrity_'+checks.length);
  // Send unsupported methods with valid auth and CSRF; these must not expose editing/deletion.
  for(const method of ['POST','PATCH','DELETE']){stage='unsupported_'+method;const response=await fetch('http://127.0.0.1:8080/api/v1'+records,{method,headers:{Cookie:sessions.padmin.cookie,'X-CSRF-Token':sessions.padmin.csrf_token,'Idempotency-Key':randomUUID(),'Content-Type':'application/json'},body:'{}',redirect:'manual',signal:AbortSignal.timeout(15000)});assert.equal(response.status,405);const payload=await response.json();assert.ok(payload.error);checks.push({name:stage,status:405,contract_valid:'no mutation endpoint'});}
  const denialTrace=traces.get('msp_records_denied');const denied=(await request('padmin','GET',records+'?trace_id='+denialTrace+'&result=DENIED',records,200,'denial_remains_auditable')).value;assert.equal(denied.items.length,1);assert.equal(denied.items[0].actor_id,current.msp.user.id);
  const tail=(await request('padmin','GET',records+'?page_size=10',records,200,'final_tenant_only_page')).value;assert.ok(tail.items.every(row=>row.tenant_id===current.padmin.tenant.id));
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,stable_filtered_snapshot_size:12,new_first_page_size:13,interval_records_verified:proof.verified_records,default_range_records:beginning.verified_records,successful_reads_did_not_append_audit:true,externally_anchored:false,stored_audit_edit_delete_repair_actions:0,credentials_output:false,limitations:['No real table tampering; INVALID branches tested with isolated/in-memory copies','No external trusted anchor or WORM guarantee','No browser query UI yet','Full M2-12/M7-5 and production retention still incomplete']};writeFileSync(new URL('docs/evidence/niko-audit-api-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,externally_anchored:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
