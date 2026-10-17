<script setup lang="ts">
import type { IdentityCommand } from '../utils/projectIdentity'
import ApiErrorNotice from './ApiErrorNotice.vue'
defineProps<{ command: IdentityCommand; message: string; readbackFailed?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <ApiErrorNotice v-if="command.state === 'uncertain'" :error="command.error || true" type="warning" title="暂未收到服务端确认回执" guidance="请点击下方按钮凭原请求幂等标识重试，请勿修改表单后重复提交。"><el-button @click="$emit('retry')">重试当前请求</el-button></ApiErrorNotice>
  <ApiErrorNotice v-else :error="command.error" />
  <el-alert v-if="message" role="status" aria-live="polite" :title="message" :type="readbackFailed ? 'warning' : 'success'" :closable="false" />
</template>
