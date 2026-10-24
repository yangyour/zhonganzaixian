// Synthetic UI-only identity; all business responses are intercepted by the browser spec.
import { createApp, h } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/theme.css'
import Panel from '../src/components/DeliveryJournalPanel.vue'
import { session, contextGeneration } from '../src/stores/session'
import { deliveryScope } from '../tests/deliveryJournal.fixture'
session.value = { user: { id: deliveryScope.actor, display_name: 'SYNTHETIC', role: 'padmin' },
  tenant: { id: deliveryScope.tenant, name: 'SYNTHETIC' }, allowed_tenants: [], permission_version: 1,
  account_version: 1, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'synthetic',
  capabilities: ['audit:read', 'ticket_notes:read_internal'] }
createApp({ render: () => h('main', { id: 'main-content', style: 'padding:24px;max-width:1400px;margin:auto' }, [
  h('h1', 'SYNTHETIC 通知待核对清单'), h('p', '明确合成身份与拦截API，仅作桌面交互检查。'),
  h('button', { onClick: () => { session.value = null; contextGeneration.value++ } }, '模拟撤权'), h(Panel),
]) }).use(ElementPlus).mount('#fixture')
