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
const kpis=[{id:'ASSET_ONLINE_RATE',title:'资产在线率',hint:'正常 / 未知 / 总数均未采集'},{id:'CURRENT_PROBLEMS',title:'当前问题数',hint:'不把缺失事件当作零告警'},{id:'MONTH_SERVICE_AVAILABILITY',title:'本月服务可用率',hint:'上海时间当月1日零点起'},{id:'PENDING_TICKETS',title:'待处理工单',hint:'正式状态与超时SLA口径尚不可用'},{id:'MTTA_24H',title:'最近24小时 MTTA',hint:'需要真实事件与确认时间'},{id:'DELIVERY_RATE_24H',title:'最近24小时送达率',hint:'受理不等于送达，缺少供应商回执'}]
const panels=[{id:'ALERT_TREND',title:'告警趋势',hint:'暂无已接入的事件读取器，不绘制模拟曲线'},{id:'SERVICE_HEALTH',title:'服务健康概览',hint:'没有服务区间样本，不能判断正常或故障'},{id:'FAULT_TIMELINE',title:'最近24小时故障时间轴',hint:'合同最多8行；无真实区间，不生成虚构故障段'},{id:'RECENT_EVENTS',title:'近期事件',hint:'合同最近7条；未知不代表没有事件'},{id:'PENDING_TICKETS',title:'待处理工单列表',hint:'合同最多5条；人工登记不能替代正式工单状态'},{id:'RESOURCE_TOP5',title:'资源使用 Top5',hint:'正式全资产排行尚未接入；当前绑定设备排行见上方'}]
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
  <header class="dashboard-heading"><div><div class="title-with-context"><h1>运营总览</h1><span class="tenant-badge">{{session?.tenant.name}}</span><span class="availability-badge"><span aria-hidden="true" />实际数据工作区</span></div><details class="boundary-details"><summary>页面说明</summary><p>服务健康、事件与履约概览 · 北京时间（Asia/Shanghai）。设备监控使用当前租户实际绑定与采样。正式事件、SLA和送达统计分别保留接入状态，未知值不代表正常或零事件。</p></details></div></header>
  <p v-if="!permitted" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据</p>
  <template v-else>
   <LiveMonitoringPanel v-if="can('assets:read')" :auto-refresh="autoRefresh" />
   <DashboardReportsPanel v-if="can('tickets:read')" />
   <details class="formal-dashboard-status"><summary>正式运营指标与接入进度</summary><div class="dashboard-header-actions">   <label class="dashboard-range-select">正式指标范围 <select aria-label="告警趋势范围" :value="state.window" @change="chooseWindow"><option value="HOURS_24">最近24小时</option><option value="DAYS_7">最近7天</option><option value="DAYS_30">最近30天</option></select></label><div class="dashboard-ranges" aria-label="告警趋势快捷范围"><button v-for="range in [{id:'HOURS_24',label:'24小时'},{id:'DAYS_7',label:'7天'},{id:'DAYS_30',label:'30天'}]" :key="range.id" type="button" :aria-pressed="state.window===range.id" :class="{active:state.window===range.id}" :disabled="!permitted||state.busy" @click="changeWindow(range.id)">{{range.label}}</button></div><div class="dashboard-controls"><button type="button" :disabled="state.busy||!permitted" @click="refresh">{{state.busy?'读取中…':'刷新统计状态'}}</button></div></div><p>以下统计依赖正式事件、工单、服务区间与送达回执。设备采样单独展示，不换算为合同服务指标。</p>
   <ApiErrorNotice :error="state.error" :retrying="state.busy" @retry="refresh" />
   <div class="dashboard-kpis" aria-label="六项固定运营指标">
    <article v-for="(card,index) in kpis" :key="card.id" class="kpi-card" :data-kpi="card.id"><h2>{{card.title}}<span class="metric-indicator" aria-hidden="true" /></h2><strong class="unknown-value" :class="{'reading-value':!state.current}">{{noDataText}}</strong><p :title="card.hint">{{card.hint}}</p><details v-if="state.current" class="metric-reason"><summary>查看未接入原因</summary><small>{{state.current.kpis[index]?.unavailable_reason}}</small></details></article>
   </div>
   <div class="dashboard-panels">
    <template v-for="(panel,index) in panels" :key="panel.id"><article class="dashboard-panel" :data-panel="panel.id"><header><h2>{{panel.title}}</h2><div v-if="panel.id==='RESOURCE_TOP5'" class="dashboard-top-switch" aria-label="资源排行指标"><button v-for="name in ['CPU','内存','磁盘']" :key="name" type="button" :class="{active:topMetric===name}" :aria-pressed="topMetric===name" @click="topMetric=name">{{name}}</button></div><span v-else class="panel-status">未采集</span></header><div class="unavailable-panel"><span class="panel-empty-icon" aria-hidden="true"><i /></span><strong>{{noDataText}}</strong><p>{{ panel.id==='RESOURCE_TOP5' ? `正式全资产${topMetric}排行未接入，当前绑定设备采样见上方` : panel.hint }}</p></div><details v-if="state.current" class="metric-reason panel-reason"><summary>查看未接入原因与统计范围</summary><div class="panel-reason-content"><small>{{state.current.panels[index]?.unavailable_reason}}</small><p v-if="state.current.panels[index]?.period" class="panel-period">统计范围：{{displayTime(state.current.panels[index]!.period!.from)}} 至 {{displayTime(state.current.panels[index]!.period!.to)}}</p></div></details></article><aside v-if="index===0" class="dashboard-severity"><header><h2>当前问题按严重级别</h2><span>事件源待接入</span></header><div class="severity-content"><div class="unknown-donut"><strong>—</strong><span>问题数量未知</span></div><ul><li v-for="level in ['灾难','严重','一般','警告','信息']" :key="level"><i />{{level}}<b>—</b><span>未知</span></li></ul></div></aside></template>
   </div>
   </details>
   <div class="dashboard-toolbar">

   <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox" aria-label="每30秒自动只读刷新" />每30秒只读刷新</label>
   <p class="read-status" role="status" aria-live="polite">{{state.busy?'正在读取当前授权范围':state.error?'当前读取失败，未沿用旧数据':state.current?'授权与时间投影已读取；指标仍未知':'尚未读取'}} · {{autoRefresh?'后台标签页暂停自动刷新':'自动刷新已暂停'}}</p>
   </div>
   <details class="dashboard-explanation"><summary>数据口径与读取说明</summary><div><p v-if="state.current" class="observation" data-testid="observation">数据库观察时间：{{displayTime(state.current.generated_at)}}（未核验时钟同步，不是指标采样时间）<br />本月起点：{{displayTime(state.current.month_start)}}<span v-if="state.current.month_window_empty"> · 正好处于月初，累计区间为空</span></p><p class="dashboard-footnote">布局与顺序固定，不提供拖拽、隐藏或保存布局。刷新只发起GET，不新增工单、收据或监控样本</p></div></details>
  </template>
 </section>
</template>

<style scoped>
.formal-dashboard-status{margin:18px 0;padding:16px;background:#fff;border:1px solid #dfe7f0;border-radius:8px}.formal-dashboard-status>summary{cursor:pointer;font-weight:600;color:#425775;min-height:28px}.formal-dashboard-status>p{color:#64748b;font-size:13px;line-height:1.7}

.dashboard-page { display: grid; gap: 12px; min-width: 0; }
.dashboard-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 18px; border: 1px solid var(--mop-border); border-radius: var(--mop-radius,4px); background: #fff; }
.dashboard-heading h1 { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 0 0 5px; font-size: 18px; font-weight: 600; line-height: 1.5; color: var(--mop-text); }
.dashboard-heading p { margin: 0; color: var(--mop-muted); font-size: 12px; line-height: 1.6; }
.tenant-badge { max-width: 100%; padding: 2px 8px; border: 1px solid #dceafd; border-radius: 3px; background: #f0f7ff; color: #286cbf; font-size: 12px; font-weight: 400; overflow-wrap: anywhere; }
.availability-badge { display: inline-flex; align-items: center; gap: 7px; padding: 5px 9px; border: 1px solid #e0e7ee; border-radius: 3px; background: #f8fafc; color: var(--mop-muted); white-space: nowrap; font-size: 12px; }
.availability-badge > span { width: 6px; height: 6px; border-radius: 50%; background: #94a3b8; flex: none; }
.dashboard-boundary { display: flex; align-items: flex-start; gap: 9px; margin: 0; padding: 10px 14px; border: 1px solid #eadfc8; border-radius: var(--mop-radius,4px); background: #fffcf5; line-height: 1.7; color: #786039; font-size: 12px; }
.boundary-icon { display: inline-grid; place-items: center; flex: none; width: 15px; height: 15px; margin-top: 3px; border: 1px solid currentColor; border-radius: 50%; font-size: 10px; font-weight: 600; line-height: 1; }
.dashboard-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; padding: 2px 0; }
.dashboard-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.dashboard-controls label { display: flex; gap: 8px; align-items: center; font-size: 12px; color: var(--mop-muted); }
.dashboard-controls select, .dashboard-controls button { min-height: 34px; padding: 6px 10px; border: 1px solid var(--mop-border); border-radius: var(--mop-radius,4px); background: #fff; color: var(--mop-text); font: inherit; font-size: 12px; }
.dashboard-controls button { cursor: pointer; white-space: nowrap; }
.dashboard-controls button:disabled { cursor: wait; }
.dashboard-controls input { width: 15px; height: 15px; margin: 0; }
.auto-refresh { min-height: 34px; }
.dashboard-controls :focus-visible, .dashboard-page summary:focus-visible { outline: 2px solid var(--mop-focus); outline-offset: 3px; }
.read-status, .observation { margin: 0; color: var(--mop-muted); line-height: 1.7; font-size: 11px; }
.read-status { flex: 1 1 280px; text-align: right; }
.dashboard-kpis { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 10px; }
.kpi-card { display: flex; flex-direction: column; min-width: 0; padding: 14px 15px 8px; border: 1px solid var(--mop-border); border-radius: var(--mop-radius,4px); background: #fff; }
.kpi-card h2 { display: flex; justify-content: space-between; align-items: flex-start; gap: 6px; font-size: 12px; font-weight: 400; margin: 0; line-height: 1.6; color: var(--mop-muted); }
.metric-indicator { flex: none; width: 8px; height: 8px; margin-top: 5px; border: 1px solid #afbed0; border-radius: 50%; }
.unknown-value { display: block; margin: 10px 0 6px; color: #526881; font-size: 24px; font-weight: 500; line-height: 1.4; overflow-wrap: anywhere; }
.unknown-value.reading-value { font-size: 16px; }
.kpi-card p { flex: 1; color: var(--mop-muted); font-size: 11px; line-height: 1.7; margin: 0 0 5px; }
.metric-reason { color: var(--mop-muted); font-size: 12px; }
.metric-reason summary { cursor: pointer; min-height: 32px; line-height: 1.5; padding-block: 7px; font-size: 11px; }
.metric-reason summary:hover { color: var(--el-color-primary); }
.dashboard-page small { display: block; overflow-wrap: anywhere; color: var(--mop-muted); font-size: 12px; line-height: 1.6; }
.dashboard-panels { display: grid; grid-template-columns: repeat(6,minmax(0,1fr)); gap: 12px; align-items: start; }
.dashboard-panel { display: flex; flex-direction: column; align-self: stretch; grid-column: span 3; min-width: 0; border: 1px solid var(--mop-border); border-radius: var(--mop-radius,4px); background: #fff; overflow: hidden; }
.dashboard-panel:first-child { grid-column: span 4; }
.dashboard-panel:nth-child(2) { grid-column: span 2; }
.dashboard-panel header { display: flex; justify-content: space-between; align-items: center; gap: 12px; min-height: 48px; padding: 11px 16px; border-bottom: 1px solid var(--mop-border); background: linear-gradient(90deg,#f7faff,#fff); }
.dashboard-panel h2 { font-size: 13px; font-weight: 500; margin: 0; line-height: 1.6; color: var(--mop-text); }
.panel-status { flex: none; padding: 1px 7px; border: 1px solid #e4eaf1; border-radius: 3px; background: #f8fafc; color: var(--mop-muted); font-size: 11px; }
.unavailable-panel { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; min-height: 166px; padding: 24px 20px 20px; text-align: center; color: var(--mop-muted); }
.unavailable-panel > strong { margin-top: 15px; font-size: 14px; font-weight: 500; line-height: 1.6; overflow-wrap: anywhere; }
.unavailable-panel p { max-width: 420px; line-height: 1.7; font-size: 12px; margin: 6px 0 0; }
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
.panel-period { margin: 7px 0 0; font-size: 11px; line-height: 1.7; color: var(--mop-muted); }
.dashboard-explanation { padding: 0 14px; border: 1px solid var(--mop-border); border-radius: var(--mop-radius,4px); background: #fff; color: var(--mop-muted); font-size: 12px; }
.dashboard-explanation > summary { min-height: 38px; padding: 9px 0; cursor: pointer; line-height: 1.6; }
.dashboard-explanation > div { padding: 0 0 12px; }
.dashboard-footnote { margin: 7px 0 0; font-size: 11px; line-height: 1.7; color: var(--mop-muted); }
@media (min-width: 1280px) {
  .dashboard-kpis { grid-template-columns: repeat(6,minmax(0,1fr)); }
  .kpi-card h2 { min-height: 38px; }
}
@media (max-width: 1000px) {
  .read-status { flex-basis: 100%; text-align: left; }
  .dashboard-panel:first-child, .dashboard-panel:nth-child(2) { grid-column: span 3; }
}
@media(max-width:760px) {
  .dashboard-page { gap: 10px; }
  .dashboard-heading { align-items: flex-start; flex-wrap: wrap; gap: 10px; padding: 14px; }
  .dashboard-heading h1 { font-size: 18px; }
  .dashboard-kpis { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .kpi-card { padding: 13px 12px 5px; }
  .dashboard-panels { grid-template-columns: 1fr; }
  .dashboard-panel, .dashboard-panel:first-child, .dashboard-panel:nth-child(2) { grid-column: auto; }
  .dashboard-controls { align-items: stretch; gap: 6px 10px; }
  .dashboard-controls label { flex-wrap: wrap; font-size: 12px; }
  .dashboard-controls select, .dashboard-controls button, .auto-refresh { min-height:44px; }
  .dashboard-controls select { max-width: 100%; }
  .dashboard-controls input { width: 18px; height: 18px; }
  .metric-reason summary, .dashboard-explanation > summary { min-height:44px; padding-block: 12px; }
  .unavailable-panel { min-height: 160px; padding-inline: 16px; }
  .availability-badge { white-space: normal; }
}
@media (max-width: 360px) {
  .dashboard-kpis { grid-template-columns: 1fr; }
  .dashboard-controls { width: 100%; }
  .dashboard-controls > label:first-child { width: 100%; }
}
</style>
