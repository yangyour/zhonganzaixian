// Synthetic payload builders for the development-only UI preview. All names, addresses
// (RFC 5737 documentation ranges) and identifiers are invented and labelled as such.
import eventFixture from '../../../fixtures/monitoring-event-center-controller.json'
import { row as dashboardRow } from '../tests/dashboard.fixture'
import { metricNames } from '../src/utils/networkMonitoring'

export interface SyntheticRequest { url: URL; path: string; params: string[]; role: 'padmin' | 'msp' | 'tadmin' | 'viewer' }
interface Route { name: string; pattern: RegExp; handle: (request: SyntheticRequest) => unknown }

export const TENANT = '55000000-0000-4000-8000-000000000001'
export const TENANT_B = '55000000-0000-4000-8000-0000000000b2'
export const USER = '55000000-0000-4000-8000-000000000002'
export const PERMISSION_VERSION = 3
const id = (prefix: string, n: number) => `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`
const iso = (minutesAgo: number) => new Date(Math.floor((Date.now() - minutesAgo * 60000) / 1000) * 1000).toISOString().replace('.000Z', 'Z')

const allCapabilities = ['asset_dates:read', 'assets:read', 'assets:write', 'audit:read', 'backup_plan_drafts:read', 'backup_plan_drafts:write', 'capacity:estimate', 'capacity_scenarios:read', 'capacity_scenarios:write', 'change_notice_drafts:read', 'change_notice_drafts:write', 'configurations:read', 'configurations:write', 'contacts:read', 'contacts:write', 'dashboard:read', 'inspection_quarterly_reports:read', 'inspection_quarterly_reports:write', 'inspection_source_collections:read', 'inspection_source_collections:write', 'inspection_templates:read', 'inspection_templates:write', 'inspection_worksheets:read', 'inspection_worksheets:write', 'manual_incidents:read', 'manual_incidents:write', 'manual_recovery:confirm', 'manual_recovery:read', 'notification_preferences:read_own', 'notification_preferences:write_own', 'operations:compare_explicit', 'roles:read', 'roles:write', 'search:read', 'sla_scenarios:read', 'tenants:read_inventory', 'ticket_comments:read', 'ticket_comments:write', 'ticket_notes:read_internal', 'ticket_notes:write_internal', 'tickets:lifecycle_write', 'tickets:read', 'tickets:report', 'users:read', 'users:write', 'worklogs:read_own', 'worklogs:write_own']
const roleCapabilities: Record<SyntheticRequest['role'], string[]> = {
  padmin: allCapabilities,
  msp: allCapabilities.filter(c => !['roles:write', 'users:write', 'tenants:read_inventory'].includes(c)),
  tadmin: ['assets:read', 'contacts:read', 'contacts:write', 'dashboard:read', 'tickets:read', 'tickets:report', 'ticket_comments:read', 'ticket_comments:write', 'users:read', 'notification_preferences:read_own', 'notification_preferences:write_own', 'search:read', 'audit:read'],
  viewer: ['assets:read', 'dashboard:read', 'tickets:read', 'tickets:report', 'ticket_comments:read', 'ticket_comments:write', 'notification_preferences:read_own', 'notification_preferences:write_own', 'search:read'],
}
const roleNames = { padmin: '预览平台管理员', msp: '预览值班工程师', tadmin: '预览客户管理员', viewer: '预览客户成员' }

function session({ role }: SyntheticRequest) {
  return {
    user: { id: USER, display_name: roleNames[role], role },
    tenant: { id: TENANT, name: '合成预览租户 A' },
    allowed_tenants: ['padmin', 'msp'].includes(role) ? [{ id: TENANT, name: '合成预览租户 A' }, { id: TENANT_B, name: '合成预览租户 B' }] : [{ id: TENANT, name: '合成预览租户 A' }],
    capabilities: roleCapabilities[role], permission_version: PERMISSION_VERSION, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL',
    mode: 'remote_test', identity_source: 'project_local', account_version: 1,
  }
}

// ---- Assets (M3) ----
const assetNames: [string, string, string, string, string?][] = [
  ['核心交换机 SW-CORE-01', 'switch', '192.0.2.1', 'ok'], ['核心交换机 SW-CORE-02', 'switch', '192.0.2.2', 'ok'],
  ['出口防火墙 FW-EDGE-01', 'firewall', '192.169.11.1', 'problem'], ['汇聚交换机 SW-AGG-03F', 'switch', '192.0.2.13', 'ok'],
  ['汇聚交换机 SW-AGG-05F', 'switch', '192.0.2.15', 'unknown'], ['出口路由器 RT-ISP-01', 'router', '198.51.100.1', 'ok'],
  ['分支路由器 RT-BR-SH', 'router', '198.51.100.21', 'maintenance'], ['无线控制器 AC-01', 'wireless_controller', '192.0.2.40', 'ok'],
  ['办公区 AP-3F-01', 'ap', '192.0.2.101', 'ok'], ['办公区 AP-3F-02', 'ap', '192.0.2.102', 'unknown'],
  ['会议室 AP-5F-01', 'ap', '192.0.2.121', 'problem'], ['接入交换机 SW-ACC-2F', 'switch', '192.0.2.32', 'ok'],
  ['接入交换机 SW-ACC-6F', 'switch', '192.0.2.36', 'disabled'], ['机房 UPS 网卡', 'other', '203.0.113.10', 'unknown'],
  ['分支防火墙 FW-BR-SH', 'firewall', '198.51.100.22', 'ok'], ['仓库 AP-WH-01', 'ap', '203.0.113.31', 'ok'],
]
const sites = [['site-hq', '总部机房'], ['site-hq', '总部机房'], ['site-hq', '总部机房'], ['site-hq-3f', '总部 3F 弱电间'], ['site-hq-5f', '总部 5F 弱电间'], ['site-hq', '总部机房'], ['site-sh', '上海分支'], ['site-hq', '总部机房'], ['site-hq-3f', '总部 3F 弱电间'], ['site-hq-3f', '总部 3F 弱电间'], ['site-hq-5f', '总部 5F 弱电间'], ['site-hq-2f', '总部 2F 弱电间'], ['site-hq-6f', '总部 6F 弱电间'], ['site-hq', '总部机房'], ['site-sh', '上海分支'], ['site-wh', '城东仓库']]
export const assets = assetNames.map(([name, type, ip, status], index) => ({
  id: index === 2 ? '55000000-0000-4000-8000-000000000005' : id('55000000', 1000 + index), tenant_id: TENANT, name, type, management_ip: ip,
  site_id: sites[index]![0], status, lifecycle_status: index > 13 ? 'DRAFT' : 'ACTIVE', source: 'manual',
  sampled_at: status === 'unknown' || status === 'disabled' ? null : iso(1 + (index % 4)), unknown_reason: status === 'unknown' ? '合成：采集尚未返回样本' : null,
  availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING', version: 1 + (index % 3), created_at: '2026-09-28T02:00:00Z', updated_at: '2026-10-20T08:30:00Z',
}))
function assetPage({ url }: SyntheticRequest) {
  const size = Number(url.searchParams.get('page_size') || 15), q = (url.searchParams.get('q') || '').toLowerCase()
  const status = url.searchParams.get('status'), type = url.searchParams.get('type'), site = url.searchParams.get('site_id')
  const rows = assets.filter(a => (!q || a.name.toLowerCase().includes(q) || a.management_ip.includes(q)) && (!status || a.status === status) && (!type || a.type === type) && (!site || a.site_id === site))
  const offset = Number(url.searchParams.get('cursor')?.replace('synthetic-offset-', '') || 0)
  const items = rows.slice(offset, offset + size), more = offset + size < rows.length
  return { items, next_cursor: more ? `synthetic-offset-${offset + size}` : null, has_more: more, source: 'postgresql', page_size_limit: 100, page_size_limit_kind: 'test_technical_limit' }
}
function asset({ params }: SyntheticRequest) {
  const found = assets.find(a => a.id === params[0])
  if (!found) throw new Error('合成资产不存在')
  return found
}

// ---- Network monitoring (M4) ----
const monitored = assets.filter(a => ['switch', 'firewall', 'router'].includes(a.type)).slice(0, 8)
function sample(metric: string, value: string | null, state = 'FRESH', minutes = 1) {
  return { state, value: state === 'FRESH' ? value : null, unit: metric === 'latency' ? 's' : metric === 'loss' ? '%' : '', recorded_at: state === 'NO_DATA' ? null : iso(minutes) }
}
function device(a: typeof assets[number], index: number) {
  const silent = a.status === 'unknown', down = a.status === 'problem' && a.type !== 'firewall'
  const metrics = Object.fromEntries(metricNames.map(m => [m, silent ? sample(m, null, 'NO_DATA') : sample(m,
    m === 'icmp' ? (down ? '0' : '1') : m === 'loss' ? (down ? '100' : String((index % 3) * 0.5)) : m === 'latency' ? (down ? '0' : (0.0012 + index * 0.0007).toFixed(4)) : m === 'tcp_80' ? '0' : '1')]))
  return {
    asset_id: a.id, name: a.name, management_ip: a.management_ip, brand: a.type === 'firewall' ? '合成厂商 F' : '合成厂商 S', model: `SYN-${a.type.toUpperCase()}-${100 + index}`,
    network_state: silent ? 'UNKNOWN' : down ? 'NO_RESPONSE' : 'REACHABLE', metrics, sample_interval_seconds: 60, freshness_seconds: 180,
    vendor_metrics_status: a.management_ip === '192.169.11.1' ? 'PARTIAL_API' : 'DOCUMENTATION_PENDING', device_identity_verified: false, alert_rule_status: 'CONFIG_PENDING',
  }
}
const networkSnapshot = () => ({ tenant_id: TENANT, generated_at: iso(0), devices: monitored.map(device), source: 'ZABBIX_SIMPLE_CHECK', scope: 'CURRENT_TENANT_EXPLICIT_TEST_BINDINGS', refresh_seconds: 30 })
function networkHistory({ url, params }: SyntheticRequest) {
  const metric = url.searchParams.get('metric') || 'latency', window = url.searchParams.get('window') || 'HOUR_1'
  const minutes = window === 'HOUR_1' ? 60 : 1440, step = window === 'HOUR_1' ? 1 : 10
  const points = Array.from({ length: Math.floor(minutes / step) - 1 }, (_, i) => {
    const t = minutes - (i + 1) * step, wave = Math.sin(i / 6) * 0.5 + 0.5
    const value = metric === 'latency' ? (0.0015 + wave * 0.002).toFixed(4) : metric === 'loss' ? (i % 37 === 0 ? '20' : '0') : '1'
    return { recorded_at: iso(t), value }
  })
  return { asset_id: params[0], metric, unit: metric === 'latency' ? 's' : metric === 'loss' ? '%' : '', window, from: iso(minutes), to: iso(0), points, sample_interval_seconds: 60, gap_after_seconds: 180, timestamp_meaning: 'ZABBIX_RECORD_TIME', complete_window_claimed: false, source: 'ZABBIX_SIMPLE_CHECK' }
}
function resources({ params }: SyntheticRequest) {
  return { asset_id: params[0], management_ip: '192.169.11.1', generated_at: iso(0), received_at: iso(1), source_sampled_at: null, state: 'FRESH', reason: null, metrics: { cpu: { value: '37.5', unit: '%' }, memory: { value: '64', unit: '%' } }, source: 'SANGFOR_AF_API', timestamp_meaning: 'COLLECTOR_RECEIPT_TIME', sample_interval_seconds: 60, freshness_seconds: 180, tls_mode: 'TEST_INSECURE', tls_trust_verified: false, alert_rule_status: 'CONFIG_PENDING' }
}
function resourceHistory({ url, params }: SyntheticRequest) {
  const metric = url.searchParams.get('metric') || 'cpu', window = url.searchParams.get('window') || 'HOUR_1', minutes = window === 'HOUR_1' ? 60 : 1440, step = window === 'HOUR_1' ? 1 : 10
  const points = Array.from({ length: Math.floor(minutes / step) - 1 }, (_, i) => ({ recorded_at: iso(minutes - (i + 1) * step), value: String(Math.round((metric === 'cpu' ? 30 : 60) + Math.sin(i / 5) * 12)) }))
  return { asset_id: params[0], metric, unit: '%', window, from: iso(minutes), to: iso(0), points, sample_interval_seconds: 60, gap_after_seconds: 90, timestamp_meaning: 'COLLECTOR_RECEIPT_TIME', complete_window_claimed: false, source: 'SANGFOR_AF_API' }
}
const refreshPolicy = () => ({ tenant_id: TENANT, actor_id: USER, permission_version: PERMISSION_VERSION, configuration_key: 'monitor.page_refresh_sec', interval_seconds: 30, auto_refresh_enabled: true, source: 'CONTRACT_DEFAULT', reason: null, publication_id: null, publication_version: null, checked_at: iso(0), expires_at: null, definition_hash: 'c'.repeat(64), production_approved: false })

// ---- Events (M4-14) ----
const eventWorkspace = () => {
  const data = structuredClone(eventFixture.workspace.data) as typeof eventFixture.workspace.data
  data.events.sort((a, b) => b.id.localeCompare(a.id))
  return data
}

// ---- Ticket workflows (M5) ----
const workflowStates = ['OPEN', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED']
const workflowTitles = ['总部 3F 无线信号弱，会议室无法投屏', '出口防火墙 CPU 持续偏高', '上海分支专线丢包', '财务部门打印机无法访问', '新员工入职网络开通', '5F 会议室 AP 掉线', '核心交换机端口错包增长']
function workflow(n: number, state: string) {
  const accepted = ['ACCEPTED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'].includes(state), resolved = ['RESOLVED', 'CLOSED'].includes(state)
  return {
    ticket_id: id('56000000', n), tenant_id: TENANT, title: workflowTitles[n % workflowTitles.length], receipt_no: `RPT-20261023-${String(n).padStart(4, '0')}`, registration_created_at: iso(20 + n * 25),
    version: state === 'OPEN' ? 1 : 5, state, priority: ['P1', 'P2', 'P3', 'P2', 'P3'][n % 5], service_tier: 'STANDARD', assignee_id: accepted ? USER : null, collaborator_ids: [],
    accepted_at: accepted ? iso(15 + n * 25) : null, resolved_at: resolved ? iso(10 + n * 25) : null, closed_at: state === 'CLOSED' ? iso(5 + n * 25) : null,
    confirm_due_at: resolved ? iso(-2880) : null, close_mode: state === 'CLOSED' ? 'customer_confirm' : null, reopen_count: 0, confirmation_cycle: resolved ? 1 : 0,
    resolution_note: resolved ? '合成：已更换故障光模块并观察 30 分钟无告警。' : null, response_due_at: iso(n * 25 - 10), recovery_due_at: iso(n % 3 === 0 ? n * 25 - 60 : -120 + n * 10),
    sla_missing: {}, sla_references: [], snapshot_hash: 'a'.repeat(64), updated_at: iso(30 + n), observed_at: iso(0), authority_source: 'MOP', source: 'postgresql',
    scope: 'CURRENT_TENANT_INTERNAL', customer_confirmation_status: 'UNVERIFIED', production_approved: false, external_notification_delivery: 'NOT_CONFIRMED',
  }
}
function workflowPage({ url }: SyntheticRequest) {
  const state = url.searchParams.get('state') || 'OPEN', limit = Number(url.searchParams.get('limit') || 5)
  if (state === 'CANCELLED') return { items: [], has_more: false, next_cursor: null, source: 'postgresql' }
  const offset = url.searchParams.get('cursor') ? limit : 0
  const items = Array.from({ length: state === 'CLOSED' ? 2 : 7 }, (_, i) => workflow(i + 1 + Math.max(0, workflowStates.indexOf(state)) * 10, state)).slice(offset)
  const more = items.length > limit
  return { items: items.slice(0, limit), has_more: more, next_cursor: more ? 'synthetic.cursor' : null, source: 'postgresql' }
}

export const syntheticRoutes: Route[] = [
  { name: 'session', pattern: /^\/session$/, handle: session },
  { name: 'auth-options', pattern: /^\/auth\/options$/, handle: () => ({ mode: 'remote_test', identity_source: 'project_local', login_url: null, local_enabled: true, sso_enabled: false, local_login_url: '/api/v1/auth/local/login', csrf_url: '/api/v1/auth/csrf', wecom_enabled: false, wecom_start_url: null }) },
  { name: 'dashboard', pattern: /^\/dashboard$/, handle: ({ url }) => { const w = (url.searchParams.get('window') || 'HOURS_24') as 'HOURS_24'; const r = dashboardRow(w, iso(0)); return { ...r, tenant_id: TENANT, actor_id: USER, permission_version: PERMISSION_VERSION } } },
  { name: 'events', pattern: /^\/events$/, handle: eventWorkspace },
  { name: 'event-detail', pattern: /^\/events\/([0-9a-f-]{36})$/, handle: () => structuredClone(eventFixture.detail.data) },
  { name: 'workflows', pattern: /^\/ticket-workflows$/, handle: workflowPage },
  { name: 'network', pattern: /^\/network-monitoring$/, handle: networkSnapshot },
  { name: 'refresh-policy', pattern: /^\/network-monitoring\/refresh-policy$/, handle: refreshPolicy },
  { name: 'network-history', pattern: /^\/network-monitoring\/([0-9a-f-]{36})\/history$/, handle: networkHistory },
  { name: 'resources', pattern: /^\/network-monitoring\/([0-9a-f-]{36})\/resources$/, handle: resources },
  { name: 'resource-history', pattern: /^\/network-monitoring\/([0-9a-f-]{36})\/resources\/history$/, handle: resourceHistory },
  { name: 'assets', pattern: /^\/assets$/, handle: assetPage },
  { name: 'asset', pattern: /^\/assets\/([0-9a-f-]{36})$/, handle: asset },
]
