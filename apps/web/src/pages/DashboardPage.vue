<script setup lang="ts">
import { computed, ref } from 'vue'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import LiveMonitoringPanel from '../components/LiveMonitoringPanel.vue'
import DashboardEventsPanel from '../components/DashboardEventsPanel.vue'
import DashboardWorkflowsPanel from '../components/DashboardWorkflowsPanel.vue'
import DashboardReportsPanel from '../components/DashboardReportsPanel.vue'
const autoRefresh = ref(true)
const permitted = computed(() => Boolean(session.value?.mode === 'remote_test' && ['viewer','tadmin','msp','padmin'].includes(session.value.user.role) && can('dashboard:read') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, [...(session.value?.capabilities ?? [])].sort(), permitted.value]))
</script>
<template>
 <section class="dashboard-page" aria-labelledby="dashboard-title">
  <h1 id="dashboard-title" class="page-title-sr">运营总览</h1>
  <p v-if="!permitted" role="alert">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据</p>
  <div v-else :key="contextKey" class="dashboard-content">
   <label v-if="can('assets:read')" class="auto-refresh"><input v-model="autoRefresh" type="checkbox" aria-label="启用监控自动刷新" />自动刷新监控</label>
   <LiveMonitoringPanel v-if="can('assets:read')" :auto-refresh="autoRefresh" />
   <DashboardEventsPanel />
   <DashboardReportsPanel v-if="can('tickets:read')" />
   <DashboardWorkflowsPanel v-if="can('tickets:read')" />
  </div>
 </section>
</template>
<style scoped>
.dashboard-page,.dashboard-content{min-width:0;max-width:100%}.auto-refresh{display:flex;align-items:center;gap:8px;min-height:44px;justify-content:flex-end;font-size:13px}.auto-refresh input:focus-visible{outline:2px solid #2563eb;outline-offset:3px}
@media(max-width:760px){.auto-refresh{justify-content:flex-start}}
</style>
