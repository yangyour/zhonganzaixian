<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { api, isCancelled } from '../api/client'
import type { Asset, AssetDraft, AssetPage } from '../api/types'
import { session } from '../stores/session'
import { assetTypes, displayTime, typeLabel, sourceLabel } from '../utils/display'
import AppTable from '../components/AppTable.vue'
import StatusTag from '../components/StatusTag.vue'
import PermissionGate from '../components/PermissionGate.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import AssetImportWorkspace from '../components/AssetImportWorkspace.vue'
import AssetDependencyWorkspace from '../components/AssetDependencyWorkspace.vue'
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
  cursorHistory.value = []
  void router.replace({ query: { ...route.query, ...Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value.trim() || undefined])), cursor: undefined, page_size: String(pageSize.value) } })
}
function resetFilters() { Object.assign(filters, { q: '', status: '', type: '', site_id: '' }); applyFilters() }
function resizePage(value: unknown) { cursorHistory.value = []; void router.replace({ query: { ...route.query, cursor: undefined, page_size: String(value) } }) }
function nextPage() { if (!nextCursor.value) return; cursorHistory.value.push(queryString('cursor')); void router.replace({ query: { ...route.query, cursor: nextCursor.value, page_size: String(pageSize.value) } }) }
function previousPage() { const cursor = cursorHistory.value.pop(); void router.replace({ query: { ...route.query, cursor: cursor || undefined } }) }

const detailOpen = computed({ get: () => Boolean(queryString('asset_id')), set: value => { if (!value) void router.replace({ query: { ...route.query, asset_id: undefined } }) } })
const asset = ref<Asset | null>(null)
const detailError = ref<unknown>(null)
const detailLoading = ref(false)
const onboarding = ref(false)
let detailController: AbortController | undefined
let detailGeneration = 0
watch(() => queryString('asset_id'), id => { void loadDetail(id) }, { immediate: true })
async function loadDetail(id: string) {
  detailController?.abort(); detailController = new AbortController(); const generation = ++detailGeneration
  asset.value = null; detailError.value = null; onboarding.value = false
  if (!id) { detailLoading.value = false; return }
  detailLoading.value = true
  try { const result = await api.request<Asset>(`/assets/${encodeURIComponent(id)}`, { signal: detailController.signal }); if (generation === detailGeneration) asset.value = result }
  catch (cause) { if (!isCancelled(cause) && generation === detailGeneration) detailError.value = cause }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function openDetail(id: string) { void router.replace({ query: { ...route.query, asset_id: id } }) }
async function requestOnboarding() {
  if (!asset.value) return
  onboarding.value = true; detailError.value = null
  try { await api.request(`/assets/${encodeURIComponent(asset.value.id)}/onboarding`, { method: 'POST', body: { expected_version: asset.value.version }, signal: detailController?.signal }); await loadDetail(queryString('asset_id')) }
  catch (cause) { if (!isCancelled(cause)) detailError.value = cause }
  finally { onboarding.value = false }
}

const createOpen = ref(false)
const formRef = ref<FormInstance>()
const draft = reactive<AssetDraft>({ name: '', type: 'switch', management_ip: '', site_id: '' })
const createError = ref<unknown>(null)
const creating = ref(false)
const dependencyOpen = ref(false), dependencyAsset = ref<Asset | null>(null)
const dependencyPanel = ref<InstanceType<typeof AssetDependencyWorkspace> | null>(null)
function openDependencies() {
  if (!asset.value) return
  if (dependencyPanel.value?.hasUnresolved() && dependencyAsset.value?.id !== asset.value.id) { ElMessage.warning('另一台设备的依赖保存结果尚未确认，请先打开原设备并使用原请求重试'); return }
  dependencyAsset.value = asset.value; dependencyOpen.value = true
}
const importOpen = ref(false)
const importAllowed = computed(() => ['padmin', 'msp', 'tadmin'].includes(session.value?.user.role || ''))
const availableSites = computed(() => [...new Set(rows.value.map(item => item.site_id))])
const rules: FormRules<AssetDraft> = {
  name: [{ required: true, whitespace: true, message: '请输入资产名称', trigger: 'blur' }, { max: 100, message: '名称最多 100 个字符', trigger: 'blur' }],
  type: [{ required: true, message: '请选择设备类型', trigger: 'change' }],
  management_ip: [{ required: true, whitespace: true, message: '请输入管理 IP', trigger: 'blur' }],
  site_id: [{ required: true, whitespace: true, message: '请输入站点 ID', trigger: 'blur' }],
}
function openCreate() { Object.assign(draft, { name: '', type: 'switch', management_ip: '', site_id: availableSites.value[0] || filters.site_id || '' }); createError.value = null; createOpen.value = true; formRef.value?.clearValidate() }
async function createAsset() {
  if (!await formRef.value?.validate().catch(() => false)) return
  creating.value = true; createError.value = null
  try {
    const created = await api.request<Asset>('/assets', { method: 'POST', body: { ...draft, name: draft.name.trim(), management_ip: draft.management_ip.trim(), site_id: draft.site_id.trim() }, signal: listController.value?.signal })
    createOpen.value = false; ElMessage.success('资产草稿已保存，状态以服务端回读为准')
    await router.replace({ query: { ...route.query, asset_id: created.id } })
    await loadAssets()
  } catch (cause) { if (!isCancelled(cause)) createError.value = cause }
  finally { creating.value = false }
}
onUnmounted(() => { listController.value?.abort(); detailController?.abort() })
</script>
<template>
  <section>
    <div class="page-heading"><div><span class="eyebrow">RESOURCE / ASSETS</span><h1>资产管理</h1><p>管理当前租户的资产台账，查看来源与接入状态。</p></div><div class="assets-heading-actions"><PermissionGate capability="assets:write"><el-button type="primary" @click="openCreate">＋ 创建资产草稿</el-button><el-button v-if="importAllowed" plain @click="importOpen = true">导入资产来源</el-button></PermissionGate></div></div>
    <div class="scope-card"><div><span class="small-label">当前租户</span><strong>{{ session?.tenant.name }}</strong></div><div><span class="small-label">本页资产</span><strong>{{ loading ? '—' : rows.length }} <small>个</small></strong></div><div class="scope-explanation"><span class="scope-dot" /><span>未知不代表正常。测试种子与手动草稿未真实采集，采样时间与指标保留为空。</span></div></div>
    <div class="panel">
      <el-form class="filter-form" :inline="true" @submit.prevent="applyFilters">
        <el-form-item label="搜索"><el-input v-model="filters.q" clearable placeholder="资产名称 / 管理 IP" aria-label="搜索资产" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="filters.status" clearable placeholder="全部状态" aria-label="资产状态"><el-option label="正常" value="ok" /><el-option label="有问题" value="problem" /><el-option label="未知" value="unknown" /><el-option label="维护中" value="maintenance" /><el-option label="已停用" value="disabled" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="filters.type" clearable placeholder="全部类型" aria-label="设备类型"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item>
        <el-form-item label="站点"><el-input v-model="filters.site_id" clearable placeholder="站点 ID" aria-label="站点 ID" /></el-form-item>
        <el-form-item class="filter-actions"><el-button type="primary" native-type="submit">查询</el-button><el-button @click="resetFilters">重置</el-button><el-button :loading="loading" @click="loadAssets">刷新</el-button></el-form-item>
      </el-form>
      <ApiErrorNotice :error="error"><el-button size="small" @click="loadAssets">重试</el-button></ApiErrorNotice>
      <AppTable :rows="rows" :loading="loading" :empty-text="error ? '数据加载失败，请重试' : '当前筛选下没有资产'">
        <el-table-column label="资产" min-width="220"><template #default="{ row }"><el-button link type="primary" class="asset-link" @click="openDetail(row.id)">{{ row.name }}</el-button><span class="cell-sub mono">{{ row.management_ip }}</span></template></el-table-column>
        <el-table-column label="设备类型" min-width="130"><template #default="{ row }">{{ typeLabel(row.type) }}</template></el-table-column>
        <el-table-column label="站点" min-width="150"><template #default="{ row }">{{ row.site_name || row.site_id }}</template></el-table-column>
        <el-table-column label="可用性" min-width="110"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="生命周期" min-width="110"><template #default="{ row }"><el-tag type="info" effect="plain">{{ row.lifecycle_status === 'DRAFT' ? '草稿' : row.lifecycle_status }}</el-tag></template></el-table-column>
        <el-table-column label="数据来源 / 最近采样" min-width="180"><template #default="{ row }"><span>{{ sourceLabel(row.source) }}</span><span class="cell-sub">{{ displayTime(row.sampled_at) }}</span></template></el-table-column>
        <el-table-column label="操作" min-width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.id)">详情</el-button></template></el-table-column>
      </AppTable>
      <div class="table-footer"><span>仅显示当前租户授权对象 · 本页 {{ rows.length }} 条</span><div class="cursor-pagination"><el-select :model-value="pageSize" aria-label="每页条数" @change="resizePage"><el-option v-for="size in pageSizes" :key="size" :label="`${size} 条 / 页`" :value="size" /></el-select><el-button :disabled="loading || !cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="loading || !hasMore" @click="nextPage">下一页</el-button></div></div>
    </div>
    <el-drawer v-model="detailOpen" title="资产详情" size="560px" class="asset-drawer" destroy-on-close>
      <ApiErrorNotice :error="detailError" />
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <template v-if="asset"><div class="detail-title"><span class="eyebrow">{{ typeLabel(asset.type) }}</span><h2>{{ asset.name }}</h2><StatusTag :status="asset.status" /></div><el-alert type="info" :closable="false" show-icon :title="asset.unknown_reason || '当前未取得真实采集数据，不能判断为正常。'" />
        <el-descriptions :column="1" border class="asset-descriptions"><el-descriptions-item label="资产 ID"><span class="mono">{{ asset.id }}</span></el-descriptions-item><el-descriptions-item label="所属租户">{{ asset.tenant_id }}</el-descriptions-item><el-descriptions-item label="管理 IP"><span class="mono">{{ asset.management_ip }}</span></el-descriptions-item><el-descriptions-item label="站点">{{ asset.site_name || asset.site_id }}</el-descriptions-item><el-descriptions-item label="生命周期">{{ asset.lifecycle_status === 'DRAFT' ? '草稿' : asset.lifecycle_status }}</el-descriptions-item><el-descriptions-item label="来源">{{ sourceLabel(asset.source) }}</el-descriptions-item><el-descriptions-item label="采样时间">{{ displayTime(asset.sampled_at) }}</el-descriptions-item><el-descriptions-item label="版本">v{{ asset.version }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ displayTime(asset.created_at) }}</el-descriptions-item><el-descriptions-item label="更新时间">{{ displayTime(asset.updated_at) }}</el-descriptions-item></el-descriptions>
        <div class="detail-section"><h3>依赖草稿</h3><p class="muted">按完整租户图保存人工依赖提案，监控抑制尚未生效。</p><el-button @click="openDependencies">查看或追加依赖草稿</el-button></div>
        <div class="detail-section"><h3>监控接入</h3><p class="muted">测试环境数据已持久保存；生产隔离、监控组件许可与阈值依赖 Q02 / Q03 / Q06。完成客户确认前，草稿不能发布到真实监控。</p><PermissionGate capability="assets:write"><el-button type="primary" plain :loading="onboarding" @click="requestOnboarding">检查接入条件</el-button></PermissionGate></div>
      </template>
      <template #footer><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-drawer>
    <el-dialog v-model="createOpen" title="创建资产草稿" width="520px" class="responsive-dialog" :close-on-click-modal="!creating" :show-close="!creating" destroy-on-close>
      <el-alert title="仅保存当前租户的测试环境草稿；不会自动接入监控或产生采集数据。" type="info" :closable="false" show-icon />
      <ApiErrorNotice :error="createError" />
      <el-form ref="formRef" :model="draft" :rules="rules" label-position="top" class="dialog-form" @submit.prevent="createAsset"><el-form-item label="资产名称" prop="name"><el-input v-model="draft.name" maxlength="100" show-word-limit placeholder="例如：总部核心交换机" /></el-form-item><el-form-item label="设备类型" prop="type"><el-select v-model="draft.type"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item><el-form-item label="管理 IP" prop="management_ip"><el-input v-model="draft.management_ip" placeholder="IPv4 或 IPv6，最终由服务端校验" /></el-form-item><el-form-item label="站点 ID" prop="site_id"><el-select v-model="draft.site_id" filterable allow-create default-first-option placeholder="当前租户站点 ID"><el-option v-for="site in availableSites" :key="site" :label="site" :value="site" /></el-select><span class="field-help">选项来自本页资产的服务端站点 ID；草稿提交后还须通过服务端归属校验。</span></el-form-item></el-form>
      <template #footer><el-button :disabled="creating" @click="createOpen = false">取消</el-button><el-button type="primary" :loading="creating" @click="createAsset">保存草稿</el-button></template>
    </el-dialog>
    <AssetImportWorkspace v-model="importOpen" />
    <AssetDependencyWorkspace ref="dependencyPanel" v-model="dependencyOpen" :asset="dependencyAsset" />
  </section>
</template>

<style>
.assets-heading-actions{display:flex;flex-wrap:wrap;gap:8px}.assets-heading-actions .el-button+.el-button{margin-left:0}
@media(max-width:760px){.assets-heading-actions .el-button{min-height:44px;height:auto;white-space:normal}}
</style>
