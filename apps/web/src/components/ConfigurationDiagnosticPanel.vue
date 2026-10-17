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
  <el-drawer v-model="open" title="配置登记来源与提案诊断" size="760px" class="configuration-diagnostic-drawer" destroy-on-close>
    <p class="configuration-diagnostic-key mono">{{ configurationKey }}</p>
    <p class="field-help">当前租户：{{ session?.tenant.name }}。展示当前配置项的设计登记目录属性与近期修订提案记录。</p>
    <router-link :to="{path:'/runtime-configurations',query:{key:configurationKey}}">查看此配置的运行值、发布与回滚</router-link>
    <ApiErrorNotice :error="error"><el-button size="small" :disabled="loading" @click="load">重新查询诊断</el-button></ApiErrorNotice>
    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else-if="diagnostic">
      <el-alert type="warning" :closable="false" show-icon title="配置基线登记与运行发布独立存储；当前生效状态请前往运行配置中心核对。" />
      <el-descriptions :column="1" border class="configuration-diagnostic-facts">
        <el-descriptions-item label="来源用途">设计基线登记诊断（不代表当前运行生效值）</el-descriptions-item>
        <el-descriptions-item label="诊断租户"><span class="mono">{{ diagnostic.context.tenant_id }}</span></el-descriptions-item>
        <el-descriptions-item label="来源读取时间">{{ displayTime(diagnostic.observed_at) }}</el-descriptions-item>
        <el-descriptions-item label="关联Q编号">{{ diagnosticQuestions(diagnostic.question_ids) }}</el-descriptions-item>
      </el-descriptions>
      <div class="detail-section">
        <h3>设计登记目录</h3>
        <p class="field-help">登记版本与描述来自平台设计基线目录；运行定义与当前租户发布版本请在运行配置中心核对。</p>
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
        <h3>设计登记的来源说明</h3>
        <ul class="configuration-diagnostic-reasons">
          <li v-for="(reason, index) in diagnostic.reasons.filter(r => !['RUNTIME_DEFINITION_SOURCE_UNAVAILABLE','PUBLICATION_SOURCE_UNAVAILABLE'].includes(r.code))" :key="`${reason.code}:${reason.source_id}:${index}`">
            <strong>{{ configurationDiagnosticReasonLabel[reason.code] }}</strong>
            <span class="mono">{{ reason.code }}</span>
            <p>{{ configurationDiagnosticSourceLabel[reason.source_kind] }} · {{ reason.source_id === null ? '来源标识未提供' : reason.source_id }} · {{ reason.source_version === null ? '版本未提供' : `v${reason.source_version}` }}</p>
          </li>
        </ul>
      </div>
      <div class="detail-section">
        <h3>当前租户近期提案</h3>
        <p class="field-help">展示最近最多 5 条内部修订提案记录；实际发布状态请前往运行配置中心查询。</p>
        <p v-if="diagnostic.recent_proposals.truncated" class="field-help">仅列出最近 5 条提案记录，更多记录请在“修订与审批”中查看。</p>
        <p v-if="!diagnostic.recent_proposals.items.length" class="muted">当前配置暂无近期修订提案。</p>
        <AppTable v-else :rows="diagnostic.recent_proposals.items" row-key="id">
          <el-table-column label="内部提案 / 版本" min-width="230"><template #default="{ row }"><span class="mono">{{ row.id }}</span><span class="cell-sub">v{{ row.version }} · INTERNAL_REVISION</span></template></el-table-column>
          <el-table-column label="内部流程" min-width="200"><template #default="{ row }">{{ revisionStatusLabel[row.workflow_status as keyof typeof revisionStatusLabel] }}<span class="cell-sub">运行发布另行查询</span></template></el-table-column>
          <el-table-column label="证据输入" min-width="190"><template #default="{ row }">{{ row.evidence_reference_count === null ? '引用数量未知' : `${row.evidence_reference_count} 项引用` }}<span class="cell-sub">书面引用 · REFERENCE_ONLY</span><span class="cell-sub">待客户核验 · UNVERIFIED</span></template></el-table-column>
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
