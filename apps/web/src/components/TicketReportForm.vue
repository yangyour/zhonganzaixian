<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElRadio, ElRadioGroup } from 'element-plus'
import { api, ApiError, isCancelled } from '../api/client'
import type { Asset, AssetPage, CustomerReportAcceptance, CustomerReportOperation } from '../api/types'
import { can, contextGeneration, session } from '../stores/session'
import { displayTime } from '../utils/display'
import { emptyReportForm, impactScopeLabel, isTicketUuid, parseCustomerReport, prepareReportAttempt, ReportValidationError, ticketCategories, ticketIdFromResultRef, urgencyLabel, type ReportSubmissionAttempt } from '../utils/ticket'
import ApiErrorNotice from './ApiErrorNotice.vue'

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
const uncertain = computed(() => attempt.value?.state === 'uncertain')
const locked = computed(() => submitting.value || uncertain.value || Boolean(acceptedOperationId.value))
const busy = computed(() => submitting.value || operationLoading.value)

function reset() {
  assetsController?.abort(); reportController?.abort(); operationController?.abort()
  ++assetsGeneration; ++writeGeneration; ++operationGeneration
  Object.assign(form, emptyReportForm()); fieldErrors.value = {}; assets.value = []; assetsLoading.value = false; assetsError.value = null; assetsHasMore.value = false
  attempt.value = null; submitting.value = false; submitError.value = null; acceptedOperationId.value = ''; operation.value = null; operationLoading.value = false; operationError.value = null; complete.value = false
}
watch(() => props.modelValue, value => {
  if (value) {
    if (!attempt.value || complete.value) reset()
    if (!locked.value) void loadAssets()
  } else { assetsController?.abort(); ++assetsGeneration; assetsLoading.value = false }
})
watch(contextGeneration, () => { reset(); open.value = false }, { flush: 'sync' })
onUnmounted(reset)
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
  operationLoading.value = true; operationError.value = null
  try {
    const result = await api.request<CustomerReportOperation>(`/operations/${encodeURIComponent(acceptedOperationId.value)}`, { signal: controller.signal })
    if (generation !== operationGeneration || writeContext !== writeGeneration) return
    if (!isTicketUuid(result.id) || result.id.toLowerCase() !== acceptedOperationId.value || result.kind !== 'CUSTOMER_REPORT_REGISTRATION'
      || result.status !== 'PENDING' || result.step !== 'AWAITING_TICKET_CONFIGURATION' || result.progress !== null) throw new ApiError(200, 'INVALID_RESPONSE', '登记结果返回异常，请重新查询；本次报障不会再次提交', '—')
    operation.value = result
    const ticketId = ticketIdFromResultRef(result.result_ref)
    if (!ticketId) throw new ApiError(200, 'INVALID_RESULT_REFERENCE', '登记结果引用不符合本应用路径，请重新查询；不会打开外部地址或再次提交', '—')
    complete.value = true; open.value = false
    emit('registered', ticketId)
  } catch (cause) { if (!isCancelled(cause) && generation === operationGeneration && writeContext === writeGeneration) operationError.value = cause }
  finally { if (generation === operationGeneration && writeContext === writeGeneration) operationLoading.value = false }
}
async function submitReport() {
  if (!permitted.value || submitting.value || acceptedOperationId.value) return
  try {
    const body = uncertain.value && attempt.value ? attempt.value.body : parseCustomerReport(form, assets.value.map(item => item.id))
    attempt.value = prepareReportAttempt(attempt.value, body); fieldErrors.value = {}
  } catch (cause) {
    if (cause instanceof ReportValidationError) fieldErrors.value = cause.fields
    else submitError.value = new ApiError(0, 'REPORT_INPUT_ERROR', (cause as Error).message, '—')
    return
  }
  if (!attempt.value) return
  reportController?.abort(); const controller = new AbortController(); reportController = controller
  const generation = ++writeGeneration
  const currentAttempt = attempt.value
  submitting.value = true; submitError.value = null
  try {
    const result = await api.request<CustomerReportAcceptance>('/tickets', { method: 'POST', body: currentAttempt.body, idempotencyKey: currentAttempt.key, signal: controller.signal })
    if (generation !== writeGeneration) return
    if (result.status !== 'PENDING' || !isTicketUuid(result.operation_id)) throw new ApiError(200, 'INVALID_RESPONSE', '提交响应无法确认，请保留原填写内容并使用原请求重试', '—')
    currentAttempt.state = 'accepted'; acceptedOperationId.value = result.operation_id.toLowerCase()
    emit('accepted', acceptedOperationId.value)
    await readRegistration()
  } catch (cause) {
    if (!isCancelled(cause) && generation === writeGeneration) {
      if (cause instanceof ApiError && (cause.status === 0 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE')) currentAttempt.state = 'uncertain'
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
      <el-alert type="info" :closable="false" show-icon title="可以先不带图片登记。图片上传及补传尚未实现；图片能力未接入不会取消已经登记的报障。" />
      <p class="field-help">同一设备、同分类的短时间重复提交可能返回已有记录。请核对回读详情；已有描述与联系信息不会被覆盖。</p>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="open = false">{{ uncertain || acceptedOperationId ? '关闭并保留本次填写' : '取消' }}</el-button><el-button v-if="acceptedOperationId" type="primary" :loading="operationLoading" :disabled="submitting" @click="readRegistration">重新查询登记结果</el-button><el-button v-else type="primary" :loading="submitting" :disabled="!permitted" @click="submitReport">{{ uncertain ? '使用原请求重试' : '提交报障登记' }}</el-button></template>
  </el-dialog>
</template>

<style>
.ticket-form-intro{font-size:12px;color:#61718a}.ticket-report-dialog .api-error{margin-top:16px}.ticket-contact-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.ticket-choice-group{display:flex;flex-wrap:wrap;gap:0 18px}.ticket-choice-group .el-radio{margin-right:0;min-height:36px}.ticket-operation-summary{margin-top:18px;padding:15px;border:1px solid #d9e9ff;background:#f5f9ff;border-radius:6px;font-size:12px}.ticket-operation-summary p{margin:8px 0}.ticket-report-form>.field-help{margin-top:-10px;margin-bottom:20px}
@media(max-width:760px){.ticket-contact-fields{grid-template-columns:1fr;gap:0}.ticket-report-dialog .el-dialog__footer{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px}.ticket-report-dialog .el-dialog__footer .el-button{margin-left:0;min-height:44px}.ticket-report-form .el-input__wrapper,.ticket-report-form .el-select__wrapper{min-height:44px}.ticket-choice-group .el-radio{min-height:44px}}
</style>
