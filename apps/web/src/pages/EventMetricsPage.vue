<script setup lang="ts">
import {computed,onUnmounted,shallowRef,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {api} from '../api/client'
import {session,can,contextGeneration,switchingTenant} from '../stores/session'
import {createEventMetricReader,emptyEventMetrics,eventMetricWindows,elapsedMetricLabel,type EventMetricWindow,type EventMetricView} from '../utils/eventOperationalMetrics'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const route=useRoute(),router=useRouter(),state=shallowRef(emptyEventMetrics()),reader=createEventMetricReader(api,v=>state.value=v)
const permitted=computed(()=>session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('dashboard:read')&&can('tickets:read')&&!switchingTenant.value)
const window=computed<EventMetricWindow>(()=>eventMetricWindows.includes(route.query.window as EventMetricWindow)?route.query.window as EventMetricWindow:'HOURS_24')
const view=computed<EventMetricView>(()=>route.query.view==='CURRENT_PROBLEMS'?'CURRENT_PROBLEMS':'RECENT_PROBLEMS')
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value]))
function refresh(next=false){if(permitted.value)void reader.load(window.value,view.value,next?state.value.current?.next??null:null)}
function select(field:string,value:string){void router.replace({path:'/event-metrics',query:{...route.query,[field]:value}})}
watch([scope,window,view],()=>{const s=session.value;reader.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,canRead:true}:null);refresh()},{immediate:true,flush:'sync'})
onUnmounted(reader.dispose)
const statusText=computed(()=>({CALCULATED:'已按记录时间计算',NO_ACKNOWLEDGED_SAMPLES:'没有已确认样本，时长未知',INVALID_SOURCE_TIMESTAMPS:'存在缺失或异常确认时间，均值不计算',SAMPLE_BUDGET_EXCEEDED:'超过完整核算预算，未返回部分均值'})[state.value.current?.mtta.status??'NO_ACKNOWLEDGED_SAMPLES'])
</script>
<template>
 <section class="proto-panel" aria-label="事件指标明细" :aria-busy="state.busy"><div class="actions"><h1>事件指标明细</h1><router-link to="/dashboard">运营首页</router-link><router-link to="/monitoring-events">事件中心</router-link><button :disabled="state.busy||!permitted" @click="refresh()">刷新</button><button v-if="state.busy" @click="reader.cancel">取消读取</button></div>
 <p v-if="!permitted">当前账号无内部事件指标读取权限。</p><template v-else><div class="actions"><label>范围<select :value="window" :disabled="state.busy" @change="select('window',($event.target as HTMLSelectElement).value)"><option value="HOURS_24">近24小时</option><option value="DAYS_7">近7天</option><option value="DAYS_30">近30天</option></select></label><label>明细<select :value="view" :disabled="state.busy" @change="select('view',($event.target as HTMLSelectElement).value)"><option value="CURRENT_PROBLEMS">当前未抑制问题</option><option value="RECENT_PROBLEMS">范围内发生的事件</option></select></label></div>
 <ApiErrorNotice :error="state.error"/><p v-if="state.cancelled" role="status">已取消读取，未沿用旧值。</p><p v-if="state.busy" role="status">正在读取同源计数和明细…</p>
 <template v-if="state.current"><p>当前未抑制问题 {{state.current.current_problems.total}}；MTTA均值 {{elapsedMetricLabel(state.current.mtta.mean_millis)}} · 中位数 {{elapsedMetricLabel(state.current.mtta.median_millis)}} · P95 {{elapsedMetricLabel(state.current.mtta.p95_millis)}}。</p><p role="status">{{statusText}}；确认样本 {{state.current.mtta.sample_count??'未知'}}，未确认 {{state.current.mtta.unacknowledged_count??'未知'}}，异常 {{state.current.mtta.invalid_count??'未知'}}。</p><p>MTTA选取窗口内首次发生的事件，含各抑制状态；当前问题单独排除抑制。数据库读取 {{displayTime(state.current.observed_at)}}，不证明采集时效或时钟准确性，也不是冻结月报。</p>
 <div class="table-scroll" tabindex="0" role="region" aria-label="同源事件明细，可横向滚动"><table><thead><tr><th>事件</th><th>级别/抑制</th><th>问题发生</th><th>首次确认</th><th>时长/状态</th></tr></thead><tbody><tr v-for="row in state.current.items" :key="row.event_id"><td><router-link :to="{path:'/monitoring-events',query:{event_id:row.event_id}}">{{row.rule_name}}</router-link><small>v{{row.version}}</small></td><td>{{row.severity}} / {{row.suppression_state}}</td><td>{{displayTime(row.problem_at)}}</td><td>{{row.first_ack_at?displayTime(row.first_ack_at):'未记录'}}</td><td>{{elapsedMetricLabel(row.elapsed_millis)}}<small>{{row.acknowledgement_state}}</small></td></tr></tbody></table></div><p v-if="!state.current.items.length">当前页没有符合该视图的事件记录。</p><div class="actions"><button :disabled="state.busy" @click="refresh()">第一页</button><button v-if="state.current.next" :disabled="state.busy" @click="refresh(true)">下一页</button></div></template></template>
 </section>
</template>
<style scoped>.table-scroll{overflow:auto}table{width:100%;min-width:720px;text-align:left}th,td{padding:8px;vertical-align:top}small{display:block}p{overflow-wrap:anywhere}</style>
