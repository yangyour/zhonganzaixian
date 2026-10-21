import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
const contract=JSON.parse(fs.readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url)))
for(const kind of ['Email','WeCom'])test(`${kind} target binding is a read with explicit null and existing write preserved`,()=>{
 const path=contract.paths[`/${kind.toLowerCase()}/bindings/{userId}`]
 assert.equal(path.get['x-mop-capability'],'users:read');assert.equal(path.put['x-mop-capability'],'users:write')
 assert.deepEqual(path.get.parameters.map(p=>p.name),['userId']);assert.equal(path.get.requestBody,undefined)
 const result=contract.components.schemas[`Target${kind}BindingResult`].properties.data
 assert.equal(result.additionalProperties,false);assert.equal(result.properties.binding.anyOf[1].type,'null')
 assert.equal(result.properties.source.const,'postgresql');for(const key of ['tenant_id','user_id','binding'])assert.ok(result.required.includes(key))
})
