import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'))
const ajv=new Ajv2020({strict:false});addFormats(ajv);ajv.addFormat('binary',true);ajv.addSchema({$id:'urn:mop:page-intake',components:contract.components})
const valid=(name,value)=>ajv.getSchema(`urn:mop:page-intake#/components/schemas/${name}`)(value)
const id='79000000-0000-4000-8000-000000000001'
const input={expected_version:2,expected_source_definition_sha256:'a'.repeat(64),first_event_id:'40',last_event_id:'99',from_clock:0,through_clock:100,page_size:2}
const item={source_event_id:'44',source_phase:'PROBLEM',record_id:id,raw_outcome:'RAW_STORED',first_received_at:'2026-10-24T00:00:00Z',business_processed:true,event_id:id,event_version:1,ticket_id:null,pending_reason:null}
test('page command requires all seven bounded fields and rejects caller business content',()=>{
 assert.ok(valid('MonitoringMappingPageInput',input))
 for(const bad of [{...input,page_size:11},{...input,page_size:0},{...input,from_clock:-1},{...input,payload:{}},{...input,tenant_id:id},{...input,severity:'DISASTER'}])assert.equal(valid('MonitoringMappingPageInput',bad),false)
 const missing={...input};delete missing.last_event_id;assert.equal(valid('MonitoringMappingPageInput',missing),false)
})
test('page item cannot claim business success from recovery or conflicting raw content',()=>{
 assert.ok(valid('MonitoringMappingPageItem',item))
 for(const bad of [{...item,source_phase:'RECOVERED'},{...item,raw_outcome:'CONFLICT_HELD'},{...item,pending_reason:'SOURCE_CLOCK_AHEAD'},{...item,event_id:null}])assert.equal(valid('MonitoringMappingPageItem',bad),false)
})
test('raw-only pending reasons are coherent with phase and conflict outcome',()=>{
 const pending={...item,business_processed:false,event_id:null,event_version:null,ticket_id:null,pending_reason:'SOURCE_SEVERITY_UNCLASSIFIED'}
 assert.ok(valid('MonitoringMappingPageItem',pending));assert.ok(valid('MonitoringMappingPageItem',{...pending,source_phase:'RECOVERED',pending_reason:'RECOVERY_OBSERVATION_REQUIRED'}));assert.ok(valid('MonitoringMappingPageItem',{...pending,raw_outcome:'CONFLICT_HELD',pending_reason:'SOURCE_KEY_CONFLICT'}))
 assert.equal(valid('MonitoringMappingPageItem',{...pending,pending_reason:'SOURCE_KEY_CONFLICT'}),false)
 assert.equal(valid('MonitoringMappingPageItem',{...pending,source_phase:'RECOVERED'}),false)
})
test('page route documents current authorization and does not claim checkpoint recovery or dispatch',()=>{
 const operation=contract.paths['/monitoring-object-mappings/{id}/page-intakes'].post
 for(const field of ['x-mop-default-enabled','x-mop-global-watermark','x-mop-recovery-confirmed','x-mop-outbound-dispatch'])assert.equal(operation[field],false)
 assert.equal(operation['x-mop-current-membership-recheck'],true);assert.equal(operation['x-mop-current-padmin-required'],true)
 assert.deepEqual(operation.parameters.filter(p=>p.in==='header').map(p=>p.name),['Idempotency-Key','X-CSRF-Token'])
 for(const field of ['complete_window_claimed','global_checkpoint_advanced','recovery_confirmed','dispatch_allowed'])assert.equal(contract.components.schemas.MonitoringMappingPageReceipt.properties[field].const,false)
})
