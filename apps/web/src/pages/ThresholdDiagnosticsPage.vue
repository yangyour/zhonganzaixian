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
  <div class="compact-page-toolbar page-heading"><div><h1 class="page-title-sr">告警阈值规则诊断</h1></div><div class="toolbar-links"><router-link v-if="permitted" class="observation-entry" to="/observation-window-diagnostics">采样观察窗诊断</router-link><router-link to="/configurations">返回配置提案</router-link></div></div>
  <el-empty v-if="!permitted" description="仅当前平台配置管理员可以诊断共享阈值候选" />
  <template v-else>
   
   <ApiErrorNotice :error="state.error" />
   <div class="threshold-layout"><div class="threshold-main"><section v-if="!state.result" class="threshold-panel threshold-empty"><div class="panel-heading"><h2>诊断结果</h2></div><div class="diagnostic-empty"><span aria-hidden="true">⌘</span><h3>尚未诊断</h3><p>在右侧粘贴候选 JSON 后开始诊断</p></div></section><section v-if="state.result" class="threshold-panel" aria-live="polite"><h2>{{ statusLabel }}</h2><p class="help">诊断版本 {{ state.result.analysis_version }} · 本次录入候选</p>
    <ul class="issues"><li v-for="issue in state.result.issues" :key="issue.code"><strong>{{ issue.kind === 'CONFLICT' ? '冲突' : '未定' }}：</strong>{{ issueLabels[issue.code] }} <span class="help">{{ issue.code }}</span></li></ul><p v-if="state.result.issues.length === 0">未发现数学区间冲突。</p>
    <h3>原输入回显</h3><p>异常方向：{{ state.result.inputs.direction === 'HIGH_IS_BAD' ? '高值异常' : state.result.inputs.direction === 'LOW_IS_BAD' ? '低值异常' : '未确认' }}</p>
    <div class="condition-grid"><section v-for="item in conditions" :key="item.name" class="condition"><h4>{{ item.name }}条件</h4><p>{{ operator(item.value?.comparison) }} {{ item.value?.threshold ?? '阈值未确认' }}</p><p>单位：{{ item.value?.unit ?? '未确认' }}</p></section></div>
    <p>触发持续时长：{{ state.result.inputs.trigger_hold_seconds === null ? '未确认' : state.result.inputs.trigger_hold_seconds + ' 秒' }}；恢复持续时长：{{ state.result.inputs.recovery_hold_seconds === null ? '未确认' : state.result.inputs.recovery_hold_seconds + ' 秒' }}</p>
    <h3>精确关系检查</h3><dl class="relations"><dt>比较符方向一致</dt><dd>{{ booleanText(state.result.direction_consistent) }}</dd><dt>严重条件比警告更严格</dt><dd>{{ booleanText(state.result.critical_strict_subset_of_warning) }}</dd><dt>恢复与警告存在交集</dt><dd>{{ booleanText(state.result.recovery_overlaps_warning) }}</dd><dt>恢复与严重存在交集</dt><dd>{{ booleanText(state.result.recovery_overlaps_critical) }}</dd><dt>两个持续时长均非负</dt><dd>{{ booleanText(state.result.durations_nonnegative) }}</dd></dl>
    
   </section></div><aside class="threshold-side"><section class="threshold-panel"><div class="panel-heading"><h2>候选阈值输入</h2><span>仅校验，不会直接下发至生产监控</span></div>
    <details><summary>JSON 结构示例</summary><pre>{{ example }}</pre><p>direction 可选 HIGH_IS_BAD / LOW_IS_BAD / null；comparison 可选 GT（&gt;）、GE（≥）、LT（&lt;）、LE（≤）或 null。单位按字面一致性核对，不自动执行跨单位换算。</p></details>
    <el-input type="textarea" :rows="16" :model-value="state.text" :disabled="discarding" aria-label="阈值候选原始JSON" placeholder="请粘贴阈值候选 JSON（未确认项保持 null；请勿填写密码、Token 或密钥）" @update:model-value="change($event)" />
    <p class="help">≤16KiB · 3 层嵌套 · 256 个 Token · 小数最多 40 位整数 / 40 位小数</p>
    <div class="threshold-actions"><el-button type="primary" :disabled="state.busy || discarding || !state.text" :loading="state.busy" @click="analyze">开始诊断</el-button><el-button :disabled="discarding || !state.text" @click="clearInput">清除输入与结果</el-button></div>
   </section></aside></div>
  </template>
 </section>
</template>
<style scoped>
.threshold-page{max-width:none;min-width:0;line-height:1.7}
.threshold-layout{display:grid;grid-template-columns:minmax(0,1.65fr) minmax(340px,1fr);gap:14px;margin-top:14px;align-items:start}
.threshold-main,.threshold-side{min-width:0}
.threshold-panel{padding:18px;border:1px solid #e2e9f2;border-radius:6px;margin:0;background:#fff;overflow-wrap:anywhere}
.threshold-layout h2{font-size:15px}
.threshold-layout h3{font-size:14px}
.threshold-layout p,.threshold-layout .help,.threshold-layout .boundary-details,.threshold-layout summary{font-size:12px;line-height:1.7}
.help{color:#526176}
.threshold-notice{font-size:12px;color:#947334;background:#fff9ec;border:1px solid #f0e4c8;border-radius:4px;padding:10px 14px}
.threshold-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}
.threshold-actions .el-button{margin-left:0;font-size:12px}
.condition-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}
.condition{background:#f6f9fe;border:1px solid #e4edf9;border-radius:5px;padding:14px}
.condition h4{font-size:12px;color:#657993;font-weight:500;margin:0 0 10px}
.condition strong{font-size:26px;color:#8ea7c8}
.diagnostic-empty{padding:50px 12px;text-align:center;color:#8a9bb2;font-size:12px}
.diagnostic-empty>span{font-size:40px;color:#c1d1e6}
.diagnostic-empty h3{color:#647f9f;font-weight:500}
.relations{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);border-top:1px solid #e7edf5;font-size:12px;gap:0;margin-bottom:20px}
.relations dt,.relations dd{padding:12px;border-bottom:1px solid #e7edf5;margin:0}
.relations dt{background:#f6f9fd;color:#657993}
.issues{background:#fff9ed;padding:14px 14px 14px 32px;font-size:12px}
pre{white-space:pre-wrap;overflow-wrap:anywhere}
.threshold-page summary{cursor:pointer}
.threshold-page :deep(textarea){font-family:monospace;font-size:12px;line-height:1.7;background:#fafcff}
@media(max-width:1050px){.threshold-layout{grid-template-columns:1fr}.threshold-side{grid-row:1}.threshold-layout :deep(textarea){max-height:230px}}
@media(max-width:700px){.threshold-panel{padding:14px}.condition-grid,.relations{grid-template-columns:1fr}.relations dd{margin-bottom:8px}.threshold-page .el-button{min-height:44px}.page-heading{align-items:flex-start;gap:12px;flex-direction:column}}
.observation-entry:focus-visible{outline:3px solid #176bb5;outline-offset:3px}
.toolbar-links{display:flex;gap:8px;flex-wrap:wrap;align-items:center}.toolbar-links a{display:inline-flex;align-items:center;min-height:32px;box-sizing:border-box;padding:4px 12px;border:1px solid #c4cfdc;border-radius:6px;background:#fff;color:#185695;text-decoration:none;font-size:13px}.toolbar-links a:hover{border-color:#1f6fd1}
.threshold-panel>.panel-heading{margin:-18px -18px 14px;border-radius:6px 6px 0 0}@media(max-width:700px){.toolbar-links a{min-height:44px}.threshold-panel>.panel-heading{margin:-14px -14px 12px}}
</style>
