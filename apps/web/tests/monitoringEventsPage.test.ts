// @vitest-environment jsdom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Page from '../src/pages/MonitoringEventsPage.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { contextTransitionGuards } from '../src/utils/contextTransitionGuard'
import { context, sampleWorkspace } from './monitoringEventControllerFixtures'

const routeGuard = vi.hoisted(() => ({ leave: undefined as undefined | (() => boolean), update: undefined as undefined | (() => boolean) }))
vi.mock('vue-router', async original => ({ ...await original<typeof import('vue-router')>(), onBeforeRouteLeave: (guard: () => boolean) => { routeGuard.leave = guard }, onBeforeRouteUpdate: (guard: () => boolean) => { routeGuard.update = guard }, useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
const auth: Session = {
  user: { id: context.actorId, display_name: '合成测试管理员', role: 'padmin' },
  tenant: { id: context.tenantId, name: '合成测试租户' }, allowed_tenants: [],
  capabilities: ['tickets:read', 'ticket_notes:write_internal', 'configurations:write'],
  permission_version: 3, csrf_token: 'SYNTHETIC-D15', mode: 'remote_test', identity_source: 'project_local',
}
let app: App | undefined, host: HTMLElement
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
function mount() { host = document.createElement('div'); document.body.appendChild(host); app = createApp(Page); app.mount(host) }
const buttons = (label: string) => [...host.querySelectorAll('button')].filter(b => b.textContent?.trim() === label)
const button = (label: string) => { const b = buttons(label)[0]; expect(b, label).toBeDefined(); return b! }
async function input(id: string, value: string) { const field = document.getElementById(id) as HTMLInputElement; expect(field).not.toBeNull(); field.value = value; field.dispatchEvent(new Event('input', { bubbles: true })); await settle() }
function ready() {
  const data = sampleWorkspace()
  const request = vi.spyOn(api, 'request').mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test')
    return structuredClone(data)
  })
  return { data, request }
}
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0 })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

describe('D15 event page uses controller states and explicit human inputs', () => {
  it('labels workspace sub-records in Chinese while keeping every original code in the badge title', async () => {
    const { data } = ready()
    data.quarantine_items[0]!.quarantine_reason = 'CROSS_TENANT_CLAIM'
    mount(); await settle()
    const label = (code: string) => host.querySelector(`.event-sub-card .mop-badge[title="${code}"]`)?.textContent?.trim()
    expect(['ACTIVE', 'SCHEDULED', 'ENDED'].map(label)).toEqual(['进行中', '已计划', '已结束'])
    expect(['CROSS_TENANT_CLAIM', 'UNKNOWN_MAPPING'].map(label)).toEqual(['跨租户声明', '来源未映射'])
    expect(['BIND_ASSET', 'DISCARD'].map(label)).toEqual(['处置：已绑定资产', '处置：已丢弃'])
    expect(['SUCCEEDED', 'RETRY_SCHEDULED', 'QUARANTINED_MANUAL_REQUIRED', 'MANUAL_CREATED', 'SUPPRESSED_SKIPPED'].map(label)).toEqual(['已登记', '等待重试', '待人工补建', '已人工补建', '已抑制跳过'])
    expect(host.textContent).toContain('采集继续 · 抑制通知')
    const shown = [...host.querySelectorAll('.event-sub-card .mop-badge')].map(b => b.textContent?.trim() ?? '')
    expect(shown.length).toBeGreaterThan(10)
    expect(shown.filter(text => /[A-Z]{3,}_[A-Z_]+/.test(text))).toEqual([])
  })
  it('mounts real-shaped data with no writes, exposes legal transitions and keeps empty root causes disabled', async () => {
    const { request } = ready(); mount(); await settle()
    expect(host.querySelector('[title="PENDING_REVIEW"]')?.textContent).toBe('待复核')
    expect(host.querySelector('[title="INFO_DEFAULT_SILENT"]')?.textContent).toBe('信息级静默')
    expect(host.textContent).not.toContain('已定位根因并处理')
    expect(host.textContent).not.toContain('已确认生效')
    expect(host.textContent).toContain('已记录管理员确认')
    expect(host.textContent).toContain('抖动静默：5分钟')
    expect(buttons('确认解决').length).toBeGreaterThan(0)
    expect(buttons('确认解决').every(b => b.disabled)).toBe(true)
    expect(buttons('开始处理').length).toBeGreaterThan(0)
    expect(buttons('关闭事件').length).toBeGreaterThan(0)
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0]![0]).toBe('/events?window=24h&page_size=25')
  })
  it('requires at least twenty entered root-cause characters and sends the selected version exactly', async () => {
    const { data, request } = ready(), e = data.events.find(e => e.handling_state === 'PENDING_REVIEW')!
    mount(); await settle()
    await input(`root-cause-${e.id}`, '很短'); expect(button('确认解决').disabled).toBe(true)
    await input(`root-cause-${e.id}`, '人工复核光模块故障原因并完成更换，持续观察确认网络恢复稳定'); expect(button('确认解决').disabled).toBe(false)
    button('确认解决').click(); await settle()
    const post = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    expect(post[0]).toBe(`/events/${e.id}/transitions`)
    expect(post[1]?.body).toEqual({ expected_version: e.version, action: 'RESOLVE', root_cause_note: '人工复核光模块故障原因并完成更换，持续观察确认网络恢复稳定' })
    expect(post[1]?.idempotencyKey).toBeTruthy()
    expect(host.textContent).toContain('原操作结果尚未确认')
  })
  it('keeps root-cause drafts scoped to their event, not shared across rows', async () => {
    const { data, request } = ready(), e = data.events.find(e => e.handling_state === 'PENDING_REVIEW')!
    const other = { ...e, id: 'ffffffff-ffff-4fff-8fff-ffffffffffff', rule_name: '另一个待复核事件' }
    data.events.push(other); mount(); await settle()
    await input(`root-cause-${e.id}`, '仅确认第一个事件原因')
    const otherField = document.getElementById(`root-cause-${other.id}`) as HTMLTextAreaElement
    expect(otherField.value).toBe(''); expect(otherField.closest('details')!.querySelector<HTMLButtonElement>('button')!.disabled).toBe(true)
    expect(request).toHaveBeenCalledTimes(1)
  })
  it.each(['START', 'MARK_PENDING_REVIEW', 'CLOSE'] as const)('sends actual %s transition token from an explicit click', async action => {
    const { data, request } = ready(); if(action==='MARK_PENDING_REVIEW'){const e=data.events.find(e=>e.handling_state==='IN_PROGRESS')!;e.monitor_state='RECOVERED';e.recovered_at=e.last_occurred_at} mount(); await settle()
    button({ START: '开始处理', MARK_PENDING_REVIEW: '转为待复核', CLOSE: '关闭事件' }[action]).click(); await settle()
    const post = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    expect(post[0]).toMatch(/^\/events\/[^/]+\/transitions$/)
    expect(post[1]?.body).toMatchObject({ action, root_cause_note: null })
  })
  it.each(['BIND_ASSET', 'DISCARD'] as const)('requires explicit quarantine inputs and sends %s', async decision => {
    const { data, request } = ready(), q = data.quarantine_items.find(q => q.decision === null)!
    mount(); await settle(); expect(button(decision === 'BIND_ASSET' ? '绑定资产并回灌' : '丢弃隔离事件').disabled).toBe(true)
    await input(`quarantine-reason-${q.quarantine_id}`, '人工核验来源与资产')
    if (decision === 'BIND_ASSET') await input(`quarantine-asset-${q.quarantine_id}`, data.events[0]!.asset_id)
    button(decision === 'BIND_ASSET' ? '绑定资产并回灌' : '丢弃隔离事件').click(); await settle()
    const post = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    expect(post[0]).toBe(`/monitoring/quarantine/${q.quarantine_id}/decisions`)
    expect(post[1]?.body).toEqual({ decision, bound_asset_id: decision === 'BIND_ASSET' ? data.events[0]!.asset_id : null, reason: '人工核验来源与资产' })
  })
  it('enables maintenance extension only for a later valid UTC timestamp and uses END for early ending', async () => {
    const { data, request } = ready(), w = data.maintenance_windows.find(w => ['ACTIVE', 'SCHEDULED'].includes(w.status))!
    mount(); await settle()
    await input(`window-end-${w.id}`, 'invalid'); expect(button('延长维护').disabled).toBe(true)
    await input(`window-end-${w.id}`, w.ends_at); expect(button('延长维护').disabled).toBe(true)
    await input(`window-end-${w.id}`, '2099-10-19T10:00:00.123456789Z'); expect(button('延长维护').disabled).toBe(true)
    expect(host.textContent).toContain('最多毫秒精度')
    await input(`window-end-${w.id}`, '2099-10-19T10:00:00.123Z'); expect(button('延长维护').disabled).toBe(false)
    button('结束维护').click(); await settle()
    const post = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    expect(post[0]).toBe(`/maintenance-windows/${w.id}/actions`)
    expect(post[1]?.body).toEqual({ expected_version: w.version, action: 'END', new_ends_at: null })
  })
  it('requires an entered manual ticket reason instead of manufacturing one', async () => {
    const { data, request } = ready(), attempt = data.autoticket_attempts.find(a => ['RETRY_SCHEDULED', 'QUARANTINED_MANUAL_REQUIRED'].includes(a.status))!
    mount(); await settle(); expect(buttons('人工补建工单').every(b => b.disabled)).toBe(true)
    expect(host.textContent).toContain('人工补建原因（最多492个字符）')
    await input(`manual-ticket-reason-${attempt.id}`, '因'.repeat(493)); expect(buttons('人工补建工单').every(b => b.disabled)).toBe(true)
    await input(`manual-ticket-reason-${attempt.id}`, '已核验隔离失败需要人工补建')
    buttons('人工补建工单').find(b => !b.disabled)!.click(); await settle()
    const post = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    expect(post[0]).toBe(`/autoticket-attempts/${attempt.id}/manual-create`)
    expect(post[1]?.body).toEqual({ expected_version: attempt.version, reason: '已核验隔离失败需要人工补建' })
  })
})

describe('D15 page permissions and interrupted flows', () => {
  it.each([
    { ...auth, capabilities: [] },
    { ...auth, user: { ...auth.user, role: 'viewer' } },
  ] as Session[])('denies reads and writes for a session without internal read access', async s => {
    session.value = s; const { request } = ready(); mount(); await settle()
    expect(request).not.toHaveBeenCalled(); expect(host.textContent).toContain('当前没有监控事件读取权限')
  })
  it('requires configurations:write and padmin for SLA confirmation independently of note-writing', async () => {
    session.value = { ...auth, capabilities: ['tickets:read', 'configurations:write'] }
    const { request } = ready(); mount(); await settle()
    expect(buttons('确认解决')).toHaveLength(0); expect(buttons('管理员确认SLA排除').length).toBeGreaterThan(0)
    button('管理员确认SLA排除').click(); await settle()
    expect(request.mock.calls.find(([, o]) => o?.method === 'POST')?.[1]?.body).toMatchObject({ action: 'CONFIRM_SLA_EXCLUSION', new_ends_at: null })
  })
  it('hides SLA confirmation without the configuration capability', async () => {
    session.value = { ...auth, capabilities: ['tickets:read', 'ticket_notes:write_internal'] }
    ready(); mount(); await settle(); expect(buttons('管理员确认SLA排除')).toHaveLength(0)
  })
  it('locks read/write controls, voluntary route/context transitions and beforeunload while a result is unknown', async () => {
    const { data, request } = ready(), e = data.events.find(e => e.handling_state === 'PENDING_REVIEW')!
    mount(); await settle(); await input(`root-cause-${e.id}`, '明确人工复核设备故障根因并完成修复，持续观察确认网络恢复稳定')
    button('确认解决').click(); await settle()
    expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toHaveLength(1)
    expect(button('刷新事件').disabled).toBe(true); expect((document.getElementById(`root-cause-${e.id}`) as HTMLTextAreaElement).disabled).toBe(true)
    expect(routeGuard.leave?.()).toBe(false); expect(routeGuard.update?.()).toBe(false); expect(await contextTransitionGuards.approve()).toBe(false)
    const unload = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(unload); expect(unload.defaultPrevented).toBe(true)
    const original = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    button('原请求与幂等键重试').click(); await settle()
    const posts = request.mock.calls.filter(([, o]) => o?.method === 'POST'); expect(posts).toHaveLength(2)
    expect(posts[1]).toEqual(original)
  })
  it.each(['switchingTenant', 'permissionVersion', 'contextGeneration'] as const)('clears inputs/data and discards late response on %s', async change => {
    const { data, request } = ready(); let finish!: (v: unknown) => void
    request.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    mount(); await settle(); const first = finish, signal = request.mock.calls[0]![1]!.signal!
    if (change === 'switchingTenant') switchingTenant.value = true
    if (change === 'permissionVersion') session.value = { ...auth, permission_version: 4 }
    if (change === 'contextGeneration') contextGeneration.value++
    await settle(); expect(signal.aborted).toBe(true); first(data); await settle()
    expect(host.querySelector('.event-table-scroll')).toBeNull()
    expect(host.querySelector('textarea')).toBeNull()
  })
  it('clears entered root cause when permission scope changes', async () => {
    const { data } = ready(), e = data.events.find(e => e.handling_state === 'PENDING_REVIEW')!
    mount(); await settle(); await input(`root-cause-${e.id}`, '不能带入新权限范围的根因')
    session.value = { ...auth, permission_version: 4 }; await settle()
    expect((document.getElementById(`root-cause-${e.id}`) as HTMLTextAreaElement).value).toBe('')
  })
  it('unmount aborts the pending read and removes transition guard', async () => {
    const { request } = ready(); request.mockImplementation(() => new Promise(() => {})); mount(); await settle()
    const signal = request.mock.calls[0]![1]!.signal!; app!.unmount(); app = undefined
    expect(signal.aborted).toBe(true); expect(await contextTransitionGuards.approve()).toBe(true)
  })
})

it('manual creation CAS conflict preserves entered reason and never upgrades or retries the request automatically',async()=>{
 const {data,request}=ready(),attempt=data.autoticket_attempts.find(a=>['RETRY_SCHEDULED','QUARANTINED_MANUAL_REQUIRED'].includes(a.status))!
 mount();await settle();const reason='已核验当前告警，需要人工补建并保留此说明'
 await input(`manual-ticket-reason-${attempt.id}`,reason)
 request.mockRejectedValueOnce(new ApiError(409,'VERSION_CONFLICT','自动建单尝试版本已变化，本次事务未提交','SYNTHETIC_CAS'))
 buttons('人工补建工单').find(b=>!b.disabled)!.click();await settle()
 expect((document.getElementById(`manual-ticket-reason-${attempt.id}`) as HTMLInputElement).value).toBe(reason)
 const posts=request.mock.calls.filter(([,o])=>o?.method==='POST');expect(posts).toHaveLength(1)
 expect(posts[0]![1]?.body).toEqual({expected_version:attempt.version,reason})
 expect(request).toHaveBeenCalledTimes(2)
})

it('shows stored retry deadlines as plans rather than promising execution or delivery',async()=>{
 const {data}=ready();mount();await settle()
 for(const attempt of data.autoticket_attempts.filter(a=>a.next_retry_at))expect(host.querySelector(`time[datetime="${attempt.next_retry_at}"]`)).not.toBeNull()
 expect(host.textContent).toContain('是否执行取决于 Worker 配置')
 expect(host.textContent).toContain('登记已保存，不等于正式工单流程已启动或外部通知已送达')
})
it('puts events first in a full-width workspace and removes funnel statistics',async()=>{ready();mount();await settle();expect(host.querySelector('.workspace-grid,.kpi-list')).toBeNull();expect(host.querySelector('.event-workspace>section')?.getAttribute('aria-label')).toBe('监控事件主列表');const scroll=host.querySelector<HTMLElement>('.event-table-scroll')!;expect(scroll.tabIndex).toBe(0);scroll.focus();expect(document.activeElement).toBe(scroll);expect(scroll.querySelectorAll('th[scope="col"]')).toHaveLength(7);expect(button('刷新事件').disabled).toBe(false)})
it('refresh remains read-only after removing funnel controls',async()=>{const {request}=ready();mount();await settle();button('刷新事件').click();await settle();expect(request).toHaveBeenCalledTimes(2);expect(request.mock.calls.every(([,o])=>o?.method!=='POST')).toBe(true)})

it('account-version-only changes cancel the old event read and discard its late private rows',async()=>{
 const resolvers:Array<(value:unknown)=>void>=[];const req=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>resolvers.push(r)));
 mount();await settle();expect(req).toHaveBeenCalledTimes(1);session.value={...auth,account_version:2};await settle();expect(req.mock.calls[0]![1]?.signal?.aborted).toBe(true);expect(req).toHaveBeenCalledTimes(2);
 const old=sampleWorkspace();old.events[0]!.rule_name='STALE_ACCOUNT_EVENT';resolvers[0]!(old);await settle();expect(host.textContent).not.toContain('STALE_ACCOUNT_EVENT');resolvers[1]!(sampleWorkspace());await settle();expect(host.textContent).toContain(sampleWorkspace().events[0]!.rule_name);
})
