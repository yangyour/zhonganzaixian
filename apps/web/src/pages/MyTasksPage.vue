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
 <h1 class="page-title-sr">我的待办</h1>
 <p v-if="!permitted" role="status">当前身份缺少内部工单读取或负责人筛选权限；未读取任务数据。</p>
 <template v-else>
  <div class="task-toolbar content-toolbar"><label for="own-workflow-state">我的工单状态<select id="own-workflow-state" :value="state.filter" :disabled="state.busy" @change="select"><option v-for="(label,key) in ownWorkflowLabels" :key="key" :value="key">{{label}}</option></select></label><button ref="refreshButton" type="button" :disabled="state.busy" @click="read('refresh')">刷新我的工单</button><button v-if="state.busy" type="button" @click="cancel">取消读取我的工单</button><p ref="status" tabindex="-1" role="status" class="task-status">{{state.busy?'正在读取分派给当前账号的工单…':state.cancelled?'读取已取消，旧列表已清除。':state.error?'读取失败，未显示旧列表，可重试原页。':state.page?`${workflowLabels[state.filter]} · 第 ${state.pageNumber} 页，本页 ${state.page.items.length} 条${state.page.has_more?'，还有后续页':''}`:'尚无读取结果。'}}</p><div class="content-actions"><router-link to="/dashboard" v-if="can('dashboard:read')">查看运营总览</router-link></div></div>
  <ApiErrorNotice :error="state.error" />
  <button v-if="state.canRetry" type="button" :disabled="state.busy" @click="read('retry')">重试原页我的工单</button>
  <template v-if="state.page">
   <div class="task-scroll" tabindex="0" role="region" aria-label="我负责的工单列表，可横向滚动"><table><caption class="sr-only">当前账号负责 · {{workflowLabels[state.filter]}} · 本页记录</caption><thead><tr><th scope="col">登记号与标题</th><th scope="col">状态 / 优先级</th><th scope="col">登记时间</th><th scope="col">服务端观察时间</th><th scope="col">办理入口</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.ticket_id"><td><strong>{{row.receipt_no}}</strong><span>{{row.title}}</span></td><td><span class="state-pill" :data-state="row.state">{{workflowLabels[row.state!]}}</span><span class="prio-pill">{{row.priority}}</span></td><td>{{displayTime(row.registration_created_at)}}</td><td>{{displayTime(row.observed_at)}}</td><td><router-link :to="{path:'/ticket-workflows',query:{ticket_id:row.ticket_id}}" :aria-label="'查看我的工单'+row.receipt_no" class="row-link">查看与办理</router-link></td></tr></tbody></table></div>
   <p v-if="!state.page.items.length" class="task-empty">本状态下没有明确分派给当前账号的工单</p>
  </template>
  <nav class="paging" aria-label="我的工单分页"><span class="paging-note">按登记时间倒序 · 每页 5 条</span><button type="button" :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页我的工单</button><button type="button" :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页我的工单</button></nav>
 </template>
</section>
</template>
<style scoped>
.my-tasks-page{min-width:0;color:#26384f}.my-tasks-page header,.paging{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.my-tasks-page h1{margin:0}.my-tasks-page p{line-height:1.6;overflow-wrap:anywhere}
.task-toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:0 0 12px;padding:10px 14px;border:1px solid #dbe3ed;border-radius:10px;background:#fff}
.task-toolbar label{display:flex;gap:8px;align-items:center;flex-wrap:wrap;font-size:13px;color:#475a72}
.task-status{margin:0 0 0 auto;font-size:13px;color:#5b6b7f}
.my-tasks-page button,.my-tasks-page select{box-sizing:border-box;font:inherit;padding:6px 12px;border:1px solid #c4cfdc;background:#fff;color:#183d68;border-radius:6px}
.my-tasks-page button:disabled{color:#8a97a8;background:#f4f6f9;cursor:not-allowed}
.my-tasks-page a{color:#1f63b6;overflow-wrap:anywhere}.my-tasks-page header a{display:inline-flex;align-items:center;min-height:32px;text-decoration:none}
.task-scroll{overflow:auto;max-width:100%;border:1px solid #dbe3ed;border-radius:10px;background:#fff}
.task-scroll table{border-collapse:collapse;width:100%;min-width:720px;font-size:13px}
.task-scroll th,.task-scroll td{padding:11px 14px;text-align:left;border-bottom:1px solid #edf1f6;vertical-align:middle;overflow-wrap:anywhere}
.task-scroll thead th{background:#f6f8fb;color:#5b6b7f;font-weight:600;font-size:12px}
.task-scroll tbody tr:hover{background:#f8fbff}.task-scroll tbody tr:last-child td{border-bottom:0}
.task-scroll td:first-child{max-width:320px}.task-scroll td:first-child strong{display:block;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#5b6b7f;font-weight:500}.task-scroll td:first-child span{display:block;color:#1f2f43;font-weight:600}
.state-pill{display:inline-block;padding:2px 8px;border-radius:999px;background:#eaf2fd;color:#1f5fa8;font-size:12px;margin-right:6px}
.state-pill[data-state="OPEN"]{background:#fff4e0;color:#8a5200}.state-pill[data-state="RESOLVED"]{background:#f1ecfd;color:#5b3aa8}.state-pill[data-state="CLOSED"],.state-pill[data-state="CANCELLED"]{background:#eef1f5;color:#4a5a6e}
.prio-pill{display:inline-block;padding:1px 7px;border-radius:4px;border:1px solid #d6dee8;font-size:12px;color:#475a72}
.row-link{display:inline-flex;align-items:center;min-height:32px;font-weight:500;text-decoration:none}
.task-empty{padding:28px 12px;text-align:center;color:#5b6b7f;background:#fff;border:1px dashed #d6dee8;border-radius:10px;margin:12px 0 0}
.paging{justify-content:flex-end;margin:12px 0}.paging-note{margin-right:auto;font-size:12px;color:#6a7a8e}
.my-tasks-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
@media(max-width:600px){.task-toolbar{align-items:stretch;flex-direction:column}.task-toolbar label{display:grid}.task-toolbar select{max-width:100%}.task-status{margin:0}.my-tasks-page button,.my-tasks-page select,.my-tasks-page a{min-height:44px;min-width:44px}.paging-note{width:100%}}
</style>
