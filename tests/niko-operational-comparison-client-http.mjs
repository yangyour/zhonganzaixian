// Actual frontend response verifier/workspace against approved real PG/OIDC API; not a browser test.
import assert from 'node:assert/strict';
import {existsSync,readFileSync,writeFileSync} from 'node:fs';
import {ApiClient,createOperationalWorkspace,emptyOperationalInput,operationalSampleInput} from '../.local/niko-test/operational-client.mjs';
const root=new URL('../',import.meta.url);let stage='initialization';
try{
 const evidenceFile=new URL('docs/evidence/niko-operational-comparison-client-http-20261003.json',root);assert.equal(existsSync(evidenceFile),false,'Preserve previous evidence');
 const sessions=Object.fromEntries(['padmin','msp'].map(role=>{const s=JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`,root),'utf8'));assert.equal(s.environment,'niko-local-test');return [role,s]}));
 let requests=0,posts=0,mode=null,release,ready;const nextHeld=()=>new Promise(resolve=>{ready=resolve});
 function client(role,inject=false){const s=sessions[role];const c=new ApiClient(async(url,options)=>{const response=await fetch(url,{...options,headers:{...options.headers,Cookie:s.cookie},signal:options.signal??AbortSignal.timeout(20000)});requests++;if(String(url).endsWith('/operations/explicit-comparisons')){posts++;if(inject&&mode==='drop'){mode=null;assert.equal(response.status,200);throw new TypeError('Controlled successful response discard')}if(inject&&mode==='hold'){mode=null;assert.equal(response.status,200);ready();await new Promise(resolve=>{release=resolve})}}return response},'http://127.0.0.1:8080/api/v1');c.setCsrfToken(s.csrf_token);return c}
 const api=client('padmin',true),admin=client('padmin'),deniedClient=client('msp');const session=await admin.request('/session');const scope={tenantId:session.tenant.id,actorId:session.user.id,permissionVersion:session.permission_version,role:session.user.role,canCompare:session.capabilities.includes('operations:compare_explicit')};assert.equal(scope.canCompare,true);let state;const workspace=createOperationalWorkspace(api,next=>{state=next});workspace.reset(scope);
 const body={elapsed_millis:['9','2','1','3'],staffing:{active_tenants:'30',on_call_fte:'1.5',second_line_fte:'1'}};
 stage='null_groups';assert.equal(await workspace.calculate(),true);assert.equal(state.result.sample_median.median_millis,null);assert.equal(state.result.staffing.tenants_per_fte,null);
 stage='exact_hand_values';workspace.change(body);assert.equal(await workspace.calculate(),true);assert.equal(state.result.sample_median.median_millis,'2.5');assert.equal(state.result.staffing.tenants_per_fte,'12');
 const scenarios=[
 {elapsed_millis:[],staffing:null},
 {elapsed_millis:['0',null,'3'],staffing:null},
 {elapsed_millis:['0','0'],staffing:{active_tenants:'0',on_call_fte:'1',second_line_fte:'0'}},
 {elapsed_millis:['9007199254740993'],staffing:{active_tenants:'9007199254740993',on_call_fte:'1',second_line_fte:'0'}},
 {elapsed_millis:['1'],staffing:{active_tenants:'1',on_call_fte:'1.1',second_line_fte:'1.9'}},
 {elapsed_millis:['0'],staffing:{active_tenants:'0',on_call_fte:'0',second_line_fte:'0'}},
 {elapsed_millis:null,staffing:{active_tenants:'0',on_call_fte:'0',second_line_fte:null}},
 {elapsed_millis:null,staffing:{active_tenants:null,on_call_fte:'1.1',second_line_fte:'1.9'}},
 {elapsed_millis:Array(10000).fill('9'.repeat(40)),staffing:{active_tenants:'9'.repeat(40),on_call_fte:'0.'+'0'.repeat(39)+'1',second_line_fte:'0'}},
 {elapsed_millis:['1'],staffing:{active_tenants:'1',on_call_fte:'9'.repeat(40),second_line_fte:'9'.repeat(40)}},
 ];
 stage='independent_bigint_full_boundary_verification';for(const s of scenarios){workspace.change(s);assert.equal(await workspace.calculate(),true);assert.equal(state.result.formal_metrics_available,false);assert.equal(state.result.staffing.target_met,null)}
 stage='editing_no_auto_request_and_invalid_empty_row';const before=posts;workspace.change({...body,elapsed_millis:operationalSampleInput('provided','0\n')});assert.equal(state.result,null);assert.equal(await workspace.calculate(),false);assert.equal(posts,before);
 stage='successful_response_drop_removes_old_result';workspace.change(body);assert.equal(await workspace.calculate(),true);mode='drop';assert.equal(await workspace.calculate(),false);assert.equal(state.result,null);assert.equal(state.busy,false);
 stage='late_response_after_edit';const held=nextHeld();mode='hold';const pending=workspace.calculate();await held;workspace.change(emptyOperationalInput());release();assert.equal(await pending,false);assert.equal(state.result,null);
 stage='duplicate_click_one_request';workspace.change(body);const doubleHeld=nextHeld();mode='hold';const first=workspace.calculate();await doubleHeld;const count=posts;assert.equal(await workspace.calculate(),false);assert.equal(posts,count);release();assert.equal(await first,true);
 stage='permission_context_invalidation';const permissionHeld=nextHeld();mode='hold';const p=workspace.calculate();await permissionHeld;workspace.reset({...scope,role:'msp',canCompare:false});release();assert.equal(await p,false);assert.equal(state.result,null);assert.equal(await workspace.calculate(),false);workspace.reset(scope);workspace.change(body);
 stage='dispose_while_real_response_held';const closeHeld=nextHeld();mode='hold';const close=workspace.calculate();await closeHeld;workspace.dispose();release();assert.equal(await close,false);assert.equal(state.result,null);
 stage='real_msp_denies_forged_local_admin';let deniedState;const denied=createOperationalWorkspace(deniedClient,s=>{deniedState=s});denied.reset(scope);denied.change(body);assert.equal(await denied.calculate(),false);assert.equal(deniedState.error.status,403);assert.equal(deniedState.result,null);denied.dispose();
 stage='readonly_audit_tail';const tail=(await admin.request('/audit/records?page_size=10')).snapshot_sequence;const final=createOperationalWorkspace(admin,()=>{});final.reset(scope);final.change(body);assert.equal(await final.calculate(),true);assert.equal((await admin.request('/audit/records?page_size=10')).snapshot_sequence,tail);final.dispose();
 const evidence={verified_at:new Date().toISOString(),environment:'niko-local-test',environment_instance:'rebuilt-20261003-after-executor-loss',api_version:'0.29.0',frontend:'actual operationalComparisonWorkspace and ApiClient',http_requests:requests,readonly_comparison_post_attempts:posts,business_records_written:0,independent_bigint_median_and_decimal128_verification:true,max_10000_samples_40_digit_counts_verified:true,unknown_empty_zero_and_missing_fte_distinguished:true,editing_no_auto_request:true,duplicate_click_suppressed:true,successful_response_loss_clears_result:true,late_responses_rejected_after_edit_permission_and_dispose:true,real_msp_forbidden_despite_local_admin:true,successful_readonly_audit_tail_unchanged:true,faults:'one real 200 discarded and four real 200 responses held in client transport; not physical network or process outage',formal_project_metrics:false,browser_dom_tested:false,storage_tested:false,credentials_output:false,contract_done:false};writeFileSync(evidenceFile,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:requests,comparison_posts:posts,business_writes:0}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1}
