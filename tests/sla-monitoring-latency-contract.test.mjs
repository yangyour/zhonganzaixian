import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import Ajv2020 from 'ajv/dist/2020.js'
const contract=JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'))
const validate=new Ajv2020({strict:false}).compile(contract.components.schemas.SlaLatencyStats)
const sample={sample_count:1,unrecovered_count:2,mean_sec:null,p50_sec:null,p95_sec:null,
 source_version:'mop-monitoring-recovery-t2-v1',unknown_count:1}
test('monitoring timing represents missing source evidence separately from unfinished events',()=>{
 assert.equal(validate(sample),true,JSON.stringify(validate.errors))
 assert.equal(validate({...sample,mean_sec:20}),false)
 assert.equal(validate({...sample,source_version:'unverified-ticket-resolution'}),false)
})
test('source version and unknown count travel together and do not alter legacy frozen payloads',()=>{
 const {source_version,...withoutVersion}=sample
 assert.equal(validate(withoutVersion),false)
 const {unknown_count,...withoutCount}=sample
 assert.equal(validate(withoutCount),false)
 assert.equal(validate({sample_count:1,unrecovered_count:0,mean_sec:0,p50_sec:0,p95_sec:0}),true)
})
