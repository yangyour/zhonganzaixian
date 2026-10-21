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
</script>
<template>
<section v-if="permitted" class="dashboard-events" aria-labelledby="dashboard-events-title" :aria-busy="busy">
 <header><div><h2 id="dashboard-events-title">近期监控事件</h2><p>当前租户 · 按最近发生时间倒序，最多 7 条；包含恢复和关闭事件，不限最近 24 小时。</p></div><div class="actions"><button :disabled="busy" @click="load">{{busy?'读取中…':'刷新近期事件'}}</button><router-link to="/monitoring-events">进入事件中心</router-link></div></header>
 <ApiErrorNotice :error="error" :retrying="busy" @retry="load" />
 <p v-if="!events" role="status">{{busy?'正在读取近期事件…':error?'事件读取失败，未沿用旧列表。':'尚未读取事件。'}}</p>
 <template v-else><p role="status">已读取 {{events.length}} 条近期记录 · 数据库观察时间：{{displayTime(observed)}}。此处手动刷新，不代表实时推送。</p><p v-if="!events.length">当前查询没有事件记录，不代表全量设备健康或采集完整。</p>
 <ol v-else class="events"><li v-for="event in events" :key="event.id"><h3><router-link :to="{path:'/monitoring-events',query:{event_id:event.id}}">{{event.rule_name}} · 查看事件详情</router-link></h3><dl><div><dt>严重程度</dt><dd>{{severity[event.severity]}}</dd></div><div><dt>监控状态</dt><dd>{{event.monitor_state==='PROBLEM'?'异常':'已恢复'}}</dd></div><div><dt>人工处理</dt><dd>{{handling[event.handling_state]}}</dd></div><div><dt>通知抑制</dt><dd>{{suppression[event.suppression_state]}}</dd></div><div><dt>合并出现次数</dt><dd>{{event.occurrence_count}}</dd></div><div><dt>最近发生</dt><dd>{{displayTime(event.last_occurred_at)}}</dd></div></dl><p class="id">资产 ID：{{event.asset_id}}</p><p>{{event.notification_empty_state_label}}。派发资格不代表已发送或送达。</p></li></ol></template>
 <p>列表数量不是当前问题总数；监控恢复不等于人工结单。SLA、全租户告警趋势和正式运营指标独立验收。</p>
</section>
</template>
<style scoped>
.dashboard-events{min-width:0;padding:20px;border:1px solid #cbd5e1;background:#fff;border-radius:8px;margin:16px 0;color:#24374a}.dashboard-events header,.actions{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.dashboard-events h2{font-size:18px;margin:0}.dashboard-events h3{font-size:16px}.dashboard-events p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.dashboard-events button,.dashboard-events a{display:inline-flex;align-items:center;min-height:44px;box-sizing:border-box;padding:8px 12px;color:#185695;background:white;border:1px solid #8293a6;border-radius:4px;font:inherit;text-decoration:underline;overflow-wrap:anywhere}.dashboard-events button{cursor:pointer;text-decoration:none}.dashboard-events button:disabled{opacity:.6;cursor:default}.dashboard-events :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.events{padding-left:24px}.events li{padding:12px 0;border-top:1px solid #cbd5e1}.events dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.events dt{font-size:12px;font-weight:bold}.events dd{margin:4px 0;overflow-wrap:anywhere}.id{font-family:monospace}@media(max-width:760px){.dashboard-events{padding:14px}.events dl{grid-template-columns:1fr}.events{padding-left:18px}}
</style>
