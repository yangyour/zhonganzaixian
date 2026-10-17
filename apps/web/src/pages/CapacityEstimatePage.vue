<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createCapacityWorkspace, emptyCapacityInput, type CapacityState, type CapacityOutputs } from '../utils/capacityEstimateWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import CapacityScenarioPanel from '../components/CapacityScenarioPanel.vue'
import CapacityGrowthPanel from '../components/CapacityGrowthPanel.vue'
import CapacityRuntimeInputsPanel from '../components/CapacityRuntimeInputsPanel.vue'
import type { capacityImportedValues } from '../utils/capacityRuntimeInputs'
const scenarios = ref<InstanceType<typeof CapacityScenarioPanel> | null>(null)
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
const importedAt = ref<string | null>(null)
const permitted = computed(() => session.value?.user.role === 'padmin' && can('capacity:estimate') && !switchingTenant.value)
const nullable = (v: string) => v === '' ? null : v
const days = (v: string) => v === '' ? null : /^(0|[1-9][0-9]{0,9})$/.test(v) ? Number(v) : v
function input() { return { inputs: Object.fromEntries(fields.map(f => [f.key, ['history_days', 'trend_days'].includes(f.key) ? days(draft.inputs[f.key]) : nullable(draft.inputs[f.key])])), model: Object.fromEntries(modelFields.map(f => [f.key, nullable(draft.model[f.key])])), proxy_buffer: draft.includeBuffer ? Object.fromEntries(bufferFields.map(f => [f.key, nullable(draft.proxy_buffer[f.key])])) : null } }
function clearDraft() { for (const f of fields) draft.inputs[f.key] = ''; for (const f of modelFields) draft.model[f.key] = ''; for (const f of bufferFields) draft.proxy_buffer[f.key] = ''; draft.includeBuffer = false; workspace.change(input()) }
const authorizationKey = computed(() => JSON.stringify([session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, contextGeneration.value, permitted.value]))
watch(authorizationKey, () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canEstimate: true } : null); clearDraft(); importedAt.value = null }, { immediate: true, flush: 'sync' })
watch(draft, () => { workspace.change(input()); importedAt.value = null }, { deep: true, flush: 'sync' })
function importRuntime(values: ReturnType<typeof capacityImportedValues>, checkedAt: string) { if (!permitted.value) return; Object.assign(draft.inputs, values.inputs); Object.assign(draft.proxy_buffer, values.buffer); workspace.change(input()); importedAt.value = checkedAt }
async function calculate() { if (!permitted.value || state.value.busy) return; workspace.change(input()); await workspace.calculate() }
onBeforeUnmount(() => workspace.dispose())
const missingLabels: Record<string, string> = Object.fromEntries([...fields.map(f => [f.key, f.label]), ...modelFields.map(f => ['model.' + f.key, f.label]), ['inputs', '容量输入组'], ['model', '模型参数组']])
const bufferMissing: Record<string, string> = Object.fromEntries([...bufferFields.map(f => [f.key, f.label]), ['proxy_buffer', '尚未启用Proxy缓冲输入']])
</script>
<template>
  <CapacityScenarioPanel ref="scenarios" :calculation="input()" />
  <section class="capacity-page">
    <div class="page-heading"><div><span class="eyebrow">容量规划</span><h1>容量与 Proxy 缓冲评估</h1><p>按有效监控项、采样频率和保留周期测算存储与吞吐规模 · 模型估算供容量规划参考</p></div><div class="capacity-actions"><el-button v-if="can('capacity_scenarios:read')" @click="scenarios?.browse()">已保存方案</el-button><el-button v-if="can('capacity_scenarios:write')" type="primary" @click="scenarios?.prepare()">以当前输入准备保存方案</el-button></div></div>
    <el-empty v-if="!permitted" description="当前身份没有平台容量估算权限" />
    <template v-else>
      <div class="feature-summary"><article><span>平均 NVPS</span><strong>{{ state.result?.outputs?.nvps ?? '—' }}</strong><small>值 / 秒 · 模型估算值</small></article><article><span>单节点容量</span><strong>{{ state.result?.outputs?.single_node_bytes ?? '—' }}</strong><small>bytes · 历史 + 趋势 + 余量</small></article><article><span>有效监控项</span><strong>{{ state.result?.outputs?.total_items ?? '—' }}</strong><small>数量未知时不按零处理</small></article><article><span>模型状态</span><strong class="summary-status">{{ state.result?.status === 'ESTIMATED' ? '已估算' : '待明确' }}</strong><small>供容量规划参考</small></article></div>
      <ApiErrorNotice :error="state.error" />
      <CapacityRuntimeInputsPanel @apply="importRuntime" />
      <p v-if="importedAt" role="status" class="capacity-help">已填入 {{importedAt}} 读取的参数快照。修改任意输入后按修改后的显式参数计算。</p>
      <div class="capacity-layout">
        <div class="capacity-main">
          <el-form label-position="top" @submit.prevent="calculate">
            <section class="capacity-input-section"><div class="panel-heading"><h2>可调整的容量模型</h2><span>未确认项请留空</span></div><div class="capacity-fields"><el-form-item v-for="field in fields" :key="field.key" :label="field.label"><el-input v-model="draft.inputs[field.key]" :aria-label="field.label" inputmode="decimal" placeholder="未定请留空，0 请填 0" /><span class="field-help">{{ field.help }}</span></el-form-item></div>
              <div class="model-section"><h3>模型系数</h3><div class="model-fields"><el-form-item v-for="field in modelFields" :key="field.key" :label="field.label"><el-input v-model="draft.model[field.key]" :aria-label="field.label" placeholder="未定请留空" /><span class="field-help">{{ field.help }}</span></el-form-item></div></div>
              <div class="capacity-actions"><el-button type="primary" native-type="submit" :loading="state.busy" :disabled="state.busy">只读估算</el-button><el-button @click="clearDraft">清空输入与结果</el-button><span class="capacity-help">修改输入即清除旧结果，不自动计算</span></div>
            </section>
            <section class="capacity-input-section"><div class="panel-heading"><h2>Proxy 缓冲测算</h2><el-checkbox v-model="draft.includeBuffer" aria-label="明确启用Proxy缓冲输入">启用独立缓冲计算输入</el-checkbox></div><p class="capacity-help">站点 NVPS × 最长断网秒数 × 每值空间 × 余量倍率；倍率直接相乘。</p><div v-if="draft.includeBuffer" class="capacity-fields"><el-form-item v-for="field in bufferFields" :key="field.key" :label="field.label"><el-input v-model="draft.proxy_buffer[field.key]" :aria-label="field.label" inputmode="decimal" placeholder="未定请留空" /><span class="field-help">{{ field.help }}</span></el-form-item></div><p v-else class="capacity-empty-inline">尚未启用独立缓冲参数（不自动沿用主模型参数）。</p></section>
          </el-form>
          <CapacityGrowthPanel :calculation="input()" />
        </div>
        <aside class="capacity-side">
          <section class="capacity-results" aria-live="polite"><div class="panel-heading"><h2>{{ state.result ? (state.result.status === 'ESTIMATED' ? '容量模型估算结果' : '容量输入尚不完整') : '估算结果' }}</h2><span>未压缩模型</span></div>
            <template v-if="state.result"><p class="capacity-help">公式 {{ state.result.formula_version }} · 模型标签 {{ state.result.model_version ?? '未指定' }} · 精度 DECIMAL128，最终一次舍入</p><p v-if="state.result.missing_inputs.length" class="missing-inputs">待明确：{{ state.result.missing_inputs.map(k => missingLabels[k] || k).join('、') }}</p><div class="capacity-output-grid"><article v-for="field in outputs" :key="field.key"><h3>{{ field.label }}</h3><strong>{{ state.result.outputs?.[field.key] ?? '待测算' }}</strong><small>{{ field.unit }}</small></article></div><h3>Proxy缓冲：{{ state.result.proxy_buffer.status === 'ESTIMATED' ? '已完成估算' : '参数未完整' }}</h3><p v-if="state.result.proxy_buffer.missing_inputs.length" class="capacity-help">待明确：{{ state.result.proxy_buffer.missing_inputs.map(k => bufferMissing[k] || k).join('、') }}</p><p class="capacity-buffer-value">{{ state.result.proxy_buffer.estimated_bytes ?? '待测算' }} <small>bytes</small></p><el-alert v-if="state.result.proxy_buffer.margin_factor_below_one" type="warning" :closable="false" title="缓冲余量倍率小于 1，计算结果低于基础缓冲需求，请核对输入倍率。" /></template>
            <div v-else class="estimate-empty"><span>∑</span><h3>等待录入模型参数</h3><p>填写左侧参数并点击“只读估算”后，在此展示各项容量估算结果。</p><small>留空参数保持待确认状态，不预填默认演示系数</small></div>
          </section>
          <section class="capacity-input-section formula-card"><div class="panel-heading"><h2>计算依据</h2></div><dl><dt>平均 NVPS</dt><dd>主机数 × 有效监控项 ÷ 采样间隔</dd><dt>历史空间</dt><dd>NVPS × 86,400 × 历史天数 × 每值空间</dd><dt>趋势空间</dt><dd>总监控项 × 趋势比例 × 24 × 趋势天数 × 趋势行空间</dd><dt>单节点空间</dt><dd>（历史 + 趋势）×（1 + 额外余量比例）</dd></dl></section>
          <section class="capacity-input-section"><div class="panel-heading"><h2>本次模型输入核对</h2><span>{{ state.result ? '服务端回读' : '等待计算' }}</span></div><p v-if="!state.result" class="capacity-empty-inline">输入参数并执行估算后，可在此核对本次计算所采用的模型系数。</p><dl v-else class="capacity-model-readback"><div><dt>模型版本</dt><dd>{{ state.result.model_version ?? '未填写' }}</dd></div><div><dt>每条趋势空间</dt><dd>{{ state.result.input.model?.bytes_per_trend_row ?? '未填写' }} bytes</dd></div><div><dt>单池 NVPS 上限输入</dt><dd>{{ state.result.input.model?.pool_nvps_limit ?? '未填写' }}</dd></div><div><dt>主模型未完整字段</dt><dd>{{ state.result.missing_inputs.length }} 项</dd></div></dl><p class="capacity-empty-inline">以上为本次模型输入快照；实际数据库落盘体积、压缩比及实测资源池吞吐需结合压测结果核定。</p></section>
        </aside>
      </div>
      <details class="boundary-details capacity-boundaries"><summary>估算范围、输入精度与压测验收边界说明</summary><p>本页结果属于容量公式估算，不直接替代实测容量、采购规格或 PoC 验收报告。所有模型系数均按页面实际输入计算；只读估算不保存场景，如需归档可点击上方“以当前输入准备保存方案”保存为独立方案草稿（不影响生产监控或租户配额）。</p><p>留空字段代表暂未指定，不按零值折算。修改输入参数将立即清除旧结果，页面数值统一以高精度十进制字符串传输与计算。</p><p>主备双节点容量为算术双倍估算，高可用切换与断网续传能力需结合实际架构验证。</p><p>PoC 压测基线目标：12,500 NVPS 持续 24 小时、2 倍峰值持续 30 分钟；本工具没有执行该压测。系统支持保存容量方案草稿及主机增长推演对照，正式容量验收以压测报告为准。</p></details>
    </template>
  </section>
</template>
<style scoped>
.capacity-page{min-width:0}.capacity-layout{display:grid;grid-template-columns:minmax(0,1.8fr) minmax(320px,1fr);gap:14px;align-items:start;margin-top:14px}.capacity-main,.capacity-side{min-width:0}.capacity-input-section,.capacity-results{padding:18px;border:1px solid #e2e9f2;border-radius:6px;background:#fff;margin-bottom:14px;overflow-wrap:anywhere}.capacity-fields{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:0 14px}.model-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.capacity-fields .el-form-item,.model-fields .el-form-item{min-width:0;margin-bottom:16px}.field-help{display:block;font-size:11px;color:#8995a5;line-height:1.5;margin-top:5px}.capacity-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.capacity-actions .el-button+.el-button{margin-left:0}.capacity-help,.capacity-empty-inline,.capacity-boundaries{font-size:12px;color:#6a798c;line-height:1.7}.capacity-empty-inline{padding:20px 4px;text-align:center}.model-section{border-top:1px solid #edf1f6;padding-top:12px;margin-top:3px}.model-section h3{font-size:13px;margin:0 0 12px}.capacity-output-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.capacity-output-grid article{padding:13px;border:1px solid #dfeafa;border-radius:5px;background:#f4f8fe;min-width:0}.capacity-output-grid h3{font-size:11px;color:#6d7e96;margin:0 0 6px;font-weight:500}.capacity-output-grid strong{font-size:19px;color:#3467a6;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.capacity-output-grid small{display:block;font-size:10px;color:#8995a5;margin-top:4px}.capacity-results>h3{font-size:13px;margin-top:18px}.capacity-buffer-value{font-size:24px;color:#3467a6}.capacity-buffer-value small{font-size:12px;color:#7b8799}.estimate-empty{padding:44px 14px;text-align:center;color:#8696ac;font-size:12px;line-height:1.7}.estimate-empty>span{display:block;font-size:40px;color:#c1d1e6}.estimate-empty h3{font-size:14px;color:#546b89;font-weight:500}.formula-card dl{font-size:12px;line-height:1.8}.formula-card dt{color:#3e526d;font-weight:500;margin-top:9px}.formula-card dd{margin:0;color:#7c889a}.missing-inputs{padding:10px;background:#fff8e9;color:#9b712b;font-size:12px;line-height:1.6}.summary-status{font-size:20px!important}
.capacity-model-readback{font-size:13px}.capacity-model-readback>div{display:flex;gap:16px;justify-content:space-between;border-bottom:1px solid #e3eaf2;padding:12px 0}.capacity-model-readback dt{color:#60758b}.capacity-model-readback dd{margin:0;overflow-wrap:anywhere;min-width:0;text-align:right}
@media(max-width:1300px){.capacity-fields{grid-template-columns:repeat(2,minmax(0,1fr))}.model-fields{grid-template-columns:1fr}.model-fields .el-form-item{margin-bottom:4px}}@media(max-width:900px){.capacity-layout{grid-template-columns:1fr}.capacity-side{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.capacity-results{grid-column:1/-1}.capacity-output-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:600px){.capacity-fields,.capacity-side{grid-template-columns:1fr}.capacity-output-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.capacity-input-section,.capacity-results{padding:14px}.capacity-actions .el-button{min-height:44px}}
</style>
