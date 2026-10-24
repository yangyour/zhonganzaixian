<script setup lang="ts">
import { computed } from 'vue'
import { can, session, switchingTenant } from '../stores/session'
import DashboardWorkflowsPanel from '../components/DashboardWorkflowsPanel.vue'

const permitted = computed(() => session.value?.mode === 'remote_test' && can('dashboard:read') && can('tickets:read') && !switchingTenant.value)
</script>
<template>
  <section aria-labelledby="actionable-tickets-title">
    <h1 id="actionable-tickets-title" class="page-title-sr">待处理工单与SLA</h1>
    <p v-if="!permitted" class="mop-empty" role="alert">当前会话不可读取待处理工单与SLA，或正在切换租户；已清除原范围数据。</p>
    <template v-else>
      <p class="scope-note">与首页使用相同待处理范围：排除已关闭、已取消和已解决。每次翻页重新读取当前授权范围与SLA。</p>
      <DashboardWorkflowsPanel full-list />
    </template>
  </section>
</template>
<style scoped>
.scope-note{margin:0 0 12px;color:var(--mop-muted,#5d6e83);font-size:13px;line-height:1.7}
</style>
