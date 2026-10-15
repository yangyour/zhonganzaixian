import { randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'
import { Client, evidence, save, check } from './verify-runtime-configuration.mjs'
const key='monitor.page_refresh_sec',admin=new Client('padmin'),reviewer=new Client('reviewer'),viewer=new Client('viewerA'),anonymous=new Client('anonymous'),run=evidence.run_id
let definitionHash=''
const command=(version,proposal,patch={})=>({expected_version:version,expected_definition_hash:definitionHash,action:'PUBLISH',revision_id:proposal.id,revision_version:proposal.version,target_release_id:null,activation:'IMMEDIATE',effective_at:null,expires_at:null,reason:'SYNTHETIC_REFRESH_CONFIGURATION '+run,...patch})
async function approve(value){const r=await admin.call('create refresh proposal','/configurations/'+key+'/revisions','POST',{proposed_value:value,reason:'SYNTHETIC_REFRESH_CONFIGURATION '+run,evidence_refs:[{reference:'document-ref:synthetic-refresh-'+run,title:'Synthetic refresh verification, not production approval',sha256:'a'.repeat(64)}]});const submitted=await admin.call('submit refresh proposal','/configuration-revisions/'+r.id+'/submit','POST',{expected_version:r.version});return reviewer.call('review refresh proposal','/configuration-revisions/'+r.id+'/review','POST',{expected_version:submitted.version,decision:'approve',comment:'SYNTHETIC_REFRESH_CONFIGURATION '+run})}
try{
 await anonymous.call('anonymous policy denied','/network-monitoring/refresh-policy','GET',undefined,401)
 await admin.login('padmin');await reviewer.login('reviewer');await viewer.login('viewerA')
 await viewer.call('asset reader cannot access general settings','/runtime-configurations/'+key,'GET',undefined,403)
 const catalog=await admin.call('catalog for refresh definition','/runtime-configurations/catalog');definitionHash=catalog.items.find(x=>x.key===key).definition_hash
 await admin.call('unsupported refresh interval rejected','/runtime-configurations/'+key+'/validate','POST',{value:3601},400)
 const before=await admin.call('refresh current before','/runtime-configurations/'+key)
 if(before.selected_release)assert.ok(before.selected_release.reason.startsWith('SYNTHETIC_REFRESH_CONFIGURATION'),'Do not replace a non-test refresh configuration')
 const baseline=await viewer.call('viewer reads authorized policy','/network-monitoring/refresh-policy');check('policy binds viewer tenant and actor without publisher identity',baseline.tenant_id===viewer.session.tenant.id&&baseline.actor_id===viewer.session.user.id&&!Object.hasOwn(baseline,'published_by'))
 await viewer.call('forged tenant query denied','/network-monitoring/refresh-policy?tenant_id='+viewer.session.tenant.id,'GET',undefined,400)
 const proposal=await approve(15),input=command(before.latest_version,proposal),originalKey=randomUUID()
 const published=await admin.call('publish fifteen-second policy','/runtime-configurations/'+key+'/releases','POST',input,200,{key:originalKey})
 const policy=await viewer.call('viewer consumes published interval','/network-monitoring/refresh-policy');check('business consumer applies fifteen seconds from the exact publication',policy.interval_seconds===15&&policy.auto_refresh_enabled&&policy.publication_id===published.id&&policy.source==='RUNTIME_PUBLICATION')
 const disabled=await admin.call('disable data auto-refresh','/runtime-configurations/'+key+'/releases','POST',command(published.version,proposal,{action:'DISABLE',revision_id:null,revision_version:null}))
 const stopped=await viewer.call('viewer reads explicit disabled policy','/network-monitoring/refresh-policy');check('disabled policy pauses without silently using thirty',stopped.interval_seconds===null&&!stopped.auto_refresh_enabled&&stopped.reason==='DISABLED')
 const restored=await admin.call('restore through explicit rollback','/runtime-configurations/'+key+'/releases','POST',command(disabled.version,proposal,{action:'ROLLBACK',revision_id:null,revision_version:null,target_release_id:published.id}))
 check('rollback changes the business consumer back to fifteen',(await viewer.call('read rollback policy','/network-monitoring/refresh-policy')).interval_seconds===15)
 const baselineProposal=await approve(30),final=await admin.call('restore original contract interval explicitly','/runtime-configurations/'+key+'/releases','POST',command(restored.version,baselineProposal))
 const replay=await admin.call('late original publication receipt replay','/runtime-configurations/'+key+'/releases','POST',input,200,{key:originalKey});assert.deepEqual(replay,published)
 const last=await viewer.call('read final restored interval','/network-monitoring/refresh-policy');check('late replay does not reapply old values',last.interval_seconds===30&&last.publication_id===final.id)
 evidence.status='PASS';evidence.kind='monitoring-refresh-consumer';evidence.finished_at=new Date().toISOString();evidence.cleanup='Explicit reviewed test publication restores 30-second original contract interval; no collector/source settings changed';save();console.log(JSON.stringify({status:evidence.status,requests:evidence.requests.length,checks:evidence.checks.length,run_id:run}))
}catch(e){evidence.status='FAILED';evidence.failure={type:e.name,message:e.message?.slice(0,180)};save();console.error(JSON.stringify(evidence.failure));process.exitCode=1}
