<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createGrowthWorkspace, emptyGrowthState, growthWidths, type GrowthState } from '../utils/capacityGrowthWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ calculation: unknown }>()
const hosts = ref(''), state = shallowRef<GrowthState>(emptyGrowthState()), workspace = createGrowthWorkspace(api, value => { state.value = value })
const permitted = computed(() => session.value?.user.role === 'padmin' && can('capacity:estimate') && !switchingTenant.value)
watch(() => [session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, contextGeneration.value, permitted.value], () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canEstimate: true } : null); hosts.value = '' }, { immediate: true, flush: 'sync' })
watch(() => JSON.stringify(props.calculation), () => workspace.invalidate(), { flush: 'sync' })
watch(hosts, () => workspace.invalidate(), { flush: 'sync' })
const series = [{ key: 'history_bytes', label: '历史总量（不含余量）', color: '#2563eb' }, { key: 'trend_bytes', label: '趋势总量（不含余量）', color: '#0d9488' }, { key: 'single_node_bytes', label: '单节点总量（含余量）', color: '#9333ea' }] as const
const bars = computed(() => { const points = state.value.points; if (!points || points.some(p => !p.result.outputs)) return null; const widths = growthWidths(points.flatMap(p => series.map(s => p.result.outputs![s.key]))); return points.map((p, i) => ({ hosts: p.hosts, values: series.map((s, j) => ({ ...s, value: p.result.outputs![s.key], width: widths[i * 3 + j]! })) })) })
onUnmounted(workspace.dispose)
</script>
<template>
 <section class="capacity-growth" v-if="permitted"><h2>随主机数增长的显式容量对照</h2><p>使用上方当前模型，逐个替换主机数，其他容量参数保持不变。请明确填写2至20个递增主机数，逗号或空白分隔；不提供原型默认档位。此对照不计算Proxy、不保存方案，也不是时间预测或实测趋势。</p>
  <el-form label-position="top" @submit.prevent="workspace.calculate(calculation, hosts)"><el-form-item label="明确主机数序列"><el-input v-model="hosts" type="textarea" :rows="2" aria-label="容量增长主机数序列" placeholder="尚未提供；请输入2至20个递增非负整数" /></el-form-item><div class="growth-actions"><el-button native-type="submit" type="primary" :loading="state.busy" :disabled="state.busy">只读计算全部对照点</el-button><el-button :disabled="!state.busy && !state.points" @click="workspace.invalidate()">停止读取 / 清除结果</el-button><span v-if="state.busy">已核对 {{ state.completed }}/{{ state.total }}；完成前不展示部分曲线</span></div></el-form>
  <ApiErrorNotice :error="state.error" />
  <template v-if="state.points"><p>全部点均为真实接口返回并经独立算术核对；改动输入立即清除。单位为bytes（十进制），没有采购或容量达标结论。</p><p v-if="!bars">输入未完整，不能绘制曲线：{{ state.points[0]?.result.missing_inputs.join('、') }}。未知没有当作零。</p>
   <div v-else class="growth-chart" aria-label="主机数与容量离散对照，精确值列在每条下方"><p>三系列共用比例尺，各条不是叠加。小于可见像素的数值请读取完整数字；不会给零值伪造条长。</p><article v-for="point in bars" :key="point.hosts"><h3>主机数 {{ point.hosts }}</h3><div v-for="value in point.values" :key="value.key" class="growth-series"><strong>{{ value.label }}</strong><div class="growth-track" aria-hidden="true"><span :style="{ width: value.width, background: value.color }" /></div><span class="growth-value">{{ value.value }} bytes</span></div></article></div>
  </template>
 </section>
</template>
<style scoped>
.capacity-growth{margin:20px 0;padding:20px;background:white;border:1px solid #dce4ee;border-radius:12px;overflow-wrap:anywhere}.capacity-growth p{line-height:1.65;color:#526176}.growth-actions{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.growth-chart article{margin:20px 0;padding:12px;border:1px solid #dce4ee;border-radius:8px}.growth-series{margin:14px 0}.growth-series strong{font-size:13px}.growth-track{height:12px;background:#eef2f7;margin:6px 0;overflow:hidden;border-radius:3px}.growth-track span{display:block;height:100%}.growth-value{font-family:monospace;overflow-wrap:anywhere;font-size:13px}@media(max-width:700px){.capacity-growth{padding:14px}.capacity-growth .el-button{min-height:44px;height:auto;white-space:normal}.growth-actions>span{width:100%}}
</style>
