import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const contract=JSON.parse(fs.readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'));
test('attachment public contract never accepts scanner verdicts',()=>{
 const action=contract.paths['/files/{id}/security/actions'].post;
 const schema=action.requestBody.content['application/json'].schema;
 assert.equal(schema.additionalProperties,false);
 assert.deepEqual(schema.required,['action','expected_version']);
 assert.deepEqual(schema.properties.action.enum,['REQUEST_SCAN','CANCEL_SCAN','RELEASE','QUARANTINE']);
 assert.equal(schema.properties.expected_version.maximum,9007199254740989);
 assert.ok(action.parameters.some(p=>p.name==='Idempotency-Key'&&p.required));
 assert.ok(action.parameters.some(p=>p.name==='X-CSRF-Token'&&p.required));
});
test('attachment snapshot separates clean evidence from explicit release',()=>{
 const schema=contract.components.schemas.AttachmentSecuritySnapshot;
 assert.equal(schema.additionalProperties,false);
 for(const field of ['file_id','tenant_id','version','scan_request_id','content_sha256','valid_until','content_allowed','release_allowed'])assert.ok(schema.required.includes(field));
 assert.ok(schema.properties.state.enum.includes('CLEAN'));assert.ok(schema.properties.state.enum.includes('RELEASED'));
 assert.ok(schema['x-mop-invariants'].some(x=>x.includes('CLEAN is not RELEASED')));
});
