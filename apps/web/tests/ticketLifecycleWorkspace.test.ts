import { describe, expect, it, vi } from 'vitest'
import { ApiError, type ApiClient } from '../src/api/client'
import { createTicketLifecycleWorkspace, readLifecycle, lifecycleActions, type LifecycleContext, type LifecycleSnapshot } from '../src/utils/ticketLifecycleWorkspace'
const id = (n: number) => `10000000-0000-0000-0000-${String(n).padStart(12,'0')}`
const scope: LifecycleContext = { tenantId: id(1), ticketId: id(2), actorId: id(3), permissionVersion: 1, customer: false, canWrite: true }
const snapshot = (patch: Partial<LifecycleSnapshot> = {}): LifecycleSnapshot => ({ tenant_id: id(1), ticket_id: id(2), version: 1, state: 'NEW', reopen_count: 0, occurred_at: '2026-10-20T00:00:00Z', resolved_at: null, closed_at: null, response_due_at: '2026-10-20T01:00:00Z', recovery_due_at: '2026-10-20T02:00:00Z', solution_snapshot_hash: null, close_mode: null, assignee_label: null, assigned_engineer_id: null, production_approved: false, notification_status: 'NOT_INTEGRATED', sla_clock_status: 'NOT_INTEGRATED', ...patch })
const envelope = (s: LifecycleSnapshot | null = snapshot()) => ({ lifecycle: s, status: s ? 'TEST_LIFECYCLE' : 'NOT_STARTED', source: 'postgresql', production_approved: false })
function harness(handler: (path: string, options?: { method?: string; body?: unknown; idempotencyKey?: string }) => unknown) {
  const request = vi.fn(async (p: string, o?: { method?: string; body?: unknown; idempotencyKey?: string }) => handler(p,o))
  const workspace = createTicketLifecycleWorkspace({ request } as Pick<ApiClient,'request'>, () => undefined, () => 'stable-request')
  workspace.reset(scope); return { request, workspace }
}
describe('ticket lifecycle transport workspace', () => {
  it('represents not started without inventing state', async () => { const h=harness(()=>envelope(null)); expect(await h.workspace.refresh()).toBe(true);expect(h.workspace.state().snapshot).toBeNull();expect(h.workspace.state().loaded).toBe(true) })
  it('rejects cross tenant and extra identity data',()=>{ for(const patch of [{tenant_id:id(9)},{ticket_id:id(9)},{assigned_engineer_id:'bad'},{version:0}]) expect(()=>readLifecycle(envelope(snapshot(patch)),scope)).toThrow();expect(()=>readLifecycle({...envelope(),actor_id:id(3)},scope)).toThrow() })
  it('hides engineer identity for customer',()=>{expect(()=>readLifecycle(envelope(snapshot({assigned_engineer_id:id(3)})),{...scope,customer:true})).toThrow()})
  it('does not accept claimed production or delivery success',()=>{ for(const patch of [{production_approved:true},{notification_status:'DELIVERED'},{sla_clock_status:'RUNNING'}])expect(()=>readLifecycle(envelope({...snapshot(),...patch} as LifecycleSnapshot),scope)).toThrow()})
  it('only exposes legal role and state actions',()=>{expect(lifecycleActions(snapshot(),scope)).toEqual(['accept','transfer']);expect(lifecycleActions(snapshot(),{...scope,customer:true})).toEqual([]);expect(lifecycleActions(snapshot(),{...scope,canWrite:false})).toEqual([])})
  it('submits current version with an idempotency key and reads back',async()=>{let current=snapshot();const h=harness((p,o)=>{if(o?.method)current=snapshot({version:2,state:'IN_PROGRESS'});return envelope(current)});await h.workspace.refresh();expect(await h.workspace.submit('accept')).toBe(true);const call=h.request.mock.calls.find(([,o])=>o?.method)!;expect(call[1]).toMatchObject({body:{expected_version:1},idempotencyKey:'stable-request'});expect(h.workspace.state().snapshot?.version).toBe(2)})
  it('uncertain failure retains exact action, body, key and refuses new writes',async()=>{let count=0;const h=harness((p,o)=>{if(!o?.method)return envelope();count++;throw new ApiError(503,'UPSTREAM_UNAVAILABLE','failure','test')});await h.workspace.refresh();expect(await h.workspace.submit('accept')).toBe(false);expect(h.workspace.unresolved()).toBe(true);expect(await h.workspace.submit('transfer','reason',id(4))).toBe(false);await h.workspace.retry();expect(count).toBe(2);const writes=h.request.mock.calls.filter(([,o])=>o?.method);expect(writes[0]![1]).toMatchObject({body:writes[1]![1]!.body,idempotencyKey:writes[1]![1]!.idempotencyKey});expect(writes[0]![0]).toBe(writes[1]![0])})
  it('refresh does not clear an uncertain write',async()=>{const h=harness((p,o)=>{if(o?.method)throw Error('network');return envelope()});await h.workspace.refresh();await h.workspace.submit('accept');await h.workspace.refresh();expect(h.workspace.unresolved()).toBe(true)})
  it('wrong successful transition stays uncertain',async()=>{const h=harness((p,o)=>envelope(o?.method?snapshot({version:2,state:'WAITING_VENDOR'}):snapshot()));await h.workspace.refresh();expect(await h.workspace.submit('accept')).toBe(false);expect(h.workspace.unresolved()).toBe(true)})
  it('refuses rollback after acknowledged command',async()=>{const h=harness((p,o)=>envelope(o?.method?snapshot({version:2,state:'IN_PROGRESS'}):snapshot()));await h.workspace.refresh();expect(await h.workspace.submit('accept')).toBe(true);expect(h.workspace.state().snapshot).toBeNull();expect(h.workspace.state().error).toBeInstanceOf(ApiError)})
  it('rejects stale response after a tenant switch',async()=>{let resolve!: (v:unknown)=>void;const h=harness(()=>new Promise(r=>{resolve=r}));const work=h.workspace.refresh();h.workspace.reset({...scope,tenantId:id(9)});resolve(envelope());expect(await work).toBe(false);expect(h.workspace.state().snapshot).toBeNull()})
  it('rejects stale write after forced expiry',async()=>{let resolve!: (v:unknown)=>void;const h=harness((p,o)=>o?.method?new Promise(r=>{resolve=r}):envelope());await h.workspace.refresh();const work=h.workspace.submit('accept');h.workspace.reset(null);resolve(envelope(snapshot({version:2,state:'IN_PROGRESS'})));expect(await work).toBe(false);expect(h.workspace.state().attempt).toBeNull()})
  it('readonly context never submits or retries',async()=>{const h=harness(()=>envelope());h.workspace.reset({...scope,canWrite:false});await h.workspace.refresh();expect(await h.workspace.submit('accept')).toBe(false);expect(await h.workspace.retry()).toBe(false);expect(h.request).toHaveBeenCalledTimes(1)})
  it('invalid reason and engineer inputs never send',async()=>{const h=harness(()=>envelope());await h.workspace.refresh();await expect(h.workspace.submit('transfer','',id(4))).rejects.toThrow();await expect(h.workspace.submit('transfer','reason','not-a-uuid')).rejects.toThrow();expect(h.request).toHaveBeenCalledTimes(1)})
  it('requires matching solution content before customer confirmation',async()=>{const pending=snapshot({state:'PENDING_CUSTOMER',version:2,resolved_at:'2026-10-20T00:00:00Z',solution_snapshot_hash:'a'.repeat(64)});const h=harness(p=>p.includes('/solution?')?{tenant_id:id(1),ticket_id:id(2),lifecycle_version:2,resolved_at:pending.resolved_at,ticket_summary:'故障',solution:'这是一段用于测试的二十字以上解决方案说明，请客户核对。',snapshot_hash:'b'.repeat(64),engineer_label:'工程师',source:'postgresql',production_approved:false}:envelope(pending));h.workspace.reset({...scope,customer:true});expect(await h.workspace.refresh()).toBe(false);expect(await h.workspace.submit('confirm')).toBe(false);expect(h.request.mock.calls.some(([,o])=>o?.method)).toBe(false)})
})

// Static wiring guards complement isolated transport tests; not browser acceptance.
it('detail close, same-route navigation and refresh retain uncertain lifecycle requests', async () => {
  const { readFileSync } = await import('node:fs')
  const page=readFileSync(new URL('../src/pages/TicketsPage.vue',import.meta.url),'utf8')
  const overview=readFileSync(new URL('../src/components/TicketDetailOverview.vue',import.meta.url),'utf8')
  const panel=readFileSync(new URL('../src/components/TicketLifecyclePanel.vue',import.meta.url),'utf8')
  expect(page).toContain('onBeforeRouteUpdate(() => detailOverview.value?.canLeave() !== false)')
  expect(page).toContain('if (detailOverview.value?.canLeave() === false) return')
  expect(overview).toContain('defineExpose({ canLeave })')
  expect(panel).toContain('contextTransitionGuards.register(canLeave)')
  expect(panel).toContain("window.addEventListener('beforeunload', beforeUnload)")
  expect(panel).not.toContain('v-html')
})
