<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createThresholdWorkspace, emptyThresholdState, type ThresholdState, type ThresholdComparison } from '../utils/thresholdDiagnosticWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state = shallowRef<ThresholdState>(emptyThresholdState()), discarding = ref(false)
const workspace = createThresholdWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && session.value.user.role === 'padmin' && can('configurations:read') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.user.role, permitted.value]))
let disposed = false
watch(contextKey, () => { discarding.value = false; const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true } : null) }, { immediate: true, flush: 'sync' })
function change(text: string) { if (permitted.value && !discarding.value) workspace.change(text) }
async function analyze() { if (!permitted.value || discarding.value || !state.value.text) return false; return workspace.analyze() }
async function discard() {
 if (discarding.value) return false
 if (!state.value.text) return true
 const turn = contextKey.value; discarding.value = true
 try { await ElMessageBox.confirm('输入与诊断只保存在当前页面内存，没有保存规则或发布。离开会清除输入和结果，确认继续？', '保留阈值候选输入', { confirmButtonText: '清除并继续', cancelButtonText: '保留输入', type: 'warning' }); return !disposed && turn === contextKey.value } catch { return false } finally { if (!disposed && turn === contextKey.value) discarding.value = false }
}
async function clearInput() { const turn = contextKey.value; if (!permitted.value || !await discard() || turn !== contextKey.value) return false; workspace.change(''); return true }
onBeforeRouteLeave(discard)
const unregister = contextTransitionGuards.register(discard)
function beforeUnload(event: BeforeUnloadEvent) { if (state.value.text) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed = true; unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
const example = JSON.stringify({ direction: null, warning: { comparison: null, threshold: null, unit: null }, critical: { comparison: null, threshold: null, unit: null }, recovery: { comparison: null, threshold: null, unit: null }, trigger_hold_seconds: null, recovery_hold_seconds: null }, null, 2)
const issueLabels: Record<string, string> = { DIRECTION_MISSING: '高值/低值异常方向未确认', WARNING_CONDITION_INCOMPLETE: '警告条件未完整提供', CRITICAL_CONDITION_INCOMPLETE: '严重条件未完整提供', RECOVERY_CONDITION_INCOMPLETE: '恢复条件未完整提供', UNIT_MISMATCH: '单位标签不一致，未进行换算', WARNING_DIRECTION_CONFLICT: '警告比较符与所选方向冲突', CRITICAL_DIRECTION_CONFLICT: '严重比较符与所选方向冲突', RECOVERY_DIRECTION_CONFLICT: '恢复比较符方向与异常方向冲突', CRITICAL_NOT_STRICTER_THAN_WARNING: '严重条件未比警告更严格', RECOVERY_OVERLAPS_WARNING: '恢复条件与警告条件有交集', RECOVERY_OVERLAPS_CRITICAL: '恢复条件与严重条件有交集', TRIGGER_DURATION_MISSING: '触发持续时长未确认', RECOVERY_DURATION_MISSING: '恢复持续时长未确认', NEGATIVE_TRIGGER_DURATION: '触发持续时长为负数', NEGATIVE_RECOVERY_DURATION: '恢复持续时长为负数' }
const statusLabel = computed(() => state.value.result?.status === 'CONSISTENT' ? '数学一致，仍不可发布' : state.value.result?.status === 'CONFLICT' ? '候选冲突，未自动修正' : '资料未定，结果不完整')
const conditions = computed(() => { const i = state.value.result?.inputs; return i ? [{ name: '警告', value: i.warning }, { name: '严重', value: i.critical }, { name: '恢复', value: i.recovery }] : [] })
const operator = (value: ThresholdComparison | null | undefined) => value ? ({ GT: '>', GE: '≥', LT: '<', LE: '≤' }[value]) : '未确认'
const booleanText = (value: boolean | null) => value === null ? '未知 / 无法比较' : value ? '是' : '否'
defineExpose({ change, analyze, clearInput, state })
</script>
<template>
 <section class="threshold-page">
  <div class="page-heading"><div><h1>阈值候选诊断</h1><p>只读数学检查 · 不保存规则 · 不发布监控</p></div><router-link to="/configurations">返回配置提案</router-link></div>
<details class="page-guidance"><summary>计算口径与说明</summary>  <el-alert type="warning" :closable="false" title="阈值冲突必须保留，不能自动换数或反转比较符">不会从合同候选中选择默认阈值，也不会把“恢复&gt;15”改成“恢复&lt;15”。CONSISTENT只表示数学一致；客户Q06、指标物理范围、真实采样/持续窗口与生产发布均未核验。</el-alert>
</details>
    <el-empty v-if="!permitted" description="仅当前平台配置管理员可以诊断共享阈值候选" />
  <template v-else>
   <div class="workbench-layout"><section class="threshold-panel workbench-form"><h2>明确输入候选</h2><p>六个根字段必须完整；未知填写null。warning/critical/recovery为null或完整comparison/threshold/unit三字段。所有阈值与秒数使用十进制字符串，不使用JSON数值；保留原小数精度和单位标签。</p>
    <details><summary>查看未定字段结构（不会填入或发送）</summary><pre>{{ example }}</pre><p>direction：HIGH_IS_BAD / LOW_IS_BAD / null。comparison：GT（&gt;）、GE（≥）、LT（&lt;）、LE（≤）或null。单位只检查字面相同，不识别别名或自动换算。明确0秒与未定null不同，负持续时长会指出冲突。</p></details>
    <el-input type="textarea" :rows="12" :model-value="state.text" :disabled="discarding" aria-label="阈值候选原始JSON" placeholder="粘贴明确候选；未确认值保持null；不要填写密码、token或API密钥" @update:model-value="change($event)" />
    <p class="help">最多16KiB、3层、256 JSON token；十进制最多40位整数/40位小数。拒绝重复键、指数与负零；编辑立即清除旧结果，不自动发请求。</p>
    <div class="threshold-actions"><el-button type="primary" :disabled="state.busy || discarding || !state.text" :loading="state.busy" @click="analyze">只读诊断候选</el-button><el-button :disabled="discarding || !state.text" @click="clearInput">清除输入与结果</el-button></div>
   <ApiErrorNotice :error="state.error" /></section>
   <section class="workbench-result" aria-live="polite"><h2>诊断结果</h2><div v-if="!state.result" class="workbench-empty"><strong>{{state.busy ? '正在检查…' : '等待检查'}}</strong><p>输入资料后点击左侧检查按钮。结果不会自动保存。</p></div><div v-else class="threshold-panel"><h2>{{ statusLabel }}</h2><p>诊断版本 {{ state.result.analysis_version }}；来源为本次明确输入，不是当前生效规则。</p>
    <ul class="issues"><li v-for="issue in state.result.issues" :key="issue.code"><strong>{{ issue.kind === 'CONFLICT' ? '冲突' : '未定' }}：</strong>{{ issueLabels[issue.code] }} <span class="help">{{ issue.code }}</span></li></ul><p v-if="state.result.issues.length === 0">本组显式谓词未发现上述数学冲突；不能由此宣称阈值适合该设备或可以发布。</p>
    <h3>原输入回显</h3><p>异常方向：{{ state.result.inputs.direction === 'HIGH_IS_BAD' ? '高值异常' : state.result.inputs.direction === 'LOW_IS_BAD' ? '低值异常' : '未确认' }}</p>
    <div class="condition-grid"><section v-for="item in conditions" :key="item.name" class="condition"><h4>{{ item.name }}条件</h4><p>{{ operator(item.value?.comparison) }} {{ item.value?.threshold ?? '阈值未确认' }}</p><p>单位：{{ item.value?.unit ?? '未确认' }}</p></section></div>
    <p>触发持续时长：{{ state.result.inputs.trigger_hold_seconds === null ? '未确认' : state.result.inputs.trigger_hold_seconds + ' 秒' }}；恢复持续时长：{{ state.result.inputs.recovery_hold_seconds === null ? '未确认' : state.result.inputs.recovery_hold_seconds + ' 秒' }}</p>
    <h3>精确关系检查</h3><dl class="relations"><dt>比较符方向一致</dt><dd>{{ booleanText(state.result.direction_consistent) }}</dd><dt>严重条件比警告更严格</dt><dd>{{ booleanText(state.result.critical_strict_subset_of_warning) }}</dd><dt>恢复与警告存在交集</dt><dd>{{ booleanText(state.result.recovery_overlaps_warning) }}</dd><dt>恢复与严重存在交集</dt><dd>{{ booleanText(state.result.recovery_overlaps_critical) }}</dd><dt>两个持续时长均非负</dt><dd>{{ booleanText(state.result.durations_nonnegative) }}</dd></dl>
    <el-alert type="info" :closable="false" title="未保存、未应用，仍不可发布">生效规则为null；物理范围未验证、客户确认未核验、单位未换算、真实时序未计算。本页没有保存或发布按钮，实际监控状态不由本页结论推断。</el-alert>
   </div></section></div>
  </template>
 </section>
</template>
<style scoped>
.threshold-page{min-width:0;line-height:1.7}.threshold-panel{margin:20px 0;padding:22px;border:1px solid #dce4ee;border-radius:12px;background:white;overflow-wrap:anywhere}.help{color:#526176}.threshold-actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:16px}.threshold-actions .el-button{margin-left:0}.condition-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.condition{padding:12px;background:#f4f7fb;border-radius:8px}.relations{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px 16px}.relations dd{margin:0}.issues{padding-left:22px}pre{white-space:pre-wrap;overflow-wrap:anywhere}.threshold-page summary{cursor:pointer}.threshold-page :deep(textarea){font-family:monospace}@media(max-width:700px){.threshold-panel{padding:14px}.condition-grid,.relations{grid-template-columns:1fr}.relations dd{margin-bottom:8px}.threshold-page .el-button{min-height:44px}.page-heading{align-items:flex-start;gap:12px;flex-direction:column}}
.threshold-page .workbench-form{margin:0}.workbench-result>.threshold-panel{border:0;padding:0;margin:0;background:transparent}.workbench-form>p{font-size:13px}
</style>
