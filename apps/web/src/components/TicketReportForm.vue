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
import AutoTextarea from './AutoTextarea.vue'
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
  if (hasUnresolved()) { ElMessage.warning('当前还有未完成的故障申报或待处理的现场照片，请先完成核对或移除本地照片后再离开'); return false }
  if (!dirty.value) return true
  const turn = contextKey.value, snapshot = formSnapshot.value
  confirming.value = true
  try {
    await ElMessageBox.confirm('离开将清除尚未保存的故障申报内容，是否继续？', '内容尚未保存', { confirmButtonText: '放弃并离开', cancelButtonText: '继续编辑', type: 'warning' })
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
    if (!result || result.status !== 'PENDING' || !isTicketUuid(result.operation_id)) throw new ApiError(200, 'INVALID_RESPONSE', '暂未收到有效的受理回执，系统已保留填写内容，请点击重试提交', '—')
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
  <el-dialog v-model="open" title="故障申报登记" width="650px" class="responsive-dialog ticket-report-dialog" :close-on-click-modal="!busy && !uncertain" :close-on-press-escape="!busy" :show-close="!busy" destroy-on-close>
    <p class="ticket-form-intro">申报租户：<strong>{{ session?.tenant.name }}</strong></p>
    <el-alert v-if="acceptedOperationId" type="info" :closable="false" show-icon title="故障申报已受理，系统正在生成正式工单，可点击下方按钮刷新处理进度。" />
    <el-alert v-else-if="uncertain" type="warning" :closable="false" show-icon title="提交结果待确认：系统已锁定当前填写内容，请先刷新列表核对是否已生效，或点击“重试提交”继续处理。" />
    <ApiErrorNotice :error="submitError" pending-message="系统已保留当前填写内容，请刷新或重试以确认受理结果。" />
    <ApiErrorNotice :error="operationError" />
    <div v-if="operation" class="ticket-operation-summary"><strong>已受理入库 · 待关联 ITSM 流程</strong><p>{{ operation.error.message }}</p><p>关联配置项：{{ operation.error.question_ids.join(' / ') }}</p><p>受理时间：{{ displayTime(operation.created_at) }}</p></div>
    <el-form :disabled="locked" label-position="top" class="dialog-form ticket-report-form" @submit.prevent="submitReport">
      <el-form-item label="故障分类" :error="fieldErrors.category"><el-select v-model="form.category" placeholder="请选择故障分类" aria-label="故障分类"><el-option v-for="category in ticketCategories" :key="category" :label="category" :value="category" /></el-select></el-form-item>
      <el-form-item label="关联设备" :error="fieldErrors.asset_id"><el-select v-model="form.asset_id" filterable placeholder="不确定" :loading="assetsLoading" aria-label="关联设备"><el-option label="不确定" value="" /><el-option v-for="asset in assets" :key="asset.id" :label="asset.name" :value="asset.id" /></el-select><span class="field-help">{{ assetsLoading ? '正在加载当前可见设备，可先选择“不确定”。' : assetsError ? '设备列表加载失败，可选择“不确定”继续申报。' : assetsHasMore ? '展示当前可见设备首页列表，如未找到目标设备可选择“不确定”。' : '请选择故障关联设备，若无法确认可选择“不确定”。' }}</span></el-form-item>
      <ApiErrorNotice :error="assetsError"><el-button size="small" :disabled="locked" @click="loadAssets">重试设备查询</el-button></ApiErrorNotice>
      <el-form-item label="影响范围" :error="fieldErrors.impact_scope"><el-radio-group v-model="form.impact_scope" class="ticket-choice-group" aria-label="影响范围"><el-radio v-for="(label, value) in impactScopeLabel" :key="value" :value="value">{{ label }}</el-radio></el-radio-group></el-form-item>
      <el-form-item label="描述（必填，最多 3000 个字符）" :error="fieldErrors.description"><AutoTextarea v-model="form.description" placeholder="请描述故障发生时间、具体现象与受影响业务，请勿填写密码或密钥信息" aria-label="报障描述" /></el-form-item>
      <el-form-item label="标题（选填）" :error="fieldErrors.title"><el-input v-model="form.title" placeholder="留空时系统将按故障分类与关联设备自动生成" aria-label="报障标题" /></el-form-item>
      <div class="ticket-contact-fields"><el-form-item label="联系人" :error="fieldErrors.contact_name"><el-input v-model="form.contact_name" placeholder="本次故障申报联系人" aria-label="报障联系人" /></el-form-item><el-form-item label="联系电话" :error="fieldErrors.contact_phone"><el-input v-model="form.contact_phone" type="tel" placeholder="例如：+86 138 0000 0000" aria-label="报障联系电话" /></el-form-item></div>
      <p class="field-help">请填写现场或业务联系人信息，以便工程师及时核实故障现象。</p>
      <el-form-item label="紧急程度（填报参考）" :error="fieldErrors.urgency"><el-radio-group v-model="form.urgency" class="ticket-choice-group" aria-label="紧急程度"><el-radio v-for="(label, value) in urgencyLabel" :key="value" :value="value">{{ label }}</el-radio></el-radio-group></el-form-item>
      
    </el-form>
    <el-alert v-if="registeredTicket" type="success" :closable="false" :title="`工单已生成（单号：${registeredTicket.receipt_no} · v${registeredTicket.version}），可查看工单详情或继续关联现场照片。`" />
    <ReportPhotoPanel :queue="photos" :existing="registeredTicket?.attachments || []" :disabled="submitting || uncertain" :appender="registeredTicket ? appender : undefined" />
    <p v-if="acceptedOperationId && !registeredTicket" class="field-help">故障申报已受理，请先刷新处理进度，待正式工单生成后即可追加现场照片。</p>
    <template #footer><el-button :disabled="busy" @click="open = false">{{ uncertain || acceptedOperationId ? '关闭窗口（保留当前内容）' : '取消' }}</el-button><el-button v-if="acceptedOperationId" type="primary" :loading="operationLoading" :disabled="submitting" @click="readRegistration">刷新处理进度</el-button><el-button v-else type="primary" :loading="submitting" :disabled="!permitted" @click="submitReport">{{ uncertain ? '重试提交' : '提交故障申报' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
:deep(.ticket-report-dialog.el-dialog){display:flex;flex-direction:column;max-height:calc(100vh - 48px);max-height:calc(100dvh - 48px);margin-top:24px!important;margin-bottom:24px;overflow:hidden}
:deep(.ticket-report-dialog>.el-dialog__header),:deep(.ticket-report-dialog>.el-dialog__footer){flex:none;min-width:0}
:deep(.ticket-report-dialog>.el-dialog__body){flex:1 1 auto;min-height:0;min-width:0;overflow:auto;overscroll-behavior:contain;scrollbar-gutter:stable;scroll-padding-block:16px}
:deep(.ticket-report-dialog>.el-dialog__body :is(input,textarea,select,button,[tabindex])){scroll-margin-block:16px}
:deep(.ticket-report-dialog>.el-dialog__footer){border-top:1px solid var(--el-border-color-lighter);margin-top:16px;padding-top:16px}
:deep(.ticket-report-dialog .el-dialog__footer .el-button){max-width:100%;height:auto;white-space:normal}
.ticket-form-intro{margin:0 0 14px;padding:10px 14px;background:#f6f9fc;border:1px solid #e5edf5;border-radius:6px;font-size:12px;line-height:1.65;color:#4e6178}
:deep(.ticket-report-dialog .api-error){margin-top:16px}
.ticket-contact-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.ticket-choice-group{display:flex;flex-wrap:wrap;gap:0 18px}
.ticket-choice-group :deep(.el-radio){margin-right:0;min-height:36px}
.ticket-operation-summary{margin-top:18px;padding:15px;border:1px solid #d9e9ff;background:#f5f9ff;border-radius:6px;font-size:12px}
.ticket-operation-summary p{margin:8px 0}
.ticket-report-form>.field-help{margin-top:-10px;margin-bottom:20px}
@media(max-width:760px){
  :deep(.ticket-report-dialog.el-dialog){max-height:calc(100vh - 24px);max-height:calc(100dvh - 24px);margin-top:12px!important;margin-bottom:12px}
  .ticket-contact-fields{grid-template-columns:1fr;gap:0}
  :deep(.ticket-report-dialog .el-dialog__footer){display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px}
  :deep(.ticket-report-dialog .el-dialog__footer .el-button){margin-left:0;min-height:44px}
  .ticket-report-form :deep(.el-input__wrapper),.ticket-report-form :deep(.el-select__wrapper){min-height:44px}
  .ticket-choice-group :deep(.el-radio){min-height:44px}
}
</style>

