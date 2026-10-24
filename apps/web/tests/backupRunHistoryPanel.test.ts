// @vitest-environment jsdom
import {createApp,h,nextTick,type App} from 'vue'
import ElementPlus from 'element-plus'
import {it,expect,vi,afterEach} from 'vitest'
import Panel from '../src/components/BackupRunHistoryPanel.vue'
import {api,ApiError} from '../src/api/client'
import {session,switchingTenant} from '../src/stores/session'
import type {Asset,Session} from '../src/api/types'
import {historyScope,runPage} from './backupRunHistory.fixture'
let app:App|undefined,host:HTMLElement|undefined
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
async function mount(){session.value={user:{id:historyScope.actorId,role:'padmin',display_name:'Synthetic'},tenant:{id:historyScope.tenantId,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read'],permission_version:1,account_version:1,mode:'remote_test',identity_source:'synthetic',csrf_token:'UNIT_ONLY'} as Session;switchingTenant.value=false;host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,{asset:{id:historyScope.assetId,tenant_id:historyScope.tenantId,version:1} as Asset})});app.use(ElementPlus);app.mount(host);await settle()}
function click(text:string){const button=[...host!.querySelectorAll('button')].find(b=>b.textContent===text);expect(button).toBeTruthy();button!.click()}
afterEach(()=>{app?.unmount();host?.remove();session.value=null;vi.restoreAllMocks()})
it('does not query until explicitly requested and exposes expandable attempts',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(runPage());await mount();expect(request).not.toHaveBeenCalled();click('读取运行历史');await settle();expect(request.mock.calls[0]![0]).toBe(`/assets/${historyScope.assetId}/backup-runs?page_size=20`);expect(host!.querySelectorAll('tbody tr')).toHaveLength(1);expect(host!.querySelector('details')).not.toBeNull();expect(host!.textContent).toContain('尚无终局');expect(host!.textContent).toContain('自动P3建单未启用')})
it('missing grant is a recoverable error not empty success',async()=>{vi.spyOn(api,'request').mockRejectedValue(new ApiError(403,'BACKUP_RUN_HISTORY_FORBIDDEN','Synthetic denial','unit'));await mount();click('读取运行历史');await settle();expect(host!.textContent).toContain('独立的运行历史授权');expect(host!.textContent).not.toContain('本页没有运行记录');expect([...host!.querySelectorAll('button')].some(b=>b.textContent==='重试原页')).toBe(true)})
it('account version change aborts and clears even with same tenant and role',async()=>{let finish!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(resolve=>finish=resolve));await mount();click('读取运行历史');await settle();session.value={...session.value!,account_version:2};await settle();expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true);finish(runPage());await settle();expect(host!.querySelector('table')).toBeNull()})
it('customer role cannot retain internal run facts',async()=>{vi.spyOn(api,'request').mockResolvedValue(runPage());await mount();click('读取运行历史');await settle();session.value={...session.value!,user:{...session.value!.user,role:'tadmin'}};await settle();expect(host!.querySelector('section')).toBeNull()})
