<script setup lang="ts">
import { computed } from 'vue'
import { ApiError } from '../api/client'
const props = defineProps<{ error: unknown }>()
const value = computed(() => props.error instanceof ApiError ? props.error : new ApiError(0, 'CLIENT_ERROR', '操作未完成，请稍后重试', '—'))
const questionIds = computed(() => [...new Set(value.value.details.flatMap(detail => Array.isArray(detail.question_ids) ? detail.question_ids.map(String) : detail.question_id ? [String(detail.question_id)] : []))])
</script>
<template>
  <el-alert v-if="error" class="api-error" type="error" :closable="false" show-icon :title="value.message">
    <p v-if="questionIds.length">待客户确认：{{ questionIds.join('、') }}。草稿保持原状态。</p>
    <p class="trace">{{ value.code }} · trace_id: {{ value.traceId }}</p>
    <slot />
  </el-alert>
</template>
