import { createRouter, createWebHistory } from 'vue-router'
import { can, initializeSession, session } from './stores/session'
import LoginPage from './pages/LoginPage.vue'
import AssetsPage from './pages/AssetsPage.vue'
import ConfigurationsPage from './pages/ConfigurationsPage.vue'
import AuditPage from './pages/AuditPage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginPage, meta: { title: '登录' } },
    { path: '/', redirect: '/assets' },
    { path: '/assets', name: 'assets', component: AssetsPage, meta: { title: '资产管理', group: '资源', capability: 'assets:read' } },
    { path: '/configurations', name: 'configurations', component: ConfigurationsPage, meta: { title: '配置确认登记', group: '管理', capability: 'configurations:read' } },
    { path: '/audit', name: 'audit', component: AuditPage, meta: { title: '审计日志', group: '管理', capability: 'audit:read' } },
    { path: '/:pathMatch(.*)*', redirect: '/assets' },
  ],
})
router.beforeEach(async to => {
  try { await initializeSession() }
  catch { if (to.name !== 'login') return { name: 'login' } }
  if (!session.value && to.name !== 'login') return { name: 'login' }
  if (session.value && to.name === 'login') return { name: 'assets' }
  if (to.meta.capability && !can(String(to.meta.capability))) return { name: 'assets' }
  document.title = `${String(to.meta.title || '管理端')} · MOP`
})
