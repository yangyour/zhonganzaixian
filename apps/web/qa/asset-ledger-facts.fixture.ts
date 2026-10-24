// The actual AssetsPage with synthetic HTTP interception; no fixture authorization or live integration claim.
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
import AssetsPage from '../src/pages/AssetsPage.vue'
import { session, contextGeneration } from '../src/stores/session'
import { ledgerActor, ledgerTenant } from '../tests/assetLedgerFacts.fixture'

if (!import.meta.env.DEV) throw Error('Synthetic asset ledger browser entry is development-only')
session.value = { user: { id: ledgerActor, display_name: 'Synthetic reader', role: 'viewer' }, tenant: { id: ledgerTenant, name: 'Synthetic' },
  allowed_tenants: [], capabilities: ['assets:read'], permission_version: 7, account_version: 1,
  csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/assets', name: 'assets', component: AssetsPage, meta: { capability: 'assets:read' } },
  { path: '/customer-topology', name: 'customer-topology', component: { template: '<p>合成导航目标</p>' } },
  { path: '/network-monitoring', name: 'network-monitoring', component: { template: '<p>合成导航目标</p>' } },
] })
await router.push('/assets?page_size=15')
createApp({ render: () => h('div', { class: 'prototype-shell' }, [h('main', { id: 'main-content', class: 'content', style: 'max-width:1400px;margin:auto' }, [
  h('p', '合成身份与模拟API，仅验证公开登记资料交互，不是后端联调或客户验收。'),
  h('button', { onClick: () => { session.value = null; contextGeneration.value++ } }, '模拟撤销资产读取权限'),
  h(RouterView),
])]) }).use(router).use(ElementPlus).mount('#fixture')
