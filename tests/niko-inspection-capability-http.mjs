// Supplemental actual GET verifies the catalog regression fixed during inspection API development.
import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,existsSync} from 'node:fs'
import Ajv from 'ajv/dist/2020.js'
import formats from 'ajv-formats'
const root=new URL('../',import.meta.url),output=new URL('docs/evidence/niko-inspection-capability-http-20261004.json',root)
try {
  assert.equal(existsSync(output),false)
  const auth=JSON.parse(readFileSync(new URL('.local/niko-test/private/sessions/qa-padmin.json',root),'utf8'));assert.equal(auth.environment,'niko-local-test')
  const schema=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));assert.equal(schema.info.version,'0.35.0');const ajv=new Ajv({strict:false});formats(ajv);ajv.addFormat('binary',true);ajv.addSchema({$id:'urn:mop:actual-api',components:schema.components})
  const response=await fetch('http://127.0.0.1:8080/api/v1/authorization/capability-catalog',{headers:{Cookie:auth.cookie},redirect:'error',signal:AbortSignal.timeout(20000)});assert.equal(response.status,200);const value=await response.json();assert.equal(ajv.getSchema('urn:mop:actual-api#/components/schemas/CapabilityCatalogResult')(value),true)
  const rows=value.data.entries.filter(x=>x.id.startsWith('inspection_templates:'));assert.deepEqual(rows.map(x=>x.id),['inspection_templates:read','inspection_templates:write']);for(const row of rows){assert.deepEqual(row.dimension_ids,['TEMPLATES_AUTOMATION']);assert.deepEqual(row.declared_by_role,{viewer:false,tadmin:false,msp:false,padmin:true})}
  assert.equal(value.data.effective_customer_matrix,null);assert.equal(value.data.customer_approval_status,'UNVERIFIED');assert.ok(Object.values(value.data.actions_this_request).every(x=>x===false))
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_contract_version:'0.35.0',http_requests:1,http_methods:['GET'],status:200,schema_validated:true,new_capabilities_classified:2,dimension:'TEMPLATES_AUTOMATION',declared_role:'padmin',customer_approval:'UNVERIFIED',business_writes:0,new_runtime_grants:false,browser_tested:false}
  writeFileSync(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:1,classified:2}))
} catch {console.error(JSON.stringify({status:'FAILED',details:'redacted'}));process.exitCode=1}
