// Real designated PG/OIDC + installed browser. Own synthetic candidate only; no publication.
import assert from 'node:assert/strict';
import {existsSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {randomUUID,createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const root=new URL('../',import.meta.url),base='http://127.0.0.1:5173';
const suffix=process.argv[2]??'';assert.ok(suffix===''||/^attempt[1-9][0-9]*$/.test(suffix));
const output=new URL(`docs/evidence/local-inspection-dom${suffix?'-'+suffix:''}-20261004.json`,root);
const controlFile=new URL(`.local/designated-test/inspection-dom${suffix?'-'+suffix:''}-control.json`,root);
const images=new URL(`docs/evidence/local-ui-inspection-20261004${suffix?'-'+suffix:''}/`,root);
let browser,context,page,owned=null,stage='preconditions',failure=null;
const checks=[],checkpoint={owned:null,pending:null,cleanup_complete:false};
const evidence={verified_at:new Date().toISOString(),environment:'designated-server-from-Windows',browser:'installed Edge with sandbox',checks,passed:false,viewport_sizes:[[1440,900],[390,844]],fault_type:'one actual committed PATCH success response discarded in browser transport',published:false,active_scan_performed:false,formal_acceptance:'NOT_RUN'};
function check(name,value){assert.ok(value,name);checks.push(name)}
function save(){writeFileSync(controlFile,JSON.stringify(checkpoint,null,2)+'\n')}
async function api(method,path,body,key=randomUUID()){
 const s=await context.request.get(base+'/api/v1/session');assert.equal(s.status(),200);const session=(await s.json()).data;
 const response=await context.request.fetch(base+'/api/v1'+path,{method,headers:{'X-CSRF-Token':session.csrf_token,'Idempotency-Key':key},...(body?{data:body}:{})});
 assert.equal(response.status(),200);return (await response.json()).data;
}
async function login(p){
 const runtime=JSON.parse(readFileSync(new URL('deploy/private/runtime.json',root),'utf8').replace(/^\uFEFF/,'')),u=runtime.test_users.find(x=>x.role==='padmin');assert.ok(u);
 await p.goto(base+'/login');await p.getByRole('button',{name:'使用 Keycloak 登录',exact:true}).click();await p.locator('#username').waitFor();
 const url=new URL(p.url());assert.equal(url.origin,new URL(runtime.environment.MOP_OIDC_ISSUER).origin);assert.equal(url.searchParams.get('code_challenge_method'),'S256');
 await p.locator('#username').fill(u.username);await p.locator('#password').fill(u.password);await p.locator('#kc-login').click();await p.getByRole('heading',{name:'资产管理',exact:true}).waitFor();
 await p.goto(base+'/inspection-templates');await p.getByRole('heading',{name:'巡检模板候选',exact:true}).waitFor();
}
async function fit(p){return p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)}
async function mutation(p,method,path,button){const response=p.waitForResponse(r=>r.request().method()===method&&new URL(r.url()).pathname===path);await button.click();const r=await response;assert.equal(r.status(),200);return (await r.json()).data}
try{
 assert.equal(existsSync(output),false);assert.equal(existsSync(controlFile),false);mkdirSync(new URL('.local/designated-test/',root),{recursive:true});mkdirSync(images,{recursive:true});writeFileSync(controlFile,JSON.stringify(checkpoint)+'\n',{flag:'wx'});
 const executablePath=['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','C:/Program Files/Microsoft/Edge/Application/msedge.exe'].find(existsSync);assert.ok(executablePath);
 browser=await chromium.launch({executablePath,headless:true,chromiumSandbox:true});context=await browser.newContext({viewport:{width:1440,height:900}});page=await context.newPage();page.setDefaultTimeout(20000);let pageErrors=0;page.on('pageerror',()=>pageErrors++);
 stage='real_login';await login(page);check('real_padmin_page_loaded',await fit(page));
 const name='SYNTHETIC_INSPECTION_DOM_'+randomUUID();
 stage='create_editor';await page.getByRole('button',{name:'新建未发布模板',exact:true}).click();await page.getByLabel('模板设备类型标识',{exact:true}).fill('custom_switch');await page.getByLabel('模板名称',{exact:true}).fill(name);
 await page.getByLabel('检查项1标识',{exact:true}).fill('first');await page.getByLabel('检查项1分组',{exact:true}).fill('明确测试分组');await page.getByLabel('检查项1名称',{exact:true}).fill('测试空说明');await page.getByLabel('检查项1说明未填写',{exact:true}).uncheck();
 await page.getByRole('button',{name:'添加检查项',exact:true}).click();await page.getByLabel('检查项2标识',{exact:true}).fill('first');await page.getByLabel('检查项2分组',{exact:true}).fill('测试分组二');await page.getByLabel('检查项2名称',{exact:true}).fill('测试未填写说明');
 let sent=0;page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/v1/inspection-templates')sent++});
 await page.getByRole('button',{name:'保存新候选',exact:true}).click();await page.getByText('请补齐设备类型标识、模板名称和非重复检查项；说明可未填写，不会自动补默认值。未发送新请求',{exact:true}).waitFor();check('duplicate_keys_keep_two_rows_and_send_no_request',sent===0&&await page.locator('.editor-items>li').count()===2);
 await page.getByLabel('检查项2标识',{exact:true}).fill('second');await page.getByRole('button',{name:'上移检查项2',exact:true}).click();check('real_dom_reorder_preserves_distinct_fields',await page.getByLabel('检查项1标识',{exact:true}).inputValue()==='second');
 stage='create_save';owned=await mutation(page,'POST','/api/v1/inspection-templates',page.getByRole('button',{name:'保存新候选',exact:true}));checkpoint.owned={id:owned.id,name,tenant_id:owned.tenant_id};save();check('ordered_null_and_empty_persist',owned.items[0].key==='second'&&owned.items[0].instruction===null&&owned.items[1].instruction==='');
 await page.getByRole('button',{name:'回读当前模板',exact:true}).click();await page.locator('.current-template').waitFor();await page.getByRole('button',{name:'按当前版本编辑',exact:true}).click();await page.getByLabel('模板名称',{exact:true}).fill(name+'_v2');
 stage='discard_committed_success';let captured=null,faultError=false,settle;const faultDone=new Promise(resolve=>settle=resolve);const path='/api/v1/inspection-templates/'+owned.id;
 await page.route('**'+path,async route=>{
  if(route.request().method()!=='PATCH'||captured){await route.continue();return}
  try{const request=route.request();checkpoint.pending={method:'PATCH',path,body:request.postData(),key:request.headers()['idempotency-key']};save();const response=await route.fetch();assert.equal(response.status(),200);captured=(await response.json()).data;checkpoint.pending.receipt=captured;save();await route.abort('failed')}catch{faultError=true;await route.abort('failed').catch(()=>{})}finally{settle()}
 });
 await page.getByRole('button',{name:'保存新修订',exact:true}).click();await Promise.race([faultDone,new Promise((_,reject)=>setTimeout(()=>reject(new Error('fault deadline')),30000))]);assert.equal(faultError,false);assert.ok(captured);
 await page.getByRole('button',{name:'使用完全相同原请求重试',exact:true}).waitFor();check('unknown_write_locks_original_input',!await page.getByLabel('模板名称',{exact:true}).isEnabled());
 await page.getByRole('button',{name:'查询 / 刷新第一页',exact:true}).click();await page.getByRole('button',{name:'使用完全相同原请求重试',exact:true}).waitFor();check('ordinary_real_get_keeps_original_unknown',true);
 stage='recover_original';const replay=await mutation(page,'PATCH',path,page.getByRole('button',{name:'使用完全相同原请求重试',exact:true}));check('original_receipt_recovered_once',replay.id===owned.id&&replay.version===2);evidence.original_request_body_sha256=createHash('sha256').update(checkpoint.pending.body).digest('hex');checkpoint.pending=null;save();
 await page.unroute('**'+path);await page.getByRole('button',{name:'回读当前模板',exact:true}).click();await page.getByRole('button',{name:'按当前版本编辑',exact:true}).click();await page.getByLabel('模板名称',{exact:true}).fill(name+'_stale_input');
 stage='real_cas_conflict';const three=await api('PATCH','/inspection-templates/'+owned.id,{expected_version:2,name:name+'_v3',device_type:replay.device_type,items:replay.items});assert.equal(three.version,3);
 const rejected=page.waitForResponse(r=>r.request().method()==='PATCH'&&new URL(r.url()).pathname===path);await page.getByRole('button',{name:'保存新修订',exact:true}).click();assert.equal((await rejected).status(),409);await page.getByRole('button',{name:'保留输入并解锁',exact:true}).click();check('actual_409_keeps_stale_input_and_baseline',await page.getByLabel('模板名称',{exact:true}).inputValue()===name+'_stale_input');
 await page.getByRole('button',{name:'关闭输入',exact:true}).click();await page.getByRole('button',{name:'确认替换',exact:true}).click();
 await page.getByLabel('模板名称筛选',{exact:true}).fill(name);await page.getByRole('button',{name:'查询 / 刷新第一页',exact:true}).click();await page.getByRole('button',{name:'查看完整定义',exact:true}).click();
 stage='archive';await page.locator('.current-template').getByRole('button',{name:'归档并保留历史',exact:true}).click();const confirmArchive=page.locator('.el-message-box').getByRole('button',{name:'归档并保留历史',exact:true});await confirmArchive.waitFor();const archive=await mutation(page,'POST',path+'/archive',confirmArchive);assert.ok(archive.archived&&archive.version===4);
 await page.getByRole('button',{name:'回读当前模板',exact:true}).click();await page.getByRole('button',{name:'按当前版本准备恢复',exact:true}).click();const restored=await mutation(page,'POST',path+'/restore',page.getByRole('button',{name:'明确恢复为未发布候选',exact:true}));check('archive_restore_keep_unverified_history',restored.version===5&&!restored.archived&&restored.approval_status==='UNVERIFIED');
 await page.getByRole('button',{name:'回读当前模板',exact:true}).click();await page.getByRole('button',{name:'读取修订历史',exact:true}).click();await page.locator('.template-history article').first().waitFor();check('all_five_real_revisions_visible',await page.locator('.template-history article').count()===5);await page.locator('.template-history article').last().getByRole('button',{name:'查看该版定义',exact:true}).click();check('old_null_and_empty_definition_visible',await page.locator('.template-history').getByText('说明未填写（null）',{exact:true}).isVisible()&&await page.locator('.template-history').getByText('明确空说明',{exact:true}).isVisible());
 await page.locator('.current-template').scrollIntoViewIfNeeded();await page.screenshot({path:fileURLToPath(new URL('desktop-inspection-history.png',images)),scale:'css'});check('desktop_no_document_overflow',await fit(page));
 stage='mobile_history';const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,storageState:await context.storageState()});const p=await mobile.newPage();p.setDefaultTimeout(20000);await p.goto(base+'/inspection-templates');await p.getByRole('heading',{name:'巡检模板候选',exact:true}).waitFor();await p.getByLabel('模板名称筛选',{exact:true}).fill(name);await p.getByRole('button',{name:'查询 / 刷新第一页',exact:true}).click();await p.getByRole('button',{name:'查看完整定义',exact:true}).click();await p.getByRole('button',{name:'读取修订历史',exact:true}).click();await p.locator('.template-history article').first().waitFor();await p.locator('.template-history article').first().getByRole('button',{name:'查看该版定义',exact:true}).click();check('mobile_same_frozen_history',await p.locator('.template-history article').count()===5);check('mobile_no_document_overflow',await fit(p));await p.locator('.template-history').scrollIntoViewIfNeeded();await p.screenshot({path:fileURLToPath(new URL('mobile-inspection-history.png',images)),scale:'css'});await mobile.close();
 check('no_browser_page_errors',pageErrors===0);evidence.browser_version=browser.version();evidence.synthetic_candidates_created=1;evidence.report_published=false;evidence.passed=true;
}catch(error){writeFileSync(new URL(`.local/designated-test/inspection-dom${suffix?'-'+suffix:''}-failure.txt`,root),String(error?.message??'unknown'));failure={stage,details:'redacted'};process.exitCode=1}
finally{
 if(owned&&context){try{const current=await api('GET','/inspection-templates/'+owned.id);assert.ok(current.name.startsWith(checkpoint.owned.name));if(!current.archived)await api('POST','/inspection-templates/'+owned.id+'/archive',{expected_version:current.version});checkpoint.cleanup_complete=true;save()}catch{failure??={stage:'archive_own_candidate',details:'redacted'};evidence.passed=false;process.exitCode=1}}
 if(browser)await browser.close();if(failure)evidence.failure=failure;evidence.all_own_candidates_archived=checkpoint.cleanup_complete;if(!existsSync(output))writeFileSync(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({passed:evidence.passed,checks:checks.length,...(failure?{failed_stage:failure.stage}:{}),all_own_candidates_archived:checkpoint.cleanup_complete}));
}
