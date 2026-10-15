// @vitest-environment jsdom
// Exercise the page setup and authorization watchers with isolated responses, not a browser/integration claim.
import { createApp, nextTick, type App } from 'vue'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),onBeforeRouteLeave:vi.fn(),onBeforeRouteUpdate:vi.fn()}))
import Page from '../src/pages/RuntimeConfigurationsPage.vue'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
const id=(n:number)=>'74000000-0000-4000-8000-'+String(n).padStart(12,'0'),key='remote.idle_timeout_sec'
const auth:Session={user:{id:id(1),role:'padmin',display_name:'synthetic'},tenant:{id:id(2),name:'synthetic'},permission_version:1,allowed_tenants:[],csrf_token:'SYNTHETIC',capabilities:['configurations:read','configurations:write'],mode:'remote_test',identity_source:'synthetic-unit-only'}
const registry=JSON.parse(readFileSync(resolve('../../contracts/config-baseline.json'),'utf8')).settings
let app:App|undefined,host:HTMLElement,state:any
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
async function mount(){const request=vi.spyOn(api,'request').mockImplementation(async(path,options)=>{if(options?.method==='POST')throw new ApiError(503,'UPSTREAM_UNAVAILABLE','synthetic uncertainty','unit');if(path==='/runtime-configurations/catalog')return {items:registry.map((r:any)=>({...r,editable:r.contract_value_kind!=='fixed_constraint',schema_version:1,definition_hash:'a'.repeat(64),schema:{type:r.type},registered_value:r.value,runtime_scope:'CURRENT_TENANT',customer_confirmation_status:'UNVERIFIED'})),question_count:31,source:'code_catalog'};if(path==='/configurations')return{items:registry};if(path.endsWith('/history'))return{items:[],source:'postgresql',page_size:25};if(path.endsWith('/revisions'))return{items:[]};return{tenant_id:id(2),configuration_key:key,environment:'remote_test',scope:'CURRENT_TENANT',observed_at:'2026-10-15T00:00:00Z',latest_version:0,definition_hash:'a'.repeat(64),status:'UNCONFIGURED',effective_value:null,selected_release:null,source:'postgresql',customer_confirmation_status:'UNVERIFIED',production_approved:false}});host=document.createElement('div');document.body.append(host);app=createApp({...Page,render:()=>null});const vm=app.mount(host);state=(vm.$ as any).setupState;await settle();await state.select(key);await settle();return request}
it('keeps the original uncertain request across an identical session refresh',async()=>{const request=await mount();await state.workspace.publish({expected_version:0,expected_definition_hash:'a'.repeat(64),action:'DISABLE',revision_id:null,revision_version:null,target_release_id:null,activation:'IMMEDIATE',effective_at:null,expires_at:null,reason:'synthetic'});expect(state.workspace.unresolved()).toBe(true);const original=state.state.attempt.key,reads=request.mock.calls.length;session.value={...structuredClone(auth),csrf_token:'SYNTHETIC_REFRESHED'};await settle();expect(state.workspace.unresolved()).toBe(true);expect(state.state.attempt.key).toBe(original);expect(request).toHaveBeenCalledTimes(reads)})
it('a genuinely different actor clears old scoped facts and does not replay the old command',async()=>{const request=await mount();await state.workspace.publish({expected_version:0,expected_definition_hash:'a'.repeat(64),action:'DISABLE',revision_id:null,revision_version:null,target_release_id:null,activation:'IMMEDIATE',effective_at:null,expires_at:null,reason:'synthetic'});session.value={...auth,user:{...auth.user,id:id(3)}};await settle();expect(state.state.attempt).toBeNull();expect(state.state.current).toBeNull();expect(request.mock.calls.filter(c=>c[1]?.method==='POST')).toHaveLength(1)})
