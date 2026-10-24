<script setup lang="ts">
import { useDemoExample } from '../utils/useDemoExample'
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {
 createSlaReportWorkspace,emptySlaReportState,formatDurationSec,formatFenToYuan,
 serviceStatusLabels,type SlaReportState,type SlaLatencyStats
} from '../utils/onCallBoard'
import {tierLabels} from '../utils/serviceTiers'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {slaServiceReviewExport,downloadSlaServiceReview} from '../utils/slaServiceReviewExport'

const state=shallowRef<SlaReportState>(emptySlaReportState())
const workspace=createSlaReportWorkspace(api,s=>{state.value=s})
const latencyDuration=(value:SlaLatencyStats,percentile:'p50_sec'|'p95_sec')=>
 (value.unknown_count??0)>0?'来源待核验':formatDurationSec(value[percentile])
const internal=computed(()=>['padmin','msp'].includes(session.value?.user.role??''))
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&internal.value&&can('sla_scenarios:read')&&!switchingTenant.value))
const canWriteInternal=computed(()=>Boolean(permitted.value&&can('ticket_notes:write_internal')))
const canWriteConfig=computed(()=>Boolean(permitted.value&&session.value?.user.role==='padmin'&&can('configurations:write')))

const periodInput=ref('')
const freezeSnapshot=ref(false)
watch(periodInput,()=>{freezeSnapshot.value=false},{flush:'sync'})
const exporting=ref(false),exportStatus=ref(''),exportError=shallowRef<unknown>(null);let exportTurn=0
const blocked=ref(false),readStatus=ref(''),queryButton=ref<HTMLButtonElement|null>(null),detailHeading=ref<HTMLElement|null>(null)
let readTurn=0,readOrigin:HTMLButtonElement|null=null

const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value,canWriteInternal.value,canWriteConfig.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved()||exporting.value)

watch(context,()=>{
 readTurn++;readOrigin=null;readStatus.value='';blocked.value=false
 const s=session.value
 workspace.reset(permitted.value&&s?{
  tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,
  canWriteInternal:canWriteInternal.value,canWriteConfig:canWriteConfig.value
 }:null)
 periodInput.value='';freezeSnapshot.value=false
},{immediate:true,flush:'sync'})

watch([context,periodInput,()=>state.value.report],()=>{exportTurn++;exporting.value=false;exportStatus.value='';exportError.value=null},{flush:'sync'})
async function exportServices(){
 const s=session.value,report=state.value.report
 if(!s||!permitted.value||locked.value||!report||periodInput.value!==report.period)return false
 const key=context.value,turn=++exportTurn;exporting.value=true;exportStatus.value='';exportError.value=null
 const current=()=>turn===exportTurn&&key===context.value&&permitted.value&&state.value.report===report&&periodInput.value===report.period&&!state.value.busy&&!workspace.unresolved()
 try{await nextTick();if(!current())return false
  const file=slaServiceReviewExport(report,{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,canWriteInternal:canWriteInternal.value,canWriteConfig:canWriteConfig.value},report.period)
  if(!current()||!downloadSlaServiceReview(file,current))return false
  if(current())exportStatus.value=`已生成 ${file.period} 的 ${file.serviceCount} 条已加载服务核对清单并请求下载；是否保存由浏览器确认。不是正式报告或账单。`
  return true
 }catch(error){if(current())exportError.value=error;return false}finally{if(turn===exportTurn)exporting.value=false}
}
async function queryReport(){
 if(locked.value)return false
 const turn=++readTurn,key=context.value;readOrigin=queryButton.value;readStatus.value=''
 const ok=await workspace.load(periodInput.value)
 if(turn!==readTurn||key!==context.value)return false
 if(ok)readStatus.value='已读取 '+state.value.report?.period+' 月报；未保存或冻结快照。'
 return ok
}
async function readDetail(serviceId:string,event:Event){
 const report=state.value.report;if(!report||locked.value)return false
 const turn=++readTurn,key=context.value;readOrigin=event.currentTarget instanceof HTMLButtonElement?event.currentTarget:null;readStatus.value=''
 const ok=await workspace.loadServiceDetail(report.period,serviceId)
 if(turn!==readTurn||key!==context.value)return false
 if(ok){readStatus.value='已读取服务区间解释；未保存或冻结快照。';await nextTick();if(turn===readTurn&&key===context.value)detailHeading.value?.focus()}
 return ok
}
async function cancelRead(){
 if(!workspace.cancelRead())return false
 const turn=++readTurn,key=context.value,origin=readOrigin;readStatus.value='已取消读取，迟到结果不会显示；不会取消或撤销已保存快照。'
 await nextTick();if(turn===readTurn&&key===context.value&&origin?.isConnected&&!origin.disabled)origin.focus()
 return true
}
async function submitMonthlyEvaluation(){
 if(!state.value.report||periodInput.value!==state.value.report.period)return false
 readTurn++;readStatus.value='';readOrigin=queryButton.value
 const existing=state.value.report.monthly_result
 const ok=await workspace.submitMonthlyResult({
  report_month:periodInput.value,
  expected_version:existing?.version??0,
  freeze:freezeSnapshot.value
 })
 if(ok)freezeSnapshot.value=false
}

async function retryMonthly(){readTurn++;readStatus.value='';readOrigin=queryButton.value;return workspace.retry()}

function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}return true}
onBeforeRouteLeave(mayLeave)
const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(event:BeforeUnloadEvent){if(workspace.unresolved()){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{exportTurn++;exporting.value=false;readTurn++;readOrigin=null;unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({state,workspace,mayLeave,periodInput,freezeSnapshot,submitMonthlyEvaluation,queryReport,readDetail,cancelRead,readStatus,exportServices,exportStatus})
const demoExample = useDemoExample<{month:string}>('sla-reports', permitted, () => periodInput.value, async value => { periodInput.value=value.month; await queryReport() })
</script>
<template>
 <section class="sla-reports">
  <h1 class="page-title-sr">SLA 月度报表与赔付试算 </h1>
 <p v-if="demoExample.loaded.value" class="hint" role="note">[DEMO] 已加载独立测试样例；这些是假设输入，不代表真实业务或已生效配置。</p>
  <p class="panel" v-if="!permitted">当前身份无权访问内部 SLA 月报与赔付台账，或正在切换租户。</p>
  <template v-else>
   

   <div class="proto-toolbar content-toolbar">
    <form class="period-bar" @submit.prevent="queryReport">
     <label>统计月份
      <input type="month" v-model="periodInput" :disabled="locked" required />
     </label>
     <button ref="queryButton" type="submit" :disabled="locked">查询月报</button>
    </form>
   <div class="content-actions"><RouterLink to="/oncall-board" class="back">值班看板</RouterLink></div></div>

   <p v-if="!periodInput">请明确选择统计月份；不会自动查询或保存预设月份。</p>
   <p v-if="state.report&&periodInput!==state.report.period" role="status">月份输入已改变。当前显示仍为 {{state.report.period}}，请先查询新月份；不能跨期保存或导出。</p>
   <p v-if="state.receipt" role="status">已核对原保存回执：{{state.receipt.report_month}} · v{{state.receipt.version}} · {{state.receipt.frozen?'已冻结':'未冻结'}}。回读失败不代表保存失败，不自动重复提交。</p>
   <ApiErrorNotice :error="state.error" />
   <p v-if="readStatus" role="status">{{readStatus}}</p>
   <button v-if="state.busy==='read'" type="button" @click="cancelRead">取消读取月报或明细</button>
   <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">月度 SLA 快照提交结果尚未确认，请点击重试。</p>
   <button v-if="state.attempt&&!state.busy" type="button" @click="retryMonthly">重试提交月度快照</button>
   <p v-if="state.busy" role="status" aria-live="polite">{{ state.busy==='write'?'正在保存/冻结月度 SLA 快照…':'正在计算 SLA 月报…' }}</p>

   <template v-if="state.report">
    <div class="review-export"><button type="button" :disabled="locked||periodInput!==state.report.period" aria-describedby="sla-review-export-note" @click="exportServices">{{exporting?'正在生成服务核对 CSV…':'导出当前服务核对 CSV'}}</button><p id="sla-review-export-note">仅导出当前已加载的 {{state.report.services.length}} 条服务可用性及观察时间；不包含赔付金额、工单明细或冻结快照，不是正式报告或账单。下载不会保存或冻结月报。</p><ApiErrorNotice :error="exportError" /><p v-if="exportStatus" role="status">{{exportStatus}}</p></div>
    <div class="compact-result-summary">
     <div>
      <span>已核验可用性均值</span>
      <b>{{ state.report.kpis.mean_verified_availability_pct!==null?`${state.report.kpis.mean_verified_availability_pct}%`:'待配置' }}</b>
      <small>目标 {{ state.report.target_availability_pct===null?'未配置':`${state.report.target_availability_pct}%` }} ({{ state.report.current_tier? tierLabels[state.report.current_tier]:'未配置' }})</small>
     </div>
     <div>
      <span>不可用并集总时长</span>
      <b>{{ formatDurationSec(state.report.kpis.unavailable_union_total_sec) }}</b>
      <small>重叠故障已去重合并</small>
     </div>
     <div>
      <span>响应耗时 MTTA</span>
      <b>P50 {{ latencyDuration(state.report.kpis.mtta,'p50_sec') }}</b>
      <small>P95 {{ latencyDuration(state.report.kpis.mtta,'p95_sec') }} · 样本 {{ state.report.kpis.mtta.sample_count }}<template v-if="state.report.kpis.mtta.unknown_count"> · 待核验 {{state.report.kpis.mtta.unknown_count}}</template></small>
     </div>
     <div>
      <span>恢复耗时 MTTR</span>
      <b>P50 {{ latencyDuration(state.report.kpis.mttr,'p50_sec') }}</b>
      <small>P95 {{ latencyDuration(state.report.kpis.mttr,'p95_sec') }} · 未恢复 {{ state.report.kpis.unrecovered_total_count }}<template v-if="state.report.kpis.mttr.unknown_count"> · 恢复待核验 {{state.report.kpis.mttr.unknown_count}}</template></small>
     </div>
     <div>
      <span>未知区间单独隔离</span>
      <b>{{ formatDurationSec(state.report.kpis.unknown_interval_total_sec) }}</b>
      <small>不计入已核验可用（Q08）</small>
     </div>
    </div>

    <p v-if="state.report.kpis.mttr.source_version" class="muted">响应耗时取告警首次人工确认，恢复耗时取已验证的监控恢复，均从原问题发生时刻起算。按问题发生月份归属，使用本次读取时已知的证据；不等同工单解决时长，也不改写已冻结月报。</p>

    <div class="proto-panel workspace">
     <h2>服务节点可用性与区间明细（M5-9 / M5-10）</h2>
     <div class="table-wrap" tabindex="0" role="region" aria-label="SLA 数据表，可横向滚动">
      <table class="proto-table">
       <thead>
        <tr>
         <th>资产监控对象</th>
         <th>目标可用性</th>
         <th>已核验可用性</th>
         <th>可用性上限（含未知）</th>
         <th>不可用并集</th>
         <th>未知隔离时长</th>
         <th>MTTA / MTTR (P50)</th>
         <th>状态</th>
         <th>明细下钻</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="svc in state.report.services" :key="svc.service_id">
         <td>{{ svc.service_name }} (<code>{{ svc.service_id }}</code>)</td>
         <td>{{ svc.target_pct===null?'未配置':`${svc.target_pct}%` }}</td>
         <td>{{ svc.verified_availability_pct!==null?`${svc.verified_availability_pct}%`:'—' }}</td>
         <td>{{ svc.upper_bound_availability_pct!==null?`${svc.upper_bound_availability_pct}%`:'—' }}</td>
         <td>{{ formatDurationSec(svc.unavailable_union_sec) }}</td>
         <td>{{ formatDurationSec(svc.unknown_interval_sec) }} ({{ svc.unknown_policy_status }})</td>
         <td>{{ latencyDuration(svc.mtta,'p50_sec') }} / {{ latencyDuration(svc.mttr,'p50_sec') }}</td>
         <td>{{ serviceStatusLabels[svc.status] }}</td>
         <td>
          <button type="button" :disabled="locked" @click="readDetail(svc.service_id,$event)">
           查看区间解释
          </button>
         </td>
        </tr>
       </tbody>
      </table>
     </div>

     <div v-if="state.detail" class="detail-box">
      <h3 ref="detailHeading" tabindex="-1">{{ state.detail.service.service_name }} · 可读时间区间解释（{{ state.detail.period }}）</h3>
      <div class="table-wrap" tabindex="0" role="region" aria-label="SLA 数据表，可横向滚动">
       <table class="proto-table">
        <thead>
         <tr>
          <th>区间类型</th>
          <th>开始时间</th>
          <th>结束时间</th>
          <th>持续时长</th>
          <th>计算口径说明</th>
         </tr>
        </thead>
        <tbody>
         <tr v-for="(iv,idx) in state.detail.interval_details" :key="idx">
          <td><code>{{ iv.kind }}</code></td>
          <td>{{ displayTime(iv.start) }}</td>
          <td>{{ displayTime(iv.end) }}</td>
          <td>{{ formatDurationSec(iv.duration_sec) }}</td>
          <td>{{ iv.explanation }}</td>
         </tr>
        </tbody>
       </table>
      </div>
     </div>
    </div>

    <div class="proto-panel workspace">
     <h2>月度工单 SLA 达标判定与赔付试算（M5-19 / CONFIG_PENDING_Q26）</h2>
     <div class="eval-grid">
      <div class="eval-card">
       <h3>实时达标评估 ({{ state.report.period }})</h3>
       <p>适用工单：<b>{{ state.report.live_monthly_evaluation.applicable_count }}</b> / 总工单 {{ state.report.live_monthly_evaluation.total_ticket_count }}（豁免不适用 {{ state.report.live_monthly_evaluation.not_applicable_count }}，未决 {{ state.report.live_monthly_evaluation.unresolved_count }}）</p>
       <p>达标率：<b>{{ state.report.live_monthly_evaluation.achievement_rate_pct!==null?`${state.report.live_monthly_evaluation.achievement_rate_pct}% (${state.report.live_monthly_evaluation.met_count}/${state.report.live_monthly_evaluation.applicable_count})`:'暂无适用工单' }}</b></p>
       <p>档位赔付上限比例：<b>{{ state.report.live_monthly_evaluation.compensation_ratio===null?'未配置':`${(state.report.live_monthly_evaluation.compensation_ratio*100).toFixed(0)}%` }}</b> · 月服务费基数：{{ formatFenToYuan(state.report.live_monthly_evaluation.monthly_fee_fen) }}</p>
       <p>正式生效赔付额：<b>待确认 ({{ state.report.live_monthly_evaluation.compensation_status }})</b></p>
       <p>候选一（固定档位比例）：<b>{{ formatFenToYuan(state.report.live_monthly_evaluation.candidate_fixed_ratio_fen) }}</b></p>
       <p>按客户新规范，固定档位比例金额四舍五入到元；不采用未达标工单占比连乘。正式补偿仍需核对试运行期、费用月份档位与对账审批。</p>
      </div>

      <div class="eval-card">
       <h3>已保存月度快照</h3>
       <template v-if="state.report.monthly_result">
        <p>快照版本：<b>v{{ state.report.monthly_result.version }}</b> · 状态：<b>{{ state.report.monthly_result.frozen?'已冻结（禁止修改）':'草稿快照（可重算）' }}</b></p>
        <p>评估时间：{{ displayTime(state.report.monthly_result.evaluated_at) }}</p>
        <p v-if="state.report.monthly_result.frozen_at">冻结时间：{{ displayTime(state.report.monthly_result.frozen_at) }}</p>
        <p>快照达标：{{ state.report.monthly_result.met_count }} / {{ state.report.monthly_result.applicable_count }} · 赔付状态：<code>{{ state.report.monthly_result.compensation_status }}</code></p>
       </template>
       <p v-else>当前月份尚未保存月度 SLA 结果快照。</p>

       <form v-if="canWriteInternal&&!state.report.monthly_result?.frozen" class="freeze-form" @submit.prevent="submitMonthlyEvaluation">
        <label class="checkbox-label">
         <input type="checkbox" v-model="freezeSnapshot" :disabled="locked||periodInput!==state.report.period" />
         同时冻结本月度 SLA 快照（冻结后不可覆盖，M5-10）
        </label>
        <button type="submit" class="primary" :disabled="locked||periodInput!==state.report.period">
         {{ state.report.monthly_result?'更新月度 SLA 快照':'生成月度 SLA 快照' }}
        </button>
       </form>
      </div>
     </div>

     <div class="table-wrap" tabindex="0" role="region" aria-label="逐工单 SLA 明细，可横向滚动" v-if="state.report.live_monthly_evaluation.items.length">
      <h3>本月逐工单 SLA 达标与原因明细</h3>
      <table class="proto-table">
       <thead>
        <tr>
         <th>工单 ID</th>
         <th>优先级 / 冻结档位</th>
         <th>计入考核</th>
         <th>响应判定</th>
         <th>恢复判定</th>
         <th>综合结论</th>
         <th>判定依据</th>
        </tr>
       </thead>
       <tbody>
        <tr v-for="item in state.report.live_monthly_evaluation.items" :key="item.ticket_id">
         <td><code>{{ item.ticket_id }}</code></td>
         <td>{{ item.priority }} · {{ tierLabels[item.tier_snapshot] }}</td>
         <td>{{ item.applicable?'是':'否（豁免）' }}</td>
         <td>{{ item.response_check }}</td>
         <td>{{ item.recovery_check }}</td>
         <td><b>{{ item.outcome }}</b></td>
         <td>{{ item.reasons.join(', ') }}</td>
        </tr>
       </tbody>
      </table>
     </div>
    </div>
   </template>
  </template>
 </section>
</template>
<style scoped>
.review-export{margin:16px 0;min-width:0}.review-export button{min-height:44px;max-width:100%;white-space:normal}.review-export p{line-height:1.7;overflow-wrap:anywhere}.review-export button:focus-visible{outline:3px solid #176bb5;outline-offset:3px}
.sla-reports{display:grid;gap:16px}
.workspace{display:grid;gap:16px}
.period-bar{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap}
.period-bar label{display:grid;gap:6px;font-weight:600}
.eval-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}
.eval-card{display:grid;gap:8px;padding:16px;border:1px solid var(--border,#d0d7de);border-radius:8px;background:var(--surface-subtle,#f8fafc)}
.freeze-form{display:grid;gap:10px;margin-top:8px}
.checkbox-label{display:flex;align-items:center;gap:8px;font-weight:600}
.detail-box{display:grid;gap:10px;padding:12px;border:1px solid #cbd5e1;border-radius:8px;background:#f8fafc}
.table-wrap{max-width:100%;overflow:auto}.table-wrap:focus-visible,h3[tabindex]:focus-visible{outline:3px solid #176bb5;outline-offset:3px}
button,input,select,.back{min-height:44px}
@media(max-width:760px){
 .eval-grid{grid-template-columns:1fr}
}
</style>
