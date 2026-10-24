import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'));
const ajv=new Ajv2020({strict:false});addFormats(ajv);ajv.addFormat('binary',true);
ajv.addSchema({$id:'urn:mop:notification-cancellation',components:contract.components});
const validate=ajv.getSchema('urn:mop:notification-cancellation#/components/schemas/NotificationRequestCancellationWrite');
test('cancellation accepts only exact current version and bounded explicit reason',()=>{
 assert.equal(validate({expected_version:0,reason:'SYNTHETIC stop planning'}),true);
 for(const body of [{expected_version:-1,reason:'x'},{expected_version:1.5,reason:'x'},
  {expected_version:0,reason:''},{expected_version:0,reason:'x'.repeat(1001)},
  {expected_version:0,reason:'x',tenant_id:'79000000-0000-4000-8000-000000000001'}])assert.equal(validate(body),false);
});
test('cancellation contract retains CSRF, original-command identity, atomic audit and no provider retraction',()=>{
 const path=contract.paths['/notification-routing/requests/{id}/cancel'];
 assert.deepEqual(Object.keys(path),['post']);
 assert.deepEqual(path.post['x-mop-capabilities'],['configurations:write','ticket_notes:read_internal','audit:read']);
 assert.equal(path.post['x-mop-external-dispatch'],'DISABLED');assert.equal(path.post['x-mop-atomic-audit'],true);
 assert.equal(path.post['x-mop-cancellation-scope'],'PRE_DISPATCH_CONTROL_ONLY_NOT_PROVIDER_RETRACTION');
 assert.deepEqual(path.post.parameters.map(value=>value.name),['id','Idempotency-Key','X-CSRF-Token']);
 assert.ok(path.post.responses['409']);assert.ok(path.post.responses['404']);assert.ok(path.post.responses['503']);
});
