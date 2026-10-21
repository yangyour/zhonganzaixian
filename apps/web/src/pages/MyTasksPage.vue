<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {createDashboardWorkflows,emptyDashboardWorkflows,ownWorkflowLabels} from '../utils/dashboardWorkflows'
import {workflowLabels,type WorkflowState} from '../utils/ticketWorkflow'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state=shallowRef({...emptyDashboardWorkflows(),filter:'ACCEPTED' as WorkflowState}),status=ref<HTMLElement|null>(null),refreshButton=ref<HTMLButtonElement|null>(null)
const workspace=createDashboardWorkflows(api,value=>state.value=value,'own-assigned')
let disposed=false
const permitted=computed(()=>session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('tickets:read')&&can('ticket_notes:write_internal')&&!switchingTenant.value)
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.user.role,session.value?.permission_version,session.value?.account_version,session.value?.mode,permitted.value]))
watch(scope,()=>{const s=session.value;workspace.reset(s&&permitted.value?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canReadAssigned:true}:null);if(permitted.value)void workspace.refresh()},{immediate:true,flush:'sync'})
async function read(action:'refresh'|'next'|'previous'|'retry',filter?:WorkflowState){if(disposed||!permitted.value||state.value.busy)return;const owner=scope.value;await(filter?workspace.select(filter):workspace[action]());await nextTick();if(!disposed&&owner===scope.value)status.value?.focus()}
function select(event:Event){void read('refresh',(event.target as HTMLSelectElement).value as WorkflowState)}
async function cancel(){const owner=scope.value;workspace.cancel();await nextTick();if(!disposed&&permitted.value&&owner===scope.value)refreshButton.value?.focus()}
onUnmounted(()=>{disposed=true;workspace.dispose()})
</script>
<template>
<section class="my-tasks-page" :aria-busy="state.busy">
 <header><div><h1>我的待办</h1><p>当前先提供“我负责的工单”入口，与运营总览分开。</p></div><router-link to="/dashboard" v-if="can('dashboard:read')">查看运营总览</router-link></header>
 <p v-if="!permitted" role="status">当前身份缺少内部工单读取或负责人筛选权限；未读取任务数据。</p>
 <template v-else>
  <section class="scope-note" aria-label="我的待办当前覆盖范围"><h2>我负责的工单</h2><p>仅当前租户中明确分派给当前账号的六态工作流。审批任务、未分派报障、协办关系和七态生命周期尚未合并，不显示虚假的“全部待办”总数。</p><p>“等待客户确认”是跟进状态，不表示你可代客户确认；操作权限由工单详情重新核验。</p></section>
  <div class="actions"><label for="own-workflow-state">我的工单状态<select id="own-workflow-state" :value="state.filter" :disabled="state.busy" @change="select"><option v-for="(label,key) in ownWorkflowLabels" :key="key" :value="key">{{label}}</option></select></label><button ref="refreshButton" type="button" :disabled="state.busy" @click="read('refresh')">刷新我的工单</button><button v-if="state.busy" type="button" @click="cancel">取消读取我的工单</button></div>
  <ApiErrorNotice :error="state.error" />
  <p ref="status" tabindex="-1" role="status">{{state.busy?'正在读取分派给当前账号的工单…':state.cancelled?'读取已取消，旧列表已清除。':state.error?'读取失败，未显示旧列表，可重试原页。':state.page?`${workflowLabels[state.filter]} · 第 ${state.pageNumber} 页，本页 ${state.page.items.length} 条${state.page.has_more?'，还有后续页':''}`:'尚无读取结果。'}}</p>
  <button v-if="state.canRetry" type="button" :disabled="state.busy" @click="read('retry')">重试原页我的工单</button>
  <template v-if="state.page">
   <div class="task-scroll" tabindex="0" role="region" aria-label="我负责的工单列表，可横向滚动"><table><caption>当前账号负责 · {{workflowLabels[state.filter]}} · 本页记录</caption><thead><tr><th scope="col">登记号与标题</th><th scope="col">状态 / 优先级</th><th scope="col">登记时间</th><th scope="col">服务端观察时间</th><th scope="col">办理入口</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.ticket_id"><td><strong>{{row.receipt_no}}</strong><span>{{row.title}}</span></td><td>{{workflowLabels[row.state!]}} / {{row.priority}}</td><td>{{displayTime(row.registration_created_at)}}</td><td>{{displayTime(row.observed_at)}}</td><td><router-link :to="{path:'/ticket-workflows',query:{ticket_id:row.ticket_id}}" :aria-label="'查看我的工单'+row.receipt_no">查看与办理</router-link></td></tr></tbody></table></div>
   <p v-if="!state.page.items.length">本状态下没有明确分派给当前账号的工单；不代表其他状态或审批任务全部处理完毕。</p>
  </template>
  <nav class="paging" aria-label="我的工单分页"><button type="button" :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页我的工单</button><button type="button" :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页我的工单</button></nav>
  <p class="boundary">按登记时间倒序，每页5条；分页是每次请求的当前快照，不保证跨页期间没有变化。只读入口不会接单、派单、确认关闭或发送通知，不把工单处置状态当成监控恢复状态。</p>
 </template>
</section>
</template>
<style scoped>
.my-tasks-page{min-width:0;color:#26384f}.my-tasks-page header,.actions,.paging{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.my-tasks-page h1{margin:0}.my-tasks-page h2{font-size:19px;margin:0 0 8px}.my-tasks-page p{line-height:1.7;overflow-wrap:anywhere}.scope-note{padding:16px;border:1px solid #8495aa;background:#f5f8fc;margin:16px 0;border-radius:6px}.actions{justify-content:flex-start;margin:16px 0}.actions label{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.my-tasks-page button,.my-tasks-page select,.my-tasks-page a{min-height:44px;min-width:44px;box-sizing:border-box;font:inherit}.my-tasks-page button,.my-tasks-page select{padding:8px 12px;border:1px solid #8495aa;background:#fff;color:#183d68;border-radius:5px}.my-tasks-page button:disabled{color:#526176;background:#f1f5f9;cursor:not-allowed}.my-tasks-page a{display:inline-flex;align-items:center;color:#164e91;text-decoration:underline;overflow-wrap:anywhere}.task-scroll{overflow:auto;max-width:100%;border:1px solid #8495aa;background:#fff}.task-scroll table{border-collapse:collapse;width:100%;min-width:720px}.task-scroll th,.task-scroll td{padding:12px;text-align:left;border-bottom:1px solid #d5dfec;vertical-align:top;overflow-wrap:anywhere}.task-scroll th{background:#eef3f9}.task-scroll caption{text-align:left;padding:12px;font-weight:600}.task-scroll td:first-child{max-width:280px}.task-scroll td span{display:block}.paging{justify-content:flex-end;margin:12px 0}.boundary{font-size:13px;color:#46566c}.my-tasks-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}@media(max-width:600px){.my-tasks-page header,.actions{align-items:stretch;flex-direction:column}.actions label{display:grid}.actions select{max-width:100%}.scope-note{padding:12px}}
</style>
