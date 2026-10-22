<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {createDashboardWorkflows,emptyDashboardWorkflows} from '../utils/dashboardWorkflows'
import {workflowLabels,type WorkflowState} from '../utils/ticketWorkflow'
import {displayTime} from '../utils/display'
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
</script>
<template>
<section v-if="permitted" class="dashboard-workflows" aria-labelledby="dashboard-workflows-title" :aria-busy="state.busy">
 <header><h2 id="dashboard-workflows-title">{{customer?'我的工单处理状态':'工单处理状态'}}</h2><div class="actions"><label class="state-filter"><span class="sr-label">筛选工作流状态</span><select :value="state.filter" :disabled="state.busy" aria-label="筛选工作流状态" @change="select"><option v-for="(label,key) in workflowLabels" :key="key" :value="key">{{label}}</option></select></label><button ref="refreshButton" type="button" :disabled="state.busy" @click="read('refresh')">刷新工单</button><button v-if="state.busy" type="button" @click="cancel">取消读取工单</button><router-link to="/ticket-workflows">工单处理</router-link></div></header>
 <ApiErrorNotice :error="state.error" />
 <p ref="status" tabindex="-1" role="status" class="wf-status">{{state.busy?'正在读取工单工作流…':state.cancelled?'读取已取消，未显示旧列表。':state.error?'读取失败，未显示旧列表。':state.page?`${workflowLabels[state.filter]} · 第 ${state.pageNumber} 页，本页 ${state.page.items.length} 条${state.page.has_more?'，还有更多记录':''}`:'尚未读取。'}}</p>
 <button v-if="state.canRetry" type="button" class="wf-retry" :disabled="state.busy" @click="read('retry')">重试本页工单</button>
 <template v-if="state.page">
  <p v-if="!state.page.items.length" class="wf-empty">当前状态没有已激活的工单流程（不代表没有报障登记或其他状态工单）。</p>
  <div v-else class="workflow-scroll" tabindex="0" role="region" aria-label="已激活工单工作流，可横向滚动"><table><caption>{{workflowLabels[state.filter]}}的已激活工单工作流</caption><thead><tr><th scope="col">登记号 / 标题</th><th scope="col">处理状态</th><th scope="col">优先级</th><th scope="col">登记时间</th><th scope="col">详情</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.ticket_id"><td><strong>{{row.title}}</strong><span>{{row.receipt_no}}</span></td><td><span class="wf-state">{{workflowLabels[row.state!]}}</span></td><td>{{row.priority}}</td><td class="wf-time">{{displayTime(row.registration_created_at)}}</td><td><router-link :to="{path:'/ticket-workflows',query:{ticket_id:row.ticket_id}}" :aria-label="'查看工单处理'+row.receipt_no">查看</router-link></td></tr></tbody></table></div>
 </template>
 <nav class="paging" aria-label="工单工作流分页"><button type="button" :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页工单</button><button type="button" :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页工单</button></nav>
</section>
</template>
<style scoped>
.dashboard-workflows{min-width:0;border:1px solid #e3e9f2;border-radius:10px;background:#fff;padding:16px 18px;color:#26384f}
.dashboard-workflows header,.actions,.paging{display:flex;gap:8px;align-items:center;justify-content:space-between;flex-wrap:wrap}
.dashboard-workflows h2{margin:0;font-size:15px;font-weight:600;color:#0f172a}
.dashboard-workflows button,.dashboard-workflows select,.actions a{min-height:32px;box-sizing:border-box;font:inherit;font-size:13px}
.dashboard-workflows button,.dashboard-workflows select{padding:0 12px;border:1px solid #d5deea;border-radius:6px;background:#fff;color:#1d4ed8;cursor:pointer}
.dashboard-workflows select{color:#0f172a}
.actions a{display:inline-flex;align-items:center;padding:0 12px;border:1px solid #d5deea;border-radius:6px;color:#1d4ed8;text-decoration:none}
.dashboard-workflows button:hover:not(:disabled),.actions a:hover{background:#eff6ff;border-color:#93c5fd}
.dashboard-workflows button:disabled{color:#94a3b8;background:#f8fafc;cursor:not-allowed}
.state-filter{display:inline-flex}
.sr-label,.workflow-scroll caption{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0}
.wf-status{margin:8px 0;font-size:12px;color:#64748b;outline:none}
.wf-retry{margin-bottom:8px}
.wf-empty{margin:0;padding:20px;border-radius:8px;background:#f8fafc;color:#475569;font-size:13px;text-align:center}
.workflow-scroll{overflow:auto;max-width:100%;border:1px solid #eef2f7;border-radius:8px}
.workflow-scroll table{width:100%;min-width:600px;border-collapse:collapse;font-size:13px}
.workflow-scroll th,.workflow-scroll td{text-align:left;padding:10px 12px;border-bottom:1px solid #eef2f7;vertical-align:middle;overflow-wrap:anywhere}
.workflow-scroll tbody tr:last-child td{border-bottom:0}
.workflow-scroll tbody tr:hover{background:#f8fbff}
.workflow-scroll td:first-child{max-width:280px}
.workflow-scroll td strong{display:block;font-weight:600;color:#0f172a}
.workflow-scroll td span:not(.wf-state){display:block;margin-top:2px;font-size:12px;color:#64748b;font-variant-numeric:tabular-nums}
.workflow-scroll th{background:#f6f8fb;color:#475569;font-weight:600;font-size:12px}
.workflow-scroll td a{color:#1d4ed8;text-decoration:none}.workflow-scroll td a:hover{text-decoration:underline}
.wf-state{display:inline-flex;height:22px;align-items:center;padding:0 8px;border-radius:999px;background:#eff6ff;color:#1d4ed8;font-size:12px;white-space:nowrap}
.wf-time{white-space:nowrap;font-variant-numeric:tabular-nums;color:#475569}
.paging{justify-content:flex-end;margin-top:10px}
.dashboard-workflows :focus-visible{outline:2px solid #2563eb;outline-offset:2px}
@media(max-width:760px){.dashboard-workflows{padding:14px}.dashboard-workflows button,.dashboard-workflows select,.actions a{min-height:44px}.dashboard-workflows header{align-items:stretch}.actions{justify-content:flex-start}.workflow-scroll td a{display:inline-flex;min-height:44px;align-items:center}}
</style>
