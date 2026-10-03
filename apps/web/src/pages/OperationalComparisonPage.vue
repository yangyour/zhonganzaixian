<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createOperationalWorkspace, emptyOperationalInput, operationalSampleInput, type OperationalState } from '../utils/operationalComparisonWorkspace'
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
  <div class="page-heading"><div><span class="eyebrow">EXPLICIT COMPARISON</span><h1>经营样本与人力对照</h1><p>平台管理员 · 显式输入 · 只读试算</p></div></div>
  <el-alert type="warning" :closable="false" title="这是输入对照，不是正式经营看板">不查询项目工程师、工单、活跃租户或出勤数据。Q23人力折算、Q27耗时与Q30统计范围未核验；不据此判断经营达标、计费或利润。</el-alert>
  <p>修改输入立即清除旧结果；不自动请求、不保存，切换租户或离开清空。空白人力字段保持未知，明确0须填写0。</p>
  <el-empty v-if="!permitted" description="当前身份无显式经营对照权限" />
  <template v-else>
   <ApiErrorNotice :error="state.error" />
   <el-form label-position="top" @submit.prevent="calculate">
    <section class="input-section"><h2>明确耗时样本</h2>
     <el-form-item label="样本来源状态"><el-select v-model="draft.sampleMode" aria-label="样本来源状态"><el-option value="unknown" label="未提供样本（未知）" /><el-option value="empty" label="明确没有样本（空集合）" /><el-option value="provided" label="我提供样本逐行对照" /></el-select></el-form-item>
     <el-form-item v-if="draft.sampleMode === 'provided'" label="毫秒样本，每行一个非负整数；未知项写?（最多10000行）"><el-input v-model="draft.samples" type="textarea" :rows="7" :maxlength="430000" aria-label="毫秒样本，每行整数或问号" placeholder="不预填示例；空行不会忽略，未知项写?" /></el-form-item>
     <p>仅核对中位数算术，不把这些耗时标为监控MTTR或工单解决时长。样本排序不影响原输入，重复值与明确0保留。</p>
    </section>
    <section class="input-section"><h2>明确月度人力输入</h2><el-checkbox v-model="draft.includeStaffing">明确提供人力与租户数</el-checkbox>
     <div v-if="draft.includeStaffing" class="staffing-fields">
      <el-form-item label="明确的活跃租户数"><el-input v-model="draft.activeTenants" aria-label="明确的活跃租户数" inputmode="numeric" placeholder="未知留空" /></el-form-item>
      <el-form-item label="月度值守人力FTE"><el-input v-model="draft.onCallFte" aria-label="月度值守人力FTE" inputmode="decimal" placeholder="明确月度折算值，未知留空" /></el-form-item>
      <el-form-item label="月度二线人力FTE"><el-input v-model="draft.secondLineFte" aria-label="月度二线人力FTE" inputmode="decimal" placeholder="明确月度折算值，未知留空" /></el-form-item>
     </div><p>租户数 ÷（值守FTE + 二线FTE）。不把人数自动当FTE、不猜出勤或缺勤规则；零分母不输出0或无穷大。</p>
    </section>
    <div class="actions"><el-button type="primary" native-type="submit" :loading="state.busy" :disabled="state.busy">只读对照</el-button><el-button @click="clearDraft">清空输入与结果</el-button></div>
   </el-form>
   <section v-if="state.result" class="results" aria-live="polite"><h2>服务端显式对照</h2>
    <article><h3>样本中位数</h3><p>{{ reasons[state.result.sample_median.reason] }}</p><strong>{{ state.result.sample_median.median_millis ?? '—' }}</strong> 毫秒<p>样本数：{{ state.result.sample_median.sample_count ?? '未知' }} · 未知项：{{ state.result.sample_median.unknown_count ?? '未知' }}</p></article>
    <article><h3>租户数 / FTE</h3><p>{{ reasons[state.result.staffing.reason] }}</p><strong>{{ state.result.staffing.tenants_per_fte ?? '—' }}</strong><p>明确FTE合计：{{ state.result.staffing.explicit_total_fte ?? '未知' }}</p><p v-if="state.result.staffing.missing_inputs.length">待明确：{{ state.result.staffing.missing_inputs.map(k => missing[k]).join('、') }}</p></article>
    <p>十进制字符串保留精度；中位数精确计算，人力比值只在最终除法按DECIMAL128舍入。正式指标与达标判断仍未知；没有导出、保存或生效操作。</p>
   </section>
  </template>
 </section>
</template>
<style scoped>
.operational-page{max-width:1100px;line-height:1.65}.input-section,.results{background:white;border:1px solid #dce4ee;border-radius:12px;padding:20px;margin:20px 0;overflow-wrap:anywhere}.staffing-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:20px}.staffing-fields .el-form-item{min-width:0}.actions{display:flex;flex-wrap:wrap;gap:12px}.results article{padding:16px 0;border-bottom:1px solid #dce4ee}.results strong{font-family:monospace;font-size:22px;overflow-wrap:anywhere}.input-section .el-select{width:100%;max-width:480px}@media(max-width:700px){.staffing-fields{grid-template-columns:1fr}.input-section,.results{padding:14px}.actions .el-button{min-height:44px}.operational-page :deep(.el-input__wrapper),.operational-page :deep(.el-select__wrapper){min-height:44px}}
</style>
