<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createDashboardWorkspace, emptyDashboardState } from '../utils/dashboardWorkspace'
import { dashboardWindows, type DashboardWindow } from '../utils/dashboardAvailability'
import { emptyActionableTickets, type ActionableTicketState } from '../utils/dashboardActionableTickets'
import {createEventMetricReader,emptyEventMetrics,elapsedMetricLabel,type EventMetricSnapshot} from '../utils/eventOperationalMetrics'
import { clockTime, displayTime } from '../utils/display'
import { routeAllowed } from '../utils/routeAccess'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import LiveMonitoringPanel from '../components/LiveMonitoringPanel.vue'
import DashboardEventsPanel from '../components/DashboardEventsPanel.vue'
import EventNoiseTrendPanel from '../components/EventNoiseTrendPanel.vue'
import DashboardWorkflowsPanel from '../components/DashboardWorkflowsPanel.vue'
import ServiceObservationsPanel from '../components/ServiceObservationsPanel.vue'
import {createServiceReader,emptyServiceState} from '../utils/serviceObservations'

const serviceState=shallowRef(emptyServiceState())
const serviceReader=createServiceReader(api,value=>serviceState.value=value)
const serviceAccess=computed(()=>permitted.value&&['msp','padmin'].includes(session.value?.user.role??'')&&['assets:read','tickets:read','sla_scenarios:read'].every(can))
const router = useRouter()
const trendPanel = ref<{refresh:()=>void}|null>(null)
const eventPanel = ref<{ refresh: () => unknown } | null>(null)
const workflowPanel = ref<{ refresh: () => unknown } | null>(null)
const resourcePanel = ref<{ refresh: () => unknown } | null>(null)
const state = shallowRef(emptyDashboardState())
const pending = shallowRef(emptyActionableTickets())
const eventMetrics=shallowRef(emptyEventMetrics())
const metricReader=createEventMetricReader(api,value=>eventMetrics.value=value)
const workspace = createDashboardWorkspace(api, value => { state.value = value })
const autoRefresh = ref(true), now = ref(performance.now()), loadedAt = ref<number | null>(null)
const permitted = computed(() => Boolean(session.value?.mode === 'remote_test' && ['viewer', 'tadmin', 'msp', 'padmin'].includes(session.value.user.role) && can('dashboard:read') && !switchingTenant.value))
const eventAccess = computed(() => can('tickets:read') && ['padmin', 'msp'].includes(session.value?.user.role ?? ''))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, [...(session.value?.capabilities ?? [])].sort(), permitted.value]))
const windows: Record<DashboardWindow, string> = { HOURS_24: '24小时', DAYS_7: '7天', DAYS_30: '30天' }
// M1-1: six fixed cards; the pending card and its list share the same authorized server snapshot.
const kpis = [
  { id: 'ASSET_ONLINE_RATE', title: '资产在线率', route: 'assets', target: '资产列表', parts: [['正常', '—'], ['未知', '—']], note: '未知单独计数，不并入正常' },
  { id: 'CURRENT_PROBLEMS', title: '当前问题', route: 'event-metrics', target: '问题明细', severities: true, note: '已排除已抑制事件' },
  { id: 'MONTH_SERVICE_AVAILABILITY', title: '本月服务可用率', route: 'sla-reports', target: '本页服务明细', note: '本月1日至今 · 无样本不视为可用' },
  { id: 'PENDING_TICKETS', title: '待处理工单', route: 'actionable-tickets', target: '全部待处理与SLA' },
  { id: 'MTTA_24H', title: 'MTTA', period: '近24小时', route: 'event-metrics', target: '时长明细', note: '首次有效人工确认 · 样本量未知' },
  { id: 'DELIVERY_RATE_24H', title: '通知送达率', period: '近24小时', route: 'ticket-notifications', target: '工单通知', parts: [['受理', '—'], ['送达', '—']] },
] as const
const severities = [['disaster', '灾难'], ['high', '严重'], ['average', '一般'], ['warning', '警告'], ['info', '信息']] as const
// Drill-down entries follow the same route gates as the menu; the tenant context is the session's.
const allowedRoutes = computed(() => new Set((router?.getRoutes() ?? []).filter(route => typeof route.name === 'string' && routeAllowed(route.meta, session.value)).map(route => String(route.name))))
const kpiValue = computed(() => state.value.busy ? '读取中' : state.value.error ? '读取失败' : '未知')
function updatePending(value: ActionableTicketState) {
  pending.value = permitted.value && can('tickets:read') ? value : emptyActionableTickets()
}
function metricValue(id: string) {
  if(id==='CURRENT_PROBLEMS'||id==='MTTA_24H'){
    if(!eventAccess.value)return '无权限'
    if(eventMetrics.value.busy)return '读取中'
    if(eventMetrics.value.cancelled)return '已取消'
    if(eventMetrics.value.error)return '读取失败'
    const value=eventMetrics.value.current
    if(!value)return '未知'
    return id==='CURRENT_PROBLEMS'?value.current_problems.total:value.mtta.status==='CALCULATED'?elapsedMetricLabel(value.mtta.mean_millis):value.mtta.status==='NO_ACKNOWLEDGED_SAMPLES'?'—':'未知'
  }
  if(id==='MONTH_SERVICE_AVAILABILITY'){if(!serviceAccess.value)return '无权限';if(serviceState.value.busy)return '读取中';if(serviceState.value.cancelled)return '已取消';if(serviceState.value.error)return '读取失败';const value=serviceState.value.current?.monthly_summary.percentage;return value==null?'未知':value.toFixed(4)+'%'}
  if (id !== 'PENDING_TICKETS') return kpiValue.value
  if (!can('tickets:read')) return '无权限'
  if (pending.value.busy) return '读取中'
  if (pending.value.cancelled) return '已取消'
  if (pending.value.error) return '读取失败'
  return pending.value.current?.pending_tickets ?? '未知'
}
function metricFailed(id: string) { if(id==='MONTH_SERVICE_AVAILABILITY')return Boolean(serviceState.value.error);return Boolean(['CURRENT_PROBLEMS','MTTA_24H'].includes(id)?eventMetrics.value.error:id === 'PENDING_TICKETS' ? pending.value.error : state.value.error) }
function metricUnknown(id: string) { if(id==='MONTH_SERVICE_AVAILABILITY')return serviceState.value.current?.monthly_summary.percentage==null;if(id==='CURRENT_PROBLEMS')return !eventMetrics.value.current;if(id==='MTTA_24H')return eventMetrics.value.current?.mtta.status!=='CALCULATED';return id === 'PENDING_TICKETS' ? pending.value.current?.pending_tickets == null : !state.value.error }
function severityCount(key:string){return eventMetrics.value.current?.current_problems.by_severity[key.toUpperCase() as keyof EventMetricSnapshot['current_problems']['by_severity']]??'未知'}
function metricLink(id:string,name:string){if(id==='MONTH_SERVICE_AVAILABILITY')return {hash:'#service-health-title'};return name==='event-metrics'?{name,query:{window:'HOURS_24',view:id==='CURRENT_PROBLEMS'?'CURRENT_PROBLEMS':'RECENT_PROBLEMS'}}:{name}}
function metricNote(id:string,fallback:string){if(id==='MONTH_SERVICE_AVAILABILITY'){const summary=serviceState.value.current?.monthly_summary;return summary?`已满足覆盖 ${summary.qualified_service_count}/${summary.service_count} 个服务 · 按有效时长汇总`:'本月1日至今 · 无样本不视为可用'}const m=eventMetrics.value.current?.mtta;if(id!=='MTTA_24H'||!m)return fallback;return m.status==='SAMPLE_BUDGET_EXCEEDED'?'超过完整样本核算预算':`确认样本 ${m.sample_count??'未知'} · 未确认 ${m.unacknowledged_count??'未知'} · 异常 ${m.invalid_count??'未知'}`}

const pendingNote = computed(() => {
  const current = pending.value.current
  if (!current) return ''
  return (current.scope === 'REPORTER_ONLY' ? '仅本人报障' : '当前租户')
    + (current.unconfigured ? ' · 另有 ' + current.unconfigured + ' 条登记未配置流程' : ' · 未关闭且未解决')
})
const freshness = computed(() => loadedAt.value === null ? '尚未读取首页状态' : `数据 ${Math.max(0, Math.floor((now.value - loadedAt.value) / 1000))} 秒前 · 指标采样未知`)
let timer: ReturnType<typeof setInterval> | undefined, lastRefresh = 0, alive = true
async function refresh(window: DashboardWindow = state.value.window) {
  const owner = contextKey.value
  lastRefresh = performance.now()
  if(serviceAccess.value)void serviceReader.load()
  if(permitted.value&&eventAccess.value)void metricReader.load()
  if (await workspace.load(window) && alive && owner === contextKey.value) loadedAt.value = performance.now()
}
function refreshAll() {
  if (!permitted.value) return
  void refresh(); trendPanel.value?.refresh(); void eventPanel.value?.refresh(); void workflowPanel.value?.refresh(); void resourcePanel.value?.refresh()
}
function selectWindow(window: DashboardWindow) { if (!state.value.busy) void refresh(window) }
watch(contextKey, () => {
  loadedAt.value = null
  pending.value = emptyActionableTickets()
  const current = session.value
  serviceReader.reset(serviceAccess.value&&current?{tenantId:current.tenant.id,actorId:current.user.id,permissionVersion:current.permission_version,canRead:true}:null)
  metricReader.reset(permitted.value&&eventAccess.value&&current?{tenantId:current.tenant.id,actorId:current.user.id,permissionVersion:current.permission_version,canRead:true}:null)
  workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version, role: current.user.role, canRead: true } : null)
  if (permitted.value) void refresh()
}, { immediate: true, flush: 'sync' })
onMounted(() => {
  timer = setInterval(() => {
    now.value = performance.now()
    if (permitted.value && autoRefresh.value && !document.hidden && now.value - lastRefresh >= 30000) { void refresh(); trendPanel.value?.refresh(); void eventPanel.value?.refresh(); void workflowPanel.value?.refresh() }
  }, 1000)
})
onUnmounted(() => { alive = false; clearInterval(timer); workspace.dispose(); metricReader.dispose(); serviceReader.dispose() })
</script>
<template>
  <section class="dashboard-page" aria-labelledby="dashboard-title">
    <h1 id="dashboard-title" class="page-title-sr">运营总览</h1>
    <p v-if="!permitted" class="mop-empty" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据。</p>
    <template v-else>
      <div class="dashboard-toolbar"><router-link to="/report-publications">报告中心</router-link>
        <div class="dashboard-range" role="group" aria-label="首页时间范围">
          <span class="mop-toolbar-label">时间范围</span>
          <div class="mop-segmented">
            <button v-for="window in dashboardWindows" :key="window" type="button" :aria-pressed="state.window === window" :disabled="state.busy && state.window !== window" @click="selectWindow(window)">{{ windows[window] }}</button>
          </div>
        </div>
        <label class="dashboard-auto"><input v-model="autoRefresh" type="checkbox" />每30秒自动刷新</label>
        <span class="dashboard-freshness mop-fresh" :class="{ live: loadedAt !== null && !state.error && !state.busy, busy: state.busy, failed: Boolean(state.error) }" role="status">{{ state.error ? '本次读取失败 · 指标采样未知' : freshness }}</span>
        <button type="button" class="dashboard-refresh" :disabled="state.busy" @click="refreshAll"><Refresh aria-hidden="true" />{{ state.busy ? '读取中…' : '刷新首页状态' }}</button>
      </div>
      <ApiErrorNotice :error="state.error" :retrying="state.busy" @retry="refresh()" />
      <div class="dashboard-kpis mop-kpis" aria-label="六项运营指标">
        <article v-for="kpi in kpis" :key="kpi.id" class="dashboard-kpi mop-kpi" :class="{ unknown: metricUnknown(kpi.id) }" :data-kpi="kpi.id">
          <div class="mop-kpi-label"><h2>{{ kpi.title }}<span v-if="'period' in kpi" class="kpi-period">{{ kpi.period }}</span></h2></div>
          <strong class="mop-kpi-value" :class="{ 'kpi-failed': metricFailed(kpi.id) }">{{ metricValue(kpi.id) }}</strong>
          <div v-if="'severities' in kpi" class="kpi-severities" aria-label="按严重级别计数">
            <span v-for="[key, label] in severities" :key="key" class="mop-sev" :class="key">{{ label }} {{severityCount(key)}}</span>
          </div>
          <p v-else-if="kpi.id === 'PENDING_TICKETS'" class="mop-kpi-detail kpi-parts"><span>已超 SLA <b :class="{ 'kpi-failed': (pending.current?.overdue_sla ?? pending.current?.known_overdue ?? 0) > 0 }">{{ pending.current?.overdue_sla ?? '未知' }}</b></span><span v-if="pending.current?.overdue_sla === null">已确认 {{ pending.current.known_overdue }} · 未知 {{ pending.current.unknown_sla }}</span></p>
          <p v-else-if="'parts' in kpi" class="mop-kpi-detail kpi-parts"><span v-for="[label, value] in kpi.parts" :key="label">{{ label }} <b>{{ value }}</b></span></p>
          <div class="kpi-foot">
            <span v-if="kpi.id === 'PENDING_TICKETS' && pending.current" class="mop-kpi-detail">{{ pendingNote }}</span>
            <span v-if="'note' in kpi" class="mop-kpi-detail">{{ metricNote(kpi.id,kpi.note) }}<span v-if="kpi.id==='MTTA_24H'&&eventMetrics.current?.mtta.target_breached" class="mop-badge warning">超出5分钟目标</span></span>
            <router-link v-if="allowedRoutes.has(kpi.route)" class="mop-kpi-link" :to="metricLink(kpi.id,kpi.route)" :aria-label="`${kpi.title}：打开${kpi.target}`">{{ kpi.target }}<ArrowRight aria-hidden="true" /></router-link>
          </div>
        </article>
      </div>
      <p class="dashboard-source-status">{{ eventMetrics.current ? '问题计数与MTTA来自授权事件投影；采集时效与时钟准确性未核验，其他未接入指标不补值。' : pending.current ? '待处理工单与下方列表来自同一授权汇总；未配置流程单列为未知，其余未接入指标不补值。' : state.current ? '正式指标尚未接入。以下业务记录与设备采样独立读取，不作为上述指标的替代值。' : '尚未取得有效首页状态，未显示缓存指标。' }}</p>
      <div :key="contextKey" class="dashboard-grid">
        <section class="dashboard-card mop-card span-8" data-block="ALERT_TREND" aria-labelledby="alert-trend-title">
          <header class="mop-card-head"><div class="mop-card-title"><h2 id="alert-trend-title">告警趋势</h2><span>原始记录 vs 指纹归并后 · {{ windows[state.window] }}</span></div><span class="mop-badge unknown" title="最终通知来源尚未接入，不能以有效告警数代替">降噪率未知</span></header>
          <div class="mop-card-body">
            <div class="dashboard-legend"><span class="legend-line raw">原始记录</span><span class="legend-line merged">指纹归并后</span><span class="legend-note">降噪链：指纹归并 → 依赖抑制 → 维护静默</span></div>
            <EventNoiseTrendPanel ref="trendPanel" :window="state.window" />
          </div>
        </section>
        <section class="dashboard-card mop-card span-4" data-block="SERVICE_HEALTH" aria-labelledby="service-health-title">
          <header class="mop-card-head"><div class="mop-card-title"><h2 id="service-health-title">服务健康</h2></div></header>
          <div class="mop-card-body">
            <div class="dashboard-legend"><span class="legend-dot ok">达标</span><span class="legend-dot danger">未达标</span><span class="legend-dot unknown">未知</span></div>
            <ServiceObservationsPanel :state="serviceState" mode="health" :permitted="serviceAccess" @refresh="serviceReader.load()" @cancel="serviceReader.cancel()" />
          </div>
        </section>
        <section class="dashboard-card mop-card span-12" data-block="FAULT_TIMELINE" aria-labelledby="fault-timeline-title">
          <header class="mop-card-head"><div class="mop-card-title"><h2 id="fault-timeline-title">故障时间线</h2><span>近24小时 · 最多 8 个服务</span></div><div class="dashboard-legend"><span class="legend-bar fault">故障</span><span class="legend-bar maint">维护</span><span class="legend-bar unknown-interval">未知区间</span></div></header>
          <div class="mop-card-body">
            <ServiceObservationsPanel :state="serviceState" mode="timeline" :permitted="serviceAccess" />
          </div>
        </section>
        <section class="dashboard-card mop-card dashboard-list span-12" data-block="RECENT_EVENTS" aria-label="最近事件">
          <DashboardEventsPanel v-if="eventAccess" ref="eventPanel" />
          <template v-else><header class="mop-card-head"><h2>最近事件</h2></header><p class="mop-empty">当前账号没有事件中心读取权限。</p></template>
        </section>
        <section class="dashboard-card mop-card dashboard-list span-7" data-block="PENDING_TICKETS" aria-label="待处理工单">
          <DashboardWorkflowsPanel v-if="can('tickets:read')" ref="workflowPanel" @state="updatePending" />
          <template v-else><header class="mop-card-head"><h2>待处理工单</h2></header><p class="mop-empty">当前账号没有工单读取权限。</p></template>
        </section>
        <section class="dashboard-card mop-card dashboard-list span-5" data-block="RESOURCE_TOP5" aria-label="资源占用Top5">
          <LiveMonitoringPanel v-if="can('assets:read')" ref="resourcePanel" mode="ranking" :auto-refresh="autoRefresh" />
          <template v-else><header class="mop-card-head"><h2>资源占用 Top5</h2></header><p class="mop-empty">当前账号没有设备读取权限。</p></template>
        </section>
      </div>
      <p v-if="state.current" class="dashboard-generated">首页状态时间 {{ displayTime(state.current.generated_at) }} · Asia/Shanghai · 固定版式 M1-11</p>
    </template>
  </section>
</template>
<style scoped>
.dashboard-page{--mop-faint:var(--mop-muted);min-width:0;max-width:100%;padding-top:4px}
.dashboard-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:8px 16px;margin:0 0 12px;min-height:40px}
.dashboard-range{display:inline-flex;align-items:center;gap:8px}
.dashboard-auto{display:inline-flex;align-items:center;gap:6px;font-size:13px;color:var(--mop-text-2);cursor:pointer}
.dashboard-auto input{width:16px;height:16px;accent-color:var(--mop-primary)}
.dashboard-freshness{margin-left:auto}
.dashboard-refresh{display:inline-flex;align-items:center;gap:6px}.dashboard-refresh svg{width:14px;height:14px}
.dashboard-kpi h2{display:flex;align-items:baseline;flex-wrap:wrap;gap:0 6px;margin:0;font-size:13px;font-weight:500;color:var(--mop-text-2);line-height:20px}
.kpi-period{font-size:12px;font-weight:400;color:var(--mop-muted)}
.kpi-foot{display:flex;align-items:flex-end;justify-content:space-between;gap:4px 8px;flex-wrap:wrap;margin-top:auto;padding-top:6px}
.kpi-foot .mop-kpi-detail{flex:1 1 120px;min-width:0}
.kpi-foot .mop-kpi-link{margin-left:auto}
.kpi-failed{color:var(--mop-danger)!important}
.kpi-parts{display:flex;flex-wrap:wrap;gap:2px 12px}.kpi-parts b{font-weight:600;color:var(--mop-text-2)}
.kpi-severities{display:flex;flex-wrap:wrap;gap:4px;margin:2px 0 4px}
.kpi-severities .mop-sev{min-width:0;min-height:20px;padding:0 5px;font-size:11px;line-height:20px;font-weight:500}
.dashboard-source-status{display:flex;align-items:center;gap:6px;margin:-2px 0 12px;font-size:12px;color:var(--mop-muted);line-height:1.6}
.dashboard-source-status::before{content:'i';display:inline-grid;place-items:center;width:16px;height:16px;flex-shrink:0;border-radius:50%;background:var(--mop-info-bg);color:var(--mop-info);font-size:11px;font-weight:700;font-style:italic}
.dashboard-grid{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));gap:12px}
.span-4{grid-column:span 4}.span-5{grid-column:span 5}.span-7{grid-column:span 7}.span-8{grid-column:span 8}.span-12{grid-column:1/-1}
.dashboard-card{min-width:0;display:flex;flex-direction:column}
.dashboard-card .mop-card-body{flex:1;display:flex;flex-direction:column;gap:10px}
.dashboard-legend{display:flex;align-items:center;flex-wrap:wrap;gap:6px 16px;font-size:12px;color:var(--mop-muted)}
.legend-line,.legend-dot,.legend-bar{display:inline-flex;align-items:center;gap:6px}
.legend-line::before{content:'';width:16px;height:2px;border-radius:2px;background:#9aa8ba}.legend-line.merged::before{height:3px;background:var(--mop-primary)}
.legend-note{color:var(--mop-faint)}
.legend-dot::before{content:'';width:8px;height:8px;border-radius:50%}.legend-dot.ok::before{background:var(--mop-ok-dot)}.legend-dot.danger::before{background:var(--mop-danger-dot)}.legend-dot.unknown::before{border:1.5px dashed var(--mop-unknown-dot);width:6px;height:6px}
.legend-bar::before{content:'';width:16px;height:8px;border-radius:2px}.legend-bar.fault::before{background:var(--mop-danger-dot)}.legend-bar.maint::before{background:var(--mop-maint-dot)}
.unknown-interval::before{background:var(--mop-hatch);box-shadow:inset 0 0 0 1px var(--mop-line-strong)}
.trend-unavailable{min-height:188px;background:linear-gradient(var(--mop-line-soft) 1px,transparent 1px) 0 0/100% 36px,var(--mop-hatch)}
.timeline-track{position:relative;display:flex;align-items:center;justify-content:center;height:34px;border-radius:var(--mop-radius-sm);background:var(--mop-hatch);box-shadow:inset 0 0 0 1px var(--mop-line)}
.timeline-track span{padding:1px 10px;border-radius:999px;background:#fff;color:var(--mop-unknown);font-size:12px;box-shadow:inset 0 0 0 1px var(--mop-line)}
.timeline-axis{position:relative;height:20px;margin:-4px 18px 0;border-top:1px solid var(--mop-line)}
.timeline-axis span{position:absolute;top:4px;transform:translateX(-50%);font-size:11px;color:var(--mop-muted);font-variant-numeric:tabular-nums}
.timeline-axis span::before{content:'';position:absolute;top:-5px;left:50%;width:1px;height:5px;background:var(--mop-line-strong)}
.dashboard-unavailable{margin:0;font-size:12px;color:var(--mop-muted)}
.dashboard-list{overflow:hidden}
.dashboard-list :deep(:is(.dashboard-events,.dashboard-workflows,.live-monitoring)){flex:1;margin:0;border:0;border-radius:0;box-shadow:none}
.dashboard-generated{margin:12px 2px 0;font-size:12px;color:var(--mop-muted);text-align:right;font-variant-numeric:tabular-nums}
.dashboard-page :focus-visible{outline:2px solid var(--mop-primary);outline-offset:2px}
@media(max-width:1250px){.span-4,.span-5,.span-7,.span-8{grid-column:1/-1}}
@media(max-width:1100px){.dashboard-kpis{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:760px){.dashboard-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.dashboard-freshness{margin-left:0;width:100%}.dashboard-refresh{min-height:44px}.dashboard-auto{min-height:44px}.dashboard-auto input{width:18px;height:18px}.timeline-axis{margin:0 12px}}
</style>
