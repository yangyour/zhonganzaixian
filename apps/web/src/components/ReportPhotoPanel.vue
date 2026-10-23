<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ReportPhoto } from '../api/types'
import type { PhotoQueue, PhotoSelection } from '../utils/photoQueue'
import type { PhotoAppender } from '../utils/photoAttachments'
import ApiErrorNotice from './ApiErrorNotice.vue'
import AttachmentSecurityPanel from './AttachmentSecurityPanel.vue'

const props = withDefaults(defineProps<{ queue: PhotoQueue; existing?: ReportPhoto[]; disabled?: boolean; selectable?: boolean; showSelections?: boolean; appender?: PhotoAppender }>(), { existing: () => [], disabled: false, selectable: true, showSelections: true })
const selectionMessage = ref('')
let uid = 0
const unboundCount = computed(() => props.showSelections ? props.queue.rows.value.filter(row => !props.existing.some(photo => photo.id === row.record?.id)).length : 0)
const full = computed(() => props.existing.length + unboundCount.value >= Math.min(3, props.queue.policy.value?.max_attachments || 3))
const statusLabel: Record<PhotoSelection['status'], string> = { SELECTED: '待上传', UPLOADING: '正在上传原图', UNCERTAIN: '提交结果待确认', PENDING: '已受理 · 待处理', RUNNING: '正在存储', READY: '已上传', FAILED: '上传处理失败' }
function select(event: Event) {
  const input = event.target as HTMLInputElement
  selectionMessage.value = ''
  if (props.disabled || !props.selectable || props.appender?.uncertain.value || props.appender?.busy.value) { input.value = ''; return }
  for (const file of Array.from(input.files || [])) {
    uid = Math.max(uid, ...props.queue.rows.value.map(row => row.uid)) + 1
    const message = props.queue.add(file, uid, props.existing.length, props.existing.map(photo => photo.id))
    if (message) selectionMessage.value += `${file.name}：${message}。 `
  }
  input.value = ''
}
</script>

<template>
  <section class="report-photo-panel" aria-label="报障照片">
    <h3>现场照片附件 <small>{{ existing.length + unboundCount }} / 3</small></h3>
    <p class="field-help">JPEG / PNG，单张 ≤5 MB，最多 3 张；可先提交工单再补传。</p>
    <ApiErrorNotice :error="queue.policyError.value"><el-button size="small" :loading="queue.policyLoading.value" @click="queue.loadPolicy">重新加载上传配置</el-button></ApiErrorNotice>
    <p v-if="selectionMessage" class="photo-input-error" role="alert">{{ selectionMessage }}</p>
    <div v-if="selectable" class="photo-select-actions">
      <label class="photo-file-label" :class="{ 'is-disabled': disabled || full || !queue.policy.value?.allowed_media_types?.length || appender?.uncertain.value || appender?.busy.value }">选择照片<input type="file" multiple accept="image/jpeg,image/png,.jpg,.jpeg,.png" :disabled="disabled || full || !queue.policy.value?.allowed_media_types?.length || appender?.uncertain.value || appender?.busy.value" aria-label="选择报障照片" @change="select" /></label>
      <label class="photo-file-label" :class="{ 'is-disabled': disabled || full || !queue.policy.value?.allowed_media_types?.length || appender?.uncertain.value || appender?.busy.value }">拍摄照片<input type="file" accept="image/jpeg,image/png" capture="environment" :disabled="disabled || full || !queue.policy.value?.allowed_media_types?.length || appender?.uncertain.value || appender?.busy.value" aria-label="拍摄报障照片" @change="select" /></label>
      <el-button :loading="queue.policyLoading.value" @click="queue.loadPolicy">刷新上传配置</el-button>
    </div>
    <p v-if="!queue.policy.value?.allowed_media_types?.length && selectable" class="muted">{{ queue.policyLoading.value ? '正在读取照片上传配置…' : '照片上传配置暂未就绪，可先提交文字故障申报。' }}</p>
    <div v-for="photo in existing" :key="photo.id" class="photo-row photo-existing-row">
      <div class="photo-row-summary"><strong>{{ photo.original_name }}</strong><span>{{ photo.size_bytes.toLocaleString() }} bytes · 已关联工单</span></div>
      <AttachmentSecurityPanel :file-id="photo.id" class="photo-row-actions" @content-access="allowed => { if (!allowed) queue.revokeContent(photo.id) }" />
      <div class="photo-row-actions"><el-button :loading="queue.contentBusy.value[photo.id]" @click="queue.view(photo)">查看原图</el-button><a v-if="queue.contentUrls.value[photo.id]" :href="queue.contentUrls.value[photo.id]" :download="photo.original_name" class="photo-download">下载原图</a></div>
      <ApiErrorNotice :error="queue.contentErrors.value[photo.id]" />
      <img v-if="queue.contentUrls.value[photo.id]" :src="queue.contentUrls.value[photo.id]" :alt="photo.original_name" class="photo-original-preview" />
    </div>
    <div v-for="row in showSelections ? queue.rows.value : []" :key="row.uid" class="photo-row">
      <img :src="row.previewUrl" :alt="`本地选择：${row.file.name}`" class="photo-local-preview" />
      <div class="photo-row-summary"><strong>{{ row.file.name }}</strong><span>{{ row.file.size.toLocaleString() }} bytes · {{ statusLabel[row.status] }}</span><p v-if="row.message">{{ row.message }}</p><p v-if="existing.some(photo => photo.id === row.record?.id)">已关联至当前工单。</p></div>
      <AttachmentSecurityPanel v-if="row.record" :file-id="row.record.id" class="photo-row-actions" @content-access="allowed => { if (!allowed && row.record) queue.revokeContent(row.record.id) }" />
      <div class="photo-row-actions">
        <el-button v-if="!row.operationId" :loading="row.uploading" :disabled="disabled || appender?.busy.value || appender?.uncertain.value" @click="queue.upload(row.uid)">{{ row.status === 'UNCERTAIN' ? '重试上传' : '上传原图' }}</el-button>
        <el-button v-else :loading="row.reading" :disabled="disabled" @click="queue.refresh(row.uid)">刷新上传状态</el-button>
        <el-button :disabled="disabled || !queue.canRemove(row.uid) || appender?.busy.value || appender?.uncertain.value" @click="queue.remove(row.uid)">移除照片</el-button>
      </div>
      <p v-if="!queue.canRemove(row.uid) && !row.uploading && !row.reading" class="field-help">{{ row.status === 'UNCERTAIN' ? '提交结果待确认：系统已锁定当前文件与请求参数，请点击“重试上传”确认结果后再移除。' : '照片正在处理中，请先刷新上传状态确认结果后再移除。' }}</p>
      <ApiErrorNotice :error="row.error" pending-message="原文件仍保留在本页面；已受理图片可点击刷新上传状态核对进度。" />
    </div>
    <template v-if="appender && appender.permitted.value">
      <ApiErrorNotice :error="appender.error.value" pending-message="已受理的工单与本地照片队列继续保留。" />
      <p v-if="appender.conflict.value" class="photo-input-error" role="alert">工单版本已更新，请先点击“刷新并核对附件”，确认现有照片后再执行关联。</p>
      <p v-if="appender.uncertain.value" class="photo-input-error" role="alert">提交结果待确认：系统已锁定当前填写内容，请先刷新列表核对是否已生效，或点击“重试提交”继续处理。</p>
      <p v-if="appender.comparison.value" class="field-help" role="status">{{ appender.comparison.value }}</p>
      <div class="photo-append-actions"><el-button :loading="appender.reading.value" :disabled="appender.busy.value" @click="appender.refreshComparison">刷新并核对附件</el-button><el-button type="primary" :loading="appender.busy.value" :disabled="appender.conflict.value || appender.reading.value || (!appender.uncertain.value && !appender.readyCount.value)" @click="appender.append">{{ appender.uncertain.value ? '重试提交' : '将已上传照片关联至工单' }}</el-button></div>
    </template>
  </section>
</template>

<style scoped>
.report-photo-panel{margin:20px 0;border-top:1px solid #e4eaf2;padding-top:12px}
.report-photo-panel h3{font-size:15px}
.report-photo-panel h3 small{font-weight:400;color:#61718a}
.photo-select-actions,.photo-row-actions,.photo-append-actions{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.photo-file-label{position:relative;display:inline-flex;align-items:center;padding:8px 15px;min-height:36px;box-sizing:border-box;border:1px solid #cdd9e8;border-radius:4px;background:#fff;font-size:13px;cursor:pointer;color:#23436a;transition:background-color .15s,border-color .15s,color .15s}
.photo-file-label:not(.is-disabled):hover{background:#f3f8ff;border-color:#9ec2f6;color:#1d4ed8}
.photo-file-label input{position:absolute;inset:0;opacity:0;width:100%;height:100%;cursor:pointer}
.photo-file-label:focus-within{outline:2px solid #409eff;outline-offset:2px}
.photo-file-label.is-disabled{opacity:.55;cursor:default}
.photo-row{margin-top:14px;padding:12px;border:1px solid #e4eaf2;border-radius:6px;background:#fafbfd;display:grid;grid-template-columns:72px minmax(0,1fr);gap:10px}
.photo-row-summary{min-width:0;overflow-wrap:anywhere;font-size:12px}
.photo-row-summary strong,.photo-row-summary span{display:block}
.photo-row-summary span{margin-top:6px;color:#61718a}
.photo-row-summary p{margin:6px 0}
.photo-local-preview{width:72px;height:72px;object-fit:cover;border-radius:4px;border:1px solid #e4eaf2}
.photo-row-actions,.photo-row :deep(.api-error),.photo-original-preview{grid-column:1/-1}
.photo-existing-row{grid-template-columns:minmax(0,1fr)}
.photo-original-preview{width:100%;max-height:420px;object-fit:contain;background:#f4f7fb;border-radius:4px}
.photo-download{font-size:13px;color:#2563eb;text-decoration:none;padding:4px 8px;border-radius:4px}
.photo-download:hover{background:#eef5ff;text-decoration:underline}
.photo-input-error{font-size:12px;color:#9b3b1b;overflow-wrap:anywhere}
.photo-append-actions{margin-top:12px}
.report-photo-panel :deep(.el-button+.el-button){margin-left:0}
@media(max-width:760px){
  .photo-file-label,.report-photo-panel :deep(.el-button),.photo-download{min-height:44px}
  .photo-append-actions :deep(.el-button){height:auto;white-space:normal;padding:12px}
  .photo-file-label{padding-top:12px;padding-bottom:12px}
}
</style>

