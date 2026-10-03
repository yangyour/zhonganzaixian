import { createRouter, createWebHistory } from 'vue-router'
import { can, initializeSession, session } from './stores/session'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./pages/LoginPage.vue'), meta: { title: '登录' } },
    { path: '/', redirect: '/assets' },
    { path: '/assets', name: 'assets', component: () => import('./pages/AssetsPage.vue'), meta: { title: '资产管理', group: '资源', capability: 'assets:read' } },
    { path: '/manual-incidents', name: 'manual-incidents', component: () => import('./pages/ManualIncidentsPage.vue'), meta: { title: '手工来源登记', group: '服务', capability: 'manual_incidents:read' } },
    { path: '/tickets', name: 'tickets', component: () => import('./pages/TicketsPage.vue'), meta: { title: '报障与受理', group: '服务', capability: 'tickets:read' } },
    { path: '/configurations', name: 'configurations', component: () => import('./pages/ConfigurationsPage.vue'), meta: { title: '配置确认登记', group: '管理', capability: 'configurations:read' } },
    { path: '/audit', name: 'audit', component: () => import('./pages/AuditPage.vue'), meta: { title: '审计日志', group: '管理', capability: 'audit:read' } },
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
