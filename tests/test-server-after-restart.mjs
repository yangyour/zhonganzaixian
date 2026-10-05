import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
const root = new URL('../', import.meta.url)
const json = path => JSON.parse(readFileSync(new URL(path, root), 'utf8'))
const base = json('deploy/private/server-test-runtime.json').public_origin + '/api/v1'
const actions = json('.local/server-acceptance/private/browser-actions-control.json')
const workers = json('.local/server-acceptance/private/deployed-worker-control.json')
const exported = json('.local/server-acceptance/private/browser-export-control.json')
const auth = Object.fromEntries(['padmin','viewerA'].map(role => [role,json('.local/server-acceptance/private/sessions/qa-'+role+'.json')]))
const output = new URL('docs/evidence/server-after-restart-http-20261005.json',root)
assert.equal(existsSync(output),false)
const evidence = { verified_at:new Date().toISOString(),environment:'designated-server-acceptance',api_version:'0.44.0',requests:0,checks:[],completed:false,formal_acceptance:'NOT_RUN' }
async function request(role,method,path,body,key,status=200){
 const a=auth[role],headers={Cookie:a.cookie,'X-CSRF-Token':a.csrf_token,'Content-Type':'application/json'}
 if(key)headers['Idempotency-Key']=key
 const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(20000)});evidence.requests++
 assert.equal(response.status,status);return(await response.json()).data
}
try{
 for(const role of Object.keys(auth)){const session=await request(role,'GET','/session');assert.equal(session.user.id,auth[role].user_id);auth[role].csrf_token=session.csrf_token}
 const created=actions.receipts[0].data,original=actions.requests[0]
 assert.deepEqual(await request('padmin','POST','/assets',original.body,original.key),created)
 assert.equal((await request('padmin','GET','/assets/'+created.id)).id,created.id);evidence.checks.push('asset original write returns same durable receipt after JVM restart')
 for(const [path,id] of [['/inspection-source-collections',actions.source_id],['/my/notification-preferences',actions.preference_id]]){
  const command=actions.observed_writes.find(item=>item.path==='/api/v1'+path)
  const receipt=await request('padmin','POST',path,command.body,command.key)
  assert.equal(receipt.id,id);assert.equal((await request('padmin','GET',path+'/'+id)).id,id)
 }
 evidence.checks.push('source collection and own preference original writes/readback remain durable')
 const contact=workers.requests.find(item=>item.path==='/contact-imports')
 assert.deepEqual(await request('padmin','POST','/contact-imports',contact.body,contact.key,202),workers.objects.contact_receipt)
 const contactJob=await request('padmin','GET','/contact-imports/'+workers.objects.contact_receipt.operation_id)
 assert.equal(contactJob.status,'SUCCEEDED');assert.equal(contactJob.results[0].contact_id,workers.objects.contact_id)
 assert.equal((await request('padmin','GET','/contacts/'+workers.objects.contact_id)).archived,true)
 evidence.checks.push('completed contact job keeps original pending 202 and archived created row')
 assert.equal((await request('padmin','GET','/assets/import-sources/'+workers.objects.source_id)).parse_status,'PARSED')
 assert.equal((await request('viewerA','GET','/files/'+workers.objects.photo_id)).upload_status,'READY')
 evidence.checks.push('stored image and parsed source survive API restart')
 assert.deepEqual(await request('padmin','POST','/asset-export-jobs',exported.request.body,exported.request.key,202),exported.receipt)
 const prepared=await request('padmin','GET','/asset-export-jobs/'+exported.receipt.job_id)
 assert.equal(prepared.prepared.row_count,1);assert.equal(prepared.download_allowed,false)
 evidence.checks.push('prepared export preserves original queued 202 and real descriptor')
 evidence.completed=true
}catch(error){evidence.failure={type:error.name};process.exitCode=1}
finally{writeFileSync(output,JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence))}
