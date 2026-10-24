// @vitest-environment jsdom
import {createApp,h,nextTick,ref,type App} from 'vue'
import {createMemoryHistory,createRouter,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import Panel from '../src/components/DeliveryDeadLetterPanel.vue'
import {api} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
import type {DeliveryEntry} from '../src/utils/deliveryJournal'
import {deliveryEntry,deliveryScope as s} from './deliveryJournal.fixture'
import {receipt} from './deliveryDeadLetter.fixture'
let app:App|undefined,host:HTMLElement;const entry=ref<DeliveryEntry|null>(null),changed=vi.fn()
const auth:Session={user:{id:s.actor,display_name:'Synthetic',role:'padmin'},tenant:{id:s.tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['configurations:write','ticket_notes:read_internal','audit:read']}
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
const button=(label:string)=>[...document.querySelectorAll('button')].find(x=>x.textContent?.trim()===label)!
async function mount(){host=document.createElement('div');document.body.append(host);const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>h(Panel,{entry:entry.value,onChanged:changed})}}]});await router.push('/');await router.isReady();app=createApp({render:()=>h(RouterView)});app.use(router).use(ElementPlus).mount(host);await settle()}
beforeEach(()=>{sessionStorage.clear();session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value++;entry.value={...deliveryEntry(),version:2,recorded_status:'FAILED'} as DeliveryEntry;changed.mockReset()})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;sessionStorage.clear();vi.restoreAllMocks()})
async function fill(){button('将失败记录转为死信').click();await settle();const text=document.querySelector('textarea')!;text.value='Synthetic reason';text.dispatchEvent(new Event('input'));(document.querySelector('input[type="checkbox"]') as HTMLInputElement).click();await settle()}
it('requires explicit reason and confirmation and never turns UNKNOWN into disposition',async()=>{const request=vi.spyOn(api,'request');await mount();button('将失败记录转为死信').click();await settle();expect(button('确认转为死信').disabled).toBe(true);expect(request).not.toHaveBeenCalled();entry.value={...entry.value!,recorded_status:'UNKNOWN'};await settle();expect(button('将失败记录转为死信')).toBeUndefined();expect(request).not.toHaveBeenCalled()})
it('records exact original command and surfaces successful application without send authority',async()=>{const request=vi.spyOn(api,'request').mockImplementation(async(path,options)=>receipt({command_id:options!.idempotencyKey!,tenant_id:s.tenant,actor_id:s.actor,delivery_id:s.id,resource_pool_id:s.pool,expected_version:2,reason:'Synthetic reason'}) as any);await mount();await fill();button('确认转为死信').click();await settle();expect(request).toHaveBeenCalledTimes(1);expect(request.mock.calls[0][1]?.body).toEqual({expected_version:2,reason:'Synthetic reason'});expect(changed).toHaveBeenCalledWith(s.id);expect(host.textContent).toContain('已记录人工死信处置')})
it('unknown result keeps original key for explicit retry and blocks duplicate submission',async()=>{const request=vi.spyOn(api,'request').mockRejectedValue(new Error('response lost'));await mount();await fill();button('确认转为死信').click();await settle();expect(host.textContent).toContain('处置结果待确认');expect(button('确认转为死信').disabled).toBe(true);button('按原命令核对处置').click();await settle();expect(request.mock.calls[1][1]?.idempotencyKey).toBe(request.mock.calls[0][1]?.idempotencyKey);expect(request.mock.calls[1][1]?.body).toEqual(request.mock.calls[0][1]?.body)})
it('context change cancels confirmation and removes private details',async()=>{vi.spyOn(api,'request');await mount();await fill();session.value={...auth,capabilities:[]};await settle();expect(host.querySelector('section')).toBeNull();expect(document.querySelector('textarea')?.closest('[role="dialog"]')?.getAttribute('aria-hidden')??'true').toBe('true')})

it('can stop waiting inside the dialog and retain a usable original-command recovery',async()=>{
 let finish!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>finish=r) as any);await mount();await fill();button('确认转为死信').click();await settle();const dialog=document.querySelector('[role="dialog"]')!;const stop=[...dialog.querySelectorAll('button')].find(b=>b.textContent?.trim()==='停止等待处置')!;stop.click();await settle();expect(document.activeElement).toBe(button('核对原处置'));expect(host.textContent).toContain('处置结果待确认');button('关闭并保留原命令').click();await settle();expect(host.textContent).toContain('处置结果待确认');finish({});await settle();expect(changed).not.toHaveBeenCalled();
})
