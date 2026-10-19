<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {
 createSlaReportWorkspace,emptySlaReportState,formatDurationSec,formatFenToYuan,
 serviceStatusLabels,type SlaReportState
} from '../utils/onCallBoard'
import {tierLabels} from '../utils/serviceTiers'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'

const state=shallowRef<SlaReportState>(emptySlaReportState())
const workspace=createSlaReportWorkspace(api,s=>{state.value=s})
const internal=computed(()=>['padmin','msp'].includes(session.value?.user.role??''))
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&internal.value&&can('sla_scenarios:read')&&!switchingTenant.value))
const canWriteInternal=computed(()=>Boolean(permitted.value&&can('ticket_notes:write_internal')))
const canWriteConfig=computed(()=>Boolean(permitted.value&&session.value?.user.role==='padmin'&&can('configurations:write')))

const periodInput=ref('2026-10')
const freezeSnapshot=ref(false)
const blocked=ref(false)

const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,permitted.value,canWriteInternal.value,canWriteConfig.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved())

watch(context,()=>{
 const s=session.value
 workspace.reset(permitted.value&&s?{
  tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,
  canWriteInternal:canWriteInternal.value,canWriteConfig:canWriteConfig.value
 }:null)
 if(permitted.value)void workspace.load(periodInput.value)
},{immediate:true,flush:'sync'})

async function submitMonthlyEvaluation(){
 const existing=state.value.report?.monthly_result
 const ok=await workspace.submitMonthlyResult({
  report_month:periodInput.value,
  expected_version:existing?.version??0,
  freeze:freezeSnapshot.value
 })
 if(ok)freezeSnapshot.value=false
}

function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}return true}
onBeforeRouteLeave(mayLeave)
const unregister=contextTransitionGuards.register(mayLeave)
onUnmounted(()=>{unregister();workspace.dispose()})
defineExpose({state,workspace,mayLeave})
</script>
<template>
 <section class="sla-reports">
  <div class="page-heading proto-page-head">
   <div>
    <h1>SLA 月度报表与赔付试算 <span class="proto-badge">{{ session?.tenant.name }}</span></h1>
    <p>不可用区间去重合并、未知区间单独隔离（Q08）、MTTA/MTTR(P50/P95) 统计及月度 SLA 达标与赔付快照（M5-9 / M5-10 / M5-19）。</p>
   </div>
   <RouterLink to="/oncall-board" class="back">值班看板</RouterLink>
  </div>
  <p class="panel" v-if="!permitted">当前身份无权访问内部 SLA 月报与赔付台账，或正在切换租户。</p>
  <template v-else>
   <p class="proto-scope-note">
    本报表严格执行重叠故障区间并集去重与批准豁免窗口剔除；监控数据缺失或 P3 次日未决区间单独隔离为未知区间，绝不默认折算为 100% 正常可用（M5-9 / Q08）。月度赔付比例按档位固定（基础 10% / 标准 20% / 进阶 30%），正式赔付金额在客户确认 Q26 口径前保持 CONFIG_PENDING_Q26（null），并同时展示固定比例与按未达标占比两套候选试算值（M5-19）。已冻结月度快照禁止覆盖（M5-10）。
   </p>

   <div class="proto-toolbar">
    <form class="period-bar" @submit.prevent="workspace.load(periodInput)">
     <label>统计月份
      <input type="month" v-model="periodInput" :disabled="locked" required />
     </label>
     <button type="submit" :disabled="locked">查询月报</button>
    </form>
   </div>

   <ApiErrorNotice :error="state.error" />
   <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">月度 SLA 快照提交结果尚未确认，请点击重试。</p>
   <button v-if="state.attempt&&!state.busy" type="button" @click="workspace.retry()">重试提交月度快照</button>
   <p v-if="state.busy" role="status" aria-live="polite">{{ state.busy==='write'?'正在保存/冻结月度 SLA 快照…':'正在计算 SLA 月报…' }}</p>

   <template v-if="state.report">
    <div class="proto-kpis">
     <div>
      <span>已核验可用性均值</span>
      <b>{{ state.report.kpis.mean_verified_availability_pct!==null?`${state.report.kpis.mean_verified_availability_pct}%`:'待配置' }}</b>
      <small>目标 {{ state.report.target_availability_pct }}% ({{ tierLabels[state.report.current_tier] }})</small>
     </div>
     <div>
      <span>不可用并集总时长</span>
      <b>{{ formatDurationSec(state.report.kpis.unavailable_union_total_sec) }}</b>
      <small>重叠故障已去重合并</small>
     </div>
     <div>
      <span>响应耗时 MTTA</span>
      <b>P50 {{ formatDurationSec(state.report.kpis.mtta.p50_sec) }}</b>
      <small>P95 {{ formatDurationSec(state.report.kpis.mtta.p95_sec) }} · 样本 {{ state.report.kpis.mtta.sample_count }}</small>
     </div>
     <div>
      <span>恢复耗时 MTTR</span>
      <b>P50 {{ formatDurationSec(state.report.kpis.mttr.p50_sec) }}</b>
      <small>P95 {{ formatDurationSec(state.report.kpis.mttr.p95_sec) }} · 未恢复 {{ state.report.kpis.unrecovered_total_count }}</small>
     </div>
     <div>
      <span>未知区间单独隔离</span>
      <b>{{ formatDurationSec(state.report.kpis.unknown_interval_total_sec) }}</b>
      <small>不计入已核验可用（Q08）</small>
     </div>
    </div>

    <div class="proto-panel workspace">
     <h2>服务节点可用性与区间明细（M5-9 / M5-10）</h2>
     <div class="table-wrap">
      <table class="proto-table">
       <thead>
        <tr>
         <th>服务名称</th>
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
         <td>{{ svc.target_pct }}%</td>
         <td>{{ svc.verified_availability_pct!==null?`${svc.verified_availability_pct}%`:'—' }}</td>
         <td>{{ svc.upper_bound_availability_pct!==null?`${svc.upper_bound_availability_pct}%`:'—' }}</td>
         <td>{{ formatDurationSec(svc.unavailable_union_sec) }}</td>
         <td>{{ formatDurationSec(svc.unknown_interval_sec) }} ({{ svc.unknown_policy_status }})</td>
         <td>{{ formatDurationSec(svc.mtta.p50_sec) }} / {{ formatDurationSec(svc.mttr.p50_sec) }}</td>
         <td>{{ serviceStatusLabels[svc.status] }}</td>
         <td>
          <button type="button" :disabled="locked" @click="workspace.loadServiceDetail(state.report.period,svc.service_id)">
           查看区间解释
          </button>
         </td>
        </tr>
       </tbody>
      </table>
     </div>

     <div v-if="state.detail" class="detail-box">
      <h3>{{ state.detail.service.service_name }} · 可读时间区间解释（{{ state.detail.period }}）</h3>
      <div class="table-wrap">
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
       <p>档位赔付上限比例：<b>{{ (state.report.live_monthly_evaluation.compensation_ratio*100).toFixed(0) }}%</b> · 月服务费基数：{{ formatFenToYuan(state.report.live_monthly_evaluation.monthly_fee_fen) }}</p>
       <p>正式生效赔付额：<b>待确认 ({{ state.report.live_monthly_evaluation.compensation_status }})</b></p>
       <p>候选一（固定档位比例）：<b>{{ formatFenToYuan(state.report.live_monthly_evaluation.candidate_fixed_ratio_fen) }}</b></p>
       <p>候选二（按未达标工单占比折算）：<b>{{ formatFenToYuan(state.report.live_monthly_evaluation.candidate_shortfall_weighted_fen) }}</b></p>
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
         <input type="checkbox" v-model="freezeSnapshot" :disabled="locked" />
         同时冻结本月度 SLA 快照（冻结后不可覆盖，M5-10）
        </label>
        <button type="submit" class="primary" :disabled="locked">
         {{ state.report.monthly_result?'更新月度 SLA 快照':'生成月度 SLA 快照' }}
        </button>
       </form>
      </div>
     </div>

     <div class="table-wrap" v-if="state.report.live_monthly_evaluation.items.length">
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
.sla-reports{display:grid;gap:16px}
.workspace{display:grid;gap:16px}
.period-bar{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap}
.period-bar label{display:grid;gap:6px;font-weight:600}
.eval-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}
.eval-card{display:grid;gap:8px;padding:16px;border:1px solid var(--border,#d0d7de);border-radius:8px;background:var(--surface-subtle,#f8fafc)}
.freeze-form{display:grid;gap:10px;margin-top:8px}
.checkbox-label{display:flex;align-items:center;gap:8px;font-weight:600}
.detail-box{display:grid;gap:10px;padding:12px;border:1px solid #cbd5e1;border-radius:8px;background:#f8fafc}
.table-wrap{max-width:100%;overflow:auto}
button,input,select,.back{min-height:44px}
@media(max-width:760px){
 .eval-grid{grid-template-columns:1fr}
}
</style>
