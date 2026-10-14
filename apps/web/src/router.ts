import { createRouter, createWebHistory } from 'vue-router'
import { initializeSession, refreshProjectSession, session } from './stores/session'
import { landingRoute, routeAllowed } from './utils/routeAccess'
import { navigating, navigationError, navigationTarget } from './stores/navigation'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./pages/LoginPage.vue'), meta: { title: '登录' } },
    { path: '/access-unavailable', name: 'access-unavailable', component: () => import('./pages/AccessUnavailablePage.vue'), meta: { title: '暂无业务权限', group: '账号' } },
    { path: '/', redirect: '/assets' },
    { path: '/account', name: 'account', component: () => import('./pages/AccountPage.vue'), meta: { title: '我的账号', group: '账号' } },
    { path: '/identity/users', name: 'identity-users', component: () => import('./pages/IdentityUsersPage.vue'), meta: { title: '用户与成员授权', group: '管理', capability: 'users:read' } },
    { path: '/identity/roles', name: 'identity-roles', component: () => import('./pages/IdentityRolesPage.vue'), meta: { title: '角色与权限', group: '管理', capability: 'roles:read' } },
    { path: '/identity/settings', name: 'identity-settings', component: () => import('./pages/IdentitySettingsPage.vue'), meta: { title: '身份认证设置', group: '管理', capability: 'roles:read' } },
    { path: '/dashboard', name: 'dashboard', component: () => import('./pages/DashboardPage.vue'), meta: { title: '运营总览', group: '资源', capability: 'dashboard:read' } },
    { path: '/assets', name: 'assets', component: () => import('./pages/AssetsPage.vue'), meta: { title: '资产管理', group: '资源', capability: 'assets:read' } },
    { path: '/network-monitoring', name: 'network-monitoring', component: () => import('./pages/NetworkMonitoringPage.vue'), meta: { title: '基础网络监控', group: '资源', capability: 'assets:read' } },
    { path: '/manual-incidents', name: 'manual-incidents', component: () => import('./pages/ManualIncidentsPage.vue'), meta: { title: '手工来源登记', group: '服务', capability: 'manual_incidents:read' } },
    { path: '/tickets', name: 'tickets', component: () => import('./pages/TicketsPage.vue'), meta: { title: '报障与受理', group: '服务', capability: 'tickets:read' } },
    { path: '/my-notification-preferences', name: 'my-notification-preferences', component: () => import('./pages/OwnNotificationPreferencesPage.vue'), meta: { title: '我的通知偏好请求', group: '服务', capability: 'notification_preferences:read_own' } },
    { path: '/asset-export-jobs', name: 'asset-export-jobs', component: () => import('./pages/AssetExportJobsPage.vue'), meta: { title: '台账导出准备', group: '服务', roles: ['padmin','msp'], capability: 'assets:read' } },
    { path: '/my-worklogs', name: 'my-worklogs', component: () => import('./pages/OwnWorklogsPage.vue'), meta: { title: '我的实填工时', group: '服务', role: 'msp', capability: 'worklogs:read_own' } },
    { path: '/sla-scenarios', name: 'sla-scenarios', component: () => import('./pages/SlaScenariosPage.vue'), meta: { title: 'SLA 显式对照', group: '服务', capability: 'sla_scenarios:read' } },
    { path: '/configurations', name: 'configurations', component: () => import('./pages/ConfigurationsPage.vue'), meta: { title: '配置确认登记', group: '管理', capability: 'configurations:read' } },
    { path: '/threshold-diagnostics', name: 'threshold-diagnostics', component: () => import('./pages/ThresholdDiagnosticsPage.vue'), meta: { title: '阈值候选诊断', group: '管理', capability: 'configurations:read' } },
    { path: '/contact-imports', name: 'contact-imports', component: () => import('./pages/ContactImportsPage.vue'), meta: { title: '联系人批量新建与作业', group: '管理', capability: 'contacts:write' } },
    { path: '/contact-import-preview', name: 'contact-import-preview', component: () => import('./pages/ContactImportPreviewPage.vue'), meta: { title: '联系人导入格式预览', group: '管理', capability: 'contacts:write' } },
    { path: '/inspection-templates', name: 'inspection-templates', component: () => import('./pages/InspectionTemplatesPage.vue'), meta: { title: '巡检模板候选', group: '管理', role: 'padmin', capability: 'inspection_templates:read' } },
    { path: '/backup-plan-drafts', name: 'backup-plan-drafts', component: () => import('./pages/BackupPlanDraftsPage.vue'), meta: { title: '每日备份计划草稿', group: '管理', role: 'padmin', capability: 'backup_plan_drafts:read' } },
    { path: '/change-notice-drafts', name: 'change-notice-drafts', component: () => import('./pages/ChangeNoticeDraftsPage.vue'), meta: { title: '变更报备草稿', group: '管理', role: 'padmin', capability: 'change_notice_drafts:read' } },
    { path: '/inspection-source-collections', name: 'inspection-source-collections', component: () => import('./pages/InspectionSourceCollectionsPage.vue'), meta: { title: '巡检来源集合草稿', group: '管理', role: 'padmin', capability: 'inspection_source_collections:read' } },
    { path: '/inspection-worksheets', name: 'inspection-worksheets', component: () => import('./pages/InspectionWorksheetsPage.vue'), meta: { title: '人工巡检执行单', group: '管理', role: 'padmin', capability: 'inspection_worksheets:read' } },
    { path: '/customer-contacts', name: 'customer-contacts', component: () => import('./pages/CustomerContactsPage.vue'), meta: { title: '客户联系人', group: '管理', capability: 'contacts:read' } },
    { path: '/operational-comparison', name: 'operational-comparison', component: () => import('./pages/OperationalComparisonPage.vue'), meta: { title: '经营样本与人力对照', group: '管理', capability: 'operations:compare_explicit' } },
    { path: '/capacity-estimates', name: 'capacity-estimates', component: () => import('./pages/CapacityEstimatePage.vue'), meta: { title: '容量与缓冲估算', group: '管理', capability: 'capacity:estimate' } },
    { path: '/capability-catalog', name: 'capability-catalog', component: () => import('./pages/CapabilityCatalogPage.vue'), meta: { title: '代码能力声明核对', group: '管理', capability: 'configurations:read' } },
    { path: '/tenant-inventory', name: 'tenant-inventory', component: () => import('./pages/TenantInventoryPage.vue'), meta: { title: '租户登记资源', group: '管理', role: 'padmin', capability: 'tenants:read_inventory' } },
    { path: '/audit', name: 'audit', component: () => import('./pages/AuditPage.vue'), meta: { title: '审计日志', group: '管理', capability: 'audit:read' } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('./pages/NotFoundPage.vue'), meta: { title: '页面未找到', group: '导航' } },
  ],
})
router.beforeEach(async to => {
  navigating.value = true
  navigationTarget.value = to.fullPath
  navigationError.value = null
  try { await initializeSession() }
  catch { if (to.name !== 'login') return { name: 'login' } }
  if (!session.value && to.name !== 'login') return { name: 'login' }
  if (to.name !== 'login' && ['project_local','oidc'].includes(session.value?.identity_source ?? '')) {
    try { await refreshProjectSession() } catch (cause) { if (!session.value) return { name: 'login' }; navigationError.value = cause; return false }
  }
  const landing = session.value?.password_change_required ? 'account' : landingRoute(session.value, router.getRoutes())
  if (session.value?.password_change_required && to.name !== 'account') return { name: 'account' }
  if (session.value && (to.name === 'login' || to.name === 'access-unavailable' && landing !== 'access-unavailable')) return { name: landing }
  if (to.name === 'not-found' && landing === 'access-unavailable') return { name: landing }
  if (!['login','access-unavailable','account','not-found'].includes(String(to.name)) && !routeAllowed(to.meta, session.value)) return { name: landing }

  document.title = `${String(to.meta.title || '管理端')} · MOP`
})
router.afterEach(() => { navigating.value = false })
router.onError(cause => { navigating.value = false; navigationError.value = cause })
