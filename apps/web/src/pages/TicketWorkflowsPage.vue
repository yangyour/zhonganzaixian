<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { ElTable, ElTableColumn } from 'element-plus'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import { can, contextGeneration, session } from '../stores/session'
import { displayTime } from '../utils/display'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createWorkflowWorkspace, emptyWorkflowState, readWorkflow, workflowLabels, type TicketWorkflow, type WorkflowContext } from '../utils/ticketWorkflow'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'

const route = useRoute(), router = useRouter()
const state = shallowRef(emptyWorkflowState()), list = ref<TicketWorkflow[]>([]), listError = ref<unknown>(null), listLoading = ref(false)
const workspace = createWorkflowWorkspace(api, value => { state.value = value })
const ticketId = computed(() => typeof route.query.ticket_id === 'string' ? route.query.ticket_id : '')
const customer = computed(() => ['viewer','tadmin'].includes(session.value?.user.role ?? ''))
const context = (ticket: string): WorkflowContext => ({ tenantId: session.value?.tenant.id ?? '', actorId: session.value?.user.id ?? '', permissionVersion: session.value?.permission_version ?? 0, ticketId: ticket, customer: customer.value })
const stateFilter = ref(''), priorityFilter = ref(''), nextCursor = ref<string | null>(null), listPaged = ref(false)
const priority = ref(''), tier = ref(''), note = ref(''), assignee = ref(''), reasonCode = ref(''), retain = ref(false)
const candidates = ref<{ id: string; display_name: string; role: string }[]>([]), candidateError = ref<unknown>(null)
const events = ref<{ id: string; version: number; action: string; state: string; note: string | null; created_at: string }[]>([]), eventsError = ref<unknown>(null), eventsBefore = ref<number | null>(null)
let listGeneration = 0, readGeneration = 0
const identityKey = computed(() => JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version]))
const writable = computed(() => can(customer.value ? 'tickets:report' : 'ticket_notes:write_internal'))
const locked = computed(() => state.value.busy || workspace.unresolved() || !writable.value || !state.value.current)
const responsible = computed(() => !customer.value && (session.value?.user.role === 'padmin' || state.value.current?.assignee_id === session.value?.user.id))
const currentState = computed(() => state.value.current?.state)
const actions = computed(() => {
  if (!state.value.current || !writable.value) return []
  if (customer.value) return currentState.value === 'RESOLVED' ? ['close','reopen'] : currentState.value === 'CLOSED' ? ['reopen'] : []
  if (!currentState.value) return ['activate']
  const result: string[] = currentState.value === 'OPEN' ? ['accept'] : []
  if (responsible.value && currentState.value === 'ACCEPTED') result.push('start','reassign')
  if (responsible.value && currentState.value === 'IN_PROGRESS') result.push('resolve','reassign')
  if (session.value?.user.role === 'padmin' && ['OPEN','ACCEPTED','IN_PROGRESS'].includes(currentState.value)) result.push('cancel')
  return result
})
const actionLabels: Record<string,string> = { activate:'建立处理流程', accept:'接单', start:'开始处理', reassign:'转派', resolve:'提交解决结果', close:'确认已解决', reopen:'未解决 / 重开', cancel:'撤销工单' }
async function loadList(next = false) {
  const token = ++listGeneration, before = next ? nextCursor.value : null, tenant = session.value?.tenant.id
  list.value = []; listError.value = null; listLoading.value = true; nextCursor.value = null
  try {
    const query = new URLSearchParams({ limit:'25' }); if (before) query.set('cursor',before); if (stateFilter.value) query.set('state',stateFilter.value); if (priorityFilter.value) query.set('priority',priorityFilter.value)
    const result = await api.request<{ items: unknown[]; has_more: boolean; next_cursor: string | null; source: string }>(`/ticket-workflows?${query}`)
    if (token !== listGeneration || tenant !== session.value?.tenant.id) return
    if (result.source !== 'postgresql' || !Array.isArray(result.items) || result.items.length > 25 || typeof result.has_more !== 'boolean' || (result.has_more ? typeof result.next_cursor !== 'string' || !result.next_cursor : result.next_cursor !== null)) throw new Error('工单列表未通过核对')
    const checked = result.items.map(raw => { const id = (raw as { ticket_id?: unknown })?.ticket_id; if (typeof id !== 'string') throw new Error('工单标识无效'); return readWorkflow(raw,context(id)) })
    if (new Set(checked.map(x => x.ticket_id)).size !== checked.length) throw new Error('工单列表包含重复记录')
    list.value = checked; nextCursor.value = result.next_cursor; listPaged.value = next
  } catch (error) { if (token === listGeneration) listError.value = error }
  finally { if (token === listGeneration) listLoading.value = false }
}
async function loadRelated(next = false) {
  const token = ++readGeneration, id = ticketId.value; events.value = []; eventsError.value = null
  if (!id) return
  try {
    const params = new URLSearchParams({ limit:'25' }); if (next && eventsBefore.value) params.set('before_version',String(eventsBefore.value))
    const result = await api.request<{ items: typeof events.value; has_more: boolean; next_before_version: number | null; source: string }>(`/tickets/${id}/events?${params}`)
    if (token !== readGeneration) return
    if (result.source !== 'postgresql' || !Array.isArray(result.items) || result.items.length > 25 || result.items.some(e => typeof e.id !== 'string' || typeof e.action !== 'string' || typeof e.created_at !== 'string' || e.note !== null && typeof e.note !== 'string')) throw new Error('处理历史未通过核对')
    events.value = result.items; eventsBefore.value = result.has_more ? result.next_before_version : null
  } catch (error) { if (token === readGeneration) eventsError.value = error }
}
async function loadAssignees() {
  candidates.value = []; candidateError.value = null; if (!can('ticket_notes:write_internal')) return
  const key = identityKey.value
  try { const result = await api.request<{ items: typeof candidates.value; source: string }>('/ticket-workflows/assignees'); if (key !== identityKey.value) return; if (result.source !== 'postgresql' || !Array.isArray(result.items) || result.items.some(x => typeof x.id !== 'string' || typeof x.display_name !== 'string')) throw new Error('人员列表未通过核对'); candidates.value = result.items }
  catch (error) { if (key === identityKey.value) candidateError.value = error }
}
async function perform(action: string) {
  if (locked.value) return
  const fields: Record<string,unknown> = action === 'activate' ? { priority:priority.value, service_tier:tier.value, reason:note.value }
    : action === 'reassign' ? { assignee_id:assignee.value, note:note.value, reason_code:reasonCode.value, retain_previous_as_collaborator:retain.value }
    : ['accept','start'].includes(action) ? {} : { note:note.value }
  if (await workspace.submit(action,fields)) { note.value = ''; await Promise.all([loadList(),loadRelated()]) }
}
async function retry() { if (await workspace.retry()) { note.value = ''; await Promise.all([loadList(),loadRelated()]) } }
watch(identityKey, () => { void loadList(); void loadAssignees() }, { immediate:true })
watch(() => `${identityKey.value}|${ticketId.value}`, () => { readGeneration++; events.value=[]; eventsBefore.value=null; priority.value='';tier.value='';note.value='';assignee.value='';reasonCode.value='';retain.value=false;workspace.reset(ticketId.value ? context(ticketId.value) : null); if(ticketId.value){void workspace.refresh();void loadRelated()} }, { immediate:true })
function guard() { return !workspace.unresolved() }
onBeforeRouteLeave(guard); onBeforeRouteUpdate(guard)
const unregister = contextTransitionGuards.register(guard)
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue='' } }
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(() => { unregister();workspace.dispose();listGeneration++;readGeneration++;window.removeEventListener('beforeunload',beforeUnload) })
</script>

<template>
  <section class="workflow-page">
    <div class="page-heading"><div><h2>工单处理</h2><p>接单、协同处理、客户确认与历史追溯。</p></div><el-button @click="router.push('/tickets')">查看报障台账</el-button></div>
    <section v-if="ticketId" class="panel workflow-detail" aria-label="工单处理详情">
      <div class="workflow-toolbar"><h3>{{ state.current?.title || '当前工单' }}</h3><el-button :disabled="state.busy" @click="workspace.refresh(); loadRelated()">刷新状态</el-button></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.uncertain" type="warning" :closable="false" title="提交结果尚未确认。当前操作已保留，请重试原请求；读取到其他状态不代表本次操作已确认。" />
      <el-button v-if="state.uncertain" type="warning" :disabled="state.busy" @click="retry">重试原请求</el-button>
      <p v-if="state.receiptVersion !== null" class="field-help">提交回执版本 v{{ state.receiptVersion }}，下方状态以重新读取的结果为准。</p>
      <template v-if="state.current">
        <div class="workflow-facts">
          <div><span>当前状态</span><strong>{{ state.current.state ? workflowLabels[state.current.state] : '尚未建立处理流程' }}</strong></div>
          <div><span>优先级 / 服务档位</span><strong>{{ state.current.priority || '未指定' }} / {{ ({ BASIC:'基础',STANDARD:'标准',ADVANCED:'进阶' } as Record<string,string>)[state.current.service_tier || ''] || '未指定' }}</strong></div>
          <div><span>首次响应截止</span><strong>{{ displayTime(state.current.response_due_at) }}</strong></div>
          <div><span>恢复截止</span><strong>{{ displayTime(state.current.recovery_due_at) }}</strong></div>
          <div><span>客户确认截止</span><strong>{{ displayTime(state.current.confirm_due_at) }}</strong></div>
          <div><span>重开次数 / 关单方式</span><strong>{{ state.current.reopen_count }} / {{ state.current.close_mode === 'customer_confirm' ? '客户确认' : state.current.close_mode === 'timeout_auto' ? '超时关闭' : '尚未关单' }}</strong></div>
        </div>
        <el-alert v-if="Object.keys(state.current.sla_missing).length" type="warning" :closable="false" title="部分 SLA 规则尚未完整配置，对应时限显示为未知；处理工单不会替这些规则填入默认值。" />
        <p v-if="state.current.resolution_note" class="workflow-note"><strong>解决说明：</strong>{{ state.current.resolution_note }}</p>
        <el-form v-if="actions.length" label-position="top" @submit.prevent>
          <div v-if="actions.includes('activate')" class="workflow-fields">
            <el-form-item label="优先级（明确选择）"><el-select v-model="priority" :disabled="locked" placeholder="选择优先级"><el-option v-for="p in ['P1','P2','P3']" :key="p" :label="p" :value="p" /></el-select></el-form-item>
            <el-form-item label="本次采用的服务档位"><el-select v-model="tier" :disabled="locked" placeholder="选择服务档位"><el-option label="基础" value="BASIC"/><el-option label="标准" value="STANDARD"/><el-option label="进阶" value="ADVANCED"/></el-select></el-form-item>
          </div>
          <p v-if="actions.includes('activate')" class="field-help">建立流程时冻结所选档位和当前可用 SLA 规则，计时起点仍为原报障登记时间。此选择不代表客户已批准生产规则。</p>
          <el-form-item label="操作说明（解决说明至少20字；确认、重开及撤销请说明原因）"><el-input v-model="note" type="textarea" :rows="3" :disabled="locked" maxlength="3000" aria-label="工单操作说明" /></el-form-item>
          <div v-if="actions.includes('reassign')" class="workflow-fields">
            <el-form-item label="转派给"><el-select v-model="assignee" :disabled="locked" placeholder="选择当前租户有权处理的人员"><el-option v-for="person in candidates" :key="person.id" :value="person.id" :label="person.display_name" /></el-select></el-form-item>
            <el-form-item label="转派原因代码"><el-input v-model="reasonCode" :disabled="locked" placeholder="大写字母、数字或下划线" maxlength="64" /></el-form-item>
            <el-checkbox v-model="retain" :disabled="locked">保留原负责人协办</el-checkbox>
          </div>
          <ApiErrorNotice :error="candidateError" />
          <div class="workflow-toolbar"><el-button v-for="action in actions" :key="action" :disabled="locked" :type="['cancel','reopen'].includes(action) ? 'warning' : 'primary'" @click="perform(action)">{{ actionLabels[action] }}</el-button></div>
        </el-form>
      </template>
      <h3>处理历史</h3><ApiErrorNotice :error="eventsError" />
      <ol class="workflow-history"><li v-for="event in events" :key="event.id"><strong>{{ displayTime(event.created_at) }} · {{ event.action === 'PROCESSING_UPDATED' ? '处理进展更新' : actionLabels[event.action.toLowerCase()] || (event.action === 'OPEN' ? '建立处理流程' : event.action === 'AUTO_CLOSE' ? '超时自动关闭' : event.action) }}</strong><p v-if="event.note">{{ event.note }}</p><span class="field-help">记录版本 v{{ event.version }}</span></li></ol>
      <el-button :disabled="!eventsBefore" @click="loadRelated(true)">更早记录</el-button>
    </section>
    <section class="panel">
      <el-form inline @submit.prevent="loadList()"><el-form-item label="状态"><el-select v-model="stateFilter" clearable placeholder="全部状态"><el-option v-for="(label,key) in workflowLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item><el-form-item label="优先级"><el-select v-model="priorityFilter" clearable placeholder="全部优先级"><el-option v-for="p in ['P1','P2','P3']" :key="p" :label="p" :value="p"/></el-select></el-form-item><el-button :loading="listLoading" native-type="submit">查询</el-button></el-form>
      <ApiErrorNotice :error="listError" />
      <el-table :data="list" v-loading="listLoading" empty-text="当前查询没有可显示的处理记录"><el-table-column label="工单" min-width="220"><template #default="{row}"><el-button link type="primary" :disabled="workspace.unresolved()" @click="router.replace({query:{ticket_id:row.ticket_id}})">{{ row.title }}</el-button></template></el-table-column><el-table-column label="优先级" prop="priority" width="90"/><el-table-column label="状态" width="130"><template #default="{row}">{{ workflowLabels[row.state as keyof typeof workflowLabels] }}</template></el-table-column><el-table-column label="响应截止" min-width="180"><template #default="{row}">{{ displayTime(row.response_due_at) }}</template></el-table-column><el-table-column label="恢复截止" min-width="180"><template #default="{row}">{{ displayTime(row.recovery_due_at) }}</template></el-table-column></el-table>
      <div class="workflow-toolbar"><el-button :disabled="listLoading || !listPaged" @click="loadList()">第一页</el-button><el-button :disabled="listLoading || !nextCursor" @click="loadList(true)">下一页</el-button><span class="field-help">每页最多25条；未建立处理流程的登记仍在报障台账中。</span></div>
    </section>
  </section>
</template>

<style scoped>
.workflow-detail{margin-bottom:20px}.workflow-toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:14px 0}.workflow-toolbar h3{margin-right:auto}.workflow-facts,.workflow-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.workflow-facts{margin:20px 0}.workflow-facts>div{display:flex;flex-direction:column;gap:7px}.workflow-facts span,.field-help{color:#64748b;font-size:13px}.workflow-facts strong{font-size:14px;font-weight:500;overflow-wrap:anywhere}.workflow-note,.workflow-history p{white-space:pre-wrap;overflow-wrap:anywhere}.workflow-history{padding-left:20px}.workflow-history li{margin-bottom:16px}.workflow-page :deep(.el-select){min-width:170px}.workflow-page :deep(.el-table){width:100%}@media(max-width:700px){.workflow-facts,.workflow-fields{grid-template-columns:1fr}.workflow-page :deep(.el-button){min-height:44px}.workflow-page :deep(.el-form--inline .el-form-item){display:block;margin-right:0}.workflow-page :deep(.el-select){width:100%;min-width:0}}
</style>
