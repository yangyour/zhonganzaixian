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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:asset-query-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:asset-query-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/assets/manual-metadata',current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  const prefix='MANUAL-QUERY-'+randomUUID(),assets=[];
  for(let i=0;i<12;i++)assets.push((await request('msp','POST','/assets','/assets',200,'synthetic_asset_'+i,{name:prefix+'-'+i,type:'router',management_ip:'2001:db8:'+randomUUID().slice(0,4)+'::'+(i+1).toString(16),site_id:'site-a-main'})).value);
  const first=(await request('msp','GET',route+'?name='+prefix+'&page_size=10',route,200,'first_filtered_page')).value;assert.equal(first.items.length,10);assert.equal(first.has_more,true);assert.equal(first.pagination_consistency,'CURRENT_REQUEST_SNAPSHOT_ONLY');assert.ok(first.items.every(x=>x.asset.source==='manual'&&x.asset.tenant_id===current.msp.tenant.id));
  const query=new URLSearchParams({name:prefix,page_size:'10',before_created_at:first.next_before_created_at,before_id:first.next_before_id});const second=(await request('padmin','GET',route+'?'+query,route,200,'second_filtered_page')).value;assert.equal(second.items.length,2);assert.equal(second.has_more,false);assert.equal(new Set([...first.items,...second.items].map(x=>x.asset.id)).size,12);
  const target=assets[0],facts={name:target.name,type:'switch',site_id:target.site_id,brand:'Literal%_Brand',model:'SyntheticModel',serial_number:'SYN-0001',location:'虚构机柜',owner_id:current.msp.user.id,business_description:'未核验业务描述',purchase_date:'2024-02-29',warranty_expiry:'2030-01-01',overdue_service_date:null,collection_method_note:'未配置协议',note:'explicit query note'};
  await request('msp','PATCH','/assets/'+target.id+'/manual-metadata','/assets/{id}/manual-metadata',200,'save_query_target',{expected_version:1,...facts});
  const allFields=new URLSearchParams(Object.fromEntries(Object.entries(facts).filter(([,v])=>v!==null)));allFields.set('unknown_fields','overdue_service_date');const one=(await request('msp','GET',route+'?'+allFields,route,200,'all_fields_conjunction')).value;assert.deepEqual(one.items.map(x=>x.asset.id),[target.id]);assert.equal(one.items[0].metadata.owner_id,current.msp.user.id);
  for(const [field,value] of Object.entries(facts)){if(value===null)continue;const q=new URLSearchParams({name:prefix,[field]:value});const matches=(await request('msp','GET',route+'?'+q,route,200,'field_'+field)).value;assert.ok(matches.items.some(x=>x.asset.id===target.id));}
  const literal=(await request('msp','GET',route+'?'+new URLSearchParams({name:prefix,brand:'%_bRaNd'}),route,200,'literal_percent_underscore_casefold')).value;assert.deepEqual(literal.items.map(x=>x.asset.id),[target.id]);
  assert.equal((await request('msp','GET',route+'?'+new URLSearchParams({name:prefix,brand:"x' OR '1'='1"}),route,200,'parameterized_injection_text')).value.items.length,0);
  const unknown=(await request('msp','GET',route+'?'+new URLSearchParams({name:prefix,unknown_fields:'brand,owner_id,note'}),route,200,'explicit_unknown_not_blank_or_default')).value;assert.equal(unknown.items.length,11);assert.ok(unknown.items.every(x=>x.metadata===null));
  await request('msp','PATCH','/assets/'+target.id+'/manual-metadata','/assets/{id}/manual-metadata',200,'change_filter_membership',{expected_version:2,...facts,brand:'NewBrand',overdue_service_date:'2026-10-02'});
  assert.equal((await request('msp','GET',route+'?'+allFields,route,200,'refresh_old_filter_sees_no_match')).value.items.length,0);const newer=(await request('msp','GET',route+'?'+new URLSearchParams({name:prefix,brand:'NewBrand',overdue_service_date:'2026-10-02'}),route,200,'refresh_new_filter_sees_updated_metadata')).value;assert.deepEqual(newer.items.map(x=>x.asset.id),[target.id]);assert.equal(newer.items[0].asset.version,3);
  assert.equal((await request('msp','GET',route+'?site_id=site-b-main',route,200,'foreign_site_has_no_matches')).value.items.length,0);assert.equal((await request('msp','GET',route+'?owner_id='+current.viewerB.user.id,route,200,'foreign_owner_has_no_matches')).value.items.length,0);
  for(const role of ['viewerA','viewerB','tadmin'])await request(role,'GET',route,route,403,role+'_query_denied');await request(null,'GET',route,route,401,'anonymous_query_denied');
  const invalid=['?tenant_id='+current.viewerB.tenant.id,'?name=a&name=b','?page_size=1000','?before_id='+target.id,'?before_created_at=2026-10-02T00:00:00Z','?warranty_status=expired','?unknown_fields=name','?unknown_fields=brand,brand','?unknown_fields=brand&brand=known','?purchase_date=2026-02-30','?type=Router','?brand='+encodeURIComponent('😀'.repeat(101)),'?note='+'a'.repeat(4100)];for(const q of invalid)await request('msp','GET',route+q,route,400,'invalid_query_'+checks.length);
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'query_audit_before')).value.snapshot_sequence;await request('msp','GET',route+'?name='+prefix,route,200,'readonly_query');assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'query_audit_after')).value.snapshot_sequence,tail);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,synthetic_manual_assets:12,page_sizes:[10,2],all_maintenance_fields_queryable:true,filter_semantics:'explicit AND/literal text/exact type-site-owner-dates/explicit null',pagination_consistency:'CURRENT_REQUEST_SNAPSHOT_ONLY',successful_queries_did_not_append_audit:true,credentials_output:false,limitations:['Internal manual-source appended view only; original fixed12 columns unchanged','No warranty state inference or customer Q09 permission expansion','No frozen export or cross-request snapshot guarantee','Browser query controls not tested']};writeFileSync(new URL('docs/evidence/niko-asset-query-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,credentials_output:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
