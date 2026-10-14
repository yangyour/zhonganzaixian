<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {createDashboardWorkspace,emptyDashboardState,type DashboardState} from '../utils/dashboardWorkspace'
import {validDashboardWindow,type DashboardWindow} from '../utils/dashboardAvailability'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'

const state=shallowRef<DashboardState>(emptyDashboardState()),autoRefresh=ref(true)
const workspace=createDashboardWorkspace(api,next=>{state.value=next})
let disposed=false,timer:ReturnType<typeof setInterval>|null=null
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['viewer','tadmin','msp','padmin'].includes(session.value.user.role)&&can('dashboard:read')&&!switchingTenant.value))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value]))
const kpis=[{id:'ASSET_ONLINE_RATE',title:'资产在线率',hint:'正常 / 未知 / 总数均未采集'},{id:'CURRENT_PROBLEMS',title:'当前问题数',hint:'不把缺失事件当作零告警'},{id:'MONTH_SERVICE_AVAILABILITY',title:'本月服务可用率',hint:'上海时间当月1日零点起'},{id:'PENDING_TICKETS',title:'待处理工单',hint:'正式状态与超时SLA口径尚不可用'},{id:'MTTA_24H',title:'最近24小时 MTTA',hint:'需要真实事件与确认时间'},{id:'DELIVERY_RATE_24H',title:'最近24小时送达率',hint:'受理不等于送达，缺少供应商回执'}]
const panels=[{id:'ALERT_TREND',title:'告警趋势',hint:'暂无已接入的事件读取器，不绘制模拟曲线'},{id:'SERVICE_HEALTH',title:'服务健康概览',hint:'没有服务区间样本，不能判断正常或故障'},{id:'FAULT_TIMELINE',title:'最近24小时故障时间轴',hint:'合同最多8行；无真实区间，不生成虚构故障段'},{id:'RECENT_EVENTS',title:'近期事件',hint:'合同最近7条；未知不代表没有事件'},{id:'PENDING_TICKETS',title:'待处理工单列表',hint:'合同最多5条；人工登记不能替代正式工单状态'},{id:'RESOURCE_TOP5',title:'资源使用 Top5',hint:'CPU / 内存 / 磁盘未采集，不对未知资源排名'}]
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
  <header class="dashboard-heading"><div><p class="dashboard-eyebrow">MOP · 当前授权租户</p><h1>运营总览</h1><p>{{session?.tenant.name}} · 北京时间（Asia/Shanghai）</p></div><span class="availability-badge">监控指标未接入</span></header>
  <p class="dashboard-boundary" role="note">这里仅核对当前授权与数据库观察时间。未知指标不表示正常、零事件或送达成功；首页尚未完成正式监控验收</p>
  <p v-if="!permitted" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据</p>
  <template v-else>
   <div class="dashboard-controls">
    <label>告警趋势范围 <select aria-label="告警趋势范围" :value="state.window" @change="chooseWindow"><option value="HOURS_24">最近24小时</option><option value="DAYS_7">最近7天</option><option value="DAYS_30">最近30天</option></select></label>
    <button type="button" :disabled="state.busy" @click="refresh">{{state.busy?'读取中…':'刷新授权状态'}}</button>
    <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox" aria-label="每30秒自动只读刷新" />每30秒只读刷新</label>
   </div>
   <p class="read-status" role="status" aria-live="polite">{{state.busy?'正在读取当前授权范围':state.error?'当前读取失败，未沿用旧数据':state.current?'授权与时间投影已读取；指标仍未知':'尚未读取'}} · {{autoRefresh?'后台标签页暂停自动刷新':'自动刷新已暂停'}}</p>
   <ApiErrorNotice :error="state.error" :retrying="state.busy" @retry="refresh" />
   <p v-if="state.current" class="observation" data-testid="observation">数据库观察时间：{{displayTime(state.current.generated_at)}}（未核验时钟同步，不是指标采样时间）<br />本月起点：{{displayTime(state.current.month_start)}}<span v-if="state.current.month_window_empty"> · 正好处于月初，累计区间为空</span></p>
   <div class="dashboard-kpis" aria-label="六项固定运营指标">
    <article v-for="(card,index) in kpis" :key="card.id" class="kpi-card" :data-kpi="card.id"><h2>{{card.title}}</h2><strong class="unknown-value">{{noDataText}}</strong><p>{{card.hint}}</p><details v-if="state.current" class="metric-reason"><summary>查看未接入原因</summary><small>{{state.current.kpis[index]?.unavailable_reason}}</small></details></article>
   </div>
   <div class="dashboard-panels">
    <article v-for="(panel,index) in panels" :key="panel.id" class="dashboard-panel" :data-panel="panel.id"><header><h2>{{panel.title}}</h2><span>未采集</span></header><p v-if="state.current?.panels[index]?.period" class="panel-period">{{displayTime(state.current.panels[index]!.period!.from)}} 至 {{displayTime(state.current.panels[index]!.period!.to)}}</p><div class="unavailable-panel"><strong>{{noDataText}}</strong><p>{{panel.hint}}</p><details v-if="state.current" class="metric-reason"><summary>查看未接入原因</summary><small>{{state.current.panels[index]?.unavailable_reason}}</small></details></div></article>
   </div>
   <footer class="dashboard-footnote">布局与顺序固定，不提供拖拽、隐藏或保存布局。刷新只发起GET，不新增工单、收据或监控样本</footer>
  </template>
 </section>
</template>

<style scoped>
.metric-reason{color:#596d84;font-size:12px}.metric-reason summary{cursor:pointer;min-height:32px;line-height:32px}.metric-reason small{display:block;overflow-wrap:anywhere}

.dashboard-page{display:grid;gap:18px;min-width:0}.dashboard-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.dashboard-heading h1{margin:4px 0 8px;font-size:28px;color:#172c46}.dashboard-heading p{margin:0;color:#52657a}.dashboard-eyebrow{font-size:12px;letter-spacing:.1em}.availability-badge{padding:9px 12px;border:1px solid #cdd7e3;border-radius:8px;background:#f3f6fa;color:#3b5067;white-space:nowrap}.dashboard-boundary{margin:0;padding:14px 16px;background:#fff8e8;border-left:3px solid #aa7620;line-height:1.65;color:#684917}.dashboard-controls{display:flex;flex-wrap:wrap;align-items:center;gap:14px}.dashboard-controls label{display:flex;gap:10px;align-items:center}.dashboard-controls select,.dashboard-controls button{min-height:44px;padding:8px 12px;border:1px solid #adbccd;border-radius:7px;background:#fff;color:#203950;font:inherit}.dashboard-controls button{cursor:pointer}.dashboard-controls button:disabled{cursor:wait;color:#65758a}.dashboard-controls input{width:18px;height:18px}.auto-refresh{min-height:44px}.dashboard-controls :focus-visible{outline:3px solid #4c8fd5;outline-offset:3px}.read-status,.observation{margin:0;color:#52657a;line-height:1.7;font-size:13px}.dashboard-kpis{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.kpi-card,.dashboard-panel{border:1px solid #dce4ed;border-radius:10px;background:#fff;padding:20px;min-width:0}.kpi-card h2,.dashboard-panel h2{font-size:16px;margin:0;color:#294158}.unknown-value{display:block;margin:18px 0;color:#63788d;font-size:24px;line-height:1.4}.kpi-card p{color:#586c81;font-size:13px;line-height:1.6}.dashboard-page small{display:block;overflow-wrap:anywhere;color:#687c90;font-size:11px;line-height:1.5}.dashboard-panels{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.dashboard-panel:first-child{grid-column:1/-1}.dashboard-panel header{display:flex;justify-content:space-between;gap:10px}.dashboard-panel header span{color:#677b8e;font-size:12px}.panel-period{font-size:12px;line-height:1.6;color:#60758a}.unavailable-panel{padding:24px 12px;margin-top:16px;border:1px dashed #c7d4e1;border-radius:8px;text-align:center;background:#f8fafc;color:#5a7085}.unavailable-panel p{line-height:1.6;font-size:13px}.dashboard-footnote{font-size:12px;line-height:1.7;color:#617589}@media(max-width:760px){.dashboard-heading{flex-direction:column}.dashboard-kpis,.dashboard-panels{grid-template-columns:1fr}.dashboard-panel:first-child{grid-column:auto}.dashboard-controls{align-items:stretch}.dashboard-controls label{flex-wrap:wrap}.dashboard-controls select{max-width:100%}.kpi-card,.dashboard-panel{padding:16px}.unknown-value{font-size:22px}.availability-badge{white-space:normal}}
</style>
