<script setup lang="ts">
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElCollapse, ElCollapseItem } from 'element-plus'
import { api } from '../api/client'
import type { MetadataCurrent } from '../utils/assetMetadataWorkspace'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { typeLabel, assetTypes } from '../utils/display'
import { createMetadataQuery, emptyMetadataQueryState, metadataQueryFields, type MetadataFilters, type MetadataQueryField, type MetadataQueryState } from '../utils/assetMetadataQuery'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import AppTable from './AppTable.vue'
const ElTableColumn = ElementTableColumn<MetadataCurrent>
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [asset: Asset] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
let disposed=false,focusEpoch=0
const resultHeading=ref<HTMLElement>(),readError=ref<HTMLElement>()
const state = shallowRef<MetadataQueryState>(emptyMetadataQueryState())
const workspace = createMetadataQuery(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('assets:write') && !switchingTenant.value))
const labels: Record<MetadataQueryField, string> = { name: '设备名称', type: '设备类型', site_id: '站点ID', brand: '品牌', model: '型号', serial_number: '序列号', location: '位置', owner_id: '负责人ID', business_description: '所属业务说明', purchase_date: '采购日期', warranty_expiry: '维保到期日', overdue_service_date: '超期服役日期', collection_method_note: '采集方式说明', note: '维护备注' }
const values = reactive(Object.fromEntries(metadataQueryFields.map(key => [key, ''])) as Record<MetadataQueryField, string>)
const modes = reactive(Object.fromEntries(metadataQueryFields.map(key => [key, 'any'])) as Record<MetadataQueryField, 'any' | 'value' | 'unknown'>)
const expanded = ref<string[]>([])
function clearForm() { metadataQueryFields.forEach(key => { values[key] = ''; modes[key] = 'any' }) }
function exactField(key: MetadataQueryField) { return ['type', 'site_id', 'owner_id', 'purchase_date', 'warranty_expiry', 'overdue_service_date'].includes(key) }
function filters(): MetadataFilters { const result: MetadataFilters = {}; const unknown: string[] = []; metadataQueryFields.forEach(key => { if (modes[key] === 'value') result[key] = values[key]; if (modes[key] === 'unknown') unknown.push(key) }); if (unknown.length) result.unknown_fields = unknown.join(','); return result }
function apply() { return withReadFocus(() => workspace.apply(filters())) }
function resetFilters() { if(state.value.busy)return;clearForm();return withReadFocus(() => workspace.apply({})) }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, session.value?.mode, session.value?.capabilities, permitted.value]))
watch(contextKey, () => { focusEpoch++; clearForm(); const s = session.value; workspace.reset(permitted.value && s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null); if (open.value && permitted.value) void refresh() }, { immediate: true, flush: 'sync' })
watch(open, value => { if (!value) {focusEpoch++;workspace.cancelRead()} else if (permitted.value && !state.value.busy && !state.value.page && !state.value.readTarget) void refresh() }, {flush:'sync'})
async function withReadFocus(action:()=>Promise<boolean>){
  if(disposed||!open.value||!permitted.value||state.value.busy)return false
  const turn=++focusEpoch,owner=contextKey.value,active=document.activeElement
  const ok=await action();await nextTick()
  if(disposed||turn!==focusEpoch||owner!==contextKey.value||!open.value||!permitted.value)return false
  if(document.activeElement===active||document.activeElement===document.body){if(state.value.error)readError.value?.focus();else if(ok)resultHeading.value?.focus()}
  return ok
}
function refresh(){return withReadFocus(workspace.refresh)}
const nextPage=()=>withReadFocus(workspace.next),retryRead=()=>withReadFocus(workspace.retryRead)
const readMessage=computed(()=>state.value.busy?'正在检索资料，可以取消。':state.value.cancelledRead?'资料检索已取消，筛选输入保留。':state.value.error?'检索未完成，请核对错误与恢复方式。':state.value.page?`已加载${state.value.paged?'后续页':'第1页'}，本页${state.value.page.items.length}条。`:'尚未读取资料检索结果。')
function select(row:MetadataCurrent){if(disposed||!open.value||!permitted.value||state.value.busy||!state.value.page?.items.some(item=>JSON.stringify(item)===JSON.stringify(row)))return false;emit('select',JSON.parse(JSON.stringify(row.asset)) as Asset);return true}
onUnmounted(()=>{disposed=true;focusEpoch++;workspace.dispose()})
</script>

<template>
  <el-drawer v-model="open" title="资产扩展资料检索" size="1100px" class="metadata-query-drawer">
    <el-empty v-if="!permitted" description="当前账号无资产扩展资料检索权限" />
    <template v-else>
      <el-collapse v-model="expanded"><el-collapse-item name="filters" title="展开配置 14 项扩展字段筛选条件"><el-form label-position="top" @submit.prevent="apply"><div class="metadata-query-filters"><el-form-item v-for="key in metadataQueryFields" :key="key" :label="labels[key]"><el-select v-model="modes[key]" :aria-label="`${labels[key]}筛选方式`"><el-option value="any" label="不限" /><el-option value="value" :label="exactField(key) ? '精确匹配' : '包含匹配'" /><el-option v-if="!['name', 'type', 'site_id'].includes(key)" value="unknown" label="未填写（空值）" /></el-select><template v-if="modes[key] === 'value'"><el-select v-if="key === 'type'" v-model="values[key]" aria-label="设备类型筛选值" placeholder="请选择设备类型"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select><el-input v-else v-model="values[key]" :type="key === 'note' ? 'textarea' : 'text'" :aria-label="`${labels[key]}筛选值`" :placeholder="key.includes('date') || key === 'warranty_expiry' ? 'YYYY-MM-DD' : key === 'owner_id' ? '请输入完整负责人 UUID' : '输入包含的关键字'" /></template></el-form-item></div><div class="metadata-query-actions"><el-button type="primary" native-type="submit" :loading="state.busy">查询匹配资料</el-button><el-button :disabled="state.busy" @click="resetFilters">清空条件</el-button></div></el-form></el-collapse-item></el-collapse>
      <div class="metadata-query-actions"><el-button :disabled="state.busy" @click="refresh">刷新到第 1 页</el-button><el-button :disabled="state.busy || !state.page?.has_more" @click="nextPage">下一页</el-button><span class="tabular-nums">{{ state.paged ? '后续页' : '第 1 页' }} · 每页 15 条</span></div>
      
      <p class="metadata-query-applied">已应用条件：<span v-if="!Object.keys(state.filters).length" class="applied-chip">不限（当前租户全部手工台账）</span><span v-for="(value, key) in state.filters" :key="key" class="applied-chip">{{ key === 'unknown_fields' ? '未填写字段' : labels[key as MetadataQueryField] }}＝{{ value }}</span></p>
      <p ref="resultHeading" tabindex="-1" role="status">{{readMessage}}</p>
      <ReadRecoveryActions :busy="state.busy?'read':null" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="资料检索" :cancel="workspace.cancelRead" :retry="retryRead" />
      <p v-if="state.readTarget?.boundary" class="field-help">原页边界：{{state.readTarget.boundary.time}} / {{state.readTarget.boundary.id}}。重试仍使用此边界与已应用条件；不采用尚未提交的新筛选。</p>
      <div v-if="state.error" ref="readError" tabindex="-1" role="group" aria-label="资料检索错误"><ApiErrorNotice :error="state.error" /><p v-if="state.error instanceof Error && !('traceId' in state.error)" role="alert" class="metadata-query-validation">{{ state.error.message }}</p></div>
      <AppTable :rows="state.page?.items || []" :loading="state.busy" :empty-text="state.page ? '当前筛选条件下暂无匹配资料' : '暂未加载扩展资料检索结果'">
        <el-table-column type="expand"><template #default="{ row }"><dl class="metadata-query-details"><template v-for="key in metadataQueryFields.filter(k => !['name', 'type', 'site_id'].includes(k))" :key="key"><dt>{{ labels[key] }}</dt><dd>{{ row.metadata?.[key] ?? '未填写' }}</dd></template></dl><p class="field-help tabular-nums">资料版本 {{ row.metadata_version ? 'v' + row.metadata_version : '未登记' }} · 来源：人工维护台账</p></template></el-table-column>
        <el-table-column label="名称 / 管理IP" min-width="230"><template #default="{ row }"><strong>{{ row.asset.name }}</strong><span class="metadata-id mono">{{ row.asset.management_ip }}</span></template></el-table-column>
        <el-table-column label="类型 / 站点" min-width="180"><template #default="{ row }">{{ typeLabel(row.asset.type) }}<span class="metadata-id">{{ row.asset.site_id }}</span></template></el-table-column>
        <el-table-column label="品牌 / 型号" min-width="170"><template #default="{ row }">{{ row.metadata?.brand ?? '未填写' }}<span class="metadata-id">{{ row.metadata?.model ?? '未填写' }}</span></template></el-table-column>
        <el-table-column label="维保到期日" min-width="160"><template #default="{ row }"><span class="tabular-nums">{{ row.metadata?.warranty_expiry ?? '未填写' }}</span></template></el-table-column>
        <el-table-column label="资料状态 / 版本" min-width="150"><template #default="{ row }">{{ row.metadata ? '人工维护 · 已登记' : '待补充资料' }}<span class="metadata-id tabular-nums">资产 v{{ row.asset.version }} · {{ row.can_edit ? '可维护草稿' : '维护受限' }}</span></template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="state.busy" @click="select(row)">资料与历史</el-button></template></el-table-column>
      </AppTable>
    </template>
    <template #footer><el-button @click="open = false">收起查询</el-button></template>
  </el-drawer>
</template>

<style>
.metadata-query-drawer{max-width:100vw}.tabular-nums{font-variant-numeric:tabular-nums}.metadata-query-drawer .el-alert{margin-bottom:14px}.metadata-query-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}.metadata-query-filters .el-select{width:100%;margin-bottom:6px}.metadata-query-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:16px 0}.metadata-query-applied{overflow-wrap:anywhere;white-space:pre-wrap;display:flex;align-items:center;flex-wrap:wrap;gap:6px;font-size:13px}.applied-chip{display:inline-block;padding:2px 8px;background:#eef4fb;border:1px solid #dbe7f5;border-radius:4px;font-size:12px;color:#2b4c7e}.metadata-query-details{display:grid;grid-template-columns:160px minmax(0,1fr);gap:8px 12px;margin:12px;padding:12px;background:#f8fafc;border-radius:6px}.metadata-query-details dt{color:var(--el-text-color-secondary)}.metadata-query-details dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.metadata-query-validation{color:var(--el-color-danger)}.metadata-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}
@media(max-width:700px){.metadata-query-drawer .el-drawer__body{padding:14px}.metadata-query-filters{grid-template-columns:1fr}.metadata-query-drawer .el-button{min-height:44px;height:auto;white-space:normal}.metadata-query-filters .el-input__wrapper,.metadata-query-filters .el-select__wrapper{min-height:44px}.metadata-query-details{grid-template-columns:1fr;gap:4px}.metadata-query-details dd{margin-bottom:10px}}
</style>
