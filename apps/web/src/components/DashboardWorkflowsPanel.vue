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
 <header><div><h2 id="dashboard-workflows-title">{{customer?'我的工单处理状态':'工单处理状态'}}</h2></div><div class="actions"><button ref="refreshButton" type="button" :disabled="state.busy" @click="read('refresh')">刷新工单</button><button v-if="state.busy" type="button" @click="cancel">取消读取工单</button><router-link to="/ticket-workflows">进入工单处理工作区</router-link></div></header>
 <label class="state-filter">筛选工作流状态<select :value="state.filter" :disabled="state.busy" aria-label="筛选工作流状态" @change="select"><option v-for="(label,key) in workflowLabels" :key="key" :value="key">{{label}}</option></select></label>
 <ApiErrorNotice :error="state.error" />
 <p ref="status" tabindex="-1" role="status">{{state.busy?'正在读取工单工作流…':state.cancelled?'读取已取消，未显示旧列表。':state.error?'读取失败，未显示旧列表。':state.page?`${workflowLabels[state.filter]} · 第 ${state.pageNumber} 页，本页 ${state.page.items.length} 条${state.page.has_more?'，还有更多记录':''}`:'尚未读取。'}}</p>
 <button v-if="state.canRetry" type="button" :disabled="state.busy" @click="read('retry')">重试本页工单</button>
 <template v-if="state.page">
  <div class="workflow-scroll" tabindex="0" role="region" aria-label="已激活工单工作流，可横向滚动"><table><caption>{{workflowLabels[state.filter]}}的已激活工单工作流</caption><thead><tr><th scope="col">登记号 / 标题</th><th scope="col">处理状态</th><th scope="col">优先级</th><th scope="col">登记时间</th><th scope="col">详情</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.ticket_id"><td><strong>{{row.receipt_no}}</strong><span>{{row.title}}</span></td><td>{{workflowLabels[row.state!]}}</td><td>{{row.priority}}</td><td>{{displayTime(row.registration_created_at)}}</td><td><router-link :to="{path:'/ticket-workflows',query:{ticket_id:row.ticket_id}}" :aria-label="'查看工单处理'+row.receipt_no">查看处理流程</router-link></td></tr></tbody></table></div>
  <p v-if="!state.page.items.length">当前授权范围、当前状态没有已激活的工单流程；不代表没有报障登记或其他状态工单。</p>
 </template>
 <nav class="paging" aria-label="工单工作流分页"><button type="button" :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页工单</button><button type="button" :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页工单</button></nav>
 <p class="boundary">当前展示MOP六态处理流程，未激活报障和其他生命周期不在此列表；不是全租户待办总数。此处手动读取，不会接单、派单或发送通知；SLA与外部送达状态独立核对。</p>
</section>
</template>
<style scoped>
.dashboard-workflows{min-width:0;border:1px solid #d5dfec;border-radius:8px;background:#fff;padding:16px;margin:16px 0;color:#26384f}.dashboard-workflows header,.actions,.paging{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.dashboard-workflows h2{margin:0;font-size:20px}.dashboard-workflows p{line-height:1.6;overflow-wrap:anywhere}.dashboard-workflows button,.dashboard-workflows select,.dashboard-workflows a{min-width:44px;min-height:44px;box-sizing:border-box;font:inherit}.dashboard-workflows button,.dashboard-workflows select{padding:8px 12px;border:1px solid #8495aa;border-radius:5px;background:#fff;color:#183d68}.dashboard-workflows a{display:inline-flex;align-items:center;color:#164e91;text-decoration:underline;overflow-wrap:anywhere}.dashboard-workflows button:disabled{color:#677586;background:#f1f5f9;cursor:not-allowed}.state-filter{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0}.workflow-scroll{overflow:auto;max-width:100%;border:1px solid #d5dfec}.workflow-scroll table{width:100%;min-width:680px;border-collapse:collapse}.workflow-scroll caption{text-align:left;padding:12px;font-weight:600}.workflow-scroll th,.workflow-scroll td{text-align:left;padding:12px;border-bottom:1px solid #d5dfec;vertical-align:top;overflow-wrap:anywhere}.workflow-scroll td:first-child{max-width:260px}.workflow-scroll td span{display:block}.workflow-scroll th{background:#eef3f9}.paging{justify-content:flex-end;margin:12px 0}.boundary{color:#46566c;font-size:13px}.dashboard-workflows :focus-visible{outline:2px solid #245daf;outline-offset:3px}@media(max-width:600px){.dashboard-workflows{padding:12px}.dashboard-workflows header,.actions{align-items:stretch;flex-direction:column}.state-filter{display:grid}.state-filter select{max-width:100%}}
</style>
