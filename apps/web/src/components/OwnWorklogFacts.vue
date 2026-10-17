<script setup lang="ts">
import type {WorklogEntry,WorklogRevision,WorklogKind} from '../utils/worklogWorkspace'
import {displayTime} from '../utils/display'
defineProps<{value:WorklogEntry|WorklogRevision}>()
const labels:Record<WorklogKind,string>={REMOTE:'远程处理',ONSITE:'现场处理',INSPECTION:'巡检',COMMUTE:'通勤'}
function duration(ms:number){const hours=Math.floor(ms/3600000),minutes=Math.floor(ms%3600000/60000),seconds=Math.floor(ms%60000/1000),fraction=ms%1000;return`${hours}小时 ${minutes}分 ${seconds}${fraction?'.'+String(fraction).padStart(3,'0'):''}秒`}
</script>
<template>
 <div class="own-worklog-facts"><dl><div><dt>作业类型</dt><dd>{{labels[value.kind]}}</dd></div><div><dt>实际经过时长（未作计费取整）</dt><dd>{{duration(value.elapsed_millis)}}<small>{{value.elapsed_millis}} ms；{{value.elapsed_millis===0?'明确实填零时长，不是缺值默认':'完整原始区间，不按筛选窗口裁剪'}}</small></dd></div><div><dt>开始时间（Asia/Shanghai）</dt><dd>{{displayTime(value.started_at)}}<small>原始 UTC：{{value.started_at}}</small></dd></div><div><dt>结束时间（Asia/Shanghai）</dt><dd>{{displayTime(value.ended_at)}}<small>原始 UTC：{{value.ended_at}}</small></dd></div><div><dt>记录来源</dt><dd>工程师人工填报 · 原始记录</dd></div><div><dt>默认工时 / 成本 / 毛利核算</dt><dd>待配置核算规则</dd></div></dl><h3>作业备注</h3><p v-if="value.note===null">未填写</p><pre v-else>{{value.note}}</pre></div>
</template>
<style scoped>
.own-worklog-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.own-worklog-facts dt{font-size:12px;color:#607086}.own-worklog-facts dd{margin:5px 0 0;line-height:1.65;overflow-wrap:anywhere}.own-worklog-facts small{display:block;color:#607086;font-size:12px;overflow-wrap:anywhere}.own-worklog-facts pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;background:#f5f8fc;border-radius:8px;padding:12px}.own-worklog-facts h3{font-size:14px;margin:16px 0 6px}@media(max-width:700px){.own-worklog-facts dl{grid-template-columns:1fr}}
</style>
