<script setup lang="ts">
import { computed } from 'vue'
import { ApiError } from '../api/client'
const props = defineProps<{ error: unknown; pendingMessage?: string; title?: string; guidance?: string; type?: 'error' | 'warning' }>()
const value = computed(() => props.error instanceof ApiError ? props.error : new ApiError(0, 'CLIENT_ERROR', '暂时无法确认操作结果', '—'))
const title = computed(() => props.title || (value.value.code === 'NETWORK_ERROR' ? '未能收到服务响应，请检查网络连接' : value.value.code === 'INVALID_RESPONSE' ? '服务返回异常，暂时无法确认结果' : value.value.message))
const guidance = computed(() => props.guidance ?? (
  ['NETWORK_ERROR', 'CLIENT_ERROR', 'INVALID_RESPONSE'].includes(value.value.code) ? '如果刚提交过内容，请先核对处理结果，避免重复提交。' :
  value.value.code === 'VERSION_CONFLICT' ? '这条记录已被更新。请先重新读取，再核对需要修改的内容。' :
  value.value.status === 429 ? '操作较频繁，请稍等一会儿再试。' : ''))
const traceId = computed(() => value.value.traceId && value.value.traceId !== '—' ? value.value.traceId : '')
const questionIds = computed(() => [...new Set(value.value.details.flatMap(detail => Array.isArray(detail.question_ids) ? detail.question_ids.map(String) : detail.question_id ? [String(detail.question_id)] : []))])
</script>
<template>
  <el-alert v-if="error" class="api-error" :type="type || 'error'" :closable="false" show-icon :title="title">
    <p v-if="guidance">{{ guidance }}</p>
    <p v-if="questionIds.length">待客户确认：{{ questionIds.join('、') }}。{{ pendingMessage || '草稿保持原状态。' }}</p>
    <slot />
    <details class="error-details" :key="`${value.code}:${value.traceId}:${value.message}`">
      <summary>查看排查信息</summary>
      <p class="trace">错误代码：{{ value.code }}</p>
      <p v-if="traceId" class="trace">排查编号：{{ traceId }}</p>
      <p v-if="title !== value.message">{{ value.message }}</p>
    </details>
  </el-alert>
</template>
<style scoped>
.error-details{margin-top:10px;font-size:12px}.error-details summary{cursor:pointer;width:fit-content;min-height:28px;line-height:28px}.error-details summary:focus-visible{outline:2px solid currentColor;outline-offset:3px}.error-details p{overflow-wrap:anywhere}
.api-error :deep(.el-alert__content){min-width:0}.api-error :deep(.el-alert__title){overflow-wrap:anywhere}
</style>
