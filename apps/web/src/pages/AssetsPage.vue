<script setup lang="ts">
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api, isCancelled } from '../api/client'
import type { Asset, AssetPage } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import {readAssetList,readAssetSnapshot} from '../utils/assetReadback'
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
import CmdbTypeCatalogPanel from '../components/CmdbTypeCatalogPanel.vue'
import AssetDependencyWorkspace from '../components/AssetDependencyWorkspace.vue'
import AssetMetadataWorkspace from '../components/AssetMetadataWorkspace.vue'
import ManualWarrantyWorkspace from '../components/ManualWarrantyWorkspace.vue'
import AssetMetadataQuery from '../components/AssetMetadataQuery.vue'
import AssetDateAssessmentPanel from '../components/AssetDateAssessmentPanel.vue'
import BackupVersionInventoryPanel from '../components/BackupVersionInventoryPanel.vue'
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
  { label: '正常', value: rows.value.filter(item => item.status === 'ok').length, detail: '采集状态正常', tone: 'success' },
  { label: '有问题', value: rows.value.filter(item => item.status === 'problem').length, detail: '存在活跃告警或异常', tone: 'danger' },
  { label: '未知', value: rows.value.filter(item => item.status === 'unknown').length, detail: '待采集或状态未知', tone: 'warning' },
  { label: '维护中', value: rows.value.filter(item => item.status === 'maintenance').length, detail: '处于计划维护窗口', tone: '' },
  { label: '待接入', value: rows.value.filter(item => item.sync_state === 'PENDING_ONBOARDING').length, detail: '监控配置待同步', tone: 'warning' },
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
let listGeneration = 0, disposed = false
const readScope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,session.value?.mode,can('assets:read'),can('assets:write'),switchingTenant.value]))
const readable=()=>!disposed&&!switchingTenant.value&&!!session.value&&can('assets:read')
const signature = computed(() => JSON.stringify(['q', 'status', 'type', 'site_id', 'cursor', 'page_size'].map(queryString)))
watch(signature, () => {
  syncFilterDraft(filters, appliedFilters.value, previousAppliedFilters)
  previousAppliedFilters = { ...appliedFilters.value }
  void loadAssets()
}, { immediate: true })
async function loadAssets() {
  listController.value?.abort(); const controller = new AbortController(); listController.value = controller
  const generation = ++listGeneration, owner=readScope.value, scope=session.value
  loading.value = true; loaded.value = false; error.value = null; rows.value = []; nextCursor.value = null; hasMore.value = false
  if(!readable()||!scope){loading.value=false;return}
  const requestedSize=pageSize.value
  const params = new URLSearchParams({ page_size: String(pageSize.value) })
  for (const key of ['q', 'status', 'type', 'site_id', 'cursor']) if (queryString(key)) params.set(key, queryString(key))
  try {
    const page = await api.request<AssetPage>(`/assets?${params}`, { signal: controller.signal })
    if (generation !== listGeneration||owner!==readScope.value||!readable()) return
    const verified=readAssetList(page,scope.tenant.id,requestedSize,scope.mode)
    rows.value = verified.items; nextCursor.value = verified.next_cursor; hasMore.value = verified.has_more; loaded.value = true
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
  detailController?.abort(); detailController = new AbortController(); const generation = ++detailGeneration, owner=readScope.value, scope=session.value
  asset.value = null; detailClock.value = null; detailError.value = null; onboarding.value = false
  if (!id||!readable()||!scope) { detailLoading.value = false; return }
  detailLoading.value = true
  try { const result = await api.requestWithClock<Asset>(`/assets/${encodeURIComponent(id)}`, { signal: detailController.signal }); if (generation === detailGeneration&&owner===readScope.value&&readable()) { const verified=readAssetSnapshot(result.data,scope.tenant.id,id);detailClock.value = result.clock; asset.value = verified } }
  catch (cause) { if (!isCancelled(cause) && generation === detailGeneration) detailError.value = cause }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function refreshDetail() { void loadDetail(queryString('asset_id')) }
function openDetail(id: string) { void router.replace({ query: { ...route.query, asset_id: id } }) }
async function requestOnboarding() {
  if (!asset.value||!readable()||!can('assets:write')||onboarding.value) return
  const owner=readScope.value,id=asset.value.id,turn=detailGeneration
  onboarding.value = true; detailError.value = null
  try { await api.request(`/assets/${encodeURIComponent(asset.value.id)}/onboarding`, { method: 'POST', body: { expected_version: asset.value.version }, signal: detailController?.signal }); if(owner===readScope.value&&turn===detailGeneration&&queryString('asset_id')===id&&readable())await loadDetail(id) }
  catch (cause) { if (!isCancelled(cause)&&owner===readScope.value&&turn===detailGeneration&&readable()) detailError.value = cause }
  finally { if(owner===readScope.value&&turn===detailGeneration)onboarding.value = false }
}

const createOpen = ref(false)
const createPanel = ref<InstanceType<typeof AssetDraftCreatePanel> | null>(null)
const dependencyOpen = ref(false), dependencyAsset = ref<Asset | null>(null)
const dependencyPanel = ref<InstanceType<typeof AssetDependencyWorkspace> | null>(null)
function openDependencies() {
  if (!asset.value) return
  if (dependencyPanel.value?.hasUnresolved() && dependencyAsset.value?.id !== asset.value.id) { ElMessage.warning('另一台设备的依赖关系正在确认保存结果，请先返回原设备处理'); return }
  dependencyAsset.value = asset.value; dependencyOpen.value = true
}
const metadataQueryOpen = ref(false), dateQueryOpen = ref(false)
const metadataOpen = ref(false), metadataAsset = ref<Asset | null>(null)
const metadataPanel = ref<InstanceType<typeof AssetMetadataWorkspace> | null>(null)
const warrantyOpen = ref(false), warrantyAsset = ref<Asset | null>(null)
const warrantyPanel = ref<InstanceType<typeof ManualWarrantyWorkspace> | null>(null)
let warrantySelectionEpoch = 0
async function openWarranty() {
  const turn = ++warrantySelectionEpoch, selected = asset.value
  if (!selected || !readable() || !can('assets:write') || !metadataAllowed.value) return false
  if (metadataPanel.value?.hasUnresolved()) { ElMessage.warning('设备扩展资料保存状态待确认，请先处理当前提交'); return false }
  if (warrantyPanel.value?.hasUnresolved() && warrantyAsset.value?.id !== selected.id) { ElMessage.warning('另一台设备的维保档案保存状态待确认，请先返回原设备处理'); return false }
  const owner = readScope.value, target = JSON.stringify(selected), editor = warrantyPanel.value, before = editor?.inputSnapshot()
  if (editor && !await editor.canReplace(selected)) return false
  if (turn !== warrantySelectionEpoch || owner !== readScope.value || !readable() || !can('assets:write') || !metadataAllowed.value || editor !== warrantyPanel.value || before !== editor?.inputSnapshot() || target !== JSON.stringify(selected) || target !== JSON.stringify(asset.value) || metadataPanel.value?.hasUnresolved()) return false
  warrantyAsset.value = selected; warrantyOpen.value = true; return true
}
function warrantySaved() { void loadAssets(); if (queryString('asset_id') === warrantyAsset.value?.id) void loadDetail(queryString('asset_id')) }
const metadataAllowed = computed(() => ['msp', 'padmin'].includes(session.value?.user.role || ''))
let metadataSelectionEpoch = 0
async function replaceMetadataTarget(selected: Asset, fromQuery: boolean) {
  const selectionTurn = ++metadataSelectionEpoch
  if (warrantyPanel.value?.hasUnresolved()) { ElMessage.warning('设备维保档案保存状态待确认，请先处理当前提交'); return false }
  if (!readable() || !can('assets:write') || !metadataAllowed.value) return false
  const owner = readScope.value, target = JSON.stringify(selected), editor = metadataPanel.value, before = editor?.inputSnapshot(), queryWasOpen = metadataQueryOpen.value
  if (editor && !await editor.canReplace(selected)) return false
  if (selectionTurn !== metadataSelectionEpoch || owner !== readScope.value || !readable() || !can('assets:write') || !metadataAllowed.value || editor !== metadataPanel.value || before !== editor?.inputSnapshot() || target !== JSON.stringify(selected) || fromQuery && queryWasOpen !== metadataQueryOpen.value || !fromQuery && target !== JSON.stringify(asset.value)) return false
  metadataAsset.value = selected; metadataOpen.value = true; if (fromQuery) metadataQueryOpen.value = false; return true
}
async function openMetadata() { return asset.value ? replaceMetadataTarget(asset.value,false) : false }
async function openQueryMetadata(selected: Asset) { return replaceMetadataTarget(selected,true) }
function metadataSaved() { void loadAssets(); if (queryString('asset_id') === metadataAsset.value?.id) void loadDetail(queryString('asset_id')) }
const importOpen = ref(false)
const importAllowed = computed(() => ['padmin', 'msp', 'tadmin'].includes(session.value?.user.role || ''))
const availableSites = computed(() => [...new Set(rows.value.filter(item => item.tenant_id === session.value?.tenant.id).map(item => item.site_id))])
function openCreate() { createOpen.value = true }
async function beforeCloseCreate(done: () => void) { if (!createPanel.value || await createPanel.value.canLeave()) done() }
const creationScope = () => readScope.value
async function createdAsset(created: Asset) {
  if (created.tenant_id !== session.value?.tenant.id) return
  const scope = creationScope()
  createOpen.value = false
  ElMessage.success('资产草稿已创建，待接入监控')
  try {
    await router.replace({ query: { ...route.query, asset_id: created.id } })
    if (scope === creationScope()) await loadAssets()
  } catch (cause) { if (!isCancelled(cause) && scope === creationScope()) error.value = cause }
}
watch(readScope,()=>{
 listGeneration++;detailGeneration++;listController.value?.abort();detailController?.abort();rows.value=[];loaded.value=false;loading.value=false;error.value=null;nextCursor.value=null;hasMore.value=false;cursorHistory.value=[]
 asset.value=null;detailClock.value=null;detailError.value=null;detailLoading.value=false;onboarding.value=false;detailTab.value='overview';Object.assign(filters,appliedFilters.value);previousAppliedFilters={...appliedFilters.value}
 createOpen.value=false;dependencyOpen.value=false;dependencyAsset.value=null;metadataOpen.value=false;metadataAsset.value=null;warrantyOpen.value=false;warrantyAsset.value=null;metadataQueryOpen.value=false;dateQueryOpen.value=false;importOpen.value=false
 if(readable()){void loadAssets();void loadDetail(queryString('asset_id'))}
},{flush:'sync'})
onUnmounted(() => { disposed=true;listGeneration++;detailGeneration++;listController.value?.abort(); detailController?.abort() })
</script>
<template>
  <section class="assets-prototype-page" :aria-busy="loading||detailLoading">
    <p v-if="!readable()" role="status">当前没有资产读取权限，或正在切换租户；原范围数据已清除。</p>
    <div v-if="readable()" v-show="!detailOpen" class="asset-list-workspace">
    <div class="page-heading"><div class="asset-page-title"><div class="title-with-context"><h1>资产管理</h1><span class="context-badge">{{ session?.tenant.name }}</span><small class="tabular-nums">当前页 {{ loading || error || !loaded ? '—' : rows.length }} 台设备</small></div><details class="boundary-details"><summary>统计口径说明</summary><p>展示当前租户授权范围内的资产台账与实时监控状态；上方指标仅统计当前页记录，未采集指标标注为待采集。</p></details></div><div class="assets-heading-actions"><div class="asset-view-switch" aria-label="资产显示方式"><button type="button" :class="{ active: assetView === 'list' }" :aria-pressed="assetView === 'list'" @click="assetView = 'list'"><List />列表</button><button type="button" :class="{ active: assetView === 'grid' }" :aria-pressed="assetView === 'grid'" @click="assetView = 'grid'"><Grid />蜂巢</button></div><el-button v-if="exportAllowed" :icon="Download" @click="router.push('/asset-export-jobs')">导出</el-button><PermissionGate capability="assets:write"><el-button v-if="importAllowed" :icon="Upload" @click="importOpen = true">导入</el-button><el-button type="primary" :icon="Plus" @click="openCreate">创建资产草稿</el-button></PermissionGate></div></div>
    <div class="scope-card feature-summary assets-kpis" aria-label="当前页资产统计"><div v-for="metric in pageMetrics" :key="metric.label" :class="metric.tone"><span class="small-label">{{ metric.label }}</span><strong class="tabular-nums">{{ loading || error || !loaded ? '—' : metric.value }}</strong><small>{{ loading || error || !loaded ? '等待当前页数据' : metric.detail }}</small></div></div>
    <div class="panel">
      <el-form class="asset-toolbar" :inline="true" @submit.prevent="applyFilters" @compositionstart="searchComposition.start" @compositionend="searchComposition.end" @keydown="searchComposition.keydown">
        <el-input v-model="filters.q" :prefix-icon="Search" clearable placeholder="搜索设备名称 / 管理 IP" aria-label="搜索资产" enterkeyhint="search" class="asset-search" />
        <div class="asset-status-chips" aria-label="按资产状态筛选"><button v-for="item in statusFilters" :key="item.value" type="button" :disabled="loading" :class="{ active: queryString('status') === item.value }" :aria-pressed="queryString('status') === item.value" @click="filterStatus(item.value)">{{ item.label }}</button></div>
        <div class="asset-select-filters"><el-select v-model="filters.type" clearable placeholder="全部类型" aria-label="设备类型"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select><el-input v-model="filters.site_id" clearable placeholder="全部站点 / 站点 ID" aria-label="站点 ID" enterkeyhint="search" /><el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button :disabled="loading" @click="resetFilters">重置</el-button><el-button :icon="Refresh" :loading="loading" aria-label="刷新已应用的资产筛选" title="仅刷新已应用条件，保留未提交输入" @click="loadAssets">刷新</el-button></div>
        <div v-if="metadataAllowed" class="asset-extended-filters"><span class="extended-filter-label">高级检索</span><el-button text bg size="small" @click="metadataQueryOpen = true">扩展资料检索</el-button><PermissionGate capability="asset_dates:read"><el-button text bg size="small" @click="dateQueryOpen = true">维保与超期筛查</el-button></PermissionGate><span class="asset-table-scope">统计范围：本页记录</span></div>
      </el-form>
      <CmdbTypeCatalogPanel />
      <AppliedFilters :items="filterItems" :pending="pendingFilters" :loading="loading" :failed="Boolean(error)" :loaded="loaded" :count="rows.length" @remove="removeFilter" @clear="resetFilters" />
      <ApiErrorNotice :error="error" guidance="请点击“重试”重新加载当前列表。"><el-button size="small" @click="loadAssets">重试</el-button></ApiErrorNotice>
      <AppTable v-if="assetView === 'list'" :error="error" :rows="rows" :loading="loading" :empty-text="error ? '列表加载失败，请点击重试' : !loaded ? '正在读取资产列表…' : filterItems.length ? '未找到匹配资产，可调整或清空筛选条件' : '当前授权范围内暂无资产'">
        <el-table-column v-for="column in assetLedgerColumns" :key="column.key" :label="column.label" :min-width="column.minWidth" :fixed="column.key === 'actions' ? 'right' : undefined">
          <template #default="{ row }">
              <div v-if="column.key === 'name'" class="asset-identity-cell"><span class="asset-type-symbol" :class="'asset-type-' + row.type" aria-hidden="true"><component :is="deviceIcons[row.type] || Box" /></span><div><el-button link type="primary" class="asset-link" @click="openDetail(row.id)">{{ assetLedgerCell(row, column.key).text }}</el-button><span class="cell-sub mono">{{ row.management_ip || '—' }}</span></div></div>
              <el-button v-else-if="column.key === 'actions'" link type="primary" @click="openDetail(row.id)">{{ assetLedgerCell(row, column.key).text }}</el-button>
              <span v-else-if="column.key === 'availability' || column.key === 'sync'" class="ledger-state" :class="{ 'ledger-state-pending': column.key === 'sync' }">{{ assetLedgerCell(row, column.key).text }}</span>
            <span v-else :class="{ 'tabular-nums': ['cpu', 'memory', 'problems', 'sampled'].includes(column.key) }">{{ assetLedgerCell(row, column.key).text }}</span>
              <span v-if="column.key !== 'name' && assetLedgerCell(row, column.key).detail && !['cpu','memory','business','problems','owner'].includes(column.key)" class="cell-sub">{{ assetLedgerCell(row, column.key).detail }}</span>
          </template>
        </el-table-column>
        <template #empty-action><el-button v-if="loaded && !error && filterItems.length" @click="resetFilters">清空筛选，查看全部资产</el-button></template>
      </AppTable>
      <div v-else class="asset-honeycomb-region" :aria-busy="loading"><el-skeleton v-if="loading" :rows="5" animated /><div v-else-if="rows.length" class="asset-honeycomb"><button v-for="item in rows" :key="item.id" type="button" class="asset-tile" :class="'asset-tile-' + item.status" @click="openDetail(item.id)"><span class="tile-top"><component :is="deviceIcons[item.type] || Box" /><span>{{ typeLabel(item.type) }}</span></span><strong>{{ item.name }}</strong><span class="mono">{{ item.management_ip }}</span><span>{{ item.site_name || item.site_id }}</span><div><b>{{ assetLedgerCell(item, 'availability').text }}</b><small>{{ assetLedgerCell(item, 'sync').text }}</small></div></button></div><el-empty v-else :description="error ? '读取未完成，请按上方提示重试' : !loaded ? '正在读取资产列表…' : filterItems.length ? '未找到匹配资产，可调整或清空筛选条件' : '当前授权范围内暂无资产'"><el-button v-if="loaded && !error && filterItems.length" @click="resetFilters">清空筛选，查看全部资产</el-button></el-empty></div>
      <details class="asset-ledger-explanation boundary-details"><summary>台账字段说明</summary><p>表格支持横向滚动查看完整 12 列台账。采集代理（Proxy）、所属业务系统、CPU / 内存使用率、当前问题数及负责人在未开通采集或未登记时显示为“—”。</p></details>
      <div class="table-footer"><span class="tabular-nums">当前租户授权资产 · 本页 {{ loading || error || !loaded ? '—' : rows.length }} 条</span><div class="cursor-pagination"><el-select :model-value="pageSize" :disabled="loading" aria-label="每页条数" @change="resizePage"><el-option v-for="size in pageSizes" :key="size" :label="`${size} 条 / 页`" :value="size" /></el-select><el-button v-if="queryString('cursor') && !cursorHistory.length" :disabled="loading" @click="firstPage">返回首页</el-button><el-button v-else :disabled="loading || !cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="loading || !hasMore" @click="nextPage">下一页</el-button></div></div>
    </div>
    </div>
    <section v-if="detailOpen&&readable()" class="asset-detail-workspace asset-drawer" aria-label="资产详情">
      <div class="asset-detail-top"><el-button :icon="ArrowLeft" text @click="detailOpen = false">返回资产列表</el-button><span>资产管理 / 资产详情</span><el-button :icon="Refresh" :loading="detailLoading" @click="refreshDetail">刷新详情</el-button></div>
      <ApiErrorNotice :error="detailError" />
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <template v-if="asset"><header class="asset-detail-hero"><div class="asset-hero-title"><span class="asset-type-symbol"><component :is="deviceIcons[asset.type] || Box" /></span><div><div class="title-with-context"><h1>{{ asset.name }}</h1><span class="ledger-state">{{ assetLedgerCell(asset, 'availability').text }}</span><span class="ledger-state ledger-state-pending">{{ assetLedgerCell(asset, 'sync').text }}</span></div><p>{{ asset.management_ip }} · {{ typeLabel(asset.type) }} · {{ session?.tenant.name }} / {{ asset.site_name || asset.site_id }}</p></div></div><div class="asset-hero-actions"><PermissionGate capability="assets:write"><el-button v-if="metadataAllowed" @click="openMetadata">扩展资料</el-button><el-button v-if="metadataAllowed" @click="openWarranty">维保档案</el-button></PermissionGate><el-button @click="openDependencies">依赖拓扑</el-button><el-button v-if="can('assets:read')" @click="router.push('/network-monitoring')">网络监控</el-button></div><div class="asset-facts-grid"><div><small>资产标识</small><strong class="mono" :title="asset.id">{{ asset.id }}</strong></div><div><small>所属站点</small><strong>{{ asset.site_name || asset.site_id }}</strong></div><div><small>资料来源</small><strong>{{ sourceLabel(asset.source) }}</strong></div><div><small>生命周期</small><strong>{{ asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status }}</strong></div><div><small>最近采样</small><strong class="tabular-nums">{{ assetLedgerCell(asset, 'sampled').text }}</strong></div><div><small>登记版本</small><strong class="tabular-nums">v{{ asset.version }}</strong></div></div></header>
      <div class="asset-detail-body panel"><nav class="asset-detail-tabs" aria-label="资产详情分区"><button v-for="tab in detailTabs" :key="tab.id" type="button" :class="{ active: detailTab === tab.id }" :aria-pressed="detailTab === tab.id" @click="detailTab = tab.id">{{ tab.label }}</button></nav>
      <div v-if="detailTab === 'overview'" class="asset-overview"><LiveMonitoringPanel :key="asset.id" :asset-id="asset.id" /><details class="asset-source-details"><summary>资产登记状态与来源</summary><AssetStateFacts :asset="asset" /><SampleFreshness :clock="detailClock" :sampled-at="asset.sampled_at" :context-key="`${asset.tenant_id}:${asset.id}`" /><p>台账生命周期与实时监控接入状态独立维护。</p></details></div>
      <LiveMonitoringPanel v-else-if="['items','charts','triggers','events'].includes(detailTab)" :key="asset.id + detailTab" :asset-id="asset.id" :mode="detailTab as 'items' | 'charts' | 'triggers' | 'events'" />
      <div v-else-if="detailTab === 'inventory'" class="asset-inventory-panel">
        <el-descriptions :column="1" border class="asset-descriptions"><el-descriptions-item label="资产 ID"><span class="mono">{{ asset.id }}</span></el-descriptions-item><el-descriptions-item label="所属租户"><span class="mono">{{ asset.tenant_id }}</span></el-descriptions-item><el-descriptions-item label="管理 IP"><span class="mono">{{ asset.management_ip }}</span></el-descriptions-item><el-descriptions-item label="站点">{{ asset.site_name || asset.site_id }}</el-descriptions-item><el-descriptions-item label="生命周期">{{ asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status }}</el-descriptions-item><el-descriptions-item label="来源">{{ sourceLabel(asset.source) }}</el-descriptions-item><el-descriptions-item label="采样时间"><span class="tabular-nums">{{ assetLedgerCell(asset, 'sampled').text }}</span><span v-if="assetLedgerCell(asset, 'sampled').detail" class="cell-sub">{{ assetLedgerCell(asset, 'sampled').detail }}</span></el-descriptions-item><el-descriptions-item label="版本"><span class="tabular-nums">v{{ asset.version }}</span></el-descriptions-item><el-descriptions-item label="创建时间"><span class="tabular-nums">{{ displayTime(asset.created_at) }}</span></el-descriptions-item><el-descriptions-item label="更新时间"><span class="tabular-nums">{{ displayTime(asset.updated_at) }}</span></el-descriptions-item></el-descriptions>
        <div v-if="metadataAllowed" class="detail-section"><h3>手工维护档案</h3><p class="muted">维护设备扩展属性与维保档案，自动留存修订记录；不改变管理 IP、CMDB 权威源与监控采集配置。</p><PermissionGate capability="assets:write"><div class="detail-section-actions"><el-button @click="openMetadata">扩展资料与修订历史</el-button><el-button @click="openWarranty">维保档案与供应商</el-button></div></PermissionGate></div>
        <AssetDateAssessmentPanel v-if="metadataAllowed" :asset="asset" />
        <BackupVersionInventoryPanel :key="asset.id" :asset="asset" />
      </div>
        <div v-else-if="detailTab === 'relations'" class="asset-inventory-panel"><div class="panel-heading"><h3>设备关联与依赖</h3><span>拓扑草稿</span></div><div class="prototype-inline-empty"><Connection /><strong>管理当前资产的上下游依赖关系</strong><span>按租户有向无环图（DAG）维护设备依赖草稿，经审批发布后用于告警收敛抑制。</span><el-button type="primary" @click="openDependencies">查看或编辑依赖关系</el-button></div></div>
        <div v-else-if="detailTab === 'configuration'" class="asset-inventory-panel"><div class="panel-heading"><h3>监控接入</h3><span>待确认</span></div><p class="muted">正式监控发布需完成生产网络隔离、采集组件部署及默认阈值基线确认（配置项 Q02 / Q03 / Q06）。</p><PermissionGate capability="assets:write"><el-button type="primary" :loading="onboarding" @click="requestOnboarding">检查接入条件</el-button></PermissionGate></div>

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

<style scoped>
.tabular-nums{font-variant-numeric:tabular-nums}
.asset-time-refresh{min-height:44px}
.asset-ledger-explanation{padding:14px 20px;margin:0;font-size:12px;line-height:1.8;background:#f8fafd;border-top:1px solid #edf1f6}
.assets-heading-actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.assets-heading-actions :deep(.el-button+.el-button){margin-left:0}
.asset-view-switch button{transition:background-color .15s,color .15s,box-shadow .15s}.asset-view-switch button:focus-visible{outline:2px solid #2563eb;outline-offset:2px}
.asset-toolbar :deep(.el-input),.asset-toolbar :deep(.el-select){min-width:0}
.asset-status-chips{max-width:100%;min-width:0}.asset-status-chips button{transition:background-color .15s,color .15s,border-color .15s}.asset-status-chips button:focus-visible{outline:2px solid #2563eb;outline-offset:2px}
.asset-select-filters{flex-wrap:wrap}
.asset-select-filters :deep(.el-button){margin-left:0}
.asset-extended-filters{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.extended-filter-label{font-size:12px;font-weight:600;color:#4e637a}.asset-extended-filters :deep(.el-button+.el-button){margin-left:0}
.asset-tile{transition:transform .16s ease,box-shadow .16s ease,border-color .16s ease}.asset-tile:hover{transform:translateY(-2px);box-shadow:0 6px 16px rgba(15,23,42,.07)}.asset-tile:focus-visible{outline:2px solid #2563eb;outline-offset:2px}
.detail-section-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:10px}.detail-section-actions :deep(.el-button+.el-button){margin-left:0}
@media(max-width:760px){.assets-heading-actions :deep(.el-button){min-height:44px;height:auto;white-space:normal}.asset-ledger-explanation{padding:12px 14px}.asset-select-filters :deep(.el-button){flex:1;min-height:44px}.asset-toolbar :deep(.el-input__wrapper),.asset-toolbar :deep(.el-select__wrapper){min-height:44px}.asset-extended-filters{min-width:0;overflow-wrap:anywhere}}
</style>
