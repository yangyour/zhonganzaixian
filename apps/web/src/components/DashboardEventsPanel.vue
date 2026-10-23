<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api,ApiError,isCancelled} from '../api/client'
import {session,can,switchingTenant,contextGeneration} from '../stores/session'
import {readMonitoringWorkspace,type MonitoringEventSummary} from '../utils/monitoringEvents'
import {utcInstantNanos} from '../utils/utcInstant'
import {compactTime,displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const events=shallowRef<MonitoringEventSummary[]|null>(null),observed=ref(''),busy=ref(false),error=ref<unknown>(null)
const assetLinks=computed(()=>can('assets:read'))
const shortId=(value:string)=>value.slice(-6)
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
 <header class="de-head"><div class="de-title"><h2 id="dashboard-events-title">最近事件</h2><span>最近 7 条</span></div><div class="actions"><button type="button" :disabled="busy" @click="load">{{busy?'读取中…':'刷新近期事件'}}</button><router-link to="/monitoring-events">事件中心</router-link></div></header>
 <ApiErrorNotice :error="error" :retrying="busy" @retry="load" />
 <p v-if="!events" role="status" class="de-state">{{busy?'正在读取近期事件…':error?'事件读取失败，未沿用旧列表。':'尚未读取事件。'}}</p>
 <template v-else>
  <p role="status" class="de-meta">已读取 {{events.length}} 条近期记录 · {{displayTime(observed)}}</p>
  <p v-if="!events.length" class="de-state">当前查询没有事件记录（不代表全量设备健康）。</p>
  <div v-else class="events-scroll" tabindex="0" role="region" aria-label="最近事件，可横向滚动">
   <table class="events mop-table">
    <caption class="sr-only">最近 {{events.length}} 条事件，按最近发生时间排序</caption>
    <thead><tr><th scope="col">级别</th><th scope="col">问题</th><th scope="col">资产</th><th scope="col">状态</th><th scope="col">发生时间</th></tr></thead>
    <tbody>
     <tr v-for="event in events" :key="event.id" :class="'sev-'+event.severity.toLowerCase()">
      <td><span class="sev mop-sev" :class="event.severity">{{severity[event.severity]}}</span></td>
      <td class="ev-problem"><router-link class="event-link" :to="{path:'/monitoring-events',query:{event_id:event.id}}" :aria-label="event.rule_name+'，查看事件详情'">{{event.rule_name}}</router-link><span class="ev-flags"><span v-if="event.occurrence_count>1" class="mop-pill-count" :title="'合并 '+event.occurrence_count+' 条原始事件'">×{{event.occurrence_count}}</span><span v-if="event.suppression_state!=='NONE'" class="mop-chip-tag" :title="suppression[event.suppression_state]">已抑制</span></span></td>
      <td><router-link v-if="assetLinks" class="ev-asset" :to="{path:'/assets',query:{asset_id:event.asset_id}}" :title="event.asset_id" :aria-label="'查看事件资产 '+event.asset_id">…{{shortId(event.asset_id)}}</router-link><span v-else class="ev-asset" :title="event.asset_id">…{{shortId(event.asset_id)}}</span></td>
      <td><span class="mop-badge" :class="event.monitor_state==='PROBLEM'?'danger':'ok'">{{event.monitor_state==='PROBLEM'?'异常':'已恢复'}}</span><small>{{handling[event.handling_state]}}<template v-if="event.suppression_state!=='NONE'"> · {{suppression[event.suppression_state]}}</template></small></td>
      <td><time :datetime="event.last_occurred_at" :title="displayTime(event.last_occurred_at)">{{compactTime(event.last_occurred_at)}}</time></td>
     </tr>
    </tbody>
   </table>
  </div>
 </template>
</section>
</template>
<style scoped>
.dashboard-events{display:flex;flex-direction:column;min-width:0;background:#fff;border:1px solid var(--mop-line,#e2e8f0);border-radius:8px;color:var(--mop-text,#2f4157)}
.de-head{display:flex;justify-content:space-between;align-items:center;gap:8px 12px;flex-wrap:wrap;min-height:48px;padding:10px 16px;border-bottom:1px solid var(--mop-line-soft,#edf1f6)}
.de-title{display:flex;align-items:baseline;gap:8px}.de-title span{font-size:12px;color:var(--mop-muted,#5d6e83)}
.dashboard-events h2{font-size:14px;font-weight:600;margin:0;color:var(--mop-ink,#17263a)}
.actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.actions a{display:inline-flex;align-items:center;min-height:32px;padding:0 4px;color:var(--mop-primary,#2165c5);font-size:13px;font-weight:500;text-decoration:none}
.actions a::after{content:'›';margin-left:4px;font-size:15px;line-height:1}
.actions a:hover{text-decoration:underline;text-underline-offset:3px}
.dashboard-events :focus-visible{outline:2px solid var(--mop-primary,#2165c5);outline-offset:2px}
.de-meta{margin:0;padding:8px 16px 0;font-size:12px;color:var(--mop-muted,#5d6e83);font-variant-numeric:tabular-nums}
.de-state{margin:12px 16px 16px;padding:24px 16px;border-radius:6px;background:var(--mop-sunken,#f7f9fc);color:var(--mop-text-2,#4f6177);font-size:13px;text-align:center}
.events-scroll{overflow-x:auto;margin-top:8px}
.events{min-width:520px}
.events th:first-child,.events td:first-child{width:64px;padding-left:16px}
.events td:last-child,.events th:last-child{padding-right:16px;white-space:nowrap}
.ev-problem{min-width:180px}
.event-link{color:var(--mop-ink,#17263a);font-weight:600;text-decoration:none;overflow-wrap:anywhere}
.event-link:hover{color:var(--mop-primary,#2165c5);text-decoration:underline;text-underline-offset:3px}
.ev-flags{display:inline-flex;gap:4px;margin-left:6px;vertical-align:middle}
.ev-asset{font-family:var(--mop-font-mono,Consolas,monospace);font-size:12px;color:var(--mop-primary,#2165c5);white-space:nowrap}
span.ev-asset{color:var(--mop-muted,#5d6e83)}
.events td small{display:block;margin-top:3px;font-size:12px;color:var(--mop-muted,#5d6e83)}
.events time{font-size:12px;color:var(--mop-text-2,#4f6177);white-space:nowrap;font-variant-numeric:tabular-nums}
@media(max-width:760px){.de-head{padding:10px 12px}.actions button,.actions a{min-height:44px}.de-meta{padding-inline:12px}.ev-asset{display:inline-flex;min-height:44px;align-items:center}.event-link{display:inline-block;min-height:24px}}
</style>
