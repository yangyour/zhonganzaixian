<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import type { RegisteredTicket } from '../api/types'
import { api, ApiError } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createTicketLifecycleWorkspace, emptyLifecycleState, lifecycleActions, lifecycleLabels, lifecycleActionLabels, type LifecycleAction, type LifecycleContext } from '../utils/ticketLifecycleWorkspace'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import {ticketAuthorityKey,type TicketProcessSummary} from '../utils/ticketProcessSummary'
import type {TicketSlaProjection} from '../utils/ticketSlaReader'
import { displayTime } from '../utils/display'
import { feedback } from '../utils/feedback'
import ApiErrorNotice from './ApiErrorNotice.vue'
import TicketSlaPanel from './TicketSlaPanel.vue'
const slaPanel=ref<InstanceType<typeof TicketSlaPanel>|null>(null)
const props = defineProps<{ ticket: RegisteredTicket; refreshKey: number }>()
const emit=defineEmits<{summary:[value:TicketProcessSummary]}>()
const root=ref<HTMLElement|null>(null),clock=shallowRef<TicketSlaProjection|null>(null)
const openingPriority = ref(''), openingAcknowledged = ref(false)
const state = shallowRef(emptyLifecycleState()), selected = ref<LifecycleAction | ''>(''), reason = ref(''), assignee = ref(''), keep = ref(false), acknowledged = ref(false), validation = ref('')
const workspace = createTicketLifecycleWorkspace(api, value => { state.value = value })
const scope = computed<LifecycleContext | null>(() => {
  const s = session.value, customer = ['viewer','tadmin'].includes(s?.user.role || '')
  if (!s || switchingTenant.value || props.ticket.tenant_id !== s.tenant.id || !can('tickets:read') || customer && props.ticket.reporter_id !== s.user.id) return null
  return { tenantId: s.tenant.id, ticketId: props.ticket.id, actorId: s.user.id, permissionVersion: s.permission_version, customer, canWrite: can(customer ? 'tickets:customer_decide' : 'tickets:lifecycle_write') }
})
const actions = computed(() => lifecycleActions(state.value.snapshot, scope.value))
const missingConfiguration = computed(() => state.value.error instanceof ApiError && state.value.error.code === 'CONFIG_PENDING'
  ? state.value.error.details.flatMap(item => typeof item.configuration_key === 'string' && /^[a-z][a-z0-9_.]{0,99}$/.test(item.configuration_key) ? [item.configuration_key] : []) : [])
const locked = computed(() => Boolean(state.value.busy) || state.value.attempt?.state === 'uncertain' || slaPanel.value?.hasUnresolved() === true)
const needsReason = computed(() => selected.value && !['accept','resume','confirm'].includes(selected.value))
const contextKey = computed(() => ticketAuthorityKey(session.value,contextGeneration.value,props.ticket,switchingTenant.value))
function clear() { clock.value=null;openingPriority.value = ''; openingAcknowledged.value = false; selected.value = ''; reason.value = ''; assignee.value = ''; keep.value = false; acknowledged.value = false; validation.value = '' }
watch(contextKey, () => { clear(); workspace.reset(scope.value); if (scope.value) void workspace.refresh() }, { immediate: true, flush: 'sync' })
watch(() => props.refreshKey, () => { if (!workspace.unresolved() && !slaPanel.value?.hasUnresolved()) void workspace.refresh() })
watch(openingPriority, () => { openingAcknowledged.value = false })
watch(selected, () => { acknowledged.value = false; validation.value = '' })
watch(() => JSON.stringify([state.value.snapshot?.version, state.value.snapshot?.solution_snapshot_hash]), () => { acknowledged.value = false; selected.value = '' })
async function start() { if (!locked.value && openingAcknowledged.value && await workspace.start(openingPriority.value)) clear() }
async function submit() {
  if (!selected.value || locked.value || !acknowledged.value) return
  try { validation.value = ''; if (await workspace.submit(selected.value, reason.value, assignee.value, keep.value)) clear() }
  catch (error) { validation.value = error instanceof Error ? error.message : '请检查动作输入' }
}
function canLeave() { if (slaPanel.value?.canLeave() === false) return false; if (!workspace.unresolved()) return true; feedback.warning('工单动作结果尚未确认，请先使用原请求重试'); return false }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
const unregister = contextTransitionGuards.register(canLeave)
onBeforeRouteLeave(canLeave)
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
function updateClock(value:TicketSlaProjection|null){clock.value=value&&value.tenant_id===props.ticket.tenant_id&&value.ticket_id===props.ticket.id&&value.lifecycle_version===state.value.snapshot?.version?value:null}
watch([state,clock,locked,contextKey],()=>{
 const snapshot=scope.value&&state.value.loaded&&!state.value.busy&&!state.value.error?state.value.snapshot:null
 const currentClock=snapshot&&clock.value?.lifecycle_version===snapshot.version?clock.value:null
 emit('summary',{authorization_key:contextKey.value,loaded:!!scope.value&&state.value.loaded,loading:!!state.value.busy,failed:!!state.value.error,locked:locked.value,snapshot:snapshot?structuredClone(snapshot):null,clock:currentClock?structuredClone(currentClock):null,actions:snapshot&&!locked.value?[...actions.value]:[]})
},{immediate:true,flush:'sync'})
async function prepareAction(action:LifecycleAction){
 if(!scope.value||locked.value||!actions.value.includes(action)||!state.value.loaded||state.value.error)return false
 if(selected.value&&selected.value!==action&&(reason.value.trim()||assignee.value.trim())){validation.value='请先核对或清空当前动作输入，再切换快捷操作';return false}
 selected.value=action;acknowledged.value=false;await nextTick();root.value?.scrollIntoView?.({block:'center'});root.value?.querySelector<HTMLElement>('textarea,input,select')?.focus();return true
}
defineExpose({ canLeave, prepareAction })
</script>
<template>
  <section v-if="scope" ref="root" class="lifecycle-panel" aria-label="工单生命周期">
    <header><h3>工单流转</h3><el-button :disabled="locked" @click="workspace.refresh()">刷新状态</el-button></header>
    
    <ApiErrorNotice v-if="state.error" :error="state.error"><p v-if="missingConfiguration.length">需补全或重新发布的配置：{{ missingConfiguration.join('、') }}。请由有权限的管理员核对配置内容及生效状态。</p></ApiErrorNotice>
    <p v-if="state.busy === 'read'" role="status">正在读取当前状态与解决方案…</p>
    <p v-else-if="state.loaded && !state.snapshot" role="status">此登记尚未创建生命周期。需有服务端生效配置及开单权限；本页不会推断档位、优先级或截止时间。</p>
    <div v-if="state.loaded && !state.snapshot && !scope.customer && scope.canWrite" class="action-form">
      <label>确认优先级<select v-model="openingPriority" :disabled="locked"><option value="">请选择，不默认映射报障紧急程度</option><option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option></select></label>
      <el-checkbox v-model="openingAcknowledged" :disabled="locked || !openingPriority">已核对优先级；使用当前已生效的租户配置创建生命周期快照</el-checkbox>
      <el-button type="primary" :disabled="locked || !openingPriority || !openingAcknowledged" @click="start">创建生命周期</el-button>
    </div>
    <template v-if="state.snapshot">
      <p><strong>{{ lifecycleLabels[state.snapshot.state] }}</strong> · 版本 {{ state.snapshot.version }} · 重开 {{ state.snapshot.reopen_count }} 次</p>
      <p>响应截止：{{ displayTime(state.snapshot.response_due_at) }}；恢复截止：{{ displayTime(state.snapshot.recovery_due_at) }}。此处为开单快照，并非暂停调整后的实时考核结果。</p>
      <article v-if="state.solution" aria-label="本轮解决方案"><h4>解决方案</h4><p>{{ state.solution.ticket_summary }}</p><p class="solution">{{ state.solution.solution }}</p><small>方案版本 {{ state.solution.lifecycle_version }} · {{ displayTime(state.solution.resolved_at) }}</small></article>
      <div v-if="actions.length" class="action-form">
        <label>选择动作 <select v-model="selected" :disabled="locked"><option value="">请选择</option><option v-for="action in actions" :key="action" :value="action">{{ lifecycleActionLabels[action] }}</option></select></label>
        <label v-if="needsReason">{{ selected === 'submit-resolution' ? '解决方案（至少20个字符）' : '说明 / 原因' }}<textarea v-model="reason" :disabled="locked" rows="4" maxlength="32000" /></label>
        <template v-if="selected === 'transfer'"><label>目标工程师 ID<input v-model="assignee" :disabled="locked" autocomplete="off" placeholder="当前租户的有效工程师 UUID" /></label><el-checkbox v-model="keep" :disabled="locked">将原工程师保留为协作人</el-checkbox></template>
        <el-checkbox v-if="selected" v-model="acknowledged" :disabled="locked">{{ selected === 'confirm' ? '我已阅读本轮方案，确认问题已解决并关闭工单' : '我已核对当前版本及动作内容' }}</el-checkbox>
        <p v-if="validation" role="alert">{{ validation }}</p>
        <el-button v-if="selected" type="primary" :disabled="locked || !acknowledged" @click="submit">{{ lifecycleActionLabels[selected] }}</el-button>
      </div>
    </template>
    <TicketSlaPanel ref="slaPanel" v-if="state.snapshot" :ticket="ticket" :expected-version="state.snapshot.version" @changed="updateClock" />
    <div v-if="state.attempt?.state === 'uncertain'" role="alert"><p>请求结果尚不确定。请勿另建请求，使用相同请求号重试；刷新状态不等于确认原请求结果。</p><el-button :disabled="Boolean(state.busy)" @click="workspace.retry()">原请求重试</el-button></div>
    <p v-if="state.attempt?.state === 'accepted'" role="status">动作已受理；以刷新后的持久状态为准。</p>
  </section>
</template>
<style scoped>
.lifecycle-panel{border:1px solid #dce5f1;background:#fff;border-radius:10px;padding:18px 20px;margin:16px 0;overflow-wrap:anywhere}.lifecycle-panel header{display:flex;align-items:center;justify-content:space-between;gap:12px}.lifecycle-panel h3{margin:0}.notice,small{color:#64748b}.action-form{display:grid;gap:12px;margin-top:16px}.action-form label{display:grid;gap:6px}.action-form select,.action-form input,.action-form textarea{font:inherit;box-sizing:border-box;max-width:100%;padding:8px;border:1px solid #b6c7df;border-radius:6px;background:white}.solution{white-space:pre-wrap}.lifecycle-panel article{background:#f4f7fc;padding:12px;border-radius:6px}.action-form :deep(.el-checkbox){white-space:normal;height:auto}.action-form :deep(.el-checkbox__label){white-space:normal}
</style>
