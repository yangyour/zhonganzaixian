<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {createDashboardWorkflows,emptyDashboardWorkflows} from '../utils/dashboardWorkflows'
import {workflowLabels,type WorkflowState} from '../utils/ticketWorkflow'
import {compactTime,displayTime} from '../utils/display'
import type {TicketWorkflow} from '../utils/ticketWorkflow'
import ApiErrorNotice from './ApiErrorNotice.vue'
const state=shallowRef(emptyDashboardWorkflows()),status=ref<HTMLElement|null>(null),refreshButton=ref<HTMLButtonElement|null>(null)
const workspace=createDashboardWorkflows(api,value=>state.value=value)
let disposed=false
const customer=computed(()=>['viewer','tadmin'].includes(session.value?.user.role??''))
const permitted=computed(()=>session.value?.mode==='remote_test'&&can('dashboard:read')&&can('tickets:read')&&!switchingTenant.value)
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.user.role,session.value?.permission_version,session.value?.account_version,session.value?.mode,permitted.value]))
watch(scope,()=>{const s=session.value;workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,role:s.user.role,permissionVersion:s.permission_version}:null);if(permitted.value)void workspace.refresh()},{immediate:true,flush:'sync'})
async function read(action:'refresh'|'next'|'previous'|'retry',filter?:WorkflowState){if(!permitted.value||state.value.busy||disposed)return;const owner=scope.value;await(filter?workspace.select(filter):workspace[action]());await nextTick();if(!disposed&&owner===scope.value)status.value?.focus()}
function select(event:Event){void read('refresh',(event.target as HTMLSelectElement).value as WorkflowState)}
async function cancel(){workspace.cancel();await nextTick();if(!disposed&&permitted.value)refreshButton.value?.focus()}
onUnmounted(()=>{disposed=true;workspace.dispose()})
defineExpose({ refresh: () => workspace.refresh() })
// M1-6 SLA column: the deadline the server issued for the clock that applies now; overdue is decided server-side.
function slaDeadline(row:TicketWorkflow){
 if(row.state==='OPEN')return {label:'响应截止',at:row.response_due_at}
 if(row.state==='ACCEPTED'||row.state==='IN_PROGRESS')return {label:'恢复截止',at:row.recovery_due_at}
 if(row.state==='RESOLVED')return {label:'确认截止',at:row.confirm_due_at}
 return {label:'',at:null}
}
const priorityTone:Record<string,string>={P1:'danger',P2:'warn',P3:'info'}
</script>
<template>
<section v-if="permitted" class="dashboard-workflows" aria-labelledby="dashboard-workflows-title" :aria-busy="state.busy">
 <header><div class="wf-title"><h2 id="dashboard-workflows-title">{{customer?'我的工单处理状态':'待处理工单'}}</h2><span>每页 5 条</span></div><div class="actions"><label class="state-filter"><span class="sr-label">筛选工作流状态</span><select :value="state.filter" :disabled="state.busy" aria-label="筛选工作流状态" @change="select"><option v-for="(label,key) in workflowLabels" :key="key" :value="key">{{label}}</option></select></label><button ref="refreshButton" type="button" :disabled="state.busy" @click="read('refresh')">刷新工单</button><button v-if="state.busy" type="button" @click="cancel">取消读取工单</button><router-link to="/ticket-workflows">工单处理</router-link></div></header>
 <ApiErrorNotice :error="state.error" />
 <p ref="status" tabindex="-1" role="status" class="wf-status">{{state.busy?'正在读取工单工作流…':state.cancelled?'读取已取消，未显示旧列表。':state.error?'读取失败，未显示旧列表。':state.page?`${workflowLabels[state.filter]} · 第 ${state.pageNumber} 页，本页 ${state.page.items.length} 条${state.page.has_more?'，还有更多记录':''}`:'尚未读取。'}}</p>
 <button v-if="state.canRetry" type="button" class="wf-retry" :disabled="state.busy" @click="read('retry')">重试本页工单</button>
 <template v-if="state.page">
  <p v-if="!state.page.items.length" class="wf-empty">当前状态没有已激活的工单流程（不代表没有报障登记或其他状态工单）。</p>
  <div v-else class="workflow-scroll" tabindex="0" role="region" aria-label="已激活工单工作流，可横向滚动"><table class="mop-table"><caption>{{workflowLabels[state.filter]}}的已激活工单工作流</caption><thead><tr><th scope="col">工单号 / 标题</th><th scope="col">状态</th><th scope="col">优先级</th><th scope="col">SLA</th><th scope="col">详情</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.ticket_id"><td><strong>{{row.title}}</strong><span>{{row.receipt_no}}</span></td><td><span class="wf-state mop-badge info">{{workflowLabels[row.state!]}}</span></td><td><span v-if="row.priority" class="mop-badge plain" :class="priorityTone[row.priority]">{{row.priority}}</span><span v-else>—</span></td><td class="wf-time"><template v-if="slaDeadline(row).at"><span class="wf-due">{{slaDeadline(row).label}}</span><time :datetime="slaDeadline(row).at!" :title="displayTime(slaDeadline(row).at)">{{compactTime(slaDeadline(row).at)}}</time></template><span v-else>—</span></td><td><router-link :to="{path:'/ticket-workflows',query:{ticket_id:row.ticket_id}}" :aria-label="'查看工单处理'+row.receipt_no">查看</router-link></td></tr></tbody></table></div>
 </template>
 <nav class="paging" aria-label="工单工作流分页"><button type="button" :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页工单</button><button type="button" :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页工单</button></nav>
</section>
</template>
<style scoped>
.dashboard-workflows{display:flex;flex-direction:column;min-width:0;border:1px solid var(--mop-line,#e2e8f0);border-radius:8px;background:#fff;color:var(--mop-text,#2f4157)}
.dashboard-workflows header{display:flex;gap:8px 12px;align-items:center;justify-content:space-between;flex-wrap:wrap;min-height:48px;padding:10px 16px;border-bottom:1px solid var(--mop-line-soft,#edf1f6)}
.wf-title{display:flex;align-items:baseline;gap:8px}.wf-title span{font-size:12px;color:var(--mop-muted,#5d6e83)}
.actions,.paging{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.dashboard-workflows h2{margin:0;font-size:14px;font-weight:600;color:var(--mop-ink,#17263a)}
.dashboard-workflows select{min-height:32px;padding:0 8px;font-size:13px}
.actions a{display:inline-flex;align-items:center;min-height:32px;padding:0 4px;color:var(--mop-primary,#2165c5);font-size:13px;font-weight:500;text-decoration:none}
.actions a::after{content:'›';margin-left:4px;font-size:15px;line-height:1}
.actions a:hover{text-decoration:underline;text-underline-offset:3px}
.state-filter{display:inline-flex}
.sr-label,.workflow-scroll caption{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0}
.wf-status{margin:0;padding:8px 16px 0;font-size:12px;color:var(--mop-muted,#5d6e83);outline:none;font-variant-numeric:tabular-nums}
.wf-retry{margin:8px 16px 0;align-self:flex-start}
.wf-empty{margin:12px 16px;padding:24px 16px;border-radius:6px;background:var(--mop-sunken,#f7f9fc);color:var(--mop-text-2,#4f6177);font-size:13px;text-align:center}
.workflow-scroll{overflow:auto;max-width:100%;margin-top:8px}
.workflow-scroll table{min-width:520px}
.workflow-scroll th:first-child,.workflow-scroll td:first-child{padding-left:16px}
.workflow-scroll th:last-child,.workflow-scroll td:last-child{padding-right:16px}
.workflow-scroll td:first-child{max-width:280px;overflow-wrap:anywhere}
.workflow-scroll td strong{display:block;font-weight:600;color:var(--mop-ink,#17263a)}
.workflow-scroll td:first-child span{display:block;margin-top:2px;font-size:12px;color:var(--mop-muted,#5d6e83);font-variant-numeric:tabular-nums}
.workflow-scroll td a{color:var(--mop-primary,#2165c5);text-decoration:none;font-weight:500}.workflow-scroll td a:hover{text-decoration:underline}
.wf-time{white-space:nowrap;font-variant-numeric:tabular-nums;color:var(--mop-text-2,#4f6177)}
.wf-due{display:block;font-size:11px;color:var(--mop-muted,#5d6e83)}
.paging{justify-content:flex-end;margin-top:auto;padding:10px 16px;border-top:1px solid var(--mop-line-soft,#edf1f6)}
.dashboard-workflows :focus-visible{outline:2px solid var(--mop-primary,#2165c5);outline-offset:2px}
@media(max-width:760px){.dashboard-workflows header{padding:10px 12px;align-items:stretch}.dashboard-workflows button,.dashboard-workflows select,.actions a{min-height:44px}.actions{justify-content:flex-start}.workflow-scroll td a{display:inline-flex;min-height:44px;align-items:center}.paging{padding:10px 12px}}
</style>
