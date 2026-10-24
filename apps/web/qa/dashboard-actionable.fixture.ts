// Development-only synthetic identity and intercepted API responses. Never a live integration source.
import { createApp, h } from 'vue'
import { createWebHistory, createRouter, RouterView, useRoute } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
// Match main.ts + App.vue order and their scope below, so component checks use production content styles.
import '../src/styles/navigation.css'
import '../src/styles/identity.css'
import '../src/styles/experience.css'
import '../src/styles/prototype.css'
import '../src/styles/functional-prototype.css'
import '../src/styles/compact-workspaces.css'
import '../src/styles/workspace.css'
import '../src/styles/controls.css'
import '../src/styles/theme.css'
import DashboardPage from '../src/pages/DashboardPage.vue'
import ActionableTicketsPage from '../src/pages/ActionableTicketsPage.vue'
import { session, contextGeneration } from '../src/stores/session'
import { scope } from '../tests/dashboard.fixture'

if (!import.meta.env.DEV) throw Error('Synthetic actionable browser entry is development-only')
session.value = { user: { id: scope.actorId, display_name: 'Synthetic', role: 'viewer' }, tenant: { id: scope.tenantId, name: 'Synthetic' },
  allowed_tenants: [], capabilities: ['dashboard:read', 'tickets:read'], permission_version: scope.permissionVersion,
  csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
const detail = { setup() { const route = useRoute(); return () => h('div', [h('p', { role: 'status' }, '合成工单详情目标：' + String(route.query.ticket_id ?? '')), h('button', { onClick: () => router.back() }, '返回原列表')]) } }
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/dashboard', name: 'dashboard', component: DashboardPage, meta: { capability: 'dashboard:read' } },
  { path: '/dashboard/actionable-tickets', name: 'actionable-tickets', component: ActionableTicketsPage, meta: { capability: 'dashboard:read', capabilities: ['tickets:read'] } },
  { path: '/ticket-workflows', name: 'ticket-workflows', component: detail, meta: { capability: 'tickets:read' } },
  { path: '/tickets', name: 'tickets', component: detail, meta: { capability: 'tickets:read' } },
] })
await router.push('/dashboard')
createApp({ render: () => h('div', { class: 'prototype-shell' }, [h('main', { id: 'main-content', class: 'content', style: 'max-width:1400px;margin:auto' }, [
  h('p', '合成身份与模拟API，仅验证页面交互，不是后端联调或客户验收。'),
  h('button', { onClick: () => { session.value = null; contextGeneration.value++ } }, '模拟撤销权限'),
  h(RouterView),
])]) }).use(router).use(ElementPlus).mount('#fixture')
