<script setup lang="ts">
import { computed,onUnmounted,ref,shallowRef,watch } from 'vue'
import { api,isCancelled } from '../api/client'
import { session,switchingTenant,can,contextGeneration } from '../stores/session'
import type { RegisteredTicket } from '../api/types'
import { readSlaReminders,type SlaReminderPage } from '../utils/ticketSlaReminderReader'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{ticket:RegisteredTicket;expectedVersion:number}>()
const value=shallowRef<SlaReminderPage|null>(null),error=shallowRef<unknown>(null),busy=ref(false)
const permitted=computed(()=>Boolean(session.value&&!switchingTenant.value&&can('tickets:read')&&props.ticket.tenant_id===session.value.tenant.id&&(!['viewer','tadmin'].includes(session.value.user.role)||props.ticket.reporter_id===session.value.user.id)))
const key=computed(()=>JSON.stringify([contextGeneration.value,session.value?.user.id,session.value?.tenant.id,session.value?.permission_version,permitted.value,props.ticket.id,props.expectedVersion]))
const states={PENDING:'待扫描',LEASED:'已领取租约',IDLE:'当前版本暂停扫描',BLOCKED_ATTEMPTS:'扫描失败待处理'}
let epoch=0,controller:AbortController|undefined
async function refresh(){
  controller?.abort();const abort=new AbortController();controller=abort;const turn=++epoch;value.value=null;error.value=null;busy.value=false
  if(!permitted.value||!session.value)return
  const scope={tenantId:session.value.tenant.id,ticketId:props.ticket.id,expectedVersion:props.expectedVersion};busy.value=true
  try{const raw=await api.request<unknown>(`/tickets/${scope.ticketId}/sla/reminders`,{signal:abort.signal});if(turn!==epoch||abort.signal.aborted)return;value.value=readSlaReminders(raw,scope)}
  catch(cause){if(turn===epoch&&!isCancelled(cause))error.value=cause}
  finally{if(turn===epoch)busy.value=false}
}
watch(key,()=>{void refresh()},{immediate:true,flush:'sync'})
onUnmounted(()=>{++epoch;controller?.abort()})
</script>
<template>
  <section v-if="permitted" class="sla-reminders" aria-label="SLA提醒记录">
    <header><h4>SLA 提醒记录</h4><el-button size="small" :disabled="busy" @click="refresh">刷新记录</el-button></header>
    <p>这里只展示持久记录与通知意图。当前尚未路由到接收人或供应商，不代表已发送或送达。</p>
    <ApiErrorNotice v-if="error" :error="error"/><p v-if="busy" role="status">正在读取提醒记录…</p>
    <template v-if="value">
      <p>队列：{{ states[value.scan_state] }}。此状态不证明 Worker 当前在线。</p>
      <p v-if="value.last_scan_at">最近扫描记录：{{ displayTime(value.last_scan_at) }} · {{ value.last_outcome }}</p><p v-else>当前生命周期版本尚无扫描结果。</p>
      <p v-if="!value.items.length">尚无已记录节点，不等于时钟未过阈值。</p>
      <ul v-else><li v-for="item in value.items" :key="item.intent_id"><strong>{{ item.clock==='RESPONSE'?'响应':'恢复' }} {{ item.progress*100 }}%</strong> · {{ displayTime(item.observed_at) }} · 版本 {{ item.lifecycle_version }}<span>通知意图已记录，未路由<span v-if="item.escalation_required">；需要升级处理（尚未执行）</span></span></li></ul>
    </template>
  </section>
</template>
<style scoped>
.sla-reminders{margin-top:16px;border-top:1px solid #dce5f1;padding-top:12px;overflow-wrap:anywhere}.sla-reminders header{display:flex;align-items:center;justify-content:space-between;gap:12px}.sla-reminders p{color:#64748b}.sla-reminders ul{padding-left:20px}.sla-reminders li{margin:10px 0;line-height:1.6}.sla-reminders li>span{display:block}
</style>
