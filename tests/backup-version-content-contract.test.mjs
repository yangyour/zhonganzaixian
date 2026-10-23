import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import Ajv from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'))
const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);ajv.addSchema({$id:'urn:mop:backup-content',components:contract.components})
const validate=ajv.getSchema('urn:mop:backup-content#/components/schemas/BackupVersionDiffResult')
const id=n=>`82000000-0000-4000-8000-${String(n).padStart(12,'0')}`
const result=()=>({data:{tenant_id:id(1),asset_id:id(2),before_version_id:id(3),after_version_id:id(4),status:'IDENTICAL',changed:false,before_lines:1,after_lines:1,hunks:[],redaction:'ALL_TEXT_WITHHELD',noise_policy:'NONE_APPLIED_UNCONFIRMED',change_approved:false,automatic_ticket_allowed:false,actual_recovery_verified:false},trace_id:'synthetic-contract'})
test('sensitive reads require internal roles and independent permissions, never inventory inheritance',()=>{
  const read=contract.paths['/assets/{assetId}/backup-versions/{versionId}/content'].get
  assert.deepEqual(read['x-mop-roles'],['padmin','msp']);assert.match(read['x-mop-independent-authorization'],/VERSION_DOWNLOAD.*VERSION_DECRYPT/)
  assert.ok(read.responses[200].content['application/octet-stream']);assert.equal(read.responses[200].headers['Cache-Control'].schema.const,'no-store')
  assert.deepEqual(read['x-mop-allowed-query-keys'],['format'])
})
test('diff is bounded strict GET computation with access audit and no business mutation',()=>{
  const operation=contract.paths['/assets/{assetId}/backup-versions/diff'].get
  assert.equal(contract.paths['/assets/{assetId}/backup-versions/diff'].post,undefined)
  assert.ok(operation.parameters.some(value=>value.name==='before_version_id'&&value.required))
  assert.equal(operation['x-mop-max-encoded-query-bytes'],2048);assert.equal(operation['x-mop-reject-duplicate-query-keys'],true)
  assert.equal(operation['x-mop-business-writes-performed'],false);assert.equal(operation['x-mop-success-writes-audit-or-receipts'],true)
})
test('comparison schema never accepts text, approval or unknown-as-normal',()=>{
  assert.equal(validate(result()),true,JSON.stringify(validate.errors))
  for(const patch of [{content:'SYNTHETIC'}, {change_approved:true},{actual_recovery_verified:true},{automatic_ticket_allowed:true},{status:'UNAVAILABLE',changed:false}]){
    const value=result();Object.assign(value.data,patch);assert.equal(validate(value),false)
  }
  const unknown=result();Object.assign(unknown.data,{status:'UNAVAILABLE',changed:null,before_lines:null,after_lines:null})
  assert.equal(validate(unknown),true,JSON.stringify(validate.errors))
})
