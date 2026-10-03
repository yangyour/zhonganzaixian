<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, onUnmounted, shallowRef, watch } from 'vue'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { displayTime } from '../utils/display'
import { revisionStatusLabel } from '../utils/configurationRevision'
import { configurationDiagnosticReasonLabel, configurationDiagnosticSourceLabel, createConfigurationDiagnosticReader, diagnosticLabel, diagnosticQuestions, type ConfigurationDiagnosticReadState } from '../utils/configurationDiagnostic'
import AppTable from './AppTable.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = defineProps<{ modelValue: boolean; configurationKey: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const permitted = computed(() => session.value?.user.role === 'padmin' && can('configurations:read') && !switchingTenant.value)
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version, can('configurations:read'), switchingTenant.value]))
const state = shallowRef<ConfigurationDiagnosticReadState>({ status: 'idle' })
const reader = createConfigurationDiagnosticReader({
  context: () => ({ tenantId: session.value?.tenant.id || '', key: contextKey.value, permitted: permitted.value }),
  onChange: value => { state.value = value },
})
const diagnostic = computed(() => state.value.status === 'ready' ? state.value.diagnostic : null)
const loading = computed(() => state.value.status === 'loading')
const error = computed(() => state.value.status === 'error' ? state.value.error : null)
function load() { if (props.modelValue && props.configurationKey && permitted.value) return reader.load(props.configurationKey) }
watch(() => [props.modelValue, props.configurationKey], () => {
  reader.reset()
  if (props.modelValue && !permitted.value) { open.value = false; return }
  void load()
}, { immediate: true, flush: 'sync' })
watch(contextKey, () => { reader.reset(); open.value = false }, { flush: 'sync' })
onUnmounted(() => reader.reset())
</script>

<template>
  <el-drawer v-model="open" title="配置有效性诊断" size="760px" class="configuration-diagnostic-drawer" destroy-on-close>
    <p class="configuration-diagnostic-key mono">{{ configurationKey }}</p>
    <p class="field-help">当前租户：{{ session?.tenant.name }}。本次只查询登记与提案的元数据，运行配置和客户核验尚待接入。</p>
    <ApiErrorNotice :error="error"><el-button size="small" :disabled="loading" @click="load">重新查询诊断</el-button></ApiErrorNotice>
    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else-if="diagnostic">
      <el-alert type="warning" :closable="false" show-icon title="尚未生效 · CONFIG_PENDING。内部审批通过仍未发布为运行配置。" />
      <el-descriptions :column="1" border class="configuration-diagnostic-facts">
        <el-descriptions-item label="运行生效值">未设 · null</el-descriptions-item>
        <el-descriptions-item label="诊断租户"><span class="mono">{{ diagnostic.context.tenant_id }}</span></el-descriptions-item>
        <el-descriptions-item label="来源读取时间">{{ displayTime(diagnostic.observed_at) }}</el-descriptions-item>
        <el-descriptions-item label="关联Q编号">{{ diagnosticQuestions(diagnostic.question_ids) }}</el-descriptions-item>
      </el-descriptions>
      <div class="detail-section">
        <h3>设计登记目录</h3>
        <p class="field-help">登记版本与描述来自设计目录，运行定义及解析作用域仍未知。</p>
        <el-descriptions :column="1" border class="configuration-diagnostic-facts">
          <el-descriptions-item label="来源">设计登记 · DESIGN_REGISTRY</el-descriptions-item>
          <el-descriptions-item label="登记版本">v{{ diagnostic.registry.version }}</el-descriptions-item>
          <el-descriptions-item label="登记类型">{{ diagnosticLabel(diagnostic.registry.type_label) }}</el-descriptions-item>
          <el-descriptions-item label="登记单位">{{ diagnosticLabel(diagnostic.registry.unit_label) }}</el-descriptions-item>
          <el-descriptions-item label="登记作用域">{{ diagnosticLabel(diagnostic.registry.scope_label) }}</el-descriptions-item>
          <el-descriptions-item label="原始来源标签">{{ diagnosticLabel(diagnostic.registry.source_ref) }}</el-descriptions-item>
          <el-descriptions-item label="原确认状态标签">{{ diagnosticLabel(diagnostic.registry.decision_status_label) }}</el-descriptions-item>
          <el-descriptions-item label="原分类标签">{{ diagnosticLabel(diagnostic.registry.classification_label) }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <div class="detail-section">
        <h3>运行与客户核验来源</h3>
        <el-descriptions :column="1" border class="configuration-diagnostic-facts">
          <el-descriptions-item label="运行定义">尚未接入 · NOT_IMPLEMENTED<br>定义版本、允许层级、优先次序和已解析作用域均未提供。</el-descriptions-item>
          <el-descriptions-item label="发布来源">尚未接入 · NOT_IMPLEMENTED<br>发布记录、修订引用、发布时间及发布授权/生产许可核验均未知。</el-descriptions-item>
          <el-descriptions-item label="客户核验">尚未接入 · NOT_IMPLEMENTED<br>本配置是否必须核验、客户结论、核验资格与真实性均未知。</el-descriptions-item>
        </el-descriptions>
      </div>
      <div class="detail-section">
        <h3>未生效原因</h3>
        <ul class="configuration-diagnostic-reasons">
          <li v-for="(reason, index) in diagnostic.reasons" :key="`${reason.code}:${reason.source_id}:${index}`">
            <strong>{{ configurationDiagnosticReasonLabel[reason.code] }}</strong>
            <span class="mono">{{ reason.code }}</span>
            <p>{{ configurationDiagnosticSourceLabel[reason.source_kind] }} · {{ reason.source_id === null ? '来源标识未提供' : reason.source_id }} · {{ reason.source_version === null ? '版本未提供' : `v${reason.source_version}` }}</p>
          </li>
        </ul>
      </div>
      <div class="detail-section">
        <h3>当前租户近期提案</h3>
        <p class="field-help">仅展示最近最多5项内部提案记录，每项仍未发布或生效。证据引用属于输入记录，真实性和资格尚未核验。</p>
        <p v-if="diagnostic.recent_proposals.truncated" class="field-help">仅列最近5项；还有未显示的提案记录。</p>
        <p v-if="!diagnostic.recent_proposals.items.length" class="muted">本次回读没有近期提案。</p>
        <AppTable v-else :rows="diagnostic.recent_proposals.items" row-key="id">
          <el-table-column label="内部提案 / 版本" min-width="230"><template #default="{ row }"><span class="mono">{{ row.id }}</span><span class="cell-sub">v{{ row.version }} · INTERNAL_REVISION</span></template></el-table-column>
          <el-table-column label="内部流程" min-width="200"><template #default="{ row }">{{ revisionStatusLabel[row.workflow_status as keyof typeof revisionStatusLabel] }}<span class="cell-sub">尚未发布 · 未生效</span></template></el-table-column>
          <el-table-column label="证据输入" min-width="190"><template #default="{ row }">{{ row.evidence_reference_count === null ? '引用数量未知' : `${row.evidence_reference_count} 项引用` }}<span class="cell-sub">仅引用输入 · REFERENCE_ONLY</span><span class="cell-sub">客户未核验 · UNVERIFIED</span></template></el-table-column>
        </AppTable>
      </div>
    </template>
    <template #footer><el-button :loading="loading" :disabled="!permitted" @click="load">刷新诊断</el-button><el-button @click="open = false">关闭</el-button></template>
  </el-drawer>
</template>

<style>
.configuration-diagnostic-drawer{max-width:100vw}.configuration-diagnostic-key{font-size:12px;overflow-wrap:anywhere;margin:0 0 12px}.configuration-diagnostic-facts{margin-top:18px}.configuration-diagnostic-facts .el-descriptions__label{width:140px}.configuration-diagnostic-facts .el-descriptions__content{white-space:pre-wrap;overflow-wrap:anywhere}.configuration-diagnostic-reasons{list-style:none;padding:0;margin:0}.configuration-diagnostic-reasons li{margin-top:12px;padding:12px;border:1px solid #e4eaf2;border-radius:5px;overflow-wrap:anywhere;font-size:12px}.configuration-diagnostic-reasons strong,.configuration-diagnostic-reasons span{display:block}.configuration-diagnostic-reasons span{margin-top:6px;color:#61718a}.configuration-diagnostic-reasons p{margin:6px 0 0}
@media(max-width:760px){.configuration-diagnostic-drawer .el-drawer__footer{display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end}.configuration-diagnostic-drawer .el-drawer__footer .el-button{margin-left:0;min-height:44px}.configuration-diagnostic-facts .el-descriptions__label{width:105px}}
</style>
