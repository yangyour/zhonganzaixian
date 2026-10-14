<script setup lang="ts">
import { writeFailureUncertain } from '../utils/writeFailure'
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox, ElRadio, ElRadioGroup } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { reportContextUnresolved } from '../utils/reportContextGuard'
import { api, ApiError, isCancelled } from '../api/client'
import type { Asset, AssetPage, CustomerReportAcceptance, CustomerReportOperation, RegisteredTicket } from '../api/types'
import { can, contextGeneration, session } from '../stores/session'
import { displayTime } from '../utils/display'
import { emptyReportForm, impactScopeLabel, isTicketUuid, parseCustomerReport, prepareReportAttempt, ReportValidationError, ticketCategories, urgencyLabel, type ReportSubmissionAttempt } from '../utils/ticket'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReportPhotoPanel from './ReportPhotoPanel.vue'
import { createPhotoQueue } from '../utils/photoQueue'
import { createPhotoAppender } from '../utils/photoAttachments'
import { readAcceptedReport } from '../utils/reportOperation'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; accepted: [operationId: string]; registered: [ticketId: string] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const permitted = computed(() => can('tickets:report') && ['viewer', 'tadmin'].includes(session.value?.user.role || ''))
const form = reactive(emptyReportForm())
const fieldErrors = ref<Record<string, string>>({})
const assets = ref<Asset[]>([])
const assetsLoading = ref(false)
const assetsError = ref<unknown>(null)
const assetsHasMore = ref(false)
let assetsController: AbortController | undefined
let assetsGeneration = 0
const attempt = ref<ReportSubmissionAttempt | null>(null)
const submitting = ref(false)
const submitError = ref<unknown>(null)
const acceptedOperationId = ref('')
const operation = ref<CustomerReportOperation | null>(null)
const operationLoading = ref(false)
const operationError = ref<unknown>(null)
let reportController: AbortController | undefined
let operationController: AbortController | undefined
let writeGeneration = 0
let operationGeneration = 0
const complete = ref(false)
const photos = createPhotoQueue()
const registeredTicket = ref<RegisteredTicket | null>(null)
const appender = createPhotoAppender(registeredTicket, photos)
const uncertain = computed(() => attempt.value?.state === 'uncertain')
const confirming = ref(false)
let disposed = false
const locked = computed(() => submitting.value || uncertain.value || Boolean(acceptedOperationId.value) || confirming.value)
const busy = computed(() => submitting.value || operationLoading.value || appender.busy.value || confirming.value)
const unresolvedPhotos = computed(() => photos.rows.value.some(row => !registeredTicket.value?.attachments.some(photo => photo.id === row.record?.id)))
const formSnapshot = computed(() => JSON.stringify(form))
const dirty = computed(() => !complete.value && Object.values(form).some(value => value !== ''))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version, permitted.value]))
function hasUnresolved() { return reportContextUnresolved({ submitting: submitting.value, uncertain: uncertain.value, accepted: Boolean(acceptedOperationId.value), complete: complete.value, appending: appender.busy.value || appender.reading.value, appendUncertain: appender.uncertain.value, unresolvedPhotos: unresolvedPhotos.value }) }

function reset() {
  confirming.value = false
  assetsController?.abort(); reportController?.abort(); operationController?.abort()
  ++assetsGeneration; ++writeGeneration; ++operationGeneration
  Object.assign(form, emptyReportForm()); fieldErrors.value = {}; assets.value = []; assetsLoading.value = false; assetsError.value = null; assetsHasMore.value = false
  attempt.value = null; submitting.value = false; submitError.value = null; acceptedOperationId.value = ''; operation.value = null; operationLoading.value = false; operationError.value = null; complete.value = false
  photos.clear(); registeredTicket.value = null; appender.reset()
}
watch(() => props.modelValue, value => {
  if (value) {
    if (complete.value && !unresolvedPhotos.value) reset()
    if (!locked.value) void loadAssets()
    if (!photos.policy.value) void photos.loadPolicy()
  } else { assetsController?.abort(); ++assetsGeneration; assetsLoading.value = false }
})
// Re-reading the same authorized session must preserve entered fields and the original registration request.
watch(contextKey, () => { reset(); open.value = false }, { flush: 'sync' })
async function canLeave() {
  if (disposed || confirming.value) return false
  if (hasUnresolved()) { ElMessage.warning('报障登记或照片尚未处理完，请先回读、使用原请求重试或移除可撤回的本地照片，再离开页面、切换租户或退出'); return false }
  if (!dirty.value) return true
  const turn = contextKey.value, snapshot = formSnapshot.value
  confirming.value = true
  try {
    await ElMessageBox.confirm('离开将清除尚未保存的报障输入，是否继续？', '报障输入尚未保存', { confirmButtonText: '放弃输入并继续', cancelButtonText: '继续编辑', type: 'warning' })
    // Do not reset here: another route/context guard or the transition itself may still refuse.
    return !disposed && turn === contextKey.value && snapshot === formSnapshot.value && !hasUnresolved()
  } catch { return false }
  finally { if (!disposed && turn === contextKey.value) confirming.value = false }
}
function beforeUnload(event: BeforeUnloadEvent) { if (hasUnresolved() || dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => canLeave())
const unregisterContextGuard = contextTransitionGuards.register(() => canLeave())
onUnmounted(() => { unregisterContextGuard(); disposed = true; window.removeEventListener('beforeunload', beforeUnload); reset(); photos.dispose(); appender.dispose() })
watch(appender.comparison, value => { if (value && registeredTicket.value) emit('registered', registeredTicket.value.id) })
async function loadAssets() {
  if (!can('assets:read')) { assets.value = []; return }
  assetsController?.abort(); const controller = new AbortController(); assetsController = controller
  const generation = ++assetsGeneration
  assetsLoading.value = true; assetsError.value = null; assetsHasMore.value = false
  try {
    const page = await api.request<AssetPage>('/assets?page_size=100', { signal: controller.signal })
    if (generation !== assetsGeneration) return
    assets.value = page.items; assetsHasMore.value = page.has_more
  } catch (cause) { if (!isCancelled(cause) && generation === assetsGeneration) { assets.value = []; assetsError.value = cause } }
  finally { if (generation === assetsGeneration) assetsLoading.value = false }
}
async function readRegistration() {
  if (!acceptedOperationId.value || operationLoading.value) return
  operationController?.abort(); const controller = new AbortController(); operationController = controller
  const generation = ++operationGeneration
  const writeContext = writeGeneration
  const context = { tenantId: session.value?.tenant.id || '', actorId: session.value?.user.id || '' }
  operationLoading.value = true; operationError.value = null
  try {
    const result = await readAcceptedReport(acceptedOperationId.value, context, {
      signal: controller.signal,
      isCurrent: () => generation === operationGeneration && writeContext === writeGeneration && session.value?.tenant.id === context.tenantId && session.value?.user.id === context.actorId,
    })
    if (generation !== operationGeneration || writeContext !== writeGeneration) return
    operation.value = result.operation; registeredTicket.value = result.ticket; photos.reconcile(result.ticket.attachments)
    complete.value = true
    if (!photos.rows.value.length) open.value = false
    emit('registered', result.ticket.id)
  } catch (cause) { if (!isCancelled(cause) && generation === operationGeneration && writeContext === writeGeneration) operationError.value = cause }
  finally { if (generation === operationGeneration && writeContext === writeGeneration) operationLoading.value = false }
}
async function submitReport() {
  if (disposed || confirming.value || !permitted.value || submitting.value || acceptedOperationId.value) return
  try {
    const body = uncertain.value && attempt.value ? attempt.value.body : { ...parseCustomerReport(form, assets.value.map(item => item.id)), attachment_ids: photos.readyIds() }
    attempt.value = prepareReportAttempt(attempt.value, body); fieldErrors.value = {}
  } catch (cause) {
    if (cause instanceof ReportValidationError) fieldErrors.value = cause.fields
    else submitError.value = new ApiError(0, 'REPORT_INPUT_ERROR', (cause as Error).message, '—')
    return
  }
  if (!attempt.value) return
  reportController?.abort(); const controller = new AbortController(); reportController = controller
  const generation = ++writeGeneration
  const currentAttempt = attempt.value, retry = currentAttempt.state === 'uncertain'
  submitting.value = true; submitError.value = null
  try {
    const result = await api.request<CustomerReportAcceptance>('/tickets', { method: 'POST', body: currentAttempt.body, idempotencyKey: currentAttempt.key, signal: controller.signal })
    if (generation !== writeGeneration) return
    if (!result || result.status !== 'PENDING' || !isTicketUuid(result.operation_id)) throw new ApiError(200, 'INVALID_RESPONSE', '提交响应无法确认，请保留原填写内容并使用原请求重试', '—')
    currentAttempt.state = 'accepted'; acceptedOperationId.value = result.operation_id.toLowerCase()
    emit('accepted', acceptedOperationId.value)
    await readRegistration()
  } catch (cause) {
    if (!isCancelled(cause) && generation === writeGeneration) {
      if (writeFailureUncertain(retry, cause)) currentAttempt.state = 'uncertain'
      else currentAttempt.state = 'ready'
      submitError.value = cause
    }
  } finally { if (generation === writeGeneration) submitting.value = false }
}
</script>

<template>
  <el-dialog v-model="open" title="填写报障信息" width="650px" class="responsive-dialog ticket-report-dialog" :close-on-click-modal="!busy && !uncertain" :close-on-press-escape="!busy" :show-close="!busy" destroy-on-close>
    <p class="ticket-form-intro">报障人取自当前登录账号，登记在 {{ session?.tenant.name }}。正式处理状态、优先级和时限尚待规则落实。</p>
    <el-alert v-if="acceptedOperationId" type="info" :closable="false" show-icon title="报障已耐久登记，正式工单规则待确认。只查询登记结果，不再重复提交。" />
    <el-alert v-else-if="uncertain" type="warning" :closable="false" show-icon title="提交结果暂不确定。已保留原内容与同一个请求标识，请手动使用原请求重试，避免重复登记。" />
    <ApiErrorNotice :error="submitError" pending-message="本次填写内容已保留，登记结果以服务端回读为准。" />
    <ApiErrorNotice :error="operationError" />
    <div v-if="operation" class="ticket-operation-summary"><strong>已登记 · 正式工单规则待确认</strong><p>{{ operation.error.message }}</p><p>待确认事项：{{ operation.error.question_ids.join(' / ') }}</p><p>登记时间：{{ displayTime(operation.created_at) }}</p></div>
    <el-form :disabled="locked" label-position="top" class="dialog-form ticket-report-form" @submit.prevent="submitReport">
      <el-form-item label="故障分类" :error="fieldErrors.category"><el-select v-model="form.category" placeholder="请选择故障分类" aria-label="故障分类"><el-option v-for="category in ticketCategories" :key="category" :label="category" :value="category" /></el-select></el-form-item>
      <el-form-item label="关联设备" :error="fieldErrors.asset_id"><el-select v-model="form.asset_id" filterable placeholder="不确定" :loading="assetsLoading" aria-label="关联设备"><el-option label="不确定" value="" /><el-option v-for="asset in assets" :key="asset.id" :label="asset.name" :value="asset.id" /></el-select><span class="field-help">{{ assetsLoading ? '正在加载当前可见设备，可先选择“不确定”。' : assetsError ? '设备列表加载失败，可选择“不确定”继续登记。' : assetsHasMore ? '仅显示当前可见设备的首个列表页，未找到时请选择“不确定”。' : '只可选择当前可见设备，无法判断时选择“不确定”。' }}</span></el-form-item>
      <ApiErrorNotice :error="assetsError"><el-button size="small" :disabled="locked" @click="loadAssets">重试设备查询</el-button></ApiErrorNotice>
      <el-form-item label="影响范围" :error="fieldErrors.impact_scope"><el-radio-group v-model="form.impact_scope" class="ticket-choice-group" aria-label="影响范围"><el-radio v-for="(label, value) in impactScopeLabel" :key="value" :value="value">{{ label }}</el-radio></el-radio-group></el-form-item>
      <el-form-item label="描述（必填，最多 3000 个字符）" :error="fieldErrors.description"><el-input v-model="form.description" type="textarea" :rows="4" placeholder="描述发生时间、现象和影响，请勿填写密码或其他秘密" aria-label="报障描述" /></el-form-item>
      <el-form-item label="标题（可不填）" :error="fieldErrors.title"><el-input v-model="form.title" placeholder="不填时按故障分类与设备生成" aria-label="报障标题" /></el-form-item>
      <div class="ticket-contact-fields"><el-form-item label="联系人" :error="fieldErrors.contact_name"><el-input v-model="form.contact_name" placeholder="本次报障联系人" aria-label="报障联系人" /></el-form-item><el-form-item label="联系电话" :error="fieldErrors.contact_phone"><el-input v-model="form.contact_phone" type="tel" placeholder="例如：+86 138 0000 0000" aria-label="报障联系电话" /></el-form-item></div>
      <p class="field-help">联系方式仅作为填报信息，尚未核验与账号的绑定关系。</p>
      <el-form-item label="紧急程度（仅供参考）" :error="fieldErrors.urgency"><el-radio-group v-model="form.urgency" class="ticket-choice-group" aria-label="紧急程度"><el-radio v-for="(label, value) in urgencyLabel" :key="value" :value="value">{{ label }}</el-radio></el-radio-group></el-form-item>
      <p class="field-help">同一设备、同分类的短时间重复提交可能返回已有记录。请核对回读详情；已有描述与联系信息不会被覆盖。</p>
    </el-form>
    <el-alert v-if="registeredTicket" type="info" :closable="false" :title="`已回读登记 ${registeredTicket.receipt_no} · v${registeredTicket.version}。正文和联系方式以详情为准，重复报障不会覆盖已有内容。`" />
    <ReportPhotoPanel :queue="photos" :existing="registeredTicket?.attachments || []" :disabled="submitting || uncertain" :appender="registeredTicket ? appender : undefined" />
    <p v-if="acceptedOperationId && !registeredTicket" class="field-help">报障已受理。请查询登记结果后追加照片，图片处理失败不会取消报障。</p>
    <template #footer><el-button :disabled="busy" @click="open = false">{{ uncertain || acceptedOperationId ? '关闭并保留本次填写' : '取消' }}</el-button><el-button v-if="acceptedOperationId" type="primary" :loading="operationLoading" :disabled="submitting" @click="readRegistration">重新查询登记结果</el-button><el-button v-else type="primary" :loading="submitting" :disabled="!permitted" @click="submitReport">{{ uncertain ? '使用原请求重试' : '提交报障登记' }}</el-button></template>
  </el-dialog>
</template>

<style>
.ticket-form-intro{font-size:12px;color:#61718a}.ticket-report-dialog .api-error{margin-top:16px}.ticket-contact-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.ticket-choice-group{display:flex;flex-wrap:wrap;gap:0 18px}.ticket-choice-group .el-radio{margin-right:0;min-height:36px}.ticket-operation-summary{margin-top:18px;padding:15px;border:1px solid #d9e9ff;background:#f5f9ff;border-radius:6px;font-size:12px}.ticket-operation-summary p{margin:8px 0}.ticket-report-form>.field-help{margin-top:-10px;margin-bottom:20px}
@media(max-width:760px){.ticket-contact-fields{grid-template-columns:1fr;gap:0}.ticket-report-dialog .el-dialog__footer{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px}.ticket-report-dialog .el-dialog__footer .el-button{margin-left:0;min-height:44px}.ticket-report-form .el-input__wrapper,.ticket-report-form .el-select__wrapper{min-height:44px}.ticket-choice-group .el-radio{min-height:44px}}
</style>
