<script setup lang="ts">
import type { RegisteredTicket } from '../api/types'

defineProps<{ targetId: string; ticket: RegisteredTicket | null; count: number; canDiscard: boolean }>()
defineEmits<{ resume: []; discard: [] }>()
</script>

<template>
  <section class="photo-retained-note" aria-label="保留的报障照片" role="status">
    <strong>照片保留在登记 {{ ticket?.receipt_no || targetId }}</strong>
    <p v-if="ticket">{{ ticket.title }}</p>
    <p>本页保留了 {{ count }} 张本地选择。请返回该登记继续处理，或放弃这些选择后为当前登记选择照片。</p>
    <p v-if="!canDiscard">上传或追加结果尚待确认，原文件与原请求必须保留；请返回原登记确认结果后再移除。</p>
    <p v-else>放弃选择只清除本页照片队列；服务端登记和已上传照片继续保留。</p>
    <div class="photo-retained-actions"><el-button type="primary" @click="$emit('resume')">返回原登记处理照片</el-button><el-button :disabled="!canDiscard" @click="$emit('discard')">放弃保留的本地选择</el-button></div>
  </section>
</template>

<style>
.photo-retained-note{margin:20px 0;padding:16px;border:1px solid #e6a23c;border-radius:5px;background:#fdf6ec;font-size:12px;overflow-wrap:anywhere}.photo-retained-note p{margin:8px 0;line-height:1.6}.photo-retained-actions{display:flex;gap:8px;flex-wrap:wrap}.photo-retained-actions .el-button+.el-button{margin-left:0}
@media(max-width:760px){.photo-retained-actions .el-button{min-height:44px;height:auto;white-space:normal;padding:12px}}
</style>
