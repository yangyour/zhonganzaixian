<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createDashboardWorkspace, emptyDashboardState } from '../utils/dashboardWorkspace'
import { dashboardWindows, type DashboardWindow } from '../utils/dashboardAvailability'
import { displayTime } from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import LiveMonitoringPanel from '../components/LiveMonitoringPanel.vue'
import DashboardEventsPanel from '../components/DashboardEventsPanel.vue'
import DashboardWorkflowsPanel from '../components/DashboardWorkflowsPanel.vue'

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
const kpis = [
  { id: 'ASSET_ONLINE_RATE', title: '资产在线率', detail: '正常 — · 未知 —' },
  { id: 'CURRENT_PROBLEMS', title: '当前问题', detail: '灾难 — · 严重 — · 一般 — · 警告 — · 信息 —' },
  { id: 'MONTH_SERVICE_AVAILABILITY', title: '本月服务可用率', detail: '本月1日至今 · 无样本不视为可用' },
  { id: 'PENDING_TICKETS', title: '待处理工单', detail: '已超 SLA —' },
  { id: 'MTTA_24H', title: 'MTTA', detail: '近24小时 · 样本量未知' },
  { id: 'DELIVERY_RATE_24H', title: '通知送达率', detail: '近24小时 · 受理 — · 送达 —' },
]
const freshness = computed(() => loadedAt.value === null ? '尚未读取首页状态' : `状态读取 ${Math.max(0, Math.floor((now.value - loadedAt.value) / 1000))} 秒前 · 指标采样未知`)
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
function selectWindow(event: Event) { void refresh((event.target as HTMLSelectElement).value as DashboardWindow) }
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
    <p v-if="!permitted" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据。</p>
    <template v-else>
      <div class="dashboard-toolbar">
        <label>时间范围<select :value="state.window" aria-label="首页时间范围" @change="selectWindow"><option v-for="window in dashboardWindows" :key="window" :value="window">{{ windows[window] }}</option></select></label>
        <label><input v-model="autoRefresh" type="checkbox" />每30秒自动刷新</label>
        <span class="dashboard-freshness" role="status">{{ state.error ? '本次读取失败 · 指标采样未知' : freshness }}</span>
        <button type="button" :disabled="state.busy" @click="refreshAll">{{ state.busy ? '读取中…' : '刷新首页状态' }}</button>
      </div>
      <ApiErrorNotice :error="state.error" :retrying="state.busy" @retry="refresh()" />
      <div class="dashboard-kpis" aria-label="六项运营指标">
        <article v-for="kpi in kpis" :key="kpi.id" class="dashboard-kpi" :data-kpi="kpi.id">
          <h2>{{ kpi.title }}</h2><strong>{{ state.busy ? '读取中' : state.error ? '读取失败' : '未知' }}</strong><p>{{ kpi.detail }}</p>
        </article>
      </div>
      <p class="dashboard-source-status">{{ state.current ? '正式指标尚未接入。以下业务记录与设备采样独立读取，不作为上述指标的替代值。' : '尚未取得有效首页状态，未显示缓存指标。' }}</p>
      <div :key="contextKey" class="dashboard-grid">
        <section class="dashboard-card" data-block="ALERT_TREND" aria-labelledby="alert-trend-title">
          <header><h2 id="alert-trend-title">告警趋势</h2><span>{{ windows[state.window] }} · 降噪率未知</span></header>
          <div class="dashboard-legend"><span>原始事件</span><span>归并后事件</span></div>
          <p class="dashboard-unavailable">{{ state.error ? '首页状态读取失败，请重试。' : '时序统计尚未接入，暂无可核验的趋势。' }}</p>
        </section>
        <section class="dashboard-card" data-block="SERVICE_HEALTH" aria-labelledby="service-health-title">
          <header><h2 id="service-health-title">服务健康</h2><span>接入中</span></header>
          <div class="dashboard-legend"><span>达标</span><span>未达标</span><span>未知</span></div>
          <p class="dashboard-unavailable">服务可用率与目标尚无可核验数据。</p>
        </section>
        <section class="dashboard-card dashboard-wide" data-block="FAULT_TIMELINE" aria-labelledby="fault-timeline-title">
          <header><h2 id="fault-timeline-title">故障时间线</h2><span>近24小时</span></header>
          <div class="dashboard-legend"><span>故障</span><span>维护</span><span class="unknown-interval">未知区间</span></div>
          <p class="dashboard-unavailable">服务区间尚未接入，暂无可核验的故障时间线。</p>
        </section>
        <section class="dashboard-card dashboard-list" data-block="RECENT_EVENTS" aria-label="最近事件">
          <DashboardEventsPanel ref="eventPanel" v-if="eventAccess" />
          <template v-else><h2>最近事件</h2><p class="dashboard-unavailable">当前账号没有事件中心读取权限。</p></template>
        </section>
        <section class="dashboard-card dashboard-list" data-block="PENDING_TICKETS" aria-label="待处理工单">
          <h2>待处理工单</h2>
          <DashboardWorkflowsPanel ref="workflowPanel" v-if="can('tickets:read')" />
          <p v-else class="dashboard-unavailable">当前账号没有工单读取权限。</p>
        </section>
        <section class="dashboard-card dashboard-wide dashboard-list" data-block="RESOURCE_TOP5" aria-label="资源占用Top5">
          <LiveMonitoringPanel ref="resourcePanel" v-if="can('assets:read')" mode="ranking" :auto-refresh="autoRefresh" />
          <template v-else><h2>资源占用 Top5</h2><p class="dashboard-unavailable">当前账号没有设备读取权限。</p></template>
        </section>
      </div>
      <p v-if="state.current" class="dashboard-generated">首页状态时间 {{ displayTime(state.current.generated_at) }} · Asia/Shanghai</p>
    </template>
  </section>
</template>
<style scoped>
.dashboard-page{min-width:0;max-width:100%;color:#334155}
.dashboard-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:12px}
.dashboard-toolbar label{display:inline-flex;align-items:center;gap:8px;font-size:13px}
.dashboard-toolbar button,.dashboard-toolbar select{min-height:36px;padding:6px 12px;border:1px solid #cfdaea;border-radius:5px;background:#fff;color:#2262ac;font:inherit;font-size:13px}
.dashboard-freshness{margin-left:auto;font-size:12px;color:#526276}
.dashboard-kpis{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px}
.dashboard-kpi,.dashboard-card{min-width:0;padding:16px;background:#fff;border:1px solid #e3e9f2;border-radius:8px}
.dashboard-kpi h2,.dashboard-card h2{margin:0;font-size:14px;font-weight:600;color:#334155}
.dashboard-kpi strong{display:block;margin:10px 0;font-size:25px;font-weight:600;color:#64748b}
.dashboard-kpi p{margin:0;font-size:12px;line-height:1.7;color:#526276;overflow-wrap:anywhere}
.dashboard-source-status,.dashboard-generated{font-size:12px;color:#526276;line-height:1.6;margin:10px 0}
.dashboard-grid{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,2fr);gap:14px}
.dashboard-wide{grid-column:1/-1}
.dashboard-card header{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px}
.dashboard-card header>span,.dashboard-legend{font-size:12px;color:#526276}
.dashboard-legend{display:flex;gap:16px;flex-wrap:wrap;margin-top:12px}
.unknown-interval{padding-inline:6px;background:repeating-linear-gradient(135deg,#eef2f7,#eef2f7 3px,#fff 3px,#fff 6px)}
.dashboard-unavailable{margin:14px 0 0;padding:20px 12px;background:#f8fafc;border-radius:6px;text-align:center;font-size:13px;color:#526276}
.dashboard-list{padding:0;overflow:hidden}.dashboard-list>h2{padding:16px 18px 0}
.dashboard-list>p{margin:16px}.dashboard-list :deep(.dashboard-events),.dashboard-list :deep(.dashboard-workflows),.dashboard-list :deep(.live-monitoring){border:0;border-radius:0;margin:0;box-shadow:none}
.dashboard-generated{text-align:right}.dashboard-page :focus-visible{outline:2px solid #2563eb;outline-offset:3px}
@media(max-width:1250px){.dashboard-kpis{grid-template-columns:repeat(3,minmax(0,1fr))}.dashboard-grid{grid-template-columns:1fr}.dashboard-wide{grid-column:auto}}
@media(max-width:760px){.dashboard-kpis{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.dashboard-kpi{padding:12px}.dashboard-freshness{margin-left:0;width:100%}.dashboard-toolbar button,.dashboard-toolbar select{min-height:44px}.dashboard-toolbar input{width:18px;height:18px}.dashboard-toolbar label{min-height:44px}}
</style>
