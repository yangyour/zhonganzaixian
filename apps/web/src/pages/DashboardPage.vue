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
  <div class="compact-page-toolbar dashboard-toolbar">
   <h1 id="dashboard-title" class="page-title-sr">运营总览</h1>
   <label v-if="permitted && can('assets:read')" class="auto-refresh"><input v-model="autoRefresh" type="checkbox" aria-label="启用监控自动刷新" />自动刷新监控</label>
  </div>
  <p v-if="!permitted" role="alert" class="dashboard-blocked">当前会话不可读取运营总览，或正在切换租户；已清除原范围数据</p>
  <div v-else :key="contextKey" class="dashboard-grid">
   <div class="dashboard-col dashboard-main">
    <LiveMonitoringPanel v-if="can('assets:read')" :auto-refresh="autoRefresh" />
    <DashboardWorkflowsPanel v-if="can('tickets:read')" />
   </div>
   <div class="dashboard-col dashboard-side">
    <DashboardEventsPanel />
    <DashboardReportsPanel v-if="can('tickets:read')" />
   </div>
  </div>
 </section>
</template>
<style scoped>
.dashboard-page{min-width:0;max-width:100%}
.dashboard-toolbar{min-height:44px}
.auto-refresh{display:inline-flex;align-items:center;gap:8px;min-height:36px;padding:0 12px;border:1px solid #dbe3ee;border-radius:999px;background:#fff;color:#334155;font-size:13px;cursor:pointer}
.auto-refresh input{width:16px;height:16px;margin:0;accent-color:#2563eb}
.auto-refresh input:focus-visible{outline:2px solid #2563eb;outline-offset:3px}
.dashboard-blocked{padding:16px;border:1px solid #fde68a;border-radius:10px;background:#fffbeb;color:#92400e}
.dashboard-grid{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;align-items:start;min-width:0}
.dashboard-col{display:flex;flex-direction:column;gap:16px;min-width:0}
.dashboard-col:empty{display:none}
.dashboard-col>section{margin:0}
@media(min-width:1200px){.dashboard-grid{grid-template-columns:minmax(0,3fr) minmax(340px,2fr)}}
@media(max-width:760px){.auto-refresh{min-height:44px}}
</style>
