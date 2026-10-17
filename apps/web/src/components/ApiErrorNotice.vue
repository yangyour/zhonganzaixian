<script setup lang="ts">
import { computed } from 'vue'
import { errorPresentation } from '../utils/errorPresentation'
const props = defineProps<{ error: unknown; pendingMessage?: string; title?: string; guidance?: string; type?: 'error' | 'warning'; onRetry?: () => void; retrying?: boolean; retryLabel?: string }>()
const presentation = computed(() => errorPresentation(props.error))
const value = computed(() => presentation.value.value)
const title = computed(() => props.title || presentation.value.title)
const guidance = computed(() => props.guidance ?? presentation.value.guidance)
const traceId = computed(() => value.value.traceId && value.value.traceId !== '—' ? value.value.traceId : '')
const questionIds = computed(() => [...new Set(value.value.details.flatMap(detail => Array.isArray(detail.question_ids) ? detail.question_ids.map(String) : detail.question_id ? [String(detail.question_id)] : []))])
</script>
<template>
  <el-alert v-if="error" class="api-error" :type="type || 'error'" :closable="false" show-icon :title="title">
    <p v-if="guidance">{{ guidance }}</p>
    <p v-if="questionIds.length">待客户确认：{{ questionIds.join('、') }}。{{ pendingMessage || '草稿保持原状态。' }}</p>
    <slot />
    <el-button v-if="onRetry" class="error-retry" :loading="retrying" @click="onRetry">{{ retryLabel || '重新读取' }}</el-button>
    <details class="error-details" :key="`${value.code}:${value.traceId}:${value.message}`">
      <summary>查看排查信息</summary>
      <p class="trace">错误代码：{{ value.code }}</p>
      <p v-if="traceId" class="trace">排查编号（请求追踪号）：{{ traceId }}</p>
      <p v-if="title !== value.message">{{ value.message }}</p>
    </details>
  </el-alert>
</template>
<style scoped>
.error-details{margin-top:10px;font-size:12px}.error-details summary{cursor:pointer;width:fit-content;min-height:28px;line-height:28px}.error-details summary:focus-visible{outline:2px solid currentColor;outline-offset:3px}.error-details p{overflow-wrap:anywhere}
.api-error :deep(.el-alert__content){min-width:0}.api-error :deep(.el-alert__title){overflow-wrap:anywhere}
</style>
