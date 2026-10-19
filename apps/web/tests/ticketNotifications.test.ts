import {describe,it,expect,vi} from 'vitest'
import {readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {ApiError} from '../src/api/client'
import {createNotificationWorkspace,readIntentPage,readNotificationPage,type NotificationContext} from '../src/utils/ticketNotifications'

const tenant='55000000-0000-4000-8000-000000000001',actor='55000000-0000-4000-8000-000000000011',id='55000000-0000-4000-8000-000000000099',ticket='55000000-0000-4000-8000-000000000077',other='55000000-0000-4000-8000-000000000088'
const customer:NotificationContext={tenantId:tenant,actorId:actor,permissionVersion:3,internal:false}
const internal:NotificationContext={...customer,internal:true}
const created='2026-10-18T06:00:00Z',observed='2026-10-18T06:00:05Z'
const message=(patch:Record<string,unknown>={})=>({id,ticket_id:ticket,audience:'CUSTOMER',event_type:'ticket.resolved',title:'[R-1] 已解决，待您确认',body:'工程师已提交解决说明，请确认。',created_at:created,version:0,read_at:null,channel:'IN_APP',source:'postgresql',...patch})
const page=(items:unknown[]=[message()],patch:Record<string,unknown>={})=>({items,has_more:false,next_before_created_at:null,next_before_id:null,unread_count:(items as {read_at:unknown}[]).filter(m=>m.read_at===null).length,observed_at:observed,external_dispatch:'NOT_CONFIGURED',source:'postgresql',...patch})
const intent=(patch:Record<string,unknown>={})=>({id,ticket_id:ticket,receipt_no:'R-1',event_type:'ticket.sla_100',phase:'SLA_100',priority:'P1',recipient_status:'PARTIAL',recipient_count:1,pending_reasons:['Q15_ESCALATION_CHAIN'],external_dispatch:'NOT_CONFIGURED',external_blockers:['Q14_CHANNEL_MATRIX','Q18_PROVIDER_CREDENTIALS'],created_at:created,...patch})
const intents=(items:unknown[]=[intent()])=>({items,has_more:false,next_before_created_at:null,next_before_id:null,observed_at:observed,source:'postgresql'})
function setup(scope=customer){const request=vi.fn(),key=vi.fn().mockReturnValue('synthetic-notification-read');const w=createNotificationWorkspace({request},()=>{},key);w.reset(scope);return{w,request,key}}

describe('ticket in-app notifications',()=>{
 it('accepts recipient-owned in-app records and keeps external dispatch explicit',()=>{const p=readNotificationPage(page(),customer);expect(p.items[0]?.channel).toBe('IN_APP');expect(p.external_dispatch).toBe('NOT_CONFIGURED');expect(p.unread_count).toBe(1)})
 it('rejects audience mismatches, fake channels, delivery claims and extra fields',()=>{
  expect(()=>readNotificationPage(page([message({audience:'INTERNAL'})]),customer)).toThrow()
  expect(()=>readNotificationPage(page([message()]),internal)).toThrow()
  for(const patch of [{channel:'WECHAT_WORK'},{source:'fixture'},{event_type:'alert.x'},{title:''},{read_at:created},{version:2},{extra:true}])expect(()=>readNotificationPage(page([message(patch)]),customer)).toThrow()
  expect(()=>readNotificationPage(page([message()],{external_dispatch:'DELIVERED'}),customer)).toThrow()
 })
 it('rejects future records, out-of-order pages, duplicate IDs and read records in unread view',()=>{
  expect(()=>readNotificationPage(page([message({created_at:'2026-10-18T07:00:00Z'})]),customer)).toThrow()
  expect(()=>readNotificationPage(page([message(),message()]),customer)).toThrow()
  expect(()=>readNotificationPage(page([message({id:other,created_at:'2026-10-18T05:00:00Z'}),message()]),customer)).toThrow()
  expect(()=>readNotificationPage(page([message({version:1,read_at:observed})]),customer,undefined,true)).toThrow()
  expect(()=>readNotificationPage(page([message()],{unread_count:0}),customer)).toThrow()
 })
 it('intent ledger requires both external blockers and consistent recipient status',()=>{
  expect(readIntentPage(intents()).items[0]?.pending_reasons).toEqual(['Q15_ESCALATION_CHAIN'])
  for(const patch of [{external_blockers:['Q14_CHANNEL_MATRIX']},{recipient_status:'COMPLETE'},{recipient_status:'PENDING_RULE'},{external_dispatch:'SENT'},{priority:'P9'}])expect(()=>readIntentPage(intents([intent(patch)]))).toThrow()
 })
 it('initial list is GET only with keyset parameters',async()=>{const x=setup();x.request.mockResolvedValue(page());expect(await x.w.list(false)).toBe(true);expect(x.request).toHaveBeenCalledWith('/my/notifications?page_size=20&unread_only=false',expect.objectContaining({signal:expect.any(AbortSignal)}));expect(x.key).not.toHaveBeenCalled()})
 it('unknown mark-read result preserves the exact original request until retried',async()=>{
  const x=setup();x.request.mockResolvedValueOnce(page()).mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','unknown','t')).mockResolvedValueOnce({id,version:1,read_at:observed})
  await x.w.list(false);expect(await x.w.mark(id)).toBe(false);expect(x.w.unresolved()).toBe(true);expect(await x.w.list()).toBe(false);expect(await x.w.retry()).toBe(true)
  const posts=x.request.mock.calls.filter(c=>c[1]?.method==='POST');expect(posts.map(c=>[c[0],c[1].body,c[1].idempotencyKey])).toEqual(Array(2).fill([`/my/notifications/${id}/read`,{expected_version:0},'synthetic-notification-read']))
  expect(x.w.state().page?.items[0]?.read_at).toBe(observed);expect(x.key).toHaveBeenCalledTimes(1)
 })
 it('confirmed rejection clears the pending command; failures never invent empty pages',async()=>{
  const x=setup();x.request.mockResolvedValueOnce(page()).mockRejectedValueOnce(new ApiError(409,'VERSION_CONFLICT','changed','t'));await x.w.list(false);await x.w.mark(id);expect(x.w.unresolved()).toBe(false);expect(x.w.state().receipt).toBeNull()
  const y=setup();const error=new ApiError(503,'UPSTREAM_UNAVAILABLE','down','t');y.request.mockRejectedValue(error);expect(await y.w.list(false)).toBe(false);expect(y.w.state().page).toBeNull();expect(y.w.state().error).toBe(error)
 })
 it('customers cannot load the internal ledger; context switch aborts stale reads',async()=>{
  const x=setup();expect(await x.w.intents()).toBe(false);expect(x.request).not.toHaveBeenCalled()
  let resolveRead!:(v:unknown)=>void;x.request.mockReturnValue(new Promise(r=>{resolveRead=r}));const pending=x.w.list(false);const signal=x.request.mock.calls[0]![1].signal;x.w.reset(null);resolveRead(page());expect(await pending).toBe(false);expect(signal.aborted).toBe(true);expect(x.w.state().page).toBeNull()
  const y=setup(internal);y.request.mockResolvedValue(intents());expect(await y.w.intents()).toBe(true);expect(y.request).toHaveBeenCalledWith('/notification-intents?page_size=25')
 })
 it('route, menu and page separate explicit WeCom delivery from automatic channel policy',()=>{
  const router=readFileSync(resolve('src/router.ts'),'utf8'),nav=readFileSync(resolve('src/utils/navigationModules.ts'),'utf8'),source=readFileSync(resolve('src/pages/TicketNotificationsPage.vue'),'utf8')
  expect(router).toContain("path: '/ticket-notifications'");expect(router).toMatch(/ticket-notifications.*capability: 'tickets:read'/);expect(nav).toContain("['ticket-notifications','工单通知']")
  expect(source).toContain('WeComNotificationAction');expect(source).toContain('自动渠道策略仍按配置生效');expect(source).toContain('接口受理与实际收到分别记录')
 })
})
