// Native Vue/jsdom controls connected to real PG/OIDC. Confirmation response is explicitly stubbed, not browser QA.
import {it,vi} from 'vitest'
import assert from 'node:assert/strict'
import {existsSync,readFileSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {randomUUID,webcrypto} from 'node:crypto'
import {createApp,nextTick,type App} from 'vue'
vi.mock('vue-router',()=>({onBeforeRouteLeave:()=>undefined}))
vi.mock('element-plus',async original=>({...await original<typeof import('element-plus')>(),ElMessage:{warning:()=>undefined},ElMessageBox:{confirm:async()=>true}}))
import {ElAlert} from 'element-plus'
import Page from '../src/pages/AssetExportJobsPage.vue'
import {api} from '../src/api/client'
import type {Session} from '../src/api/types'
import type {AssetExportSummary} from '../src/utils/assetExportJobs'
import {session,switchingTenant,contextGeneration} from '../src/stores/session'
it('recovers explicit preparation-only acceptance using native controls and reads current progress independently',async()=>{
 const root=resolve(process.cwd(),'../..'),output=resolve(root,'docs/evidence/niko-export-page-http-20261004.json'),controlFile=resolve(root,'.local/niko-test/export-page-control.json')
 const control={marker:'SYNTHETIC_EXPORT_PAGE_'+randomUUID(),original:null as null|{path:string;key:string;body:any;job_id:string|null},commands:[]as{key:string;body:any;status:number|null}[],completed:false,ui_receipt_confirmed:false}
 let stage='authorize',initialized=false,app:App|undefined,host:HTMLElement|undefined,vm:any,requests=0,writes=0,drop=false,actualLoss=false,auditStable=false,preparedRead=false,failure:null|{stage:string;details:string}=null
 const realFetch=globalThis.fetch,statuses:Record<string,number>={},save=()=>writeFileSync(controlFile,JSON.stringify(control,null,2)+'\n',{mode:0o600})
 const tick=async()=>{for(let n=0;n<5;n++){await Promise.resolve();await nextTick()}},wait=async(p:()=>boolean)=>vi.waitFor(()=>assert.ok(p(),stage),{timeout:15000,interval:20})
 const button=(text:string)=>{const b=[...host!.querySelectorAll<HTMLButtonElement>('button')].find(v=>v.textContent?.trim()===text);assert.ok(b,'Native action absent');return b}
 const input=(label:string,value:string)=>{const e=host!.querySelector<HTMLInputElement>(`[aria-label="${label}"]`);assert.ok(e);e.value=value;e.dispatchEvent(new Event('input',{bubbles:true}))}
 const checkbox=(label:string,value:boolean)=>{const e=host!.querySelector<HTMLInputElement>(`[aria-label="${label}"]`);assert.ok(e);e.checked=value;e.dispatchEvent(new Event('change',{bubbles:true}))}
 try{
  assert.equal(process.env.MOP_RUN_NIKO_EXPORT_PAGE_HTTP,'true');assert.equal(existsSync(output),false);assert.equal(existsSync(controlFile),false);initialized=true;save()
  const auth=JSON.parse(readFileSync(resolve(root,'.local/niko-test/private/sessions/qa-padmin.json'),'utf8'));assert.equal(auth.environment,'niko-local-test');vi.stubGlobal('crypto',webcrypto)
  vi.stubGlobal('fetch',async(url:any,options:any={})=>{const target=new URL(String(url),'http://127.0.0.1:8080');assert.equal(target.origin,'http://127.0.0.1:8080');assert.ok(target.pathname.startsWith('/api/v1/'));const headers=new Headers(options.headers);headers.set('Cookie',auth.cookie);const write=options.method==='POST';if(write)headers.set('X-CSRF-Token',auth.csrf_token);let command:typeof control.commands[number]|undefined
   if(write){assert.equal(target.pathname,'/api/v1/asset-export-jobs');writes++;command={key:headers.get('Idempotency-Key')!,body:JSON.parse(options.body),status:null};assert.ok(command.key);control.commands.push(command);control.original??={path:'/asset-export-jobs',key:command.key,body:command.body,job_id:null};assert.equal(command.key,control.original.key);assert.deepEqual(command.body,control.original.body);save()}
   requests++;const response=await realFetch(target,{...options,headers,redirect:'error'});statuses[response.status]=(statuses[response.status]??0)+1;const value=await response.clone().json();if(command){command.status=response.status;if(response.status===202){control.original!.job_id??=value.data.job_id;assert.equal(value.data.job_id,control.original!.job_id)}save()}
   if(write&&drop&&response.status===202){drop=false;actualLoss=true;throw new TypeError('CONTROLLED_REAL_202_RESPONSE_LOSS')}return response
  })
  stage='actual_session';const s=await api.request<Session>('/session');assert.equal(s.user.id,auth.user_id);assert.equal(s.user.role,'padmin');auth.csrf_token=s.csrf_token;api.setCsrfToken(s.csrf_token);session.value=s;switchingTenant.value=false;contextGeneration.value++
  stage='mount_and_native_filters';host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);app.component('ElAlert',ElAlert);vm=app.mount(host);await tick();await wait(()=>vm.state.page!==null&&vm.state.busy===null);assert.equal(writes,0);assert.equal(button('受理本次准备请求').disabled,true)
  checkbox('导出站点不限',false);checkbox('导出文本不限',false);input('导出检索文本',control.marker);checkbox('确认仅准备不发布',true);await tick();assert.equal(button('受理本次准备请求').disabled,false)
  stage='lost_original_and_guard';drop=true;button('受理本次准备请求').click();await tick();await wait(()=>vm.hasUnresolved()&&vm.state.busy===null);assert.ok(control.original?.job_id);assert.equal(vm.state.receipt,null);assert.equal(host.querySelector<HTMLFieldSetElement>('fieldset')!.disabled,true);assert.equal(await vm.discard(),false);assert.equal(await vm.beginNew(),false)
  const beforeRefresh=writes;button('只读刷新第一页').click();await tick();await wait(()=>vm.state.page!==null&&vm.state.busy===null);assert.equal(vm.hasUnresolved(),true);assert.equal(vm.state.receipt,null);assert.equal(writes,beforeRefresh)
  stage='native_exact_retry';button('重试完全相同原请求').click();await tick();await wait(()=>vm.state.receipt!==null&&vm.state.busy===null);assert.equal(vm.state.receipt.status,'QUEUED');assert.equal(vm.state.receipt.job_id,control.original.job_id);control.ui_receipt_confirmed=true;save();assert.equal(writes,2);assert.deepEqual(control.original.body.filters,{type:null,status:null,site_id:'',query:control.marker})
  stage='readonly_current_and_retained_descriptor';const before=await api.request<{snapshot_sequence:number}>('/audit/records?page_size=10');button('独立读取该作业当前进度').click();await tick();await wait(()=>vm.state.selected!==null&&vm.state.busy===null);assert.equal(vm.state.selected.status,'QUEUED');assert.equal(vm.state.selected.prepared,null);assert.ok(host.querySelector('.no-preparation')!.textContent!.includes('行数与文件大小未知'));assert.ok(host.querySelector('.original-receipt')!.textContent!.includes('QUEUED / v0'))
  button('只读刷新第一页').click();await tick();await wait(()=>vm.state.page!==null&&vm.state.busy===null)
  for(let n=0;n<3&&!preparedRead;n++){
   const candidates=(vm.state.page.items as AssetExportSummary[]).filter(v=>['RUNNING','RECONCILING_STORAGE'].includes(v.status)&&v.filters.query?.startsWith('SYNTHETIC'))
   for(const candidate of candidates){assert.equal(await vm.select(candidate.id),true);await tick();if(vm.state.selected.prepared){preparedRead=true;assert.ok(host.querySelector('.prepared-descriptor'));assert.equal(host.querySelector('[download],.current-export a'),null);break}}
   if(!preparedRead&&vm.state.page.has_more){button('下一页本人作业').click();await tick();await wait(()=>vm.state.page!==null&&vm.state.busy===null)}else break
  }
  assert.equal(preparedRead,true);const after=await api.request<{snapshot_sequence:number}>('/audit/records?page_size=10');assert.equal(after.snapshot_sequence,before.snapshot_sequence);auditStable=true;assert.equal(vm.state.receipt.job_id,control.original.job_id)
  stage='explicit_new_form_without_new_acceptance';button('清除并准备新请求').click();await tick();await wait(()=>vm.state.attempt===null);assert.equal(vm.form.query,'');assert.equal(vm.acknowledge,false);assert.equal(writes,2);assert.equal(host.querySelector('.original-request'),null)
  control.completed=true
 }catch{failure={stage,details:'Native component verification incomplete. Preserve original private commands and actual receipts; do not rerun blindly.'}}
 finally{app?.unmount();host?.remove();session.value=null;api.setCsrfToken(null);api.invalidateContext();vi.unstubAllGlobals();if(initialized){save();writeFileSync(output,JSON.stringify({verified_at:new Date().toISOString(),environment:'niko-local-test',api_version:'0.42.0',vue_sfc_native_jsdom:true,real_browser:false,css_visual_verified:false,phone_or_wechat_verified:false,confirmation_response_explicitly_stubbed:true,http_requests:requests,write_requests:writes,statuses,completed:control.completed,controlled_loss_after_actual_202:actualLoss,ui_original_receipt_confirmed:control.ui_receipt_confirmed,successful_readonly_window_audit_unchanged:auditStable,existing_owned_prepared_descriptor_read:preparedRead,owned_jobs:control.original?.job_id?1:0,worker_started:false,object_store_requests:0,download_granted:false,new_credentials:false,new_migrations:0,failure,formal_acceptance:'NOT_RUN'},null,2)+'\n',{flag:'wx'})}if(failure)throw new Error('Export component verification failed at '+failure.stage)}
})
