import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'));
const ajv=new Ajv2020({strict:false});addFormats(ajv);ajv.addSchema({$id:'urn:mop:notification',components:contract.components});
const valid=(name,value)=>ajv.getSchema(`urn:mop:notification#/components/schemas/${name}`)(value);
const id='11111111-1111-4111-8111-111111111111';
const draft={published:false,resource_pool_id:null,matrix:null,aggregate_seconds:null,precedence:null,retry_budget:null,retry_delay_seconds:null};
test('routing unknown drafts stay nullable but incomplete publication is rejected',()=>{
 assert.equal(valid('NotificationRoutingPolicy',draft),true);
 assert.equal(valid('NotificationRoutingPolicy',{...draft,published:true}),false);
 assert.equal(valid('NotificationRoutingPolicy',{...draft,password:'not-allowed'}),false);
});
test('explicit empty channels and zero retry budget do not become guessed defaults',()=>{
 const value={...draft,published:true,resource_pool_id:id,matrix:{P1:['EMAIL'],P2:[],P3:[],P4:[]},aggregate_seconds:0,precedence:'MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS',retry_budget:0,retry_delay_seconds:60};
 assert.equal(valid('NotificationRoutingPolicy',value),true);
 assert.equal(valid('NotificationRoutingPolicy',{...value,matrix:{...value.matrix,P1:['EMAIL','EMAIL']}}),false);
});
test('owner publication has no caller-controlled receiver or tenant override',()=>{
 const value={expected_version:-1,draft_id:id,draft_version:1,published:true};
 assert.equal(valid('NotificationPreferencePublicationWrite',value),true);
 assert.equal(valid('NotificationPreferencePublicationWrite',{...value,owner_id:id}),false);
 assert.equal(valid('NotificationPreferencePublicationWrite',{...value,tenant_id:id}),false);
});
test('control writes retain CSRF and exact command identity while demo guards remain present',()=>{
 for(const path of ['/notification-routing/policy','/notification-routing/bindings','/my/notification-preferences/publication']){
  const operation=contract.paths[path].post;
  assert.equal(operation['x-mop-external-dispatch'],'DISABLED');
  assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key'&&p.required&&p.schema.format==='uuid'));
  assert.ok(operation.parameters.some(p=>p.name==='X-CSRF-Token'&&p.required));
 }
 assert.ok(contract.paths['/email/bindings/{userId}'].put['x-mop-demo-disabled-scope']);
 assert.ok(contract.paths['/wecom/bindings/{userId}'].put['x-mop-demo-disabled-scope']);
});
