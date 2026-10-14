// @vitest-environment jsdom
// Execute the actual SFC setup/watchers with a null render. This is not browser/visual acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import { context, id, page, ticket } from './ticketReader.fixture'
const mocks = vi.hoisted(() => ({ query: {} as Record<string,string>, replace: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: mocks.query }), useRouter: () => ({ replace: mocks.replace }), onBeforeRouteLeave: vi.fn() }))
import TicketsPage from '../src/pages/TicketsPage.vue'
import { api, ApiError } from '../src/api/client'
import { session, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
let app: App | undefined, host: HTMLElement, state: any
async function settle() { for (let i=0;i<8;i++) { await Promise.resolve(); await nextTick() } }
function mount() { host=document.createElement('div');document.body.appendChild(host);app=createApp({ ...TicketsPage, render: () => null }); const vm=app.mount(host); state=(vm.$ as unknown as {setupState: any}).setupState }
const auth: Session={user:{id:context.actorId,role:'msp',display_name:'合成内部用户'},tenant:{id:context.tenantId,name:'合成租户'},allowed_tenants:[],capabilities:['tickets:read','ticket_comments:read'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
beforeEach(() => { session.value=structuredClone(auth);contextGeneration.value=0;mocks.query=reactive({});mocks.replace.mockReset() })
afterEach(() => { app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks() })
function request() { return vi.spyOn(api,'request').mockImplementation(async url => { if(url.startsWith('/tickets?')) return page(); if(url.startsWith('/tickets/')) return ticket(); throw new ApiError(503,'UPSTREAM_UNAVAILABLE','synthetic operation read unavailable','synthetic') }) }
it('loads only validated detached list and detail values', async () => { const raw=page(), detail=ticket();const mock=request();mock.mockImplementation(async url=>url.startsWith('/tickets?')?raw: url.startsWith('/tickets/')?detail:Promise.reject(new ApiError(503,'UPSTREAM_UNAVAILABLE','synthetic','test')));mocks.query.ticket_id=id(10);mount();await settle();expect(state.rows).toHaveLength(1);expect(state.ticket.id).toBe(id(10));raw.items[0]!.title='changed';detail.history[0]!.actor_id=id(99);expect(state.rows[0].title).toBe('合成报障');expect(state.ticket.history[0].actor_id).toBe(id(2));expect(mock.mock.calls.every(([,options])=>!options?.method || options.method==='GET')).toBe(true) })
it('invalid refresh clears previous rows and disables pagination', async () => { const mock=request();mount();await settle();mock.mockResolvedValueOnce({...page(),source:'fixture'});await state.loadTickets();expect(state.rows).toEqual([]);expect(state.nextCursor).toBeNull();expect(state.hasMore).toBe(false);expect(state.error.code).toBe('INVALID_TICKET_READ_RESPONSE') })
it('HTTP failure remains an error without stale list values', async () => {const mock=request();mount();await settle();mock.mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','synthetic unavailable','test'));await state.loadTickets();expect(state.rows).toEqual([]);expect(state.error.status).toBe(503);expect(state.loading).toBe(false)})
it('wrong detail ID and invented formal state never reach detail or photo workspace', async () => {const mock=request();mount();await settle();mock.mockResolvedValueOnce(ticket(11));await state.loadDetail(id(10));expect(state.ticket).toBeNull();expect(state.detailError.code).toBe('INVALID_TICKET_READ_RESPONSE');mock.mockResolvedValueOnce({...ticket(),state:'RESOLVED'});await state.loadDetail(id(10));expect(state.ticket).toBeNull();expect(state.detailLoading).toBe(false)})
it('newer list result wins when earlier transport ignores abort', async () => {const mock=request();let finish!:(v:unknown)=>void;mock.mockImplementationOnce(()=>new Promise(r=>{finish=r}));mount();await settle();await state.loadTickets();finish(page([ticket(11)]));await settle();expect(state.rows[0].id).toBe(id(10));expect(mock.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('closing detail ignores late response and never starts an operation read', async () => {const mock=request();mount();await settle();let finish!:(v:unknown)=>void;mock.mockImplementationOnce(()=>new Promise(r=>{finish=r}));const pending=state.loadDetail(id(10));await state.loadDetail('');finish(ticket());await pending;await settle();expect(state.ticket).toBeNull();expect(state.detailLoading).toBe(false);expect(mock.mock.calls.filter(([url])=>url.startsWith('/operations/'))).toHaveLength(0)})
it('tenant change clears old list, detail and retained comments even without parent remount', async () => {request();mount();await settle();await state.loadDetail(id(10));state.openComments();expect(state.commentTarget.id).toBe(id(10));session.value={...auth,tenant:{id:id(99),name:'新合成租户'}};await settle();expect(state.rows).toEqual([]);expect(state.ticket).toBeNull();expect(state.commentTarget).toBeNull();expect(state.commentOpen).toBe(false)})
it('permission epoch reset aborts pending reads and discards old payload and error', async () => {const mock=request();let finish!:(v:unknown)=>void;mock.mockImplementationOnce(()=>new Promise(r=>{finish=r}));mount();await settle();contextGeneration.value++;await settle();finish(page());await settle();expect(state.rows).toEqual([]);expect(state.error).toBeNull();expect(state.loading).toBe(false);expect(mock.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('filter and page size are captured before delayed HTTP completion', async () => {const mock=request();mount();await settle();let finish!:(v:unknown)=>void;mock.mockImplementationOnce(()=>new Promise(r=>{finish=r}));mocks.query.category='其他';await settle();finish(page());await settle();expect(state.rows).toEqual([]);expect(state.error.code).toBe('INVALID_TICKET_READ_RESPONSE')})
it('unmount aborts list and detail without any automatic writes', async () => {const mock=vi.spyOn(api,'request').mockImplementation(()=>new Promise(()=>{}));mocks.query.ticket_id=id(10);mount();await settle();const calls=[...mock.mock.calls];app!.unmount();app=undefined;expect(calls.length).toBe(2);expect(calls.every(([,options])=>options?.signal?.aborted)).toBe(true);expect(calls.every(([,options])=>!options?.body&&!options?.method)).toBe(true)})
it('cancelled worklog switch retains the original target and restores its collapsed editor', async () => {
  request(); session.value!.capabilities.push('worklogs:read_own'); mount(); await settle()
  state.ticket=ticket(); await state.openWorklogs(); state.worklogOpen=false
  const canLeave=vi.fn().mockResolvedValue(false); state.worklogWorkspace={canLeave}; state.ticket=ticket(11)
  await state.openWorklogs(); expect(canLeave).toHaveBeenCalledWith('切换报障登记')
  expect(state.worklogTarget.id).toBe(id(10)); expect(state.worklogOpen).toBe(true)
})
it('worklog target changes only after approval and the same target reopens without confirmation', async () => {
  request(); session.value!.capabilities.push('worklogs:read_own'); mount(); await settle()
  state.ticket=ticket(); await state.openWorklogs()
  const canLeave=vi.fn().mockResolvedValue(true); state.worklogWorkspace={canLeave}; state.worklogOpen=false
  await state.openWorklogs(); expect(canLeave).not.toHaveBeenCalled(); expect(state.worklogOpen).toBe(true)
  let approve!:(value:boolean)=>void; canLeave.mockImplementationOnce(()=>new Promise(r=>{approve=r})); state.ticket=ticket(11)
  const switching=state.openWorklogs(); expect(state.worklogTarget.id).toBe(id(10)); approve(true); await switching
  expect(state.worklogTarget.id).toBe(id(11)); expect(state.worklogOpen).toBe(true)
})
it.each(['context', 'detail'])('late worklog confirmation cannot restore an obsolete %s target', async changed => {
  request(); session.value!.capabilities.push('worklogs:read_own'); mount(); await settle()
  state.ticket=ticket(); await state.openWorklogs(); state.worklogOpen=false
  let approve!:(value:boolean)=>void; state.worklogWorkspace={canLeave:()=>new Promise(r=>{approve=r})}; state.ticket=ticket(11)
  const switching=state.openWorklogs()
  if(changed==='context')contextGeneration.value++; else state.ticket=ticket(12)
  await settle(); approve(true); await switching
  expect(state.worklogTarget?.id??null).toBe(changed==='context'?null:id(10)); expect(state.worklogOpen).toBe(false)
})
