<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api, ApiError, isCancelled } from '../api/client'
import type { Asset, AssetPage } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createDependencyWorkspace, type DependencyState, type ProposedDependency } from '../utils/assetDependencyWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import AppTable from './AppTable.vue'

const props = defineProps<{ modelValue: boolean; asset: Asset | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<DependencyState>({ draft: null, receipt: null, attempt: null, busy: null, error: null })
const workspace = createDependencyWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.asset?.tenant_id === session.value.tenant.id && can('assets:read') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('assets:write'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !writable.value || !state.value.draft || Boolean(state.value.busy) || unresolved.value)
const visibleEdges = computed(() => state.value.draft?.proposed_edges.filter(edge => edge.parent_id === props.asset?.id || edge.child_id === props.asset?.id) || [])
const query = ref(''), options = ref<Asset[]>([]), optionsLoading = ref(false), optionsError = ref<unknown>(null), moreOptions = ref(false)
const selected = ref(''), direction = ref<'' | 'upstream' | 'downstream'>(''), proposedType = ref(''), proposedScope = ref(''), proposedEnabled = ref('unknown'), validation = ref('')
let searchController: AbortController | undefined, searchGeneration = 0
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, writable.value, props.asset?.id]))
function clearForm() { selected.value = ''; direction.value = ''; proposedType.value = ''; proposedScope.value = ''; proposedEnabled.value = 'unknown'; validation.value = '' }
watch(contextKey, () => {
  searchController?.abort(); ++searchGeneration; options.value = []; optionsLoading.value = false; optionsError.value = null; moreOptions.value = false; query.value = ''; clearForm()
  const current = session.value
  workspace.reset(permitted.value && current && props.asset ? { tenantId: current.tenant.id, assetId: props.asset.id, actorId: current.user.id, permissionVersion: current.permission_version, canWrite: writable.value } : null)
  if (open.value && permitted.value) void workspace.refresh()
}, { immediate: true })
watch(open, value => { if (value && permitted.value && !state.value.busy) void workspace.refresh() })
async function searchAssets() {
  if (!writable.value || state.value.busy || unresolved.value) return
  searchController?.abort(); const controller = new AbortController(); searchController = controller
  const generation = ++searchGeneration, context = contextKey.value, tenantId = session.value!.tenant.id
  optionsLoading.value = true; optionsError.value = null; options.value = []; moreOptions.value = false; selected.value = ''
  try {
    const result = await api.request<AssetPage & { source: unknown }>(`/assets?${new URLSearchParams({ page_size: '100', q: query.value.trim() })}`, { signal: controller.signal })
    if (generation !== searchGeneration || context !== contextKey.value || controller.signal.aborted) return
    if (result.source !== 'postgresql' || !Array.isArray(result.items) || result.items.length > 100 || typeof result.has_more !== 'boolean' || result.items.some(item => item.tenant_id !== tenantId || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(item.id) || typeof item.name !== 'string')) throw new ApiError(200, 'INVALID_RESPONSE', '可选资产回读范围异常，请重新查询', '—')
    options.value = result.items.filter(item => item.id !== props.asset?.id); moreOptions.value = result.has_more
  } catch (error) { if (generation === searchGeneration && !isCancelled(error)) optionsError.value = error }
  finally { if (generation === searchGeneration) optionsLoading.value = false }
}
async function save() {
  if (locked.value || !props.asset) return
  if (!options.value.some(item => item.id === selected.value)) { validation.value = '请先查询并选择当前租户的另一台设备'; return }
  if (!['upstream', 'downstream'].includes(direction.value)) { validation.value = '请明确选择上游或下游方向'; return }
  const edge: ProposedDependency = { parent_id: direction.value === 'upstream' ? selected.value : props.asset.id, child_id: direction.value === 'upstream' ? props.asset.id : selected.value, proposed_type: proposedType.value.trim() || null, proposed_suppression_scope: proposedScope.value.trim() || null, proposed_enabled: proposedEnabled.value === 'unknown' ? null : proposedEnabled.value === 'true' }
  validation.value = ''
  try { if (await workspace.submit(edge)) clearForm() }
  catch { validation.value = '请检查建议字段：类型最多64、范围最多128个Unicode字符，不允许控制字符或不完整字符' }
}
function label(id: string) { return id === props.asset?.id ? props.asset.name : options.value.find(item => item.id === id)?.name || id }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('依赖保存结果尚未确认，请先使用原请求重试，再离开资产页面'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('资产依赖保存结果尚未确认，请先处理原请求再切换租户或退出'); return false })
onUnmounted(() => { unregisterContextGuard(); searchController?.abort(); ++searchGeneration; workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved })
</script>

<template>
  <el-drawer v-model="open" title="资产依赖草稿" size="900px" class="asset-dependency-drawer" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" title="仅保存人工提出的依赖草稿。Q20规则待确认，有效规则为空；不会启用监控抑制或推断真实影响范围。" />
    <p v-if="asset"><strong>{{ asset.name }}</strong><span class="dependency-id">{{ asset.id }}</span></p>
    <el-empty v-if="!permitted" description="当前资产不在有效租户上下文中，请重新打开资产详情" />
    <template v-else>
      <div class="dependency-actions"><el-button :disabled="state.busy === 'write'" :loading="state.busy === 'read'" @click="workspace.refresh">刷新完整租户图</el-button><span v-if="state.draft">图版本 v{{ state.draft.version }} · 当前设备相关 {{ visibleEdges.length }} 条 · 租户草稿共 {{ state.draft.proposed_edges.length }} 条</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存结果尚未确认。原版本、内容和幂等键已保留，刷新只读取状态，不会自动重发。请勿刷新浏览器或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="workspace.retry">使用原请求重试</el-button>
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`已收到草稿v${state.receipt.version}的持久化回执；监控仍未应用${state.draft ? '' : '，当前图回读尚未成功，请只读刷新'}`" />
      <el-skeleton v-if="state.busy === 'read'" :rows="3" animated />
      <template v-if="state.draft">
        <p v-if="state.draft.version === 0" class="field-help">当前租户尚未保存依赖草稿；不代表真实设备之间没有依赖。</p>
        <p class="field-help">上游 → 下游。这里只展示当前设备相关边，保存使用完整租户图的版本。其他设备的修改也可能导致版本冲突；刷新后再明确提交。</p>
        <AppTable :rows="visibleEdges" :loading="false" empty-text="当前设备没有已登记的草稿边">
          <el-table-column label="上游 → 下游" min-width="240"><template #default="{ row }"><span class="dependency-node">{{ label(row.parent_id) }}</span> → <span class="dependency-node">{{ label(row.child_id) }}</span></template></el-table-column>
          <el-table-column label="建议类型" min-width="110"><template #default="{ row }">{{ row.proposed_type ?? '待确认' }}</template></el-table-column>
          <el-table-column label="建议抑制范围" min-width="140"><template #default="{ row }">{{ row.proposed_suppression_scope ?? '待确认' }}</template></el-table-column>
          <el-table-column label="建议启用（未生效）" min-width="150"><template #default="{ row }">{{ row.proposed_enabled === null ? '待确认' : row.proposed_enabled ? '建议是' : '建议否' }}</template></el-table-column>
        </AppTable>
      </template>
      <section v-if="writable" class="dependency-form">
        <h3>追加一条依赖草稿</h3>
        <p class="field-help">现有边和历史修订不会被覆盖；禁止自指、跨租户和有向环。当前只支持追加，不提供删除或发布。</p>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="查找另一台设备"><div class="dependency-search"><el-input v-model="query" aria-label="依赖设备搜索" :disabled="locked || optionsLoading" placeholder="设备名称或管理IP" @keydown.enter.prevent="searchAssets" /><el-button :disabled="locked" :loading="optionsLoading" @click="searchAssets">查询设备</el-button></div></el-form-item>
          <ApiErrorNotice :error="optionsError" />
          <p v-if="moreOptions" class="field-help">仅显示前100个匹配结果，请细化搜索；不会把这份列表当作完整租户设备集。</p>
          <el-form-item label="选择另一台设备"><el-select v-model="selected" aria-label="依赖的另一台设备" :disabled="locked || optionsLoading" filterable placeholder="先查询，再选择"><el-option v-for="item in options" :key="item.id" :value="item.id" :label="`${item.name} · ${item.id}`" /></el-select></el-form-item>
          <el-form-item label="依赖方向"><el-select v-model="direction" placeholder="请明确选择方向" aria-label="依赖方向" :disabled="locked"><el-option label="所选设备是当前设备的上游" value="upstream" /><el-option label="所选设备是当前设备的下游" value="downstream" /></el-select></el-form-item>
          <el-form-item label="建议类型（可留空，最多64字符）"><el-input v-model="proposedType" :disabled="locked" aria-label="建议依赖类型" /></el-form-item>
          <el-form-item label="建议抑制范围（可留空，最多128字符）"><el-input v-model="proposedScope" :disabled="locked" aria-label="建议抑制范围" /></el-form-item>
          <el-form-item label="建议启用（仅为提案，不执行）"><el-select v-model="proposedEnabled" :disabled="locked" aria-label="建议启用"><el-option label="未确认，保存null" value="unknown" /><el-option label="建议是，仍不生效" value="true" /><el-option label="建议否，仍不生效" value="false" /></el-select></el-form-item>
          <p v-if="validation" role="alert" class="dependency-validation">{{ validation }}</p>
          <el-button type="primary" native-type="submit" :disabled="locked || optionsLoading" :loading="state.busy === 'write'">保存依赖草稿</el-button>
        </el-form>
      </section>
      <p v-else class="field-help">当前身份只读，不能保存依赖草稿。</p>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起不会取消已发送的请求；未确认请求保留在当前页面。</span></template>
  </el-drawer>
</template>

<style>
.asset-dependency-drawer{max-width:100vw}.asset-dependency-drawer .el-alert{margin-bottom:12px}.dependency-id,.dependency-node{overflow-wrap:anywhere}.dependency-id{display:block;color:var(--el-text-color-secondary);font-size:12px}.dependency-actions,.dependency-search{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.dependency-search{width:100%}.dependency-search .el-input{flex:1;min-width:180px}.dependency-actions{margin:16px 0}.dependency-form{border-top:1px solid var(--el-border-color);margin-top:24px;padding-top:12px}.dependency-form .el-select{width:100%}.dependency-validation{color:var(--el-color-danger)}
@media(max-width:600px){.asset-dependency-drawer .el-drawer__body{padding:14px}.asset-dependency-drawer .el-button{min-height:44px;height:auto;white-space:normal}.dependency-actions>span{width:100%}.dependency-search .el-input{min-width:0;width:100%;flex-basis:100%}.asset-dependency-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}}
</style>
