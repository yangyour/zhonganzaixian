// @vitest-environment jsdom
// Component regressions; does not constitute browser acceptance.
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
vi.mock('../src/components/LiveMonitoringPanel.vue',()=>({default:{props:['autoRefresh'],template:'<div data-panel="live"><span data-refresh>{{autoRefresh}}</span><slot name="tools" /></div>'}}))
vi.mock('../src/components/DashboardEventsPanel.vue',()=>({default:{template:'<div data-panel="events" />'}}))
vi.mock('../src/components/DashboardReportsPanel.vue',()=>({default:{template:'<div data-panel="reports" />'}}))
vi.mock('../src/components/DashboardWorkflowsPanel.vue',()=>({default:{template:'<div data-panel="workflows" />'}}))
import Page from '../src/pages/DashboardPage.vue'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { scope } from './dashboard.fixture'
const auth:Session={user:{id:scope.actorId,role:'viewer',display_name:'合成用户'},tenant:{id:scope.tenantId,name:'合成租户'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',capabilities:['dashboard:read','assets:read','tickets:read'],mode:'remote_test',identity_source:'synthetic-unit-only'}
let app:App,host:HTMLElement
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value++;host=document.createElement('div');document.body.append(host);app=createApp(Page);app.mount(host)})
afterEach(()=>{app.unmount();host.remove();session.value=null;vi.restoreAllMocks()})
it('retains functional panels without placeholder KPIs or explanatory scaffolding',()=>{expect(host.querySelectorAll('[data-panel]')).toHaveLength(4);expect(host.querySelector('.dashboard-kpis,.formal-dashboard-status,.dashboard-explanation,.tenant-badge')).toBeNull();expect(host.querySelector('h1')?.textContent).toBe('运营总览')})
it('does not poll unused dashboard projection',async()=>{const spy=vi.spyOn(api,'request');vi.useFakeTimers();await vi.advanceTimersByTimeAsync(90000);expect(spy).not.toHaveBeenCalled();vi.useRealTimers()})
it('checkbox changes actual monitoring child prop without persistence',async()=>{const spy=vi.spyOn(api,'request');const input=host.querySelector('input')!;input.checked=false;input.dispatchEvent(new Event('change',{bubbles:true}));await nextTick();expect(host.querySelector('[data-refresh]')?.textContent).toBe('false');expect(spy).not.toHaveBeenCalled()})
it.each(['assets:read','tickets:read'])('honors child capability loss: %s',async(cap)=>{session.value={...auth,capabilities:auth.capabilities.filter(c=>c!==cap)};await nextTick();expect(host.querySelector(cap==='assets:read'?'[data-panel="live"]':'[data-panel="reports"]')).toBeNull();expect(host.querySelector('[data-panel="events"]')).not.toBeNull()})
it('removes all children when tenant switch starts',async()=>{switchingTenant.value=true;await nextTick();expect(host.querySelector('[data-panel]')).toBeNull();expect(host.textContent).toContain('已清除原范围数据')})
it('removes all children on dashboard permission loss',async()=>{session.value={...auth,capabilities:[]};await nextTick();expect(host.querySelector('[data-panel]')).toBeNull()})
it('rejects local fixture session',async()=>{session.value={...auth,mode:'local_fixture'};await nextTick();expect(host.querySelector('[data-panel]')).toBeNull()})
it('remounts child state after account context changes',async()=>{const old=host.querySelector('[data-panel="events"]');contextGeneration.value++;await nextTick();expect(host.querySelector('[data-panel="events"]')).not.toBe(old)})
it.each(['viewer','tadmin','msp','padmin'] as const)('retains dashboard for permitted role %s',async(role)=>{session.value={...auth,user:{...auth.user,role}};await nextTick();expect(host.querySelector('[data-panel="events"]')).not.toBeNull()})
