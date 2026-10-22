<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { ElTable, ElTableColumn,ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api,isCancelled } from '../api/client'
import { can, contextGeneration, session,switchingTenant } from '../stores/session'
import { displayTime } from '../utils/display'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createWorkflowWorkspace, emptyWorkflowState, readWorkflow, workflowLabels, type TicketWorkflow, type WorkflowContext } from '../utils/ticketWorkflow'
import {feedback} from '../utils/feedback'
import {isUtcInstant} from '../utils/utcInstant'
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
const events = ref<{ id: string; version: number; action: string; state: string; note: string | null; created_at: string;actor_id:string|null;details:Record<string,unknown>|null;snapshot_hash:string }[]>([]), eventsError = ref<unknown>(null), eventsBefore = ref<number | null>(null)
let listGeneration=0,readGeneration=0,candidateGeneration=0,disposed=false
let listAbort:AbortController|null=null,eventsAbort:AbortController|null=null,candidatesAbort:AbortController|null=null
const appliedFilters=ref({state:'',priority:''})
let listAttempt:{cursor:string|null;filters:{state:string;priority:string};next:boolean}|null=null,eventAttempt:number|null=null
const eventsLoading=ref(false),candidateLoading=ref(false),confirming=ref(false)
const dirty=computed(()=>!!(priority.value||tier.value||note.value||assignee.value||reasonCode.value||retain.value))
const formKey=()=>JSON.stringify([priority.value,tier.value,note.value,assignee.value,reasonCode.value,retain.value])
const readable=()=>!disposed&&session.value?.mode==='remote_test'&&!switchingTenant.value&&can('tickets:read')
function clearForm(){priority.value='';tier.value='';note.value='';assignee.value='';reasonCode.value='';retain.value=false}
function clearSubmitted(action:string){if(['accept','start'].includes(action))return;note.value='';if(action==='activate'){priority.value='';tier.value=''}if(action==='reassign'){assignee.value='';reasonCode.value='';retain.value=false}}
const uuid=(v:unknown)=>typeof v==='string'&&v.length===36&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)

const identityKey = computed(() => JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.user.role,session.value?.permission_version,session.value?.account_version,session.value?.mode,can('tickets:read'),can('tickets:report'),can('ticket_notes:write_internal'),switchingTenant.value]))
const writable = computed(() => readable()&&can(customer.value ? 'tickets:report' : 'ticket_notes:write_internal'))
const locked = computed(() => confirming.value||state.value.busy || workspace.unresolved() || !writable.value || !state.value.current)
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
async function loadList(next=false,retry=false){
 if(!readable()||listLoading.value)return
 const before=retry?listAttempt?.cursor??null:next?nextCursor.value:null,filters=retry&&listAttempt?{...listAttempt.filters}:next?{...appliedFilters.value}:{state:stateFilter.value,priority:priorityFilter.value}
 if(next&&!before)return
 const paged=retry?listAttempt?.next??false:next,token=++listGeneration,owner=identityKey.value;listAbort?.abort();listAbort=new AbortController();const signal=listAbort.signal
 listAttempt={cursor:before,filters:{...filters},next:paged};appliedFilters.value=filters;list.value=[];listError.value=null;listLoading.value=true;nextCursor.value=null;listPaged.value=paged
 try{
  const query=new URLSearchParams({limit:'25'});if(before)query.set('cursor',before);if(filters.state)query.set('state',filters.state);if(filters.priority)query.set('priority',filters.priority)
  const result=await api.request<{items:unknown[];has_more:boolean;next_cursor:string|null;source:string}>('/ticket-workflows?'+query,{signal})
  if(token!==listGeneration||owner!==identityKey.value||!readable())return
  if(!result||result.source!=='postgresql'||!Array.isArray(result.items)||result.items.length>25||typeof result.has_more!=='boolean'||(result.has_more?typeof result.next_cursor!=='string'||!result.next_cursor:result.next_cursor!==null))throw new Error('工单列表未通过核对')
  const checked=result.items.map(raw=>{const id=(raw as {ticket_id?:unknown})?.ticket_id;if(!uuid(id))throw new Error('工单标识无效');const row=readWorkflow(raw,context(id as string));if(row.version<1||filters.state&&row.state!==filters.state||filters.priority&&row.priority!==filters.priority)throw new Error('工单筛选结果不一致');return row})
  if(new Set(checked.map(x=>x.ticket_id)).size!==checked.length)throw new Error('工单列表包含重复记录')
  list.value=checked;nextCursor.value=result.next_cursor
 }catch(error){if(token===listGeneration&&owner===identityKey.value&&!isCancelled(error))listError.value=error}
 finally{if(token===listGeneration){listLoading.value=false;listAbort=null}}
}
async function loadRelated(next=false,retry=false){
 if(!readable()||eventsLoading.value||!uuid(ticketId.value))return
 const before=retry?eventAttempt:next?eventsBefore.value:null
 if(next&&!before)return
 const token=++readGeneration,id=ticketId.value,owner=identityKey.value;eventsAbort?.abort();eventsAbort=new AbortController();const signal=eventsAbort.signal
 eventAttempt=before;events.value=[];eventsError.value=null;eventsBefore.value=null;eventsLoading.value=true
 try{
  const params=new URLSearchParams({limit:'25'});if(before)params.set('before_version',String(before))
  const result=await api.request<{items:typeof events.value;has_more:boolean;next_before_version:number|null;source:string}>(`/tickets/${id}/events?${params}`,{signal})
  if(token!==readGeneration||owner!==identityKey.value||id!==ticketId.value||!readable())return
  if(!result||result.source!=='postgresql'||!Array.isArray(result.items)||result.items.length>25||typeof result.has_more!=='boolean'||(result.has_more?!Number.isSafeInteger(result.next_before_version)||Number(result.next_before_version)<1:result.next_before_version!==null)||result.items.some((e,i)=>!e||typeof e!=='object'||!uuid(e.id)||!Number.isSafeInteger(e.version)||e.version<1||before!==null&&e.version>=before||i>0&&e.version>=result.items[i-1]!.version||!['OPEN','ACCEPT','START','REASSIGN','RESOLVE','CLOSE','REOPEN','CANCEL','AUTO_CLOSE','PROCESSING_UPDATED'].includes(e.action)||!Object.hasOwn(workflowLabels,e.state)||!isUtcInstant(e.created_at)||e.note!==null&&typeof e.note!=='string'||typeof e.snapshot_hash!=='string'||!/^[0-9a-f]{64}$/.test(e.snapshot_hash)||e.actor_id!==null&&!uuid(e.actor_id)||customer.value&&(e.actor_id!==null||e.details!==null)||!customer.value&&(!e.details||typeof e.details!=='object'||Array.isArray(e.details)))||result.has_more&&(result.items.length!==25||result.next_before_version!==result.items.at(-1)?.version)||new Set(result.items.map(e=>e.id)).size!==result.items.length)throw new Error('处理历史未通过核对')
  events.value=result.items;eventsBefore.value=result.next_before_version
 }catch(error){if(token===readGeneration&&owner===identityKey.value&&!isCancelled(error))eventsError.value=error}
 finally{if(token===readGeneration){eventsLoading.value=false;eventsAbort=null}}
}
async function loadAssignees(){
 candidates.value=[];candidateError.value=null;candidateLoading.value=false;candidatesAbort?.abort();const token=++candidateGeneration
 if(!readable()||customer.value||!can('ticket_notes:write_internal'))return
 const owner=identityKey.value;candidateLoading.value=true;candidatesAbort=new AbortController();const signal=candidatesAbort.signal
 try{const result=await api.request<{items:typeof candidates.value;source:string}>('/ticket-workflows/assignees',{signal});if(token!==candidateGeneration||owner!==identityKey.value||!readable())return;if(!result||result.source!=='postgresql'||!Array.isArray(result.items)||result.items.length>100||result.items.some(x=>!x||typeof x!=='object'||!uuid(x.id)||typeof x.display_name!=='string'||!['msp','padmin'].includes(x.role))||new Set(result.items.map(x=>x.id)).size!==result.items.length)throw new Error('人员列表未通过核对');candidates.value=result.items}
 catch(error){if(token===candidateGeneration&&owner===identityKey.value&&!isCancelled(error))candidateError.value=error}
 finally{if(token===candidateGeneration){candidateLoading.value=false;candidatesAbort=null}}
}
async function refreshDetail(){if(!readable()||state.value.busy||workspace.unresolved())return;await Promise.all([workspace.refresh(),loadRelated(),loadAssignees()])}
async function firstList(){if(listLoading.value)return;listAttempt={cursor:null,filters:{...appliedFilters.value},next:false};await loadList(false,true)}
async function reloadAfterWrite(){listGeneration++;readGeneration++;listAbort?.abort();eventsAbort?.abort();listLoading.value=false;eventsLoading.value=false;listAttempt={cursor:null,filters:{...appliedFilters.value},next:false};await Promise.all([loadList(false,true),loadRelated()])}
async function perform(action: string) {
  if (locked.value||!actions.value.includes(action)||action==='reassign'&&(candidateLoading.value||!candidates.value.some(person=>person.id===assignee.value)||assignee.value===state.value.current?.assignee_id)) return
  const owner=identityKey.value,id=ticketId.value
  const fields: Record<string,unknown> = action === 'activate' ? { priority:priority.value, service_tier:tier.value, reason:note.value }
    : action === 'reassign' ? { assignee_id:assignee.value, note:note.value, reason_code:reasonCode.value, retain_previous_as_collaborator:retain.value }
    : ['accept','start'].includes(action) ? {} : { note:note.value }
  if(await workspace.submit(action,fields)&&owner===identityKey.value&&id===ticketId.value){clearSubmitted(action);await reloadAfterWrite()}
}
async function retry(){const owner=identityKey.value,id=ticketId.value;if(await workspace.retry()&&owner===identityKey.value&&id===ticketId.value){if(state.value.receiptAction)clearSubmitted(state.value.receiptAction);await reloadAfterWrite()}}
watch(identityKey,()=>{
 listGeneration++;candidateGeneration++;listAbort?.abort();candidatesAbort?.abort();listLoading.value=false;list.value=[];listError.value=null;nextCursor.value=null;listPaged.value=false;candidates.value=[];candidateError.value=null;candidateLoading.value=false;stateFilter.value='';priorityFilter.value='';appliedFilters.value={state:'',priority:''};listAttempt=null;confirming.value=false
 if(readable()){void loadList();void loadAssignees()}
},{immediate:true,flush:'sync'})
watch(()=>`${identityKey.value}|${ticketId.value}`,()=>{
 readGeneration++;eventsAbort?.abort();eventsLoading.value=false;events.value=[];eventsError.value=null;eventsBefore.value=null;eventAttempt=null;clearForm();workspace.reset(readable()&&ticketId.value?context(ticketId.value):null)
 if(readable()&&ticketId.value){void workspace.refresh();void loadRelated()}
},{immediate:true,flush:'sync'})
async function guard(message='离开会丢弃尚未提交的工单输入，是否继续？',title='离开工单编辑'){
 if(workspace.unresolved()){feedback.warning('原工单操作结果尚未确认，请先原请求重试或核对，避免重复提交。');return false}
 if(!dirty.value)return true
 if(confirming.value)return false
 const owner=identityKey.value,id=ticketId.value,input=formKey();confirming.value=true
 try{await ElMessageBox.confirm(message,title,{confirmButtonText:'确认放弃',cancelButtonText:'继续编辑',type:'warning'});return !disposed&&owner===identityKey.value&&id===ticketId.value&&input===formKey()&&!workspace.unresolved()}
 catch{return false}finally{if(owner===identityKey.value)confirming.value=false}
}
async function discardInputs(){const owner=identityKey.value,id=ticketId.value,input=formKey();if(state.value.busy||workspace.unresolved())return;if(await guard('只清空尚未提交的输入，不影响已确认的工单操作，是否继续？','清空未提交输入')&&owner===identityKey.value&&id===ticketId.value&&input===formKey())clearForm()}
onBeforeRouteLeave(()=>guard());onBeforeRouteUpdate(()=>guard())
const unregister=contextTransitionGuards.register(()=>guard())
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||workspace.unresolved()){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;unregister();workspace.dispose();listGeneration++;readGeneration++;candidateGeneration++;listAbort?.abort();eventsAbort?.abort();candidatesAbort?.abort();window.removeEventListener('beforeunload',beforeUnload)})
</script>

<template>
  <section class="workflow-page">
    <p v-if="!readable()" role="status">当前没有工单读取权限，或正在切换租户；原范围数据已清除。</p>
    <p v-if="dirty" role="status">尚有未提交输入。刷新状态会保留输入；接单和开始处理不会提交说明，请核对当前状态后继续操作。</p><el-button v-if="dirty" :disabled="state.busy||workspace.unresolved()||confirming" @click="discardInputs">清空未提交输入</el-button>
    <h1 class="page-title-sr">工单处理 </h1>
    <section v-if="ticketId&&readable()" class="panel workflow-detail" aria-label="工单处理详情">
      <div class="workflow-toolbar"><h2>{{ state.current?.title || '当前工单' }}</h2><el-button :disabled="state.busy||workspace.unresolved()" @click="refreshDetail">刷新状态</el-button></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.uncertain" type="warning" :closable="false" title="提交结果尚未确认，当前填写内容已保留，请点击下方按钮重试提交。" />
      <el-button v-if="state.uncertain" type="warning" :disabled="state.busy" @click="retry">重试提交</el-button>
      <el-alert v-if="state.readbackFailed" type="warning" :closable="false" title="提交已确认，但最新状态回读失败。请刷新状态，不要重复提交。" />
      <p v-if="state.receiptVersion !== null" class="field-help">提交回执版本 v{{ state.receiptVersion }}，下方状态以重新读取的结果为准。</p>
      <template v-if="state.current">
        <div class="workflow-facts">
          <div><span>当前状态</span><strong>{{ state.current.state ? workflowLabels[state.current.state] : '尚未建立处理流程' }}</strong></div>
          <div><span>优先级 / 服务档位</span><strong>{{ state.current.priority || '未指定' }} / {{ ({ BASIC:'基础',STANDARD:'标准',ADVANCED:'高级' } as Record<string,string>)[state.current.service_tier || ''] || '未指定' }}</strong></div>
          <div><span>首次响应截止</span><strong>{{ displayTime(state.current.response_due_at) }}</strong></div>
          <div><span>恢复截止</span><strong>{{ displayTime(state.current.recovery_due_at) }}</strong></div>
          <div><span>客户确认截止</span><strong>{{ displayTime(state.current.confirm_due_at) }}</strong></div>
          <div><span>重开次数 / 关单方式</span><strong>{{ state.current.reopen_count }} / {{ state.current.close_mode === 'customer_confirm' ? '客户确认' : state.current.close_mode === 'timeout_auto' ? '超时关闭' : '尚未关单' }}</strong></div>
        </div>
        <el-alert v-if="Object.keys(state.current.sla_missing).length" type="warning" :closable="false" title="部分 SLA 规则尚未完整配置，对应时限显示为未知；处理工单不会替这些规则填入默认值。" />
        <p v-if="state.current.resolution_note" class="workflow-note"><strong>解决说明：</strong>{{ state.current.resolution_note }}</p>
        <el-form v-if="actions.length" label-position="top" @submit.prevent>
          <div v-if="actions.includes('activate')" class="workflow-fields">
            <el-form-item label="优先级"><el-select v-model="priority" :disabled="locked" placeholder="选择优先级"><el-option v-for="p in ['P1','P2','P3']" :key="p" :label="p" :value="p" /></el-select></el-form-item>
            <el-form-item label="本次采用的服务档位"><el-select v-model="tier" :disabled="locked" placeholder="选择服务档位"><el-option label="基础" value="BASIC"/><el-option label="标准" value="STANDARD"/><el-option label="高级" value="ADVANCED"/></el-select></el-form-item>
          </div>
          <p v-if="actions.includes('activate')" class="field-help">建立流程时锁定所选服务档位与当前 SLA 规则，首次响应与恢复时限按工单报障登记时间起算。</p>
          <el-form-item label="操作说明（解决说明至少20字；确认、重开及撤销请说明原因）"><el-input v-model="note" type="textarea" :rows="3" :disabled="locked" maxlength="3000" aria-label="工单操作说明" /></el-form-item>
          <div v-if="actions.includes('reassign')" class="workflow-fields">
            <el-form-item label="转派给"><el-select v-model="assignee" filterable :disabled="locked||candidateLoading" placeholder="选择当前租户有权处理的人员"><el-option v-for="person in candidates" :key="person.id" :value="person.id" :label="person.display_name" /></el-select></el-form-item>
            <el-form-item label="转派原因代码"><el-input v-model="reasonCode" :disabled="locked" placeholder="大写字母、数字或下划线" maxlength="64" /></el-form-item>
            <el-checkbox v-model="retain" :disabled="locked">保留原负责人协办</el-checkbox>
          </div>
          <ApiErrorNotice :error="candidateError" :retrying="candidateLoading" @retry="loadAssignees" /><p v-if="candidateLoading" role="status">正在读取可转派人员…</p>
          <div class="workflow-toolbar"><el-button v-for="action in actions" :key="action" :disabled="locked||action==='reassign'&&(candidateLoading||!candidates.some(person=>person.id===assignee)||assignee===state.current?.assignee_id)" :type="['cancel','reopen'].includes(action) ? 'warning' : 'primary'" @click="perform(action)">{{ actionLabels[action] }}</el-button></div>
        </el-form>
      </template>
      <h3>处理历史</h3><ApiErrorNotice :error="eventsError" @retry="loadRelated(false,true)" />
      <p v-if="eventsLoading" role="status">正在读取处理历史…</p><p v-else-if="!eventsError&&!events.length">当前页没有处理历史。</p>
      <ol class="workflow-history"><li v-for="event in events" :key="event.id"><strong>{{ displayTime(event.created_at) }} · {{ event.action === 'PROCESSING_UPDATED' ? '处理进展更新' : actionLabels[event.action.toLowerCase()] || (event.action === 'OPEN' ? '建立处理流程' : event.action === 'AUTO_CLOSE' ? '超时自动关闭' : event.action) }}</strong><p v-if="event.note">{{ event.note }}</p><span class="field-help">记录版本 v{{ event.version }}</span></li></ol>
      <el-button :disabled="eventsLoading||!eventsBefore" @click="loadRelated(true)">更早记录</el-button>
    </section>
    <section v-if="readable()" class="panel" aria-label="已激活工单列表"><h2>已激活工单列表</h2>
      <el-form inline @submit.prevent="loadList()"><el-form-item label="状态"><el-select v-model="stateFilter" clearable placeholder="全部状态"><el-option v-for="(label,key) in workflowLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item><el-form-item label="优先级"><el-select v-model="priorityFilter" clearable placeholder="全部优先级"><el-option v-for="p in ['P1','P2','P3']" :key="p" :label="p" :value="p"/></el-select></el-form-item><el-button :loading="listLoading" native-type="submit">查询</el-button></el-form>
      <p v-if="stateFilter!==appliedFilters.state||priorityFilter!==appliedFilters.priority" class="field-help">筛选修改尚未应用；下一页仍沿用已应用的筛选，请点击查询后再核对。</p>
      <ApiErrorNotice :error="listError" @retry="loadList(false,true)" /><p v-if="listLoading" role="status">正在读取工单列表…</p>
      <el-table v-if="!listLoading&&!listError" :data="list" v-loading="listLoading" empty-text="当前查询没有可显示的处理记录"><el-table-column label="工单" min-width="220"><template #default="{row}"><el-button link type="primary" :disabled="workspace.unresolved()" @click="router.replace({query:{ticket_id:row.ticket_id}})">{{ row.title }}</el-button></template></el-table-column><el-table-column label="优先级" prop="priority" width="90"/><el-table-column label="状态" width="130"><template #default="{row}">{{ workflowLabels[row.state as keyof typeof workflowLabels] }}</template></el-table-column><el-table-column label="响应截止" min-width="180"><template #default="{row}">{{ displayTime(row.response_due_at) }}</template></el-table-column><el-table-column label="恢复截止" min-width="180"><template #default="{row}">{{ displayTime(row.recovery_due_at) }}</template></el-table-column></el-table>
      <div class="workflow-toolbar content-toolbar"><el-button :disabled="listLoading || !listPaged" @click="firstList">第一页</el-button><el-button :disabled="listLoading || !nextCursor" @click="loadList(true)">下一页</el-button><span class="field-help">每页 25 条</span><div class="content-actions"><el-button @click="router.push('/tickets')">查看报障台账</el-button></div></div>
    </section>
  </section>
</template>

<style scoped>
.workflow-page>.panel{padding:16px 20px}.workflow-page>.panel>h2{font-size:15px;font-weight:600;margin:0 0 12px}.workflow-detail{margin-bottom:20px}.workflow-toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:14px 0}.workflow-toolbar h2{margin-right:auto;min-width:0;max-width:100%;overflow-wrap:anywhere}.workflow-facts,.workflow-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.workflow-fields{grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.workflow-facts{margin:16px 0}.workflow-facts>div{display:flex;flex-direction:column;gap:6px;padding:12px 14px;background:#f8fbff;border:1px solid #e6edf5;border-radius:6px}.workflow-facts span,.field-help{color:#64748b;font-size:12px}.workflow-facts strong{font-size:14px;font-weight:600;color:#1e293b;overflow-wrap:anywhere}.workflow-note,.workflow-history p{white-space:pre-wrap;overflow-wrap:anywhere}.workflow-history{padding-left:20px}.workflow-history li{margin-bottom:16px}.workflow-page :deep(.el-select){min-width:170px}.workflow-page :deep(.el-table){width:100%}@media(max-width:900px){.workflow-facts{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:700px){.workflow-facts,.workflow-fields{grid-template-columns:1fr}.workflow-page :deep(.el-button){min-height:44px}.workflow-page :deep(.el-form--inline .el-form-item){display:block;margin-right:0}.workflow-page :deep(.el-select){width:100%;min-width:0}}
.workflow-page :deep(.el-button),.workflow-page :deep(.el-input__wrapper),.workflow-page :deep(.el-select__wrapper){min-height:44px}</style>
