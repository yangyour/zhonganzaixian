import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'));
test('Email mutation contract requires CSRF, idempotency and explicit zero enqueue version',()=>{
 for(const path of ['/email/test-messages','/my/notifications/{id}/email']){const action=contract.paths[path].post;assert.equal(action['x-mop-cross-tenant'],'404');assert.ok(action.parameters.find(p=>p.name==='Idempotency-Key'&&p.required));assert.ok(action.parameters.find(p=>p.name==='X-CSRF-Token'&&p.required));assert.ok(action.responses[202]);}
 const input=contract.components.schemas.EmailEnqueueInput;assert.deepEqual(Object.keys(input.properties),['expected_version']);assert.equal(input.properties.expected_version.const,0);
});
test('Email contract cannot label SMTP acceptance as delivered or publish a password',()=>{
 const statuses=contract.components.schemas.EmailDelivery.properties.status.enum;assert.ok(statuses.includes('UNKNOWN'));assert.ok(statuses.includes('ACCEPTED'));assert.ok(!statuses.includes('DELIVERED'));
 const status=contract.components.schemas.EmailStatusResult.properties.data;assert.equal(status.properties.receipt_query_supported.const,false);assert.equal(status.properties.automatic_notifications_enabled.const,false);
 const ajv=new Ajv2020({strict:false});addFormats(ajv);const check=ajv.compile(contract.components.schemas.EmailEnqueueInput);assert.equal(check({expected_version:0,recipient:'other@example.test'}),false);assert.equal(check({expected_version:0}),true);
 assert.ok(!JSON.stringify(status).includes('"password"'));
});
