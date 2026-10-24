// Development-only synthetic identity; browser tests intercept every API request.
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
import SlaReportsPage from '../src/pages/SlaReportsPage.vue'
import { session, contextGeneration } from '../src/stores/session'
import wire from '../../../fixtures/customer-sla-controller.json'

if (!import.meta.env.DEV) throw new Error('Synthetic SLA source entry is development-only')
session.value = { user: { id: wire.report.monthly_result.evaluated_by, display_name: 'SYNTHETIC', role: 'msp' },
  tenant: { id: wire.report.tenant_id, name: 'SYNTHETIC' }, allowed_tenants: [], permission_version: 3,
  csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'synthetic-unit-only',
  capabilities: ['sla_scenarios:read', 'ticket_notes:write_internal'] }
const router = createRouter({ history: createWebHistory(), routes: [{ path: '/sla-reports', component: SlaReportsPage }] })
await router.push('/sla-reports')
createApp({ render: () => h('div', { class: 'prototype-shell' }, [h('main', {
  id:'main-content',class: 'content', style: 'max-width:1400px;margin:auto',
}, [h('p', '合成身份与模拟API，仅验证指标状态和导出，不是指定环境或客户验收。'),
  h('button', { onClick: () => { session.value = null; contextGeneration.value++ } }, '模拟撤销权限'),
  h(RouterView)])]) }).use(router).use(ElementPlus).mount('#fixture')
