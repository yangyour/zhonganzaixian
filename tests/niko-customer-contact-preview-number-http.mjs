// Actual updated frontend parser/workspace; input rounding regression is rejected before transport.
import assert from 'node:assert/strict';
import {existsSync,readFileSync,writeFileSync} from 'node:fs';
import {ApiClient,createContactPreviewWorkspace} from '../.local/niko-test/contact-preview-client.mjs';
const root=new URL('../',import.meta.url);let stage='initialization';
try{
 const output=new URL('docs/evidence/niko-customer-contact-preview-number-http-20261003.json',root);assert.equal(existsSync(output),false);
 const session=JSON.parse(readFileSync(new URL('.local/niko-test/private/sessions/qa-padmin.json',root),'utf8'));assert.equal(session.environment,'niko-local-test');
 let requests=0,posts=0;const api=new ApiClient(async(url,options)=>{requests++;if(options.method==='POST')posts++;return fetch(url,{...options,headers:{...options.headers,Cookie:session.cookie},signal:options.signal??AbortSignal.timeout(20000)})},'http://127.0.0.1:8080/api/v1');api.setCsrfToken(session.csrf_token);
 const current=await api.request('/session');let state;const workspace=createContactPreviewWorkspace(api,v=>{state=v});workspace.reset({tenantId:current.tenant.id,actorId:current.user.id,permissionVersion:current.permission_version,role:current.user.role,canRead:current.capabilities.includes('contacts:read'),canWrite:current.capabilities.includes('contacts:write')});
 stage='five_lossy_or_noncanonical_tokens_blocked_without_request';for(const value of ['1e-9999','0.99999999999999999','1e0','1.0','-0']){workspace.change('{"rows":['+value+']}');assert.equal(await workspace.preview(),false);assert.equal(state.result,null);assert.equal(posts,0)}
 stage='safe_integer_diagnostic_still_reaches_actual_api';workspace.change('{"rows":[0,-1,9007199254740991]}');assert.equal(await workspace.preview(),true);assert.equal(state.result.invalid_rows,3);assert.equal(state.result.valid_rows,0);assert.equal(state.result.can_import,false);assert.equal(posts,1);workspace.dispose();
 writeFileSync(output,JSON.stringify({verified_at:new Date().toISOString(),environment:'niko-local-test',environment_instance:'rebuilt-20261003-after-executor-loss',api_version:'0.31.0',http_requests:requests,readonly_preview_posts:posts,noncanonical_tokens_rejected_before_network:5,safe_integer_diagnostic_rows:3,business_writes:0,frontend:'actual updated contactImportPreviewWorkspace and ApiClient',browser_dom_tested:false,can_import:false,credentials_output:false,contract_done:false},null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:true,http_requests:requests,readonly_posts:posts,local_rejections:5}));
}catch{console.error(JSON.stringify({status:'failed',stage,details:'redacted'}));process.exitCode=1}
