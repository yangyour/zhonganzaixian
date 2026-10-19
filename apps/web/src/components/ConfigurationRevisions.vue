<script setup lang="ts">
import { ElMessageBox, ElTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createConfigurationRevisionWriter, emptyRevisionWriteState, readConfigurationRevision, type RevisionWriteState, type RevisionWriteContext } from '../utils/configurationRevisionWriter'
import { ElEmpty, ElRadio, ElRadioGroup, ElTimeline, ElTimelineItem } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api, ApiError, isCancelled } from '../api/client'
import type { Configuration, ConfigurationRevision } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { displayTime, displayValue } from '../utils/display'
import { editorKind } from '../utils/configuration'
import { parseRevisionForm, revisionActions, revisionActionLabel, revisionStatusLabel, RevisionValidationError, validateRevisionComment, type RevisionForm } from '../utils/configurationRevision'
import AppTable from './AppTable.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = defineProps<{ modelValue: boolean; configuration: Configuration | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; changed: [] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const writer = computed(() => session.value?.user.role === 'padmin' && can('configurations:write') && !switchingTenant.value)
const mayCreate = computed(() => writer.value && props.configuration?.contract_value_kind !== 'fixed_constraint')
const kind = computed(() => props.configuration ? editorKind(props.configuration) : 'text')
const rows = ref<ConfigurationRevision[]>([])
const listLoading = ref(false)
const listError = ref<unknown>(null)
let listController: AbortController | undefined
let listGeneration = 0
const detailOpen = ref(false)
const detailId = ref('')
const detail = ref<ConfigurationRevision | null>(null)
const detailLoading = ref(false)
const detailError = ref<unknown>(null)
let detailController: AbortController | undefined
let detailGeneration = 0
let writeGeneration = 0
const createOpen = ref(false)
const commandState = shallowRef<RevisionWriteState>(emptyRevisionWriteState())
const commands = createConfigurationRevisionWriter(api, value => { commandState.value = value })
const unresolved = computed(() => commandState.value.busy || commandState.value.attempt?.state === 'uncertain')
const creating = computed(() => commandState.value.busy && commandState.value.attempt?.action === 'create')
const createError = ref<unknown>(null)
const fieldErrors = ref<Record<string, string>>({})
const form = reactive<RevisionForm>({ proposed: '', reason: '', evidence_refs: [] })
const blankEvidence = () => ({ reference: '', title: '', sha256: '' })
const actionType = ref<'submit' | 'withdraw' | 'review' | null>(null)
const actionOpen = computed({ get: () => actionType.value !== null, set: value => { if (!value) actionType.value = null } })
const actionExpectedVersion = ref(0)
const actionComment = ref('')
const reviewDecision = ref<'approve' | 'reject' | ''>('')
const actionValidation = ref('')
const actionError = ref<unknown>(null)
const acting = computed(() => commandState.value.busy && commandState.value.attempt?.action !== 'create')
const writing = computed(() => creating.value || acting.value)
const unsaved = computed(() => commandState.value.attempt?.state !== 'accepted' && Boolean(
  createOpen.value && (form.proposed !== '' || form.reason !== '' || form.evidence_refs.some(item => item.reference !== '' || item.title !== '' || item.sha256 !== ''))
  || actionOpen.value && (actionComment.value !== '' || reviewDecision.value !== '')))
let disposed = false, confirmingLeave = false
const leaveContextKey = () => JSON.stringify([props.configuration?.key, contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, writer.value, switchingTenant.value])
const leaveInputKey = () => JSON.stringify([createOpen.value, form, actionType.value, actionExpectedVersion.value, actionComment.value, reviewDecision.value])
function canCloseForReadPanel() { return !createOpen.value && !actionOpen.value && !unresolved.value }
defineExpose({ canCloseForReadPanel, hasUnresolved: commands.unresolved })
function currentContext(): RevisionWriteContext | null { const value = session.value; return value && props.configuration ? { tenantId: value.tenant.id, actorId: value.user.id, permissionVersion: value.permission_version, configurationKey: props.configuration.key } : null }
const actions = computed(() => detail.value ? revisionActions(detail.value, session.value?.user, can('configurations:write')) : { submit: false, withdraw: false, review: false, waitingForReviewer: false })
const actionAllowed = computed(() => Boolean(actionType.value && actions.value[actionType.value]))
const actionConflict = computed(() => actionError.value instanceof ApiError && actionError.value.status === 409)
const actionTitle = computed(() => ({ submit: '提交内部审批', withdraw: '撤回修订提案', review: '审核修订提案' })[actionType.value || 'submit'])
const statusType = (status: string): 'success' | 'warning' | 'danger' | 'info' => status === 'APPROVED' ? 'success' : status === 'IN_REVIEW' ? 'warning' : status === 'REJECTED' ? 'danger' : 'info'

function reset() {
  listController?.abort(); detailController?.abort()
  ++listGeneration; ++detailGeneration; ++writeGeneration
  commands.reset(writer.value ? currentContext() : null)
  rows.value = []; listLoading.value = false; listError.value = null
  detailOpen.value = false; detailId.value = ''; detail.value = null; detailLoading.value = false; detailError.value = null
  createOpen.value = false; createError.value = null; fieldErrors.value = {}
  Object.assign(form, { proposed: '', reason: '', evidence_refs: [] })
  actionType.value = null; actionComment.value = ''; reviewDecision.value = ''; actionError.value = null; actionValidation.value = ''
}
watch([() => props.configuration?.key, contextGeneration, () => session.value?.tenant.id, () => session.value?.user.id,
  () => session.value?.user.role, () => session.value?.permission_version, () => session.value?.account_version, writer, switchingTenant], () => {
  reset(); if (props.modelValue && props.configuration && !switchingTenant.value) void loadList()
}, { immediate: true, flush: 'sync' })
watch(() => props.modelValue, value => { if (value && props.configuration && !switchingTenant.value) void loadList() })
async function mayLeave() {
  if (commands.unresolved()) { ElMessage.warning('配置提交结果尚未确认，请先完成重试核对'); return false }
  if (!unsaved.value) return true
  if (disposed || confirmingLeave) return false
  const scope = leaveContextKey(), input = leaveInputKey()
  confirmingLeave = true
  try {
    await ElMessageBox.confirm('离开将清除尚未保存的修订提案、证据引用或审核意见，是否继续？', '内容尚未保存', { type: 'warning', confirmButtonText: '放弃并离开', cancelButtonText: '继续编辑' })
    // Do not clear here: another navigation/context guard may reject the transition.
    return !disposed && !commands.unresolved() && scope === leaveContextKey() && input === leaveInputKey()
  } catch { return false } finally { confirmingLeave = false }
}
const unregisterContextGuard = contextTransitionGuards.register(mayLeave)
onBeforeRouteLeave(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (commands.unresolved() || unsaved.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed = true; unregisterContextGuard(); window.removeEventListener('beforeunload', beforeUnload); reset(); commands.dispose() })
watch(detailOpen, value => {
  if (value || unresolved.value) return
  detailController?.abort(); ++detailGeneration
  detail.value = null; detailId.value = ''; detailError.value = null; detailLoading.value = false
  actionType.value = null; actionComment.value = ''; reviewDecision.value = ''; actionError.value = null
})

async function loadList() {
  if (!props.configuration || !props.modelValue) return
  const key = props.configuration.key, context = currentContext()
  if (!context) return
  listController?.abort(); const controller = new AbortController(); listController = controller
  const generation = ++listGeneration
  listLoading.value = true; listError.value = null; rows.value = []
  try {
    const result = await api.request<{ items: ConfigurationRevision[] }>(`/configurations/${encodeURIComponent(key)}/revisions`, { signal: controller.signal })
    if (generation === listGeneration) { if (!Array.isArray(result.items)) throw new Error('修订列表读取异常'); rows.value = result.items.map(value => readConfigurationRevision(value, context)) }
  } catch (cause) { if (!isCancelled(cause) && generation === listGeneration) listError.value = cause }
  finally { if (generation === listGeneration) listLoading.value = false }
}
async function loadDetail(id = detailId.value): Promise<boolean> {
  const context = currentContext()
  if (!id || !context) return false
  detailController?.abort(); const controller = new AbortController(); detailController = controller
  const generation = ++detailGeneration
  detailLoading.value = true; detailError.value = null; detail.value = null
  try {
    const result = await api.request<ConfigurationRevision>(`/configuration-revisions/${encodeURIComponent(id)}`, { signal: controller.signal })
    if (generation !== detailGeneration) return false
    const value = readConfigurationRevision(result, context); if (value.id !== id) throw new Error('修订目标不匹配'); detail.value = value
    return true
  } catch (cause) { if (!isCancelled(cause) && generation === detailGeneration) detailError.value = cause; return false }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function openDetail(id: string) { if (unresolved.value && id !== detailId.value) { ElMessage.warning('当前请求结果尚未确认，请先完成重试核对后再切换记录'); return } detailId.value = id; detailOpen.value = true; void loadDetail(id) }
function openCreate() {
  if (!mayCreate.value || !commands.prepare()) return
  Object.assign(form, { proposed: '', reason: '', evidence_refs: [blankEvidence()] })
  fieldErrors.value = {}; createError.value = null; createOpen.value = true
}
async function accepted() {
  const receipt = commandState.value.receipt, generation = writeGeneration
  if (!receipt) return
  createOpen.value = false; actionType.value = null; detailId.value = receipt.id; detailOpen.value = true; detail.value = receipt
  ElMessage.success('操作已保存（当前为内部提案/审批记录，需在运行配置中心发布后生效）')
  await loadDetail(receipt.id); if (generation !== writeGeneration) return
  await loadList(); if (generation === writeGeneration) emit('changed')
}
async function retryOriginal() {
  if (!writer.value || !unresolved.value || commandState.value.busy) return
  const action = commandState.value.attempt?.action
  if (await commands.retry()) { createError.value = null; actionError.value = null; await accepted() }
  else if (action === 'create') createError.value = commandState.value.error; else actionError.value = commandState.value.error
}
async function createRevision() {
  if (!props.configuration || !mayCreate.value || unresolved.value) return
  let body
  try { body = parseRevisionForm(props.configuration, form); fieldErrors.value = {} }
  catch (cause) { if (cause instanceof RevisionValidationError) fieldErrors.value = cause.fields; return }
  if (commandState.value.attempt && !commands.prepare()) return
  createError.value = null
  try { if (await commands.submit('create', null, body)) await accepted(); else createError.value = commandState.value.error }
  catch (cause) { createError.value = cause }
}
function openAction(type: 'submit' | 'withdraw' | 'review') {
  if (!detail.value || !actions.value[type] || !commands.prepare()) return
  actionType.value = type; actionExpectedVersion.value = detail.value.version
  actionComment.value = ''; reviewDecision.value = ''; actionError.value = null; actionValidation.value = ''
}
async function refreshActionVersion() {
  if (unresolved.value) return
  const type = actionType.value, verified = await loadDetail()
  if (verified && detail.value && actionType.value === type && commands.prepare()) { actionExpectedVersion.value = detail.value.version; actionError.value = null }
}
async function performAction() {
  if (!detail.value || !actionType.value || !actionAllowed.value || unresolved.value || actionConflict.value) return
  if (detail.value.version !== actionExpectedVersion.value) { actionError.value = new ApiError(409, 'VERSION_CONFLICT', '修订记录版本已更新，请先刷新核对最新版本后再操作', '—'); return }
  const type = actionType.value, body: Record<string, unknown> = { expected_version: actionExpectedVersion.value }
  actionValidation.value = ''
  try {
    if (type === 'review') { if (!reviewDecision.value) throw new Error('请选择通过或驳回'); body.decision = reviewDecision.value; body.comment = validateRevisionComment(actionComment.value, '审核意见') }
    if (type === 'withdraw') body.reason = validateRevisionComment(actionComment.value, '撤回理由')
  } catch (cause) { actionValidation.value = (cause as Error).message; return }
  if (commandState.value.attempt && !commands.prepare()) return
  actionError.value = null
  try { if (await commands.submit(type, detail.value, body)) await accepted(); else actionError.value = commandState.value.error }
  catch (cause) { actionError.value = cause }
}

</script>

<template>
  <el-drawer v-model="open" title="修订与审批" size="820px" class="configuration-revisions-drawer" :close-on-click-modal="!unresolved" :close-on-press-escape="!unresolved" :show-close="!unresolved" destroy-on-close>
    <section v-if="unresolved" class="revision-original-request" role="status"><el-alert type="warning" :closable="false" title="提交结果待确认：系统已锁定当前提交内容与版本，请直接点击下方按钮重试确认结果，避免重复新建提交。" /><el-button type="warning" :disabled="commandState.busy || !writer" @click="retryOriginal">重试提交</el-button></section>
    <el-alert v-if="commandState.receipt" type="success" :closable="false" :title="'已保存修订记录：' + commandState.receipt.id + ' · v' + commandState.receipt.version + '（待前往运行配置中心发布后生效）'" />
    <template v-if="configuration">
      <div class="config-dialog-context"><strong>{{ configuration.description }}</strong><span class="mono">{{ configuration.key }}</span><p>当前租户：{{ session?.tenant.name }} · 登记值：{{ displayValue(configuration.registered_value) }}</p></div>
      <el-alert type="info" :closable="false" show-icon title="修订提案与草稿独立保存；内部审批通过后需前往运行配置中心发布生效，客户生产环境确认状态另行核验。" />
      <div class="revision-toolbar"><span class="muted">当前租户修订 · {{ listLoading ? '加载中' : `${rows.length} 项` }}</span><div><el-button :loading="listLoading" :disabled="unresolved" @click="loadList">刷新修订</el-button><el-button v-if="mayCreate" type="primary" :disabled="unresolved" @click="openCreate">创建修订提案</el-button></div></div>
      <el-alert v-if="configuration.contract_value_kind === 'fixed_constraint'" type="warning" :closable="false" title="合同固定约束不接受修订提案。" class="page-note" />
      <ApiErrorNotice :error="listError"><el-button size="small" @click="loadList">重试</el-button></ApiErrorNotice>
      <AppTable :rows="rows" :loading="listLoading" :empty-text="listError ? '修订加载失败，请重试' : '本租户尚无此配置的修订提案'">
        <el-table-column label="提案 / 建议值" min-width="200"><template #default="{ row }"><el-button type="primary" link class="asset-link" @click="openDetail(row.id)">{{ displayValue(row.proposed_value) }}</el-button><span class="cell-sub mono">{{ row.id }}</span><span class="cell-sub">{{ row.reason }}</span></template></el-table-column>
        <el-table-column label="流程状态" min-width="190"><template #default="{ row }"><el-tag :type="statusType(row.workflow_status)">{{ revisionStatusLabel[row.workflow_status as keyof typeof revisionStatusLabel] }}</el-tag><span class="cell-sub">证据待核验 · v{{ row.version }}</span></template></el-table-column>
        <el-table-column label="创建人 / 创建时间" min-width="180"><template #default="{ row }"><span class="mono">{{ row.created_by }}</span><span class="cell-sub">{{ displayTime(row.created_at) }}</span></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button type="primary" link @click="openDetail(row.id)">查看详情</el-button></template></el-table-column>
      </AppTable>
    </template>
    <template #footer><el-button :disabled="unresolved" @click="open = false">关闭</el-button></template>
  </el-drawer>

  <el-drawer v-model="detailOpen" title="修订详情与审批记录" size="680px" class="configuration-revision-detail" append-to-body :close-on-click-modal="!unresolved" :close-on-press-escape="!unresolved" :show-close="!unresolved" destroy-on-close>
    <ApiErrorNotice :error="detailError"><el-button size="small" @click="loadDetail()">重新加载详情</el-button></ApiErrorNotice>
    <el-skeleton v-if="detailLoading" :rows="10" animated />
    <template v-if="detail">
      <div class="detail-title"><span class="eyebrow">本租户配置修订</span><h2>{{ configuration?.description || detail.configuration_key }}</h2><el-tag :type="statusType(detail.workflow_status)">{{ revisionStatusLabel[detail.workflow_status] }}</el-tag></div>
      <el-alert type="warning" :closable="false" show-icon title="外部书面证据待核验（UNVERIFIED）：系统登记外部资料引用标识与 SHA-256 摘要，正式客户确认状态以线下签署结果为准。" />
      <el-descriptions :column="1" border class="asset-descriptions">
        <el-descriptions-item label="修订 ID"><span class="mono">{{ detail.id }}</span></el-descriptions-item>
        <el-descriptions-item label="所属租户"><span class="mono">{{ detail.tenant_id }}</span></el-descriptions-item>
        <el-descriptions-item label="配置项"><span class="mono">{{ detail.configuration_key }}</span></el-descriptions-item>
        <el-descriptions-item label="建议值"><span class="revision-text">{{ displayValue(detail.proposed_value) }}</span></el-descriptions-item>
        <el-descriptions-item label="修订理由"><span class="revision-text">{{ detail.reason }}</span></el-descriptions-item>
        <el-descriptions-item label="生效值">暂未生效 · {{ displayValue(detail.effective_value) }}</el-descriptions-item>
        <el-descriptions-item label="登记结论">{{ detail.decision_status === 'pending' ? '待客户确认' : '合同有据' }} · 证据待核验</el-descriptions-item>
        <el-descriptions-item label="版本 / 来源">v{{ detail.version }} · {{ detail.source }}</el-descriptions-item>
        <el-descriptions-item label="创建人"><span class="mono">{{ detail.created_by }}</span></el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ displayTime(detail.created_at) }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ displayTime(detail.updated_at) }}</el-descriptions-item>
        <el-descriptions-item label="审批人"><span v-if="detail.reviewed_by" class="mono">{{ detail.reviewed_by }}</span><span v-else>尚无审批记录</span></el-descriptions-item>
        <el-descriptions-item label="审批时间">{{ displayTime(detail.reviewed_at) }}</el-descriptions-item>
        <el-descriptions-item label="审核意见"><span class="revision-text">{{ detail.review_comment || '—' }}</span></el-descriptions-item>
      </el-descriptions>
      <div class="detail-section"><h3>证据引用</h3><div v-for="(item, index) in detail.evidence_refs" :key="`${item.reference}:${index}`" class="evidence-card"><strong>{{ index + 1 }}. {{ item.title }}</strong><span class="mono">{{ item.reference }}</span><span class="small-label">SHA-256</span><span class="mono">{{ item.sha256 }}</span></div></div>
      <div class="detail-section"><h3>内部审批</h3><p v-if="actions.waitingForReviewer" class="field-help">等待另一位平台管理员审批。创建者不能审核自己的提案。</p><p v-else-if="detail.workflow_status === 'APPROVED'" class="field-help">内部审批已通过（当前提案尚未直接生效，请前往运行配置中心发布运行版本；客户生产确认状态另行核验）。</p><p v-else-if="detail.workflow_status === 'REJECTED' || detail.workflow_status === 'WITHDRAWN'" class="field-help">此提案流程已结束。如需调整，请创建新修订提案。</p><p v-else class="field-help">提案仅支持由创建人提交或撤回，并由另一位平台管理员执行双人复核审批。</p><div class="revision-action-buttons"><el-button v-if="actions.submit" type="primary" :disabled="unresolved" @click="openAction('submit')">提交内部审批</el-button><el-button v-if="actions.review" type="primary" :disabled="unresolved" @click="openAction('review')">审核提案</el-button><el-button v-if="actions.withdraw" type="danger" plain :disabled="unresolved" @click="openAction('withdraw')">撤回提案</el-button></div></div>
      <div class="detail-section"><h3>流程历史</h3><el-empty v-if="!detail.history.length" description="暂无流程历史" :image-size="60" /><el-timeline v-else class="revision-history"><el-timeline-item v-for="(item, index) in detail.history" :key="`${item.occurred_at}:${index}`" :timestamp="displayTime(item.occurred_at)" placement="top"><strong>{{ revisionActionLabel[item.action] }}</strong><p class="mono">{{ item.actor_id }}</p><p>{{ item.from_status ? revisionStatusLabel[item.from_status] : '新提案' }} → {{ revisionStatusLabel[item.to_status] }}</p><p v-if="item.comment" class="revision-text">{{ item.comment }}</p></el-timeline-item></el-timeline></div>
    </template>
    <template #footer><el-button :disabled="writing || detailLoading" @click="loadDetail()">刷新详情</el-button><el-button :disabled="unresolved" @click="detailOpen = false">关闭详情</el-button></template>
  </el-drawer>

  <el-dialog v-model="createOpen" title="创建配置修订提案" width="650px" class="responsive-dialog configuration-revision-dialog" append-to-body :close-on-click-modal="!unresolved" :close-on-press-escape="!unresolved" :show-close="!unresolved" destroy-on-close>
    <div v-if="configuration" class="config-dialog-context"><strong>{{ configuration.description }}</strong><span class="mono">{{ configuration.key }}</span><p>当前登记值：{{ displayValue(configuration.registered_value) }}（待确认项保持未生效状态）。</p></div>
    <el-alert type="info" :closable="false" show-icon title="登记外部书面依据引用与内容 SHA-256 摘要（独立于原建议草稿保存，审批通过后可在运行配置中心发布）。" />
    <ApiErrorNotice :error="createError" />
    <el-alert v-if="unresolved" type="warning" :closable="false" title="提交结果待确认，当前填写内容与版本已锁定，请点击重试提交确认结果。" /><el-button v-if="unresolved" type="warning" :disabled="commandState.busy || !writer" @click="retryOriginal">重试提交</el-button>
    <el-form :disabled="unresolved" label-position="top" class="dialog-form" @submit.prevent="createRevision">
      <el-form-item :label="`建议值${configuration?.unit ? `（${configuration.unit}）` : ''}`" :error="fieldErrors.proposed">
        <el-select v-if="kind === 'boolean'" v-model="form.proposed" placeholder="请选择布尔值" aria-label="修订建议值"><el-option label="是（true）" :value="true" /><el-option label="否（false）" :value="false" /></el-select>
        <el-input v-else v-model="form.proposed" :type="kind === 'json' ? 'textarea' : 'text'" :rows="kind === 'json' ? 5 : 1" :placeholder="kind === 'number' ? '请填写有效数字' : kind === 'json' ? '请填写合法 JSON 格式内容' : '请填写建议值'" aria-label="修订建议值" />
      </el-form-item>
      <el-form-item label="修订理由（1–500 个字符）" :error="fieldErrors.reason"><el-input v-model="form.reason" type="textarea" :rows="3" placeholder="说明修改依据与目的，请勿填写密码或 token" aria-label="修订理由" /></el-form-item>
      <div class="revision-evidence-heading"><h3>证据引用（1–5 项）</h3><el-button size="small" :disabled="form.evidence_refs.length >= 5 || unresolved" @click="form.evidence_refs.push(blankEvidence())">添加证据引用</el-button></div>
      <p v-if="fieldErrors.evidence_refs" class="revision-validation" role="alert">{{ fieldErrors.evidence_refs }}</p>
      <div v-for="(item, index) in form.evidence_refs" :key="index" class="evidence-form-card">
        <div class="revision-evidence-heading"><strong>证据 {{ index + 1 }}</strong><el-button type="danger" link :disabled="form.evidence_refs.length <= 1 || unresolved" :aria-label="`移除证据 ${index + 1}`" @click="form.evidence_refs.splice(index, 1)">移除</el-button></div>
        <el-form-item label="资料引用" :error="fieldErrors[`evidence_refs.${index}.reference`]"><el-input v-model="item.reference" placeholder="document-ref:书面资料的ASCII标识" :aria-label="`证据 ${index + 1} 资料引用`" /><span class="field-help">格式为 document-ref: 后接字母、数字、点、下划线、冒号或短横线（不超过 160 字符）。请勿填写 URL、本地路径或密钥信息。</span></el-form-item>
        <el-form-item label="证据标题（1–200 个字符）" :error="fieldErrors[`evidence_refs.${index}.title`]"><el-input v-model="item.title" placeholder="外部书面资料的标题" :aria-label="`证据 ${index + 1} 标题`" /></el-form-item>
        <el-form-item label="内容 SHA-256（64 位十六进制）" :error="fieldErrors[`evidence_refs.${index}.sha256`]"><el-input v-model="item.sha256" placeholder="填写对应文档内容的 SHA-256" :aria-label="`证据 ${index + 1} 内容哈希`" /></el-form-item>
      </div>
    </el-form>
    <template #footer><el-button :disabled="unresolved" @click="createOpen = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="unresolved" @click="createRevision">保存修订提案</el-button></template>
  </el-dialog>

  <el-dialog v-model="actionOpen" :title="actionTitle" width="540px" class="responsive-dialog" append-to-body :close-on-click-modal="!unresolved" :close-on-press-escape="!unresolved" :show-close="!unresolved" destroy-on-close>
    <p class="field-help">操作基准版本 v{{ actionExpectedVersion }} · 当前最新版本 {{ detail ? `v${detail.version}` : '读取中…' }}</p>
    <el-alert v-if="actionType === 'submit'" type="info" :closable="false" show-icon title="提交后等待另一位平台管理员内部审批；证据仍未核验，配置尚未生效。" />
    <el-alert v-if="actionType === 'review'" type="warning" :closable="false" show-icon title="审核结果仅作为内部复核记录；审批通过后可前往运行配置中心发布测试运行版本，客户生产环境确认另行核验。" />
    <ApiErrorNotice :error="actionError" />
    <el-alert v-if="unresolved" type="warning" :closable="false" title="提交结果待确认，当前填写内容与版本已锁定，请点击重试提交确认结果。" /><el-button v-if="unresolved" type="warning" :disabled="commandState.busy || !writer" @click="retryOriginal">重试提交</el-button>
    <template v-if="actionConflict"><p class="field-help">当前修订版本已更新，系统已保留您填写的意见。请先加载最新版本并核对变更内容后，再决定是否继续提交。</p><el-button :loading="detailLoading" @click="refreshActionVersion">加载最新版本（保留已填内容）</el-button></template>
    <ApiErrorNotice :error="detailError" />
    <el-alert v-if="detail && !actionAllowed" type="warning" :closable="false" title="当前修订状态或授权不允许此动作，请关闭并查看最新详情。" />
    <el-form :disabled="unresolved" class="dialog-form" label-position="top" @submit.prevent="performAction">
      <el-form-item v-if="actionType === 'review'" label="内部审核结论"><el-radio-group v-model="reviewDecision" aria-label="内部审核结论"><el-radio value="approve">通过</el-radio><el-radio value="reject">驳回</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="actionType === 'review' || actionType === 'withdraw'" :label="actionType === 'review' ? '审核意见（必填，1–500 个字符）' : '撤回理由（必填，1–500 个字符）'" :error="actionValidation"><el-input v-model="actionComment" type="textarea" :rows="4" :aria-label="actionType === 'review' ? '审核意见' : '撤回理由'" placeholder="请说明理由，请勿填写密码或 token" /></el-form-item>
      <p v-else-if="actionValidation" class="revision-validation" role="alert">{{ actionValidation }}</p>
    </el-form>
    <template #footer><el-button :disabled="unresolved" @click="actionOpen = false">取消</el-button><el-button type="primary" :loading="acting" :disabled="unresolved || !actionAllowed || actionConflict || detailLoading" @click="performAction">{{ actionType === 'review' ? '提交审核结论' : actionType === 'withdraw' ? '确认撤回' : '提交内部审批' }}</el-button></template>
  </el-dialog>
</template>

<style>
.configuration-revisions-drawer,.configuration-revision-detail{max-width:100vw}
.revision-original-request{display:flex;flex-direction:column;align-items:flex-start;gap:10px;margin-bottom:14px}
.config-dialog-context{padding:12px 14px;background:#f6f9fc;border:1px solid #e5edf5;border-radius:6px;margin-bottom:14px;font-size:12px}
.config-dialog-context strong{display:block;font-size:13px;margin-bottom:4px}
.config-dialog-context p{margin:6px 0 0;color:#52667f}
.revision-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:22px 0 18px;font-size:12px}
.revision-toolbar>div,.revision-action-buttons{display:flex;flex-wrap:wrap;gap:8px}
.revision-toolbar .el-button,.revision-action-buttons .el-button{margin-left:0}
.revision-evidence-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}
.revision-evidence-heading h3{margin:0}
.evidence-card,.evidence-form-card{border:1px solid #e5ecf5;background:#f8fafd;border-radius:6px;padding:16px;margin-bottom:14px}
.evidence-card{display:flex;flex-direction:column;gap:9px;font-size:12px}
.evidence-card strong{overflow-wrap:anywhere}
.evidence-card .small-label{margin-top:3px}
.evidence-form-card .el-form-item:last-child{margin-bottom:4px}
.configuration-revision-dialog .api-error{margin-top:16px}
.revision-text{white-space:pre-wrap;overflow-wrap:anywhere}
.revision-validation{font-size:12px;color:var(--el-color-danger)}
.revision-history{padding-left:4px;margin-top:20px}
.revision-history p{font-size:12px;margin:8px 0}
.revision-history strong{font-size:13px}
@media(max-width:760px){
  .revision-toolbar{align-items:flex-start;flex-direction:column}
  .revision-toolbar>div{width:100%;justify-content:space-between}
  .configuration-revisions-drawer .el-table .cell{padding:0 10px}
  .configuration-revision-detail .el-drawer__footer{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px;padding:14px 20px}
  .configuration-revision-detail .el-drawer__footer .el-button{margin-left:0}
  .revision-evidence-heading{align-items:flex-start;gap:8px}
  .evidence-card,.evidence-form-card{padding:13px}
  .revision-history p{font-size:11px}
}
</style>
