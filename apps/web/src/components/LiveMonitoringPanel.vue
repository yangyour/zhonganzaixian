<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { useMonitoringRefreshPolicy, refreshPermissionRejected } from '../utils/monitoringRefreshPolicy'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createLiveMonitoringWorkspace, liveMetricText, liveResourceValue, type LiveMonitoringState } from '../utils/liveMonitoringWorkspace'
import { historySegments, metricNames, type Device, type Metric, type HistoryMetric } from '../utils/networkMonitoring'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = withDefaults(defineProps<{ assetId?: string; mode?: 'overview' | 'items' | 'charts' | 'events' | 'triggers' | 'ranking'; autoRefresh?: boolean }>(), { mode: 'overview', autoRefresh: true })
const state = shallowRef<LiveMonitoringState>({ snapshot: null, resources: {}, resourceErrors: {}, loadedAt: 0, busy: false, error: null, history: null, historyBusy: false, historyError: null })
const workspace = createLiveMonitoringWorkspace(api, value => { state.value = value })
let lastRefresh = 0
const now = ref(performance.now()), selected = ref(''), metric = ref<HistoryMetric>('latency'), window = ref<'HOUR_1' | 'HOURS_24'>('HOUR_1'), rankMetric = ref<'cpu' | 'memory'>('cpu')
const permitted = computed(() => session.value?.mode === 'remote_test' && can('assets:read') && !switchingTenant.value)
const {intervalSeconds:refreshInterval,label:refreshPolicyLabel,error:refreshPolicyError,refresh:refreshPolicy}=useMonitoringRefreshPolicy(permitted)
const devices = computed(() => state.value.snapshot?.devices ?? [])
const names: Record<HistoryMetric, string> = { icmp: 'ICMP 连通性', latency: '响应延迟', loss: '丢包率', tcp_22: 'TCP 22', tcp_80: 'TCP 80', tcp_443: 'TCP 443', cpu: 'CPU 使用率', memory: '内存使用率' }
const text = (device: Device, name: Metric) => liveMetricText(state.value, device, name, now.value)
const reachable = computed(() => devices.value.filter(d => text(d, 'icmp') === '可达').length)
const noResponse = computed(() => devices.value.filter(d => text(d, 'icmp') === '无回应').length)
const resourceValue = (id: string, name: 'cpu' | 'memory') => liveResourceValue(state.value, id, name, now.value)
function resourceText(id: string, name: 'cpu' | 'memory') {
  const value = resourceValue(id, name), resource = state.value.resources[id]
  if (value !== null) return `${value.toFixed(1)}%`
  if (state.value.resourceErrors[id]) return '资源读取失败'
  if (!resource) return state.value.busy ? '读取中…' : '未取得采样'
  return ({ FRESH: '数据过期', STALE: '数据过期', NO_DATA: '等待采样', COLLECTOR_ERROR: '采集异常', CLOCK_SKEW: '时钟异常' })[resource.state]
}
const ranking = computed(() => devices.value.map(d => ({ device: d, value: resourceValue(d.asset_id, rankMetric.value) })).filter((d): d is { device: Device; value: number } => d.value !== null).sort((a, b) => b.value - a.value || a.device.asset_id.localeCompare(b.device.asset_id)).slice(0, 5))
const rankingKind = ref<'cpu' | 'memory' | 'disk'>('cpu')
const rankingKinds = [{ value: 'cpu', label: 'CPU' }, { value: 'memory', label: '内存' }, { value: 'disk', label: '磁盘' }] as const
watch(rankingKind, value => { if (value !== 'disk') rankMetric.value = value })
const observations = computed(() => devices.value.flatMap(device => metricNames.filter(m => {
  const value = text(device, m)
  return m === 'loss' ? value !== '0.0%' : m === 'latency' ? !value.endsWith(' ms') : !['可达', '开放'].includes(value)
}).map(m => ({ device, metric: m, value: text(device, m), time: device.metrics[m].recorded_at }))))
const selectedDevice = computed(() => devices.value.find(d => d.asset_id === selected.value))
const availableMetrics = computed(() => [...metricNames, ...(selectedDevice.value?.vendor_metrics_status === 'PARTIAL_API' ? ['cpu','memory'] as const : [])])
const segments = computed(() => state.value.history ? historySegments(state.value.history) : [])
const historyMaximum = computed(() => { const value = state.value.history; if (!value) return 0; return ['loss','cpu','memory'].includes(value.metric) ? 100 : value.metric === 'latency' ? Math.max(...value.points.map(p => Number(p.value)), 0.001) : 1 })
async function loadHistory() { if (selected.value) await workspace.history(selected.value, metric.value, window.value); else workspace.clearHistory() }
async function refresh() { lastRefresh=performance.now(); if (await workspace.load()) { now.value = performance.now(); if (selected.value && !devices.value.some(d => d.asset_id === selected.value)) selected.value = ''; else if (selected.value) await loadHistory() } }
function inspect(id: string, name: HistoryMetric = 'latency') { selected.value = id; metric.value = name }
watch(() => [contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, props.assetId], () => {
  selected.value = ''; metric.value = 'latency'; window.value = 'HOUR_1'
  const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, assetId: props.assetId } : null)
  if (permitted.value) void refresh()
}, { immediate: true, flush: 'sync' })
watch(refreshPolicyError,cause=>{if(refreshPermissionRejected(cause)){selected.value='';const s=session.value;workspace.reset(s&&permitted.value?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,assetId:props.assetId}:null)}},{flush:'sync'})
watch([selected, metric, window], () => { if (!availableMetrics.value.includes(metric.value)) metric.value = 'latency'; else void loadHistory() })
let timer: ReturnType<typeof setInterval> | undefined
function visible() { now.value = performance.now(); if (!document.hidden && props.autoRefresh && refreshInterval.value!==null) void refresh() }
onMounted(() => { timer = setInterval(() => { now.value = performance.now(); if (refreshInterval.value!==null&&now.value-lastRefresh>=refreshInterval.value*1000&&!document.hidden&&props.autoRefresh) void refresh() }, 1000); document.addEventListener('visibilitychange', visible) })
onUnmounted(() => { clearInterval(timer); document.removeEventListener('visibilitychange', visible); workspace.dispose() })
defineExpose({ refresh })
</script>
<template>
 <section class="live-monitoring" :aria-busy="state.busy" aria-label="实际设备监控">
  <div class="live-heading"><div class="live-title"><h2>{{ mode === 'ranking' ? '资源占用 Top5' : mode === 'events' ? '当前采集检查' : mode === 'triggers' ? '告警接入状态' : '设备监控' }}</h2><span v-if="permitted && devices.length && mode !== 'ranking'" class="live-summary"><span>{{ devices.length }} 台</span><span class="live-chip ok">可达 {{ reachable }}</span><span v-if="noResponse" class="live-chip bad">无回应 {{ noResponse }}</span></span></div><div class="live-tools"><slot name="tools" /><span v-if="permitted" class="live-policy" aria-label="监控自动刷新设置">{{refreshPolicyLabel}}</span><button type="button" :disabled="state.busy || !permitted" @click="refresh">{{ state.busy ? '读取中…' : '刷新监控数据' }}</button></div></div>
  <p v-if="!permitted" role="status">当前账号没有资产读取权限。</p>
  <template v-else>
   <ApiErrorNotice :error="refreshPolicyError" @retry="refreshPolicy"/>
   <ApiErrorNotice :error="state.error" :retrying="state.busy" @retry="refresh" />
   <p v-if="!state.snapshot" class="live-empty">{{ state.busy ? '正在读取设备绑定与监控采样…' : state.error ? '本次读取失败，请重试。' : '尚未读取监控数据。' }}</p>
   <template v-else>
    <section v-if="mode === 'ranking'" class="contract-ranking" aria-label="资源排行">
     <div class="ranking-switch mop-segmented" role="group" aria-label="资源排行指标"><button v-for="kind in rankingKinds" :key="kind.value" type="button" :aria-pressed="rankingKind === kind.value" @click="rankingKind = kind.value">{{ kind.label }}</button></div>
     <p v-if="rankingKind === 'disk'" class="live-empty">磁盘使用率尚未接入，暂无可核验的排行。</p>
     <template v-else-if="ranking.length">
      <ol class="contract-ranking-list"><li v-for="(row, index) in ranking" :key="row.device.asset_id"><span class="rank-no">{{ index + 1 }}</span><router-link :to="{ path: '/assets', query: { asset_id: row.device.asset_id } }">{{ row.device.name }}</router-link><meter min="0" max="100" low="70" high="85" optimum="0" :value="row.value" :aria-label="row.device.name + names[rankMetric]" /><strong>{{ row.value.toFixed(1) }}%</strong></li></ol>
      <div class="ranking-axis" aria-hidden="true"><span>0%</span><span>50%</span><span>100%</span></div>
     </template>
     <p v-else class="live-empty">暂无新鲜资源采样，未知和过期数据不参与排行。</p>
    </section>
    <p v-if="!devices.length && mode !== 'ranking'" class="live-empty">{{ assetId ? '当前资产未绑定监控采集。请在监控配置中核对接入条件。' : '当前租户没有已绑定的监控设备。' }}</p>
    <template v-else>
     <div v-if="mode === 'overview'" class="live-table-wrap" tabindex="0" role="region" aria-label="设备实时概览"><table class="live-table"><thead><tr><th>设备 / 管理 IP</th><th>ICMP</th><th>延迟</th><th>丢包</th><th>CPU / 内存</th><th>历史</th></tr></thead><tbody><tr v-for="d in devices" :key="d.asset_id"><td><strong>{{ d.name }}</strong><small class="mono-text">{{ d.management_ip }}</small></td><td><span :class="{ 'live-positive': text(d,'icmp') === '可达' }">{{ text(d,'icmp') }}</span></td><td>{{ text(d,'latency') }}</td><td>{{ text(d,'loss') }}</td><td><template v-if="d.vendor_metrics_status === 'PARTIAL_API'">{{ resourceText(d.asset_id,'cpu') }} / {{ resourceText(d.asset_id,'memory') }}<small>{{ displayTime(state.resources[d.asset_id]?.received_at ?? null) }}</small><small v-if="state.resources[d.asset_id]?.tls_mode === 'TEST_INSECURE'" class="live-warning">证书未核验</small></template><span v-else class="live-uncollected">暂未接入资源采集</span></td><td><button type="button" class="ws-link" :aria-label="'查看'+d.name+'历史'" @click="inspect(d.asset_id)">查看历史</button></td></tr></tbody></table></div>
     <template v-if="mode === 'overview' || mode === 'items' || mode === 'charts'">
      <template v-if="mode !== 'overview'">
      <div class="live-table-wrap" tabindex="0" role="region" aria-label="实际监控采样表"><table class="live-table"><thead><tr><th>设备</th><th>采集项</th><th>当前值</th><th>记录时间</th><th>历史</th></tr></thead><tbody><template v-for="d in devices" :key="d.asset_id"><tr v-for="m in metricNames" :key="d.asset_id + m"><td><strong>{{ d.name }}</strong><small class="mono-text">{{ d.management_ip }}</small></td><td>{{ names[m] }}</td><td><span :class="{ 'live-positive': text(d,m) === '可达' }">{{ text(d,m) }}</span></td><td>{{ displayTime(d.metrics[m].recorded_at) }}</td><td><button type="button" class="ws-link" @click="inspect(d.asset_id,m)">查看</button></td></tr><template v-if="d.vendor_metrics_status === 'PARTIAL_API'"><tr v-for="m in ['cpu','memory'] as const" :key="d.asset_id+m"><td><strong>{{ d.name }}</strong><small>防火墙 API</small></td><td>{{ names[m] }}</td><td>{{ resourceText(d.asset_id,m) }}</td><td>{{ displayTime(state.resources[d.asset_id]?.received_at ?? null) }}<small v-if="state.resources[d.asset_id]?.tls_mode === 'TEST_INSECURE'" class="live-warning">证书未核验</small></td><td><button type="button" class="ws-link" @click="inspect(d.asset_id,m)">查看</button></td></tr><tr v-if="state.resourceErrors[d.asset_id]"><td colspan="5"><ApiErrorNotice :error="state.resourceErrors[d.asset_id]" @retry="refresh" /></td></tr></template></template></tbody></table></div>
      </template>
      <section v-if="!assetId && mode === 'overview' && ranking.length" class="live-ranking"><div class="live-heading"><h3>资源占用 Top 5</h3><label>指标<select v-model="rankMetric" aria-label="资源排行指标"><option value="cpu">CPU</option><option value="memory">内存</option></select></label></div><ol v-if="ranking.length"><li v-for="row in ranking" :key="row.device.asset_id"><button type="button" @click="inspect(row.device.asset_id,rankMetric)">{{ row.device.name }}</button><meter min="0" max="100" :value="row.value" :aria-label="row.device.name + names[rankMetric]" /><strong>{{ row.value.toFixed(1) }}%</strong></li></ol></section>
     </template>
     <template v-if="mode === 'events'"><p class="live-period">采样检出项不自动生成正式事件</p><div class="live-table-wrap"><table class="live-table"><thead><tr><th>检查项</th><th>观察结果</th><th>记录时间</th><th>核对</th></tr></thead><tbody><tr v-for="item in observations" :key="item.device.asset_id+item.metric"><td>{{ names[item.metric] }}</td><td>{{ item.value }}</td><td>{{ displayTime(item.time) }}</td><td><button type="button" class="ws-link" @click="inspect(item.device.asset_id,item.metric)">查看历史</button></td></tr></tbody></table></div><p v-if="!observations.length" class="live-empty">本次采样未检出异常项。</p></template>
     <template v-if="mode === 'triggers'"><div v-for="d in devices" :key="d.asset_id" class="live-rule"><h3>{{ d.name }}</h3><dl><div><dt>规则状态</dt><dd>{{ d.alert_rule_status === 'CONFIG_PENDING' ? '待确认并发布' : '未核验' }}</dd></div><div><dt>采样周期</dt><dd>{{ d.sample_interval_seconds }} 秒</dd></div><div><dt>新鲜度窗口</dt><dd>{{ d.freshness_seconds }} 秒</dd></div><div><dt>采集来源</dt><dd>Zabbix 基础检查{{ d.vendor_metrics_status === 'PARTIAL_API' ? ' / 防火墙 API' : '' }}</dd></div></dl></div><p class="live-empty">告警触发器规则待发布，暂无生效规则与触发计数。</p><div class="live-links"><router-link v-if="can('configurations:read')" to="/threshold-diagnostics">核对阈值候选</router-link><router-link v-if="can('configurations:read')" to="/configurations">查看配置确认记录</router-link></div></template>
     <section v-if="mode !== 'triggers' && mode !== 'ranking'" class="live-history"><div class="live-heading"><h3>采样历史</h3><div class="live-history-inputs"><label>设备<select v-model="selected" aria-label="历史设备"><option value="">请选择设备</option><option v-for="d in devices" :key="d.asset_id" :value="d.asset_id">{{ d.name }}</option></select></label><label>采集项<select v-model="metric" aria-label="历史采集项"><option v-for="m in availableMetrics" :key="m" :value="m">{{ names[m] }}</option></select></label><label>范围<select v-model="window" aria-label="历史时间范围"><option value="HOUR_1">最近1小时</option><option value="HOURS_24">最近24小时</option></select></label></div></div><ApiErrorNotice :error="state.historyError" :retrying="state.historyBusy" @retry="loadHistory" /><p v-if="!selected" class="live-hint">选择设备后显示采样趋势</p><p v-else-if="state.historyBusy">正在读取采样历史…</p><template v-else-if="state.history"><p class="live-period">{{ displayTime(state.history.from) }} — {{ displayTime(state.history.to) }} · {{ state.history.points.length }} 个采样</p><p v-if="state.history.points.length" class="live-period">纵轴：0 — {{ historyMaximum }} {{ state.history.unit || '（0 未建立连接 / 1 可达）' }} · 横轴：上方查询时间范围</p><svg v-if="state.history.points.length" class="live-chart" viewBox="0 0 720 180" role="img" :aria-label="names[state.history.metric] + '真实采样历史，间断不连接'"><title>{{ names[state.history.metric] }}，单位 {{ state.history.unit || '0 / 1' }}</title><path d="M16 20V154H704" fill="none" stroke="#d7e1ef"/><g v-for="(segment,index) in segments" :key="index"><polyline :points="segment.line" fill="none" stroke="#2877d7" stroke-width="2"/><circle v-for="(dot,i) in segment.dots" :key="i" :cx="dot.x" :cy="dot.y" r="2" fill="#2877d7"/></g></svg><p v-else class="live-empty">所选时间范围内暂无采样记录。</p><details><summary>查看原始采样（{{ state.history.points.length }} 条）</summary><div class="live-table-wrap live-samples"><table class="live-table"><thead><tr><th>记录时间</th><th>原始值 {{ state.history.unit }}</th></tr></thead><tbody><tr v-for="(point,index) in state.history.points" :key="index"><td>{{ displayTime(point.recorded_at) }}</td><td>{{ point.value }}</td></tr></tbody></table></div></details></template></section>
    </template>
    <p class="live-footnote">快照 {{ displayTime(state.snapshot.generated_at) }} · Zabbix</p>
   </template>
  </template>
 </section>
</template>
<style scoped>
.contract-ranking{display:grid;gap:4px}.ranking-switch{justify-self:start;margin-bottom:6px}
.contract-ranking-list{list-style:none;margin:0;padding:0}.contract-ranking-list li{display:grid;grid-template-columns:22px minmax(120px,38%) minmax(60px,1fr) 56px;align-items:center;gap:12px;min-height:36px}
.rank-no{display:inline-grid;place-items:center;width:20px;height:20px;border-radius:4px;background:var(--mop-sunken,#f7f9fc);color:var(--mop-muted,#5d6e83);font-size:12px;font-weight:600;font-variant-numeric:tabular-nums}
.contract-ranking-list li:first-child .rank-no{background:var(--mop-primary-soft,#eaf2fe);color:var(--mop-primary,#2165c5)}
.contract-ranking-list a{color:var(--mop-ink,#17263a);font-weight:500;overflow-wrap:anywhere;text-decoration:none}.contract-ranking-list a:hover{color:var(--mop-primary,#2165c5);text-decoration:underline}
.contract-ranking-list meter{width:100%;height:10px;border:0;border-radius:999px;background:var(--mop-line-soft,#edf1f6);-webkit-appearance:none;appearance:none}
.contract-ranking-list meter::-webkit-meter-bar{height:10px;border:0;border-radius:999px;background:var(--mop-line-soft,#edf1f6)}
.contract-ranking-list meter::-webkit-meter-optimum-value{border-radius:999px;background:#3b82f6}
.contract-ranking-list meter::-webkit-meter-suboptimum-value{border-radius:999px;background:#e0a12b}
.contract-ranking-list meter::-webkit-meter-even-less-good-value{border-radius:999px;background:#e5484d}
.contract-ranking-list meter::-moz-meter-bar{border-radius:999px;background:#3b82f6}
.contract-ranking-list meter:-moz-meter-sub-optimum::-moz-meter-bar{background:#e0a12b}.contract-ranking-list meter:-moz-meter-sub-sub-optimum::-moz-meter-bar{background:#e5484d}
.contract-ranking-list strong{text-align:right;font-variant-numeric:tabular-nums;color:var(--mop-ink,#17263a)}
.ranking-axis{display:grid;grid-template-columns:22px minmax(120px,38%) minmax(60px,1fr) 56px;gap:12px;font-size:11px;color:var(--mop-muted,#5d6e83);font-variant-numeric:tabular-nums}
.ranking-axis span{grid-column:3;grid-row:1}.ranking-axis span:first-child{justify-self:start}.ranking-axis span:nth-child(2){justify-self:center}.ranking-axis span:last-child{justify-self:end}

.live-monitoring{min-width:0;margin:0 0 12px;padding:0 16px 14px;background:#fff;border:1px solid var(--mop-line,#e2e8f0);border-radius:8px;color:var(--mop-text,#2f4157);box-shadow:var(--mop-shadow-1,none)}
.live-heading{display:flex;align-items:center;justify-content:space-between;gap:8px 12px;flex-wrap:wrap;min-height:48px;margin:0 -16px 12px;padding:8px 16px;border-bottom:1px solid var(--mop-line-soft,#edf1f6)}
.live-history .live-heading,.live-ranking .live-heading{margin:0 0 10px;padding:0;min-height:0;border:0}
.live-title,.live-tools,.live-summary{display:flex;align-items:center;gap:8px 10px;flex-wrap:wrap}
.live-summary{gap:6px;font-size:12px;color:var(--mop-muted,#5d6e83)}
.live-chip{display:inline-flex;align-items:center;height:22px;padding:0 8px;border-radius:999px;font-weight:600;background:var(--mop-unknown-bg,#eef2f6)}.live-chip.ok{background:var(--mop-ok-bg,#e8f6ee);color:var(--mop-ok,#1d7a4c)}.live-chip.bad{background:var(--mop-danger-bg,#fdecea);color:var(--mop-danger,#b42318)}
.live-policy{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--mop-muted,#5d6e83);font-variant-numeric:tabular-nums}.live-policy::before{content:'';width:7px;height:7px;border-radius:50%;background:var(--mop-ok-dot,#2e9c63)}
.live-hint{margin:0;padding:28px 16px;border:1px dashed var(--mop-line-strong,#cfd9e5);border-radius:6px;text-align:center;color:var(--mop-muted,#5d6e83);font-size:13px}
.live-heading h2,.live-heading h3{margin:0;font-size:14px;font-weight:600;color:var(--mop-ink,#17263a)}
.live-heading p,.live-footnote,.live-period,.live-monitoring small{color:var(--mop-muted,#5d6e83);font-size:12px}.live-heading p{margin:6px 0 0;line-height:1.6}
.live-monitoring select{min-height:32px;max-width:100%}
.live-table-wrap{overflow:auto;max-width:100%;border:1px solid var(--mop-line-soft,#edf1f6);border-radius:6px}
.live-table{min-width:630px}.live-table td{overflow-wrap:anywhere}.live-table small{display:block;margin-top:3px}
.mono-text{font-family:var(--mop-font-mono,Consolas,monospace);font-variant-numeric:tabular-nums}
.live-positive{color:var(--mop-ok,#1d7a4c);font-weight:600}.live-uncollected{color:var(--mop-muted,#5d6e83);font-size:12px}.live-warning{color:var(--mop-warn,#8f5a07)!important}
.live-history,.live-ranking{margin-top:16px;padding-top:14px;border-top:1px solid var(--mop-line-soft,#edf1f6)}
.live-history-inputs{display:flex;flex-wrap:wrap;gap:8px 12px}.live-monitoring label{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--mop-text-2,#4f6177)}
.live-chart{display:block;width:100%;max-height:250px;background:var(--mop-sunken,#f7f9fc);border-radius:6px;border:1px solid var(--mop-line-soft,#edf1f6)}
.live-samples{max-height:250px;margin:12px 0}.live-samples .live-table{min-width:320px}
.live-monitoring details{margin:12px 0 0}
.live-ranking ol{padding:0;list-style:none}.live-ranking li{display:flex;align-items:center;gap:16px;margin:10px 0}.live-ranking strong,.live-period{font-variant-numeric:tabular-nums}.live-ranking meter{flex:1;min-width:50px}
.live-empty{margin:4px 0;padding:22px 16px;background:var(--mop-sunken,#f7f9fc);border-radius:6px;color:var(--mop-text-2,#4f6177);font-size:13px;text-align:center}
.live-rule{margin-bottom:12px}.live-rule h3{margin:0 0 8px;font-size:14px}.live-rule dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:0}.live-rule dl div{padding:10px 12px;background:var(--mop-sunken,#f7f9fc);border-radius:6px}.live-rule dt{font-size:12px;color:var(--mop-muted,#5d6e83)}.live-rule dd{margin:4px 0 0;font-variant-numeric:tabular-nums;color:var(--mop-ink,#17263a)}
.live-links{display:flex;gap:18px;flex-wrap:wrap}.live-links a{color:var(--mop-primary,#2165c5);text-decoration:none;font-size:13px;font-weight:500}.live-links a:hover{text-decoration:underline}
.live-footnote{line-height:1.6;margin:12px 0 0;text-align:right;font-variant-numeric:tabular-nums}
.live-monitoring :focus-visible{outline:2px solid var(--mop-primary,#2165c5);outline-offset:2px}
@media(max-width:1100px){.live-rule dl{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:760px){.live-monitoring{padding:0 12px 12px}.live-heading{margin:0 -12px 12px;padding:8px 12px;align-items:stretch}.live-history-inputs{width:100%}.live-history-inputs label{width:100%;justify-content:space-between}.live-history-inputs select{max-width:75%}.live-monitoring button,.live-monitoring select{min-height:44px}.live-ranking li{gap:8px;flex-wrap:wrap}.live-ranking button{max-width:55%;overflow-wrap:anywhere}.live-rule dl{grid-template-columns:1fr}.contract-ranking-list li,.ranking-axis{grid-template-columns:22px minmax(0,1fr) 52px;gap:8px}.contract-ranking-list meter{grid-column:2/-1;grid-row:2}.ranking-axis{display:none}}
</style>
