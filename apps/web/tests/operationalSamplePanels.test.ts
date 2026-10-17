// @vitest-environment jsdom
import { createApp, h, nextTick, shallowReactive, type App } from 'vue'
import { afterEach, expect, it } from 'vitest'
import Panel from '../src/components/OperationalSamplePanels.vue'
import type { OperationalResult } from '../src/utils/operationalComparisonWorkspace'
let app:App|undefined, host:HTMLElement
const result=(values:(string|null)[]|null)=>({input:{elapsed_millis:values,staffing:null},staffing:{}} as OperationalResult)
async function mount(value:OperationalResult|null){host=document.createElement('div');document.body.append(host);const props=shallowReactive({result:value,busy:false});app=createApp({render:()=>h(Panel,props)});app.mount(host);await nextTick();return props}
afterEach(()=>{app?.unmount();host?.remove()})
function button(label:string){return [...host.querySelectorAll<HTMLButtonElement>('button')].find(x=>x.textContent===label)!}
it('has no charts before an actual verified response',async()=>{await mount(null);expect(host.querySelector('meter,table')).toBeNull();expect(host.textContent).toContain('等待计算')})
it('renders unknown separately and does not present distribution as a time trend',async()=>{await mount(result(['0',null,'100']));expect(host.textContent).toContain('1 个未知项单独保留');expect(host.textContent).toContain('仅反映数值分布特征');expect(host.querySelectorAll('tbody tr')).toHaveLength(3)})
it('limits original-input DOM rows to 50 and keeps source indexes through filtering',async()=>{await mount(result(Array.from({length:101},(_,i)=>i%20===0?null:String(i))));expect(host.querySelectorAll('tbody tr')).toHaveLength(50);button('下一页样本').click();await nextTick();expect(host.querySelector('tbody td')?.textContent).toBe('51');const filter=host.querySelector<HTMLInputElement>('input')!;filter.checked=true;filter.dispatchEvent(new Event('change',{bubbles:true}));await nextTick();expect([...host.querySelectorAll('tbody tr td:first-child')].map(x=>x.textContent)).toEqual(['1','21','41','61','81','101']);expect(button('下一页样本').disabled).toBe(true)})
it('changing inputs clears the previous visualization along with the result',async()=>{const props=await mount(result(['1','2']));expect(host.querySelector('meter')).not.toBeNull();props.result=null;await nextTick();expect(host.querySelector('meter,table')).toBeNull()})
it('empty and all-unknown inputs never draw zero-valued bars',async()=>{const props=await mount(result([]));expect(host.textContent).toContain('本次样本集为空');expect(host.querySelector('meter')).toBeNull();props.result=result([null]);await nextTick();expect(host.textContent).toContain('当前样本全部为未知项');expect(host.querySelector('meter')).toBeNull()})
