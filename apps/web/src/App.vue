<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { can, session, contextGeneration, switchingTenant, maySwitchTenant, switchTenant, logout } from './stores/session'
import { isCancelled } from './api/client'
import { roleLabel } from './utils/display'
import ApiErrorNotice from './components/ApiErrorNotice.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
const route = useRoute()
const router = useRouter()
const navigationOpen = ref(false)
const error = ref<unknown>(null)
const leaving = ref(false)
const tenantTransition = ref(false)
const pageKey = computed(() => `${session.value?.tenant.id}:${session.value?.permission_version}:${contextGeneration.value}`)
watch(() => route.fullPath, () => { navigationOpen.value = false; error.value = null })
async function changeTenant(value: unknown) {
  error.value = null
  tenantTransition.value = true
  try {
    if (await switchTenant(String(value))) await router.replace({ query: { ...route.query, asset_id: undefined, ticket_id: undefined, operation_id: undefined, cursor: undefined, site_id: undefined } })
  }
  catch (cause) { if (!isCancelled(cause)) error.value = cause }
  finally { tenantTransition.value = false }
}
async function endSession() {
  leaving.value = true; error.value = null
  try { if (await logout() === 'local') await router.replace('/login') }
  catch (cause) { if (!isCancelled(cause)) error.value = cause }
  finally { leaving.value = false }
}
</script>
<template>
  <el-config-provider :locale="zhCn">
  <router-view v-if="!session" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="/assets" @click.prevent="router.push('/assets')"><span class="brand-mark">M</span><span><strong>MOP 运维托管</strong><small>网络设备管理平台</small></span></a>
      <div class="nav-heading">资源</div>
      <el-menu :default-active="String(route.name)" class="side-menu" @select="router.push({ name: $event })">
        <el-menu-item index="assets"><span class="nav-symbol">▤</span>资产管理</el-menu-item>
        <el-menu-item v-if="can('manual_incidents:read')" index="manual-incidents"><span class="nav-symbol">◇</span>手工来源登记</el-menu-item>
        <el-menu-item v-if="can('sla_scenarios:read')" index="sla-scenarios">SLA 显式对照</el-menu-item>
        <el-menu-item v-if="can('tickets:read')" index="tickets"><span class="nav-symbol">▣</span>报障与受理</el-menu-item>
        <el-menu-item v-if="session?.user.role === 'msp' && can('worklogs:read_own')" index="my-worklogs">我的实填工时</el-menu-item>
      </el-menu>
      <template v-if="can('configurations:read') || can('audit:read')">
        <div class="nav-heading">管理</div>
        <el-menu :default-active="String(route.name)" class="side-menu" @select="router.push({ name: $event })">
          <el-menu-item v-if="can('configurations:read')" index="configurations"><span class="nav-symbol">⚙</span>配置确认登记</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('inspection_templates:read')" index="inspection-templates">巡检模板候选</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('backup_plan_drafts:read')" index="backup-plan-drafts">每日备份计划草稿</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('change_notice_drafts:read')" index="change-notice-drafts">变更报备草稿</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('inspection_source_collections:read')" index="inspection-source-collections">巡检来源集合草稿</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('inspection_worksheets:read')" index="inspection-worksheets">人工巡检执行单</el-menu-item>
          <el-menu-item v-if="can('contacts:read')" index="customer-contacts">客户联系人</el-menu-item>
          <el-menu-item v-if="can('contacts:write')" index="contact-imports">联系人批量新建</el-menu-item>
          <el-menu-item v-if="can('configurations:read')" index="threshold-diagnostics">阈值候选诊断</el-menu-item>
        <el-menu-item v-if="can('operations:compare_explicit')" index="operational-comparison">经营样本与人力对照</el-menu-item>
        <el-menu-item v-if="can('capacity:estimate')" index="capacity-estimates">容量与缓冲估算</el-menu-item>
          <el-menu-item v-if="can('configurations:read')" index="capability-catalog">代码能力声明核对</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin'" index="tenant-inventory">租户登记资源</el-menu-item>
          <el-menu-item v-if="can('audit:read')" index="audit"><span class="nav-symbol">≡</span>审计日志</el-menu-item>
        </el-menu>
      </template>
      <div class="sidebar-foot"><span class="small-label">共享测试环境</span><p>公共底座与资产纵向切片</p><small>监控采集与生产身份源待确认</small></div>
    </aside>
    <div class="main-layout">
      <header class="topbar">
        <el-button class="mobile-menu" aria-label="打开导航" @click="navigationOpen = true">☰</el-button>
        <el-breadcrumb separator="/"><el-breadcrumb-item>MOP</el-breadcrumb-item><el-breadcrumb-item>{{ route.meta.group }}</el-breadcrumb-item><el-breadcrumb-item>{{ route.meta.title }}</el-breadcrumb-item></el-breadcrumb>
        <div class="topbar-controls">
          <GlobalSearch />
          <el-select v-if="maySwitchTenant" :model-value="session.tenant.id" :disabled="switchingTenant || tenantTransition" class="tenant-select" aria-label="切换租户" @change="changeTenant">
            <el-option v-for="tenant in session.allowed_tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" />
          </el-select>
          <span v-else class="tenant-name">{{ session.tenant.name }}</span>
          <div class="identity"><span class="avatar">{{ session.user.display_name.slice(0, 1) }}</span><div><strong>{{ session.user.display_name }}</strong><small>{{ roleLabel[session.user.role] }}</small></div></div>
          <el-button text :loading="leaving" @click="endSession">退出</el-button>
        </div>
      </header>
      <div class="environment-strip"><span class="environment-indicator" />{{ session.mode === 'remote_test' ? '共享测试环境 · PostgreSQL / Keycloak · 非生产' : '历史隔离单元夹具 · 非集成验收环境' }} · 资产状态尚无真实采集依据</div>
      <main class="content">
        <ApiErrorNotice :error="error" />
        <div v-if="switchingTenant" class="switching-panel"><el-skeleton :rows="5" animated /><p>正在切换租户，已取消旧上下文请求…</p></div>
        <router-view v-else :key="pageKey" />
      </main>
      <footer class="app-footer">MOP 开发验证 V0.1 <span>资产草稿 · 配置登记 · 报障登记 · 审计</span></footer>
    </div>
    <el-drawer v-model="navigationOpen" title="MOP 导航" direction="ltr" size="260px">
      <el-menu :default-active="String(route.name)" @select="router.push({ name: $event })">
        <el-menu-item index="assets">资产管理</el-menu-item>
        <el-menu-item v-if="can('manual_incidents:read')" index="manual-incidents">手工来源登记</el-menu-item>
        <el-menu-item v-if="can('sla_scenarios:read')" index="sla-scenarios">SLA 显式对照</el-menu-item>
        <el-menu-item v-if="can('tickets:read')" index="tickets">报障与受理</el-menu-item>
        <el-menu-item v-if="session?.user.role === 'msp' && can('worklogs:read_own')" index="my-worklogs">我的实填工时</el-menu-item>
        <el-menu-item v-if="can('configurations:read')" index="configurations">配置确认登记</el-menu-item>
        <el-menu-item v-if="session?.user.role === 'padmin' && can('inspection_templates:read')" index="inspection-templates">巡检模板候选</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('backup_plan_drafts:read')" index="backup-plan-drafts">每日备份计划草稿</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('change_notice_drafts:read')" index="change-notice-drafts">变更报备草稿</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('inspection_source_collections:read')" index="inspection-source-collections">巡检来源集合草稿</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin' && can('inspection_worksheets:read')" index="inspection-worksheets">人工巡检执行单</el-menu-item>
          <el-menu-item v-if="can('contacts:read')" index="customer-contacts">客户联系人</el-menu-item>
          <el-menu-item v-if="can('contacts:write')" index="contact-imports">联系人批量新建</el-menu-item>
          <el-menu-item v-if="can('configurations:read')" index="threshold-diagnostics">阈值候选诊断</el-menu-item>
        <el-menu-item v-if="can('operations:compare_explicit')" index="operational-comparison">经营样本与人力对照</el-menu-item>
        <el-menu-item v-if="can('capacity:estimate')" index="capacity-estimates">容量与缓冲估算</el-menu-item>
          <el-menu-item v-if="can('configurations:read')" index="capability-catalog">代码能力声明核对</el-menu-item>
          <el-menu-item v-if="session?.user.role === 'padmin'" index="tenant-inventory">租户登记资源</el-menu-item>
          <el-menu-item v-if="can('audit:read')" index="audit">审计日志</el-menu-item>
      </el-menu>
    </el-drawer>
  </div>
  </el-config-provider>
</template>
