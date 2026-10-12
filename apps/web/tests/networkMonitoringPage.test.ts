// @vitest-environment jsdom
import {createApp,nextTick,type App} from 'vue'
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import {ElAlert,ElButton} from 'element-plus'
import Page from '../src/pages/NetworkMonitoringPage.vue'
import {api,ApiError} from '../src/api/client'
import {session,switchingTenant,contextGeneration} from '../src/stores/session'
import type {Session} from '../src/api/types'
import {snapshot,history} from './networkMonitoringFixtures'
let app:App|undefined,host:HTMLElement
const auth:Session={user:{id:'55000000-0000-4000-8000-000000000002',display_name:'测试',role:'viewer'},tenant:{id:snapshot().tenant_id,name:'虚构租户'},allowed_tenants:[],capabilities:['assets:read'],permission_version:1,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'project_local'}
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);app.component('ElAlert',ElAlert);app.component('ElButton',ElButton);app.mount(host)}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks();vi.useRealTimers()})
it('shows actual status and requires explicit device selection',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue(snapshot());mount();await settle();expect(host.textContent).toContain('2.00 ms');expect(host.textContent).toContain('请选择上方一台设备');expect(req).toHaveBeenCalledTimes(1)})
it('device history and window controls send explicit requests',async()=>{const req=vi.spyOn(api,'request').mockResolvedValueOnce(snapshot()).mockResolvedValueOnce(history());mount();await settle();([...host.querySelectorAll('button')].find(x=>x.textContent!.includes('查看历史'))!).click();await settle();expect(req.mock.calls[1]![0]).toContain('/history?metric=latency&window=HOUR_1');expect(host.querySelectorAll('svg circle')).toHaveLength(3)})
it('tenant switch clears cards and rejects delayed old response',async()=>{let finish!:(x:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>finish=r));mount();await settle();switchingTenant.value=true;await nextTick();finish(snapshot());await settle();expect(host.querySelector('.device-card')).toBeNull()})
it('source failure clears prior successful data',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue(snapshot());mount();await settle();req.mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','来源暂时不可用','test'));([...host.querySelectorAll('button')].find(x=>x.textContent!.includes('刷新监控'))!).click();await settle();expect(host.querySelector('.device-card')).toBeNull();expect(host.textContent).toContain('来源暂时不可用')})
it('no grant makes no network request',async()=>{session.value={...auth,capabilities:[]};const req=vi.spyOn(api,'request');mount();await settle();expect(req).not.toHaveBeenCalled();expect(host.textContent).toContain('当前没有资产读取权限')})
it('unmount aborts pending data request',async()=>{const req=vi.spyOn(api,'request').mockImplementation(()=>new Promise(()=>{}));mount();await settle();const signal=req.mock.calls[0]![1]!.signal!;app!.unmount();app=undefined;expect(signal.aborted).toBe(true)})
