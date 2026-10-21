<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { session, switchingTenant, can, contextGeneration } from '../stores/session'
import type { RegisteredTicket } from '../api/types'
import { readTicketSla, type TicketSlaProjection } from '../utils/ticketSlaReader'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
import TicketSlaPolicyPanel from './TicketSlaPolicyPanel.vue'
import TicketSlaReminderPanel from './TicketSlaReminderPanel.vue'
const policyPanel=ref<InstanceType<typeof TicketSlaPolicyPanel>|null>(null)
defineExpose({canLeave:()=>policyPanel.value?.canLeave()!==false,hasUnresolved:()=>policyPanel.value?.hasUnresolved()===true})
const props=defineProps<{ ticket: RegisteredTicket; expectedVersion: number }>()
const value=shallowRef<TicketSlaProjection | null>(null), error=shallowRef<unknown>(null), busy=ref(false)
const permitted=computed(()=>Boolean(session.value && !switchingTenant.value && can('tickets:read') && session.value.tenant.id===props.ticket.tenant_id && (!['viewer','tadmin'].includes(session.value.user.role) || props.ticket.reporter_id===session.value.user.id)))
const key=computed(()=>JSON.stringify([contextGeneration.value,session.value?.user.id,session.value?.tenant.id,session.value?.permission_version,permitted.value,props.ticket.id,props.expectedVersion]))
const labels={RUNNING:'计时中',PAUSED:'待客户确认，已暂停',COMPLETED:'已停止',CONFIG_PENDING:'待配置'}
let controller: AbortController | undefined, epoch=0
async function refresh(){
  controller?.abort();const abort=new AbortController();controller=abort;const turn=++epoch;value.value=null;error.value=null;busy.value=false
  if(!permitted.value||!session.value)return
  const scope={tenantId:session.value.tenant.id,ticketId:props.ticket.id,expectedVersion:props.expectedVersion};busy.value=true
  try{const raw=await api.request<unknown>(`/tickets/${scope.ticketId}/sla`,{signal:abort.signal});if(turn!==epoch||abort.signal.aborted)return;value.value=readTicketSla(raw,scope)}
  catch(cause){if(turn===epoch&&!isCancelled(cause))error.value=cause}
  finally{if(turn===epoch)busy.value=false}
}
watch(key,()=>{void refresh()},{immediate:true,flush:'sync'})
onUnmounted(()=>{++epoch;controller?.abort()})
const duration=(ms:number|null)=>ms===null?'待配置':`${(ms/60000).toFixed(2)} 分钟`
</script>
<template>
  <section v-if="permitted" class="ticket-sla" aria-label="服务端SLA时钟">
    <header><h4>服务端 SLA 时钟</h4><el-button size="small" :disabled="busy" @click="refresh">刷新时钟</el-button></header>
    <ApiErrorNotice v-if="error" :error="error" />
    <p v-if="busy" role="status">正在读取服务端判定…</p>
    <template v-if="value">
      <p>截至 {{ displayTime(value.observed_at) }} · 生命周期版本 {{ value.lifecycle_version }}。前端不自行判定超时，刷新后更新。</p>
      <div class="clocks"><article v-for="kind in (['response','recovery'] as const)" :key="kind"><strong>{{ kind==='response'?'响应':'恢复' }}：{{ labels[value[kind].status] }}</strong><p v-if="value[kind].missing">待补配置 / 数据：{{ value[kind].missing }}</p><template v-else><p>有效已用 {{ duration(value[kind].elapsed_ms) }} / 预算 {{ duration(value[kind].budget_ms) }}</p><p>截止：{{ displayTime(value[kind].deadline) }}</p><p v-if="value[kind].stopped_at">停止于 {{ displayTime(value[kind].stopped_at) }}</p><p :class="{breached:value[kind].breached}" role="status">{{ value[kind].breached?'服务端判定：已超时':'服务端判定：尚未超时' }}</p></template></article></div>
      <p v-if="value.paused_at">本轮暂停于 {{ displayTime(value.paused_at) }}</p><p>已结束暂停区间 {{ value.completed_pauses.length }} 段；口径引用 {{ value.configuration_reference }}</p><p v-if="value.calculation_policy_reference">重开口径引用：{{ value.calculation_policy_reference }}</p><small>此结果不代表提醒送达、月度结算或生产批准。</small>
    </template>
    <TicketSlaReminderPanel :ticket="ticket" :expected-version="expectedVersion" />
    <TicketSlaPolicyPanel ref="policyPanel" :ticket="ticket" :expected-version="expectedVersion" @bound="refresh" />
  </section>
</template>
<style scoped>
.ticket-sla{margin-top:18px;border-top:1px solid #dce5f1;padding-top:12px;overflow-wrap:anywhere}.ticket-sla header{display:flex;align-items:center;justify-content:space-between;gap:12px}.clocks{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}.clocks article{background:#f5f8fd;border-radius:8px;padding:12px}.breached{color:#b42318;font-weight:600}.ticket-sla small{color:#64748b}
</style>
