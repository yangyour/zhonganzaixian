<script setup lang="ts">
import type { IdentityCommand } from '../utils/projectIdentity'
import ApiErrorNotice from './ApiErrorNotice.vue'
defineProps<{ command: IdentityCommand; message: string; readbackFailed?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <ApiErrorNotice v-if="command.state === 'uncertain'" :error="command.error || true" type="warning" title="提交结果待确认" guidance="系统已保留当前填写内容，请直接点击下方按钮重试确认，避免修改表单后重复提交。"><el-button @click="$emit('retry')">重试提交</el-button></ApiErrorNotice>
  <ApiErrorNotice v-else :error="command.error" />
  <el-alert v-if="message" role="status" aria-live="polite" :title="message" :type="readbackFailed ? 'warning' : 'success'" :closable="false" />
</template>
