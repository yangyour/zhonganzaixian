import assert from 'node:assert/strict';
import { readFileSync,writeFileSync } from 'node:fs';
import { resolve,join } from 'node:path';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

// Run against owned niko PG/OIDC/API with incomplete OSS settings. No OSS credentials or network needed.
let stage='configuration';
try {
  assert.equal(process.env.MOP_TEST_ENVIRONMENT_NAME,'niko-local-test');
  assert.ok(process.env.MOP_TEST_SESSION_DIRECTORY);
  const session=JSON.parse(readFileSync(join(resolve(process.env.MOP_TEST_SESSION_DIRECTORY),'qa-padmin.json'),'utf8'));
  const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'));
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);
  const checks=[];
  async function request(path,status,authenticated=true){
    stage=path;
    const response=await fetch('http://127.0.0.1:8080/api/v1'+path,{headers:authenticated?{Cookie:session.cookie}:{},redirect:'manual',signal:AbortSignal.timeout(5000)});
    assert.equal(response.status,status);
    const value=await response.json();
    const schema=contract.paths[path]?.get?.responses[String(status)]?.content?.['application/json']?.schema;
    if(schema)assert.ok(ajv.compile({...schema,components:contract.components})(value));
    checks.push({path,status});return value.data ?? value.error;
  }
  await request('/health/live',200,false);
  const ready=await request('/health/ready',503,false);
  assert.equal(ready.code,'UPSTREAM_UNAVAILABLE');
  const photo=ready.details.find(item=>item.component==='photos').photos;
  assert.equal(photo.enabled,true);assert.equal(photo.ready,false);assert.equal(photo.schema_ready,true);assert.equal(photo.storage_ready,false);
  assert.ok(photo.reasons.includes('PHOTO_STORAGE_UNAVAILABLE'));
  assert.equal((await request('/session',200)).identity_source,'test_oidc');
  const registry=(await request('/configurations',200)).items;
  const pending=registry.find(x=>x.key==='monitor.sample_interval_sec');assert.equal(pending.value,null);assert.equal(pending.effective_value,null);
  await request('/fixture/users',403);
  const evidence={recorded_at:new Date().toISOString(),environment:'niko-local-test',scope:'incomplete OSS provider fails closed while PG/OIDC and configuration reads work',checks,oss_authentication:false,oss_writes:0,minio_fallback:false,browser_ui_tested:false};
  writeFileSync(new URL('../docs/evidence/niko-oss-unready-http-20261002.json',import.meta.url),JSON.stringify(evidence,null,2)+'\n');
  console.log(JSON.stringify({environment:'niko-local-test',http_checks:checks.length,partial_storage_unready:true,other_features_available:true,cloud_storage_tested:false}));
} catch {console.error('niko incomplete OSS verification failed at '+stage+'; private details withheld');process.exitCode=1;}
