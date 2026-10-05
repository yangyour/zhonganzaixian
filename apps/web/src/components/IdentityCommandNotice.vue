<script setup lang="ts">
import type { IdentityCommand } from '../utils/projectIdentity'
import ApiErrorNotice from './ApiErrorNotice.vue'
defineProps<{ command: IdentityCommand; message: string; readbackFailed?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <ApiErrorNotice :error="command.error" />
  <el-alert v-if="command.state === 'uncertain'" title="原操作结果尚未确认，请使用原请求重试；不要新建重复操作。" type="warning" :closable="false"><el-button @click="$emit('retry')">原请求重试</el-button></el-alert>
  <el-alert v-if="message" :title="message" :type="readbackFailed ? 'warning' : 'success'" :closable="false" />
</template>
