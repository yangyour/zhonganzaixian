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
 <section class="capacity-growth" v-if="permitted"><h2>随主机数增长的容量推演对照</h2><p>基于上方当前容量模型参数，按您输入的递增主机数序列逐档测算容量需求（支持输入 2 至 20 个递增非负整数，以逗号或空格分隔）。本对照仅作主机规模容量推演，不计算 Proxy 缓冲、不保存方案，也不是时间预测或实测趋势。</p>
  <el-form label-position="top" @submit.prevent="workspace.calculate(calculation, hosts)"><el-form-item label="主机数序列（2–20 个递增非负整数）"><el-input v-model="hosts" type="textarea" :rows="2" aria-label="容量增长主机数序列" placeholder="例如：100, 500, 1000, 2000" /></el-form-item><div class="growth-actions"><el-button native-type="submit" type="primary" :loading="state.busy" :disabled="state.busy">开始推演测算</el-button><el-button :disabled="!state.busy && !state.points" @click="workspace.invalidate()">清空结果</el-button><span v-if="state.busy">已核对 {{ state.completed }}/{{ state.total }}；完成前不展示部分曲线</span></div></el-form>
  <ApiErrorNotice :error="state.error" />
  <template v-if="state.points"><p>各档位结果均由服务端实时计算并通过算术一致性校验（修改模型参数后自动清空）；容量单位为 bytes（十进制）。</p><p v-if="!bars">模型输入尚未完备，暂无法绘制对照图：{{ state.points[0]?.result.missing_inputs.join('、') }}。</p>
   <div v-else class="growth-chart" aria-label="主机数与容量离散对照，精确值列在每条下方"><p>三项指标共用比例尺独立展示（非堆叠条形图）；精确字节数详见各条形下方标注。</p><article v-for="point in bars" :key="point.hosts"><h3>主机数 {{ point.hosts }}</h3><div v-for="value in point.values" :key="value.key" class="growth-series"><strong>{{ value.label }}</strong><div class="growth-track" aria-hidden="true"><span :style="{ width: value.width, background: value.color }" /></div><span class="growth-value">{{ value.value }} bytes</span></div></article></div>
  </template>
 </section>
</template>
<style scoped>
.capacity-growth{margin:20px 0;padding:20px;background:white;border:1px solid #dce4ee;border-radius:12px;overflow-wrap:anywhere}.capacity-growth p{line-height:1.65;color:#526176}.growth-actions{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.growth-chart article{margin:20px 0;padding:12px;border:1px solid #dce4ee;border-radius:8px}.growth-series{margin:14px 0}.growth-series strong{font-size:13px}.growth-track{height:12px;background:#eef2f7;margin:6px 0;overflow:hidden;border-radius:3px}.growth-track span{display:block;height:100%}.growth-value{font-family:monospace;overflow-wrap:anywhere;font-size:13px}@media(max-width:700px){.capacity-growth{padding:14px}.capacity-growth .el-button{min-height:44px;height:auto;white-space:normal}.growth-actions>span{width:100%}}
</style>
