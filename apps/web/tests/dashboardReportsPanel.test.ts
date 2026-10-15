// @vitest-environment jsdom
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElAlert, ElButton } from 'element-plus'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import Panel from '../src/components/DashboardReportsPanel.vue'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { context, page, ticket, id } from './ticketReader.fixture'
let app: App | undefined, host: HTMLElement
const auth:Session={user:{id:context.actorId,role:'viewer',display_name:'测试'},tenant:{id:context.tenantId,name:'测试'},allowed_tenants:[],capabilities:['tickets:read'],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only'}
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:Panel}]});await router.push('/dashboard');app=createApp(Panel);app.use(router);app.component('ElAlert',ElAlert);app.component('ElButton',ElButton);app.mount(host);await settle()}
function button(name:string){return [...host.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.trim()===name)!}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('shows validated own records with actual detail links without a formal state claim',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue(page());await mount();expect(host.textContent).toContain('我的报障登记');expect(host.textContent).toContain('不把登记数算作待处理工单数');expect(host.querySelector('tbody a')?.getAttribute('href')).toBe('/tickets?ticket_id='+id(10));expect(req.mock.calls[0]![0]).toBe('/tickets?page_size=10');expect(req.mock.calls[0]![1]?.method).toBe('GET')})
it('uses the opaque cursor and explicitly returns to the first page',async()=>{const first=page(Array.from({length:10},(_,n)=>ticket(10+n)),true);const req=vi.spyOn(api,'request').mockResolvedValueOnce(first).mockResolvedValueOnce(page([ticket(25)])).mockResolvedValueOnce(first);await mount();button('下一页登记').click();await settle();expect(req.mock.calls[1]![0]).toContain('cursor=opaque.signed-cursor');expect(host.textContent).toContain('第 2 页');button('返回第一页').click();await settle();expect(req.mock.calls[2]![0]).toBe('/tickets?page_size=10');expect(host.textContent).toContain('第 1 页')})
it('failed continuation retries the same cursor and never displays old rows',async()=>{const first=page(Array.from({length:10},(_,n)=>ticket(10+n)),true);const req=vi.spyOn(api,'request').mockResolvedValueOnce(first).mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','暂不可用','unit')).mockResolvedValueOnce(page([ticket(25)]));await mount();button('下一页登记').click();await settle();expect(host.querySelector('tbody')).toBeNull();button('重新读取').click();await settle();expect(req.mock.calls[2]![0]).toBe(req.mock.calls[1]![0]);expect(host.textContent).toContain('第 2 页')})
it('cross-tenant or other reporters are rejected for a customer account',async()=>{vi.spyOn(api,'request').mockResolvedValue(page([{...ticket(),reporter_id:id(99)}]));await mount();expect(host.querySelector('tbody')).toBeNull();expect(host.textContent).toContain('登记读取失败')})
it('tenant switching aborts and discards a late old response',async()=>{let finish!:(v:any)=>void;const req=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>finish=r));await mount();switchingTenant.value=true;await settle();finish(page());await settle();expect(host.textContent).not.toContain('合成报障');expect(req.mock.calls[0]![1]?.signal?.aborted).toBe(true)})
it('does not fetch without a ticket grant and clears rows on grant loss',async()=>{session.value={...auth,capabilities:[]};const req=vi.spyOn(api,'request').mockResolvedValue(page());await mount();expect(req).not.toHaveBeenCalled();session.value=structuredClone(auth);await settle();expect(host.textContent).toContain('合成报障');session.value={...auth,capabilities:[]};await settle();expect(host.querySelector('table')).toBeNull()})
it('known empty list differs from a failure and cannot paginate',async()=>{vi.spyOn(api,'request').mockResolvedValue(page([]));await mount();expect(host.textContent).toContain('当前授权范围没有报障登记');expect(button('下一页登记').disabled).toBe(true)})
