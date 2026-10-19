<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createOperationalWorkspace, emptyOperationalInput, operationalSampleInput, type OperationalState } from '../utils/operationalComparisonWorkspace'
import OperationalSamplePanels from '../components/OperationalSamplePanels.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const draft = reactive({ sampleMode: 'unknown', samples: '', includeStaffing: false, activeTenants: '', onCallFte: '', secondLineFte: '' })
const state = shallowRef<OperationalState>({ input: emptyOperationalInput(), result: null, busy: false, error: null })
const workspace = createOperationalWorkspace(api, value => { state.value = value })
const permitted = computed(() => session.value?.user.role === 'padmin' && can('operations:compare_explicit') && !switchingTenant.value)
const nullable = (value: string) => value === '' ? null : value
function input() { return { elapsed_millis: operationalSampleInput(draft.sampleMode, draft.samples), staffing: draft.includeStaffing ? { active_tenants: nullable(draft.activeTenants), on_call_fte: nullable(draft.onCallFte), second_line_fte: nullable(draft.secondLineFte) } : null } }
function clearDraft() { Object.assign(draft, { sampleMode: 'unknown', samples: '', includeStaffing: false, activeTenants: '', onCallFte: '', secondLineFte: '' }); workspace.change(input()) }
watch(() => [session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, contextGeneration.value, permitted.value], () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canCompare: true } : null); clearDraft() }, { immediate: true, flush: 'sync' })
watch(draft, () => workspace.change(input()), { deep: true, flush: 'sync' })
async function calculate() { if (!permitted.value || state.value.busy) return; workspace.change(input()); await workspace.calculate() }
onBeforeUnmount(() => workspace.dispose())
const reasons: Record<string, string> = { ALL_EXPLICIT: '明确输入的算术结果', INPUT_UNAVAILABLE: '未提供输入', EMPTY_SAMPLE: '明确没有样本', UNKNOWN_SAMPLE: '存在未知样本，未丢弃或按零计算', MISSING_INPUT: '输入未完整', ZERO_FTE: 'FTE为零，除法未定义' }
const missing: Record<string, string> = { input: '尚未提供人力组', active_tenants: '明确的活跃租户数', on_call_fte: '月度值守FTE', second_line_fte: '月度二线FTE' }
</script>
<template>
 <section class="operational-page">
  <div class="page-heading"><div><h1>经营样本与人力测算</h1><p>基于录入样本与月度人力配置执行算术对照测算 · 正式经营统计看板待数据源接入后启用</p></div><el-button @click="clearDraft">重置条件</el-button></div>
  <el-empty v-if="!permitted" description="当前身份无显式经营对照权限" />
  <template v-else>
   <div class="feature-summary"><article><span>录入耗时样本</span><strong>{{ state.result?.sample_median.sample_count ?? '—' }}</strong><small>仅统计本次录入样本</small></article><article><span>样本中位数</span><strong>{{ state.result?.sample_median.median_millis ?? '—' }}</strong><small>毫秒 · 样本算术中位数</small></article><article><span>月度折算 FTE</span><strong>{{ state.result?.staffing.explicit_total_fte ?? '—' }}</strong><small>值守 + 二线折算</small></article><article><span>租户数 / FTE</span><strong>{{ state.result?.staffing.tenants_per_fte ?? '—' }}</strong><small>算术对照，供资源评估参考</small></article></div>
   <ApiErrorNotice :error="state.error" />
   <div class="operations-layout">
    <div class="operations-main">
     <section class="results" aria-live="polite"><div class="panel-heading"><h2>服务端测算结果对照</h2><span>本次输入 / 算术结果</span></div><div class="operations-table-wrap"><table class="operations-table"><thead><tr><th>对照指标</th><th>结果</th><th>输入情况</th><th>核对结论</th></tr></thead><tbody><tr><td><strong>样本中位数</strong><small>耗时 / 毫秒</small></td><td class="metric-value">{{ state.result?.sample_median.median_millis ?? '—' }}</td><td>样本数：{{ state.result?.sample_median.sample_count ?? '待测算' }}<small>未知项：{{ state.result?.sample_median.unknown_count ?? '待测算' }}</small></td><td>{{ state.result ? reasons[state.result.sample_median.reason] : '等待录入样本并执行对照' }}</td></tr><tr><td><strong>租户数 / FTE</strong><small>月度人力折算</small></td><td class="metric-value">{{ state.result?.staffing.tenants_per_fte ?? '—' }}</td><td>折算 FTE 合计：{{ state.result?.staffing.explicit_total_fte ?? '待测算' }}</td><td>{{ state.result ? reasons[state.result.staffing.reason] : '等待录入参数并执行对照' }}<small v-if="state.result?.staffing.missing_inputs.length">待明确：{{ state.result.staffing.missing_inputs.map(k => missing[k]).join('、') }}</small></td></tr></tbody></table></div><p class="table-note">本表仅展示当前录入参数的算术测算结果；正式经营考核指标待数据源接入与口径确认后开放。</p></section>
     <OperationalSamplePanels :result="state.result" :busy="state.busy" />
    </div>
    <aside class="operations-side"><el-form label-position="top" @submit.prevent="calculate">
     <section class="input-section"><div class="panel-heading"><h2>耗时样本录入</h2><span>输入对照</span></div><el-form-item label="样本来源状态"><el-select v-model="draft.sampleMode" aria-label="样本来源状态"><el-option value="unknown" label="暂未提供样本" /><el-option value="empty" label="无样本记录（空集合）" /><el-option value="provided" label="手工录入样本逐行对照" /></el-select></el-form-item><el-form-item v-if="draft.sampleMode === 'provided'" label="毫秒样本，每行一个非负整数；未知项写?（最多10000行）"><el-input v-model="draft.samples" type="textarea" :rows="6" :maxlength="430000" aria-label="毫秒样本，每行整数或问号" placeholder="每行填写一个非负整数；空行不会忽略，未知项请写 ?" /></el-form-item><p class="input-help">保留重复值与 0 值，包含未知项（?）时不按子集估算中位数。</p></section>
     <section class="input-section"><div class="panel-heading"><h2>月度人力参数录入</h2></div><el-checkbox v-model="draft.includeStaffing">录入月度人力与活跃租户数</el-checkbox><div v-if="draft.includeStaffing" class="staffing-fields"><el-form-item label="活跃租户数"><el-input v-model="draft.activeTenants" aria-label="明确的活跃租户数" inputmode="numeric" placeholder="未定请留空" /></el-form-item><el-form-item label="月度值守人力 FTE"><el-input v-model="draft.onCallFte" aria-label="月度值守人力FTE" inputmode="decimal" placeholder="填写月度折算 FTE，未定留空" /></el-form-item><el-form-item label="月度二线人力 FTE"><el-input v-model="draft.secondLineFte" aria-label="月度二线人力FTE" inputmode="decimal" placeholder="填写月度折算 FTE，未定留空" /></el-form-item></div><p class="input-help">计算公式：活跃租户数 ÷（值守 FTE + 二线 FTE）。</p><div class="actions"><el-button type="primary" native-type="submit" :loading="state.busy" :disabled="state.busy">开始测算</el-button></div></section>
    </el-form></aside>
   </div>
   <details class="boundary-details"><summary>数据来源、统计口径与计算说明</summary><p>这是输入对照，不是正式经营看板。本工具不读取生产工单、排班考勤或租户账单；人力折算系数（Q23）、耗时统计口径（Q27）与考核范围（Q30）以客户最终确认的业务规则为准。</p><p>修改输入参数后将自动清空旧计算结果；页面数据仅在当前会话内存中保留，切换租户或离开页面后自动清空。</p><p>样本中位数仅针对录入的毫秒数值进行精确算术计算，不代表实时监控 MTTR 或工单解决时长。</p><p>全部数值采用高精度十进制字符串传输，人力比值仅在最终除法阶段按 DECIMAL128 规则舍入。</p></details>
  </template>
 </section>
</template>
<style scoped>

.operational-page{min-width:0}.operations-layout{display:grid;grid-template-columns:minmax(0,2.4fr) minmax(300px,1fr);gap:14px;align-items:start;margin-top:14px}.operations-main,.operations-side{min-width:0}.input-section,.results,.empty-chart{background:#fff;border:1px solid #e2e9f2;border-radius:6px;padding:18px;margin-bottom:14px;overflow-wrap:anywhere}.operations-table-wrap{overflow:auto;margin:0 -18px}.operations-table{width:100%;min-width:620px;border-collapse:collapse}.operations-table th{padding:13px 16px;background:#f5f8fc;text-align:left;font-weight:500;font-size:12px;color:#65778f}.operations-table td{padding:18px 16px;border-bottom:1px solid #edf1f6;font-size:12px;color:#53667e}.operations-table td strong{font-size:13px;font-weight:500;color:#365c8a}.operations-table td small{display:block;margin-top:5px;color:#8a97a7;line-height:1.6}.operations-table .metric-value{font-size:23px;color:#3469a7;font-variant-numeric:tabular-nums}.table-note,.input-help,.boundary-details{font-size:12px;color:#7b899c;line-height:1.7}.table-note{margin:14px 0 0}.staffing-fields{margin-top:15px}.staffing-fields .el-form-item{margin-bottom:14px}.input-section .el-select{width:100%}.operations-charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.empty-chart-body{min-height:185px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;color:#8b9bb0;font-size:12px}.empty-chart-body>span{font-size:43px;color:#c1d2e6;line-height:1.4}.empty-chart-body strong{font-size:13px;font-weight:500;color:#627b9b;margin:8px 0}.actions{display:flex;gap:10px;margin-top:18px}@media(max-width:1000px){.operations-layout{grid-template-columns:1fr}.operations-side>form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}}@media(max-width:650px){.operations-charts,.operations-side>form{grid-template-columns:1fr}.input-section,.results,.empty-chart{padding:14px}.operations-table-wrap{margin:0 -14px}.actions .el-button{min-height:44px}}

</style>
