<script setup lang="ts">
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api, isCancelled } from '../api/client'
import type { Asset, AssetPage } from '../api/types'
import { contextGeneration, session } from '../stores/session'
import { assetTypes, displayTime, typeLabel, sourceLabel } from '../utils/display'
import AppTable from '../components/AppTable.vue'
import AssetStateFacts from '../components/AssetStateFacts.vue'
import AssetDraftCreatePanel from '../components/AssetDraftCreatePanel.vue'
import SampleFreshness from '../components/SampleFreshness.vue'
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
const ElTableColumn = ElementTableColumn<Asset>
const route = useRoute()
const router = useRouter()
const pageSizes = [10, 15, 25, 50, 100]
const queryString = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : ''
const pageSize = computed(() => pageSizes.includes(Number(queryString('page_size'))) ? Number(queryString('page_size')) : 15)
const filters = reactive({ q: '', status: '', type: '', site_id: '' })
const rows = ref<Asset[]>([])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const cursorHistory = ref<string[]>([])
const loading = ref(false)
const error = ref<unknown>(null)
const listController = ref<AbortController>()
let listGeneration = 0
const signature = computed(() => JSON.stringify(['q', 'status', 'type', 'site_id', 'cursor', 'page_size'].map(queryString)))
watch(signature, () => {
  for (const key of ['q', 'status', 'type', 'site_id'] as const) filters[key] = queryString(key)
  void loadAssets()
}, { immediate: true })
async function loadAssets() {
  listController.value?.abort(); const controller = new AbortController(); listController.value = controller
  const generation = ++listGeneration
  loading.value = true; error.value = null; rows.value = []; nextCursor.value = null; hasMore.value = false
  const params = new URLSearchParams({ page_size: String(pageSize.value) })
  for (const key of ['q', 'status', 'type', 'site_id', 'cursor']) if (queryString(key)) params.set(key, queryString(key))
  try {
    const page = await api.request<AssetPage>(`/assets?${params}`, { signal: controller.signal })
    if (generation !== listGeneration) return
    rows.value = page.items; nextCursor.value = page.next_cursor; hasMore.value = page.has_more
  } catch (cause) { if (!isCancelled(cause) && generation === listGeneration) error.value = cause }
  finally { if (generation === listGeneration) loading.value = false }
}
function applyFilters() {
  if (loading.value) return
  cursorHistory.value = []
  const query = { ...route.query, ...Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value.trim() || undefined])), cursor: undefined, page_size: String(pageSize.value) }
  const unchanged = !queryString('cursor') && Object.entries(filters).every(([key, value]) => value.trim() === queryString(key))
  if (unchanged) void loadAssets()
  else void router.replace({ query })
}
function resetFilters() { Object.assign(filters, { q: '', status: '', type: '', site_id: '' }); applyFilters() }
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
  <section>
    <div class="page-heading"><div><span class="eyebrow">RESOURCE / ASSETS</span><h1>资产管理</h1><p>管理当前租户的资产台账，查看来源与接入状态。</p></div><div class="assets-heading-actions"><PermissionGate capability="assets:write"><el-button type="primary" @click="openCreate">＋ 创建资产草稿</el-button><el-button v-if="metadataAllowed" plain @click="metadataQueryOpen = true">手工资料筛选</el-button><PermissionGate capability="asset_dates:read"><el-button plain @click="dateQueryOpen = true">维保与超期日期筛选</el-button></PermissionGate><el-button v-if="importAllowed" plain @click="importOpen = true">导入资产来源</el-button></PermissionGate></div></div>
    <div class="scope-card"><div><span class="small-label">当前租户</span><strong>{{ session?.tenant.name }}</strong></div><div><span class="small-label">本页资产</span><strong>{{ loading || error ? '—' : rows.length }} <small>个</small></strong></div><div class="scope-explanation"><span class="scope-dot" /><span>测试数据和手动草稿尚未接入真实采集，采样时间和指标暂缺；状态“未知”不表示设备正常。</span></div></div>
    <div class="panel">
      <el-form class="filter-form" :inline="true" @submit.prevent="applyFilters">
        <el-form-item label="搜索"><el-input v-model="filters.q" clearable placeholder="资产名称 / 管理 IP" aria-label="搜索资产" /></el-form-item>
        <el-form-item label="汇总状态"><el-select v-model="filters.status" clearable placeholder="全部状态" aria-label="资产状态"><el-option label="正常" value="ok" /><el-option label="有问题" value="problem" /><el-option label="未知" value="unknown" /><el-option label="维护中" value="maintenance" /><el-option label="已停用" value="disabled" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="filters.type" clearable placeholder="全部类型" aria-label="设备类型"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item>
        <el-form-item label="站点"><el-input v-model="filters.site_id" clearable placeholder="站点 ID" aria-label="站点 ID" /></el-form-item>
        <el-form-item class="filter-actions"><el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button :disabled="loading" @click="resetFilters">重置</el-button><el-button :loading="loading" @click="loadAssets">刷新</el-button></el-form-item>
      </el-form>
      <ApiErrorNotice :error="error" guidance="请点击“重试”重新加载当前列表。"><el-button size="small" @click="loadAssets">重试</el-button></ApiErrorNotice>
      <AppTable :error="error" :rows="rows" :loading="loading" :empty-text="error ? '数据加载失败，请重试' : '当前筛选下没有资产'">
        <el-table-column v-for="column in assetLedgerColumns" :key="column.key" :label="column.label" :min-width="column.minWidth" :fixed="column.key === 'actions' ? 'right' : undefined">
          <template #default="{ row }">
            <el-button v-if="column.key === 'name' || column.key === 'actions'" link type="primary" :class="{ 'asset-link': column.key === 'name' }" @click="openDetail(row.id)">{{ assetLedgerCell(row, column.key).text }}</el-button>
            <span v-else>{{ assetLedgerCell(row, column.key).text }}</span>
            <span v-if="assetLedgerCell(row, column.key).detail" class="cell-sub">{{ assetLedgerCell(row, column.key).detail }}</span>
          </template>
        </el-table-column>
      </AppTable>
      <p class="muted asset-ledger-explanation">台账按约定顺序展示12列，可左右滚动查看。Proxy、所属业务系统、CPU、内存、当前问题数和负责人暂未提供，以“—”显示；这不表示数值为零、设备正常或尚未配置。手工资料需在对应页面按权限查看。</p>
      <div class="table-footer"><span>仅显示当前租户内有权查看的资产 · 本页 {{ loading || error ? '—' : rows.length }} 条</span><div class="cursor-pagination"><el-select :model-value="pageSize" :disabled="loading" aria-label="每页条数" @change="resizePage"><el-option v-for="size in pageSizes" :key="size" :label="`${size} 条 / 页`" :value="size" /></el-select><el-button v-if="queryString('cursor') && !cursorHistory.length" :disabled="loading" @click="firstPage">返回首页</el-button><el-button v-else :disabled="loading || !cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="loading || !hasMore" @click="nextPage">下一页</el-button></div></div>
    </div>
    <el-drawer v-model="detailOpen" title="资产详情" size="560px" class="asset-drawer" destroy-on-close>
      <ApiErrorNotice :error="detailError" />
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <template v-if="asset"><div class="detail-title"><span class="eyebrow">{{ typeLabel(asset.type) }}</span><h2>{{ asset.name }}</h2></div><AssetStateFacts :asset="asset" /><el-alert type="info" :closable="false" show-icon :title="asset.unknown_reason || '当前未取得真实采集数据，不能判断为正常。'" />
        <el-descriptions :column="1" border class="asset-descriptions"><el-descriptions-item label="资产 ID"><span class="mono">{{ asset.id }}</span></el-descriptions-item><el-descriptions-item label="所属租户">{{ asset.tenant_id }}</el-descriptions-item><el-descriptions-item label="管理 IP"><span class="mono">{{ asset.management_ip }}</span></el-descriptions-item><el-descriptions-item label="站点">{{ asset.site_name || asset.site_id }}</el-descriptions-item><el-descriptions-item label="生命周期">{{ asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status }}</el-descriptions-item><el-descriptions-item label="来源">{{ sourceLabel(asset.source) }}</el-descriptions-item><el-descriptions-item label="采样时间">{{ assetLedgerCell(asset, 'sampled').text }}<span v-if="assetLedgerCell(asset, 'sampled').detail" class="cell-sub">{{ assetLedgerCell(asset, 'sampled').detail }}</span></el-descriptions-item><el-descriptions-item label="版本">v{{ asset.version }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ displayTime(asset.created_at) }}</el-descriptions-item><el-descriptions-item label="更新时间">{{ displayTime(asset.updated_at) }}</el-descriptions-item></el-descriptions>
        <SampleFreshness :clock="detailClock" :sampled-at="asset.sampled_at" :context-key="`${asset.tenant_id}:${asset.id}`" />
        <div v-if="metadataAllowed" class="detail-section"><h3>手工草稿资料</h3><p class="muted">内部维护明确资料并留存前后历史；管理IP、CMDB和监控配置不变。</p><PermissionGate capability="assets:write"><el-button @click="openMetadata">查看资料与修订历史</el-button><el-button @click="openWarranty">维护维保日期与供应商</el-button></PermissionGate></div>
        <AssetDateAssessmentPanel v-if="metadataAllowed" :asset="asset" />
        <div class="detail-section"><h3>依赖草稿</h3><p class="muted">按完整租户图保存人工依赖提案，监控抑制尚未生效。</p><el-button @click="openDependencies">查看或追加依赖草稿</el-button></div>
        <div class="detail-section"><h3>监控接入</h3><p class="muted">测试环境数据已持久保存；生产隔离、监控组件许可与阈值依赖 Q02 / Q03 / Q06。完成客户确认前，草稿不能发布到真实监控。</p><PermissionGate capability="assets:write"><el-button type="primary" plain :loading="onboarding" @click="requestOnboarding">检查接入条件</el-button></PermissionGate></div>
      </template>
      <template #footer><el-button class="asset-time-refresh" :loading="detailLoading" :disabled="detailLoading" @click="refreshDetail">只读刷新</el-button><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-drawer>
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
