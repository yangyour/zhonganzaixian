<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api,ApiError,isCancelled} from '../api/client'
import {session,can,switchingTenant,contextGeneration} from '../stores/session'
import {readMonitoringWorkspace,type MonitoringEventSummary} from '../utils/monitoringEvents'
import {utcInstantNanos} from '../utils/utcInstant'
import {displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const events=shallowRef<MonitoringEventSummary[]|null>(null),observed=ref(''),busy=ref(false),error=ref<unknown>(null)
const permitted=computed(()=>session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('dashboard:read')&&can('tickets:read')&&!switchingTenant.value)
let epoch=0,abort:AbortController|null=null,alive=true
function reset(){++epoch;abort?.abort();abort=null;events.value=null;observed.value='';error.value=null;busy.value=false}
async function load(){
 if(!alive||!permitted.value||!session.value||busy.value)return
 const s=session.value,scope={tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,canWriteInternal:false,canAdminConfirm:false},turn=++epoch,controller=new AbortController();abort=controller;events.value=null;observed.value='';error.value=null;busy.value=true
 try{
  // window only controls the unused noise funnel, not the event list's time range.
  const raw=await api.request('/events?window=24h&page_size=7',{method:'GET',signal:controller.signal})
  if(!alive||turn!==epoch||controller.signal.aborted)return
  const result=readMonitoringWorkspace(raw,scope)
  if(result.events.length>7||result.events.some((e,i)=>{const before=result.events[i-1];return before&&(utcInstantNanos(e.last_occurred_at)!>utcInstantNanos(before.last_occurred_at)!||utcInstantNanos(e.last_occurred_at)===utcInstantNanos(before.last_occurred_at)&&e.id>=before.id)}))throw new ApiError(0,'INVALID_RECENT_EVENTS','最近事件数量或时间排序未通过核对','—')
  events.value=result.events;observed.value=result.observed_at
 }catch(cause){if(alive&&turn===epoch&&!isCancelled(cause))error.value=cause}
 finally{if(alive&&turn===epoch){busy.value=false;abort=null}}
}
watch(()=>[contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value],()=>{reset();if(permitted.value)void load()},{immediate:true,flush:'sync'})
onUnmounted(()=>{alive=false;reset()})
const severity={DISASTER:'灾难',HIGH:'严重',AVERAGE:'一般',WARNING:'警告',INFO:'信息'}
const handling={NEW:'新事件',ACKNOWLEDGED:'已确认',IN_PROGRESS:'处理中',PENDING_REVIEW:'待复核',RESOLVED:'已解决',CLOSED:'已关闭'}
const suppression={NONE:'无抑制',FLAP_SILENCED:'抖动静默',DEPENDENCY_SUPPRESSED:'依赖抑制',MAINTENANCE_SILENCED:'维护静默',INFO_DEFAULT_SILENT:'信息级默认静默'}
defineExpose({ refresh: load })
</script>
<template>
<section v-if="permitted" class="dashboard-events" aria-labelledby="dashboard-events-title" :aria-busy="busy">
 <header class="de-head"><h2 id="dashboard-events-title">最近事件</h2><div class="actions"><button type="button" :disabled="busy" @click="load">{{busy?'读取中…':'刷新近期事件'}}</button><router-link to="/monitoring-events">事件中心</router-link></div></header>
 <ApiErrorNotice :error="error" :retrying="busy" @retry="load" />
 <p v-if="!events" role="status" class="de-state">{{busy?'正在读取近期事件…':error?'事件读取失败，未沿用旧列表。':'尚未读取事件。'}}</p>
 <template v-else>
  <p role="status" class="de-meta">已读取 {{events.length}} 条近期记录 · {{displayTime(observed)}}</p>
  <p v-if="!events.length" class="de-state">当前查询没有事件记录（不代表全量设备健康）。</p>
  <ol v-else class="events"><li v-for="event in events" :key="event.id" :class="'sev-'+event.severity.toLowerCase()"><span class="sev">{{severity[event.severity]}}</span><div class="ev-body"><h3><router-link :to="{path:'/monitoring-events',query:{event_id:event.id}}" :aria-label="event.rule_name+'，查看事件详情'">{{event.rule_name}}</router-link></h3><p><span :class="event.monitor_state==='PROBLEM'?'st-problem':'st-ok'">{{event.monitor_state==='PROBLEM'?'异常':'已恢复'}}</span> · {{handling[event.handling_state]}}<template v-if="event.suppression_state!=='NONE'"> · {{suppression[event.suppression_state]}}</template> · {{event.occurrence_count}} 次</p></div><time :datetime="event.last_occurred_at">{{displayTime(event.last_occurred_at)}}</time></li></ol>
 </template>
</section>
</template>
<style scoped>
.dashboard-events{min-width:0;padding:16px 18px;border:1px solid #e3e9f2;background:#fff;border-radius:10px;color:#24374a}
.de-head,.actions{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}
.dashboard-events h2{font-size:15px;font-weight:600;margin:0;color:#0f172a}
.actions button,.actions a{display:inline-flex;align-items:center;min-height:32px;box-sizing:border-box;padding:0 12px;color:#1d4ed8;background:#fff;border:1px solid #d5deea;border-radius:6px;font:inherit;font-size:13px;text-decoration:none;cursor:pointer}
.actions button:hover:not(:disabled),.actions a:hover{background:#eff6ff;border-color:#93c5fd}
.actions button:disabled{opacity:.6;cursor:default}
.dashboard-events :focus-visible{outline:2px solid #2563eb;outline-offset:2px}
.de-meta{margin:8px 0 4px;font-size:12px;color:#64748b;font-variant-numeric:tabular-nums}
.de-state{margin:12px 0 0;padding:20px;border-radius:8px;background:#f8fafc;color:#475569;font-size:13px;text-align:center}
.events{list-style:none;margin:4px 0 0;padding:0}
.events li{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:12px;padding:10px 0;border-top:1px solid #eef2f7}
.events li:first-child{border-top:0}
.sev{display:inline-flex;align-items:center;justify-content:center;min-width:40px;height:22px;padding:0 8px;border-radius:999px;font-size:12px;font-weight:600;background:#f1f5f9;color:#475569}
.sev-disaster .sev{background:#fee2e2;color:#991b1b}.sev-high .sev{background:#ffedd5;color:#9a3412}.sev-average .sev{background:#fef3c7;color:#92400e}.sev-warning .sev{background:#fef9c3;color:#854d0e}.sev-info .sev{background:#e0f2fe;color:#075985}
.ev-body{min-width:0}
.events h3{margin:0;font-size:14px;font-weight:600;line-height:1.4}
.events h3 a{color:#0f172a;text-decoration:none;overflow-wrap:anywhere}
.events h3 a:hover{color:#1d4ed8;text-decoration:underline}
.ev-body p{margin:2px 0 0;font-size:12px;color:#64748b}
.st-problem{color:#b91c1c;font-weight:600}.st-ok{color:#15803d;font-weight:600}
.events time{font-size:12px;color:#64748b;white-space:nowrap;font-variant-numeric:tabular-nums}
@media(max-width:760px){.dashboard-events{padding:14px}.actions button,.actions a{min-height:44px}.events li{grid-template-columns:auto minmax(0,1fr)}.events time{grid-column:2}.events h3 a{display:inline-block;min-height:24px}}
</style>
