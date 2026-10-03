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
    { path: '/sla-scenarios', name: 'sla-scenarios', component: () => import('./pages/SlaScenariosPage.vue'), meta: { title: 'SLA 显式对照', group: '服务', capability: 'sla_scenarios:read' } },
    { path: '/configurations', name: 'configurations', component: () => import('./pages/ConfigurationsPage.vue'), meta: { title: '配置确认登记', group: '管理', capability: 'configurations:read' } },
    { path: '/capacity-estimates', name: 'capacity-estimates', component: () => import('./pages/CapacityEstimatePage.vue'), meta: { title: '容量与缓冲估算', group: '管理', capability: 'capacity:estimate' } },
    { path: '/capability-catalog', name: 'capability-catalog', component: () => import('./pages/CapabilityCatalogPage.vue'), meta: { title: '代码能力声明核对', group: '管理', capability: 'configurations:read' } },
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
