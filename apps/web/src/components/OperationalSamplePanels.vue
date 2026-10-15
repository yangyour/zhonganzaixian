<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { OperationalResult } from '../utils/operationalComparisonWorkspace'
import { operationalSampleDistribution } from '../utils/operationalSampleDistribution'
const props = defineProps<{ result: OperationalResult | null; busy: boolean }>()
const offset = ref(0), onlyUnknown = ref(false)
const distribution = computed(() => operationalSampleDistribution(props.result?.input.elapsed_millis ?? null))
const filtered = computed(() => distribution.value?.rows.filter(row => !onlyUnknown.value || row.value === null) ?? [])
const rows = computed(() => filtered.value.slice(offset.value, offset.value + 50))
watch(() => props.result, () => { offset.value = 0; onlyUnknown.value = false }, { flush: 'sync' })
watch(onlyUnknown, () => { offset.value = 0 }, { flush: 'sync' })
</script>
<template>
 <div class="sample-panels">
  <section class="sample-panel" aria-label="本次耗时样本分布"><div class="panel-heading"><h2>本次样本分布</h2><span>毫秒 · {{ result ? '已回读输入' : '等待计算' }}</span></div>
   <p v-if="!result">{{ busy ? '正在取得服务端对照结果…' : '输入样本并点击“只读对照”后查看分布。' }}</p>
   <p v-else-if="!distribution">本次明确未提供样本，分布未知。</p>
   <template v-else><div class="sample-counts"><span>已知 <b>{{ distribution.known }}</b></span><span>未知 <b>{{ distribution.unknown }}</b></span><span>总数 <b>{{ distribution.rows.length }}</b></span></div>
    <p v-if="!distribution.rows.length">本次明确提供空集合，没有样本可绘制。</p>
    <p v-else-if="!distribution.known">全部样本未知，没有可绘制的已知值。</p>
    <ul v-else class="sample-bars"><li v-for="bin in distribution.bins" :key="bin.from"><span>{{ bin.from === bin.to ? bin.from : bin.from + ' — ' + bin.to }} ms</span><meter min="0" :max="distribution.known" :value="bin.count" :aria-label="bin.from+'到'+bin.to+'毫秒样本数'"/><b>{{ bin.count }} 条</b></li></ul>
    <p v-if="distribution.unknown" class="sample-warning">{{ distribution.unknown }} 个未知项单独保留；条形仅分组已知样本，不能用已知子集替代整组中位数。</p>
    <p class="sample-note">最多5个等宽整数区间，重复值与明确0保留。样本没有时间顺序，分布不表示工单响应趋势。</p>
    <details v-if="distribution.rows.length"><summary>核对原始输入顺序（{{ distribution.rows.length }} 条）</summary><label class="sample-filter"><input v-model="onlyUnknown" type="checkbox" aria-label="只看未知样本"/>只看未知项</label><div class="sample-table-wrap"><table><thead><tr><th>输入序号</th><th>耗时 / 毫秒</th></tr></thead><tbody><tr v-for="row in rows" :key="row.index"><td>{{ row.index }}</td><td>{{ row.value ?? '未知（?）' }}</td></tr></tbody></table></div><p v-if="!rows.length">当前筛选没有样本。</p><div class="sample-pagination"><span>{{ filtered.length ? offset+1 : 0 }}—{{ Math.min(offset+50,filtered.length) }} / {{ filtered.length }}</span><button type="button" :disabled="offset===0" @click="offset=Math.max(0,offset-50)">上一页样本</button><button type="button" :disabled="offset+50>=filtered.length" @click="offset+=50">下一页样本</button></div></details>
   </template>
  </section>
  <section class="sample-panel" aria-label="本次人力输入"><div class="panel-heading"><h2>明确人力输入</h2><span>{{ result ? '本次回读' : '等待计算' }}</span></div><p v-if="!result">计算后核对本次提交的人力与租户数。</p><p v-else-if="!result.input.staffing">本次未提供人力组，不推断人数或实际覆盖范围。</p><template v-else><dl class="sample-staffing"><div><dt>活跃租户数</dt><dd>{{ result.input.staffing.active_tenants ?? '未填写' }}</dd></div><div><dt>月度值守 FTE</dt><dd>{{ result.input.staffing.on_call_fte ?? '未填写' }}</dd></div><div><dt>月度二线 FTE</dt><dd>{{ result.input.staffing.second_line_fte ?? '未填写' }}</dd></div><div><dt>服务端 FTE 合计</dt><dd>{{ result.staffing.explicit_total_fte ?? '尚不能计算' }}</dd></div><div><dt>服务端租户数 / FTE</dt><dd>{{ result.staffing.tenants_per_fte ?? '尚不能计算' }}</dd></div></dl><p class="sample-note">只反映明确提交的月度输入；不代表已核验出勤、平台租户总量或财务指标。</p></template></section>
 </div>
</template>
<style scoped>
.sample-panels{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;align-items:start}.sample-panel{background:white;border:1px solid #e1e8f0;border-radius:6px;padding:18px;min-width:0;overflow-wrap:anywhere}.sample-panel h2{font-size:15px;margin:0}.sample-panel p,.sample-panel summary{font-size:13px;line-height:1.8;color:#52657c}.sample-counts{display:flex;gap:16px;flex-wrap:wrap;padding:12px 0;font-size:13px}.sample-counts b{font-size:20px;margin-left:6px;color:#263f60}.sample-bars{list-style:none;padding:0;margin:14px 0}.sample-bars li{display:grid;grid-template-columns:minmax(0,1fr) minmax(50px,1fr) auto;gap:10px;align-items:center;margin:14px 0;font-size:12px}.sample-bars meter{width:100%;height:18px}.sample-warning{background:#fff5e6;color:#815a1d!important;padding:10px;border-radius:4px}.sample-note{font-size:12px!important}.sample-filter{display:flex;align-items:center;gap:6px;margin:12px 0;font-size:13px}.sample-table-wrap{max-height:300px;overflow:auto}.sample-table-wrap table{width:100%;border-collapse:collapse;font-size:12px;text-align:left}.sample-table-wrap td,.sample-table-wrap th{border-bottom:1px solid #e5ebf3;padding:10px;overflow-wrap:anywhere}.sample-table-wrap th{background:#f5f8fc}.sample-pagination{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:12px 0;font-size:12px}.sample-pagination button{background:white;border:1px solid #ccdae9;border-radius:4px;padding:6px 10px;color:#245c99;min-height:36px}.sample-pagination button:disabled{opacity:.5}.sample-staffing{margin:14px 0}.sample-staffing div{display:flex;justify-content:space-between;gap:18px;padding:13px 0;border-bottom:1px solid #e5ebf3;font-size:13px}.sample-staffing dt{color:#65778d;min-width:100px}.sample-staffing dd{margin:0;text-align:right;overflow-wrap:anywhere;min-width:0}@media(max-width:760px){.sample-panels{grid-template-columns:1fr}.sample-panel{padding:14px}.sample-pagination button{min-height:44px}.sample-filter{min-height:44px}}
</style>
