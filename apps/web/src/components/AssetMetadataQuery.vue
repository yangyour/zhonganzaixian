<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElCollapse, ElCollapseItem } from 'element-plus'
import { api } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { typeLabel, assetTypes } from '../utils/display'
import { createMetadataQuery, emptyMetadataQueryState, metadataQueryFields, type MetadataFilters, type MetadataQueryField, type MetadataQueryState } from '../utils/assetMetadataQuery'
import ApiErrorNotice from './ApiErrorNotice.vue'
import AppTable from './AppTable.vue'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [asset: Asset] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
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
function apply() { void workspace.apply(filters()) }
function resetFilters() { clearForm(); void workspace.apply({}) }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => { clearForm(); const s = session.value; workspace.reset(permitted.value && s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true })
watch(open, value => { if (value && permitted.value && !state.value.busy) void workspace.refresh() })
onUnmounted(workspace.dispose)
</script>

<template>
  <el-drawer v-model="open" title="手工资料筛选（内部附加视图）" size="1100px" class="metadata-query-drawer">
    <el-alert type="info" :closable="false" title="仅当前租户手工来源资料。所有已选条件按“同时满足”查询；未知与空文本不同，不推断维保状态，不改变主台账固定列。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有内部资料查询权限" />
    <template v-else>
      <el-collapse v-model="expanded"><el-collapse-item name="filters" title="设置14个维护字段筛选条件"><el-form label-position="top" @submit.prevent="apply"><div class="metadata-query-filters"><el-form-item v-for="key in metadataQueryFields" :key="key" :label="labels[key]"><el-select v-model="modes[key]" :aria-label="`${labels[key]}筛选方式`"><el-option value="any" label="不限（不添加条件）" /><el-option value="value" :label="exactField(key) ? '精确值' : '包含文字（字面匹配）'" /><el-option v-if="!['name', 'type', 'site_id'].includes(key)" value="unknown" label="明确未知 / null" /></el-select><template v-if="modes[key] === 'value'"><el-select v-if="key === 'type'" v-model="values[key]" aria-label="设备类型筛选值" placeholder="明确选择类型"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select><el-input v-else v-model="values[key]" :type="key === 'note' ? 'textarea' : 'text'" :aria-label="`${labels[key]}筛选值`" :placeholder="key.includes('date') || key === 'warranty_expiry' ? 'YYYY-MM-DD，不推断逾期' : key === 'owner_id' ? '完整负责人UUID' : '原样匹配，百分号/下划线不是通配符'" /></template></el-form-item></div><div class="metadata-query-actions"><el-button type="primary" native-type="submit" :loading="state.busy">应用条件并读取第一页</el-button><el-button @click="resetFilters">清除条件</el-button></div></el-form></el-collapse-item></el-collapse>
      <div class="metadata-query-actions"><el-button :loading="state.busy" @click="workspace.refresh()">刷新已应用条件（只读）</el-button><el-button :disabled="state.busy || !state.page?.has_more" @click="workspace.next()">下一页</el-button><span>{{ state.paged ? '后续页' : '第一页' }} · 每页15条</span></div>
      <p class="field-help">每次查询是当时的只读快照。新增或修改可能改变后续匹配，刷新从第一页重读；多页不是同一冻结导出快照。</p>
      <p class="metadata-query-applied">已应用条件：<span v-if="!Object.keys(state.filters).length">不限（仍仅本租户手工来源）</span><span v-for="(value, key) in state.filters" :key="key">{{ key === 'unknown_fields' ? '明确未知字段' : labels[key as MetadataQueryField] }}＝{{ value }}； </span></p>
      <ApiErrorNotice :error="state.error" />
      <p v-if="state.error instanceof Error && !('traceId' in state.error)" role="alert" class="metadata-query-validation">{{ state.error.message }}</p>
      <AppTable :rows="state.page?.items || []" :loading="state.busy" :empty-text="state.page ? '当前条件没有匹配资料' : '查询尚未成功，不能视为无匹配'">
        <el-table-column type="expand"><template #default="{ row }"><dl class="metadata-query-details"><template v-for="key in metadataQueryFields.filter(k => !['name', 'type', 'site_id'].includes(k))" :key="key"><dt>{{ labels[key] }}</dt><dd>{{ row.metadata?.[key] ?? '未填写 / 未知' }}</dd></template></dl><p class="field-help">资料版本 {{ row.metadata_version ?? '尚无修订' }}；人工填报未独立核验，监控/CMDB未应用</p></template></el-table-column>
        <el-table-column label="名称 / 管理IP" min-width="230"><template #default="{ row }"><strong>{{ row.asset.name }}</strong><span class="metadata-id">{{ row.asset.management_ip }}</span></template></el-table-column>
        <el-table-column label="类型 / 站点" min-width="180"><template #default="{ row }">{{ typeLabel(row.asset.type) }}<span class="metadata-id">{{ row.asset.site_id }}</span></template></el-table-column>
        <el-table-column label="品牌 / 型号" min-width="170"><template #default="{ row }">{{ row.metadata?.brand ?? '未知' }}<span class="metadata-id">{{ row.metadata?.model ?? '未知' }}</span></template></el-table-column>
        <el-table-column label="维保到期日（实填）" min-width="160"><template #default="{ row }">{{ row.metadata?.warranty_expiry ?? '未知' }}</template></el-table-column>
        <el-table-column label="资料 / 版本" min-width="150"><template #default="{ row }">{{ row.metadata ? '人工填报 · 未核验' : '尚无资料' }}<span class="metadata-id">资产 v{{ row.asset.version }} · {{ row.can_edit ? '可维护草稿' : '维护受限' }}</span></template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="emit('select', row.asset)">资料与历史</el-button></template></el-table-column>
      </AppTable>
    </template>
    <template #footer><el-button @click="open = false">收起查询</el-button></template>
  </el-drawer>
</template>

<style>
.metadata-query-drawer{max-width:100vw}.metadata-query-drawer .el-alert{margin-bottom:14px}.metadata-query-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}.metadata-query-filters .el-select{width:100%;margin-bottom:6px}.metadata-query-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:16px 0}.metadata-query-applied{overflow-wrap:anywhere;white-space:pre-wrap}.metadata-query-details{display:grid;grid-template-columns:160px minmax(0,1fr);gap:8px 12px;margin:12px}.metadata-query-details dt{color:var(--el-text-color-secondary)}.metadata-query-details dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.metadata-query-validation{color:var(--el-color-danger)}
@media(max-width:700px){.metadata-query-drawer .el-drawer__body{padding:14px}.metadata-query-filters{grid-template-columns:1fr}.metadata-query-drawer .el-button{min-height:44px;height:auto;white-space:normal}.metadata-query-filters .el-input__wrapper,.metadata-query-filters .el-select__wrapper{min-height:44px}.metadata-query-details{grid-template-columns:1fr;gap:4px}.metadata-query-details dd{margin-bottom:10px}}
</style>
