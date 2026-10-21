// @vitest-environment jsdom
import {createApp,nextTick,type App} from 'vue'
import {it,expect,vi,beforeEach,afterEach} from 'vitest'
const hooks=vi.hoisted(()=>({leave:null as null|(()=>boolean)}))
vi.mock('vue-router',()=>({onBeforeRouteLeave:(cb:()=>boolean)=>{hooks.leave=cb}}))
vi.mock('../src/utils/contextTransitionGuard',()=>({contextTransitionGuards:{register:()=>()=>{}}}))
import Page from '../src/pages/SlaReportsPage.vue'
import {api} from '../src/api/client'
import {session,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
import wire from '../../../fixtures/customer-sla-controller.json'
let app:App|undefined,host:HTMLElement,vm:any
const auth:Session={user:{id:wire.report.monthly_result.evaluated_by,role:'msp',display_name:'合成'},tenant:{id:wire.report.tenant_id,name:'合成'},allowed_tenants:[],capabilities:['sla_scenarios:read','ticket_notes:write_internal'],permission_version:3,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only'}
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp(Page);app.component('RouterLink',{template:'<a><slot /></a>'});vm=app.mount(host);await settle()}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('requires explicit month and never automatically queries historical hardcoded default',async()=>{const req=vi.spyOn(api,'request');await mount();expect(vm.periodInput).toBe('');expect(req).not.toHaveBeenCalled();expect(host.textContent).toContain('请明确选择统计月份');expect(await vm.submitMonthlyEvaluation()).toBe(false)})
it('changing month clears freeze selection and prevents cross-period write until reread',async()=>{const r=structuredClone(wire.report);r.monthly_result.frozen=false;r.monthly_result.frozen_at=null as any;const req=vi.spyOn(api,'request').mockResolvedValue(r);await mount();vm.periodInput='2026-10';await vm.workspace.load('2026-10');await settle();vm.freezeSnapshot=true;vm.periodInput='2026-11';await settle();expect(vm.freezeSnapshot).toBe(false);expect(host.textContent).toContain('当前显示仍为 2026-10');expect(await vm.submitMonthlyEvaluation()).toBe(false);expect(req.mock.calls.filter(c=>c[1]?.method==='POST')).toHaveLength(0);const button=[...host.querySelectorAll('button')].find(b=>b.textContent?.includes('更新月度 SLA 快照'));expect(button?.disabled).toBe(true)})
it('scope changes clear selected month and freeze choice, including role-only change',async()=>{vi.spyOn(api,'request');await mount();vm.periodInput='2026-10';vm.freezeSnapshot=true;session.value={...auth,user:{...auth.user,role:'padmin'}};await settle();expect(vm.periodInput).toBe('');expect(vm.freezeSnapshot).toBe(false);expect(vm.state.report).toBeNull()})
