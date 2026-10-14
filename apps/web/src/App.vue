<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { can, session, contextGeneration, switchingTenant, maySwitchTenant, switchTenant, logout } from './stores/session'
import { isCancelled } from './api/client'
import { roleLabel } from './utils/display'
import { landingRoute } from './utils/routeAccess'
import HorizontalModuleNavigation from './components/HorizontalModuleNavigation.vue'
import './styles/navigation.css'
import ApiErrorNotice from './components/ApiErrorNotice.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import './styles/identity.css'
const route = useRoute()
const router = useRouter()
const homeRoute = computed(() => landingRoute(session.value, router.getRoutes()))
const error = ref<unknown>(null)
const leaving = ref(false)
const tenantTransition = ref(false)
const pageKey = computed(() => `${session.value?.tenant.id}:${session.value?.permission_version}:${contextGeneration.value}`)
watch(() => route.fullPath, () => { error.value = null })
async function changeTenant(value: unknown) {
  error.value = null
  tenantTransition.value = true
  try {
    await switchTenant(String(value), async transition => {
      if (session.value?.tenant.id !== transition.toTenantId) return
      const navigation = await router.replace({ query: { ...route.query, asset_id: undefined, ticket_id: undefined, operation_id: undefined, cursor: undefined, site_id: undefined } })
      if (isNavigationFailure(navigation) && !isNavigationFailure(navigation, NavigationFailureType.duplicated)) throw navigation
      // Let route watchers finish before displaying an original uncertain-switch error.
      await nextTick()
    })
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
  <div v-else class="app-shell horizontal-shell">
    <div class="main-layout">
      <div class="app-header">
      <header class="topbar">
        <a class="brand" :href="router.resolve({ name: homeRoute }).href" @click.prevent="router.push({ name: homeRoute })"><span class="brand-mark">M</span><span><strong>MOP 运维托管</strong><small>网络设备管理平台</small></span></a>
        <div class="topbar-controls">
          <GlobalSearch v-if="can('search:read')" />
          <el-select v-if="maySwitchTenant" :model-value="session.tenant.id" :disabled="switchingTenant || tenantTransition" class="tenant-select" aria-label="切换租户" @change="changeTenant">
            <el-option v-for="tenant in session.allowed_tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" />
          </el-select>
          <span v-else class="tenant-name">{{ session.tenant.name }}</span>
          <div class="identity"><span class="avatar">{{ session.user.display_name.slice(0, 1) }}</span><div><strong>{{ session.user.display_name }}</strong><small>{{ roleLabel[session.user.role] }}</small></div></div>
          <el-button v-if="['project_local','oidc'].includes(session.identity_source)" text @click="router.push('/account')">我的账号</el-button>
          <el-button text :loading="leaving" @click="endSession">退出</el-button>
        </div>
      </header>
      <HorizontalModuleNavigation />
      <div class="page-context"><el-breadcrumb separator="/"><el-breadcrumb-item>MOP</el-breadcrumb-item><el-breadcrumb-item>{{ route.meta.group }}</el-breadcrumb-item><el-breadcrumb-item>{{ route.meta.title }}</el-breadcrumb-item></el-breadcrumb></div>
      </div>
      <div class="environment-strip"><span class="environment-indicator" />{{ session.mode === 'remote_test' ? '项目测试环境 · 非生产 · 厂商指标待接入' : '历史单元测试夹具 · 不用于联调验收' }}</div>
      <main class="content">
        <ApiErrorNotice :error="error" />
        <div v-if="switchingTenant" class="switching-panel"><el-skeleton :rows="5" animated /><p>正在切换租户，请稍候…</p></div>
        <router-view v-else :key="pageKey" />
      </main>
      <footer class="app-footer">MOP 测试版 V0.1 <span>资产草稿 · 配置登记 · 报障登记 · 审计</span></footer>
    </div>
  </div>
  </el-config-provider>
</template>
