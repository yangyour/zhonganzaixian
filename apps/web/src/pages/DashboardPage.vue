<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {createDashboardWorkspace,emptyDashboardState,type DashboardState} from '../utils/dashboardWorkspace'
import {validDashboardWindow,type DashboardWindow} from '../utils/dashboardAvailability'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import LiveMonitoringPanel from '../components/LiveMonitoringPanel.vue'
import DashboardReportsPanel from '../components/DashboardReportsPanel.vue'

const state=shallowRef<DashboardState>(emptyDashboardState()),autoRefresh=ref(true)
const topMetric = ref('CPU')
const workspace=createDashboardWorkspace(api,next=>{state.value=next})
let disposed=false,timer:ReturnType<typeof setInterval>|null=null
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['viewer','tadmin','msp','padmin'].includes(session.value.user.role)&&can('dashboard:read')&&!switchingTenant.value))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value]))
const kpis=[{id:'ASSET_ONLINE_RATE',title:'资产在线率',hint:'正常、未知与总数均待采集'},{id:'CURRENT_PROBLEMS',title:'当前问题数',hint:'事件源待接入，缺失数据不计为零告警'},{id:'MONTH_SERVICE_AVAILABILITY',title:'本月服务可用率',hint:'按当月1日 00:00（北京时间）起统计'},{id:'PENDING_TICKETS',title:'待处理工单',hint:'工单流转与 SLA 超时口径待接入'},{id:'MTTA_24H',title:'最近24小时 MTTA',hint:'待接入告警事件与响应确认时间'},{id:'DELIVERY_RATE_24H',title:'最近24小时送达率',hint:'受理不等于送达，通道回执待接入'}]
const panels=[{id:'ALERT_TREND',title:'告警趋势',hint:'告警事件源待接入，暂无趋势曲线数据'},{id:'SERVICE_HEALTH',title:'服务健康概览',hint:'服务健康区间样本待接入，暂未判定状态'},{id:'FAULT_TIMELINE',title:'最近24小时故障时间轴',hint:'最多展示 8 条记录；故障区间数据待采集'},{id:'RECENT_EVENTS',title:'近期事件',hint:'合同最近7条记录；事件源待采集'},{id:'PENDING_TICKETS',title:'待处理工单列表',hint:'最多展示 5 条；待接入正式工单流转状态'},{id:'RESOURCE_TOP5',title:'资源使用 Top5',hint:'全量资产排行待接入，已绑定设备排行见上方监控面板'}]
const noDataText=computed(()=>state.value.busy?'正在读取授权状态…':state.value.error?'读取失败，未显示旧投影':state.value.current?'未知':'尚未读取')
watch(contextKey,()=>{const current=session.value;workspace.reset(!disposed&&permitted.value&&current?{tenantId:current.tenant.id,actorId:current.user.id,permissionVersion:current.permission_version,role:current.user.role,canRead:true}:null);if(!disposed&&permitted.value)void workspace.load()},{immediate:true,flush:'sync'})
async function refresh(){if(disposed||!permitted.value)return false;return workspace.load()}
async function changeWindow(value:unknown){if(disposed||!permitted.value||!validDashboardWindow(value))return false;return workspace.load(value as DashboardWindow)}
function chooseWindow(event:Event){void changeWindow((event.target as HTMLSelectElement).value)}
function tick(){if(!disposed&&permitted.value&&autoRefresh.value&&!document.hidden&&!state.value.busy)void refresh()}
function visibility(){if(!document.hidden)tick()}
onMounted(()=>{timer=setInterval(tick,30_000);document.addEventListener('visibilitychange',visibility)})
onUnmounted(()=>{disposed=true;if(timer!==null)clearInterval(timer);document.removeEventListener('visibilitychange',visibility);workspace.dispose()})
defineExpose({state,autoRefresh,refresh,changeWindow})
</script>

<template>
 <section class="dashboard-page" :aria-busy="state.busy">
  <header class="dashboard-heading"><div><div class="title-with-context"><h1>运营总览</h1><span class="tenant-badge">{{session?.tenant.name}}</span><span class="availability-badge"><span aria-hidden="true" />实际数据工作区</span></div><details class="boundary-details"><summary>口径与范围说明</summary><p>当前展示运营总览指标框架与统计口径（北京时间 Asia/Shanghai）；未采集指标标记为“待采集”，不计入正常率或零值统计。上方设备监控面板展示当前租户已绑定设备的实时采样。</p></details></div></header>
  <p v-if="!permitted" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据</p>
  <template v-else>
   <LiveMonitoringPanel v-if="can('assets:read')" :auto-refresh="autoRefresh" />
   <DashboardReportsPanel v-if="can('tickets:read')" />
   <details class="formal-dashboard-status"><summary>全量运营指标与接入状态</summary><div class="dashboard-header-actions"><label class="dashboard-range-select">统计周期 <select aria-label="告警趋势范围" :value="state.window" @change="chooseWindow"><option value="HOURS_24">最近24小时</option><option value="DAYS_7">最近7天</option><option value="DAYS_30">最近30天</option></select></label><div class="dashboard-ranges" aria-label="告警趋势快捷范围"><button v-for="range in [{id:'HOURS_24',label:'24小时'},{id:'DAYS_7',label:'7天'},{id:'DAYS_30',label:'30天'}]" :key="range.id" type="button" :aria-pressed="state.window===range.id" :class="{active:state.window===range.id}" :disabled="!permitted||state.busy" @click="changeWindow(range.id)">{{range.label}}</button></div><div class="dashboard-controls"><button type="button" :disabled="state.busy||!permitted" @click="refresh">{{state.busy?'读取中…':'刷新视图'}}</button></div></div><p>分项指标待接入：告警事件、工单流转、服务可用性区间与通知回执暂未接入采集；已绑定设备的实时采样见上方监控面板。</p>
   <ApiErrorNotice :error="state.error" :retrying="state.busy" @retry="refresh" />
   <div class="dashboard-kpis" aria-label="六项固定运营指标">
    <article v-for="(card,index) in kpis" :key="card.id" class="kpi-card" :data-kpi="card.id"><h2>{{card.title}}<span class="metric-indicator" aria-hidden="true" /></h2><strong class="unknown-value" :class="{'reading-value':!state.current}">{{noDataText}}</strong><p :title="card.hint">{{card.hint}}</p><details v-if="state.current" class="metric-reason"><summary>查看指标待采集说明</summary><small>{{state.current.kpis[index]?.unavailable_reason}}</small></details></article>
   </div>
   <div class="dashboard-panels">
    <template v-for="(panel,index) in panels" :key="panel.id"><article class="dashboard-panel" :data-panel="panel.id"><header><h2>{{panel.title}}</h2><div v-if="panel.id==='RESOURCE_TOP5'" class="dashboard-top-switch" aria-label="资源排行指标"><button v-for="name in ['CPU','内存','磁盘']" :key="name" type="button" :class="{active:topMetric===name}" :aria-pressed="topMetric===name" @click="topMetric=name">{{name}}</button></div><span v-else class="panel-status">指标待采集</span></header><div class="unavailable-panel"><span class="panel-empty-icon" aria-hidden="true"><i /></span><strong>{{noDataText}}</strong><p>{{ panel.id==='RESOURCE_TOP5' ? `全量资产 ${topMetric} 排行待采集，已绑定设备采样见上方监控面板` : panel.hint }}</p></div><details v-if="state.current" class="metric-reason panel-reason"><summary>查看待采集说明与统计范围</summary><div class="panel-reason-content"><small>{{state.current.panels[index]?.unavailable_reason}}</small><p v-if="state.current.panels[index]?.period" class="panel-period">统计范围：{{displayTime(state.current.panels[index]!.period!.from)}} 至 {{displayTime(state.current.panels[index]!.period!.to)}}</p></div></details></article><aside v-if="index===0" class="dashboard-severity"><header><h2>当前问题按严重级别</h2><span class="panel-status">事件源待接入</span></header><div class="severity-content"><div class="unknown-donut"><strong>—</strong><span>指标待采集</span></div><ul><li v-for="level in ['灾难','严重','一般','警告','信息']" :key="level"><i />{{level}}<b>—</b><span>待采集</span></li></ul></div></aside></template>
   </div>
   </details>
   <div class="dashboard-toolbar">
   <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox" aria-label="启用监控自动刷新" />启用监控自动刷新（周期以运行配置为准）</label>
   <p class="read-status" role="status" aria-live="polite">{{state.busy?'正在读取当前授权范围':state.error?'当前读取失败，未沿用旧数据':state.current?'采样状态：待接入实时样本 · 时钟同步：待核验 · 统计规则：未采集数据独立标识':'尚未读取'}} · {{autoRefresh?'后台标签页暂停自动刷新':'自动刷新已暂停'}}</p>
   </div>
   <details class="dashboard-explanation"><summary>数据口径与读取说明</summary><div><p v-if="state.current" class="observation" data-testid="observation">数据库观察时间：{{displayTime(state.current.generated_at)}}（未核验时钟同步，不是指标采样时间）<br />本月起点：{{displayTime(state.current.month_start)}}<span v-if="state.current.month_window_empty"> · 当前处于月初起点，累计统计区间为空</span></p><p class="dashboard-footnote">面板布局与指标顺序固定。刷新操作仅读取最新统计状态，不触发新增采样或变更记录。</p></div></details>
  </template>
 </section>
</template>

<style scoped>
.dashboard-page { display: grid; gap: 12px; min-width: 0; }
.dashboard-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 18px; border: 1px solid var(--mop-border, #e2e8f0); border-radius: var(--mop-radius, 6px); background: #fff; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03); }
.title-with-context { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.dashboard-heading h1 { margin: 0; font-size: 18px; font-weight: 600; line-height: 1.5; color: var(--mop-text, #1e293b); }
.dashboard-heading p { margin: 6px 0 0; color: var(--mop-muted, #64748b); font-size: 12px; line-height: 1.6; }
.boundary-details { margin-top: 6px; font-size: 12px; color: var(--mop-muted, #64748b); }
.boundary-details summary { cursor: pointer; font-weight: 500; color: #475569; user-select: none; transition: color 0.15s ease; }
.boundary-details summary:hover { color: #2563eb; }
.tenant-badge { max-width: 100%; padding: 2px 8px; border: 1px solid #dceafd; border-radius: 4px; background: #f0f7ff; color: #286cbf; font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }
.availability-badge { display: inline-flex; align-items: center; gap: 7px; padding: 4px 9px; border: 1px solid #e0e7ee; border-radius: 4px; background: #f8fafc; color: var(--mop-muted, #64748b); white-space: nowrap; font-size: 12px; }
.availability-badge > span { width: 6px; height: 6px; border-radius: 50%; background: #10b981; flex: none; box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.18); }
.formal-dashboard-status { margin: 8px 0; padding: 16px; background: #fff; border: 1px solid #dfe7f0; border-radius: 8px; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02); }
.formal-dashboard-status > summary { cursor: pointer; font-weight: 600; color: #334155; min-height: 28px; display: flex; align-items: center; user-select: none; transition: color 0.15s ease; }
.formal-dashboard-status > summary:hover { color: #1d4ed8; }
.formal-dashboard-status > p { color: #64748b; font-size: 13px; line-height: 1.7; margin: 10px 0 14px; }
.dashboard-header-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; padding-bottom: 12px; border-bottom: 1px solid #edf2f7; }
.dashboard-range-select { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: #475569; }
.dashboard-range-select select, .dashboard-controls button, .dashboard-ranges button, .dashboard-top-switch button { min-height: 34px; padding: 6px 12px; border: 1px solid #cbd5e1; border-radius: 5px; background: #fff; color: #1e293b; font: inherit; font-size: 12px; cursor: pointer; transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease; }
.dashboard-range-select select:hover, .dashboard-controls button:hover:not(:disabled), .dashboard-ranges button:hover:not(:disabled):not(.active), .dashboard-top-switch button:hover:not(.active) { border-color: #93c5fd; background: #f8fbff; color: #1d4ed8; }
.dashboard-ranges, .dashboard-top-switch { display: inline-flex; gap: 6px; flex-wrap: wrap; }
.dashboard-ranges button.active, .dashboard-top-switch button.active { background: #eff6ff; border-color: #3b82f6; color: #1d4ed8; font-weight: 500; }
.dashboard-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.dashboard-controls button:disabled, .dashboard-ranges button:disabled { cursor: wait; opacity: 0.6; }
.dashboard-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; padding: 6px 14px; background: #fff; border: 1px solid var(--mop-border, #e2e8f0); border-radius: 6px; }
.auto-refresh { display: inline-flex; align-items: center; gap: 8px; min-height: 34px; font-size: 12px; color: #475569; cursor: pointer; user-select: none; }
.dashboard-controls :focus-visible, .dashboard-page summary:focus-visible, .dashboard-ranges button:focus-visible, .dashboard-top-switch button:focus-visible { outline: 2px solid var(--mop-focus, #2563eb); outline-offset: 2px; }
.read-status, .observation { margin: 0; color: var(--mop-muted, #64748b); line-height: 1.7; font-size: 12px; font-variant-numeric: tabular-nums; }
.read-status { flex: 1 1 280px; text-align: right; }
.dashboard-kpis { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.kpi-card { display: flex; flex-direction: column; min-width: 0; padding: 14px 16px 10px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fcfdff; transition: border-color 0.18s ease, box-shadow 0.18s ease; }
.kpi-card:hover { border-color: #cbd5e1; box-shadow: 0 2px 6px rgba(15, 23, 42, 0.04); }
.kpi-card h2 { display: flex; justify-content: space-between; align-items: flex-start; gap: 6px; font-size: 12px; font-weight: 500; margin: 0; line-height: 1.5; color: #475569; }
.metric-indicator { flex: none; width: 8px; height: 8px; margin-top: 4px; border: 1px solid #94a3b8; border-radius: 50%; }
.unknown-value { display: block; margin: 10px 0 6px; color: #334155; font-size: 22px; font-weight: 600; line-height: 1.4; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.unknown-value.reading-value { font-size: 15px; }
.kpi-card p { flex: 1; color: #64748b; font-size: 11px; line-height: 1.6; margin: 0 0 6px; }
.metric-reason { color: #64748b; font-size: 12px; border-top: 1px dashed #e2e8f0; padding-top: 4px; }
.metric-reason summary { cursor: pointer; min-height: 30px; line-height: 1.5; padding-block: 4px; font-size: 11px; user-select: none; transition: color 0.15s ease; }
.metric-reason summary:hover { color: #2563eb; }
.dashboard-page small { display: block; overflow-wrap: anywhere; color: #64748b; font-size: 12px; line-height: 1.6; }
.dashboard-panels { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; align-items: start; }
.dashboard-panel, .dashboard-severity { display: flex; flex-direction: column; align-self: stretch; grid-column: span 3; min-width: 0; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; overflow: hidden; transition: border-color 0.18s ease, box-shadow 0.18s ease; }
.dashboard-panel:hover, .dashboard-severity:hover { border-color: #cbd5e1; box-shadow: 0 2px 6px rgba(15, 23, 42, 0.04); }
.dashboard-panel:first-child { grid-column: span 4; }
.dashboard-severity { grid-column: span 2; }
.dashboard-panel header, .dashboard-severity header { display: flex; justify-content: space-between; align-items: center; gap: 12px; min-height: 46px; padding: 10px 16px; border-bottom: 1px solid #edf2f7; background: linear-gradient(90deg, #f8fafc, #fff); }
.dashboard-panel h2, .dashboard-severity h2 { font-size: 13px; font-weight: 600; margin: 0; line-height: 1.5; color: #1e293b; }
.panel-status { flex: none; padding: 2px 8px; border: 1px solid #e2e8f0; border-radius: 4px; background: #f8fafc; color: #64748b; font-size: 11px; }
.unavailable-panel { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; min-height: 164px; padding: 24px 20px 20px; text-align: center; color: #64748b; }
.unavailable-panel > strong { margin-top: 14px; font-size: 14px; font-weight: 600; line-height: 1.5; color: #475569; overflow-wrap: anywhere; }
.unavailable-panel p { max-width: 420px; line-height: 1.7; font-size: 12px; margin: 6px 0 0; }
.severity-content { display: flex; flex-direction: column; gap: 14px; padding: 16px; flex: 1; justify-content: center; }
.unknown-donut { display: flex; align-items: baseline; justify-content: space-between; padding: 10px 14px; border-radius: 6px; background: #f8fafc; border: 1px solid #edf2f7; }
.unknown-donut strong { font-size: 20px; color: #334155; font-variant-numeric: tabular-nums; }
.unknown-donut span { font-size: 12px; color: #64748b; }
.severity-content ul { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; font-size: 12px; color: #475569; }
.severity-content li { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-bottom: 6px; border-bottom: 1px dashed #f1f5f9; }
.severity-content li b { margin-left: auto; color: #334155; font-variant-numeric: tabular-nums; }
.severity-content li span { color: #94a3b8; font-size: 11px; }
.panel-empty-icon { display: block; position: relative; width: 34px; height: 26px; border: 1.5px solid #9eb6d1; border-radius: 3px; background: #f7faff; }
.panel-empty-icon::before, .panel-empty-icon::after, .panel-empty-icon i { content: ''; position: absolute; display: block; }
.panel-empty-icon::before { width: 1px; height: 6px; background: #9eb6d1; bottom: -7px; left: 15px; }
.panel-empty-icon::after { width: 16px; height: 1px; background: #9eb6d1; bottom: -8px; left: 7px; }
.panel-empty-icon i { width: 7px; height: 7px; left: 12px; top: 8px; border: 1px solid #c0cfe0; border-radius: 50%; }
.dashboard-panel[data-panel="SERVICE_HEALTH"] .panel-empty-icon { width: 30px; height: 30px; border-radius: 50%; }
.dashboard-panel[data-panel="SERVICE_HEALTH"] .panel-empty-icon::before { top: 7px; bottom: auto; left: 13px; height: 13px; }
.dashboard-panel[data-panel="SERVICE_HEALTH"] .panel-empty-icon::after { top: 13px; bottom: auto; left: 7px; width: 13px; }
.dashboard-panel[data-panel="SERVICE_HEALTH"] .panel-empty-icon i { display: none; }
.dashboard-panel[data-panel="FAULT_TIMELINE"] .panel-empty-icon { width: 30px; height: 30px; border-radius: 50%; }
.dashboard-panel[data-panel="FAULT_TIMELINE"] .panel-empty-icon::before { top: 6px; bottom: auto; left: 13px; height: 9px; }
.dashboard-panel[data-panel="FAULT_TIMELINE"] .panel-empty-icon::after { top: 14px; bottom: auto; left: 13px; width: 7px; transform: rotate(30deg); transform-origin: left center; }
.dashboard-panel[data-panel="FAULT_TIMELINE"] .panel-empty-icon i { display: none; }
.dashboard-panel[data-panel="RECENT_EVENTS"] .panel-empty-icon, .dashboard-panel[data-panel="PENDING_TICKETS"] .panel-empty-icon { width: 27px; height: 32px; }
.dashboard-panel[data-panel="RECENT_EVENTS"] .panel-empty-icon::before, .dashboard-panel[data-panel="PENDING_TICKETS"] .panel-empty-icon::before { left: 6px; top: 10px; bottom: auto; width: 12px; height: 1px; }
.dashboard-panel[data-panel="RECENT_EVENTS"] .panel-empty-icon::after, .dashboard-panel[data-panel="PENDING_TICKETS"] .panel-empty-icon::after { left: 6px; top: 17px; bottom: auto; width: 9px; }
.dashboard-panel[data-panel="RECENT_EVENTS"] .panel-empty-icon i, .dashboard-panel[data-panel="PENDING_TICKETS"] .panel-empty-icon i { display: none; }
.dashboard-panel[data-panel="RESOURCE_TOP5"] .panel-empty-icon { width: 32px; height: 29px; }
.dashboard-panel[data-panel="RESOURCE_TOP5"] .panel-empty-icon::before { left: 0; top: 12px; bottom: auto; width: 29px; height: 1px; }
.dashboard-panel[data-panel="RESOURCE_TOP5"] .panel-empty-icon::after { left: 5px; top: 6px; bottom: auto; width: 4px; }
.dashboard-panel[data-panel="RESOURCE_TOP5"] .panel-empty-icon i { left: 5px; top: 19px; width: 4px; height: 1px; background: #9eb6d1; border: 0; }
.panel-reason { border-top: 1px solid #eef2f7; background: #fcfdff; }
.panel-reason summary { padding: 9px 16px; }
.panel-reason-content { padding: 0 16px 12px; }
.panel-period { margin: 7px 0 0; font-size: 11px; line-height: 1.7; color: #64748b; font-variant-numeric: tabular-nums; }
.dashboard-explanation { padding: 0 14px; border: 1px solid var(--mop-border, #e2e8f0); border-radius: 6px; background: #fff; color: #64748b; font-size: 12px; }
.dashboard-explanation > summary { min-height: 38px; padding: 9px 0; cursor: pointer; line-height: 1.6; font-weight: 500; color: #475569; user-select: none; transition: color 0.15s ease; }
.dashboard-explanation > summary:hover { color: #2563eb; }
.dashboard-explanation > div { padding: 0 0 12px; }
.dashboard-footnote { margin: 7px 0 0; font-size: 11px; line-height: 1.7; color: #64748b; }
@media (min-width: 1280px) {
  .dashboard-kpis { grid-template-columns: repeat(6, minmax(0, 1fr)); }
  .kpi-card h2 { min-height: 38px; }
}
@media (max-width: 1000px) {
  .read-status { flex-basis: 100%; text-align: left; }
  .dashboard-panel:first-child, .dashboard-severity { grid-column: span 3; }
}
@media(max-width:760px) {
  .dashboard-page { gap: 10px; }
  .dashboard-heading { align-items: flex-start; flex-wrap: wrap; gap: 10px; padding: 14px; }
  .dashboard-heading h1 { font-size: 18px; }
  .dashboard-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .kpi-card { padding: 13px 12px 6px; }
  .dashboard-panels { grid-template-columns: 1fr; }
  .dashboard-panel, .dashboard-panel:first-child, .dashboard-severity { grid-column: auto; }
  .dashboard-controls { align-items: stretch; gap: 6px 10px; }
  .dashboard-controls select, .dashboard-controls button, .dashboard-range-select select, .dashboard-ranges button, .auto-refresh { min-height:44px; }
  .metric-reason summary, .dashboard-explanation > summary { min-height:44px; padding-block: 12px; }
  .unavailable-panel { min-height: 160px; padding-inline: 16px; }
  .availability-badge { white-space: normal; }
}
@media (max-width: 360px) {
  .dashboard-kpis { grid-template-columns: 1fr; }
  .dashboard-controls { width: 100%; }
}
</style>
