<script setup lang="ts">
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api, isCancelled } from '../api/client'
import type { Asset, AssetPage } from '../api/types'
import { can, contextGeneration, session } from '../stores/session'
import { assetTypes, displayTime, typeLabel, sourceLabel } from '../utils/display'
import AppTable from '../components/AppTable.vue'
import AppliedFilters from '../components/AppliedFilters.vue'
import { createSearchCompositionGuard, syncFilterDraft } from '../utils/listSearch'
import AssetStateFacts from '../components/AssetStateFacts.vue'
import AssetDraftCreatePanel from '../components/AssetDraftCreatePanel.vue'
import SampleFreshness from '../components/SampleFreshness.vue'
import LiveMonitoringPanel from '../components/LiveMonitoringPanel.vue'
import type { HttpClockObservation } from '../utils/httpResponseClock'
import PermissionGate from '../components/PermissionGate.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import AssetImportWorkspace from '../components/AssetImportWorkspace.vue'
import AssetDependencyWorkspace from '../components/AssetDependencyWorkspace.vue'
import AssetMetadataWorkspace from '../components/AssetMetadataWorkspace.vue'
import ManualWarrantyWorkspace from '../components/ManualWarrantyWorkspace.vue'
import AssetMetadataQuery from '../components/AssetMetadataQuery.vue'
import AssetDateAssessmentPanel from '../components/AssetDateAssessmentPanel.vue'
import AssetDateQuery from '../components/AssetDateQuery.vue'
import { assetLedgerColumns, assetLedgerCell } from '../utils/assetLedgerColumns'
import { Connection, Switch, Lock, Position, SetUp, Box, Grid, List, Download, Upload, Plus, Search, Refresh, ArrowLeft } from '@element-plus/icons-vue'
const deviceIcons = { router: Connection, switch: Switch, firewall: Lock, ap: Position, wireless_controller: SetUp, other: Box }
const ElTableColumn = ElementTableColumn<Asset>
const route = useRoute()
const router = useRouter()
const pageSizes = [10, 15, 25, 50, 100]
const queryString = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : ''
const pageSize = computed(() => pageSizes.includes(Number(queryString('page_size'))) ? Number(queryString('page_size')) : 15)
const filters = reactive({ q: '', status: '', type: '', site_id: '' })
const appliedFilters = computed(() => ({ q: queryString('q'), status: queryString('status'), type: queryString('type'), site_id: queryString('site_id') }))
let previousAppliedFilters: typeof filters | undefined
const pendingFilters = computed(() => Object.entries(filters).some(([key, value]) => value.trim() !== queryString(key)))
const searchComposition = createSearchCompositionGuard()
const rows = ref<Asset[]>([])
const assetView = ref<'list' | 'grid'>('list')
const detailTab = ref('overview')
const detailTabs = [{ id: 'overview', label: '概览' }, { id: 'items', label: '监控项' }, { id: 'triggers', label: '告警接入' }, { id: 'events', label: '采集检查' }, { id: 'charts', label: '图表' }, { id: 'inventory', label: '资产清单' }, { id: 'configuration', label: '监控配置' }, { id: 'relations', label: '关联' }]
const statusFilters = [{ value: '', label: '全部' }, { value: 'ok', label: '正常' }, { value: 'problem', label: '有问题' }, { value: 'unknown', label: '未知' }, { value: 'maintenance', label: '维护中' }, { value: 'disabled', label: '已停用' }]
const filterItems = computed(() => [
  { key: 'q', label: '关键词', value: appliedFilters.value.q },
  { key: 'status', label: '状态', value: appliedFilters.value.status ? statusFilters.find(item => item.value === appliedFilters.value.status)?.label || appliedFilters.value.status : '' },
  { key: 'type', label: '类型', value: appliedFilters.value.type ? assetTypes.find(item => item.value === appliedFilters.value.type)?.label || appliedFilters.value.type : '' },
  { key: 'site_id', label: '站点', value: appliedFilters.value.site_id },
].filter(item => item.value))
const pageMetrics = computed(() => [
  { label: '本页资产', value: rows.value.length, detail: `${new Set(rows.value.map(item => item.type)).size} 种类型 · ${new Set(rows.value.map(item => item.site_id)).size} 个站点`, tone: '' },
  { label: '正常', value: rows.value.filter(item => item.status === 'ok').length, detail: '当前页明确正常的记录', tone: 'success' },
  { label: '有问题', value: rows.value.filter(item => item.status === 'problem').length, detail: '当前页明确异常的记录', tone: 'danger' },
  { label: '未知', value: rows.value.filter(item => item.status === 'unknown').length, detail: '数据缺失 ≠ 正常', tone: 'warning' },
  { label: '维护中', value: rows.value.filter(item => item.status === 'maintenance').length, detail: '仅按登记状态统计', tone: '' },
  { label: '待接入', value: rows.value.filter(item => item.sync_state === 'PENDING_ONBOARDING').length, detail: '尚未确认监控配置生效', tone: 'warning' },
])
const exportAllowed = computed(() => ['msp', 'padmin'].includes(session.value?.user.role || '') && can('assets:read'))
function filterStatus(value: string) { updateAppliedFilter('status', value) }
watch(() => queryString('asset_id'), () => { detailTab.value = 'overview' })
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const cursorHistory = ref<string[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref<unknown>(null)
const listController = ref<AbortController>()
let listGeneration = 0
const signature = computed(() => JSON.stringify(['q', 'status', 'type', 'site_id', 'cursor', 'page_size'].map(queryString)))
watch(signature, () => {
  syncFilterDraft(filters, appliedFilters.value, previousAppliedFilters)
  previousAppliedFilters = { ...appliedFilters.value }
  void loadAssets()
}, { immediate: true })
async function loadAssets() {
  listController.value?.abort(); const controller = new AbortController(); listController.value = controller
  const generation = ++listGeneration
  loading.value = true; loaded.value = false; error.value = null; rows.value = []; nextCursor.value = null; hasMore.value = false
  const params = new URLSearchParams({ page_size: String(pageSize.value) })
  for (const key of ['q', 'status', 'type', 'site_id', 'cursor']) if (queryString(key)) params.set(key, queryString(key))
  try {
    const page = await api.request<AssetPage>(`/assets?${params}`, { signal: controller.signal })
    if (generation !== listGeneration) return
    rows.value = page.items; nextCursor.value = page.next_cursor; hasMore.value = page.has_more; loaded.value = true
  } catch (cause) { if (!isCancelled(cause) && generation === listGeneration) error.value = cause }
  finally { if (generation === listGeneration) loading.value = false }
}
function applyFilters() {
  if (loading.value || searchComposition.isComposing()) return
  for (const key of Object.keys(filters) as (keyof typeof filters)[]) filters[key] = filters[key].trim()
  cursorHistory.value = []
  const query = { ...route.query, ...Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value.trim() || undefined])), cursor: undefined, page_size: String(pageSize.value) }
  const unchanged = !queryString('cursor') && Object.entries(filters).every(([key, value]) => value.trim() === queryString(key))
  if (unchanged) void loadAssets()
  else void router.replace({ query })
}
function resetFilters() { if (loading.value) return; Object.assign(filters, { q: '', status: '', type: '', site_id: '' }); applyFilters() }
function updateAppliedFilter(key: string, value: string) {
  if (loading.value || !Object.hasOwn(filters, key)) return
  filters[key as keyof typeof filters] = value
  if (queryString(key) === value && !queryString('cursor')) return
  cursorHistory.value = []
  void router.replace({ query: { ...route.query, [key]: value || undefined, cursor: undefined } })
}
function removeFilter(key: string) { updateAppliedFilter(key, '') }
function resizePage(value: unknown) { cursorHistory.value = []; void router.replace({ query: { ...route.query, cursor: undefined, page_size: String(value) } }) }
function nextPage() { if (!nextCursor.value) return; cursorHistory.value.push(queryString('cursor')); void router.replace({ query: { ...route.query, cursor: nextCursor.value, page_size: String(pageSize.value) } }) }
function previousPage() { const cursor = cursorHistory.value.pop(); void router.replace({ query: { ...route.query, cursor: cursor || undefined } }) }
function firstPage() { cursorHistory.value = []; void router.replace({ query: { ...route.query, cursor: undefined } }) }

const detailOpen = computed({ get: () => Boolean(queryString('asset_id')), set: value => { if (!value) void router.replace({ query: { ...route.query, asset_id: undefined } }) } })
const asset = ref<Asset | null>(null)
const detailClock = shallowRef<HttpClockObservation | null>(null)
const detailError = ref<unknown>(null)
const detailLoading = ref(false)
const onboarding = ref(false)
let detailController: AbortController | undefined
let detailGeneration = 0
watch(() => queryString('asset_id'), id => { void loadDetail(id) }, { immediate: true })
async function loadDetail(id: string) {
  detailController?.abort(); detailController = new AbortController(); const generation = ++detailGeneration
  asset.value = null; detailClock.value = null; detailError.value = null; onboarding.value = false
  if (!id) { detailLoading.value = false; return }
  detailLoading.value = true
  try { const result = await api.requestWithClock<Asset>(`/assets/${encodeURIComponent(id)}`, { signal: detailController.signal }); if (generation === detailGeneration) { detailClock.value = result.clock; asset.value = result.data } }
  catch (cause) { if (!isCancelled(cause) && generation === detailGeneration) detailError.value = cause }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function refreshDetail() { void loadDetail(queryString('asset_id')) }
function openDetail(id: string) { void router.replace({ query: { ...route.query, asset_id: id } }) }
async function requestOnboarding() {
  if (!asset.value) return
  onboarding.value = true; detailError.value = null
  try { await api.request(`/assets/${encodeURIComponent(asset.value.id)}/onboarding`, { method: 'POST', body: { expected_version: asset.value.version }, signal: detailController?.signal }); await loadDetail(queryString('asset_id')) }
  catch (cause) { if (!isCancelled(cause)) detailError.value = cause }
  finally { onboarding.value = false }
}

const createOpen = ref(false)
const createPanel = ref<InstanceType<typeof AssetDraftCreatePanel> | null>(null)
const dependencyOpen = ref(false), dependencyAsset = ref<Asset | null>(null)
const dependencyPanel = ref<InstanceType<typeof AssetDependencyWorkspace> | null>(null)
function openDependencies() {
  if (!asset.value) return
  if (dependencyPanel.value?.hasUnresolved() && dependencyAsset.value?.id !== asset.value.id) { ElMessage.warning('另一台设备的依赖保存结果尚未确认，请先打开原设备并使用原请求重试'); return }
  dependencyAsset.value = asset.value; dependencyOpen.value = true
}
const metadataQueryOpen = ref(false), dateQueryOpen = ref(false)
const metadataOpen = ref(false), metadataAsset = ref<Asset | null>(null)
const metadataPanel = ref<InstanceType<typeof AssetMetadataWorkspace> | null>(null)
const warrantyOpen = ref(false), warrantyAsset = ref<Asset | null>(null)
const warrantyPanel = ref<InstanceType<typeof ManualWarrantyWorkspace> | null>(null)
function openWarranty() {
  if (!asset.value) return
  if (metadataPanel.value?.hasUnresolved()) { ElMessage.warning('基础资料保存结果未确认，请先处理原请求'); return }
  if (warrantyPanel.value?.hasUnresolved() && warrantyAsset.value?.id !== asset.value.id) { ElMessage.warning('另一台设备的维保保存结果未确认，请先打开原设备重试'); return }
  warrantyAsset.value = asset.value; warrantyOpen.value = true
}
function warrantySaved() { void loadAssets(); if (queryString('asset_id') === warrantyAsset.value?.id) void loadDetail(queryString('asset_id')) }
const metadataAllowed = computed(() => ['msp', 'padmin'].includes(session.value?.user.role || ''))
function openMetadata() {
  if (warrantyPanel.value?.hasUnresolved()) { ElMessage.warning('维保保存结果未确认，请先处理原请求'); return }
  if (!asset.value) return
  if (metadataPanel.value?.hasUnresolved() && metadataAsset.value?.id !== asset.value.id) { ElMessage.warning('另一台设备的资料保存结果尚未确认，请先打开原设备并使用原请求重试'); return }
  metadataAsset.value = asset.value; metadataOpen.value = true
}
function openQueryMetadata(selected: Asset) {
  if (warrantyPanel.value?.hasUnresolved()) { ElMessage.warning('维保保存结果未确认，请先处理原请求'); return }
  if (metadataPanel.value?.hasUnresolved() && metadataAsset.value?.id !== selected.id) { ElMessage.warning('另一台设备的资料保存结果尚未确认，请先处理原请求'); return }
  metadataAsset.value = selected; metadataOpen.value = true; metadataQueryOpen.value = false
}
function metadataSaved() { void loadAssets(); if (queryString('asset_id') === metadataAsset.value?.id) void loadDetail(queryString('asset_id')) }
const importOpen = ref(false)
const importAllowed = computed(() => ['padmin', 'msp', 'tadmin'].includes(session.value?.user.role || ''))
const availableSites = computed(() => [...new Set(rows.value.filter(item => item.tenant_id === session.value?.tenant.id).map(item => item.site_id))])
function openCreate() { createOpen.value = true }
async function beforeCloseCreate(done: () => void) { if (!createPanel.value || await createPanel.value.canLeave()) done() }
const creationScope = () => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version])
async function createdAsset(created: Asset) {
  if (created.tenant_id !== session.value?.tenant.id) return
  const scope = creationScope()
  createOpen.value = false
  ElMessage.success('资产草稿创建已由原请求回执确认，尚未接入监控')
  try {
    await router.replace({ query: { ...route.query, asset_id: created.id } })
    if (scope === creationScope()) await loadAssets()
  } catch (cause) { if (!isCancelled(cause) && scope === creationScope()) error.value = cause }
}
onUnmounted(() => { listController.value?.abort(); detailController?.abort() })
</script>
<template>
  <section class="assets-prototype-page">
    <div v-show="!detailOpen" class="asset-list-workspace">
    <div class="page-heading"><div class="asset-page-title"><div class="title-with-context"><h1>资产管理</h1><span class="context-badge">{{ session?.tenant.name }}</span><small>当前页 {{ loading || error || !loaded ? '—' : rows.length }} 个资产</small></div><details class="boundary-details"><summary>页面说明</summary><p>按当前授权与筛选读取资产台账。下方统计仅覆盖当前页；缺失的监控数据不会作为正常或零值。正式接入仍需通过检查。</p></details></div><div class="assets-heading-actions"><div class="asset-view-switch" aria-label="资产显示方式"><button type="button" :class="{ active: assetView === 'list' }" :aria-pressed="assetView === 'list'" @click="assetView = 'list'"><List />列表</button><button type="button" :class="{ active: assetView === 'grid' }" :aria-pressed="assetView === 'grid'" @click="assetView = 'grid'"><Grid />蜂巢</button></div><el-button v-if="exportAllowed" :icon="Download" @click="router.push('/asset-export-jobs')">导出</el-button><PermissionGate capability="assets:write"><el-button v-if="importAllowed" :icon="Upload" @click="importOpen = true">导入</el-button><el-button type="primary" :icon="Plus" @click="openCreate">创建资产草稿</el-button></PermissionGate></div></div>
    <div class="scope-card feature-summary assets-kpis" aria-label="当前页资产统计"><div v-for="metric in pageMetrics" :key="metric.label" :class="metric.tone"><span class="small-label">{{ metric.label }}</span><strong>{{ loading || error || !loaded ? '—' : metric.value }}</strong><small>{{ loading || error || !loaded ? '等待当前页数据' : metric.detail }}</small></div></div>
    <div class="panel">
      <el-form class="asset-toolbar" :inline="true" @submit.prevent="applyFilters" @compositionstart="searchComposition.start" @compositionend="searchComposition.end" @keydown="searchComposition.keydown">
        <el-input v-model="filters.q" :prefix-icon="Search" clearable placeholder="搜索名称 / 管理 IP" aria-label="搜索资产" enterkeyhint="search" class="asset-search" />
        <div class="asset-status-chips" aria-label="按资产状态筛选"><button v-for="item in statusFilters" :key="item.value" type="button" :disabled="loading" :class="{ active: queryString('status') === item.value }" :aria-pressed="queryString('status') === item.value" @click="filterStatus(item.value)">{{ item.label }}</button></div>
        <div class="asset-select-filters"><el-select v-model="filters.type" clearable placeholder="全部类型" aria-label="设备类型"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select><el-input v-model="filters.site_id" clearable placeholder="全部站点 / 站点 ID" aria-label="站点 ID" enterkeyhint="search" /><el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button :disabled="loading" @click="resetFilters">重置</el-button><el-button :icon="Refresh" :loading="loading" aria-label="刷新已应用的资产筛选" title="仅刷新已应用条件，保留未提交输入" @click="loadAssets">刷新</el-button></div>
        <div v-if="metadataAllowed" class="asset-extended-filters"><span>更多筛选</span><el-button text @click="metadataQueryOpen = true">设备资料</el-button><PermissionGate capability="asset_dates:read"><el-button text @click="dateQueryOpen = true">维保与超期日期</el-button></PermissionGate><span class="asset-table-scope">统计范围：当前查询的本页记录</span></div>
      </el-form>
      <AppliedFilters :items="filterItems" :pending="pendingFilters" :loading="loading" :failed="Boolean(error)" :loaded="loaded" :count="rows.length" @remove="removeFilter" @clear="resetFilters" />
      <ApiErrorNotice :error="error" guidance="请点击“重试”重新加载当前列表。"><el-button size="small" @click="loadAssets">重试</el-button></ApiErrorNotice>
      <AppTable v-if="assetView === 'list'" :error="error" :rows="rows" :loading="loading" :empty-text="error ? '数据加载失败，请重试' : !loaded ? '资产列表尚未读取' : filterItems.length ? '没有找到匹配资产，可调整或清空筛选' : '当前授权范围内暂无资产'">
        <el-table-column v-for="column in assetLedgerColumns" :key="column.key" :label="column.label" :min-width="column.minWidth" :fixed="column.key === 'actions' ? 'right' : undefined">
          <template #default="{ row }">
              <div v-if="column.key === 'name'" class="asset-identity-cell"><span class="asset-type-symbol" :class="'asset-type-' + row.type" aria-hidden="true"><component :is="deviceIcons[row.type] || Box" /></span><div><el-button link type="primary" class="asset-link" @click="openDetail(row.id)">{{ assetLedgerCell(row, column.key).text }}</el-button><span class="cell-sub mono">{{ row.management_ip || '—' }}</span></div></div>
              <el-button v-else-if="column.key === 'actions'" link type="primary" @click="openDetail(row.id)">{{ assetLedgerCell(row, column.key).text }}</el-button>
              <span v-else-if="column.key === 'availability' || column.key === 'sync'" class="ledger-state" :class="{ 'ledger-state-pending': column.key === 'sync' }">{{ assetLedgerCell(row, column.key).text }}</span>
            <span v-else>{{ assetLedgerCell(row, column.key).text }}</span>
              <span v-if="column.key !== 'name' && assetLedgerCell(row, column.key).detail && !['cpu','memory','business','problems','owner'].includes(column.key)" class="cell-sub">{{ assetLedgerCell(row, column.key).detail }}</span>
          </template>
        </el-table-column>
        <template #empty-action><el-button v-if="loaded && !error && filterItems.length" @click="resetFilters">清空筛选，查看全部资产</el-button></template>
      </AppTable>
      <div v-else class="asset-honeycomb-region" :aria-busy="loading"><el-skeleton v-if="loading" :rows="5" animated /><div v-else-if="rows.length" class="asset-honeycomb"><button v-for="item in rows" :key="item.id" type="button" class="asset-tile" :class="'asset-tile-' + item.status" @click="openDetail(item.id)"><span class="tile-top"><component :is="deviceIcons[item.type] || Box" /><span>{{ typeLabel(item.type) }}</span></span><strong>{{ item.name }}</strong><span class="mono">{{ item.management_ip }}</span><span>{{ item.site_name || item.site_id }}</span><div><b>{{ assetLedgerCell(item, 'availability').text }}</b><small>{{ assetLedgerCell(item, 'sync').text }}</small></div></button></div><el-empty v-else :description="error ? '读取未完成，请按上方提示重试' : !loaded ? '资产列表尚未读取' : filterItems.length ? '没有找到匹配资产，可调整或清空筛选' : '当前授权范围内暂无资产'"><el-button v-if="loaded && !error && filterItems.length" @click="resetFilters">清空筛选，查看全部资产</el-button></el-empty></div>
      <details class="asset-ledger-explanation boundary-details"><summary>数据范围与缺失说明</summary><p>台账按约定顺序展示12列，可左右滚动查看。Proxy、所属业务系统、CPU、内存、当前问题数和负责人暂未提供，以“—”显示；这不表示数值为零、设备正常或尚未配置。手工资料需在对应页面按权限查看。</p></details>
      <div class="table-footer"><span>仅显示当前租户内有权查看的资产 · 本页 {{ loading || error || !loaded ? '—' : rows.length }} 条</span><div class="cursor-pagination"><el-select :model-value="pageSize" :disabled="loading" aria-label="每页条数" @change="resizePage"><el-option v-for="size in pageSizes" :key="size" :label="`${size} 条 / 页`" :value="size" /></el-select><el-button v-if="queryString('cursor') && !cursorHistory.length" :disabled="loading" @click="firstPage">返回首页</el-button><el-button v-else :disabled="loading || !cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="loading || !hasMore" @click="nextPage">下一页</el-button></div></div>
    </div>
    </div>
    <section v-if="detailOpen" class="asset-detail-workspace asset-drawer" aria-label="资产详情">
      <div class="asset-detail-top"><el-button :icon="ArrowLeft" text @click="detailOpen = false">返回资产列表</el-button><span>资产管理 / 资产详情</span><el-button :loading="detailLoading" @click="refreshDetail">只读刷新</el-button></div>
      <ApiErrorNotice :error="detailError" />
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <template v-if="asset"><header class="asset-detail-hero"><div class="asset-hero-title"><span class="asset-type-symbol"><component :is="deviceIcons[asset.type] || Box" /></span><div><div class="title-with-context"><h1>{{ asset.name }}</h1><span class="ledger-state">{{ assetLedgerCell(asset, 'availability').text }}</span><span class="ledger-state ledger-state-pending">{{ assetLedgerCell(asset, 'sync').text }}</span></div><p>{{ asset.management_ip }} · {{ typeLabel(asset.type) }} · {{ session?.tenant.name }} / {{ asset.site_name || asset.site_id }}</p></div></div><div class="asset-hero-actions"><PermissionGate capability="assets:write"><el-button v-if="metadataAllowed" @click="openMetadata">资料维护</el-button><el-button v-if="metadataAllowed" @click="openWarranty">维保资料</el-button></PermissionGate><el-button @click="openDependencies">查看依赖</el-button><el-button v-if="can('assets:read')" @click="router.push('/network-monitoring')">查看网络监控</el-button></div><div class="asset-facts-grid"><div><small>资产标识</small><strong class="mono" :title="asset.id">{{ asset.id }}</strong></div><div><small>所属站点</small><strong>{{ asset.site_name || asset.site_id }}</strong></div><div><small>资料来源</small><strong>{{ sourceLabel(asset.source) }}</strong></div><div><small>生命周期</small><strong>{{ asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status }}</strong></div><div><small>最近采样</small><strong>{{ assetLedgerCell(asset, 'sampled').text }}</strong></div><div><small>登记版本</small><strong>v{{ asset.version }}</strong></div></div></header>
      <div class="asset-detail-body panel"><nav class="asset-detail-tabs" aria-label="资产详情分区"><button v-for="tab in detailTabs" :key="tab.id" type="button" :class="{ active: detailTab === tab.id }" :aria-pressed="detailTab === tab.id" @click="detailTab = tab.id">{{ tab.label }}</button></nav>
      <div v-if="detailTab === 'overview'" class="asset-overview"><LiveMonitoringPanel :key="asset.id" :asset-id="asset.id" /><details class="asset-source-details"><summary>资产登记状态与来源</summary><AssetStateFacts :asset="asset" /><SampleFreshness :clock="detailClock" :sampled-at="asset.sampled_at" :context-key="`${asset.tenant_id}:${asset.id}`" /><p>资产登记状态与实时监控分开保存，不将网络可达自动写成正式纳管成功。</p></details></div>
      <LiveMonitoringPanel v-else-if="['items','charts','triggers','events'].includes(detailTab)" :key="asset.id + detailTab" :asset-id="asset.id" :mode="detailTab as 'items' | 'charts' | 'triggers' | 'events'" />
      <div v-else-if="detailTab === 'inventory'" class="asset-inventory-panel">
        <el-descriptions :column="1" border class="asset-descriptions"><el-descriptions-item label="资产 ID"><span class="mono">{{ asset.id }}</span></el-descriptions-item><el-descriptions-item label="所属租户">{{ asset.tenant_id }}</el-descriptions-item><el-descriptions-item label="管理 IP"><span class="mono">{{ asset.management_ip }}</span></el-descriptions-item><el-descriptions-item label="站点">{{ asset.site_name || asset.site_id }}</el-descriptions-item><el-descriptions-item label="生命周期">{{ asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status }}</el-descriptions-item><el-descriptions-item label="来源">{{ sourceLabel(asset.source) }}</el-descriptions-item><el-descriptions-item label="采样时间">{{ assetLedgerCell(asset, 'sampled').text }}<span v-if="assetLedgerCell(asset, 'sampled').detail" class="cell-sub">{{ assetLedgerCell(asset, 'sampled').detail }}</span></el-descriptions-item><el-descriptions-item label="版本">v{{ asset.version }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ displayTime(asset.created_at) }}</el-descriptions-item><el-descriptions-item label="更新时间">{{ displayTime(asset.updated_at) }}</el-descriptions-item></el-descriptions>
        <div v-if="metadataAllowed" class="detail-section"><h3>手工草稿资料</h3><p class="muted">内部维护明确资料并留存前后历史；管理IP、CMDB和监控配置不变。</p><PermissionGate capability="assets:write"><el-button @click="openMetadata">查看资料与修订历史</el-button><el-button @click="openWarranty">维护维保日期与供应商</el-button></PermissionGate></div>
        <AssetDateAssessmentPanel v-if="metadataAllowed" :asset="asset" />
      </div>
        <div v-else-if="detailTab === 'relations'" class="asset-inventory-panel"><div class="panel-heading"><h3>设备关联与依赖</h3><span>人工草稿</span></div><div class="prototype-inline-empty"><Connection /><strong>查看当前资产的依赖草稿</strong><span>按完整租户图保存提案，尚未作为监控抑制规则生效。</span><el-button type="primary" @click="openDependencies">查看或追加依赖草稿</el-button></div></div>
        <div v-else-if="detailTab === 'configuration'" class="asset-inventory-panel"><div class="panel-heading"><h3>监控接入</h3><span>待确认</span></div><p class="muted">生产隔离、监控组件许可与阈值依赖 Q02 / Q03 / Q06。完成客户确认前，草稿不能发布到真实监控。</p><PermissionGate capability="assets:write"><el-button type="primary" :loading="onboarding" @click="requestOnboarding">检查接入条件</el-button></PermissionGate></div>

      </div>
      </template>
    </section>
    <el-dialog v-model="createOpen" title="创建资产草稿" width="520px" class="responsive-dialog" :close-on-click-modal="false" :before-close="beforeCloseCreate" destroy-on-close>
      <AssetDraftCreatePanel ref="createPanel" :sites="availableSites" @created="createdAsset" @close="createOpen = false" />
    </el-dialog>
    <AssetImportWorkspace v-model="importOpen" />
    <AssetDateQuery v-model="dateQueryOpen" @select="openDetail" />
    <AssetMetadataQuery v-model="metadataQueryOpen" @select="openQueryMetadata" />
    <ManualWarrantyWorkspace ref="warrantyPanel" v-model="warrantyOpen" :asset="warrantyAsset" @saved="warrantySaved" />
    <AssetMetadataWorkspace ref="metadataPanel" v-model="metadataOpen" :asset="metadataAsset" @saved="metadataSaved" />
    <AssetDependencyWorkspace ref="dependencyPanel" v-model="dependencyOpen" :asset="dependencyAsset" />
  </section>
</template>

<style>
.asset-time-refresh{min-height:44px}
.asset-ledger-explanation{padding:14px 20px;margin:0;font-size:12px;line-height:1.8;background:#f8fafd;border-top:1px solid #edf1f6}
.assets-heading-actions{display:flex;flex-wrap:wrap;gap:8px}.assets-heading-actions .el-button+.el-button{margin-left:0}
@media(max-width:760px){.assets-heading-actions .el-button{min-height:44px;height:auto;white-space:normal}.asset-ledger-explanation{padding:12px 14px}.filter-form .el-form-item__label{text-align:left}}
</style>

<style scoped>
.asset-toolbar :deep(.el-input),.asset-toolbar :deep(.el-select){min-width:0}
.asset-status-chips{max-width:100%;min-width:0}
.asset-select-filters{flex-wrap:wrap}
.asset-select-filters :deep(.el-button){margin-left:0}
@media(max-width:760px){.asset-select-filters :deep(.el-button){flex:1;min-height:44px}.asset-toolbar :deep(.el-input__wrapper),.asset-toolbar :deep(.el-select__wrapper){min-height:44px}.asset-extended-filters{min-width:0;overflow-wrap:anywhere}}
</style>
