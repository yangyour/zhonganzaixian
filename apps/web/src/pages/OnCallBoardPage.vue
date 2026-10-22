<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {
 createOnCallBoardWorkspace,emptyOnCallBoardState,formatCountdown,formatDurationSec,
 policyTierLabels,shiftCodeLabels,targetRoleLabels,triggerConditionLabels,
 type OnCallQueueItem,type EscalationStepRun,type EscalationStep,type EscalationPolicy,type OnCallBoardState,type PolicyTierCode,type ShiftCode,type TargetRole,type TriggerCondition
} from '../utils/onCallBoard'
import {tierLabels} from '../utils/serviceTiers'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import OnCallMemberPicker from '../components/OnCallMemberPicker.vue'
import {createOnCallMemberCandidates,emptyOnCallMemberState} from '../utils/onCallMemberCandidates'

const state=shallowRef<OnCallBoardState>(emptyOnCallBoardState())
const workspace=createOnCallBoardWorkspace(api,s=>{state.value=s})
const members=shallowRef(emptyOnCallMemberState()),memberButton=ref<HTMLButtonElement|null>(null),memberStatus=ref('')
const memberWorkspace=createOnCallMemberCandidates(api,s=>{members.value=s})
let memberTurn=0
const internal=computed(()=>['padmin','msp'].includes(session.value?.user.role??''))
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&internal.value&&can('tickets:read')&&!switchingTenant.value))
const canWriteInternal=computed(()=>Boolean(permitted.value&&can('ticket_notes:write_internal')))
const canWriteConfig=computed(()=>Boolean(permitted.value&&session.value?.user.role==='padmin'&&can('configurations:write')))

const tab=ref<'queue'|'roster'|'policy'>('queue')
const blocked=ref(false),refreshPaused=ref(false),readStatus=ref(''),refreshButton=ref<HTMLButtonElement|null>(null)
let readTurn=0

// Roster form
const shiftDate=ref('')
const shiftCode=ref<ShiftCode>('DAY')
const primaryUserId=ref('')
const backupUserId=ref('')
const rosterNote=ref('')

// Handoff form
const handoffRosterId=ref('')
const incomingUserId=ref('')
const handoffNote=ref('')

// Escalation trigger form
const escTicketId=ref('')
const escTier=ref<PolicyTierCode>('STANDARD')
const escStepOrder=ref(1)
const escTrigger=ref<TriggerCondition>('PHONE_UNANSWERED')
const escUnansweredCount=ref(2)

// Policy editor form
const editPolicyTier=ref<PolicyTierCode>('STANDARD')
const preparedPolicy=shallowRef<EscalationPolicy|null>(null)
const editUnansweredCount=ref<number|null>(null)
const editStep2Wait=ref<number|null>(null)
const editStep2Role=ref<TargetRole|null>(null)

const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value,canWriteInternal.value,canWriteConfig.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved())

function clearRoster(){shiftDate.value='';shiftCode.value='DAY';primaryUserId.value='';backupUserId.value='';rosterNote.value=''}
function clearHandoff(){handoffRosterId.value='';incomingUserId.value='';handoffNote.value=''}
function clearEscalation(){escTicketId.value='';escTier.value='STANDARD';escStepOrder.value=1;escTrigger.value='PHONE_UNANSWERED';escUnansweredCount.value=2}
function clearPolicy(){preparedPolicy.value=null;editUnansweredCount.value=null;editStep2Wait.value=null;editStep2Role.value=null}
watch(editPolicyTier,clearPolicy,{flush:'sync'})
const policyDirty=computed(()=>Boolean(preparedPolicy.value&&(editUnansweredCount.value!==preparedPolicy.value.unanswered_escalate_count||editStep2Wait.value!==preparedPolicy.value.steps[1]?.wait_minutes||editStep2Role.value!==preparedPolicy.value.steps[1]?.target_role)))
function preparePolicy(){if(locked.value||policyDirty.value)return false;const current=state.value.board?.policies.find(p=>p.tier===editPolicyTier.value);if(!current||current.steps.length<2)return false;preparedPolicy.value=structuredClone(current);editUnansweredCount.value=current.unanswered_escalate_count;editStep2Wait.value=current.steps[1]!.wait_minutes;editStep2Role.value=current.steps[1]!.target_role;return true}
async function discardPolicy(){if(locked.value)return false;const turn=context.value,draft=draftSnapshot();if(policyDirty.value){try{await ElMessageBox.confirm('清除尚未保存的策略修改？已保存策略不受影响。','保留策略输入',{confirmButtonText:'清除修改',cancelButtonText:'继续编辑',type:'warning'})}catch{return false}}if(turn!==context.value||draft!==draftSnapshot()||locked.value)return false;clearPolicy();return true}
function clearDrafts(){clearRoster();clearHandoff();clearEscalation();clearPolicy();editPolicyTier.value='STANDARD';blocked.value=false}
const dirty=computed(()=>Boolean(shiftDate.value||shiftCode.value!=='DAY'||primaryUserId.value||backupUserId.value||rosterNote.value||handoffRosterId.value||incomingUserId.value||handoffNote.value||escTicketId.value||escTier.value!=='STANDARD'||escStepOrder.value!==1||escTrigger.value!=='PHONE_UNANSWERED'||escUnansweredCount.value!==2||policyDirty.value))
function clearConfirmed(path:string|undefined){if(path==='/oncall/rosters')clearRoster();if(path==='/oncall/handoffs')clearHandoff();if(path==='/oncall/escalations')clearEscalation();if(path==='/escalation-policies')clearPolicy()}
function draftSnapshot(){return JSON.stringify([shiftDate.value,shiftCode.value,primaryUserId.value,backupUserId.value,rosterNote.value,handoffRosterId.value,incomingUserId.value,handoffNote.value,escTicketId.value,escTier.value,escStepOrder.value,escTrigger.value,escUnansweredCount.value,editPolicyTier.value,preparedPolicy.value,editUnansweredCount.value,editStep2Wait.value,editStep2Role.value])}
async function recover(){const turn=context.value,draft=draftSnapshot();if(await workspace.retry()&&turn===context.value&&draft===draftSnapshot())clearConfirmed(state.value.receipt?.path)}
async function refreshBoard(){if(locked.value)return false;const turn=++readTurn,key=context.value;readStatus.value='';const ok=await workspace.load();if(turn===readTurn&&key===context.value&&ok)readStatus.value='已读取当前看板快照；未提交排班、交接或升级。';return ok}
async function cancelRead(){if(!workspace.cancelRead())return false;refreshPaused.value=true;const turn=++readTurn,key=context.value;readStatus.value='已取消读取并暂停自动刷新；迟到结果不会显示，已保存回执不受影响。';await nextTick();if(turn===readTurn&&key===context.value&&!refreshButton.value?.disabled)refreshButton.value?.focus();return true}
function setMember(field:'primary'|'backup'|'incoming',value:string,key:string){if(key!==context.value||!canWriteInternal.value||locked.value)return false;if(field==='primary')primaryUserId.value=value;else if(field==='backup')backupUserId.value=value;else incomingUserId.value=value;return true}
async function loadMembers(){if(!canWriteInternal.value||locked.value||members.value.busy)return false;const turn=++memberTurn,key=context.value;memberStatus.value='';const ok=await memberWorkspace.load();if(turn===memberTurn&&key===context.value&&ok)memberStatus.value=`已读取${members.value.items.length}名当前候选，未替换任何已填人员。`;return ok}
async function cancelMembers(){if(!memberWorkspace.cancel())return false;const turn=++memberTurn,key=context.value;memberStatus.value='已取消候选读取，当前人员输入保留。';await nextTick();if(turn===memberTurn&&key===context.value&&!memberButton.value?.disabled)memberButton.value?.focus();return true}
function openWorkflow(row:OnCallQueueItem|EscalationStepRun,event:MouseEvent,navigate:(event?:MouseEvent)=>unknown){
 const board=state.value.board
 if(!permitted.value||locked.value||!board||![...board.queue,...board.step_runs].some(current=>current===row)){event.preventDefault();return false}
 return navigate(event)
}
function toggleRefresh(){refreshPaused.value=!refreshPaused.value;readStatus.value=refreshPaused.value?'自动刷新已暂停，可手动读取。':'已恢复30秒刷新；填写表单、操作未确认、读取失败或页面隐藏时仍会暂停。'}
let refreshTimer:ReturnType<typeof setInterval>|null=null
function clearRefreshTimer(){if(refreshTimer){clearInterval(refreshTimer);refreshTimer=null}}
function startRefreshTimer(){
 clearRefreshTimer()
 if(!permitted.value)return
 refreshTimer=setInterval(()=>{if(permitted.value&&!refreshPaused.value&&!members.value.busy&&!state.value.error&&!locked.value&&!dirty.value&&!document.hidden&&!document.activeElement?.closest('form,.table-wrap,.proto-tabs'))void workspace.load()},30000)
}

watch(context,()=>{
 readTurn++;readStatus.value='';memberTurn++;memberStatus.value=''
 const s=session.value
 memberWorkspace.reset(canWriteInternal.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version}:null)
 tab.value='queue';clearDrafts()
 workspace.reset(permitted.value&&s?{
  tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,
  canWriteInternal:canWriteInternal.value,canWriteConfig:canWriteConfig.value
 }:null)
 if(permitted.value){
  void workspace.load()
  startRefreshTimer()
 }else{
  clearRefreshTimer()
 }
},{immediate:true,flush:'sync'})

async function submitRoster(){
 const turn=context.value,draft=draftSnapshot()
 if(!shiftDate.value)return false
 const existing=state.value.board?.rosters.find(r=>r.shift_date===shiftDate.value&&r.shift_code===shiftCode.value)
 const ok=await workspace.submitRoster({
  expected_version:existing?.version??0,
  shift_date:shiftDate.value,
  shift_code:shiftCode.value,
  primary_oncall_user_id:primaryUserId.value.trim(),
  backup_oncall_user_id:backupUserId.value.trim()||null,
  note:rosterNote.value.trim()||null
 })
 if(ok&&turn===context.value&&draft===draftSnapshot())clearRoster()
 return ok
}

async function submitHandoff(){
 const turn=context.value,draft=draftSnapshot()
 const ok=await workspace.submitHandoff({
  roster_id:handoffRosterId.value.trim()||null,
  incoming_user_id:incomingUserId.value.trim(),
  note:handoffNote.value.trim()
 })
 if(ok&&turn===context.value&&draft===draftSnapshot())clearHandoff()
 return ok
}

async function submitEscalation(){
 const turn=context.value,draft=draftSnapshot()
 const ok=await workspace.submitEscalation({
  ticket_id:escTicketId.value.trim(),
  tier:escTier.value,
  step_order:escStepOrder.value,
  trigger_condition:escTrigger.value,
  unanswered_phone_count:escUnansweredCount.value
 })
 if(ok&&turn===context.value&&draft===draftSnapshot())clearEscalation();return ok
}

async function submitPolicyUpdate(){
 const turn=context.value,draft=draftSnapshot()
 const current=preparedPolicy.value
 if(!current||current.tier!==editPolicyTier.value||typeof editUnansweredCount.value!=='number'||typeof editStep2Wait.value!=='number'||!editStep2Role.value)return false
 const expected=current.persisted?current.version:0
 const baseSteps:EscalationStep[]=current.steps.map((s,idx)=>idx===1?{...s,wait_minutes:editStep2Wait.value!,target_role:editStep2Role.value!}:{...s})
 const ok=await workspace.submitPolicyUpdate({
  tier:editPolicyTier.value,
  expected_version:expected,
  unanswered_escalate_count:editUnansweredCount.value,
  steps:baseSteps
 })
 if(ok&&turn===context.value&&draft===draftSnapshot())clearPolicy();return ok
}

async function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}if(!dirty.value)return true;const turn=context.value,draft=draftSnapshot();try{await ElMessageBox.confirm('离开将丢弃尚未保存的排班、交接或升级输入；已保存记录不受影响。','保留未保存输入',{confirmButtonText:'丢弃并离开',cancelButtonText:'继续编辑',type:'warning'});return turn===context.value&&draft===draftSnapshot()&&!workspace.unresolved()}catch{return false}}
onBeforeRouteLeave(mayLeave)
const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(event:BeforeUnloadEvent){if(workspace.unresolved()||dirty.value){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{memberTurn++;memberWorkspace.dispose();clearRefreshTimer();unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({state,workspace,members,loadMembers,cancelMembers,openWorkflow,refreshPaused,refreshBoard,cancelRead,toggleRefresh,discardPolicy,mayLeave,dirty,shiftDate,primaryUserId,backupUserId,rosterNote,handoffRosterId,incomingUserId,handoffNote,escTicketId,editStep2Wait,submitRoster,preparedPolicy,preparePolicy,submitPolicyUpdate,editPolicyTier,editUnansweredCount,editStep2Role})
</script>
<template>
 <section class="oncall-board" :aria-busy="!!state.busy">
  <h1 class="page-title-sr">值班看板与升级链管理 </h1>
  <p class="panel" v-if="!permitted">当前身份无权访问内部值班看板，或正在切换租户。</p>
  <template v-else>
   
   <p v-if="dirty" role="status">存在未保存输入，自动刷新已暂停；离开前会提醒。</p>
   <p v-if="state.receipt" role="status">原操作回执已核对：{{state.receipt.identity}} · {{displayTime(state.receipt.recordedAt)}}。后续回读失败不表示原操作未保存。</p>
   

   <div class="proto-panel workspace">
    <div class="proto-toolbar content-toolbar">
     <div class="proto-tabs" aria-label="值班看板视图">
      <button type="button" :class="{active:tab==='queue'}" :aria-pressed="tab==='queue'" :disabled="locked" @click="tab='queue'">倒计时队列与升级执行</button>
      <button type="button" :class="{active:tab==='roster'}" :aria-pressed="tab==='roster'" :disabled="locked" @click="tab='roster'">当班排班与 18:00 交接班</button>
      <button type="button" :class="{active:tab==='policy'}" :aria-pressed="tab==='policy'" :disabled="locked" @click="tab='policy'">四套升级策略配置</button>
     </div>
     <button ref="refreshButton" type="button" :disabled="locked" @click="refreshBoard">{{state.readFailed?'重试读取看板':'立即刷新'}}</button>
     <button type="button" :aria-pressed="refreshPaused" @click="toggleRefresh">{{refreshPaused?'恢复自动刷新':'暂停自动刷新'}}</button>
     <button v-if="state.busy==='read'" type="button" @click="cancelRead">取消读取</button>
    <div class="content-actions"><RouterLink to="/sla-reports" class="back">SLA 月报与赔付</RouterLink></div></div>

    <ApiErrorNotice :error="state.error" />
    <p v-if="readStatus" role="status" aria-live="polite">{{readStatus}}</p>
    <p v-if="state.readFailed" role="status">看板读取尚未成功，自动刷新已暂停；请重试读取。不能据此判断队列为空。</p>
    
    <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">值班或升级操作提交结果尚未确认，请点击重试。</p>
    <button v-if="state.attempt&&!state.busy" type="button" @click="recover">重试上次提交</button>
    <p v-if="state.busy" role="status" aria-live="polite">{{ state.busy==='write'?'正在提交值班/升级记录…':'正在同步值班看板…' }}</p>

    <template v-if="tab==='queue'">
     
     <div class="table-wrap" tabindex="0" role="region" aria-label="倒计时队列，可横向滚动" v-if="state.board?.queue.length">
      <table class="proto-table"><caption>倒计时队列</caption>
       <thead>
        <tr>
         <th scope="col">工单 ID</th>
         <th scope="col">优先级 / 档位</th>
         <th scope="col">状态</th>
         <th scope="col">SLA 倒计时</th>
         <th scope="col">超时进度</th>
         <th scope="col">当前升级步</th>
         <th scope="col">下一升级目标</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="item in state.board.queue" :key="item.ticket_id">
         <td><RouterLink v-if="!locked" :to="{path:'/ticket-workflows',query:{ticket_id:item.ticket_id}}" custom v-slot="{href,navigate}"><a class="workflow-link" :href="href" :aria-label="'查看并办理工单 '+item.ticket_id" @click="openWorkflow(item,$event,navigate)">查看并办理<code>{{item.ticket_id}}</code></a></RouterLink><code v-else>{{item.ticket_id}}</code></td>
         <td>{{ item.priority }} · {{ tierLabels[item.tier_snapshot] }}</td>
         <td>{{ item.state }}</td>
         <td :class="{danger:item.breached}">{{ formatCountdown(item.countdown_remaining_sec) }}</td>
         <td>{{ item.countdown_progress_pct!==null?`${(item.countdown_progress_pct*100).toFixed(1)}%`:'—' }}</td>
         <td>第 {{ item.current_escalation_step }} 步</td>
         <td>{{ item.next_escalation_target_role?`第 ${item.next_escalation_step} 步 · ${targetRoleLabels[item.next_escalation_target_role]}`:'已达末级' }}</td>
        </tr>
       </tbody>
      </table>
     </div>
     <p v-else-if="state.board&&!state.busy&&!state.error" class="empty">当前看板快照未返回在途工单。</p>

     <form v-if="canWriteInternal" class="action-form" @submit.prevent="submitEscalation">
      <h2>登记升级执行 / 电话未接听升级（M5-12 / M5-13）</h2>
      <div class="form-grid">
       <label>工单 UUID
        <input type="text" v-model="escTicketId" :disabled="locked" required placeholder="输入在途工单 UUID" />
       </label>
       <label>适用升级链
        <select v-model="escTier" :disabled="locked">
         <option value="BASIC">基础版升级链</option>
         <option value="STANDARD">标准版升级链</option>
         <option value="ADVANCED">高级版升级链</option>
         <option value="FALLBACK">未配置档位兜底链</option>
        </select>
       </label>
       <label>当前步骤序号
        <input type="number" min="1" max="8" v-model.number="escStepOrder" :disabled="locked" />
       </label>
       <label>触发条件
        <select v-model="escTrigger" :disabled="locked">
         <option value="PHONE_UNANSWERED">电话连续未接听</option>
         <option value="COUNTDOWN_ZERO">SLA倒计时归零</option>
         <option value="UNACKNOWLEDGED">超时未确认</option>
         <option value="UNRESOLVED">超时未恢复</option>
        </select>
       </label>
       <label>连续未接听次数
        <input type="number" min="0" max="10" v-model.number="escUnansweredCount" :disabled="locked" />
       </label>
      </div>
      <button type="submit" class="primary" :disabled="locked||!escTicketId.trim()">执行升级并记录短信告知</button>
     </form>

     <div class="table-wrap" tabindex="0" role="region" aria-label="升级执行与短信意图流水，可横向滚动" v-if="state.board?.step_runs.length">
      <h3>最近升级执行与短信告知流水</h3>
      <table class="proto-table"><caption>升级执行与短信意图流水</caption>
       <thead>
        <tr>
         <th scope="col">记录时间</th>
         <th scope="col">工单 ID</th>
         <th scope="col">档位 / 策略版本</th>
         <th scope="col">触发条件</th>
         <th scope="col">未接听次数</th>
         <th scope="col">升级结果</th>
         <th scope="col">短信告知状态</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="run in state.board.step_runs" :key="run.id">
         <td>{{ displayTime(run.recorded_at) }}</td>
         <td><RouterLink v-if="!locked" :to="{path:'/ticket-workflows',query:{ticket_id:run.ticket_id}}" custom v-slot="{href,navigate}"><a class="workflow-link" :href="href" :aria-label="'查看升级记录对应工单 '+run.ticket_id" @click="openWorkflow(run,$event,navigate)">查看对应工单<code>{{run.ticket_id}}</code></a></RouterLink><code v-else>{{run.ticket_id}}</code></td>
         <td>{{ policyTierLabels[run.tier] }} (v{{ run.policy_version }})</td>
         <td>{{ triggerConditionLabels[run.trigger_condition] }}</td>
         <td>{{ run.unanswered_phone_count }}</td>
         <td>{{ run.escalated_to_step&&run.escalated_target_role?`升级至第 ${run.escalated_to_step} 步 (${targetRoleLabels[run.escalated_target_role]})`:'保持当前步骤' }}</td>
         <td>{{ run.sms_notice_summary||run.sms_notice_status }}</td>
        </tr>
       </tbody>
      </table>
     </div>
    </template>

    <template v-else-if="tab==='roster'">
     <section v-if="canWriteInternal" class="member-tools" aria-label="内部候选人员" :aria-busy="members.busy">
      <h2>排班与接班人员选择</h2>
      <p>候选为当前租户活跃处理人员（最多 100 人）；未列出时可按 UUID 填写。</p>
      <button ref="memberButton" type="button" :disabled="locked||members.busy" @click="loadMembers">{{members.error?'重试读取内部候选人员':'读取内部候选人员'}}</button>
      <button v-if="members.busy" type="button" @click="cancelMembers">取消候选读取</button>
      <p v-if="members.busy" role="status">正在读取候选人员；可以继续填写UUID，未自动选择人员。</p>
      <p v-if="memberStatus" role="status">{{memberStatus}}</p>
      <ApiErrorNotice :error="members.error" />
      <p v-if="members.loaded&&!members.items.length" role="status">本次没有返回候选；这不是租户全部人员清单。</p>
     </section>
     <div class="split-forms" v-if="canWriteInternal">
      <form class="action-form" @submit.prevent="submitRoster">
       <h2>登记/更新当班主备排班（CONFIG_PENDING_Q15）</h2>
       <div class="form-grid">
        <label>值班日期
         <input type="date" v-model="shiftDate" :disabled="locked" required />
        </label>
        <label>班次
         <select v-model="shiftCode" :disabled="locked">
          <option value="DAY">白班（09:00-18:00）</option>
          <option value="NIGHT">夜班（18:00-次日09:00）</option>
          <option value="FULL_DAY">全天值班（24h）</option>
         </select>
        </label>
        <OnCallMemberPicker :model-value="primaryUserId" :scope-key="context" @update:model-value="(value,key)=>setMember('primary',value,key)" id-prefix="roster-primary" label="主班工程师" :items="members.items" :busy="members.busy" :locked="locked" required />
        <OnCallMemberPicker :model-value="backupUserId" :scope-key="context" @update:model-value="(value,key)=>setMember('backup',value,key)" id-prefix="roster-backup" label="备班工程师" :items="members.items" :busy="members.busy" :locked="locked" :excluded-id="primaryUserId" />
        <label class="full">排班备注
         <input type="text" v-model="rosterNote" :disabled="locked" maxlength="500" placeholder="可选排班说明" />
        </label>
       </div>
       <button type="submit" class="primary" :disabled="locked||!primaryUserId.trim()">保存当班排班</button>
      </form>

      <form class="action-form" @submit.prevent="submitHandoff">
       <h2>登记 18:00 交接班记录（只追加台账）</h2>
       <div class="form-grid">
        <label>关联排班 ID（可选）
         <input type="text" v-model="handoffRosterId" :disabled="locked" placeholder="排班记录 UUID" />
        </label>
        <OnCallMemberPicker :model-value="incomingUserId" :scope-key="context" @update:model-value="(value,key)=>setMember('incoming',value,key)" id-prefix="handoff-incoming" label="接班工程师" :items="members.items" :busy="members.busy" :locked="locked" :excluded-id="session?.user.id" required />
        <label class="full">交接班摘要说明
         <textarea v-model="handoffNote" :disabled="locked" rows="2" maxlength="1000" required placeholder="填写 18:00 交接重点事项"></textarea>
        </label>
       </div>
       <button type="submit" class="primary" :disabled="locked||!incomingUserId.trim()||!handoffNote.trim()">固化 18:00 交接班快照</button>
      </form>
     </div>

     <div class="table-wrap" tabindex="0" role="region" aria-label="排班记录，可横向滚动" v-if="state.board?.rosters.length">
      <h3>当班主备排班列表</h3>
      <table class="proto-table"><caption>排班记录</caption>
       <thead>
        <tr>
         <th scope="col">日期 / 班次</th>
         <th scope="col">交接时刻</th>
         <th scope="col">主值班人</th>
         <th scope="col">备值班人</th>
         <th scope="col">版本</th>
         <th scope="col">策略状态</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="r in state.board.rosters" :key="r.id">
         <td>{{ r.shift_date }} · {{ shiftCodeLabels[r.shift_code] }}</td>
         <td>{{ r.handoff_local_time }}</td>
         <td><code>{{ r.primary_oncall_user_id }}</code></td>
         <td><code>{{ r.backup_oncall_user_id||'未设置' }}</code></td>
         <td>v{{ r.version }}</td>
         <td>{{ r.roster_policy_status }}</td>
        </tr>
       </tbody>
      </table>
     </div>

     <div class="table-wrap" tabindex="0" role="region" aria-label="交接记录，可横向滚动" v-if="state.board?.handoffs.length">
      <h3>18:00 交接班只追加历史台账</h3>
      <table class="proto-table"><caption>交接记录</caption>
       <thead>
        <tr>
         <th scope="col">交接时间</th>
         <th scope="col">交班人 → 接班人</th>
         <th scope="col">未确认</th>
         <th scope="col">进行中</th>
         <th scope="col">待复核</th>
         <th scope="col">交接摘要</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="h in state.board.handoffs" :key="h.id">
         <td>{{ displayTime(h.handed_off_at) }} ({{ h.handoff_local_time }})</td>
         <td><code>{{ h.outgoing_user_id }}</code> → <code>{{ h.incoming_user_id }}</code></td>
         <td>{{ h.unacknowledged_count }}</td>
         <td>{{ h.in_progress_count }}</td>
         <td>{{ h.pending_review_count }}</td>
         <td>{{ h.note }}</td>
        </tr>
       </tbody>
      </table>
     </div>
    </template>

    <template v-else>
     <div class="table-wrap" tabindex="0" role="region" aria-label="升级策略，可横向滚动" v-if="state.board?.policies.length">
      <table class="proto-table"><caption>升级策略</caption>
       <thead>
        <tr>
         <th scope="col">升级链档位</th>
         <th scope="col">版本</th>
         <th scope="col">未接听升级阈值</th>
         <th scope="col">重呼叫间隔</th>
         <th scope="col">升级步骤明细</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="p in state.board.policies" :key="p.tier">
         <td>{{ policyTierLabels[p.tier] }}</td>
         <td>{{ p.persisted?`v${p.version}`:'默认模板 (v0)' }}</td>
         <td>连续 {{ p.unanswered_escalate_count }} 次未接听</td>
         <td>待确认 ({{ p.policy_status }})</td>
         <td>
          <span v-for="s in p.steps" :key="s.step_order" class="step-pill">
           #{{ s.step_order }} {{ triggerConditionLabels[s.trigger_condition] }} ({{ s.wait_minutes }}m) → {{ targetRoleLabels[s.target_role] }}
          </span>
         </td>
        </tr>
       </tbody>
      </table>
     </div>

     <form v-if="canWriteConfig" class="action-form" @submit.prevent="submitPolicyUpdate">
      <h2>调整档位升级链参数（padmin 专属，M5-13）</h2>
      <div class="form-grid">
       <label>目标档位
        <select v-model="editPolicyTier" :disabled="locked||policyDirty">
         <option value="BASIC">基础版升级链</option>
         <option value="STANDARD">标准版升级链</option>
         <option value="ADVANCED">高级版升级链</option>
         <option value="FALLBACK">未配置档位兜底链</option>
        </select>
       </label>
       <button type="button" :disabled="locked||policyDirty" @click="preparePolicy">载入当前策略版本</button><button type="button" :disabled="locked" @click="discardPolicy">清除策略输入</button><p>{{preparedPolicy?`已锁定源版本 v${preparedPolicy.version}，刷新不自动替换此版本`:'尚未载入策略；先选档位并读取当前版本，缺少第2步时不擅自新增步骤。'}}</p>
       <label>电话连续未接听升级次数
        <input type="number" min="1" max="10" v-model.number="editUnansweredCount" :disabled="locked||!preparedPolicy" />
       </label>
       <label>第 2 步等待分钟数
        <input type="number" min="0" max="10080" v-model.number="editStep2Wait" :disabled="locked||!preparedPolicy" />
       </label>
       <label>第 2 步升级目标角色
        <select v-model="editStep2Role" :disabled="locked||!preparedPolicy"><option :value="null">尚未载入</option><option value="L1_ONCALL">一线值班工程师</option>
         <option value="L2_ONCALL">二线值班专家</option>
         <option value="DUTY_LEAD">值班主管</option>
         <option value="SERVICE_MANAGER">服务经理</option>
         <option value="CUSTOMER_ADMIN">客户管理员</option>
         <option value="DECISION_MAKER">决策人</option>
        </select>
       </label>
      </div>
      <button type="submit" class="primary" :disabled="locked||!preparedPolicy">保存升级策略链</button>
     </form>
    </template>
   </div>
  </template>
 </section>
</template>
<style scoped>
.oncall-board{display:grid;gap:16px}
.workspace{display:grid;gap:16px}
.member-tools{border:1px solid #dce4ee;padding:12px;border-radius:5px}.member-tools p{line-height:1.6;overflow-wrap:anywhere}.member-tools button{margin-right:8px}
.action-form{display:grid;gap:12px;padding:16px;border:1px solid var(--border,#d0d7de);border-radius:8px;background:var(--surface-subtle,#f8fafc)}
.split-forms{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}
.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.form-grid label{display:grid;gap:6px;font-weight:600}
.form-grid label.full{grid-column:1/-1}
.table-wrap{max-width:100%;overflow:auto}
.table-wrap:focus-visible,button:focus-visible,.back:focus-visible,.workflow-link:focus-visible{outline:3px solid #2563eb;outline-offset:3px}
.workflow-link{display:inline-flex;flex-direction:column;justify-content:center;gap:4px;min-height:44px;min-width:44px;color:#164e91;text-decoration:underline;overflow-wrap:anywhere}
caption{text-align:left;font-weight:600;font-size:13px;color:#1f2d3d;padding:12px 10px 8px}
.step-pill{display:inline-block;margin:2px 6px 2px 0;padding:2px 8px;border-radius:12px;background:#eef2ff;font-size:12px}
.danger{color:#b91c1c;font-weight:700}
button,input,select,textarea,.back{min-height:44px}
@media(max-width:760px){
 .split-forms,.form-grid{grid-template-columns:1fr}
}
</style>
