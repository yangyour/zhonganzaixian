// @vitest-environment jsdom
import {it,expect,vi,afterEach} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import Panel from '../src/components/AssetReachabilityPanel.vue'
import {emptyReachability,type ReachabilityState} from '../src/utils/assetReachability'
import {reachabilitySample} from './assetReachability.fixture'
let app:App|undefined,host:HTMLElement|undefined
const props=reactive({state:emptyReachability() as ReachabilityState,now:0}),refresh=vi.fn(),cancel=vi.fn(),close=vi.fn()
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,{...props,onRefresh:refresh,onCancel:cancel,onClose:close})});app.component('router-link',{props:['to'],template:'<a><slot /></a>'});app.mount(host);await nextTick()}
function click(label:string){[...host!.querySelectorAll('button')].find(b=>b.textContent===label)!.click()}
afterEach(()=>{app?.unmount();host?.remove();props.state=emptyReachability();props.now=0;vi.clearAllMocks()})
it('paginates the complete snapshot and resets page on filtering',async()=>{const data=reachabilitySample();data.items=Array.from({length:45},(_,i)=>({...data.items[0]!,id:`30000000-0000-4000-8000-${String(i).padStart(12,'0')}`,name:'资产'+i}));props.state.current=data;await mount();expect(host!.querySelectorAll('tbody tr')).toHaveLength(20);click('下一页');await nextTick();expect(host!.querySelector('tbody')!.textContent).toContain('资产20');click('下一页');await nextTick();expect(host!.querySelectorAll('tbody tr')).toHaveLength(5);const select=host!.querySelector('select')!;select.value='NORMAL';select.dispatchEvent(new Event('change'));await nextTick();expect(host!.textContent).toContain('第 1 / 1 页');expect(host!.textContent).toContain('当前筛选没有资产记录')})
it('sample expiry changes both counts and filtered rows',async()=>{const data=reachabilitySample();Object.assign(data.items[0]!,{state:'NORMAL',reason:'FRESH_ICMP_SAMPLE',sampled_at:data.observed_at,valid_until:'2026-10-24T00:01:00Z'});props.state.current=data;await mount();expect(host!.textContent).toContain('正常 1');props.now=60000;await nextTick();expect(host!.textContent).toContain('正常 0');expect(host!.textContent).toContain('未知 1');expect(host!.textContent).toContain('超过60秒新鲜度')})
it('cancel and close are distinct, refresh is disabled during read',async()=>{props.state.busy=true;await mount();expect([...host!.querySelectorAll('button')].find(b=>b.textContent==='重新读取')!.disabled).toBe(true);click('停止等待');click('关闭明细');expect(cancel).toHaveBeenCalledOnce();expect(close).toHaveBeenCalledOnce();expect(refresh).not.toHaveBeenCalled()})
