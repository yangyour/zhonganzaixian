<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { session, can, contextGeneration, switchingTenant } from '../stores/session'
import { createTicketSlaPolicyWorkspace, emptyPolicyState, type PolicyScope } from '../utils/ticketSlaPolicyWorkspace'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import {ticketAuthorityKey} from '../utils/ticketProcessSummary'
import { feedback } from '../utils/feedback'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{ticket:RegisteredTicket;expectedVersion:number}>(),emit=defineEmits<{bound:[]}>()
const state=shallowRef(emptyPolicyState()),acknowledged=ref(false)
const workspace=createTicketSlaPolicyWorkspace(api,v=>{state.value=v})
const scope=computed<PolicyScope|null>(()=>{const s=session.value;if(!s||switchingTenant.value||s.tenant.id!==props.ticket.tenant_id||!can('tickets:read')||['viewer','tadmin'].includes(s.user.role)&&props.ticket.reporter_id!==s.user.id)return null;return{tenantId:s.tenant.id,ticketId:props.ticket.id,actorId:s.user.id,permissionVersion:s.permission_version,expectedVersion:props.expectedVersion,canWrite:['msp','padmin'].includes(s.user.role)&&can('tickets:lifecycle_write')}})
const key=computed(()=>JSON.stringify([ticketAuthorityKey(session.value,contextGeneration.value,props.ticket,switchingTenant.value),scope.value])),locked=computed(()=>Boolean(state.value.busy)||state.value.attempt?.state==='uncertain')
watch(()=>JSON.stringify(state.value.view),()=>{acknowledged.value=false})
watch(key,()=>{acknowledged.value=false;workspace.reset(scope.value);if(scope.value)void workspace.refresh()},{immediate:true,flush:'sync'})
async function bind(){if(!acknowledged.value||locked.value)return;if(await workspace.bind()){acknowledged.value=false;emit('bound')}}
async function retry(){if(await workspace.retry()){acknowledged.value=false;emit('bound')}}
function canLeave(){if(!workspace.unresolved())return true;feedback.warning('SLA口径绑定结果尚未确认，请先用原请求重试');return false}
function beforeUnload(event:BeforeUnloadEvent){if(workspace.unresolved()){event.preventDefault();event.returnValue=''}}
const unregister=contextTransitionGuards.register(canLeave);onBeforeRouteLeave(canLeave);window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({canLeave,hasUnresolved:workspace.unresolved})
const policyLabel=(value:string|null)=>value==='EXCLUDE_CONFIRMED_PAUSES'?'排除已确认暂停区间':value==='INCLUDE_ALL_ELAPSED_TIME'?'计入全部经过时间':'待配置'
</script>
<template>
  <section v-if="scope" class="sla-policy" aria-label="重开计时口径">
    <header><strong>重开计时口径</strong><el-button size="small" :disabled="locked" @click="workspace.refresh()">读取口径</el-button></header>
    <ApiErrorNotice v-if="state.error" :error="state.error" />
    <p v-if="state.busy==='read'" role="status">正在读取配置和绑定记录…</p>
    <template v-if="state.view">
      <p v-if="state.view.status==='CONFIG_PENDING'">待补依赖：{{ state.view.missing }}</p>
      <p v-else>{{ policyLabel(state.view.clock_policy) }} · {{ state.view.status==='OPENING_FROZEN'?'已随开单冻结':state.view.status==='BOUND'?'已独立绑定，不可替换':'已发布，尚未绑定此工单' }}</p>
      <template v-if="state.view.status==='AVAILABLE'&&scope.canWrite"><p>发布版本 {{ state.view.release_version }} · {{ state.view.release_id }}</p><el-checkbox v-model="acknowledged" :disabled="locked">我已核对本工单及发布版本，确认固定使用此计时口径</el-checkbox><el-button type="primary" size="small" :disabled="locked||!acknowledged" @click="bind">绑定已发布口径</el-button></template>
    </template>
    <div v-if="state.attempt?.state==='uncertain'" role="alert"><p>结果尚不确定，不要新建请求。请使用相同请求号重试。</p><el-button :disabled="Boolean(state.busy)" @click="retry">原请求重试</el-button></div>
    <p v-if="state.attempt?.state==='accepted'" role="status">绑定已保存，SLA状态将重新读取。</p>
  </section>
</template>
<style scoped>
.sla-policy{margin-top:14px;padding:12px;border:1px solid #dce5f1;border-radius:8px;overflow-wrap:anywhere}.sla-policy header{display:flex;justify-content:space-between;gap:12px;align-items:center}.sla-policy :deep(.el-checkbox){white-space:normal;height:auto;margin:8px 0}.sla-policy :deep(.el-checkbox__label){white-space:normal}
</style>
