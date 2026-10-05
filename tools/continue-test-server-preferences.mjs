// Continue only the known fresh-realm history prerequisite failure; never recreate the first two slots.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
const root = new URL('../', import.meta.url)
const previous = JSON.parse(readFileSync(new URL('docs/evidence/server-own-notification-preference-http-20261005.json', root), 'utf8'))
assert.equal(previous.completed, false)
assert.equal(previous.failure?.stage, 'readonly_audit_and_history')
assert.equal(previous.pending_original_requests, 0)
const output = new URL('docs/evidence/server-own-preference-continuation-http-20261005.json', root)
assert.equal(existsSync(output), false, 'Preserve a completed continuation; do not repeat writes')
const old = readFileSync(new URL('.local/server-acceptance-preferences.mjs', root), 'utf8')
const start = old.indexOf('\ntry{\n assert.equal(existsSync(output)')
const resume = old.indexOf(" stage='readonly_audit_and_history'")
assert.ok(start > 0 && resume > start)
const prefix = old.slice(0, start).replace('server-own-notification-preference-http-20261005.json', 'server-own-preference-continuation-http-20261005.json')
const setup = `
try{
 Object.assign(control,JSON.parse(readFileSync(controlFile,'utf8')));assert.equal(control.pending.length,0);assert.equal(control.completed,false);assert.equal(control.owned.length,2);initialized=true
 stage='recheck_original_owned_slots'
 for(const role of ['padmin','msp','tadmin','viewerA','viewerB','reviewer']){auth[role]=JSON.parse(readFileSync(new URL('.local/server-acceptance/private/sessions/qa-'+role+'.json',root),'utf8'));assert.equal(auth[role].environment,'designated-server-acceptance');const s=await request(role,'GET','/session',{schema:'SessionResult'});assert.equal(s.user.id,auth[role].user_id);auth[role].session=s;auth[role].csrf_token=s.csrf_token}
 const original=control.owned.find(x=>x.role==='viewerA').original,admin=control.owned.find(x=>x.role==='tadmin').original
 const originalBody=control.original_request.body,originalKey=control.original_request.key
 const reset=await read('viewerA',original.id),adminReset=await read('tadmin',admin.id)
 assert.equal(reset.version,4);assert.equal(adminReset.version,2);draft(reset,'viewerA');draft(adminReset,'tadmin')
 stage='explicit_fresh_test_history_preparation'
 const absent=await current('viewerB');assert.equal(absent.exists,false,'Fresh acceptance viewer B expected; never overwrite unknown prior history')
 let prepared=await request('viewerB','POST',path,{body:empty(),schema:'OwnNotificationPreferenceResult'})
 control.owned.push({role:'viewerB',id:prepared.id,original:prepared});save()
 while(prepared.version<11){prepared=await request('viewerB','PATCH',path+'/'+prepared.id,{body:{...empty(),expected_version:prepared.version,requested_level:prepared.version%2?'P1_ONLY':null},schema:'OwnNotificationPreferenceResult'});draft(prepared,'viewerB')}
 control.prepared_history={owner:'viewerB',id:prepared.id,versions:prepared.version,explicit_api_writes:true};save()
`
const tail = old.slice(resume).replace(/api_version\s*:\s*'0\.[0-9]+\.0'/g, "api_version:'0.44.0'")
const generated = new URL('.local/server-acceptance-preferences-continuation.mjs', root)
writeFileSync(generated, prefix + setup + tail)
const result = spawnSync(process.execPath, [generated.pathname.replace(/^\/([A-Za-z]:)/, '$1')], { cwd: new URL('.', root), stdio: 'inherit', timeout: 180000 })
process.exitCode = result.status ?? 1
