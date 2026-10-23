<script setup lang="ts">
import { useDemoExample } from '../utils/useDemoExample'
import { computed, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createOperationalWorkspace, emptyOperationalInput, operationalSampleInput, type OperationalState } from '../utils/operationalComparisonWorkspace'
import OperationalSamplePanels from '../components/OperationalSamplePanels.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const discarding=ref(false), submitButton=ref<{ $el: HTMLButtonElement } | null>(null)
let disposed=false
const draft = reactive({ sampleMode: 'unknown', samples: '', includeStaffing: false, activeTenants: '', onCallFte: '', secondLineFte: '' })
const state = shallowRef<OperationalState>({ input: emptyOperationalInput(), result: null, busy: false, error: null })
const workspace = createOperationalWorkspace(api, value => { state.value = value })
const permitted = computed(() => session.value?.user.role === 'padmin' && can('operations:compare_explicit') && !switchingTenant.value)
const nullable = (value: string) => value === '' ? null : value
function input() { return { elapsed_millis: operationalSampleInput(draft.sampleMode, draft.samples), staffing: draft.includeStaffing ? { active_tenants: nullable(draft.activeTenants), on_call_fte: nullable(draft.onCallFte), second_line_fte: nullable(draft.secondLineFte) } : null } }
function clearDraft() { Object.assign(draft, { sampleMode: 'unknown', samples: '', includeStaffing: false, activeTenants: '', onCallFte: '', secondLineFte: '' }); workspace.change(input()) }
const scopeKey=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,contextGeneration.value,permitted.value]))
watch(scopeKey, () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canCompare: true } : null); clearDraft() }, { immediate: true, flush: 'sync' })
watch(draft, () => workspace.change(input()), { deep: true, flush: 'sync' })
async function calculate() { if (!permitted.value || state.value.busy) return; workspace.change(input()); await workspace.calculate() }
const hasDraft=computed(()=>draft.sampleMode!=='unknown'||draft.samples!==''||draft.includeStaffing||[draft.activeTenants,draft.onCallFte,draft.secondLineFte].some(v=>v!==''))
async function mayDiscard(){
 if(demoExample.pristine.value)return true
 if(discarding.value)return false
 if(!hasDraft.value)return true
 const owner=scopeKey.value,before=JSON.stringify(draft);discarding.value=true
 try{await ElMessageBox.confirm('样本和人力参数只在本页内存中，测算不会保存。继续会丢弃当前输入，确认继续？','保留经营测算输入',{confirmButtonText:'丢弃并继续',cancelButtonText:'保留输入',type:'warning'});return !disposed&&owner===scopeKey.value&&before===JSON.stringify(draft)}catch{return false}finally{if(!disposed)discarding.value=false}
}
async function focusSubmit(){await nextTick();submitButton.value?.$el?.focus()}
async function resetDraft(){const owner=scopeKey.value;if(await mayDiscard()&&!disposed&&permitted.value&&owner===scopeKey.value){clearDraft();await focusSubmit()}}
async function cancelCalculation(){workspace.cancel();await focusSubmit()}
onBeforeRouteLeave(mayDiscard);const unregister=contextTransitionGuards.register(mayDiscard)
function beforeUnload(event:BeforeUnloadEvent){if(demoExample.pristine.value)return;if(hasDraft.value){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onBeforeUnmount(()=>{disposed=true;unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
const reasons: Record<string, string> = { ALL_EXPLICIT: '明确输入的算术结果', INPUT_UNAVAILABLE: '未提供输入', EMPTY_SAMPLE: '明确没有样本', UNKNOWN_SAMPLE: '存在未知样本，未丢弃或按零计算', MISSING_INPUT: '输入未完整', ZERO_FTE: 'FTE为零，除法未定义' }
const missing: Record<string, string> = { input: '尚未提供人力组', active_tenants: '明确的活跃租户数', on_call_fte: '月度值守FTE', second_line_fte: '月度二线FTE' }
const demoExample = useDemoExample<{elapsed_millis:string[];staffing:{active_tenants:string;on_call_fte:string;second_line_fte:string}}>('operational-comparison', permitted, () => JSON.stringify(draft), async value => { Object.assign(draft, {sampleMode:'provided',samples:value.elapsed_millis.join('\n'),includeStaffing:true,activeTenants:value.staffing.active_tenants,onCallFte:value.staffing.on_call_fte,secondLineFte:value.staffing.second_line_fte}); await calculate() })
</script>
<template>
 <section class="operational-page">
  <h1 class="page-title-sr">经营样本与人力测算</h1>
 <p v-if="demoExample.loaded.value" class="hint" role="note">[DEMO] 已加载独立测试样例；这些是假设输入，不代表真实业务或已生效配置。</p>
  <el-empty v-if="!permitted" description="当前身份无显式经营对照权限" />
  <template v-else>
   
   <ApiErrorNotice :error="state.error" :retrying="state.busy" retry-label="以当前输入重新测算" @retry="calculate" /><p role="status" class="ops-status" :class="{'sr-only':!state.busy&&!state.error}">{{state.busy?'正在测算…':state.error?'测算未完成，输入已保留；请核对后重试。':state.result?'测算完成，未保存。':''}}</p>
   <div class="operations-layout">
    <div class="operations-main">
     <section class="results" aria-live="polite"><div class="panel-heading content-toolbar"><h2>服务端测算结果对照</h2><span>本次输入 / 算术结果</span><div class="content-actions"><el-button v-if="permitted" :disabled="discarding" @click="resetDraft">重置条件</el-button></div></div><div class="operations-table-wrap" tabindex="0" role="region" aria-label="显式经营测算结果，可横向滚动"><table class="operations-table"><caption class="sr-only">本次显式输入的算术对照，非正式经营指标</caption><thead><tr><th scope="col">对照指标</th><th scope="col">结果</th><th scope="col">输入情况</th><th scope="col">核对结论</th></tr></thead><tbody><tr><td><strong>样本中位数</strong><small>耗时 / 毫秒</small></td><td class="metric-value">{{ state.result?.sample_median.median_millis ?? '—' }}</td><td>样本数：{{ state.result?.sample_median.sample_count ?? '待测算' }}<small>未知项：{{ state.result?.sample_median.unknown_count ?? '待测算' }}</small></td><td>{{ state.result ? reasons[state.result.sample_median.reason] : '等待录入样本并执行对照' }}</td></tr><tr><td><strong>租户数 / FTE</strong><small>月度人力折算</small></td><td class="metric-value">{{ state.result?.staffing.tenants_per_fte ?? '—' }}</td><td>折算 FTE 合计：{{ state.result?.staffing.explicit_total_fte ?? '待测算' }}</td><td>{{ state.result ? reasons[state.result.staffing.reason] : '等待录入参数并执行对照' }}<small v-if="state.result?.staffing.missing_inputs.length">待明确：{{ state.result.staffing.missing_inputs.map(k => missing[k]).join('、') }}</small></td></tr></tbody></table></div></section>
     <OperationalSamplePanels :result="state.result" :busy="state.busy" />
    </div>
    <aside class="operations-side"><el-form label-position="top" @submit.prevent="calculate">
     <section class="input-section"><div class="panel-heading"><h2>耗时样本录入</h2></div><el-form-item label="样本来源状态"><el-select v-model="draft.sampleMode" aria-label="样本来源状态"><el-option value="unknown" label="暂未提供样本" /><el-option value="empty" label="无样本记录（空集合）" /><el-option value="provided" label="手工录入样本逐行对照" /></el-select></el-form-item><el-form-item v-if="draft.sampleMode === 'provided'" label="毫秒样本，每行一个非负整数；未知项写?（最多10000行）"><el-input v-model="draft.samples" type="textarea" :rows="6" aria-label="毫秒样本，每行整数或问号" placeholder="每行填写一个非负整数；空行不会忽略，未知项请写 ?" /></el-form-item><p class="input-help">保留重复值与 0 值；含未知项（?）时不估算中位数。</p></section>
     <section class="input-section"><div class="panel-heading"><h2>月度人力参数录入</h2></div><el-checkbox v-model="draft.includeStaffing">录入月度人力与活跃租户数</el-checkbox><div v-if="draft.includeStaffing" class="staffing-fields"><el-form-item label="活跃租户数"><el-input v-model="draft.activeTenants" aria-label="明确的活跃租户数" inputmode="numeric" placeholder="未定请留空" /></el-form-item><el-form-item label="月度值守人力 FTE"><el-input v-model="draft.onCallFte" aria-label="月度值守人力FTE" inputmode="decimal" placeholder="填写月度折算 FTE，未定留空" /></el-form-item><el-form-item label="月度二线人力 FTE"><el-input v-model="draft.secondLineFte" aria-label="月度二线人力FTE" inputmode="decimal" placeholder="填写月度折算 FTE，未定留空" /></el-form-item></div><p class="input-help">计算公式：活跃租户数 ÷（值守 FTE + 二线 FTE）。</p><div class="actions"><el-button ref="submitButton" type="primary" native-type="submit" :loading="state.busy" :disabled="state.busy">开始测算</el-button><el-button v-if="state.busy" @click="cancelCalculation">取消本次测算</el-button></div></section>
    </el-form></aside>
   </div>
   
  </template>
 </section>
</template>
<style scoped>

.operational-page{min-width:0}.operations-layout{display:grid;grid-template-columns:minmax(0,2.4fr) minmax(300px,1fr);gap:14px;align-items:start;margin-top:14px}.operations-main,.operations-side{min-width:0}.input-section,.results,.empty-chart{background:#fff;border:1px solid #e2e9f2;border-radius:6px;padding:18px;margin-bottom:14px;overflow-wrap:anywhere}.operations-table-wrap{overflow:auto;margin:0 -18px}.operations-table{width:100%;min-width:620px;border-collapse:collapse}.operations-table th{padding:13px 16px;background:#f5f8fc;text-align:left;font-weight:500;font-size:12px;color:#65778f}.operations-table td{padding:18px 16px;border-bottom:1px solid #edf1f6;font-size:12px;color:#53667e}.operations-table td strong{font-size:13px;font-weight:500;color:#365c8a}.operations-table td small{display:block;margin-top:5px;color:#526176;line-height:1.6}.operations-table .metric-value{font-size:23px;color:#3469a7;font-variant-numeric:tabular-nums}.table-note,.input-help,.boundary-details{font-size:12px;color:#526176;line-height:1.7}.table-note{margin:14px 0 0}.staffing-fields{margin-top:15px}.staffing-fields .el-form-item{margin-bottom:14px}.input-section .el-select{width:100%}.operations-charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.empty-chart-body{min-height:185px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;color:#526176;font-size:12px}.empty-chart-body>span{font-size:43px;color:#c1d2e6;line-height:1.4}.empty-chart-body strong{font-size:13px;font-weight:500;color:#627b9b;margin:8px 0}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}@media(max-width:1000px){.operations-layout{grid-template-columns:1fr}.operations-side>form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}}@media(max-width:650px){.operations-charts,.operations-side>form{grid-template-columns:1fr}.input-section,.results,.empty-chart{padding:14px}.operations-table-wrap{margin:0 -14px}.actions .el-button{min-height:44px}}

 .operations-table caption{text-align:left;padding:12px 16px;font-size:13px;font-weight:600;color:#26384f}.operational-page :deep(.el-button){min-height:44px}.operational-page :deep(:focus-visible){outline:3px solid #176bb5;outline-offset:3px}
.ops-status{margin:0 0 10px;font-size:13px;color:#526176}
</style>
