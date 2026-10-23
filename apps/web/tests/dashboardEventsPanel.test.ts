// @vitest-environment jsdom
import {createApp,nextTick,type App} from 'vue'
import {createMemoryHistory,createRouter} from 'vue-router'
import {ElAlert,ElButton} from 'element-plus'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import Panel from '../src/components/DashboardEventsPanel.vue'
import {api,ApiError} from '../src/api/client'
import {session,switchingTenant,contextGeneration} from '../src/stores/session'
import type {Session} from '../src/api/types'
import {context,sampleWorkspace as controllerWorkspace} from './monitoringEventControllerFixtures'
// Controller fixture covers DTO states; repository orders equal timestamps by descending UUID.
function sampleWorkspace(){const value=controllerWorkspace();value.events.sort((a,b)=>a.id>b.id?-1:a.id<b.id?1:0);return value}
let app:App|undefined,host:HTMLElement
const auth:Session={user:{id:context.actorId,role:'padmin',display_name:'测试'},tenant:{id:context.tenantId,name:'测试'},allowed_tenants:[],capabilities:['dashboard:read','tickets:read'],permission_version:context.permissionVersion,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only'}
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:Panel}]});await router.push('/dashboard');app=createApp(Panel);app.use(router);app.component('ElAlert',ElAlert);app.component('ElButton',ElButton);app.mount(host);await settle()}
function button(name:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===name)!}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('uses real controller DTO with bounded GET and stable event detail deep link',async()=>{const data=sampleWorkspace(),req=vi.spyOn(api,'request').mockResolvedValue(data);await mount();expect(req).toHaveBeenCalledTimes(1);expect(req.mock.calls[0]![0]).toBe('/events?window=24h&page_size=7');expect(req.mock.calls[0]![1]?.method).toBe('GET');expect(host.querySelector('tbody a.event-link')?.getAttribute('href')).toBe('/monitoring-events?event_id='+data.events[0]!.id);expect(host.textContent).toContain('已读取 6 条近期记录');expect(host.querySelector('tbody .sev')).not.toBeNull()})
it('renders the M1-5 columns with merge and suppression badges taken from the event DTO',async()=>{const data=sampleWorkspace();vi.spyOn(api,'request').mockResolvedValue(data);await mount();expect([...host.querySelectorAll('thead th')].map(th=>th.textContent)).toEqual(['级别','问题','资产','状态','发生时间']);const rows=[...host.querySelectorAll('tbody tr')];expect(rows).toHaveLength(data.events.length);rows.forEach((row,index)=>{const event=data.events[index]!;expect(row.querySelector('.mop-pill-count')?.textContent??null).toBe(event.occurrence_count>1?'×'+event.occurrence_count:null);expect(Boolean(row.querySelector('.mop-chip-tag'))).toBe(event.suppression_state!=='NONE');expect(row.querySelector('time')?.getAttribute('datetime')).toBe(event.last_occurred_at)});expect(host.querySelector('tbody a[href^="/assets"]')).toBeNull()})
it.each(['viewer','tadmin'])('never requests for %s even with matching capabilities',async role=>{session.value={...auth,user:{...auth.user,role:role as any}};const req=vi.spyOn(api,'request');await mount();expect(req).not.toHaveBeenCalled();expect(host.querySelector('section')).toBeNull()})
it.each(['dashboard:read','tickets:read'])('requires %s independently',async cap=>{session.value={...auth,capabilities:auth.capabilities.filter(c=>c!==cap)};const req=vi.spyOn(api,'request');await mount();expect(req).not.toHaveBeenCalled()})
it('known empty has a bounded truthful message rather than a healthy or zero inventory claim',async()=>{const data=sampleWorkspace();data.events=[];vi.spyOn(api,'request').mockResolvedValue(data);await mount();expect(host.textContent).toContain('当前查询没有事件记录');expect(host.textContent).toContain('不代表全量设备健康');expect(host.querySelector('table')).toBeNull()})
it('refresh failure removes old rows and explicit retry uses the same endpoint',async()=>{const req=vi.spyOn(api,'request').mockResolvedValueOnce(sampleWorkspace()).mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','暂不可用','unit')).mockResolvedValueOnce(sampleWorkspace());await mount();button('刷新近期事件').click();await settle();expect(host.querySelector('table')).toBeNull();expect(host.textContent).toContain('未沿用旧列表');button('重新读取').click();await settle();expect(host.querySelector('table')).not.toBeNull();expect(new Set(req.mock.calls.map(c=>c[0])).size).toBe(1)})
it.each(['tenant','permission','unmount','switch'])('aborts and discards late response after %s change',async kind=>{let finish!:(v:unknown)=>void;const req=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>finish=r));await mount();if(kind==='tenant')session.value=null;if(kind==='permission')session.value={...auth,capabilities:[]};if(kind==='switch')switchingTenant.value=true;if(kind==='unmount'){app!.unmount();app=undefined}await settle();expect(req.mock.calls[0]![1]?.signal?.aborted).toBe(true);finish(sampleWorkspace());await settle();expect(host.querySelector('table')).toBeNull()})
it('rejects cross-tenant, oversized and unsorted lists instead of silently slicing',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue({...sampleWorkspace(),tenant_id:'ffffffff-ffff-4fff-8fff-ffffffffffff'});await mount();expect(host.querySelector('table')).toBeNull();const many=sampleWorkspace();many.events=Array.from({length:8},(_,n)=>({...many.events[0]!,id:`00000000-0000-4000-8000-${String(100-n).padStart(12,'0')}`}));req.mockResolvedValue(many);button('刷新近期事件').click();await settle();expect(host.querySelector('table')).toBeNull();many.events=many.events.slice(0,2).reverse();req.mockResolvedValue(many);button('刷新近期事件').click();await settle();expect(host.querySelector('table')).toBeNull()})
it('inflight refresh is disabled and text values cannot become HTML',async()=>{const data=sampleWorkspace();data.events[0]!.rule_name='<img src=x onerror=alert(1)>';const req=vi.spyOn(api,'request').mockResolvedValueOnce(data);await mount();expect(host.querySelector('img')).toBeNull();expect(host.textContent).toContain('<img src=x');let finish!:(v:unknown)=>void;req.mockImplementation(()=>new Promise(r=>finish=r));button('刷新近期事件').click();await settle();expect(button('读取中…').disabled).toBe(true);button('读取中…').click();expect(req).toHaveBeenCalledTimes(2);finish(data);await settle()})

it('account version change aborts the old request and accepts only the new authorized read',async()=>{
 const completions:Array<(value:unknown)=>void>=[];
 const req=vi.spyOn(api,'request').mockImplementation(()=>new Promise(resolve=>completions.push(resolve)));
 await mount();expect(req).toHaveBeenCalledTimes(1);
 session.value={...auth,account_version:2};await settle();
 expect(req.mock.calls[0]![1]?.signal?.aborted).toBe(true);expect(req).toHaveBeenCalledTimes(2);
 const old=sampleWorkspace();old.events[0]!.rule_name='STALE_ACCOUNT_RECORD';completions[0]!(old);await settle();
 expect(host.textContent).not.toContain('STALE_ACCOUNT_RECORD');expect(host.querySelector('table')).toBeNull();
 const fresh=sampleWorkspace();fresh.events[0]!.rule_name='CURRENT_ACCOUNT_RECORD';completions[1]!(fresh);await settle();
 expect(host.textContent).toContain('CURRENT_ACCOUNT_RECORD');expect(host.textContent).not.toContain('STALE_ACCOUNT_RECORD');
})
it('account version change clears already rendered records before the next response',async()=>{
 const req=vi.spyOn(api,'request').mockResolvedValueOnce(sampleWorkspace());await mount();expect(host.querySelector('table')).not.toBeNull();
 let finish!:(value:unknown)=>void;req.mockImplementation(()=>new Promise(resolve=>finish=resolve));
 session.value={...auth,account_version:2};await settle();expect(host.querySelector('table')).toBeNull();expect(host.textContent).toContain('正在读取近期事件');
 finish(sampleWorkspace());await settle();expect(host.querySelector('table')).not.toBeNull();
})
