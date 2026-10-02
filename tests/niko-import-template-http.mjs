// Real authorized binary HTTP plus the actual frontend byte-verification module. Not a browser test.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { fetchAssetImportTemplate } from '../.local/niko-test/template-client.mjs';
const root=new URL('../',import.meta.url);let stage='initialization';
try{
  const spec=JSON.parse(readFileSync(new URL('contracts/asset-import-template.json',root),'utf8'));
  const contract=JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8'));
  const ajv=new Ajv({strict:false});addFormats(ajv);const errorSchema=ajv.compile({...contract.components.schemas.Error,components:contract.components});
  const sessions=Object.fromEntries(['msp','tadmin','padmin','viewerA','viewerB'].map(role=>{const value=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`,root),'utf8'));assert.equal(value.environment,'niko-local-test');return [role,value];}));
  const checks=[];let first;
  for(const role of ['msp','tadmin','padmin','msp']){
    stage='authorized_binary_'+role;
    const transport=async(path,options)=>{
      assert.equal(path,'/api/v1/assets/import-template');assert.equal(options.method,'GET');assert.equal(options.body,undefined);
      const response=await fetch('http://127.0.0.1:8080'+path,{...options,headers:{...options.headers,Cookie:sessions[role].cookie},signal:AbortSignal.timeout(12000)});
      assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'no-store');assert.equal(response.headers.get('x-content-type-options'),'nosniff');assert.ok(response.headers.get('x-trace-id'));return response;
    };
    const file=await fetchAssetImportTemplate({transport});const bytes=new Uint8Array(await file.blob.arrayBuffer());
    assert.equal(bytes.length,spec.size_bytes);assert.equal(createHash('sha256').update(bytes).digest('hex'),spec.sha256);assert.equal(file.filename,spec.filename);assert.equal(file.blob.type,spec.media_type);assert.equal(bytes[0],0x50);assert.equal(bytes[1],0x4b);
    if(first)assert.deepEqual(bytes,first);else first=bytes;
    checks.push({name:'authorized_'+role,status:200,binary_verified:true});
  }
  async function denied(role,path,status,name,method='GET'){
    stage=name;const headers={Accept:'application/json'};
    if(role){headers.Cookie=sessions[role].cookie;headers['X-CSRF-Token']=sessions[role].csrf_token;}
    if(method!=='GET'){headers['Idempotency-Key']=randomUUID();headers['Content-Type']='application/json';}
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{method,headers,body:method==='GET'?undefined:'{}',redirect:'manual',signal:AbortSignal.timeout(12000)});
    assert.equal(response.status,status);assert.match(response.headers.get('content-type'),/application\/json/);assert.equal(response.headers.get('content-disposition'),null);assert.ok(errorSchema(await response.json()));checks.push({name,status,error_contract_valid:true});
  }
  await denied('viewerA','/assets/import-template',403,'viewer_a_forbidden');await denied('viewerB','/assets/import-template',403,'viewer_b_forbidden');await denied(null,'/assets/import-template',401,'anonymous_forbidden');
  for(const query of ['tenant_id=forged','filename=other.xlsx','format=csv'])await denied('msp','/assets/import-template?'+query,400,'query_'+query.split('=')[0]+'_rejected');
  await denied('msp','/assets/import-template',405,'post_does_not_create_source','POST');
  const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:contract.info.version,passed:true,http_checks:checks.length,checks,template:{filename:spec.filename,size_bytes:spec.size_bytes,sha256:spec.sha256,schema_version:spec.schema_version,business_status:'CONFIG_PENDING',data_rows:0},actual_frontend_verifier:true,storage_operations:0,browser_ui_tested:false,credentials_output:false,limitations:['No file upload, precheck or asset import performed','Downloaded empty template does not confirm Q20/Q31','OS/browser save dialog and visual acceptance NOT_RUN']};
  writeFileSync(new URL('docs/evidence/niko-import-template-http-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({status:'passed',http_checks:checks.length,verified_bytes:spec.size_bytes,actual_frontend_verifier:true,browser_ui_tested:false}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;}
