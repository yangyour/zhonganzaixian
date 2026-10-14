<script setup lang="ts">
import type { IdentityCommand } from '../utils/projectIdentity'
import ApiErrorNotice from './ApiErrorNotice.vue'
defineProps<{ command: IdentityCommand; message: string; readbackFailed?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <ApiErrorNotice v-if="command.state === 'uncertain'" :error="command.error || true" type="warning" title="还无法确认是否保存成功" guidance="请用下方按钮重试这次操作，不要重新提交一份。"><el-button @click="$emit('retry')">原请求重试</el-button></ApiErrorNotice>
  <ApiErrorNotice v-else :error="command.error" />
  <el-alert v-if="message" role="status" aria-live="polite" :title="message" :type="readbackFailed ? 'warning' : 'success'" :closable="false" />
</template>
