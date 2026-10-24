// @vitest-environment jsdom
import {createApp,h,nextTick,reactive,type App} from 'vue'
import ElementPlus from 'element-plus'
import {it,expect,vi,afterEach} from 'vitest'
import Panel from '../src/components/ServiceObservationsPanel.vue'
import {emptyServiceState,type ServiceState,type ServiceSnapshot} from '../src/utils/serviceObservations'
import {ApiError} from '../src/api/client'
let app:App|undefined,host:HTMLElement|undefined
const state=reactive<ServiceState>(emptyServiceState())
const access=reactive({permitted:true})
const refresh=vi.fn(),cancel=vi.fn()
async function mount(mode:'health'|'timeline'='health'){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,{state,mode,permitted:access.permitted,onRefresh:refresh,onCancel:cancel})});app.use(ElementPlus);app.component('router-link',{props:['to'],template:'<a><slot /></a>'});app.mount(host);await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();Object.assign(state,emptyServiceState());access.permitted=true;vi.clearAllMocks()})
it('exposes refresh and cancel, and prevents duplicate refresh while pending',async()=>{await mount();const button=host!.querySelector('button')!;button.click();expect(refresh).toHaveBeenCalledTimes(1);state.busy=true;await nextTick();expect(button.disabled).toBe(true);const stop=[...host!.querySelectorAll('button')].find(b=>b.textContent==='停止等待')!;stop.click();expect(cancel).toHaveBeenCalledTimes(1);expect(host!.textContent).toContain('正在读取')})
it('shows cancellation and readable errors, never empty-success on a failed read',async()=>{state.cancelled=true;await mount();expect(host!.textContent).toContain('已停止等待');state.cancelled=false;state.error=new ApiError(503,'SERVICE_MAPPING_PENDING','映射未配置','test');await nextTick();expect(host!.textContent).not.toContain('尚未读取服务观测');expect(host!.querySelector('.service-row')).toBeNull();expect(host!.querySelector('button')!.disabled).toBe(false)})
it('never renders retained facts without permission',async()=>{state.current={services:[{id:'service',name:'SECRET',health_state:'UNKNOWN',assets:[],verified_availability_pct:null,target_pct:null,timeline:[]}],observed_at:'2026-10-24T00:00:00Z'} as unknown as ServiceSnapshot;await mount();expect(host!.textContent).toContain('SECRET');access.permitted=false;await nextTick();expect(host!.textContent).not.toContain('SECRET');expect(host!.textContent).toContain('没有服务观测读取权限');expect(host!.querySelector('button')).toBeNull()})
it('retains explicit unknown timeline and accessible interval details',async()=>{state.current={services:[{id:'service',name:'服务',timeline_included:true,timeline:[{kind:'UNKNOWN',severity:null,start:'2026-10-23T00:00:00Z',end:'2026-10-24T00:00:00Z'}]}],timeline_start:'2026-10-23T00:00:00Z',observed_at:'2026-10-24T00:00:00Z'} as unknown as ServiceSnapshot;await mount('timeline');expect(host!.querySelector('.unknown-track .unknown')).not.toBeNull();expect(host!.querySelector('.fault')).toBeNull();expect(host!.querySelector('details')!.textContent).toContain('数据缺失·记为未知');expect(host!.querySelectorAll('.service-axis span')).toHaveLength(5)})
