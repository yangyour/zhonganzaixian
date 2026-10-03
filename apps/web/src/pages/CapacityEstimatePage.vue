<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createCapacityWorkspace, emptyCapacityInput, type CapacityState, type CapacityOutputs } from '../utils/capacityEstimateWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const fields = [
  { key: 'hosts', label: '主机数', help: '非负整数' }, { key: 'items_per_host', label: '每台有效监控项数', help: '非负整数' },
  { key: 'interval_seconds', label: '采样间隔（秒）', help: '正数' }, { key: 'history_days', label: '历史保留天数', help: '非负整数' },
  { key: 'bytes_per_value', label: '每值空间（bytes）', help: '正数，输入值未核验' }, { key: 'trend_ratio', label: '启用趋势比例', help: '0至1；0.25代表25%' },
  { key: 'trend_days', label: '趋势保留天数', help: '非负整数' }, { key: 'headroom_ratio', label: '容量额外余量比例', help: '0至1；公式乘以1+该值' },
] as const
const modelFields = [{ key: 'version', label: '明确的模型版本标签', help: '自述标签，不代表已批准模型' }, { key: 'bytes_per_trend_row', label: '每条趋势空间（bytes）', help: '正数，不预填原型系数' }, { key: 'pool_nvps_limit', label: '显式单池NVPS上限', help: '正数，不代表实测或获准容量' }] as const
const bufferFields = [{ key: 'site_nvps', label: '站点NVPS', help: '非负数，不自动沿用主模型' }, { key: 'outage_seconds', label: '最长断网时长（秒）', help: '非负数，需明确输入' }, { key: 'bytes_per_value', label: '每值实测空间输入（bytes）', help: '正数；本工具不核验测量来源' }, { key: 'margin_factor', label: '缓冲余量倍率', help: '正数，直接相乘；不是额外余量比例' }] as const
const outputs: { key: keyof CapacityOutputs; label: string; unit: string }[] = [{ key: 'nvps', label: '平均NVPS', unit: 'values/s' }, { key: 'daily_bytes', label: '日写入量', unit: 'bytes' }, { key: 'history_bytes', label: '历史总量', unit: 'bytes' }, { key: 'trend_bytes', label: '趋势数据量', unit: 'bytes' }, { key: 'single_node_bytes', label: '单节点容量', unit: 'bytes' }, { key: 'two_node_bytes', label: '主备两份容量', unit: 'bytes' }, { key: 'peak_nvps', label: '2倍峰值目标', unit: 'values/s' }, { key: 'total_items', label: '有效监控项总数', unit: 'items' }, { key: 'pools', label: '资源池数量估算', unit: 'pools' }]
const draft = reactive({ inputs: Object.fromEntries(fields.map(f => [f.key, ''])) as Record<typeof fields[number]['key'], string>, model: Object.fromEntries(modelFields.map(f => [f.key, ''])) as Record<typeof modelFields[number]['key'], string>, proxy_buffer: Object.fromEntries(bufferFields.map(f => [f.key, ''])) as Record<typeof bufferFields[number]['key'], string>, includeBuffer: false })
const state = shallowRef<CapacityState>({ input: emptyCapacityInput(), result: null, busy: false, error: null })
const workspace = createCapacityWorkspace(api, value => { state.value = value })
const permitted = computed(() => session.value?.user.role === 'padmin' && can('capacity:estimate') && !switchingTenant.value)
const nullable = (v: string) => v === '' ? null : v
const days = (v: string) => v === '' ? null : /^(0|[1-9][0-9]{0,9})$/.test(v) ? Number(v) : v
function input() { return { inputs: Object.fromEntries(fields.map(f => [f.key, ['history_days', 'trend_days'].includes(f.key) ? days(draft.inputs[f.key]) : nullable(draft.inputs[f.key])])), model: Object.fromEntries(modelFields.map(f => [f.key, nullable(draft.model[f.key])])), proxy_buffer: draft.includeBuffer ? Object.fromEntries(bufferFields.map(f => [f.key, nullable(draft.proxy_buffer[f.key])])) : null } }
function clearDraft() { for (const f of fields) draft.inputs[f.key] = ''; for (const f of modelFields) draft.model[f.key] = ''; for (const f of bufferFields) draft.proxy_buffer[f.key] = ''; draft.includeBuffer = false; workspace.change(input()) }
watch(() => [session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, contextGeneration.value, permitted.value], () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canEstimate: true } : null); clearDraft() }, { immediate: true, flush: 'sync' })
watch(draft, () => workspace.change(input()), { deep: true, flush: 'sync' })
async function calculate() { if (!permitted.value || state.value.busy) return; workspace.change(input()); await workspace.calculate() }
onBeforeUnmount(() => workspace.dispose())
const missingLabels: Record<string, string> = Object.fromEntries([...fields.map(f => [f.key, f.label]), ...modelFields.map(f => ['model.' + f.key, f.label]), ['inputs', '容量输入组'], ['model', '模型参数组']])
const bufferMissing: Record<string, string> = Object.fromEntries([...bufferFields.map(f => [f.key, f.label]), ['proxy_buffer', '尚未启用Proxy缓冲输入']])
</script>
<template>
  <section class="capacity-page">
    <div class="page-heading"><div><span class="eyebrow">EXPLICIT ESTIMATES</span><h1>容量与 Proxy 缓冲估算</h1><p>平台管理员 · 显式输入 · 只读数学工具</p></div></div>
    <el-alert type="warning" :closable="false" title="估算不是实测容量、采购规格或PoC验收">所有系数须明确输入，不自动填入80/128字节、5000NVPS或余量示例。模型版本和池上限未核验；不保存场景，不改变监控、资源池或租户配额。</el-alert>
    <p class="capacity-help">空白表示未知（null），不会当成零。输入修改立即清除旧结果，离开或切换租户后清空；不会自动计算。数量和小数始终以十进制字符串传输，避免大整数精度丢失。</p>
    <el-empty v-if="!permitted" description="当前身份没有平台容量估算权限" />
    <template v-else>
      <ApiErrorNotice :error="state.error" />
      <el-form label-position="top" @submit.prevent="calculate">
        <section class="capacity-input-section"><h2>八项容量输入</h2><div class="capacity-fields"><el-form-item v-for="field in fields" :key="field.key" :label="field.label"><el-input v-model="draft.inputs[field.key]" :aria-label="field.label" inputmode="decimal" placeholder="未知留空，明确0请填写0" /><span class="field-help">{{ field.help }}</span></el-form-item></div></section>
        <section class="capacity-input-section"><h2>明确的模型系数</h2><p class="capacity-help">原型示例不是运行默认，也不能证明客户批准或实测单池能力。</p><div class="capacity-fields"><el-form-item v-for="field in modelFields" :key="field.key" :label="field.label"><el-input v-model="draft.model[field.key]" :aria-label="field.label" placeholder="未知留空" /><span class="field-help">{{ field.help }}</span></el-form-item></div></section>
        <section class="capacity-input-section"><h2>Proxy 缓冲独立试算</h2><el-checkbox v-model="draft.includeBuffer" aria-label="明确启用Proxy缓冲输入">明确提供缓冲计算输入</el-checkbox><p class="capacity-help">站点NVPS × 最长断网秒数 × 每值空间 × 余量倍率。与容量模型的“1+额外余量比例”不同；倍率直接相乘。</p><div v-if="draft.includeBuffer" class="capacity-fields"><el-form-item v-for="field in bufferFields" :key="field.key" :label="field.label"><el-input v-model="draft.proxy_buffer[field.key]" :aria-label="field.label" inputmode="decimal" placeholder="未知留空" /><span class="field-help">{{ field.help }}</span></el-form-item></div></section>
        <div class="capacity-actions"><el-button type="primary" native-type="submit" :loading="state.busy" :disabled="state.busy">只读估算</el-button><el-button @click="clearDraft">清空输入与结果</el-button></div>
      </el-form>
      <section v-if="state.result" class="capacity-results" aria-live="polite">
        <h2>{{ state.result.status === 'ESTIMATED' ? '显式容量估算结果' : '容量输入尚不完整' }}</h2><p class="capacity-help">公式 {{ state.result.formula_version }} · 模型标签 {{ state.result.model_version ?? '未知' }} · 精度 DECIMAL128，最终一次舍入</p>
        <p v-if="state.result.missing_inputs.length">待明确：{{ state.result.missing_inputs.map(k => missingLabels[k] || k).join('、') }}</p>
        <div class="capacity-output-grid"><article v-for="field in outputs" :key="field.key"><h3>{{ field.label }}</h3><strong>{{ state.result.outputs?.[field.key] ?? '未知' }}</strong><small>{{ field.unit }}</small></article></div>
        <h3>Proxy缓冲：{{ state.result.proxy_buffer.status === 'ESTIMATED' ? '显式估算' : '输入未完整' }}</h3><p v-if="state.result.proxy_buffer.missing_inputs.length">待明确：{{ state.result.proxy_buffer.missing_inputs.map(k => bufferMissing[k] || k).join('、') }}</p><p class="capacity-buffer-value">{{ state.result.proxy_buffer.estimated_bytes ?? '未知' }} <small>bytes</small></p>
        <el-alert v-if="state.result.proxy_buffer.margin_factor_below_one" type="warning" :closable="false" title="缓冲倍率小于1，结果小于基础需求；未自动把它解释成额外余量，也不能视为容量足够" />
        <p class="capacity-help">主备两份只是数学估算，不证明HA、Proxy接管或断网无丢失。每值空间来源未核验，实际容量和获准池限仍未知。</p>
      </section>
      <section class="capacity-boundaries"><h2>仍待真实验收</h2><p>PoC目标：12,500NVPS持续24小时，2倍峰值30分钟；本工具没有执行该压测。场景持久化、压测基准关联和增长曲线尚未提供。当前输入仅在本页，不是已保存方案。</p></section>
    </template>
  </section>
</template>
<style scoped>
.capacity-page{max-width:1200px}.capacity-help{line-height:1.65;color:#526176}.capacity-input-section,.capacity-results,.capacity-boundaries{margin:20px 0;padding:20px;border:1px solid #dce4ee;border-radius:12px;background:white;overflow-wrap:anywhere}.capacity-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 20px}.capacity-fields .el-form-item{min-width:0}.capacity-fields .field-help{display:block;width:100%;margin-top:5px}.capacity-actions{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.capacity-output-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.capacity-output-grid article{padding:16px;border:1px solid #dce4ee;border-radius:8px;min-width:0}.capacity-output-grid h3{font-size:14px;color:#526176;margin:0 0 10px}.capacity-output-grid strong,.capacity-buffer-value{font-family:monospace;font-size:19px;overflow-wrap:anywhere}.capacity-output-grid small{display:block;margin-top:8px;color:#526176}.capacity-buffer-value small{font-family:inherit;font-size:13px}@media(max-width:700px){.capacity-fields,.capacity-output-grid{grid-template-columns:1fr}.capacity-input-section,.capacity-results,.capacity-boundaries{padding:14px}.capacity-actions .el-button{min-height:44px}.capacity-page :deep(.el-input__wrapper){min-height:44px}}
</style>
