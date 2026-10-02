// Real known-role compatibility after fail-closed guards. Never creates malformed roles/accounts.
import assert from 'node:assert/strict';
import { readFileSync,writeFileSync } from 'node:fs';
import Ajv from 'ajv/dist/2020.js';import addFormats from 'ajv-formats';
const root=new URL('../',import.meta.url);let stage='initialization';
try{
 const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));
 const ajv=new Ajv({strict:false});addFormats(ajv);const validators=new Map();const checks=[];
 const expected={padmin:['assets:read','assets:write','configurations:read','configurations:write','audit:read','tickets:read'],msp:['assets:read','assets:write','tickets:read','worklogs:read_own','worklogs:write_own'],tadmin:['assets:read','assets:write','tickets:read','tickets:report'],viewer:['assets:read','tickets:read','tickets:report']};
 const roles={padmin:'padmin',reviewer:'padmin',msp:'msp',tadmin:'tadmin',viewerA:'viewer',viewerB:'viewer'};
 for(const [name,role] of Object.entries(roles)){
  const session=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${name}.json`,root),'utf8'));assert.equal(session.environment,'niko-local-test');let tenant;
  for(const [path,route,status] of [['/session','/session',200],['/assets?page_size=10','/assets',200],['/configurations','/configurations',role==='padmin'?200:403],['/assets/import-template','/assets/import-template',expected[role].includes('assets:write')?200:403]]){
   stage=`${name} ${route}`;const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{headers:{Cookie:session.cookie},redirect:'manual',signal:AbortSignal.timeout(12000)});assert.equal(response.status,status);
   if(route==='/assets/import-template'&&status===200){assert.equal(response.headers.get('x-mop-business-status'),'CONFIG_PENDING');assert.equal((await response.arrayBuffer()).byteLength,4573);}
   else{
    const payload=await response.json();const schema=contract.paths[route].get.responses[status].content['application/json'].schema;const key=JSON.stringify(schema);if(!validators.has(key))validators.set(key,ajv.compile({...schema,components:contract.components}));assert.ok(validators.get(key)(payload));
    if(route==='/session'){assert.equal(payload.data.mode,'remote_test');assert.equal(payload.data.identity_source,'test_oidc');assert.equal(payload.data.user.role,role);assert.deepEqual([...payload.data.capabilities].sort(),[...expected[role]].sort());tenant=payload.data.tenant.id;}
    if(route==='/assets')assert.ok(payload.data.items.every(asset=>asset.tenant_id===tenant));
   }
   checks.push({name:`${name}_${route}`,status});
  }
 }
 stage='anonymous_assets';const anonymous=await fetch('http://127.0.0.1:8080/api/v1/assets?page_size=10',{redirect:'manual',signal:AbortSignal.timeout(12000)});assert.equal(anonymous.status,401);checks.push({name:'anonymous_assets',status:401});
 const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',passed:true,http_checks:checks.length,checks,current_capabilities_verified:true,real_oidc_identities:6,malformed_role_database_mutations:0,credentials_output:false,limitations:['Unknown/null/inactive membership cases verified in isolated guards, not inserted into real identity/database','Not production Q09 permission approval or full M2 acceptance']};
 writeFileSync(new URL('docs/evidence/niko-worklog-role-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:'passed',http_checks:checks.length,current_capabilities_verified:true,malformed_role_database_mutations:0}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
