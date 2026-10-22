<script setup lang="ts">
import {computed,getCurrentInstance,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {onBeforeRouteLeave,type Router} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {
 billingCycleLabels,clockStatusLabels,createServiceTierWorkspace,effectiveModeLabels,
 emptyServiceTierState,tierLabels,parseOptionalTierInteger,type BillingCycle,type EffectiveMode,type ServiceTierCode,type ServiceTierState
} from '../utils/serviceTiers'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import SlaInspectionEvidence from '../components/SlaInspectionEvidence.vue'
import {exportUuid} from '../utils/assetExportJobs'

const state=shallowRef<ServiceTierState>(emptyServiceTierState())
const workspace=createServiceTierWorkspace(api,s=>{state.value=s})
const internal=computed(()=>['padmin','msp'].includes(session.value?.user.role??''))
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&internal.value&&can('sla_scenarios:read')&&!switchingTenant.value))
const canWrite=computed(()=>Boolean(permitted.value&&session.value?.user.role==='padmin'&&can('configurations:write')))
const tab=ref<'ledger'|'sla'>('ledger')
const toTier=ref<ServiceTierCode|''>('')
const effectiveMode=ref<EffectiveMode|''>('')
const deviceCount=ref<string>('')
const billingCycle=ref<BillingCycle|''>('')
const monthlyFeeFen=ref<string>('')
const reason=ref('')
const ticketIdInput=ref('')
const ledgerHeading=ref<HTMLElement|null>(null),ledgerFeedback=ref<HTMLElement|null>(null),slaHeading=ref<HTMLElement|null>(null),slaFeedback=ref<HTMLElement|null>(null),ticketInput=ref<HTMLInputElement|null>(null),slaValidation=ref('')
let ledgerFocusEpoch=0,slaFocusEpoch=0
const blocked=ref(false),confirming=ref(false)
let disposed=false,confirmationEpoch=0,savedInput:string|null=null,routeEpoch=0,removeResolve:(()=>void)|undefined
const router=getCurrentInstance()?.appContext.config.globalProperties.$router as Router|undefined
const clearRouteApproval=()=>{routeEpoch++;removeResolve?.();removeResolve=undefined}

const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,[...(session.value?.capabilities??[])].sort(),session.value?.user.role,permitted.value,canWrite.value]))
const locked=computed(()=>confirming.value||!!state.value.busy||workspace.unresolved())

const preparedVersion=ref<number|null>(null),validation=ref(''),baseline=ref('')
const formSignature=()=>JSON.stringify([toTier.value,effectiveMode.value,deviceCount.value,billingCycle.value,monthlyFeeFen.value,reason.value])
const inputSnapshot=()=>JSON.stringify([context.value,preparedVersion.value,formSignature()])
const approvalSnapshot=()=>JSON.stringify([inputSnapshot(),tab.value,ticketIdInput.value,state.value.page,state.value.receipt,state.value.attempt])
const dirty=computed(()=>preparedVersion.value!==null&&formSignature()!==baseline.value)
const formLocked=computed(()=>locked.value||preparedVersion.value===null)
const sourceChanged=computed(()=>preparedVersion.value!==null&&state.value.page!==null&&preparedVersion.value!==(state.value.page.current?.version??0))
function clearForm(){savedInput=null;preparedVersion.value=null;toTier.value='';effectiveMode.value='';deviceCount.value='';billingCycle.value='';monthlyFeeFen.value='';reason.value='';validation.value='';baseline.value=formSignature()}
async function confirmDiscard(){
 if(disposed||confirming.value||workspace.unresolved())return false
 if(!dirty.value)return true
 const before=approvalSnapshot(),turn=confirmationEpoch;confirming.value=true
 try{await ElMessageBox.confirm('丢弃尚未保存的档位、费用和变更原因？已保存记录不受影响。','保留档位输入',{confirmButtonText:'丢弃输入',cancelButtonText:'继续编辑',type:'warning'});return !disposed&&turn===confirmationEpoch&&before===approvalSnapshot()&&!workspace.unresolved()}
 catch{return false}finally{if(turn===confirmationEpoch)confirming.value=false}
}
async function prepareChange(){
 if(!canWrite.value||locked.value||!state.value.page)return false
 const before=approvalSnapshot(),current=state.value.page.current?structuredClone(state.value.page.current):null
 if(!await confirmDiscard()||disposed||before!==approvalSnapshot()||!canWrite.value||locked.value||!state.value.page)return false
 savedInput=null;preparedVersion.value=current?.version??0;toTier.value=current?.tier??'';effectiveMode.value=current?.effective_mode??'';deviceCount.value=current?.contract_device_count===null||!current?'':String(current.contract_device_count);billingCycle.value=current?.billing_cycle??'';monthlyFeeFen.value=current?.monthly_fee_fen===null||!current?'':String(current.monthly_fee_fen);reason.value='';validation.value='';baseline.value=formSignature();return true
}
function finishSave(ok:boolean,owner:string){if(!ok||disposed||owner!==context.value)return false;if(savedInput===inputSnapshot())clearForm();else validation.value='原操作已保存，当前输入已保留；请重新核对源版本后再提交。';return true}
async function recover(){if(confirming.value||disposed)return false;const owner=context.value;return finishSave(await workspace.retry(),owner)}
watch(context,()=>{
 const s=session.value
 confirmationEpoch++;ledgerFocusEpoch++;slaFocusEpoch++;slaValidation.value='';confirming.value=false;clearRouteApproval()
 tab.value='ledger';clearForm();ticketIdInput.value='';blocked.value=false
 workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,canWrite:canWrite.value}:null)
 if(permitted.value)void readLedger()
},{immediate:true,flush:'sync'})

async function readLedger(next=false,retry=false){
 if(confirming.value||disposed)return false
 const owner=context.value,turn=++ledgerFocusEpoch,active=document.activeElement
 const ok=await(retry?workspace.retryRead():workspace.load(next))
 if(disposed||turn!==ledgerFocusEpoch||owner!==context.value||!ok&&!state.value.error)return ok
 await nextTick();if(!disposed&&turn===ledgerFocusEpoch&&owner===context.value&&(document.activeElement===active||document.activeElement===document.body))(ok?ledgerHeading.value:ledgerFeedback.value)?.focus();return ok
}
function cancelLedger(){ledgerFocusEpoch++;return workspace.cancelRead()}
async function readSla(retry=false){
 if(confirming.value||disposed)return false
 if(!retry&&!exportUuid(ticketIdInput.value.trim())){slaValidation.value='请输入完整有效的工单 UUID，不会按错误输入发送请求。';await nextTick();ticketInput.value?.focus();return false}
 slaValidation.value='';const owner=context.value,turn=++slaFocusEpoch,active=document.activeElement
 const ok=await(retry?workspace.retrySlaRead():workspace.inspectTicketSla(ticketIdInput.value))
 if(disposed||turn!==slaFocusEpoch||owner!==context.value||!ok&&!state.value.slaError)return ok
 await nextTick();if(!disposed&&turn===slaFocusEpoch&&owner===context.value&&(document.activeElement===active||document.activeElement===document.body))(ok?slaHeading.value:slaFeedback.value)?.focus();return ok
}
function cancelSla(){slaFocusEpoch++;return workspace.cancelSlaRead()}
async function submitChange(){
 if(formLocked.value||!toTier.value||!effectiveMode.value||preparedVersion.value===null)return false
 validation.value=''
 let devices:number|null,fee:number|null
 try{devices=parseOptionalTierInteger(deviceCount.value,1000000);fee=parseOptionalTierInteger(monthlyFeeFen.value,Number.MAX_SAFE_INTEGER-1)}catch(error){validation.value=error instanceof Error?error.message:'数字格式无效';return false}
 const owner=context.value;savedInput=inputSnapshot()
 const ok=await workspace.submitChange({to_tier:toTier.value,effective_mode:effectiveMode.value,contract_device_count:devices,billing_cycle:billingCycle.value===''?null:billingCycle.value,monthly_fee_fen:fee,reason:reason.value},preparedVersion.value)
 return finishSave(ok,owner)
}

async function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}const before=approvalSnapshot();return await confirmDiscard()&&!disposed&&before===approvalSnapshot()}
async function leaveRoute(){clearRouteApproval();const turn=routeEpoch,before=approvalSnapshot();if(!await mayLeave()||disposed||turn!==routeEpoch||before!==approvalSnapshot())return false;if(router)removeResolve=router.beforeResolve(()=>!disposed&&turn===routeEpoch&&before===approvalSnapshot()&&!workspace.unresolved());return true}
onBeforeRouteLeave(leaveRoute)
const removeAfter=router?.afterEach(clearRouteApproval),removeError=router?.onError(clearRouteApproval)
const unregister=contextTransitionGuards.register(mayLeave,approvalSnapshot)
function beforeUnload(event:BeforeUnloadEvent){if(workspace.unresolved()||dirty.value){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;confirmationEpoch++;confirming.value=false;clearRouteApproval();removeAfter?.();removeError?.();savedInput=null;unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({state,workspace,mayLeave,prepareChange,submitChange,recover,readLedger,readSla,cancelLedger,cancelSla,preparedVersion,validation,dirty,toTier,effectiveMode,deviceCount,billingCycle,monthlyFeeFen,reason,ticketIdInput})
</script>
<template>
 <section class="service-tiers">
  <div class="compact-page-toolbar page-heading proto-page-head">
   <div>
    <h1 class="page-title-sr" ref="ledgerHeading" tabindex="-1">服务档位与 SLA 时钟台账 </h1>
    
   </div>
   <RouterLink to="/sla-scenarios" class="back">SLA 场景测算</RouterLink>
  </div>
  <p class="panel" v-if="!permitted">当前身份无权访问内部服务档位与 SLA 时钟台账，或正在切换租户。</p>
  <template v-else>
   
   

   <div class="proto-panel workspace">
    <div class="proto-toolbar">
     <div class="proto-tabs" aria-label="服务档位视图">
      <button type="button" :class="{active:tab==='ledger'}" :aria-pressed="tab==='ledger'" :disabled="locked" @click="tab='ledger'">档位快照与变更历史</button>
      <button type="button" :class="{active:tab==='sla'}" :aria-pressed="tab==='sla'" :disabled="locked" @click="tab='sla'">工单 SLA 冻结时钟核验</button>
     </div>
     <button v-if="tab==='ledger'" type="button" :disabled="locked" @click="readLedger()">刷新</button>
    </div>

    <template v-if="tab==='ledger'">
<div class="compact-result-summary">
    <div>
     <span>最新登记档位</span>
     <b>{{ state.page?.current ? tierLabels[state.page.current.tier] : state.page?'未登记':'尚未读取或读取失败' }}</b>
     <small>版本 {{ state.page?.current ? `v${state.page.current.version}` : '—' }}</small>
    </div>
    <div>
     <span>可用性目标</span>
     <b>{{ state.page?.current?.availability_target != null ? `${state.page.current.availability_target}%` : state.page?'待配置':'未知' }}</b>
     <small>来自 sla.tier_availability_target</small>
    </div>
    <div>
     <span>生效方式</span>
     <b>{{ state.page?.current ? effectiveModeLabels[state.page.current.effective_mode] : '—' }}</b>
     <small>{{ state.page?.current ? displayTime(state.page.current.effective_from) : state.page?'尚未设置':'未知' }}</small>
    </div>
    <div>
     <span>在途工单追溯</span>
     <b>不追溯</b>
     <small>开单冻结快照（M2-10）</small>
    </div>
   </div>
     <ReadRecoveryActions :busy="state.busy" :can-retry="!confirming&&workspace.canRetryRead()" :scope-key="context" label="服务档位台账" :cancel="cancelLedger" :retry="()=>readLedger(false,true)" /><div ref="ledgerFeedback" tabindex="-1" role="group" aria-label="服务档位读取结果"><ApiErrorNotice :error="state.error" /></div>
     <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">服务档位变更提交结果尚未确认，请点击下方按钮重试。</p>
     <button v-if="state.attempt&&!state.busy" type="button" @click="recover">重试提交变更</button>
     <p v-if="state.busy" role="status" aria-live="polite">{{ state.busy==='write'?'正在提交服务档位变更…':'正在读取服务档位台账…' }}</p>

     <p v-if="state.receipt" role="status">原保存回执已核对：v{{state.receipt.version}}，商务审批仍待核验。回读失败不代表保存失败，不自动重复提交。</p>
     <button v-if="canWrite&&state.page" type="button" :disabled="locked" @click="prepareChange">按当前快照准备变更</button>
     <p v-if="sourceChanged" role="alert">当前版本已变化，请重新准备并核对变更；旧输入未自动覆盖。</p>
     <p v-if="validation" role="alert">{{validation}}</p>
     <form v-if="canWrite&&state.page" class="tier-form" @submit.prevent="submitChange">
      <h2>登记服务档位变更</h2>
      <div class="form-grid">
       <label>目标档位
        <select v-model="toTier" :disabled="formLocked" required><option value="">明确选择目标档位</option>
         <option value="BASIC">基础版（5×8 工作日历）</option>
         <option value="STANDARD">标准版（7×24 全天）</option>
         <option value="ADVANCED">高级版（7×24 高优保障）</option>
        </select>
       </label>
       <label>生效方式
        <select v-model="effectiveMode" :disabled="formLocked" required><option value="">明确选择生效方式</option>
         <option value="IMMEDIATE">立即生效</option>
         <option value="NEXT_MONTH_FIRST_DAY">次月 1 日 00:00 生效（Asia/Shanghai）</option>
        </select>
       </label>
       <label>合同设备数（可选）
        <input v-model="deviceCount" type="text" inputmode="numeric" aria-label="合同设备数" placeholder="选填，未填则暂不登记" :disabled="formLocked" />
       </label>
       <label>计费周期（可选）
        <select v-model="billingCycle" :disabled="formLocked">
         <option value="">暂不指定</option>
         <option value="MONTHLY">按月</option>
         <option value="QUARTERLY">按季</option>
         <option value="YEARLY">按年</option>
        </select>
       </label>
       <label>月度服务费（分，可选）
        <input v-model="monthlyFeeFen" type="text" inputmode="numeric" aria-label="月度服务费（分）" placeholder="选填，单位：分" :disabled="formLocked" />
       </label>
       <label class="wide">变更原因（必填）
        <input v-model="reason" type="text" maxlength="1000" required placeholder="请说明档位调整依据；保存后作为历史台账留存" :disabled="formLocked" />
       </label>
      </div>
      <button type="submit" class="tier-submit-btn" :disabled="formLocked||sourceChanged||!reason.trim()">保存档位变更（源版本 v{{ preparedVersion??'未准备' }}）</button>
     </form>

     <template v-if="state.page">
      <div class="proto-table-wrap" tabindex="0" role="region" aria-label="服务档位变更历史，可横向滚动">
       <table class="proto-table"><caption>当前读取范围的服务档位变更记录</caption>
        <thead>
         <tr><th scope="col">版本</th><th scope="col">原档位 → 新档位</th><th scope="col">可用性目标</th><th scope="col">生效方式 / 时间</th><th scope="col">合同设备数 / 周期</th><th scope="col">变更原因</th><th scope="col">记录时间</th></tr>
        </thead>
        <tbody>
         <tr v-for="c in state.page.items" :key="c.id">
          <td>v{{ c.version }}</td>
          <td>{{ c.from_tier ? tierLabels[c.from_tier] : '初始设置' }} → <strong>{{ tierLabels[c.to_tier] }}</strong></td>
          <td>{{ c.availability_target != null ? `${c.availability_target}%` : '—' }}</td>
          <td>{{ effectiveModeLabels[c.effective_mode] }}（{{ displayTime(c.effective_from) }}）</td>
          <td>{{ c.contract_device_count ?? '—' }} / {{ c.billing_cycle ? billingCycleLabels[c.billing_cycle] : '—' }}</td>
          <td>{{ c.reason }}</td>
          <td>{{ displayTime(c.changed_at) }}</td>
         </tr>
        </tbody>
       </table>
       <p v-if="!state.page.items.length" class="panel">当前租户尚无服务档位变更记录。</p>
      </div>
      <button type="button" :disabled="locked" @click="readLedger()">返回第一页</button>
      <button type="button" :disabled="locked||!state.page.has_more" @click="readLedger(true)">下一页</button>
     </template>
    </template>

    <template v-else>
     <ReadRecoveryActions :busy="state.slaBusy?'read':null" :can-retry="!confirming&&workspace.canRetrySlaRead()" :scope-key="context" label="工单 SLA" :cancel="cancelSla" :retry="()=>readSla(true)" /><div ref="slaFeedback" tabindex="-1" role="group" aria-label="工单 SLA 读取结果"><ApiErrorNotice :error="state.slaError" /></div><p v-if="slaValidation" role="alert">{{slaValidation}}</p>
     <form class="sla-search" @submit.prevent="readSla()">
      <label>工单 ID（UUID）
       <input ref="ticketInput" v-model="ticketIdInput" type="text" placeholder="输入工单 UUID 核验开单冻结 SLA 时钟" />
      </label>
      <button type="submit" class="tier-submit-btn" :disabled="state.slaBusy||!ticketIdInput.trim()">核验 SLA 时钟</button>
     </form>
     <p v-if="state.slaBusy" role="status">正在读取工单 SLA 时钟…</p>
     <div v-if="state.slaInspection" class="sla-detail"><h2 ref="slaHeading" tabindex="-1">原工单 SLA 核验结果</h2>
      <p>
       工单号：<strong>{{ state.slaInspection.receipt_no }}</strong> ·
       状态：<strong>{{ state.slaInspection.state ?? '未建立正式流程' }}</strong> ·
       优先级：<strong>{{ state.slaInspection.priority ?? '—' }}</strong> ·
       开单冻结档位：<strong>{{ state.slaInspection.frozen_service_tier ? tierLabels[state.slaInspection.frozen_service_tier] : '—' }}</strong> ·
       当前租户档位：<strong>{{ state.slaInspection.current_tenant_service_tier ? tierLabels[state.slaInspection.current_tenant_service_tier] : '未登记' }}</strong> ·
       开单后档位是否变更：<strong>{{ state.slaInspection.tier_changed_since_open ? '是（在途工单截止时间保持不变）' : '否' }}</strong>
      </p>
      <div v-if="state.slaInspection.response_clock && state.slaInspection.recovery_clock" class="proto-table-wrap" tabindex="0" role="region" aria-label="原工单 SLA 时钟，可横向滚动">
       <table class="proto-table"><caption>原工单响应与恢复 SLA 时钟</caption>
        <thead>
         <tr><th scope="col">时钟</th><th scope="col">状态</th><th scope="col">截止时间</th><th scope="col">完成时间</th><th scope="col">50% 提醒</th><th scope="col">80% 提醒</th><th scope="col">100% 超时</th></tr>
        </thead>
        <tbody>
         <tr v-for="clock in [state.slaInspection.response_clock, state.slaInspection.recovery_clock]" :key="clock.name">
          <td>{{ clock.name === 'RESPONSE' ? '响应 SLA' : '恢复 SLA' }}</td>
          <td>{{ clockStatusLabels[clock.status] }}</td>
          <td>{{ clock.due_at ? displayTime(clock.due_at) : (clock.missing_reason ?? '待配置') }}</td>
          <td>{{ clock.completed_at ? displayTime(clock.completed_at) : '—' }}</td>
          <td>{{ clock.thresholds.p50_at ? displayTime(clock.thresholds.p50_at) : '—' }}</td>
          <td>{{ clock.thresholds.p80_at ? displayTime(clock.thresholds.p80_at) : '—' }}</td>
          <td>{{ clock.thresholds.p100_at ? displayTime(clock.thresholds.p100_at) : '—' }}</td>
         </tr>
        </tbody>
       </table>
      </div>
      <SlaInspectionEvidence :inspection="state.slaInspection" />
      <p class="metric-footer">
       重开次数：{{ state.slaInspection.reopen_count }} · 重开计时策略：{{ state.slaInspection.reopen_policy_status === 'PENDING_Q27' ? '待客户确认（Q27）' : '未触发重开' }} · 口径版本：<code>{{ state.slaInspection.metric_version }}</code>
      </p>
     </div>
    </template>
   </div>
  </template>
 </section>
</template>
<style scoped>
.service-tiers :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.service-tiers{min-width:0}.service-tiers .panel{padding:20px;margin:16px 0}.workspace{margin-top:12px}.workspace>p,.workspace>button{margin:12px 18px;font-size:12px}.service-tiers .proto-toolbar{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:12px;padding:0 18px}.service-tiers button,.back{display:inline-flex;align-items:center;min-height:44px;padding:6px 12px;font-size:12px;border:1px solid #cbd7e5;border-radius:4px;background:#fff;color:#245a85;font-family:inherit;cursor:pointer;text-decoration:none;transition:background-color .15s,border-color .15s,color .15s}.service-tiers button:not(:disabled):hover,.back:hover{border-color:#94b8e8;background:#f4f8ff;color:#1d4ed8}.service-tiers button.tier-submit-btn{background:#2563eb;border-color:#2563eb;color:#fff;font-weight:500}.service-tiers button.tier-submit-btn:not(:disabled):hover{background:#1d4ed8;border-color:#1d4ed8;color:#fff}.service-tiers button:disabled{opacity:.5;cursor:not-allowed}.service-tiers .proto-tabs{display:flex;flex-wrap:wrap;gap:12px}.service-tiers .proto-tabs button{border:0;border-bottom:2px solid transparent;border-radius:0;min-height:48px;padding:0 4px;background:none;color:#5f6b78}.service-tiers .proto-tabs button:not(:disabled):hover{background:none;color:#1f5fc8}.service-tiers .proto-tabs button.active{border-bottom-color:#2d79f6;color:#1f5fc8;font-weight:600}.tier-form,.sla-search,.sla-detail{margin:16px 18px;padding:16px;border:1px solid #e2e8f0;border-radius:6px;background:#f8fafc}.tier-form h2{margin:0 0 12px;font-size:14px;color:#1e293b}.form-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(200px,100%),1fr));gap:12px;margin-bottom:14px}.form-grid label,.sla-search label{display:flex;flex-direction:column;gap:5px;font-size:12px;color:#334155}.form-grid label.wide{grid-column:1/-1}.form-grid input,.form-grid select,.sla-search input{min-height:44px;padding:4px 10px;border:1px solid #cbd7e5;border-radius:4px;background:#fff;font-size:12px;transition:border-color .15s,box-shadow .15s}.form-grid input:focus,.form-grid select:focus,.sla-search input:focus{outline:none;border-color:#2d79f6;box-shadow:0 0 0 2px rgba(45,121,246,.12)}.sla-search{display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap}.sla-search label{flex:1;min-width:min(260px,100%)}.metric-footer{margin-top:10px;font-size:12px;color:#64748b}#main-content .service-tiers .compact-result-summary{padding:14px 18px 4px;margin:0}.workspace>.read-recovery,.workspace>div[tabindex="-1"]{margin:8px 18px}
.service-tiers .form-grid input:focus-visible,.service-tiers .form-grid select:focus-visible,.service-tiers .sla-search input:focus-visible{outline:3px solid #176bb5;outline-offset:3px}
</style>
