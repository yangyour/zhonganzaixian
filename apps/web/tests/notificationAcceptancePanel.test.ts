// @vitest-environment jsdom
import {it,expect,vi,afterEach} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import Panel from '../src/components/NotificationAcceptancePanel.vue'
import {emptyAcceptance,acceptanceCounts,type AcceptanceState} from '../src/utils/notificationAcceptance'
import {acceptanceSample} from './notificationAcceptance.fixture'
let app:App|undefined,host:HTMLElement|undefined
const props=reactive({state:emptyAcceptance() as AcceptanceState,expired:false}),refresh=vi.fn(),cancel=vi.fn(),close=vi.fn()
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,{...props,onRefresh:refresh,onCancel:cancel,onClose:close})});app.mount(host);await nextTick()}
function click(label:string){[...host!.querySelectorAll('button')].find(b=>b.textContent===label)!.click()}
afterEach(()=>{app?.unmount();host?.remove();props.state=emptyAcceptance();props.expired=false;vi.clearAllMocks()})
it('shows separate acceptance and missing delivered proof with full pagination',async()=>{const data=acceptanceSample();data.items=Array.from({length:45},(_,i)=>({...data.items[0]!,id:`30000000-0000-4000-8000-${String(i).padStart(12,'0')}`}));data.counts=acceptanceCounts(data.items);data.by_channel.EMAIL=data.counts;props.state.current=data;await mount();expect(host!.textContent).toContain('供应商受理 45');expect(host!.textContent).toContain('受理不等于送达');expect(host!.querySelectorAll('tbody tr')).toHaveLength(20);click('下一页');await nextTick();click('下一页');await nextTick();expect(host!.querySelectorAll('tbody tr')).toHaveLength(5);const filter=host!.querySelector('select')!;filter.value='WECHAT_WORK';filter.dispatchEvent(new Event('change'));await nextTick();expect(host!.textContent).toContain('第 1 / 1 页');expect(host!.textContent).toContain('当前筛选没有业务通知记录')})
it('labels stale snapshot as historical and keeps cancel separate from close',async()=>{props.state.current=acceptanceSample();props.expired=true;await mount();expect(host!.textContent).toContain('快照已超过5分钟');props.state.busy=true;await nextTick();click('停止等待');click('关闭明细');expect(cancel).toHaveBeenCalledOnce();expect(close).toHaveBeenCalledOnce();expect(refresh).not.toHaveBeenCalled()})
