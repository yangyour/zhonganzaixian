// Actual existing PG/OIDC records through production client/validators. No fixture, mutation or browser claim.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { createHash } from 'node:crypto'
import {realEnvironment,realTestDirectory,realEvidencePrefix,realEnvironmentInstance} from './real-test-environment.mjs'
import { ApiClient, readRegisteredTicket, readTicketPage } from '../.local/niko-test/ticket-reader-client.mjs'
const root=new URL('../',import.meta.url)
const args=process.argv.slice(2);assert.ok(args.length===0 || args.length===1 && args[0]==='--reverify')
const output=new URL(`docs/evidence/${realEvidencePrefix}-ticket-reader-client-http${args.length?'-final':''}-20261004.json`,root)
let stage='initialize',requests=0
try {
  assert.equal(existsSync(output),false)
  const clients={},scopes={},pages={},results=[]
  for(const role of ['padmin','msp','tadmin','viewerA','viewerB','reviewer']){
    const auth=JSON.parse(readFileSync(new URL(`${realTestDirectory}private/sessions/qa-${role}.json`,root),'utf8'))
    assert.equal(auth.environment,realEnvironment)
    clients[role]=new ApiClient(async(url,options)=>{
      assert.equal(options.method,'GET');assert.equal(options.body,undefined);requests++
      return fetch(url,{...options,headers:{...options.headers,Cookie:auth.cookie},signal:AbortSignal.any([options.signal,AbortSignal.timeout(20000)])})
    },'http://127.0.0.1:8080/api/v1')
    stage='session_'+role
    const s=await clients[role].request('/session');assert.equal(s.mode,'remote_test');assert.equal(s.identity_source,'test_oidc')
    scopes[role]={tenantId:s.tenant.id,actorId:s.user.id,role:s.user.role,permissionVersion:s.permission_version}
  }
  const registry=await clients.padmin.request('/configurations'),hash=createHash('sha256').update(JSON.stringify(registry)).digest('hex')
  for(const role of Object.keys(clients)){
    stage='list_'+role
    const client=clients[role],scope=scopes[role],raw=await client.request('/tickets?page_size=10'),p=readTicketPage(raw,scope,10)
    pages[role]=p
    let details=0,attachments=0,nextRows=0
    if(p.items.length){
      stage='detail_'+role
      const first=p.items[0],detail=readRegisteredTicket(await client.request('/tickets/'+first.id),scope,first.id)
      assert.deepEqual(detail,first);details++;attachments+=detail.attachments.length
      raw.items[0].history[0].actor_id='LOCAL_ONLY_TAMPER';assert.equal(p.items[0].history[0].actor_id,first.reporter_id)
      stage='filtered_'+role
      const filtered=readTicketPage(await client.request('/tickets?page_size=10&q='+encodeURIComponent(first.receipt_no)+'&category='+encodeURIComponent(first.category)+'&urgency='+first.urgency),scope,10,{category:first.category,urgency:first.urgency})
      assert.equal(filtered.items.length,1);assert.equal(filtered.items[0].id,first.id)
    }
    if(p.has_more){
      stage='next_'+role
      const second=readTicketPage(await client.request('/tickets?page_size=10&cursor='+encodeURIComponent(p.next_cursor)),scope,10)
      const firstIds=new Set(p.items.map(row=>row.id));assert.ok(second.items.every(row=>!firstIds.has(row.id)));nextRows=second.items.length
    }
    const refresh=readTicketPage(await client.request('/tickets?page_size=10'),scope,10);assert.deepEqual(refresh,p)
    results.push({test_role:role,first_page_rows:p.items.length,detail_rows:details,next_page_rows:nextRows,ready_attachment_rows:attachments,refresh_equal:true})
  }
  stage='actual_cross_tenant_404'
  const other=pages.viewerB.items[0];assert.ok(other);assert.notEqual(scopes.viewerB.tenantId,scopes.viewerA.tenantId)
  await assert.rejects(clients.viewerA.request('/tickets/'+other.id),error=>error.status===404)
  stage='actual_same_tenant_nonreporter_404'
  const own=pages.viewerA.items[0];assert.ok(own);assert.equal(scopes.viewerA.tenantId,scopes.tadmin.tenantId);assert.notEqual(scopes.viewerA.actorId,scopes.tadmin.actorId)
  await assert.rejects(clients.tadmin.request('/tickets/'+own.id),error=>error.status===404)
  stage='registry_readback'
  const after=await clients.padmin.request('/configurations');assert.equal(createHash('sha256').update(JSON.stringify(after)).digest('hex'),hash)
  const evidence={verified_at:new Date().toISOString(),environment:realEnvironment,api_contract_version:JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json',root),'utf8')).info.version,http_requests:requests,http_methods:['GET'],roles:results,existing_records_only:true,cross_tenant_detail_404:true,same_tenant_nonreporter_404:true,registry_entries:registry.items.length,registry_sha256_unchanged:hash,business_writes:0,oidc_refresh_separate:true,security_denials_may_write_audit:true,browser_dom_tested:false,oss_tested:false,formal_workflow_activated:false,contract_done:false}
  writeFileSync(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:requests,roles:results,business_writes:0}))
} catch {console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1}
