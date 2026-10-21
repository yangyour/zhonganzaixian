<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { can, session, contextGeneration, switchingTenant, maySwitchTenant, switchTenant, logout } from './stores/session'
import { isCancelled } from './api/client'
import { roleLabel } from './utils/display'
import { landingRoute } from './utils/routeAccess'
import { navigateFromLink } from './utils/linkNavigation'
import HorizontalModuleNavigation from './components/HorizontalModuleNavigation.vue'
import './styles/navigation.css'
import ApiErrorNotice from './components/ApiErrorNotice.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import './styles/identity.css'
import './styles/experience.css'
import ConnectionNotice from './components/ConnectionNotice.vue'
import { navigating, navigationError, navigationTarget, resourceLoadFailed } from './stores/navigation'
import { feedback } from './utils/feedback'
import PageRecoveryNotice from './components/PageRecoveryNotice.vue'
import SessionResumeNotice from './components/SessionResumeNotice.vue'
import { Fold, Expand, Menu } from '@element-plus/icons-vue'
import './styles/prototype.css'
import './styles/functional-prototype.css'
const route = useRoute()
const router = useRouter()
const homeRoute = computed(() => landingRoute(session.value, router.getRoutes()))
const error = ref<unknown>(null)
const leaving = ref(false)
const tenantTransition = ref(false)
const sidebarCollapsed = ref(false)
const mobileNavigationOpen = ref(false)
const pageKey = computed(() => `${session.value?.tenant.id}:${session.value?.permission_version}:${contextGeneration.value}`)
watch(() => route.fullPath, () => { error.value = null })
watch(() => route.path, async (path, previous) => {
  if (path === previous) return
  await nextTick()
  const heading = document.querySelector<HTMLElement>('#main-content h1')
  if (heading) { heading.tabIndex = -1; heading.focus({ preventScroll: true }); window.scrollTo({ top: 0 }) }
})
watch(pageKey, () => { feedback.closeAll(); navigationError.value = null })
async function retryNavigation() {
  if (navigating.value) return
  try { await router.push(navigationTarget.value || route.fullPath) }
  catch (cause) { navigationError.value = cause }
}
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
  <SessionResumeNotice />
  <div class="navigation-progress" :class="{ active: navigating }" aria-hidden="true" />
  <span class="sr-only" role="status">{{ navigating ? '正在打开页面…' : '' }}</span>
  <template v-if="!session">
    <ConnectionNotice />
    <PageRecoveryNotice />
    <ApiErrorNotice v-if="navigationError && !resourceLoadFailed" class="anonymous-navigation-error" :error="navigationError" title="页面暂时未能打开" :retrying="navigating" retry-label="重试打开页面" @retry="retryNavigation" />
    <section v-if="!route.matched.length && !navigationError && !resourceLoadFailed" class="startup-panel" role="status">正在读取登录状态，请稍候…</section>
    <router-view />
  </template>
  <div v-else class="app-shell horizontal-shell prototype-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <a class="skip-link" href="#main-content">跳过导航，进入主要内容</a>
      <header class="topbar">
        <a class="brand" :href="router.resolve({ name: homeRoute }).href" @click="navigateFromLink($event, () => router.push({ name: homeRoute }))"><span class="brand-mark">M</span><span><strong>MOP 运维托管</strong><small>网络设备管理平台</small></span></a>
        <HorizontalModuleNavigation v-model:mobile-open="mobileNavigationOpen" :collapsed="sidebarCollapsed" />
        <div class="topbar-controls">
          <div class="identity"><span class="avatar">{{ session.user.display_name.slice(0, 1) }}</span><div><strong>{{ session.user.display_name }}</strong><small>{{ roleLabel[session.user.role] }}</small></div></div>
          <el-button v-if="['project_local','oidc','wecom'].includes(session.identity_source)" text class="account-button" @click="router.push('/account')">我的账号</el-button>
          <el-button text :loading="leaving" @click="endSession">退出</el-button>
        </div>
      </header>
    <div class="main-layout">
      <div class="app-header">
        <div class="page-context">
          <button type="button" class="sidebar-toggle desktop-sidebar-toggle" :aria-label="sidebarCollapsed ? '展开功能菜单' : '收起功能菜单'" :aria-expanded="!sidebarCollapsed" aria-controls="workspace-sidebar" @click="sidebarCollapsed = !sidebarCollapsed"><component :is="sidebarCollapsed ? Expand : Fold" /></button>
          <button type="button" class="sidebar-toggle mobile-sidebar-toggle" aria-label="打开功能菜单" aria-haspopup="dialog" aria-controls="mobile-navigation-panel" :aria-expanded="mobileNavigationOpen" @click="mobileNavigationOpen = true"><Menu /></button>
          <el-breadcrumb separator="/"><el-breadcrumb-item>{{ route.meta.group }}</el-breadcrumb-item><el-breadcrumb-item>{{ route.meta.title }}</el-breadcrumb-item></el-breadcrumb>
          <GlobalSearch v-if="can('search:read')" />
          <div class="context-controls">
          <el-select v-if="maySwitchTenant" :model-value="session.tenant.id" :disabled="switchingTenant || tenantTransition" class="tenant-select" aria-label="切换租户" @change="changeTenant">
            <el-option v-for="tenant in session.allowed_tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" />
          </el-select>
          <span v-else class="tenant-name">{{ session.tenant.name }}</span>
          <span class="environment-label"><span class="environment-indicator" />{{ session.mode === 'remote_test' ? '测试环境' : '本地演示模式' }}</span>
          </div>
        </div>
      </div>
      <main id="main-content" class="content" tabindex="-1">
        <ConnectionNotice />
        <PageRecoveryNotice />
        <ApiErrorNotice v-if="!resourceLoadFailed" :error="navigationError" title="页面暂时未能打开" guidance="当前页面已保留。请检查连接后重试；已提交的操作请先核对结果。" :retrying="navigating" retry-label="重试打开页面" @retry="retryNavigation" />
        <ApiErrorNotice :error="error" />
        <div v-if="switchingTenant" class="switching-panel"><el-skeleton :rows="5" animated /><p>正在切换租户，请稍候…</p></div>
        <router-view v-else :key="pageKey" />
      </main>
      <footer class="app-footer">MOP 运维托管平台 <span>数据与操作结果以当前授权接口的实际回读为准</span></footer>
    </div>
  </div>
  </el-config-provider>
</template>
