<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { session, contextGeneration, can, switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createAttachmentSecurityFlow } from '../utils/attachmentSecurity'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = defineProps<{ fileId: string }>()
const emit = defineEmits<{ contentAccess: [allowed: boolean]; pendingChange: [pending: boolean] }>()
const permitted = computed(() => session.value?.mode === 'remote_test' && can('tickets:read') && !switchingTenant.value)
const flow = createAttachmentSecurityFlow(() => ({ fileId: props.fileId, tenantId: session.value?.tenant.id || '',
  actorId: session.value?.user.id || '', permissionVersion: session.value?.permission_version ?? -1,
  accountVersion: session.value?.account_version ?? -1 }))
const scope = computed(() => JSON.stringify([props.fileId, contextGeneration.value, session.value?.user.id,
  session.value?.tenant.id, session.value?.permission_version, session.value?.account_version, permitted.value]))
function mayLeave() { return !flow.pending.value && !flow.busy.value && !flow.restorationBlocked.value }
onBeforeRouteLeave(mayLeave)
const unregister = contextTransitionGuards.register(mayLeave)
watch(() => !mayLeave(), pending => emit('pendingChange', pending), { immediate: true, flush: 'sync' })
function beforeUnload(event: BeforeUnloadEvent) { if (!mayLeave()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
const labels = { QUARANTINED: '隔离中', QUEUED: '等待扫描', CANCELLED: '扫描已取消，保持隔离', CLEAN: '扫描通过，待发布',
  REJECTED: '检测到威胁，禁止发布', UNKNOWN: '扫描结果未知，保持隔离', RELEASED: '已发布' }
watch(scope, () => { flow.reset(); if (permitted.value) void flow.refresh() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { flow.dispose(); unregister(); window.removeEventListener('beforeunload', beforeUnload) })
watch(() => Boolean(permitted.value && flow.snapshot.value?.content_allowed && !flow.pending.value && !flow.reading.value && !flow.busy.value && !flow.restorationBlocked.value),
  allowed => emit('contentAccess', allowed), { immediate: true, flush: 'sync' })
</script>

<template>
  <section v-if="permitted" aria-label="附件安全状态">
    <p v-if="flow.snapshot.value" role="status">
      {{ labels[flow.snapshot.value.state] }} · 版本 {{ flow.snapshot.value.version }}
      <span v-if="flow.snapshot.value.scanner_status === 'NOT_CONFIGURED'"> · 扫描服务未配置</span>
      <span v-if="!flow.snapshot.value.content_allowed"> · 原图不可读取</span>
    </p>
    <ApiErrorNotice :error="flow.error.value" />
    <p v-if="flow.pending.value" role="alert">附件操作结果待确认，请使用原请求核对后再离开。</p>
    <p v-if="flow.restorationBlocked.value" role="alert">原请求记录无法安全恢复，已阻止新增操作，请核对后台记录。</p>
    <div class="photo-row-actions">
      <el-button size="small" :loading="flow.reading.value" @click="flow.refresh">刷新安全状态</el-button>
      <template v-if="flow.snapshot.value && !flow.pending.value && !flow.restorationBlocked.value">
        <el-button v-if="flow.snapshot.value.can_request_scan && flow.snapshot.value.state !== 'QUEUED'" size="small" :disabled="flow.busy.value || flow.reading.value" @click="flow.act('REQUEST_SCAN')">申请扫描</el-button>
        <el-button v-if="flow.snapshot.value.can_request_scan && flow.snapshot.value.state === 'QUEUED'" size="small" :disabled="flow.busy.value || flow.reading.value" @click="flow.act('CANCEL_SCAN')">取消扫描申请</el-button>
        <el-button v-if="flow.snapshot.value.release_allowed" size="small" :disabled="flow.busy.value || flow.reading.value" @click="flow.act('RELEASE')">发布附件</el-button>
        <el-button v-if="flow.snapshot.value.can_manage_release" size="small" :disabled="flow.busy.value || flow.reading.value" @click="flow.act('QUARANTINE')">保持隔离</el-button>
      </template>
      <el-button v-if="flow.busy.value" size="small" @click="flow.stopWaiting">停止等待</el-button>
      <el-button v-else-if="flow.pending.value" size="small" @click="flow.retry">按原请求确认结果</el-button>
    </div>
  </section>
</template>
