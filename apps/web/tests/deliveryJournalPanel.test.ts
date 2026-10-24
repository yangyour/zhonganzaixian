// @vitest-environment jsdom
import {createApp,h,nextTick,type App} from 'vue'
import ElementPlus from 'element-plus'
import {it,expect,vi,afterEach} from 'vitest'
import Panel from '../src/components/DeliveryJournalPanel.vue'
import {api,ApiError} from '../src/api/client'
import {session,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
import {deliveryScope as s,deliveryPage,deliveryDetail,deliveryRecovery} from './deliveryJournal.fixture'
let app:App|undefined,host:HTMLElement|undefined
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
async function mount(){session.value={user:{id:s.actor,role:'padmin',display_name:'Synthetic'},tenant:{id:s.tenant,name:'Synthetic'},allowed_tenants:[],capabilities:['ticket_notes:read_internal','audit:read'],permission_version:1,account_version:1,mode:'remote_test',identity_source:'synthetic',csrf_token:'UNIT_ONLY'} as Session;switchingTenant.value=false;host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel)});app.use(ElementPlus);app.mount(host);await settle()}
function click(text:string){const b=[...host!.querySelectorAll('button')].find(b=>b.textContent===text);expect(b).toBeTruthy();b!.click()}
afterEach(()=>{app?.unmount();host?.remove();session.value=null;vi.restoreAllMocks()})
it('manual list detail and original command recovery never use a send action',async()=>{const request=vi.spyOn(api,'request').mockResolvedValueOnce(deliveryPage()).mockResolvedValueOnce(deliveryDetail()).mockResolvedValueOnce(deliveryRecovery());await mount();expect(request).not.toHaveBeenCalled();click('读取日志');await settle();expect(host!.textContent).toContain('结果不明');click('查看历史');await settle();click('只读核对原命令');await settle();expect(request.mock.calls[2]![0]).toBe(`/notification-deliveries/${s.id}/commands/${s.command}`);expect(host!.textContent).toContain('不授权重新发送');expect(request.mock.calls.every(c=>c[1]?.method==='GET')).toBe(true);expect(host!.textContent).not.toContain('重新发送按钮')})
it('recorded delivered remains explicitly uncertified',async()=>{const page=deliveryPage();page.items[0]!.recorded_status='DELIVERED';vi.spyOn(api,'request').mockResolvedValue(page);await mount();click('读取日志');await settle();expect(host!.textContent).toContain('送达声明，尚未认证');expect(host!.textContent).not.toContain('已认证送达')})
it('missing configuration is an error not empty success and retains retry',async()=>{vi.spyOn(api,'request').mockRejectedValue(new ApiError(503,'SCOPE_PENDING','未配置','unit'));await mount();click('读取日志');await settle();expect(host!.textContent).toContain('请勿据此重新发送');expect(host!.textContent).not.toContain('当前页没有本地日志');expect([...host!.querySelectorAll('button')].some(b=>b.textContent==='重试原读取')).toBe(true)})
it('account revision clears visible results and aborts delayed reads',async()=>{let finish!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(resolve=>finish=resolve));await mount();click('读取日志');await settle();session.value={...session.value!,account_version:2};await settle();expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true);finish(deliveryPage());await settle();expect(host!.querySelector('table')).toBeNull()})
it('loss of audit privilege removes the workspace',async()=>{vi.spyOn(api,'request').mockResolvedValue(deliveryPage());await mount();click('读取日志');await settle();session.value={...session.value!,capabilities:['ticket_notes:read_internal']};await settle();expect(host!.querySelector('section')).toBeNull()})
