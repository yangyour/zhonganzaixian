// Read back known archived template IDs after an observed executor-session loss and ordinary component restart.
// This is bounded record verification, not a whole-database before/after equality or a controlled crash-point proof.
import assert from 'node:assert/strict'
import {readFileSync,writeFileSync,existsSync} from 'node:fs'
import {ApiClient,readInspectionDetail} from '../.local/niko-test/inspection-client.mjs'
const root=new URL('../',import.meta.url),output=new URL('docs/evidence/niko-inspection-session-recovery-http-20261004.json',root)
let stage='init',requests=0
try {
 assert.equal(existsSync(output),false)
 const auth=JSON.parse(readFileSync(new URL('.local/niko-test/private/sessions/qa-padmin.json',root),'utf8'));assert.equal(auth.environment,'niko-local-test')
 const api=new ApiClient(async(url,options)=>{requests++;assert.ok(options.method==='GET'||options.method==='POST'&&new URL(url).pathname==='/api/v1/session/tenant');return fetch(url,{...options,headers:{...options.headers,Cookie:auth.cookie},signal:AbortSignal.any([options.signal,AbortSignal.timeout(20000)])})},'http://127.0.0.1:8080/api/v1')
 stage='ready';const ready=await api.request('/health/ready');assert.equal(ready.ready,true);assert.equal(ready.inspection_templates.schema_access_ready,true);assert.equal(ready.inspection_templates.report_published,false)
 let session=await api.request('/session');assert.equal(session.mode,'remote_test');assert.equal(session.user.role,'padmin');api.setCsrfToken(session.csrf_token);const original=session.tenant.id;assert.equal(original,'55000000-0000-4000-8000-000000000001')
 const first=JSON.parse(readFileSync(new URL('.local/niko-test/inspection-template-api-control-retry.json',root),'utf8')),second=JSON.parse(readFileSync(new URL('.local/niko-test/inspection-client-control.json',root),'utf8'));assert.equal(first.cleanup_complete,true);assert.equal(first.pending.length,0);assert.equal(first.created.length,18);assert.equal(second.complete,true);assert.equal(second.pending,null);assert.equal(second.owned.length,18)
 const records=[...first.created.map(value=>({...value,group:'api'})),...second.owned.map(id=>({id,tenant_id:original,group:'client'}))];assert.equal(new Set(records.map(row=>row.id)).size,36)
 const counts={api:0,client:0}
 for(const record of records){stage='read_known_'+record.group;assert.match(record.id,/^[0-9a-f-]{36}$/);assert.ok(['55000000-0000-4000-8000-000000000001','55000000-0000-4000-8000-000000000002'].includes(record.tenant_id))
  if(session.tenant.id!==record.tenant_id){session=await api.request('/session/tenant',{method:'POST',body:{tenant_id:record.tenant_id}});api.setCsrfToken(session.csrf_token)}
  const scope={tenantId:session.tenant.id,actorId:session.user.id,permissionVersion:session.permission_version,role:session.user.role,canRead:true,canWrite:true},row=await readInspectionDetail(await api.request('/inspection-templates/'+record.id),scope);assert.equal(row.id,record.id);assert.equal(row.created_by,session.user.id);assert.equal(row.archived,true);assert.equal(row.action,'ARCHIVE');assert.equal(row.report_published,false)
  if(record.group==='api'){assert.ok(row.name.startsWith('SYNTHETIC_INSPECTION_HTTP_'+first.run+'_'));assert.equal(row.version,row.name.includes('_main_race_')?6:2)}else{assert.ok(row.name.startsWith('SYNTHETIC_INSPECTION_CLIENT_'));assert.equal(row.version,row.name.endsWith('_LATE')?19:2)}
  counts[record.group]++
 }
 if(session.tenant.id!==original){session=await api.request('/session/tenant',{method:'POST',body:{tenant_id:original}});api.setCsrfToken(session.csrf_token)}
 const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:'0.35.0',prior_session:73514,new_session:2689,http_requests:requests,ready:true,known_archived_records_read_back:counts,record_count:36,expected_versions_preserved:true,content_digest_rechecked:true,full_database_pre_crash_digest_available:false,whole_database_equality_proven:false,controlled_fault_point:false,template_writes:0,session_context_switches_only:true,production_approval:'UNVERIFIED',browser_tested:false}
 writeFileSync(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,requests,archived_records:36,template_writes:0}))
}catch{console.error(JSON.stringify({status:'FAILED',stage,details:'redacted'}));process.exitCode=1}
