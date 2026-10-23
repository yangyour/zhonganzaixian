<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createDashboardWorkspace, emptyDashboardState } from '../utils/dashboardWorkspace'
import { dashboardWindows, type DashboardWindow } from '../utils/dashboardAvailability'
import { clockTime, displayTime } from '../utils/display'
import { routeAllowed } from '../utils/routeAccess'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import LiveMonitoringPanel from '../components/LiveMonitoringPanel.vue'
import DashboardEventsPanel from '../components/DashboardEventsPanel.vue'
import DashboardWorkflowsPanel from '../components/DashboardWorkflowsPanel.vue'

const router = useRouter()
const eventPanel = ref<{ refresh: () => unknown } | null>(null)
const workflowPanel = ref<{ refresh: () => unknown } | null>(null)
const resourcePanel = ref<{ refresh: () => unknown } | null>(null)
const state = shallowRef(emptyDashboardState())
const workspace = createDashboardWorkspace(api, value => { state.value = value })
const autoRefresh = ref(true), now = ref(performance.now()), loadedAt = ref<number | null>(null)
const permitted = computed(() => Boolean(session.value?.mode === 'remote_test' && ['viewer', 'tadmin', 'msp', 'padmin'].includes(session.value.user.role) && can('dashboard:read') && !switchingTenant.value))
const eventAccess = computed(() => can('tickets:read') && ['padmin', 'msp'].includes(session.value?.user.role ?? ''))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, [...(session.value?.capabilities ?? [])].sort(), permitted.value]))
const windows: Record<DashboardWindow, string> = { HOURS_24: '24小时', DAYS_7: '7天', DAYS_30: '30天' }
// M1-1: six fixed KPI cards. Values stay unknown until the formal metric readers exist.
const kpis = [
  { id: 'ASSET_ONLINE_RATE', title: '资产在线率', route: 'assets', target: '资产列表', parts: [['正常', '—'], ['未知', '—']], note: '未知单独计数，不并入正常' },
  { id: 'CURRENT_PROBLEMS', title: '当前问题', route: 'monitoring-events', target: '事件中心', severities: true, note: '已排除已抑制事件' },
  { id: 'MONTH_SERVICE_AVAILABILITY', title: '本月服务可用率', route: 'sla-reports', target: 'SLA 报表', note: '本月1日至今 · 无样本不视为可用' },
  { id: 'PENDING_TICKETS', title: '待处理工单', route: 'ticket-workflows', target: '工单处理', parts: [['已超 SLA', '—']] },
  { id: 'MTTA_24H', title: 'MTTA', period: '近24小时', route: 'monitoring-events', target: '事件中心', note: '首次有效人工确认 · 样本量未知' },
  { id: 'DELIVERY_RATE_24H', title: '通知送达率', period: '近24小时', route: 'ticket-notifications', target: '工单通知', parts: [['受理', '—'], ['送达', '—']] },
] as const
const severities = [['disaster', '灾难'], ['high', '严重'], ['average', '一般'], ['warning', '警告'], ['info', '信息']] as const
// Drill-down entries follow the same route gates as the menu; the tenant context is the session's.
const allowedRoutes = computed(() => new Set((router?.getRoutes() ?? []).filter(route => typeof route.name === 'string' && routeAllowed(route.meta, session.value)).map(route => String(route.name))))
const kpiValue = computed(() => state.value.busy ? '读取中' : state.value.error ? '读取失败' : '未知')
const freshness = computed(() => loadedAt.value === null ? '尚未读取首页状态' : `数据 ${Math.max(0, Math.floor((now.value - loadedAt.value) / 1000))} 秒前 · 指标采样未知`)
// M1-4: a 24-hour axis with 6-hour ticks taken from the server-issued period.
const timelineTicks = computed(() => {
  const period = state.value.current?.last_24_hours
  if (!period) return []
  const from = Date.parse(period.from), to = Date.parse(period.to)
  if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) return []
  return [0, 1, 2, 3, 4].map(step => ({ offset: step * 25, label: clockTime(from + (to - from) * step / 4) }))
})
let timer: ReturnType<typeof setInterval> | undefined, lastRefresh = 0, alive = true
async function refresh(window: DashboardWindow = state.value.window) {
  const owner = contextKey.value
  lastRefresh = performance.now()
  if (await workspace.load(window) && alive && owner === contextKey.value) loadedAt.value = performance.now()
}
function refreshAll() {
  if (!permitted.value) return
  void refresh(); void eventPanel.value?.refresh(); void workflowPanel.value?.refresh(); void resourcePanel.value?.refresh()
}
function selectWindow(window: DashboardWindow) { if (!state.value.busy) void refresh(window) }
watch(contextKey, () => {
  loadedAt.value = null
  const current = session.value
  workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version, role: current.user.role, canRead: true } : null)
  if (permitted.value) void refresh()
}, { immediate: true, flush: 'sync' })
onMounted(() => {
  timer = setInterval(() => {
    now.value = performance.now()
    if (permitted.value && autoRefresh.value && !document.hidden && now.value - lastRefresh >= 30000) { void refresh(); void eventPanel.value?.refresh(); void workflowPanel.value?.refresh() }
  }, 1000)
})
onUnmounted(() => { alive = false; clearInterval(timer); workspace.dispose() })
</script>
<template>
  <section class="dashboard-page" aria-labelledby="dashboard-title">
    <h1 id="dashboard-title" class="page-title-sr">运营总览</h1>
    <p v-if="!permitted" class="mop-empty" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据。</p>
    <template v-else>
      <div class="dashboard-toolbar">
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
        <article v-for="kpi in kpis" :key="kpi.id" class="dashboard-kpi mop-kpi" :class="{ unknown: !state.error }" :data-kpi="kpi.id">
          <div class="mop-kpi-label"><h2>{{ kpi.title }}<span v-if="'period' in kpi" class="kpi-period">{{ kpi.period }}</span></h2></div>
          <strong class="mop-kpi-value" :class="{ 'kpi-failed': state.error }">{{ kpiValue }}</strong>
          <div v-if="'severities' in kpi" class="kpi-severities" aria-label="按严重级别计数">
            <span v-for="[key, label] in severities" :key="key" class="mop-sev" :class="key">{{ label }} —</span>
          </div>
          <p v-else-if="'parts' in kpi" class="mop-kpi-detail kpi-parts"><span v-for="[label, value] in kpi.parts" :key="label">{{ label }} <b>{{ value }}</b></span></p>
          <div class="kpi-foot">
            <span v-if="'note' in kpi" class="mop-kpi-detail">{{ kpi.note }}</span>
            <router-link v-if="allowedRoutes.has(kpi.route)" class="mop-kpi-link" :to="{ name: kpi.route }" :aria-label="`${kpi.title}：打开${kpi.target}`">{{ kpi.target }}<ArrowRight aria-hidden="true" /></router-link>
          </div>
        </article>
      </div>
      <p class="dashboard-source-status">{{ state.current ? '正式指标尚未接入。以下业务记录与设备采样独立读取，不作为上述指标的替代值。' : '尚未取得有效首页状态，未显示缓存指标。' }}</p>
      <div :key="contextKey" class="dashboard-grid">
        <section class="dashboard-card mop-card span-8" data-block="ALERT_TREND" aria-labelledby="alert-trend-title">
          <header class="mop-card-head"><div class="mop-card-title"><h2 id="alert-trend-title">告警趋势</h2><span>原始事件 vs 归并后 · {{ windows[state.window] }}</span></div><span class="mop-badge unknown">降噪率未知</span></header>
          <div class="mop-card-body">
            <div class="dashboard-legend"><span class="legend-line raw">原始事件</span><span class="legend-line merged">归并后事件</span><span class="legend-note">降噪链：指纹归并 → 依赖抑制 → 维护静默</span></div>
            <div class="mop-unavailable trend-unavailable"><strong>未接入</strong><span>{{ state.error ? '首页状态读取失败，请重试。' : '时序统计尚未接入，暂无可核验的趋势。' }}</span></div>
          </div>
        </section>
        <section class="dashboard-card mop-card span-4" data-block="SERVICE_HEALTH" aria-labelledby="service-health-title">
          <header class="mop-card-head"><div class="mop-card-title"><h2 id="service-health-title">服务健康</h2></div><span class="mop-badge unknown">接入中</span></header>
          <div class="mop-card-body">
            <div class="dashboard-legend"><span class="legend-dot ok">达标</span><span class="legend-dot danger">未达标</span><span class="legend-dot unknown">未知</span></div>
            <div class="mop-unavailable"><strong>接入中</strong><span>服务可用率与目标尚无可核验数据，不显示为达标。</span></div>
          </div>
        </section>
        <section class="dashboard-card mop-card span-12" data-block="FAULT_TIMELINE" aria-labelledby="fault-timeline-title">
          <header class="mop-card-head"><div class="mop-card-title"><h2 id="fault-timeline-title">故障时间线</h2><span>近24小时 · 最多 8 个服务</span></div><div class="dashboard-legend"><span class="legend-bar fault">故障</span><span class="legend-bar maint">维护</span><span class="legend-bar unknown-interval">未知区间</span></div></header>
          <div class="mop-card-body">
            <div class="timeline-track" title="数据缺失·记为未知"><span>服务区间尚未接入 · 数据缺失记为未知</span></div>
            <div v-if="timelineTicks.length" class="timeline-axis" aria-hidden="true"><span v-for="tick in timelineTicks" :key="tick.offset" :style="{ left: tick.offset + '%' }">{{ tick.label }}</span></div>
            <p class="sr-only">服务区间尚未接入，暂无可核验的故障时间线。</p>
          </div>
        </section>
        <section class="dashboard-card mop-card dashboard-list span-12" data-block="RECENT_EVENTS" aria-label="最近事件">
          <DashboardEventsPanel v-if="eventAccess" ref="eventPanel" />
          <template v-else><header class="mop-card-head"><h2>最近事件</h2></header><p class="mop-empty">当前账号没有事件中心读取权限。</p></template>
        </section>
        <section class="dashboard-card mop-card dashboard-list span-7" data-block="PENDING_TICKETS" aria-label="待处理工单">
          <DashboardWorkflowsPanel v-if="can('tickets:read')" ref="workflowPanel" />
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
.dashboard-page{min-width:0;max-width:100%;padding-top:4px}
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
