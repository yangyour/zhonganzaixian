<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElEmpty, ElMessage, ElRadio, ElRadioGroup, ElTimeline, ElTimelineItem } from 'element-plus'
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
const writer = computed(() => session.value?.user.role === 'padmin' && can('configurations:write'))
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
let writeController: AbortController | undefined
let writeGeneration = 0
const createOpen = ref(false)
const creating = ref(false)
const createError = ref<unknown>(null)
const fieldErrors = ref<Record<string, string>>({})
const form = reactive<RevisionForm>({ proposed: '', reason: '', evidence_refs: [] })
const blankEvidence = () => ({ reference: '', title: '', sha256: '' })
let createAttempt: { payload: string; key: string } | null = null
const actionType = ref<'submit' | 'withdraw' | 'review' | null>(null)
const actionOpen = computed({ get: () => actionType.value !== null, set: value => { if (!value) actionType.value = null } })
const actionExpectedVersion = ref(0)
const actionComment = ref('')
const reviewDecision = ref<'approve' | 'reject' | ''>('')
const actionValidation = ref('')
const actionError = ref<unknown>(null)
const acting = ref(false)
let actionAttempt: { payload: string; key: string } | null = null
const writing = computed(() => creating.value || acting.value)
function canCloseForReadPanel() {
  const uncertain = (error: unknown) => error instanceof ApiError && (error.status === 0 || error.status >= 500 || error.code === 'INVALID_RESPONSE')
  return !createOpen.value && !actionOpen.value && !writing.value
    && !(createAttempt && uncertain(createError.value)) && !(actionAttempt && uncertain(actionError.value))
}
defineExpose({ canCloseForReadPanel })
const actions = computed(() => detail.value ? revisionActions(detail.value, session.value?.user, can('configurations:write')) : { submit: false, withdraw: false, review: false, waitingForReviewer: false })
const actionAllowed = computed(() => Boolean(actionType.value && actions.value[actionType.value]))
const actionConflict = computed(() => actionError.value instanceof ApiError && actionError.value.status === 409)
const actionTitle = computed(() => ({ submit: '提交内部审批', withdraw: '撤回修订提案', review: '审核修订提案' })[actionType.value || 'submit'])
const statusType = (status: string): 'success' | 'warning' | 'danger' | 'info' => status === 'APPROVED' ? 'success' : status === 'IN_REVIEW' ? 'warning' : status === 'REJECTED' ? 'danger' : 'info'

function reset() {
  listController?.abort(); detailController?.abort(); writeController?.abort()
  ++listGeneration; ++detailGeneration; ++writeGeneration
  rows.value = []; listLoading.value = false; listError.value = null
  detailOpen.value = false; detailId.value = ''; detail.value = null; detailLoading.value = false; detailError.value = null
  createOpen.value = false; creating.value = false; createError.value = null; fieldErrors.value = {}
  Object.assign(form, { proposed: '', reason: '', evidence_refs: [] }); createAttempt = null
  actionType.value = null; actionComment.value = ''; reviewDecision.value = ''; actionError.value = null; actionValidation.value = ''; acting.value = false; actionAttempt = null
}
watch(() => [props.modelValue, props.configuration?.key, contextGeneration.value], () => {
  reset()
  if (props.modelValue && props.configuration && !switchingTenant.value) void loadList()
}, { immediate: true })
onUnmounted(reset)
watch(detailOpen, value => {
  if (value) return
  detailController?.abort(); ++detailGeneration
  detail.value = null; detailId.value = ''; detailError.value = null; detailLoading.value = false
  actionType.value = null; actionComment.value = ''; reviewDecision.value = ''; actionError.value = null
})

async function loadList() {
  if (!props.configuration || !props.modelValue) return
  const key = props.configuration.key
  listController?.abort(); const controller = new AbortController(); listController = controller
  const generation = ++listGeneration
  listLoading.value = true; listError.value = null; rows.value = []
  try {
    const result = await api.request<{ items: ConfigurationRevision[] }>(`/configurations/${encodeURIComponent(key)}/revisions`, { signal: controller.signal })
    if (generation === listGeneration) rows.value = result.items
  } catch (cause) { if (!isCancelled(cause) && generation === listGeneration) listError.value = cause }
  finally { if (generation === listGeneration) listLoading.value = false }
}
async function loadDetail(id = detailId.value): Promise<boolean> {
  if (!id) return false
  detailController?.abort(); const controller = new AbortController(); detailController = controller
  const generation = ++detailGeneration
  detailLoading.value = true; detailError.value = null; detail.value = null
  try {
    const result = await api.request<ConfigurationRevision>(`/configuration-revisions/${encodeURIComponent(id)}`, { signal: controller.signal })
    if (generation !== detailGeneration) return false
    detail.value = result
    return true
  } catch (cause) { if (!isCancelled(cause) && generation === detailGeneration) detailError.value = cause; return false }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function openDetail(id: string) { detailId.value = id; detailOpen.value = true; void loadDetail(id) }
function openCreate() {
  if (!mayCreate.value) return
  Object.assign(form, { proposed: '', reason: '', evidence_refs: [blankEvidence()] })
  fieldErrors.value = {}; createError.value = null; createAttempt = null; createOpen.value = true
}
async function createRevision() {
  if (!props.configuration || !mayCreate.value || creating.value) return
  let body
  try { body = parseRevisionForm(props.configuration, form); fieldErrors.value = {} }
  catch (cause) { if (cause instanceof RevisionValidationError) fieldErrors.value = cause.fields; return }
  const payload = JSON.stringify(body)
  if (!createAttempt || createAttempt.payload !== payload) createAttempt = { payload, key: crypto.randomUUID() }
  writeController?.abort(); const controller = new AbortController(); writeController = controller
  const generation = ++writeGeneration
  creating.value = true; createError.value = null
  try {
    const created = await api.request<ConfigurationRevision>(`/configurations/${encodeURIComponent(props.configuration.key)}/revisions`, { method: 'POST', body, signal: controller.signal, idempotencyKey: createAttempt.key })
    if (generation !== writeGeneration) return
    createOpen.value = false; detailId.value = created.id; detailOpen.value = true
    const verified = await loadDetail(created.id)
    if (generation !== writeGeneration) return
    await loadList()
    if (generation !== writeGeneration) return
    emit('changed')
    if (verified) ElMessage.success('修订提案已保存，尚未提交审批或生效')
  } catch (cause) { if (!isCancelled(cause) && generation === writeGeneration) createError.value = cause }
  finally { if (generation === writeGeneration) creating.value = false }
}
function openAction(type: 'submit' | 'withdraw' | 'review') {
  if (!detail.value || !actions.value[type]) return
  actionType.value = type; actionExpectedVersion.value = detail.value.version
  actionComment.value = ''; reviewDecision.value = ''; actionError.value = null; actionValidation.value = ''; actionAttempt = null
}
async function refreshActionVersion() {
  const type = actionType.value
  const verified = await loadDetail()
  if (verified && detail.value && actionType.value === type) {
    actionExpectedVersion.value = detail.value.version; actionError.value = null; actionAttempt = null
  }
}
async function performAction() {
  if (!detail.value || !actionType.value || !actionAllowed.value || acting.value || actionConflict.value) return
  const type = actionType.value, id = detail.value.id
  const body: Record<string, unknown> = { expected_version: actionExpectedVersion.value }
  actionValidation.value = ''
  try {
    if (type === 'review') {
      if (!reviewDecision.value) throw new Error('请选择通过或驳回')
      body.decision = reviewDecision.value; body.comment = validateRevisionComment(actionComment.value, '审核意见')
    }
    if (type === 'withdraw') body.reason = validateRevisionComment(actionComment.value, '撤回理由')
  } catch (cause) { actionValidation.value = (cause as Error).message; return }
  const payload = JSON.stringify([type, id, body])
  if (!actionAttempt || actionAttempt.payload !== payload) actionAttempt = { payload, key: crypto.randomUUID() }
  writeController?.abort(); const controller = new AbortController(); writeController = controller
  const generation = ++writeGeneration
  acting.value = true; actionError.value = null
  try {
    await api.request(`/configuration-revisions/${encodeURIComponent(id)}/${type}`, { method: 'POST', body, signal: controller.signal, idempotencyKey: actionAttempt.key })
    if (generation !== writeGeneration) return
    actionType.value = null
    const verified = await loadDetail(id)
    if (generation !== writeGeneration) return
    await loadList()
    if (generation !== writeGeneration) return
    emit('changed')
    if (verified) ElMessage.success(type === 'review' ? '内部审核已记录；配置尚未生效' : type === 'submit' ? '已提交，等待另一位平台管理员内部审批' : '提案已撤回；登记值未变更')
  } catch (cause) { if (!isCancelled(cause) && generation === writeGeneration) actionError.value = cause }
  finally { if (generation === writeGeneration) acting.value = false }
}
</script>

<template>
  <el-drawer v-model="open" title="修订与审批" size="820px" class="configuration-revisions-drawer" :close-on-click-modal="!writing" :close-on-press-escape="!writing" :show-close="!writing" destroy-on-close>
    <template v-if="configuration">
      <div class="config-dialog-context"><strong>{{ configuration.description }}</strong><span class="mono">{{ configuration.key }}</span><p>当前租户：{{ session?.tenant.name }} · 登记值：{{ displayValue(configuration.registered_value) }}</p></div>
      <el-alert type="info" :closable="false" show-icon title="提案与原草稿独立保存；内部审批通过后仍未生效，客户确认与发布门禁继续保留。" />
      <div class="revision-toolbar"><span class="muted">当前租户修订 · {{ listLoading ? '加载中' : `${rows.length} 项` }}</span><div><el-button :loading="listLoading" :disabled="writing" @click="loadList">刷新修订</el-button><el-button v-if="mayCreate" type="primary" :disabled="writing" @click="openCreate">创建修订提案</el-button></div></div>
      <el-alert v-if="configuration.contract_value_kind === 'fixed_constraint'" type="warning" :closable="false" title="合同固定约束不接受修订提案。" class="page-note" />
      <ApiErrorNotice :error="listError"><el-button size="small" @click="loadList">重试</el-button></ApiErrorNotice>
      <AppTable :rows="rows" :loading="listLoading" :empty-text="listError ? '修订加载失败，请重试' : '本租户尚无此配置的修订提案'">
        <el-table-column label="提案 / 建议值" min-width="200"><template #default="{ row }"><el-button type="primary" link class="asset-link" @click="openDetail(row.id)">{{ displayValue(row.proposed_value) }}</el-button><span class="cell-sub mono">{{ row.id }}</span><span class="cell-sub">{{ row.reason }}</span></template></el-table-column>
        <el-table-column label="流程状态" min-width="190"><template #default="{ row }"><el-tag :type="statusType(row.workflow_status)">{{ revisionStatusLabel[row.workflow_status as keyof typeof revisionStatusLabel] }}</el-tag><span class="cell-sub">证据未核验 · v{{ row.version }}</span></template></el-table-column>
        <el-table-column label="创建人 / 创建时间" min-width="180"><template #default="{ row }"><span class="mono">{{ row.created_by }}</span><span class="cell-sub">{{ displayTime(row.created_at) }}</span></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button type="primary" link @click="openDetail(row.id)">查看详情</el-button></template></el-table-column>
      </AppTable>
    </template>
    <template #footer><el-button :disabled="writing" @click="open = false">关闭</el-button></template>
  </el-drawer>

  <el-drawer v-model="detailOpen" title="修订详情与审批记录" size="680px" class="configuration-revision-detail" append-to-body :close-on-click-modal="!writing" :close-on-press-escape="!writing" :show-close="!writing" destroy-on-close>
    <ApiErrorNotice :error="detailError"><el-button size="small" @click="loadDetail()">重新回读详情</el-button></ApiErrorNotice>
    <el-skeleton v-if="detailLoading" :rows="10" animated />
    <template v-if="detail">
      <div class="detail-title"><span class="eyebrow">本租户配置修订</span><h2>{{ configuration?.description || detail.configuration_key }}</h2><el-tag :type="statusType(detail.workflow_status)">{{ revisionStatusLabel[detail.workflow_status] }}</el-tag></div>
      <el-alert type="warning" :closable="false" show-icon title="证据未核验（UNVERIFIED）。仅登记外部书面资料引用和内容哈希，不核验资料真实性，不产生客户确认。" />
      <el-descriptions :column="1" border class="asset-descriptions">
        <el-descriptions-item label="修订 ID"><span class="mono">{{ detail.id }}</span></el-descriptions-item>
        <el-descriptions-item label="所属租户"><span class="mono">{{ detail.tenant_id }}</span></el-descriptions-item>
        <el-descriptions-item label="配置项"><span class="mono">{{ detail.configuration_key }}</span></el-descriptions-item>
        <el-descriptions-item label="建议值"><span class="revision-text">{{ displayValue(detail.proposed_value) }}</span></el-descriptions-item>
        <el-descriptions-item label="修订理由"><span class="revision-text">{{ detail.reason }}</span></el-descriptions-item>
        <el-descriptions-item label="生效值">尚未生效 · {{ displayValue(detail.effective_value) }}</el-descriptions-item>
        <el-descriptions-item label="登记结论">{{ detail.decision_status === 'pending' ? '待客户确认' : '合同有据' }} · 证据未核验</el-descriptions-item>
        <el-descriptions-item label="版本 / 来源">v{{ detail.version }} · {{ detail.source }}</el-descriptions-item>
        <el-descriptions-item label="创建人"><span class="mono">{{ detail.created_by }}</span></el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ displayTime(detail.created_at) }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ displayTime(detail.updated_at) }}</el-descriptions-item>
        <el-descriptions-item label="审批人"><span v-if="detail.reviewed_by" class="mono">{{ detail.reviewed_by }}</span><span v-else>尚无审批记录</span></el-descriptions-item>
        <el-descriptions-item label="审批时间">{{ displayTime(detail.reviewed_at) }}</el-descriptions-item>
        <el-descriptions-item label="审核意见"><span class="revision-text">{{ detail.review_comment || '—' }}</span></el-descriptions-item>
      </el-descriptions>
      <div class="detail-section"><h3>证据引用</h3><div v-for="(item, index) in detail.evidence_refs" :key="`${item.reference}:${index}`" class="evidence-card"><strong>{{ index + 1 }}. {{ item.title }}</strong><span class="mono">{{ item.reference }}</span><span class="small-label">SHA-256</span><span class="mono">{{ item.sha256 }}</span></div></div>
      <div class="detail-section"><h3>内部审批</h3><p v-if="actions.waitingForReviewer" class="field-help">等待另一位平台管理员审批。创建者不能审核自己的提案。</p><p v-else-if="detail.workflow_status === 'APPROVED'" class="field-help">内部审批通过，尚未生效。仍须完成客户确认与发布条件，不能据此下发配置。</p><p v-else-if="detail.workflow_status === 'REJECTED' || detail.workflow_status === 'WITHDRAWN'" class="field-help">此提案流程已结束。如需调整，请创建新修订提案。</p><p v-else class="field-help">提交、撤回仅限创建者；审核仅限另一位有写权限的平台管理员。以服务端授权与版本校验为准。</p><div class="revision-action-buttons"><el-button v-if="actions.submit" type="primary" :disabled="writing" @click="openAction('submit')">提交内部审批</el-button><el-button v-if="actions.review" type="primary" :disabled="writing" @click="openAction('review')">审核提案</el-button><el-button v-if="actions.withdraw" type="danger" plain :disabled="writing" @click="openAction('withdraw')">撤回提案</el-button></div></div>
      <div class="detail-section"><h3>流程历史</h3><el-empty v-if="!detail.history.length" description="暂无流程历史" :image-size="60" /><el-timeline v-else class="revision-history"><el-timeline-item v-for="(item, index) in detail.history" :key="`${item.occurred_at}:${index}`" :timestamp="displayTime(item.occurred_at)" placement="top"><strong>{{ revisionActionLabel[item.action] }}</strong><p class="mono">{{ item.actor_id }}</p><p>{{ item.from_status ? revisionStatusLabel[item.from_status] : '新提案' }} → {{ revisionStatusLabel[item.to_status] }}</p><p v-if="item.comment" class="revision-text">{{ item.comment }}</p></el-timeline-item></el-timeline></div>
    </template>
    <template #footer><el-button :disabled="writing || detailLoading" @click="loadDetail()">回读最新详情</el-button><el-button :disabled="writing" @click="detailOpen = false">关闭详情</el-button></template>
  </el-drawer>

  <el-dialog v-model="createOpen" title="创建配置修订提案" width="650px" class="responsive-dialog configuration-revision-dialog" append-to-body :close-on-click-modal="!creating" :close-on-press-escape="!creating" :show-close="!creating" destroy-on-close>
    <div v-if="configuration" class="config-dialog-context"><strong>{{ configuration.description }}</strong><span class="mono">{{ configuration.key }}</span><p>当前登记值：{{ displayValue(configuration.registered_value) }} · 待确认值继续保留 null。</p></div>
    <el-alert type="info" :closable="false" show-icon title="仅登记外部书面资料引用与内容哈希。本页不上传附件、不核验文档真实性、不产生客户确认；原草稿不会被修改。" />
    <ApiErrorNotice :error="createError" />
    <el-form label-position="top" class="dialog-form" @submit.prevent="createRevision">
      <el-form-item :label="`建议值${configuration?.unit ? `（${configuration.unit}）` : ''}`" :error="fieldErrors.proposed">
        <el-select v-if="kind === 'boolean'" v-model="form.proposed" placeholder="请选择；未选择不等于否" aria-label="修订建议值"><el-option label="是（true）" :value="true" /><el-option label="否（false）" :value="false" /></el-select>
        <el-input v-else v-model="form.proposed" :type="kind === 'json' ? 'textarea' : 'text'" :rows="kind === 'json' ? 5 : 1" :placeholder="kind === 'number' ? '请填写数字；空值不能作为 0 保存' : kind === 'json' ? '请填写 JSON；建议值不能为 null' : '请填写建议值'" aria-label="修订建议值" />
      </el-form-item>
      <el-form-item label="修订理由（1–500 个字符）" :error="fieldErrors.reason"><el-input v-model="form.reason" type="textarea" :rows="3" placeholder="说明修改依据与目的，请勿填写密码或 token" aria-label="修订理由" /></el-form-item>
      <div class="revision-evidence-heading"><h3>证据引用（1–5 项）</h3><el-button size="small" :disabled="form.evidence_refs.length >= 5 || creating" @click="form.evidence_refs.push(blankEvidence())">添加证据引用</el-button></div>
      <p v-if="fieldErrors.evidence_refs" class="revision-validation" role="alert">{{ fieldErrors.evidence_refs }}</p>
      <div v-for="(item, index) in form.evidence_refs" :key="index" class="evidence-form-card">
        <div class="revision-evidence-heading"><strong>证据 {{ index + 1 }}</strong><el-button type="danger" link :disabled="form.evidence_refs.length <= 1 || creating" :aria-label="`移除证据 ${index + 1}`" @click="form.evidence_refs.splice(index, 1)">移除</el-button></div>
        <el-form-item label="资料引用" :error="fieldErrors[`evidence_refs.${index}.reference`]"><el-input v-model="item.reference" placeholder="document-ref:书面资料的ASCII标识" :aria-label="`证据 ${index + 1} 资料引用`" /><span class="field-help">仅 document-ref: 后接字母、数字、点、下划线、冒号或短横线，总长不超过 160。不得填写 URL、磁盘路径或秘密。</span></el-form-item>
        <el-form-item label="证据标题（1–200 个字符）" :error="fieldErrors[`evidence_refs.${index}.title`]"><el-input v-model="item.title" placeholder="外部书面资料的标题" :aria-label="`证据 ${index + 1} 标题`" /></el-form-item>
        <el-form-item label="内容 SHA-256（64 位十六进制）" :error="fieldErrors[`evidence_refs.${index}.sha256`]"><el-input v-model="item.sha256" placeholder="填写对应文档内容的 SHA-256" :aria-label="`证据 ${index + 1} 内容哈希`" /></el-form-item>
      </div>
    </el-form>
    <template #footer><el-button :disabled="creating" @click="createOpen = false">取消</el-button><el-button type="primary" :loading="creating" @click="createRevision">保存修订提案</el-button></template>
  </el-dialog>

  <el-dialog v-model="actionOpen" :title="actionTitle" width="540px" class="responsive-dialog" append-to-body :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting" destroy-on-close>
    <p class="field-help">提交版本 v{{ actionExpectedVersion }} · 当前回读版本 {{ detail ? `v${detail.version}` : '等待回读' }}</p>
    <el-alert v-if="actionType === 'submit'" type="info" :closable="false" show-icon title="提交后等待另一位平台管理员内部审批；证据仍未核验，配置尚未生效。" />
    <el-alert v-if="actionType === 'review'" type="warning" :closable="false" show-icon title="审核仅记录内部意见。通过后仍未生效，不表示客户已确认或允许发布。" />
    <ApiErrorNotice :error="actionError" />
    <template v-if="actionConflict"><p class="field-help">版本已变化，已保留你的填写内容。请主动回读最新修订并核对状态、证据与版本，再决定是否提交；系统不会自动覆盖。</p><el-button :loading="detailLoading" @click="refreshActionVersion">回读最新版本，保留填写内容</el-button></template>
    <ApiErrorNotice :error="detailError" />
    <el-alert v-if="detail && !actionAllowed" type="warning" :closable="false" title="当前修订状态或授权不允许此动作，请关闭并查看最新详情。" />
    <el-form class="dialog-form" label-position="top" @submit.prevent="performAction">
      <el-form-item v-if="actionType === 'review'" label="内部审核结论"><el-radio-group v-model="reviewDecision" aria-label="内部审核结论"><el-radio value="approve">通过</el-radio><el-radio value="reject">驳回</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="actionType === 'review' || actionType === 'withdraw'" :label="actionType === 'review' ? '审核意见（必填，1–500 个字符）' : '撤回理由（必填，1–500 个字符）'" :error="actionValidation"><el-input v-model="actionComment" type="textarea" :rows="4" :aria-label="actionType === 'review' ? '审核意见' : '撤回理由'" placeholder="请说明理由，请勿填写密码或 token" /></el-form-item>
      <p v-else-if="actionValidation" class="revision-validation" role="alert">{{ actionValidation }}</p>
    </el-form>
    <template #footer><el-button :disabled="acting" @click="actionOpen = false">取消</el-button><el-button type="primary" :loading="acting" :disabled="!actionAllowed || actionConflict || detailLoading" @click="performAction">{{ actionType === 'review' ? '记录内部审核' : actionType === 'withdraw' ? '确认撤回' : '提交内部审批' }}</el-button></template>
  </el-dialog>
</template>

<style>
.configuration-revisions-drawer,.configuration-revision-detail{max-width:100vw}
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
