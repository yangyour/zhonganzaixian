<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { session, contextGeneration } from '../stores/session'
import { createProductionReadiness, productionCheckLabels } from '../utils/productionReadiness'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { displayTime } from '../utils/display'

const flow = createProductionReadiness(() => ({ tenantId: session.value?.tenant.id || '', actorId: session.value?.user.id || '',
  permissionVersion: session.value?.permission_version || 0 }))
const statuses = { PASS: '检查通过', BLOCKED: '尚未满足', NOT_READY: '未就绪', NOT_RUN: '未检查', CUSTOMER_GATED: '待客户确认' }
watch([() => contextGeneration.value, () => session.value?.tenant.id, () => session.value?.user.id, () => session.value?.permission_version], flow.reset)
onBeforeUnmount(flow.reset)
</script>

<template>
  <details class="panel">
    <summary>生产启用检查</summary>
    <p class="field-help">检查当前测试运行边界；数据库生产隔离与公开入口证书仍须分别确认。</p>
    <el-button :loading="flow.busy.value" @click="flow.inspect">检查运行隔离</el-button>
    <el-button v-if="flow.busy.value" @click="flow.reset">取消检查</el-button>
    <ApiErrorNotice :error="flow.error.value" />
    <template v-if="flow.result.value">
      <p role="status">生产未就绪；{{ flow.result.value.runtime_database_restricted ? '当前测试数据库账号符合运行限制。' : '当前数据库运行限制未通过或未能核实。' }}</p>
      <p v-if="flow.result.value.database_observed_at">数据库观测：{{ displayTime(flow.result.value.database_observed_at) }} · {{ flow.result.value.database_version }}</p>
      <ul><li v-for="check in flow.result.value.checks" :key="check.id">{{ productionCheckLabels[check.id] }}：{{ statuses[check.status] }}</li></ul>
    </template>
  </details>
</template>
