<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { onBeforeRouteLeave } from 'vue-router'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createConfigurationDraftWriter, emptyConfigurationDraftState, type ConfigurationDraftState } from '../utils/configurationDraftWriter'
import { api, ApiError, isCancelled } from '../api/client'
import type { Configuration, ConfigurationRevision } from '../api/types'
import { displayValue } from '../utils/display'
import { editorKind, parseProposedValue } from '../utils/configuration'
import { revisionStatusLabel } from '../utils/configurationRevision'
import { comparisonValue, configurationComparison, configurationMetadataLabel, configurationQuestionFilterIssues, configurationQuestionOptions, configurationQuestionsLabel, configurationScopeOptions, createConfigurationComparisonReader, filterConfigurations, type ConfigurationComparisonReadState, type ConfigurationFilters } from '../utils/configurationComparison'
import AppTable from '../components/AppTable.vue'
import PermissionGate from '../components/PermissionGate.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ConfigurationRevisions from '../components/ConfigurationRevisions.vue'
import ConfigurationDiagnosticPanel from '../components/ConfigurationDiagnosticPanel.vue'
import WorkingCalendarReader from '../components/WorkingCalendarReader.vue'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
const calendarOpen = ref(false)
const rows = ref<Configuration[]>([])
const loading = ref(false)
const error = ref<unknown>(null)
const controller = new AbortController()
let loadGeneration = 0
const pendingCount = computed(() => rows.value.filter(item => item.decision_status === 'pending').length)
const draftCount = computed(() => rows.value.filter(item => item.lifecycle_status === 'DRAFT').length)
const filters = reactive<ConfigurationFilters>({ config_key: '', question_id: '', pending_only: false, registered_scope: '' })
const filteredRows = computed(() => filterConfigurations(rows.value, filters))
const questions = computed(() => configurationQuestionOptions(rows.value))
const registeredScopes = computed(() => configurationScopeOptions(rows.value))
const questionIssues = computed(() => configurationQuestionFilterIssues(rows.value, filters))
const countsUnknown = computed(() => loading.value || Boolean(error.value))
function resetFilters() { Object.assign(filters, { config_key: '', question_id: '', pending_only: false, registered_scope: '' }) }
async function load() {
  const generation = ++loadGeneration
  loading.value = true; error.value = null; rows.value = []
  if (comparisonOpen.value) void loadComparison()
  try { const result = await api.request<{ items: Configuration[] }>('/configurations', { signal: controller.signal }); if (generation === loadGeneration) rows.value = result.items }
  catch (cause) { if (!isCancelled(cause) && generation === loadGeneration) { error.value = cause; rows.value = [] } }
  finally { if (generation === loadGeneration) loading.value = false }
}
onMounted(load)
onUnmounted(() => { ++diagnosticOpenGeneration; controller.abort(); comparisonReader.reset() })
const draftState = shallowRef<ConfigurationDraftState>(emptyConfigurationDraftState())
const draftWriter = createConfigurationDraftWriter(api, value => { draftState.value = value })
const editing = ref<Configuration | null>(null)
const editingOpen = computed({ get: () => Boolean(editing.value), set: value => { if (!value) { if (draftWriter.unresolved()) { ElMessage.warning('原设计草稿请求尚未确认，请先原请求重试'); return } editing.value = null } } })
const proposed = ref<string | boolean>(''), initialProposed = ref<string | boolean>('')
const validationError = ref('')
const editError = computed(() => draftState.value.error)
const saving = computed(() => draftState.value.busy === 'write')
const draftUnresolved = computed(() => draftState.value.busy === 'write' || draftState.value.attempt?.state === 'uncertain')
const draftLocked = computed(() => Boolean(draftState.value.busy) || draftUnresolved.value || Boolean(draftState.value.attempt))
const draftChanged = computed(() => Boolean(editing.value) && proposed.value !== initialProposed.value)
const kind = computed(() => editing.value ? editorKind(editing.value) : 'text')
function editorValue(item: Configuration): string | boolean { const value = item.draft_value; return value === null || value === undefined ? '' : typeof value === 'boolean' ? value : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value) }
function openDraft(item: Configuration) {
  if (draftWriter.unresolved()) { ElMessage.warning('请先处理原设计草稿请求，不能更换配置键'); return }
  if (typeof item.type !== 'string') { ElMessage.warning('登记类型格式异常，请先查询该配置的有效性诊断'); return }
  try { if (!draftWriter.begin(item)) return } catch (cause) { ElMessage.warning(cause instanceof Error ? cause.message : '登记草稿基准不可用'); return }
  closeDiagnostic(); editing.value = draftState.value.baseline; validationError.value = ''; proposed.value = editorValue(item); initialProposed.value = proposed.value
}
async function savedDraft() { if (draftState.value.receipt) { editing.value = null; ElMessage.success('设计登记建议草稿已保存，未成为租户运行配置'); await load() } }
async function saveDraft() {
  if (!editing.value || draftLocked.value) return
  let value: unknown
  try { value = parseProposedValue(kind.value, proposed.value) }
  catch (cause) { validationError.value = cause instanceof Error ? cause.message : '建议值有误'; return }
  validationError.value = ''
  try { if (await draftWriter.submit(value)) await savedDraft() }
  catch (cause) { validationError.value = cause instanceof Error ? cause.message : '建议值有误' }
}
async function retryDraft() { if (await draftWriter.retry()) await savedDraft() }
async function rebaseDraft() {
  if (draftWriter.unresolved() || !await draftWriter.refresh() || !draftState.value.lastRead) return
  const next = draftState.value.lastRead, generation = contextGeneration.value
  try { await ElMessageBox.confirm('将采用刚回读的最新版本作为编辑基准，保留你当前填写的建议值。请确认已核对版本与旧建议差异。', '明确更换基准', { type: 'warning', confirmButtonText: '确认使用新基准', cancelButtonText: '保留原基准' }) } catch { return }
  if (generation !== contextGeneration.value || !draftWriter.begin(next)) return
  editing.value = next; initialProposed.value = editorValue(next); validationError.value = ''
}
async function leaveDraftContext() {
  if (draftWriter.unresolved()) { ElMessage.warning('设计登记草稿原请求尚未确认，请先原请求重试'); return false }
  if (!draftChanged.value) return true
  try { await ElMessageBox.confirm('离开将舍弃尚未发送的设计草稿编辑内容。', '未发送的建议值', { type: 'warning', confirmButtonText: '舍弃并继续', cancelButtonText: '保留编辑' }); return true } catch { return false }
}
const unregisterDraftGuard = contextTransitionGuards.register(leaveDraftContext)
onBeforeRouteLeave(leaveDraftContext)
function draftBeforeUnload(event: BeforeUnloadEvent) { if (draftWriter.unresolved() || draftChanged.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', draftBeforeUnload)
watch([contextGeneration, () => session.value?.tenant.id, () => session.value?.user.id, () => session.value?.user.role,
  () => session.value?.permission_version, () => session.value?.account_version, () => can('configurations:write'), switchingTenant],
() => { const value = session.value; draftWriter.reset(value && value.user.role === 'padmin' && can('configurations:write') && !switchingTenant.value ? { tenantId: value.tenant.id, actorId: value.user.id, permissionVersion: value.permission_version } : null); editing.value = null; proposed.value = ''; initialProposed.value = ''; validationError.value = '' }, { immediate: true, flush: 'sync' })
onUnmounted(() => { unregisterDraftGuard(); window.removeEventListener('beforeunload', draftBeforeUnload); draftWriter.dispose() })
const showConflict = computed(() => editError.value instanceof ApiError && editError.value.status === 409)
const revisionsOpen = ref(false)
const revisionConfiguration = ref<Configuration | null>(null)
const revisionPanel = ref<InstanceType<typeof ConfigurationRevisions> | null>(null)
function openRevisions(item: Configuration) { if (revisionConfiguration.value?.key !== item.key && revisionPanel.value?.hasUnresolved()) { ElMessage.warning('上一配置原请求尚未确认，请先恢复原面板并重试'); revisionsOpen.value = true; return } closeDiagnostic(); revisionConfiguration.value = item; revisionsOpen.value = true }

const comparisonOpen = ref(false)
const comparisonKey = ref('')
const proposalSelection = ref('draft')
const comparisonState = shallowRef<ConfigurationComparisonReadState>({ status: 'idle' })
const comparisonReader = createConfigurationComparisonReader({
  readConfigurations: async signal => (await api.request<{ items: Configuration[] }>('/configurations', { signal })).items,
  readRevisions: async (key, signal) => (await api.request<{ items: ConfigurationRevision[] }>(`/configurations/${encodeURIComponent(key)}/revisions`, { signal })).items,
  onChange: state => { comparisonState.value = state },
})
const comparisonSnapshot = computed(() => comparisonState.value.status === 'ready' ? comparisonState.value : null)
const comparisonLoading = computed(() => comparisonState.value.status === 'loading')
const comparisonError = computed(() => comparisonState.value.status === 'error' ? comparisonState.value.error : null)
const selectedRevision = computed(() => comparisonSnapshot.value?.revisions.find(item => item.id === proposalSelection.value))
const comparison = computed(() => {
  const snapshot = comparisonSnapshot.value
  if (!snapshot) return null
  if (proposalSelection.value === 'draft') return configurationComparison(snapshot.configuration, { kind: 'draft' })
  return selectedRevision.value ? configurationComparison(snapshot.configuration, { kind: 'revision', revision: selectedRevision.value }) : null
})
const hasDraft = computed(() => comparisonSnapshot.value?.configuration.draft_value !== null && comparisonSnapshot.value?.configuration.draft_value !== undefined)
function openComparison(item: Configuration) {
  closeDiagnostic()
  comparisonKey.value = item.key; proposalSelection.value = 'draft'; comparisonOpen.value = true
  void loadComparison()
}
async function loadComparison() {
  if (!comparisonOpen.value || !comparisonKey.value) return
  const loaded = await comparisonReader.load(comparisonKey.value)
  if (!loaded || !comparisonSnapshot.value) return
  const snapshot = comparisonSnapshot.value
  if ((proposalSelection.value === 'draft' && hasDraft.value) || snapshot.revisions.some(item => item.id === proposalSelection.value)) return
  proposalSelection.value = snapshot.revisions[0]?.id || 'draft'
}
watch(comparisonOpen, open => { if (!open) { comparisonReader.reset(); comparisonKey.value = ''; proposalSelection.value = 'draft' } })

const diagnosticOpen = ref(false), diagnosticKey = ref('')
let diagnosticOpenGeneration = 0
const diagnosticPermitted = computed(() => session.value?.user.role === 'padmin' && can('configurations:read') && !switchingTenant.value)
const diagnosticContext = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version, can('configurations:read'), switchingTenant.value]))
function closeDiagnostic() { ++diagnosticOpenGeneration; diagnosticOpen.value = false; diagnosticKey.value = '' }
function hasPendingWritePanel() {
  return Boolean(editing.value || saving.value
    || (revisionsOpen.value && (!revisionPanel.value || !revisionPanel.value.canCloseForReadPanel())))
}
async function openDiagnostic(item: Configuration) {
  if (!diagnosticPermitted.value) return
  if (hasPendingWritePanel()) { ElMessage.warning('配置填写或写入结果仍需处理，请先回到原表单确认，再打开有效性诊断'); return }
  const epoch = ++diagnosticOpenGeneration, context = diagnosticContext.value
  comparisonOpen.value = false; comparisonReader.reset(); revisionsOpen.value = false; revisionConfiguration.value = null
  await nextTick()
  if (epoch !== diagnosticOpenGeneration || diagnosticContext.value !== context || !diagnosticPermitted.value || hasPendingWritePanel()) return
  diagnosticKey.value = item.key; diagnosticOpen.value = true
}
watch(diagnosticOpen, value => { if (!value) { ++diagnosticOpenGeneration; diagnosticKey.value = '' } }, { flush: 'sync' })
watch(diagnosticContext, closeDiagnostic, { flush: 'sync' })
</script>
<template>
  <section class="configuration-page">
    <div class="compact-page-toolbar page-heading"><div><h1 class="page-title-sr">配置项登记</h1></div><router-link to="/runtime-configurations">运行配置与发布</router-link><el-button :loading="loading" @click="load">刷新列表</el-button></div>

    <WorkingCalendarReader v-model="calendarOpen" />
    <el-alert v-if="draftState.receipt" type="success" :closable="false" :title="'配置建议草稿已保存：' + draftState.receipt.key + ' · v' + draftState.receipt.version + '（草稿状态，暂未发布生效）'" />
    <div class="panel configuration-register"><nav class="feature-tabs" aria-label="配置工作区"><button type="button" :class="{active:!filters.pending_only}" @click="filters.pending_only=false">全部登记</button><button type="button" :class="{active:filters.pending_only}" @click="filters.pending_only=true">待客户确认 <span>{{ countsUnknown ? '—' : pendingCount }}</span></button><router-link to="/threshold-diagnostics">告警阈值诊断</router-link><button type="button" :disabled="!can('configurations:read') || switchingTenant" @click="calendarOpen=true">工作日历查询与试算</button></nav><div class="panel-heading configuration-register-heading"><h2>配置登记与修订</h2><span>登记基线 / 建议草稿 / 运行生效值对照</span></div><ApiErrorNotice :error="error"><el-button size="small" @click="load">重试</el-button></ApiErrorNotice>
      <el-form class="configuration-filters" label-position="top" @submit.prevent>
        <el-form-item label="配置键（config_key）"><el-input v-model="filters.config_key" clearable placeholder="按配置键查找" aria-label="筛选配置键" /></el-form-item>
        <el-form-item label="关联确认 Q 编号"><el-select v-model="filters.question_id" clearable filterable placeholder="全部 Q 编号" aria-label="筛选关联确认 Q 编号"><el-option v-for="question in questions" :key="question" :value="question" :label="question" /></el-select></el-form-item>
        <el-form-item label="登记作用域"><el-select v-model="filters.registered_scope" clearable placeholder="全部登记作用域" aria-label="筛选登记作用域"><el-option v-for="scope in registeredScopes" :key="scope" :value="scope" :label="scope" /></el-select></el-form-item>
        <el-form-item label="确认状态"><el-checkbox v-model="filters.pending_only" aria-label="仅显示待客户确认">仅待客户确认</el-checkbox></el-form-item>
        <el-button @click="resetFilters">重置筛选</el-button>
      </el-form>
      <p class="configuration-filter-count" aria-live="polite">{{ countsUnknown ? '正在加载配置登记项…' : `匹配 ${filteredRows.length} / ${rows.length} 项登记配置` }} · 支持在当前已加载列表内组合筛选。</p>
      <p v-if="!countsUnknown && questionIssues.cannotConfirmMatch" class="configuration-filter-warning" role="status">当前其他筛选下：{{ questionIssues.missing }} 项 Q 信息缺失，{{ questionIssues.invalid }} 项格式异常。{{ filters.question_id ? '这些行无法确认与所选 Q 匹配；清除 Q 筛选后可查看并查询诊断。' : '这些行仍保留供查看和诊断；Q 筛选无法确认它们的关联。' }}</p>
      <AppTable :error="error" :rows="filteredRows" :loading="loading" row-key="key" :empty-text="error ? '登记表加载失败，请重试' : rows.length ? '暂无符合筛选条件的登记项' : '暂无配置登记项'">
      <el-table-column label="配置项" min-width="210"><template #default="{ row }"><strong class="configuration-name">{{ configurationMetadataLabel(row.description, '配置描述') }}</strong><span class="cell-sub mono">{{ row.key }}</span></template></el-table-column>
      <el-table-column label="登记作用域 / 类型" min-width="120"><template #default="{ row }">{{ configurationMetadataLabel(row.scope, '作用域') }}<span class="cell-sub">{{ configurationMetadataLabel(row.type, '类型') }}<template v-if="row.unit !== null && row.unit !== undefined"> · {{ configurationMetadataLabel(row.unit, '单位') }}</template></span></template></el-table-column>
      <el-table-column label="登记 / 合同值" min-width="130"><template #default="{ row }"><span :class="{ muted: row.registered_value === null }">{{ displayValue(row.registered_value) }}</span><span class="cell-sub">{{ configurationMetadataLabel(row.source, '来源') }} · 基线来源</span></template></el-table-column>
      <el-table-column label="确认状态" min-width="130"><template #default="{ row }"><el-tag :type="row.decision_status === 'pending' ? 'warning' : 'info'">{{ row.decision_status === 'pending' ? '待客户确认' : row.decision_status === 'contract_fixed' ? '合同有据' : row.decision_status === 'confirmed' ? '已确认' : configurationMetadataLabel(row.decision_status, '确认状态') }}</el-tag><span class="cell-sub">{{ configurationQuestionsLabel(row.question_ids) }}</span></template></el-table-column>
      <el-table-column label="建议草稿 / 版本" min-width="150"><template #default="{ row }"><span>{{ row.draft_value === null || row.draft_value === undefined ? '尚未填写' : displayValue(row.draft_value) }}</span><span class="cell-sub">v{{ row.version }} · {{ row.lifecycle_status === 'DRAFT' ? '草稿' : configurationMetadataLabel(row.lifecycle_status, '草稿状态') }}</span></template></el-table-column>
      <el-table-column label="运行值 / 发布" min-width="130"><template #default="{ row }"><router-link :to="{path:'/runtime-configurations',query:{key:row.key}}">读取当前运行值</router-link><span class="cell-sub">独立版本与生效时间</span></template></el-table-column>
      <!-- @vue-generic {Configuration} -->
      <el-table-column label="操作" min-width="230" fixed="right"><template #default="{ row }"><div class="configuration-row-actions"><el-button v-if="diagnosticPermitted" type="primary" link class="configuration-diagnostic-link" @click="openDiagnostic(row)">有效性诊断</el-button><el-button type="primary" link @click="openComparison(row)">建议差异</el-button><el-button type="primary" link @click="openRevisions(row)">修订与审批</el-button><PermissionGate capability="configurations:write"><el-button type="primary" link :disabled="row.contract_value_kind === 'fixed_constraint'" @click="openDraft(row)">{{ row.contract_value_kind === 'fixed_constraint' ? '固定约束' : '编辑草稿' }}</el-button><router-link :to="{path:'/runtime-configurations',query:{key:row.key}}">运行发布</router-link></PermissionGate></div></template></el-table-column>
    </AppTable><div class="table-footer"><span>客户确认结论与生产发布许可按正式审批文档登记管理。</span></div></div>
    <ConfigurationRevisions ref="revisionPanel" v-model="revisionsOpen" :configuration="revisionConfiguration" @changed="load" />
    <ConfigurationDiagnosticPanel v-model="diagnosticOpen" :configuration-key="diagnosticKey" />
    <el-drawer v-model="comparisonOpen" title="登记值与建议值差异" size="820px" class="configuration-comparison-drawer" destroy-on-close>
      <p class="mono configuration-comparison-key">{{ comparisonKey }}</p>
      
      <ApiErrorNotice :error="comparisonError"><el-button size="small" @click="loadComparison">重试读取</el-button></ApiErrorNotice>
      <el-skeleton v-if="comparisonLoading" :rows="7" animated class="configuration-comparison-loading" />
      <template v-else-if="comparisonSnapshot && comparison">
        <div class="config-dialog-context"><strong>{{ configurationMetadataLabel(comparisonSnapshot.configuration.description, '配置描述') }}</strong><p>登记版本 v{{ comparisonSnapshot.configuration.version }} · {{ comparison.decisionStatus === 'pending' ? '待客户确认' : comparison.decisionStatus === 'contract_fixed' ? '合同有据' : comparison.decisionStatus === 'confirmed' ? '已确认' : configurationMetadataLabel(comparison.decisionStatus, '确认状态') }}</p><p>关联确认：{{ configurationQuestionsLabel(comparisonSnapshot.configuration.question_ids) }}</p></div>
        <el-form label-position="top" class="configuration-comparison-selector"><el-form-item label="选择已保存建议来源"><el-select v-model="proposalSelection" aria-label="选择差异比较建议来源"><el-option value="draft" :disabled="!hasDraft" :label="hasDraft ? `登记建议草稿 · v${comparisonSnapshot.configuration.version}` : '登记建议草稿 · 尚未填写'" /><el-option v-for="revision in comparisonSnapshot.revisions" :key="revision.id" :value="revision.id" :label="`${revisionStatusLabel[revision.workflow_status]} · v${revision.version} · ${revision.id}`" /></el-select></el-form-item></el-form>
        <p v-if="selectedRevision" class="field-help">修订 {{ selectedRevision.id }} · {{ revisionStatusLabel[selectedRevision.workflow_status] }}。客户确认状态：{{ comparison.confirmationStatus }}；内部审批通过后需在运行配置中心执行发布。</p>
        <el-descriptions :column="1" border class="configuration-comparison-facts">
          <el-descriptions-item label="登记作用域">{{ comparison.registeredScope }}</el-descriptions-item>
          <el-descriptions-item label="登记 / 合同值"><pre>{{ comparisonValue(comparison.registeredValue) }}</pre></el-descriptions-item>
          <el-descriptions-item label="建议值"><pre>{{ comparison.proposalAvailable ? comparisonValue(comparison.proposedValue) : '尚未填写建议值' }}</pre></el-descriptions-item>
          <el-descriptions-item label="运行生效值"><pre>{{ comparison.effectiveValue === undefined ? '接口暂未提供生效值' : comparison.effectiveValue === null ? '暂未生效' : comparisonValue(comparison.effectiveValue) }}</pre></el-descriptions-item>
          <el-descriptions-item label="关联影响范围">待盘点 · 接口暂未返回受影响租户、模板或路由明细（登记作用域代表配置目录归属层级）。</el-descriptions-item>
        </el-descriptions>
        <div class="detail-section"><h3>登记值 → 建议值</h3><p class="field-help">字段路径采用 JSON Pointer 格式，数组按索引位置逐项对比。</p>
          <el-empty v-if="!comparison.proposalAvailable" description="尚无已保存建议，无法比较" :image-size="70" />
          <el-empty v-else-if="!comparison.differences.length" description="建议值与登记基线值一致（暂未发布生效）" :image-size="70" />
          <AppTable v-else :rows="comparison.differences" row-key="path"><el-table-column label="字段路径 / 变化" min-width="160"><template #default="{ row }"><span class="mono">{{ row.path || '根值（整个值）' }}</span><span class="cell-sub">{{ row.kind === 'added' ? '新增字段' : row.kind === 'removed' ? '移除字段' : '值改变' }}</span></template></el-table-column><el-table-column label="登记值" min-width="180"><template #default="{ row }"><pre class="configuration-difference-value">{{ comparisonValue(row.before, row.before_present) }}</pre></template></el-table-column><el-table-column label="建议值" min-width="180"><template #default="{ row }"><pre class="configuration-difference-value">{{ comparisonValue(row.after, row.after_present) }}</pre></template></el-table-column></AppTable>
        </div>
      </template>
      <template #footer><el-button :loading="comparisonLoading" @click="loadComparison">刷新对照数据</el-button><el-button @click="comparisonOpen = false">关闭</el-button></template>
    </el-drawer>
    <el-dialog v-model="editingOpen" title="编辑配置建议草稿（未发布生效）" width="560px" class="responsive-dialog" :close-on-click-modal="!draftUnresolved" :close-on-press-escape="!draftUnresolved" :show-close="!draftUnresolved" destroy-on-close>
      <template v-if="editing"><div class="config-dialog-context"><strong>{{ configurationMetadataLabel(editing.description, '配置描述') }}</strong><span class="mono">{{ editing.key }}</span><p>当前版本 v{{ editing.version }} · 登记值：{{ displayValue(editing.registered_value) }}</p><p>运行生效：{{ editing.effective_value === null ? '尚未启用' : displayValue(editing.effective_value) }}</p><p>关联确认：{{ configurationQuestionsLabel(editing.question_ids) }}</p></div>
        
        <ApiErrorNotice :error="editError" />
        <el-alert v-if="draftUnresolved" type="warning" :closable="false" title="提交结果待确认：系统已锁定当前草稿内容与基准版本，请先核对最新状态或点击“重试提交”继续处理。" />
        <div class="calendar-actions"><el-button :disabled="saving" @click="draftWriter.refresh()">刷新最新登记</el-button><el-button v-if="draftUnresolved" type="warning" :disabled="Boolean(draftState.busy)" @click="retryDraft">重试提交</el-button><el-button v-if="!draftUnresolved" :disabled="Boolean(draftState.busy)" @click="rebaseDraft">采用最新基准并保留填写值</el-button></div>
        <p v-if="draftState.lastRead">最新回读版本 v{{ draftState.lastRead.version }} · 已存草稿值：{{ displayValue(draftState.lastRead.draft_value) }}（当前表单输入与编辑基准保持不变）</p>
        <p v-if="showConflict" class="field-help">服务端版本已更新，当前填写的建议值已保留。请先刷新核对版本差异，再点击“采用最新基准并保留填写值”。</p>
        <el-form :disabled="draftLocked" class="dialog-form" label-position="top" @submit.prevent="saveDraft"><el-form-item :label="`建议值${editing.unit !== null && editing.unit !== undefined ? `（${configurationMetadataLabel(editing.unit, '单位')}）` : ''}`" :error="validationError">
          <el-select v-if="kind === 'boolean'" v-model="proposed" placeholder="请选择布尔值"><el-option label="是（true）" :value="true" /><el-option label="否（false）" :value="false" /></el-select>
          <el-input v-else v-model="proposed" :type="kind === 'json' ? 'textarea' : 'text'" :rows="kind === 'json' ? 7 : 1" :placeholder="kind === 'number' ? '请输入数值' : kind === 'json' ? '请输入合法 JSON 结构' : '请输入建议值'" />
        </el-form-item><p v-if="editing.allowed_range !== null" class="field-help">取值范围：{{ displayValue(editing.allowed_range) }}</p><p v-if="editing.notes !== null && editing.notes !== undefined" class="field-help">{{ configurationMetadataLabel(editing.notes, '登记说明') }}</p></el-form>
      </template><template #footer><el-button :disabled="draftUnresolved" @click="editingOpen = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="draftLocked" @click="saveDraft">保存草稿</el-button></template>
    </el-dialog>

  </section>
</template>

<style>
.configuration-register{overflow:hidden}
.configuration-register-heading{padding:16px 20px 0}
.configuration-register-heading h2{margin:0;font-size:15px}
.configuration-register .feature-tabs{padding:0 20px}
.configuration-register .feature-tabs button{appearance:none;background:transparent;border:0;cursor:pointer;min-height:46px;padding:0 16px;font:inherit;font-size:13px;color:#6e8099;border-bottom:2px solid transparent}
.configuration-register .feature-tabs button.active{color:#3974b8;border-bottom-color:#3974b8}
.configuration-register .feature-tabs button span{font-size:11px;background:#eef4fc;border-radius:3px;padding:2px 5px;margin-left:5px}
.configuration-summary.scope-card{border:0;background:transparent;padding:0;gap:12px}
.configuration-summary.scope-card>div{padding:15px 18px;background:#fff;border:1px solid #e4eaf2;border-radius:6px}
.configuration-summary.scope-card .configuration-pending-label{font-size:20px}
.configuration-summary.scope-card small{font-size:11px;color:#8a97a8}
.configuration-filters{display:grid;grid-template-columns:minmax(180px,1.5fr) minmax(130px,1fr) minmax(150px,1fr) minmax(150px,1fr) auto;align-items:end;gap:12px;padding:12px 20px 0}
.configuration-filters .el-form-item{margin-bottom:0}
.configuration-filters .el-select{width:100%}
.configuration-filters>.el-button{margin-bottom:1px}
.configuration-filter-count{font-size:12px;color:#64748b;margin:10px 20px 14px}
.configuration-filter-warning{font-size:12px;color:#8d611e;margin:0 20px 18px;overflow-wrap:anywhere}
.configuration-comparison-drawer{max-width:100vw}
.configuration-comparison-key{overflow-wrap:anywhere;font-size:12px;margin:0 0 16px}
.configuration-diagnostic-link{min-height:36px}
.configuration-row-actions{display:flex;flex-wrap:wrap;align-items:center;gap:2px 12px;min-width:236px}.configuration-row-actions a{display:inline-flex;align-items:center;min-height:28px;font-size:12px;color:var(--el-color-primary);text-decoration:none}.configuration-row-actions a:hover{text-decoration:underline}.configuration-page .configuration-row-actions .configuration-diagnostic-link{margin:0;font-size:12px}
.configuration-row-actions .el-button+.el-button{margin-left:0}
.configuration-page .configuration-row-actions .el-button{font-size:12px;min-height:28px}
.configuration-register .cell-sub{font-size:11px}
.configuration-register .configuration-name{font-size:13px;font-weight:500}
.configuration-comparison-loading{margin-top:24px}
.configuration-comparison-selector .el-select{width:100%}
.configuration-comparison-facts pre,.configuration-difference-value{white-space:pre-wrap;overflow-wrap:anywhere;font-family:var(--el-font-family);font-size:12px;line-height:1.6;margin:0}
.configuration-comparison-facts .el-descriptions__label{width:125px}
@media(max-width:900px){.configuration-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.configuration-filters>.el-button{justify-self:start}}
@media(max-width:650px){.configuration-register .feature-tabs{padding:0 8px;overflow-x:auto}.configuration-register .feature-tabs button{white-space:nowrap}.configuration-register-heading{padding:14px 14px 0}.configuration-page .configuration-row-actions .el-button{min-height:36px}}
@media(max-width:480px){.configuration-filters{grid-template-columns:minmax(0,1fr);padding:16px 14px 0}.configuration-filter-count{margin:14px}.configuration-comparison-facts .el-descriptions__label{width:100px}.configuration-comparison-drawer .el-drawer__footer{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.configuration-comparison-drawer .el-drawer__footer .el-button{margin-left:0}.configuration-diagnostic-link{min-height:44px}}
</style>
