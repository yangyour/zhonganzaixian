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
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:asset-reference-http',components:contract.components});
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
    const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile(schema.$ref?{$ref:'urn:mop:asset-reference-http'+schema.$ref}:schema));
    assert.ok(validators.get(key)(payload));assert.equal(payload.trace_id,response.headers.get('x-trace-id'));checks.push({name,status:response.status,contract_valid:true});traces.set(name,payload.trace_id);return {status:response.status,value:payload.data??payload};
  }
  const route='/assets/{id}/manual-metadata/references';const current={};for(const role of Object.keys(sessions))current[role]=(await request(role,'GET','/session','/session',200,'session_'+role)).value;
  const asset=(await request('msp','POST','/assets','/assets',200,'synthetic_reference_asset',{name:'REFERENCE-HTTP-'+randomUUID(),type:'router',management_ip:'2001:db8:'+randomUUID().slice(0,4)+'::1',site_id:'site-a-main'})).value;const path='/assets/'+asset.id+'/manual-metadata/references';
  const tail=(await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'read_tail_before')).value.snapshot_sequence;
  const choices=(await request('msp','GET',path,route,200,'active_same_tenant_references')).value;assert.ok(choices.sites.some(v=>v.id==='site-a-main'));assert.equal(choices.sites.some(v=>v.id==='site-b-main'),false);assert.ok(choices.owners.some(v=>v.id===current.msp.user.id));assert.equal(choices.owners.some(v=>v.id===current.viewerB.user.id),false);assert.equal(choices.default_owner_id,null);assert.equal(choices.reference_validation,'RECHECKED_ON_WRITE');
  const admin=(await request('padmin','GET',path,route,200,'admin_same_reference_scope')).value;assert.deepEqual(admin,choices);
  const owner=(await request('msp','GET',path+'?q='+current.msp.user.id,route,200,'owner_by_literal_id')).value;assert.deepEqual(owner.owners.map(v=>v.id),[current.msp.user.id]);
  assert.deepEqual((await request('msp','GET',path+'?q=SITE-A-MAIN',route,200,'case_insensitive_literal_site')).value.sites.map(v=>v.id),['site-a-main']);
  for(const q of ['%','_',"x' OR '1'='1",'😀'.repeat(100)]){const value=(await request('msp','GET',path+'?q='+encodeURIComponent(q),route,200,'literal_query_'+checks.length)).value;assert.equal(value.query,q);assert.deepEqual(value.sites,[]);assert.deepEqual(value.owners,[]);}
  assert.equal((await request('padmin','GET','/audit/records?page_size=10','/audit/records',200,'read_tail_after')).value.snapshot_sequence,tail);
  for(const role of ['viewerA','viewerB','tadmin'])await request(role,'GET',path,route,403,role+'_directory_denied');await request(null,'GET',path,route,401,'anonymous_denied');
  const foreign=(await request('viewerB','GET','/assets?page_size=10','/assets',200,'foreign_own_asset')).value.items[0];await request('msp','GET','/assets/'+foreign.id+'/manual-metadata/references',route,404,'foreign_asset_hides_directory');
  for(const q of ['?tenant_id='+current.viewerB.tenant.id,'?q=a&q=b','?page_size=1000','?q='+encodeURIComponent('😀'.repeat(101)),'?q='+encodeURIComponent('x\n')])await request('msp','GET',path+q,route,400,'invalid_reference_query_'+checks.length);
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,reference_scope:'CURRENT_TENANT_ACTIVE_REFERENCES',query_semantics:'LITERAL_CASE_INSENSITIVE_SUBSTRING',default_owner_id:null,successful_reads_did_not_append_audit:true,credentials_output:false,limitations:['No browser selector interaction','Results capped at100 per kind with explicit truncated flags; production large directories not created for this check','Write always rechecks selected owner and site','Customer Q09 maintenance remains pending']};writeFileSync(new URL('docs/evidence/niko-asset-reference-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,credentials_output:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
