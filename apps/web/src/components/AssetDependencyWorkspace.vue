<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, nextTick, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api, ApiError, isCancelled } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createDependencyWorkspace, emptyDependencyState, type DependencyState, type ProposedDependency } from '../utils/assetDependencyWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import { readAssetList } from '../utils/assetReadback'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import AppTable from './AppTable.vue'

const props = defineProps<{ modelValue: boolean; asset: Asset | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
let disposed = false, focusEpoch = 0, submittedSnapshot: string | null = null
const readError = ref<HTMLElement>(), graphHeading = ref<HTMLElement>(), searchError = ref<HTMLElement>(), searchNotice = ref<HTMLElement>()
const state = shallowRef<DependencyState>(emptyDependencyState())
const workspace = createDependencyWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.asset?.tenant_id === session.value.tenant.id && can('assets:read') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('assets:write'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !writable.value || !state.value.draft || Boolean(state.value.busy) || unresolved.value)
const visibleEdges = computed(() => state.value.draft?.proposed_edges.filter(edge => edge.parent_id === props.asset?.id || edge.child_id === props.asset?.id) || [])
const query = ref(''), options = ref<Asset[]>([]), optionsLoading = ref(false), optionsError = ref<unknown>(null), moreOptions = ref(false)
const searchAttempt = ref<string | null>(null), searchCancelled = ref(false), searchCompleted = ref(false)
const selected = ref(''), direction = ref<'' | 'upstream' | 'downstream'>(''), proposedType = ref(''), proposedScope = ref(''), proposedEnabled = ref('unknown'), validation = ref('')
let searchController: AbortController | undefined, searchGeneration = 0
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, session.value?.mode, session.value?.capabilities, permitted.value, writable.value, props.asset?.tenant_id, props.asset?.id]))
function clearForm() { selected.value = ''; direction.value = ''; proposedType.value = ''; proposedScope.value = ''; proposedEnabled.value = 'unknown'; validation.value = '' }
watch(contextKey, () => {
  focusEpoch++; submittedSnapshot = null; searchAttempt.value = null; searchCancelled.value = false; searchCompleted.value = false; searchController?.abort(); ++searchGeneration; options.value = []; optionsLoading.value = false; optionsError.value = null; moreOptions.value = false; query.value = ''; clearForm()
  const current = session.value
  workspace.reset(permitted.value && current && props.asset ? { tenantId: current.tenant.id, assetId: props.asset.id, actorId: current.user.id, permissionVersion: current.permission_version, canWrite: writable.value } : null)
  if (open.value && permitted.value) void loadGraph()
}, { immediate: true, flush: 'sync' })
watch(open, value => { if (value && permitted.value && !state.value.busy) void loadGraph() })
async function withGraphFocus(action: () => Promise<boolean>) {
  if (disposed || state.value.busy) return false
  const turn = ++focusEpoch, owner = contextKey.value, active = document.activeElement
  const ok = await action(); await nextTick()
  if (disposed || turn !== focusEpoch || owner !== contextKey.value || !open.value) return false
  if (document.activeElement === active || document.activeElement === document.body) { if (state.value.error) readError.value?.focus(); else if (ok) graphHeading.value?.focus() }
  return ok
}
function loadGraph() { return withGraphFocus(() => workspace.refresh()) }
const retryGraph = () => withGraphFocus(workspace.retryRead)
const graphMessage = computed(() => state.value.busy === 'read' ? '正在读取原资产拓扑草稿，可以取消。' : state.value.cancelledRead ? '拓扑读取已取消，未保存输入与原保存请求保留。' : state.value.error ? '读取或保存未完成，请查看错误与恢复方式。' : state.value.draft ? `已读取完整租户草稿 v${state.value.draft.version}，当前设备关联 ${visibleEdges.value.length} 条。` : '尚未读取拓扑草稿。')
const canRetrySearch = computed(() => !disposed && writable.value && !optionsLoading.value && !state.value.busy && !unresolved.value && searchAttempt.value !== null && Boolean(optionsError.value || searchCancelled.value))
async function searchOriginal(original: string) {
  if (disposed || !writable.value || state.value.busy || unresolved.value || optionsLoading.value) return false
  if (Array.from(original).length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(original)) { validation.value = '查询关键词最多100字符且不得包含控制字符'; return false }
  searchController?.abort(); const controller = new AbortController(); searchController = controller
  const generation = ++searchGeneration, context = contextKey.value, tenantId = session.value!.tenant.id, active = document.activeElement
  optionsLoading.value = true; optionsError.value = null; options.value = []; moreOptions.value = false; searchAttempt.value = original; searchCancelled.value = false; searchCompleted.value = false
  if (disposed || generation !== searchGeneration || context !== contextKey.value || controller.signal.aborted || !writable.value) return false
  let ok = false
  try {
    const raw = await api.request<unknown>(`/assets?${new URLSearchParams({ page_size: '100', q: original })}`, { signal: controller.signal })
    if (disposed || generation !== searchGeneration || context !== contextKey.value || controller.signal.aborted || !writable.value) return false
    const result = readAssetList(raw,tenantId,100,'remote_test')
    options.value = result.items.filter(item => item.id !== props.asset?.id); moreOptions.value = result.has_more; searchCompleted.value = true; ok = true
  } catch (error) { if (!disposed && generation === searchGeneration && context === contextKey.value) { if (isCancelled(error)) searchCancelled.value = true; else optionsError.value = error } }
  finally {
    if (!disposed && generation === searchGeneration && context === contextKey.value) {
      searchController = undefined; optionsLoading.value = false; await nextTick()
      if (!disposed && generation === searchGeneration && context === contextKey.value && open.value && (document.activeElement === active || document.activeElement === document.body)) { if (optionsError.value) searchError.value?.focus(); else if (ok) searchNotice.value?.focus() }
    }
  }
  return ok
}
function searchAssets() { return searchOriginal(query.value.trim()) }
function retrySearch() { return canRetrySearch.value ? searchOriginal(searchAttempt.value!) : Promise.resolve(false) }
function cancelSearch() {
  if (disposed || !optionsLoading.value) return false
  ++searchGeneration; searchController?.abort(); searchController = undefined; optionsLoading.value = false; options.value = []; moreOptions.value = false; optionsError.value = null; searchCancelled.value = true; searchCompleted.value = false; return true
}
function formSnapshot() { return JSON.stringify([query.value,selected.value,direction.value,proposedType.value,proposedScope.value,proposedEnabled.value]) }
function clearSubmitted(original: string | null) { if (original !== null && original === formSnapshot()) { clearForm(); submittedSnapshot = null } else validation.value = '原保存已确认，后来填写的输入已保留，请核对最新拓扑后再提交' }
async function retryOriginal() {
  if (disposed || !writable.value || state.value.busy || state.value.attempt?.state !== 'uncertain') return false
  const owner = contextKey.value, original = submittedSnapshot
  if (!await workspace.retry() || disposed || owner !== contextKey.value) return false
  clearSubmitted(original); return true
}
async function save() {
  if (locked.value || !props.asset) return
  if (!options.value.some(item => item.id === selected.value)) { validation.value = '请先查询并选择当前租户的另一台设备'; return }
  if (!['upstream', 'downstream'].includes(direction.value)) { validation.value = '请选择上下游依赖方向'; return }
  const edge: ProposedDependency = { parent_id: direction.value === 'upstream' ? selected.value : props.asset.id, child_id: direction.value === 'upstream' ? props.asset.id : selected.value, proposed_type: proposedType.value.trim() || null, proposed_suppression_scope: proposedScope.value.trim() || null, proposed_enabled: proposedEnabled.value === 'unknown' ? null : proposedEnabled.value === 'true' }
  validation.value = ''; const owner = contextKey.value, original = formSnapshot(); submittedSnapshot = original
  try { if (await workspace.submit(edge) && !disposed && owner === contextKey.value) clearSubmitted(original) }
  catch { if (!disposed && owner === contextKey.value) validation.value = '请检查输入内容：依赖类型不超过 64 字符、抑制范围不超过 128 字符，且不含控制字符' }
}
function label(id: string) { return id === props.asset?.id ? props.asset.name : options.value.find(item => item.id === id)?.name || id }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('依赖关系保存状态待确认，请先完成重试后再离开页面'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('资产依赖保存状态待确认，请先完成重试再切换租户或退出'); return false })
onUnmounted(() => { disposed = true; focusEpoch++; unregisterContextGuard(); searchController?.abort(); ++searchGeneration; workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved, cancelRead: workspace.cancelRead, retryRead: retryGraph })
</script>

<template>
  <el-drawer v-model="open" title="资产依赖关系草稿" size="900px" class="asset-dependency-drawer" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" title="维护当前设备的上下游拓扑依赖草稿；告警收敛抑制与影响传导策略将在配置项 Q20 启用后生效。" />
    <div v-if="asset" class="dependency-asset-banner"><strong>{{ asset.name }}</strong><span class="dependency-id mono">{{ asset.id }}</span></div>
    <el-empty v-if="!permitted" description="当前资产不在有效租户上下文中，请重新打开资产详情" />
    <template v-else>
      <div class="dependency-actions"><el-button :disabled="Boolean(state.busy)" @click="loadGraph">刷新拓扑草稿</el-button><span v-if="state.draft" class="dependency-stats tabular-nums">拓扑版本 v{{ state.draft.version }} · 当前设备关联 {{ visibleEdges.length }} 条 · 租户草稿共 {{ state.draft.proposed_edges.length }} 条</span></div>
      <p ref="graphHeading" tabindex="-1" role="status">{{ graphMessage }}</p>
      <ReadRecoveryActions :busy="state.busy" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="依赖拓扑" :cancel="workspace.cancelRead" :retry="retryGraph" />
      <div v-if="state.error" ref="readError" tabindex="-1" role="group" aria-label="依赖拓扑读取或保存结果"><ApiErrorNotice :error="state.error" /></div>
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存请求响应超时或中断：当前填写内容与版本已锁定，请点击“重试保存”继续处理，请勿刷新或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">重试保存</el-button>
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`依赖关系草稿 v${state.receipt.version} 已保存（待监控抑制策略启用后生效）${state.draft ? '' : ' · 正在同步完整拓扑图，请点击刷新'}`" />
      <el-skeleton v-if="state.busy === 'read'" :rows="3" animated />
      <template v-if="state.draft">
        <p v-if="state.draft.version === 0" class="field-help">当前租户暂未登记设备依赖关系草稿。</p>
        <p class="field-help">流向说明：上游设备 → 下游设备。下方展示与当前设备直接关联的依赖边，提交时基于租户完整拓扑图版本校验。</p>
        <AppTable :rows="visibleEdges" :loading="false" empty-text="当前设备暂无已登记的依赖关系草稿">
          <el-table-column label="上游 → 下游" min-width="240"><template #default="{ row }"><span class="dependency-node">{{ label(row.parent_id) }}</span> <span class="dependency-arrow">→</span> <span class="dependency-node">{{ label(row.child_id) }}</span></template></el-table-column>
          <el-table-column label="建议类型" min-width="110"><template #default="{ row }">{{ row.proposed_type ?? '待确认' }}</template></el-table-column>
          <el-table-column label="建议抑制范围" min-width="140"><template #default="{ row }">{{ row.proposed_suppression_scope ?? '待确认' }}</template></el-table-column>
          <el-table-column label="建议启用状态" min-width="150"><template #default="{ row }">{{ row.proposed_enabled === null ? '待确认' : row.proposed_enabled ? '建议启用' : '建议停用' }}</template></el-table-column>
        </AppTable>
      </template>
      <section v-if="writable" class="dependency-form">
        <h3>新增依赖关系草稿</h3>
        <p class="field-help">新增依赖边采用追加方式保存，系统自动校验并拦截自环、跨租户引用与有向环路。</p>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="查找关联设备"><div class="dependency-search"><el-input v-model="query" aria-label="依赖设备搜索" :disabled="locked || optionsLoading" placeholder="输入设备名称或管理 IP" @keydown.enter.prevent="searchAssets" /><el-button :disabled="locked" :loading="optionsLoading" @click="searchAssets">查询设备</el-button></div></el-form-item>
          <ReadRecoveryActions :busy="optionsLoading ? 'read' : null" :can-retry="canRetrySearch" :scope-key="contextKey" label="关联设备" :cancel="cancelSearch" :retry="retrySearch" />
          <div v-if="optionsError" ref="searchError" tabindex="-1" role="group" aria-label="关联设备查询结果"><ApiErrorNotice :error="optionsError" /></div>
          <p ref="searchNotice" tabindex="-1" role="status">{{ optionsLoading ? '正在查询关联设备。' : searchCancelled ? '关联设备查询已取消，原编辑输入保留。' : searchCompleted ? `原查询“${searchAttempt || '空关键词'}”返回 ${options.length} 台其他设备。` : '尚无已核对的候选设备结果。' }}</p>
          <p v-if="moreOptions" class="field-help">单次最多展示前 100 条匹配设备，请输入更精确的名称或管理 IP 缩小范围。</p>
          <el-form-item label="选择关联设备"><el-select v-model="selected" aria-label="依赖的另一台设备" :disabled="locked || optionsLoading" filterable placeholder="请先查询并选择关联设备"><el-option v-for="item in options" :key="item.id" :value="item.id" :label="`${item.name} · ${item.id}`" /></el-select></el-form-item>
          <el-form-item label="依赖方向"><el-select v-model="direction" placeholder="请选择上下游方向" aria-label="依赖方向" :disabled="locked"><el-option label="所选设备是当前设备的上游" value="upstream" /><el-option label="所选设备是当前设备的下游" value="downstream" /></el-select></el-form-item>
          <el-form-item label="建议依赖类型（选填，最多 64 字符）"><el-input v-model="proposedType" :disabled="locked" placeholder="如：uplink、power、ha-pair" aria-label="建议依赖类型" /></el-form-item>
          <el-form-item label="建议抑制范围（选填，最多 128 字符）"><el-input v-model="proposedScope" :disabled="locked" placeholder="如：downstream-reachability" aria-label="建议抑制范围" /></el-form-item>
          <el-form-item label="建议启用策略"><el-select v-model="proposedEnabled" :disabled="locked" aria-label="建议启用"><el-option label="暂不指定（待确认）" value="unknown" /><el-option label="建议启用（待策略生效）" value="true" /><el-option label="建议停用（待策略生效）" value="false" /></el-select></el-form-item>
          <p v-if="validation" role="alert" class="dependency-validation">{{ validation }}</p>
          <el-button type="primary" native-type="submit" :disabled="locked || optionsLoading" :loading="state.busy === 'write'">保存依赖草稿</el-button>
        </el-form>
      </section>
      <p v-else class="field-help">当前账号仅具查看权限，不可新增或修改依赖草稿。</p>
    </template>
    <template #footer><el-button @click="open = false">收起面板</el-button><span class="field-help">收起面板不会中断正在执行的请求；待确认状态将保留在当前会话中。</span></template>
  </el-drawer>
</template>

<style>
.asset-dependency-drawer{max-width:100vw}.asset-dependency-drawer .el-alert{margin-bottom:12px}.tabular-nums{font-variant-numeric:tabular-nums}.dependency-asset-banner{display:flex;align-items:baseline;justify-content:space-between;flex-wrap:wrap;gap:8px;padding:10px 14px;background:#f8fafc;border:1px solid #e4ebf3;border-radius:8px;margin-bottom:12px}.dependency-id,.dependency-node{overflow-wrap:anywhere}.dependency-id{color:var(--el-text-color-secondary);font-size:12px}.dependency-node{display:inline-block;padding:2px 8px;background:#f1f5f9;border-radius:4px;font-weight:500}.dependency-arrow{color:#64748b;margin:0 4px}.dependency-actions,.dependency-search{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.dependency-stats{font-size:13px;color:#475569;padding:4px 10px;background:#f8fafc;border-radius:6px}.dependency-search{width:100%}.dependency-search .el-input{flex:1;min-width:180px}.dependency-actions{margin:16px 0}.dependency-form{border-top:1px solid var(--el-border-color);margin-top:24px;padding-top:16px}.dependency-form .el-select{width:100%}.dependency-validation{color:var(--el-color-danger)}
@media(max-width:600px){.asset-dependency-drawer .el-drawer__body{padding:14px}.asset-dependency-drawer .el-button{min-height:44px;height:auto;white-space:normal}.dependency-actions>span{width:100%}.dependency-search .el-input{min-width:0;width:100%;flex-basis:100%}.asset-dependency-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}}
</style>
