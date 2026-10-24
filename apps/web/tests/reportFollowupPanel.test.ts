// @vitest-environment jsdom
import { createApp,h,nextTick,type App } from 'vue'
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import ElementPlus from 'element-plus'
import Panel from '../src/components/ReportFollowupPanel.vue'
import { api } from '../src/api/client'
import { session,contextGeneration,switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { followups,query,tenant,id } from './reportFollowups.fixture'
let app:App|undefined,host:HTMLElement
const open=vi.fn()
const auth:Session={user:{id:id(99),display_name:'Synthetic',role:'padmin'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['dashboard:read','inspection_quarterly_reports:read','inspection_quarterly_reports:write']}
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,{onOpen:open})});app.use(ElementPlus);app.mount(host);await settle();const dates=host.querySelectorAll('input');dates[0]!.value='2026-10-01';dates[0]!.dispatchEvent(new Event('input'));dates[1]!.value='2026-11-01';dates[1]!.dispatchEvent(new Event('input'));const details=host.querySelector('details')!;details.open=true;details.dispatchEvent(new Event('toggle'));await settle()}
const button=(text:string)=>[...host.querySelectorAll('button')].find(x=>x.textContent?.trim()===text)!
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value++;open.mockReset()})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('loads only on explicit request, opens a report, and retries the original cursor',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(followups());await mount();expect(request).not.toHaveBeenCalled();button('查询跟进列表').click();await settle();expect(host.textContent).toContain('匹配 22 份修订');button('查看阅读情况').click();expect(open).toHaveBeenCalledWith(id(122));
 request.mockRejectedValueOnce(new Error('synthetic outage'));button('跟进列表下一页').click();await settle();expect(host.textContent).not.toContain('Synthetic report 22');const failed=request.mock.calls.at(-1)![0];request.mockResolvedValue(followups({...query,before:followups().next}));button('重试原跟进页').click();await settle();expect(request.mock.calls.at(-1)![0]).toBe(failed);expect(host.textContent).toContain('Synthetic report 1');expect([...host.querySelectorAll('tbody tr td:first-child')].map(td=>td.textContent)).toEqual(['Synthetic report 2','Synthetic report 1']);expect(request.mock.calls.every(([,o])=>!o?.method||o.method==='GET')).toBe(true)
})
it('cancels, restores focus, rejects late results and clears on filter change',async()=>{
 let resolve!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>resolve=r) as any);await mount();button('查询跟进列表').click();await settle();button('取消跟进查询').click();await settle();expect(document.activeElement).toBe(button('重试原跟进页'));resolve(followups());await settle();expect(host.textContent).not.toContain('Synthetic report');const select=host.querySelector('select')!;select.value='ALL';select.dispatchEvent(new Event('change'));await settle();expect(host.textContent).not.toContain('查询已取消')
})
it.each(['tenant','account','capabilities','role','mode'])('clears pending data on %s change',async change=>{
 let resolve!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>resolve=r) as any);await mount();button('查询跟进列表').click();await settle();
 if(change==='tenant')session.value={...auth,tenant:{id:id(90),name:'New'}};else if(change==='account')session.value={...auth,account_version:2};else if(change==='capabilities')session.value={...auth,capabilities:['dashboard:read']};else if(change==='role')session.value={...auth,user:{...auth.user,role:'msp'}};else session.value={...auth,mode:'local_fixture'} as Session;
 resolve(followups());await settle();expect(host.textContent).not.toContain('Synthetic report')
})
it('collapsing clears results and invalid date range prevents dispatch',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(followups());await mount();const dates=host.querySelectorAll('input');dates[1]!.value='2026-09-01';dates[1]!.dispatchEvent(new Event('input'));await settle();expect(button('查询跟进列表').disabled).toBe(true);button('查询跟进列表').click();expect(request).not.toHaveBeenCalled()})
