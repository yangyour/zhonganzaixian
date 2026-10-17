<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { readCapacityInput, type CapacityInput } from '../utils/capacityEstimateWorkspace'
import { createScenarioWorkspace, emptyScenarioState, type ScenarioState, type CapacityScenario } from '../utils/capacityScenarioWorkspace'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ calculation: unknown }>()
const open = ref(false), inputSnapshot = shallowRef<CapacityInput | null>(null), validation = ref(''), query = ref('')
const form = reactive({ label: '', note: '', includeReference: false, reference: '', title: '', sha256: '' })
const state = shallowRef<ScenarioState>(emptyScenarioState()), workspace = createScenarioWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value?.user.role === 'padmin' && can('capacity_scenarios:read') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('capacity_scenarios:write'))
const locked = computed(() => !writable.value || Boolean(state.value.busy) || Boolean(state.value.attempt))
const hasDraft = computed(() => inputSnapshot.value !== null && state.value.attempt === null)
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, writable.value]))
function clearForm() { inputSnapshot.value = null; Object.assign(form, { label: '', note: '', includeReference: false, reference: '', title: '', sha256: '' }); validation.value = ''; query.value = '' }
watch(contextKey, () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true, canWrite: writable.value } : null); clearForm() }, { immediate: true, flush: 'sync' })
async function confirmDiscard() { if (!hasDraft.value) return true; try { await ElMessageBox.confirm('将替换尚未提交的方案名称、说明和固定输入，是否继续？', '保留未提交方案', { confirmButtonText: '确认继续', cancelButtonText: '保留当前内容', type: 'warning' }); return true } catch { return false } }
async function prepare() { if (!writable.value) return false; open.value = true; if (workspace.unresolved() || state.value.busy) { ElMessage.warning('先处理完全相同的原请求，不能覆盖或新建'); return false } const turn = contextKey.value; if (!await confirmDiscard() || turn !== contextKey.value || !writable.value) return false; try { const value = readCapacityInput(props.calculation); if (!workspace.begin()) return false; clearForm(); inputSnapshot.value = value; return true } catch { validation.value = '当前输入格式不符合要求，请先检查容量字段；未保存任何方案'; return false } }
async function list(next = false) { validation.value = ''; try { await workspace.list(query.value, next) } catch { validation.value = '标签查询最多100字符，不接受控制字符；没有发送写请求' } }
async function browse() { if (!permitted.value) return; open.value = true; await list() }
async function save() { if (locked.value || !inputSnapshot.value) return; validation.value = ''; try { if (await workspace.submit({ expected_version: 0, label: form.label, note: form.note, calculation: inputSnapshot.value, benchmark_reference: form.includeReference ? { reference: form.reference, title: form.title, sha256: form.sha256 } : null })) ElMessage.success('已保存不可变草稿，尚未核验模型、基准或容量') } catch { validation.value = '请核对名称、说明、固定输入和可选资料引用；请勿填写密码或token' } }
async function retry() { if (await workspace.retry()) ElMessage.success('原请求回执已确认，没有另建方案') }
async function mayLeave() { if (workspace.unresolved()) { ElMessage.warning('原保存结果尚未确认，请先使用原请求重试；只读列表不能替代确认'); return false } return confirmDiscard() }
onBeforeRouteLeave(mayLeave)
const unregister = contextTransitionGuards.register(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || hasDraft.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
const labels: Record<string, string> = { hosts: '主机数', items_per_host: '每台有效监控项数', interval_seconds: '采样间隔（秒）', history_days: '历史保留天数', bytes_per_value: '每值空间（bytes）', trend_ratio: '趋势比例', trend_days: '趋势保留天数', headroom_ratio: '额外余量比例', version: '自述模型版本', bytes_per_trend_row: '每条趋势空间（bytes）', pool_nvps_limit: '显式单池NVPS上限', site_nvps: '站点NVPS', outage_seconds: '最长断网秒数', margin_factor: '直接相乘余量倍率', nvps: '平均NVPS', daily_bytes: '日写入量（bytes）', history_bytes: '历史总量（bytes）', trend_bytes: '趋势量（bytes）', single_node_bytes: '单节点（bytes）', two_node_bytes: '主备两份（bytes）', peak_nvps: '2倍峰值目标', total_items: '监控项总数', pools: '资源池数量' }
const groups = [{ key: 'inputs', label: '容量八项输入' }, { key: 'model', label: '明确模型参数' }, { key: 'proxy_buffer', label: 'Proxy缓冲输入' }] as const
function fields(calculation: CapacityInput, group: keyof CapacityInput) { return Object.entries(calculation[group] ?? {}).map(([key, value]) => ({ key, label: labels[key] || key, value })) }
function outputs(row: CapacityScenario) { return Object.entries(row.snapshot.outputs ?? {}).map(([key, value]) => ({ key, label: labels[key] || key, value })) }
defineExpose({ prepare, browse, hasUnresolved: workspace.unresolved })
</script>
<template>
 <el-drawer v-model="open" title="容量方案草稿 · 保存与回读" size="900px" class="capacity-scenario-panel" :close-on-click-modal="false">
  <el-alert type="warning" :closable="false" title="保存当前容量测算参数与公式计算结果快照（作为只读历史方案草稿归档，不直接改变生产监控或资源池配置）。请勿在说明中填写敏感凭据。" />
  <el-alert v-if="!permitted" type="warning" :closable="false" title="当前读取授权不可用，原范围内容已清除" />
  <template v-else>
   <ApiErrorNotice :error="state.error" /><p v-if="validation" role="alert">{{ validation }}</p>
   <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存结果尚未确认，原输入和幂等键已保留。刷新列表不会重发，也不能解决原请求；请勿刷新浏览器。重试被拒绝仍不证明原请求未提交。" />
   <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="首次请求被明确拒绝，输入仍保留。核对后可明确以当前页面输入准备新方案；不会自动重试。" />
   <div class="scenario-actions"><el-button v-if="writable" :disabled="Boolean(state.busy) || workspace.unresolved()" @click="prepare">以当前页面输入准备新方案</el-button><el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retry">完全相同原请求重试</el-button></div>
   <el-alert v-if="state.receipt" type="success" :closable="false" :title="'已确认原保存回执：' + state.receipt.id + ' · 已归档为方案草稿'" />
   <p v-if="state.receipt">原回执不因列表刷新被替换。<el-button text :disabled="Boolean(state.busy)" @click="workspace.select(state.receipt.id)">只读核对原方案详情</el-button></p>
   <section v-if="inputSnapshot" class="scenario-section"><h2>本次固定输入快照</h2><p>已锁定准备保存时的参数快照（后续修改主页面表单不会改变当前待保存方案；留空字段保持未填写状态）。</p>
    <div class="scenario-groups"><section v-for="group in groups" :key="group.key"><h3>{{ group.label }}</h3><p v-if="inputSnapshot[group.key] === null">未启用</p><dl v-else><template v-for="field in fields(inputSnapshot, group.key)" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value ?? '未填写' }}</dd></template></dl></section></div>
    <el-form label-position="top" @submit.prevent="save"><el-form-item label="方案名称（1至100字符，保留原文）"><el-input v-model="form.label" :disabled="locked" aria-label="容量方案名称" /></el-form-item><el-form-item label="方案说明（1至2000字符）"><el-input v-model="form.note" type="textarea" :rows="3" :disabled="locked" aria-label="容量方案说明" /></el-form-item><el-checkbox v-model="form.includeReference" :disabled="locked">关联外部基准测试资料引用</el-checkbox><p>选填项，用于登记外部压测或基准文档的标识、标题与 SHA-256 摘要。</p><template v-if="form.includeReference"><el-form-item label="资料标识（document-ref:开头，非URL或磁盘路径）"><el-input v-model="form.reference" :disabled="locked" aria-label="容量基准资料标识" /></el-form-item><el-form-item label="资料标题"><el-input v-model="form.title" :disabled="locked" aria-label="容量基准资料标题" /></el-form-item><el-form-item label="资料SHA256（64位十六进制）"><el-input v-model="form.sha256" :disabled="locked" aria-label="容量基准资料摘要" /></el-form-item></template><el-button native-type="submit" type="primary" :disabled="locked" :loading="state.busy === 'write'">明确保存不可变草稿</el-button></el-form>
   </section>
   <section class="scenario-section"><h2>当前租户已保存方案（只读）</h2><div class="scenario-actions"><el-input v-model="query" :disabled="Boolean(state.busy)" aria-label="容量方案标签查询" placeholder="按方案名称筛选" /><el-button :disabled="Boolean(state.busy)" @click="list()">刷新第一页</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more || query !== state.query" @click="list(true)">下一页</el-button></div><p>当前筛选条件：{{ state.query || '全部' }} · 每页 15 条（{{ state.paged ? '后续页' : '第一页' }}）。</p><p v-if="!state.page">正在加载方案列表…</p><el-empty v-else-if="!state.page.items.length" description="本页没有匹配方案" /><div class="scenario-list"><button v-for="row in state.page?.items || []" :key="row.id" :disabled="Boolean(state.busy)" :aria-pressed="state.selected?.id === row.id" @click="workspace.select(row.id)"><strong>{{ row.label }}</strong><span>DRAFT · 待核验 · {{ displayTime(row.recorded_at) }}</span><small>{{ row.id }}</small></button></div></section>
   <section v-if="state.selected" class="scenario-section" aria-live="polite"><h2>{{ state.selected.label }}</h2><p class="scenario-original">{{ state.selected.note }}</p><p>v1 · {{ displayTime(state.selected.recorded_at) }} · 创建人 {{ state.selected.created_by }}</p><p>基准资料引用：{{ state.selected.benchmark_reference ? '已登记引用（待核验）' : '未提供' }}</p><dl v-if="state.selected.benchmark_reference"><dt>标识</dt><dd>{{ state.selected.benchmark_reference.reference }}</dd><dt>标题</dt><dd>{{ state.selected.benchmark_reference.title }}</dd><dt>摘要</dt><dd>{{ state.selected.benchmark_reference.sha256 }}</dd></dl><div class="scenario-groups"><section v-for="group in groups" :key="group.key"><h3>原始{{ group.label }}</h3><p v-if="state.selected.calculation[group.key] === null">未启用</p><dl v-else><template v-for="field in fields(state.selected.calculation, group.key)" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value ?? '未填写' }}</dd></template></dl></section></div><h3>固定公式结果</h3><p>{{ state.selected.snapshot.capacity_formula_version }} · {{ state.selected.snapshot.status === 'ESTIMATED' ? '已完成估算' : '参数未完整' }}</p><p v-if="state.selected.snapshot.missing_inputs.length">待明确：{{ state.selected.snapshot.missing_inputs.join('、') }}</p><dl><template v-for="field in outputs(state.selected)" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl><p>Proxy缓冲：{{ state.selected.snapshot.proxy_buffer.estimated_bytes ?? '未测算' }} bytes · {{ state.selected.snapshot.buffer_formula_version }}</p><el-alert v-if="state.selected.snapshot.proxy_buffer.margin_factor_below_one" type="warning" :closable="false" title="缓冲余量倍率小于 1，计算结果低于基础缓冲需求" /><p>正式容量定级与资源池上限以实际 PoC 压测报告及客户签署结果为准。</p></section>
  </template>
  <template #footer><el-button @click="open = false">暂时收起，保留原请求与本页内容</el-button><p>收起面板不会中断正在执行的请求；切换租户或离开页面前请先确认提交结果。</p></template>
 </el-drawer>
</template>
<style>
.capacity-scenario-panel{max-width:100vw}.capacity-scenario-panel .el-alert{margin:12px 0}.scenario-actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.scenario-actions .el-input{flex:1;min-width:200px}.scenario-section{margin:20px 0;padding:16px;border:1px solid var(--el-border-color);border-radius:8px;overflow-wrap:anywhere}.scenario-section dl{display:grid;grid-template-columns:minmax(110px,1fr) minmax(0,2fr);gap:8px}.scenario-section dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.scenario-section dt{color:var(--el-text-color-secondary)}.scenario-groups{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.scenario-original{white-space:pre-wrap}.scenario-list{display:grid;gap:10px}.scenario-list button{display:flex;flex-direction:column;gap:8px;text-align:left;padding:14px;background:var(--el-bg-color);color:var(--el-text-color-primary);border:1px solid var(--el-border-color);border-radius:6px;overflow-wrap:anywhere;cursor:pointer}.scenario-list button[aria-pressed=true]{border-color:var(--el-color-primary)}@media(max-width:700px){.scenario-groups{grid-template-columns:1fr}.capacity-scenario-panel .el-drawer__body{padding:12px}.capacity-scenario-panel .el-button{min-height:44px;height:auto;white-space:normal}.capacity-scenario-panel .el-input__wrapper{min-height:44px}.scenario-section{padding:12px}.scenario-section dl{grid-template-columns:1fr}.scenario-section dd{margin-bottom:8px}}
</style>
