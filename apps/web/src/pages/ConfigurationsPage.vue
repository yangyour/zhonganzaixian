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
const publishing = ref<Configuration | null>(null)
const publishOpen = computed({ get: () => Boolean(publishing.value), set: value => { if (!value) publishing.value = null } })
const publishError = ref<unknown>(null)
const checking = ref(false)
function openPublish(item: Configuration) { closeDiagnostic(); publishing.value = item; publishError.value = null }
async function publish() {
  if (!publishing.value) return
  checking.value = true; publishError.value = null
  try {
    await api.request(`/configurations/${encodeURIComponent(publishing.value.key)}/publish`, { method: 'POST', body: { expected_version: publishing.value.version }, signal: controller.signal })
    // This local API currently always blocks publication. If the contract changes, re-read facts.
    await load(); publishing.value = null
  } catch (cause) { if (!isCancelled(cause)) publishError.value = cause }
  finally { checking.value = false }
}
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
  return Boolean(editing.value || publishing.value || saving.value || checking.value
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
  <section>
    <div class="page-heading"><div><span class="eyebrow">ADMINISTRATION / CONFIGURATION</span><h1>配置确认登记</h1><p>统一记录合同值、客户待确认项与建议草稿。测试环境草稿均未发布为运行配置。</p></div><el-button :loading="loading" @click="load">刷新登记表</el-button></div>
    <div class="calendar-entry"><el-button :disabled="!can('configurations:read') || switchingTenant" @click="calendarOpen = true">工作日历草稿查询与试算</el-button></div>
    <WorkingCalendarReader v-model="calendarOpen" />
    <el-alert v-if="draftState.receipt" type="success" :closable="false" :title="'设计登记原请求已确认：' + draftState.receipt.key + ' · v' + draftState.receipt.version + '；只是旧建议草稿，未生效'" />
    <div class="scope-card"><div><span class="small-label">登记项</span><strong>{{ countsUnknown ? '—' : rows.length }} <small>项</small></strong></div><div><span class="small-label">待客户确认</span><strong>{{ countsUnknown ? '—' : pendingCount }} <small>项</small></strong></div><div><span class="small-label">草稿版本</span><strong>{{ countsUnknown ? '—' : draftCount }} <small>项</small></strong></div><div class="scope-explanation">运行配置尚未启用。待确认项保留 null，禁止回退为演示默认。</div></div>
    <el-alert class="page-note" type="warning" :closable="false" show-icon title="配置修订支持证据引用与内部审批；证据未核验，客户确认与外部下发仍待接入，内部审批通过后尚未生效。" />
    <div class="panel"><ApiErrorNotice :error="error"><el-button size="small" @click="load">重试</el-button></ApiErrorNotice>
      <el-form class="configuration-filters" label-position="top" @submit.prevent>
        <el-form-item label="配置键（config_key）"><el-input v-model="filters.config_key" clearable placeholder="按配置键查找" aria-label="筛选配置键" /></el-form-item>
        <el-form-item label="关联确认 Q 编号"><el-select v-model="filters.question_id" clearable filterable placeholder="全部 Q 编号" aria-label="筛选关联确认 Q 编号"><el-option v-for="question in questions" :key="question" :value="question" :label="question" /></el-select></el-form-item>
        <el-form-item label="登记作用域"><el-select v-model="filters.registered_scope" clearable placeholder="全部登记作用域" aria-label="筛选登记作用域"><el-option v-for="scope in registeredScopes" :key="scope" :value="scope" :label="scope" /></el-select></el-form-item>
        <el-form-item label="确认状态"><el-checkbox v-model="filters.pending_only" aria-label="仅显示待客户确认">仅待客户确认</el-checkbox></el-form-item>
        <el-button @click="resetFilters">重置筛选</el-button>
      </el-form>
      <p class="configuration-filter-count" aria-live="polite">{{ countsUnknown ? '登记项数量等待读取' : `匹配 ${filteredRows.length} / ${rows.length} 项登记配置` }} · 在本次读取的登记表内组合筛选。</p>
      <p v-if="!countsUnknown && questionIssues.cannotConfirmMatch" class="configuration-filter-warning" role="status">当前其他筛选下：{{ questionIssues.missing }} 项 Q 信息缺失，{{ questionIssues.invalid }} 项格式异常。{{ filters.question_id ? '这些行无法确认与所选 Q 匹配；清除 Q 筛选后可查看并查询诊断。' : '这些行仍保留供查看和诊断；Q 筛选无法确认它们的关联。' }}</p>
      <AppTable :error="error" :rows="filteredRows" :loading="loading" row-key="key" :empty-text="error ? '登记表加载失败，请重试' : rows.length ? '暂无符合筛选条件的登记项' : '暂无配置登记项'">
      <el-table-column label="配置项" min-width="280"><template #default="{ row }"><strong class="configuration-name">{{ configurationMetadataLabel(row.description, '配置描述') }}</strong><span class="cell-sub mono">{{ row.key }}</span></template></el-table-column>
      <el-table-column label="登记作用域 / 类型" min-width="145"><template #default="{ row }">{{ configurationMetadataLabel(row.scope, '作用域') }}<span class="cell-sub">{{ configurationMetadataLabel(row.type, '类型') }}<template v-if="row.unit !== null && row.unit !== undefined"> · {{ configurationMetadataLabel(row.unit, '单位') }}</template> · 非影响对象清单</span></template></el-table-column>
      <el-table-column label="登记 / 合同值" min-width="150"><template #default="{ row }"><span :class="{ muted: row.registered_value === null }">{{ displayValue(row.registered_value) }}</span><span class="cell-sub">{{ configurationMetadataLabel(row.source, '来源') }} · 未运行激活</span></template></el-table-column>
      <el-table-column label="确认状态" min-width="140"><template #default="{ row }"><el-tag :type="row.decision_status === 'pending' ? 'warning' : 'info'">{{ row.decision_status === 'pending' ? '待客户确认' : row.decision_status === 'contract_fixed' ? '合同有据' : row.decision_status === 'confirmed' ? '已确认' : configurationMetadataLabel(row.decision_status, '确认状态') }}</el-tag><span class="cell-sub">{{ configurationQuestionsLabel(row.question_ids) }}</span></template></el-table-column>
      <el-table-column label="建议草稿 / 版本" min-width="180"><template #default="{ row }"><span>{{ row.draft_value === null || row.draft_value === undefined ? '尚未填写' : displayValue(row.draft_value) }}</span><span class="cell-sub">v{{ row.version }} · {{ row.lifecycle_status === 'DRAFT' ? '草稿' : configurationMetadataLabel(row.lifecycle_status, '草稿状态') }}</span></template></el-table-column>
      <el-table-column label="运行生效值" min-width="150"><template #default="{ row }"><span>{{ row.effective_value === undefined ? '生效值未知' : row.effective_value === null ? '尚未生效' : displayValue(row.effective_value) }}</span><span class="cell-sub">以接口生效值为准</span></template></el-table-column>
      <!-- @vue-generic {Configuration} -->
      <el-table-column label="操作" min-width="320" fixed="right"><template #default="{ row }"><div class="configuration-row-actions"><el-button v-if="diagnosticPermitted" type="primary" link class="configuration-diagnostic-link" @click="openDiagnostic(row)">有效性诊断</el-button><el-button type="primary" link @click="openComparison(row)">建议差异</el-button><el-button type="primary" link @click="openRevisions(row)">修订与审批</el-button><PermissionGate capability="configurations:write"><el-button type="primary" link :disabled="row.contract_value_kind === 'fixed_constraint'" @click="openDraft(row)">{{ row.contract_value_kind === 'fixed_constraint' ? '固定约束' : '编辑草稿' }}</el-button><el-button type="primary" link @click="openPublish(row)">发布检查</el-button></PermissionGate></div></template></el-table-column>
    </AppTable><div class="table-footer"><span>客户确认结论与生产许可按文档登记；测试环境草稿不构成正式确认。</span></div></div>
    <ConfigurationRevisions ref="revisionPanel" v-model="revisionsOpen" :configuration="revisionConfiguration" @changed="load" />
    <ConfigurationDiagnosticPanel v-model="diagnosticOpen" :configuration-key="diagnosticKey" />
    <el-drawer v-model="comparisonOpen" title="登记值与建议值差异" size="820px" class="configuration-comparison-drawer" destroy-on-close>
      <p class="mono configuration-comparison-key">{{ comparisonKey }}</p>
      <el-alert type="info" :closable="false" show-icon title="比较本次接口回读的登记值与已保存建议。差异不代表发布、生效或客户确认。" />
      <ApiErrorNotice :error="comparisonError"><el-button size="small" @click="loadComparison">重试读取</el-button></ApiErrorNotice>
      <el-skeleton v-if="comparisonLoading" :rows="7" animated class="configuration-comparison-loading" />
      <template v-else-if="comparisonSnapshot && comparison">
        <div class="config-dialog-context"><strong>{{ configurationMetadataLabel(comparisonSnapshot.configuration.description, '配置描述') }}</strong><p>登记版本 v{{ comparisonSnapshot.configuration.version }} · {{ comparison.decisionStatus === 'pending' ? '待客户确认' : comparison.decisionStatus === 'contract_fixed' ? '合同有据' : comparison.decisionStatus === 'confirmed' ? '已确认' : configurationMetadataLabel(comparison.decisionStatus, '确认状态') }}</p><p>关联确认：{{ configurationQuestionsLabel(comparisonSnapshot.configuration.question_ids) }}</p></div>
        <el-form label-position="top" class="configuration-comparison-selector"><el-form-item label="选择已保存建议来源"><el-select v-model="proposalSelection" aria-label="选择差异比较建议来源"><el-option value="draft" :disabled="!hasDraft" :label="hasDraft ? `登记建议草稿 · v${comparisonSnapshot.configuration.version}` : '登记建议草稿 · 尚未填写'" /><el-option v-for="revision in comparisonSnapshot.revisions" :key="revision.id" :value="revision.id" :label="`${revisionStatusLabel[revision.workflow_status]} · v${revision.version} · ${revision.id}`" /></el-select></el-form-item></el-form>
        <p v-if="selectedRevision" class="field-help">修订 {{ selectedRevision.id }} · {{ revisionStatusLabel[selectedRevision.workflow_status] }}。客户证据未核验（{{ comparison.confirmationStatus }}）；内部审批通过仍未生效，未构成发布许可。</p>
        <el-descriptions :column="1" border class="configuration-comparison-facts">
          <el-descriptions-item label="登记作用域">{{ comparison.registeredScope }}</el-descriptions-item>
          <el-descriptions-item label="登记 / 合同值"><pre>{{ comparisonValue(comparison.registeredValue) }}</pre></el-descriptions-item>
          <el-descriptions-item label="建议值"><pre>{{ comparison.proposalAvailable ? comparisonValue(comparison.proposedValue) : '尚未填写建议值' }}</pre></el-descriptions-item>
          <el-descriptions-item label="运行生效值"><pre>{{ comparison.effectiveValue === undefined ? '接口未提供生效值（未知）' : comparison.effectiveValue === null ? '尚未生效（null）' : comparisonValue(comparison.effectiveValue) }}</pre></el-descriptions-item>
          <el-descriptions-item label="实际影响对象">未知 · 接口尚未返回受影响租户、模板、路由的对象清单或数量。登记作用域是配置目录信息。</el-descriptions-item>
        </el-descriptions>
        <div class="detail-section"><h3>登记值 → 建议值</h3><p class="field-help">路径使用 JSON Pointer；数组按位置比较。字段不存在与 null、0、false 分别显示。</p>
          <el-empty v-if="!comparison.proposalAvailable" description="尚无已保存建议，无法比较" :image-size="70" />
          <el-empty v-else-if="!comparison.differences.length" description="建议值与登记值一致；仍未因此发布或生效" :image-size="70" />
          <AppTable v-else :rows="comparison.differences" row-key="path"><el-table-column label="字段路径 / 变化" min-width="160"><template #default="{ row }"><span class="mono">{{ row.path || '根值（整个值）' }}</span><span class="cell-sub">{{ row.kind === 'added' ? '新增字段' : row.kind === 'removed' ? '移除字段' : '值改变' }}</span></template></el-table-column><el-table-column label="登记值" min-width="180"><template #default="{ row }"><pre class="configuration-difference-value">{{ comparisonValue(row.before, row.before_present) }}</pre></template></el-table-column><el-table-column label="建议值" min-width="180"><template #default="{ row }"><pre class="configuration-difference-value">{{ comparisonValue(row.after, row.after_present) }}</pre></template></el-table-column></AppTable>
        </div>
      </template>
      <template #footer><el-button :loading="comparisonLoading" @click="loadComparison">回读登记与修订</el-button><el-button @click="comparisonOpen = false">关闭</el-button></template>
    </el-drawer>
    <el-dialog v-model="editingOpen" title="编辑旧设计登记建议草稿（非租户运行配置）" width="560px" class="responsive-dialog" :close-on-click-modal="!draftUnresolved" :close-on-press-escape="!draftUnresolved" :show-close="!draftUnresolved" destroy-on-close>
      <template v-if="editing"><div class="config-dialog-context"><strong>{{ configurationMetadataLabel(editing.description, '配置描述') }}</strong><span class="mono">{{ editing.key }}</span><p>当前版本 v{{ editing.version }} · 登记值：{{ displayValue(editing.registered_value) }}</p><p>运行生效：{{ editing.effective_value === null ? '尚未启用' : displayValue(editing.effective_value) }}</p><p>关联确认：{{ configurationQuestionsLabel(editing.question_ids) }}</p></div>
        <el-alert type="info" :closable="false" title="这里只修改旧设计登记建议草稿，独立于当前租户提案与审批；不会修改原登记值、客户确认或运行生效配置。" show-icon />
        <ApiErrorNotice :error="editError" />
        <el-alert v-if="draftUnresolved" type="warning" :closable="false" title="原body/key/配置键/版本已保留，结果未确认。只读刷新不重试、不重定基准，请勿刷新浏览器。" />
        <div class="calendar-actions"><el-button :disabled="saving" @click="draftWriter.refresh()">只读核对最新登记</el-button><el-button v-if="draftUnresolved" type="warning" :disabled="Boolean(draftState.busy)" @click="retryDraft">使用原设计草稿请求重试</el-button><el-button v-if="!draftUnresolved" :disabled="Boolean(draftState.busy)" @click="rebaseDraft">明确采用新基准，保留填写值</el-button></div>
        <p v-if="draftState.lastRead">最近只读所见v{{ draftState.lastRead.version }} · 已存旧建议：{{ displayValue(draftState.lastRead.draft_value) }}；当前填写与固定基准不自动替换</p>
        <p v-if="showConflict" class="field-help">版本已变化，建议值仍保留在表单。请只读核对差异，再明确采用新基准；系统不会自动覆盖或重发。</p>
        <el-form :disabled="draftLocked" class="dialog-form" label-position="top" @submit.prevent="saveDraft"><el-form-item :label="`建议值${editing.unit !== null && editing.unit !== undefined ? `（${configurationMetadataLabel(editing.unit, '单位')}）` : ''}`" :error="validationError">
          <el-select v-if="kind === 'boolean'" v-model="proposed" placeholder="请选择；未选择不等于否"><el-option label="是（true）" :value="true" /><el-option label="否（false）" :value="false" /></el-select>
          <el-input v-else v-model="proposed" :type="kind === 'json' ? 'textarea' : 'text'" :rows="kind === 'json' ? 7 : 1" :placeholder="kind === 'number' ? '请输入数字，本批校验登记类型' : kind === 'json' ? '请输入 JSON，保持字段类型与结构' : '请输入建议值'" />
        </el-form-item><p v-if="editing.allowed_range !== null" class="field-help">登记范围：{{ displayValue(editing.allowed_range) }} · 范围待正式规则核验</p><p v-if="editing.notes !== null && editing.notes !== undefined" class="field-help">{{ configurationMetadataLabel(editing.notes, '登记说明') }}</p></el-form>
      </template><template #footer><el-button :disabled="draftUnresolved" @click="editingOpen = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="draftLocked" @click="saveDraft">保存草稿</el-button></template>
    </el-dialog>
    <el-dialog v-model="publishOpen" title="配置发布条件" width="520px" class="responsive-dialog" :close-on-click-modal="!checking" :show-close="!checking" destroy-on-close>
      <template v-if="publishing"><div class="config-dialog-context"><strong>{{ configurationMetadataLabel(publishing.description, '配置描述') }}</strong><span class="mono">{{ publishing.key }}</span></div><el-alert type="warning" :closable="false" show-icon title="发布等待客户确认与审批。服务端将检查门禁，未满足条件时返回 CONFIG_PENDING。" /><p class="muted">关联确认：{{ configurationQuestionsLabel(publishing.question_ids) }}<br>发布条件：{{ configurationMetadataLabel(publishing.activation_gate, '发布条件') }}</p><ApiErrorNotice :error="publishError" /></template>
      <template #footer><el-button :disabled="checking" @click="publishOpen = false">关闭</el-button><el-button type="primary" plain :loading="checking" @click="publish">检查发布门禁</el-button></template>
    </el-dialog>
  </section>
</template>

<style>
.configuration-filters{display:grid;grid-template-columns:minmax(180px,1.5fr) minmax(130px,1fr) minmax(150px,1fr) minmax(150px,1fr) auto;align-items:end;gap:12px;padding:18px 20px 0}
.configuration-filters .el-form-item{margin-bottom:0}
.configuration-filters .el-select{width:100%}
.configuration-filters>.el-button{margin-bottom:1px}
.configuration-filter-count{font-size:12px;color:#64748b;margin:14px 20px 18px}
.configuration-filter-warning{font-size:12px;color:#8d611e;margin:0 20px 18px;overflow-wrap:anywhere}
.configuration-comparison-drawer{max-width:100vw}
.configuration-comparison-key{overflow-wrap:anywhere;font-size:12px;margin:0 0 16px}
.configuration-diagnostic-link{min-height:36px}
.configuration-row-actions{display:flex;flex-wrap:wrap;gap:6px 12px}.configuration-row-actions .el-button+.el-button{margin-left:0}
.configuration-comparison-loading{margin-top:24px}
.configuration-comparison-selector .el-select{width:100%}
.configuration-comparison-facts pre,.configuration-difference-value{white-space:pre-wrap;overflow-wrap:anywhere;font-family:var(--el-font-family);font-size:12px;line-height:1.6;margin:0}
.configuration-comparison-facts .el-descriptions__label{width:125px}
@media(max-width:900px){.configuration-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.configuration-filters>.el-button{justify-self:start}}
@media(max-width:480px){.configuration-filters{grid-template-columns:minmax(0,1fr);padding:16px 14px 0}.configuration-filter-count{margin:14px}.configuration-comparison-facts .el-descriptions__label{width:100px}.configuration-comparison-drawer .el-drawer__footer{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.configuration-comparison-drawer .el-drawer__footer .el-button{margin-left:0}.configuration-diagnostic-link{min-height:44px}}
</style>
