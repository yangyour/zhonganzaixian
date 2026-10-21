<script setup lang="ts">
import type { RegisteredTicket } from '../api/types'

defineProps<{ targetId: string; ticket: RegisteredTicket | null; count: number; canDiscard: boolean }>()
defineEmits<{ resume: []; discard: [] }>()
</script>

<template>
  <section class="photo-retained-note" aria-label="保留的报障照片" role="status">
    <strong>待处理的现场照片（工单 {{ ticket?.receipt_no || targetId }}）</strong>
    <p v-if="ticket">{{ ticket.title }}</p>
    <p>当前保留了 {{ count }} 张待关联的现场照片。您可以返回原工单继续处理，或清除本地待传队列后为其他工单上传照片。</p>
    <p v-if="!canDiscard">提交结果待确认：系统已锁定当前照片与请求参数，请先返回原工单核对处理结果后再移除。</p>
    <p v-else>清除本地队列不会影响工单中已保存的照片附件。</p>
    <div class="photo-retained-actions"><el-button type="primary" @click="$emit('resume')">返回原工单处理照片</el-button><el-button :disabled="!canDiscard" @click="$emit('discard')">清除本地待传照片</el-button></div>
  </section>
</template>

<style scoped>
.photo-retained-note{margin:20px 0;padding:16px;border:1px solid #e6a23c;border-radius:6px;background:#fdf6ec;font-size:12px;overflow-wrap:anywhere}
.photo-retained-note p{margin:8px 0;line-height:1.6}
.photo-retained-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.photo-retained-actions :deep(.el-button+.el-button){margin-left:0}
@media(max-width:760px){
  .photo-retained-actions :deep(.el-button){min-height:44px;height:auto;white-space:normal;padding:12px}
}
</style>

