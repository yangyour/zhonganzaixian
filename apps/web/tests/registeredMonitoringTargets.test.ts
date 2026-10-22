// @vitest-environment jsdom
import{createApp,nextTick,type App}from'vue'
import{beforeEach,afterEach,it,expect,vi}from'vitest'
import Component from'../src/components/RegisteredMonitoringTargets.vue'
import{api}from'../src/api/client'
import{session,switchingTenant,contextGeneration}from'../src/stores/session'
import type{Session}from'../src/api/types'
import golden from'../../../fixtures/monitoring-onboarding-golden.json'
const g=golden[0]!,t=g.config.targets[0]!,actor='55000000-0000-4000-8000-000000000002'
const auth:Session={user:{id:actor,role:'padmin',display_name:'Synthetic'},tenant:{id:t.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read','configurations:read','configurations:write'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
const page=()=>({tenant_id:t.tenant_id,actor_id:actor,permission_version:7,items:[{target_id:t.id,asset_name:'合成主机',configuration:g.config,configuration_sha256:g.sha256,test_authorized:true}],source:'SERVER_EXPLICIT_CONFIGURATION',continuous_collection_verified:false})
const outcome=()=>({tenant_id:t.tenant_id,actor_id:actor,permission_version:7,report:{target_id:t.id,configuration_sha256:g.sha256,probe_status:'FAILED',reason:'CONNECTION_FAILED',continuous_collection_verified:false,device_identity_verified:false,health_verified:false,sample_count:null,matched_metrics:[]},source:'SERVER_ONE_SHOT_PROBE'})
let app:App,host:HTMLElement
async function settle(){for(let i=0;i<8;i++){await nextTick();await Promise.resolve()}}
function button(s:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===s)!}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value++;host=document.createElement('div');document.body.append(host);app=createApp(Component);app.mount(host)})
afterEach(()=>{app.unmount();host.remove();session.value=null;vi.restoreAllMocks()})
async function load(){const req=vi.spyOn(api,'request').mockResolvedValue(page());button('读取登记目标').click();await settle();await vi.waitFor(()=>expect(host.querySelector('tbody tr')).not.toBeNull());return req}
it('never probes or loads automatically',()=>{const req=vi.spyOn(api,'request');expect(host.querySelector('tbody')).toBeNull();expect(req).not.toHaveBeenCalled();expect(button('读取登记目标')).toBeTruthy()})
it('named table scroll and scoped headers retain accessible operation',async()=>{await load();expect(host.querySelector('[role="region"]')?.getAttribute('tabindex')).toBe('0');expect(host.querySelectorAll('th[scope="col"]')).toHaveLength(4);expect(button('测试连接').getAttribute('aria-label')).toBe('测试 合成主机')})
it('executes one probe and renders failure rather than healthy zero',async()=>{const req=await load();req.mockResolvedValueOnce(outcome());button('测试连接').click();await settle();await vi.waitFor(()=>expect(host.textContent).toContain('单次探测失败'));expect(req.mock.calls.at(-1)![1]?.body).toEqual({configuration_sha256:g.sha256});expect(host.textContent).toContain('CONNECTION_FAILED');expect(host.textContent).toContain('不证明持续采集')})
it('read-only session sees configuration but cannot probe',async()=>{session.value={...auth,capabilities:['assets:read','configurations:read']};await settle();await load();expect(button('测试连接')).toBeUndefined();expect(host.textContent).toContain('只有读取权限')})
it('cancelling a pending probe does not release its late report',async()=>{const req=await load();let finish!:(v:any)=>void;req.mockImplementationOnce(()=>new Promise(r=>finish=r));button('测试连接').click();await settle();button('取消等待').click();await settle();expect(host.textContent).toContain('服务器可能仍在完成');finish(outcome());await settle();expect(host.querySelector('.server-probe-report')).toBeNull()})
it('capability loss removes endpoint and results',async()=>{await load();session.value={...auth,capabilities:[]};await settle();expect(host.querySelector('section')).toBeNull();expect(host.textContent).not.toContain(t.endpoint)})
