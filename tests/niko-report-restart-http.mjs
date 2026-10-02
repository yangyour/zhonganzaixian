// Explicit before/after phases for a real, separately controlled graceful component restart.
// This script never starts/stops a service, migrates, creates data or substitutes a repository.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
const phase=process.argv[2];
const root=new URL('../',import.meta.url);
const privateFile=new URL('.local/niko-test/private/sessions/qa-ticket-viewerA.json',root);
const stateFile=new URL('.local/niko-test/report-restart-snapshot.json',root);
let stage='initialization';
try {
  assert.ok(['before','after'].includes(phase));
  const session=JSON.parse(readFileSync(privateFile,'utf8'));
  assert.equal(session.environment,'niko-local-test');
  const ids=JSON.parse(readFileSync(new URL('.local/niko-test/customer-report-smoke.json',root),'utf8'));
  const data={};
  for(const [type,id] of [['tickets',ids.ticket_id],['operations',ids.operation_id]]) {
    stage=`read_${type}`;
    const response=await fetch(`http://127.0.0.1:8080/api/v1/${type}/${id}`,{headers:{Cookie:session.cookie},redirect:'manual',signal:AbortSignal.timeout(12000)});
    assert.equal(response.status,200);
    data[type]=(await response.json()).data;
  }
  assert.equal(data.tickets.state,null);
  assert.equal(data.tickets.priority,null);
  assert.equal(data.tickets.response_due_at,null);
  assert.equal(data.tickets.recovery_due_at,null);
  assert.equal(data.tickets.authority_source,'MOP');
  assert.equal(data.tickets.contact_binding_status,'UNVERIFIED');
  assert.equal(data.tickets.history.length,1);
  assert.equal(data.operations.status,'PENDING');
  assert.equal(data.operations.error.code,'CONFIG_PENDING');
  const digest=createHash('sha256').update(JSON.stringify(data)).digest('hex');
  if(phase==='before') {
    stage='record_checkpoint';
    assert.equal(existsSync(stateFile),false,'Refuse implicit overwrite of a previous checkpoint');
    writeFileSync(stateFile,JSON.stringify({ticket_id:ids.ticket_id,operation_id:ids.operation_id,digest,recorded_at:new Date().toISOString()})+'\n',{mode:0o600,flag:'wx'});
  } else {
    stage='compare_checkpoint';
    const prior=JSON.parse(readFileSync(stateFile,'utf8'));
    assert.equal(prior.ticket_id,ids.ticket_id);assert.equal(prior.operation_id,ids.operation_id);
    assert.equal(digest,prior.digest);
    const evidence={verified_at:new Date().toISOString(),before_at:prior.recorded_at,environment:'niko-local-test',checks:{ticket_and_operation_readback_identical:true,formal_ticket_state:null,priority:null,sla_deadlines:null,contact_binding:'UNVERIFIED',operation_status:'PENDING',operation_error:'CONFIG_PENDING'},http_reads_before:2,http_reads_after:2,readback_sha256:digest,restart_control:'external_run_stack_graceful_restart',limitations:['Not crash recovery or Worker failure validation','No OSS operations or photo verification','No formal ticket workflow/SLA activation'],credentials_output:false};
    writeFileSync(new URL('docs/evidence/niko-customer-report-restart-20261002.json',root),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});
  }
  console.log(JSON.stringify({status:'passed',phase,http_reads:2,credential_output:false}));
} catch {
  console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1;
}
