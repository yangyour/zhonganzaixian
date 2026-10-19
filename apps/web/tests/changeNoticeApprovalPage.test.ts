// @vitest-environment jsdom
// Integrated page/component guard fixtures only; no real API or browser claims.
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {createApp,nextTick,type App} from 'vue'
import {webcrypto} from 'node:crypto'
const mocks=vi.hoisted(()=>({confirm:vi.fn(),leave:null as null|(()=>Promise<boolean>),guard:null as null|(()=>Promise<boolean>)}))
vi.mock('vue-router',()=>({onBeforeRouteLeave:(cb:()=>Promise<boolean>)=>{mocks.leave=cb}}))
vi.mock('element-plus',async original=>({...await original<typeof import('element-plus')>(),ElMessageBox:{confirm:mocks.confirm}}))
vi.mock('../src/utils/contextTransitionGuard',()=>({contextTransitionGuards:{register:(cb:()=>Promise<boolean>)=>{mocks.guard=cb;return()=>{}}}}))
import {ElAlert,ElButton} from 'element-plus'
import Page from '../src/pages/ChangeNoticeDraftsPage.vue'
import {api,ApiError} from '../src/api/client'
import {session,switchingTenant,contextGeneration} from '../src/stores/session'
import type {Session} from '../src/api/types'
import {approval,row,scope} from './changeNoticeApproval.fixture'
import {page} from './changeNotice.fixture'
let app:App|undefined,host:HTMLElement,vm:any
const auth:Session={user:{id:scope.actorId,role:'padmin',display_name:'Synthetic'},tenant:{id:scope.tenantId,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',capabilities:['change_notice_drafts:read','change_notice_drafts:write','assets:read'],mode:'remote_test',identity_source:'synthetic-unit-only'}
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
function button(text:string){const b=[...host.querySelectorAll('button')].find(e=>e.textContent?.trim()===text);if(!b)throw Error('Missing '+text);return b}
function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);app.component('ElAlert',ElAlert);app.component('ElButton',ElButton);vm=app.mount(host)}
function setup(){return vi.spyOn(api,'request').mockImplementation(async(path,opts)=>{if(opts?.method)throw new ApiError(0,'NETWORK_ERROR','synthetic unknown','test');if(path.endsWith('/approval'))return approval();if(path.startsWith('/change-notice-drafts?'))return page();return row()})}
async function prepareApproval(){await vm.select(row().id);await settle();button('读取所选草稿修订的审批').click();await vi.waitFor(()=>expect(host.querySelector('.approval-current')).not.toBeNull());button('按审批版本 v1 准备动作').click();await settle()}
beforeEach(()=>{vi.stubGlobal('crypto',webcrypto);mocks.confirm.mockReset().mockResolvedValue('confirm');session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value++})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks();vi.unstubAllGlobals()})
it('approval unknown-result state participates in parent route, tenant, unload and draft-write guards',async()=>{const request=setup();mount();await settle();await prepareApproval();button('提交此修订审核').click();await vi.waitFor(()=>expect(host.textContent).toContain('原对象、版本、说明和幂等key已锁定'));expect(await mocks.leave!()).toBe(false);expect(await mocks.guard!()).toBe(false);expect(await vm.prepareFrom(row())).toBe(false);const unload=new Event('beforeunload',{cancelable:true});window.dispatchEvent(unload);expect(unload.defaultPrevented).toBe(true);expect(request.mock.calls.filter(([,o])=>o?.method==='POST')).toHaveLength(1);contextGeneration.value++;await settle();expect(host.querySelector('.approval-attempt')).toBeNull()})
it('unknown draft write blocks separately prepared approval submission',async()=>{const request=setup();mount();await settle();await prepareApproval();expect(await vm.prepareFrom(row())).toBe(true);expect(await vm.save()).toBe(false);await settle();expect(button('提交此修订审核').disabled).toBe(true);button('提交此修订审核').click();await settle();expect(request.mock.calls.filter((path)=>path[0].endsWith('/approval/actions'))).toHaveLength(0);expect(host.textContent).toContain('草稿原请求尚未确认')})
