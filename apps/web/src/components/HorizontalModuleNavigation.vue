<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { session, contextGeneration, switchingTenant } from '../stores/session'
import { navigationError, navigationTarget } from '../stores/navigation'
import { visibleNavigationModules, navigationEntryName, type NavigationModule } from '../utils/navigationModules'
import NavigationIcon from './NavigationIcon.vue'
import SidebarNavigation from './SidebarNavigation.vue'
import { navigateFromLink } from '../utils/linkNavigation'
defineProps<{ collapsed?: boolean }>()
const mobileOpen = defineModel<boolean>('mobileOpen', { default: false })
const router = useRouter(), route = useRoute(), moduleNav = ref<HTMLElement>()
let compactQuery: MediaQueryList | undefined, closedForWideViewport = false
function viewportChanged(event: MediaQueryListEvent) {
  if (event.matches || !mobileOpen.value) return
  closedForWideViewport = true
  mobileOpen.value = false
}
function restoreAfterResize() {
  if (!closedForWideViewport) return
  closedForWideViewport = false
  if (!mobileOpen.value && !compactQuery?.matches) document.querySelector<HTMLElement>('.desktop-sidebar-toggle')?.focus({ preventScroll: true })
}
onMounted(() => { compactQuery = window.matchMedia('(max-width: 1024px)'); compactQuery.addEventListener('change', viewportChanged) })
onBeforeUnmount(() => compactQuery?.removeEventListener('change', viewportChanged))
watch(mobileOpen, value => { if (value) closedForWideViewport = false })
const modules = computed(() => switchingTenant.value ? [] : visibleNavigationModules(session.value, router.getRoutes()))
const activeModule = computed(() => modules.value.find(module => module.items.some(item => item.name === navigationEntryName(route.name))) || modules.value[0])
const scopeKey = computed(() => JSON.stringify([session.value?.user.id, session.value?.tenant.id, session.value?.permission_version, session.value?.user.role, session.value?.capabilities, contextGeneration.value, switchingTenant.value]))
watch(scopeKey, () => { closedForWideViewport = false; mobileOpen.value = false }, { flush: 'sync' })
watch(() => route.name, async () => {
  closedForWideViewport = false
  mobileOpen.value = false
  await nextTick()
  // Scroll only the module strip; never move the page or a user's editor.
  const nav = moduleNav.value, selected = nav?.querySelector<HTMLElement>('.active')
  if (nav && selected) {
    const left = selected.offsetLeft - nav.offsetLeft
    if (left < nav.scrollLeft) nav.scrollLeft = left
    else if (left + selected.offsetWidth > nav.scrollLeft + nav.clientWidth) nav.scrollLeft = left + selected.offsetWidth - nav.clientWidth
  }
})
async function navigate(name: string) {
  if (!modules.value.some(module => module.items.some(item => item.name === name))) return
  try {
    const failure = await router.push({ name })
    if (!isNavigationFailure(failure) || isNavigationFailure(failure, NavigationFailureType.duplicated)) mobileOpen.value = false
  } catch (error) {
    navigationTarget.value = router.resolve({ name }).href
    navigationError.value = error
  }
}
function selectModule(module: NavigationModule) {
  if (module.items.some(item => item.name === navigationEntryName(route.name))) { if (window.matchMedia('(max-width: 1024px)').matches) mobileOpen.value = true; return }
  const first = module.items[0]
  if (first) void navigate(first.name)
}
</script>
<template>
  <nav v-if="modules.length" ref="moduleNav" class="module-navigation" aria-label="模块导航">
    <a v-for="module in modules" :key="module.id" :href="router.resolve({ name: module.items[0]!.name }).href" class="primary-nav-link" :class="{ active: module.id === activeModule?.id }" :aria-current="module.id === activeModule?.id ? 'true' : undefined" @click="navigateFromLink($event, () => selectModule(module))">
      <span class="module-icon" :class="'module-' + module.id"><NavigationIcon :name="module.id" /></span><span>{{ module.title }}</span>
    </a>
  </nav>
  <aside id="workspace-sidebar" class="workspace-sidebar" :class="{ collapsed }" aria-label="功能菜单">
    <SidebarNavigation v-if="activeModule" :key="scopeKey" :module="activeModule" :collapsed="collapsed" @navigate="navigate" />
    <p v-else class="sidebar-no-access">{{ switchingTenant ? '正在切换租户…' : '暂无可用业务权限' }}</p>
    <div class="workspace-sidebar-foot"><span class="environment-indicator" /><span>项目测试环境<small>状态以实际回执为准</small></span></div>
  </aside>
  <el-drawer v-model="mobileOpen" class="mobile-navigation-drawer" title="功能菜单" direction="ltr" size="280px" append-to-body @closed="restoreAfterResize">
    <div id="mobile-navigation-panel">
    <SidebarNavigation v-if="activeModule && mobileOpen" :key="scopeKey" :module="activeModule" @navigate="navigate" />
    <p v-else>当前没有可用的业务入口。</p>
    </div>
  </el-drawer>
</template>
