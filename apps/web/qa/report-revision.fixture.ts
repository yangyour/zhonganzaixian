// Development-only synthetic identity. Every API response is intercepted by the browser test.
import { createApp, h } from 'vue'
import { createWebHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/navigation.css'
import '../src/styles/identity.css'
import '../src/styles/experience.css'
import '../src/styles/prototype.css'
import '../src/styles/functional-prototype.css'
import '../src/styles/compact-workspaces.css'
import '../src/styles/workspace.css'
import '../src/styles/controls.css'
import '../src/styles/theme.css'
import ReportPublicationsPage from '../src/pages/ReportPublicationsPage.vue'
import InspectionQuarterlyReportsPage from '../src/pages/InspectionQuarterlyReportsPage.vue'
import { session, contextGeneration } from '../src/stores/session'

if (!import.meta.env.DEV) throw new Error('Synthetic revision entry is development-only')
session.value = {
  user: { id: '79000000-0000-4000-8000-000000000002', display_name: 'SYNTHETIC', role: 'padmin' },
  tenant: { id: '79000000-0000-4000-8000-000000000001', name: 'SYNTHETIC' }, allowed_tenants: [],
  permission_version: 7, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test',
  identity_source: 'synthetic-unit-only', capabilities: ['dashboard:read', 'inspection_quarterly_reports:read',
    'inspection_quarterly_reports:write', 'inspection_source_collections:read', 'configurations:write'],
}
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/report-publications', component: ReportPublicationsPage },
  { path: '/inspection-quarterly-reports', component: InspectionQuarterlyReportsPage },
] })
await router.push({ path: '/report-publications', query: { publication_id: '79000000-0000-4000-8000-0000000003e8' } })
createApp({ render: () => h('div', { class: 'prototype-shell' }, [h('main', {
  class: 'content', style: 'max-width:1400px;margin:auto',
}, [h('p', '合成身份与模拟API，仅验证页面交互，不是指定环境联调或客户验收。'),
  h('button', { onClick: () => { session.value = null; contextGeneration.value++ } }, '模拟撤销权限'),
  h(RouterView)])]) }).use(router).use(ElementPlus).mount('#fixture')
