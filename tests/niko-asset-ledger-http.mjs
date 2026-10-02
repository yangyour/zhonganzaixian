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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:asset-ledger-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:asset-ledger-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const current={};for(const role of Object.keys(sessions)){current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;const page=(await request(role,'GET','/assets?page_size=10','/assets',200,'known_role_scope_'+role)).value;assert.ok(page.items.every(a=>a.tenant_id===current[role].tenant.id));assert.ok(page.items.length<=10);}
  const prefix='LEDGER-HTTP-'+randomUUID(),assets=[];for(let i=0;i<12;i++)assets.push((await request('msp','POST','/assets','/assets',200,'synthetic_asset_'+i,{name:prefix+'-'+i,type:i<10?'router':'switch',management_ip:'2001:db8:'+randomUUID().slice(0,4)+'::AB',site_id:'site-a-main'})).value);
  const firstQuery=new URLSearchParams({q:prefix,page_size:'10'}),first=(await request('msp','GET','/assets?'+firstQuery,'/assets',200,'bounded_first_ten')).value;assert.equal(first.items.length,10);assert.equal(first.has_more,true);assert.ok(first.next_cursor);
  const nextQuery=new URLSearchParams(firstQuery);nextQuery.set('cursor',first.next_cursor);const next=(await request('msp','GET','/assets?'+nextQuery,'/assets',200,'bounded_remaining_two')).value;assert.equal(next.items.length,2);assert.equal(next.has_more,false);assert.deepEqual([...first.items,...next.items].map(a=>a.id),assets.map(a=>a.id));
  const narrow=(await request('viewerA','GET','/assets?'+new URLSearchParams({q:prefix,type:'switch',site_id:'site-a-main',status:'unknown'}),'/assets',200,'conjunctive_filters')).value;assert.deepEqual(narrow.items.map(a=>a.id),assets.slice(10).map(a=>a.id));
  await request('msp','GET','/assets?'+new URLSearchParams({q:prefix+'-other',cursor:first.next_cursor}),'/assets',400,'cursor_wrong_filter');await request('padmin','GET','/assets?'+nextQuery,'/assets',400,'cursor_wrong_actor');await request('viewerB','GET','/assets?'+nextQuery,'/assets',400,'cursor_wrong_tenant');await request('msp','GET','/assets?'+new URLSearchParams({q:prefix,cursor:first.next_cursor+'x'}),'/assets',400,'cursor_tampered');
  const unicodeName='LEDGER-UNICODE-HTTP-'+randomUUID()+'-ÄΟΣİK',unicode=(await request('msp','POST','/assets','/assets',200,'synthetic_unicode_name',{name:unicodeName,type:'router',management_ip:'2001:db8:'+randomUUID().slice(0,4)+'::CD',site_id:'site-a-main'})).value;
  const unicodeResult=(await request('msp','GET','/assets?'+new URLSearchParams({q:unicodeName.toLowerCase()}),'/assets',200,'unicode_root_lowercase_match')).value;assert.deepEqual(unicodeResult.items.map(a=>a.id),[unicode.id]);assert.equal(unicodeResult.items[0].name,unicodeName);
  assert.deepEqual((await request('viewerA','GET','/assets/'+unicode.id.toUpperCase(),'/assets/{id}',200,'canonical_uuid_detail')).value,unicode);
  const ip=(await request('msp','GET','/assets?'+new URLSearchParams({q:assets[0].management_ip}),'/assets',200,'exact_case_ip_substring')).value;assert.ok(ip.items.some(a=>a.id===assets[0].id));assert.equal((await request('msp','GET','/assets?'+new URLSearchParams({q:assets[0].management_ip.toLowerCase()}),'/assets',200,'lowercase_ip_not_rewritten')).value.items.some(a=>a.id===assets[0].id),false);
  assert.equal((await request('msp','GET','/assets?'+new URLSearchParams({q:prefix+"' OR '1'='1"}),'/assets',200,'literal_injection_text')).value.items.length,0);assert.equal((await request('msp','GET','/assets?'+new URLSearchParams({q:prefix+'%_'}),'/assets',200,'percent_underscore_not_wildcards')).value.items.length,0);
  const foreign=(await request('viewerB','GET','/assets?page_size=10','/assets',200,'foreign_assets_own_scope')).value.items[0];await request('msp','GET','/assets/'+foreign.id,'/assets/{id}',404,'foreign_detail_hidden');await request('msp','GET','/assets/not-a-uuid','/assets/{id}',404,'malformed_detail_hidden');assert.equal((await request('msp','GET','/assets?site_id=site-b-main','/assets',200,'foreign_site_filter_empty')).value.items.length,0);
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?page_size=10&page_size=15','?page_size=500','?q='+encodeURIComponent('x\u0000')])await request('msp','GET','/assets'+q,'/assets',400,'invalid_main_query_'+checks.length);
  const facts={expected_version:1,name:unicodeName,type:'router',site_id:unicode.site_id,brand:'ÄBC',model:null,serial_number:null,location:null,owner_id:null,business_description:null,purchase_date:null,warranty_expiry:null,overdue_service_date:null,collection_method_note:null,note:'明确Unicode资料查询验证'};await request('msp','PATCH','/assets/'+unicode.id+'/manual-metadata','/assets/{id}/manual-metadata',200,'unicode_metadata_prepare',facts);
  const metadata=(await request('msp','GET','/assets/manual-metadata?'+new URLSearchParams({name:unicodeName.toLowerCase(),brand:'äbc'}),'/assets/manual-metadata',200,'metadata_unicode_lowercase_match')).value;assert.deepEqual(metadata.items.map(a=>a.asset.id),[unicode.id]);
  const references=(await request('msp','GET','/assets/'+unicode.id+'/manual-metadata/references?q=SITE-A-MAIN','/assets/{id}/manual-metadata/references',200,'reference_search_retains_case_insensitive_scope')).value;assert.deepEqual(references.sites.map(s=>s.id),['site-a-main']);
  const ready=(await request(null,'GET','/health/ready','/health/ready',200,'unicode_dependency_readiness')).value;assert.equal(ready.asset_reads.unicode_search_ready,true);assert.equal(ready.asset_reads.pagination,'BOUNDED_POSTGRESQL_KEYSET');
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_before')).value.snapshot_sequence;await request('viewerA','GET','/assets?'+firstQuery,'/assets',200,'readonly_asset_page');await request('viewerA','GET','/assets/'+assets[0].id,'/assets/{id}',200,'readonly_asset_detail');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'readonly_tail_after')).value.snapshot_sequence,tail);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,main_page_sizes:[10,2],known_roles_current_tenant_only:true,signed_cursor_scope_unchanged:true,unicode_expression:'existing pg_catalog.und-x-icu root lowercase, literal substring, no normalization',database_global_collation_changed:false,query_and_detail_reads_do_not_append_audit:true,credentials_output:false,limitations:['No production 5000-asset benchmark or resource quota acceptance','Cursor is live creation-keyset, not frozen multi-page export','Original server Unicode dependency availability remains unverified','No browser DOM acceptance']};writeFileSync(new URL('docs/evidence/niko-asset-ledger-read-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,credentials_output:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
