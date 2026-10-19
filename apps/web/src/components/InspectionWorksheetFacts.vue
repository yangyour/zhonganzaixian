<script setup lang="ts">
import type { WorksheetDetail, WorksheetFacts } from '../utils/inspectionWorksheets'
import { displayTime } from '../utils/display'
defineProps<{ value: Pick<WorksheetDetail, keyof WorksheetFacts | "recorded_at" | "template_items" | "observations"> }>()
const resultLabel = { NORMAL: '正常（人工填写）', ATTENTION: '建议关注（人工填写）', REQUIRES_ACTION: '需处理（人工填写）' }
</script>
<template>
 <div class="worksheet-facts">
  <dl><div><dt>绑定资产 / 登记名称</dt><dd>{{ value.asset_name }}<small>{{ value.asset_id }}</small></dd></div><div><dt>关联模板版本</dt><dd>{{ value.template_name }} · 模板 v{{ value.template_version }}<small>{{ value.template_device_type }} · 适用性待核验</small></dd></div><div><dt>填报巡检时间</dt><dd>{{ value.reported_performed_at === null ? '未填写' : displayTime(value.reported_performed_at) }}<small v-if="value.reported_performed_at">UTC：{{ value.reported_performed_at }}</small></dd></div><div><dt>系统归档时间</dt><dd>{{ displayTime(value.recorded_at) }}</dd></div></dl>
  <p class="worksheet-summary">已填 {{ value.summary.answered }} / {{ value.summary.total }} 项 · 未填 {{ value.summary.unanswered }} 项 · 正常 {{ value.summary.normal }} / 建议关注 {{ value.summary.attention }} / 需处理 {{ value.summary.requires_action }}</p>
  <p class="warning">以上为人工巡检填报草稿（待复核归档，附件凭证绑定待接入）。</p>
  <ol><li v-for="item in value.template_items" :key="item.key"><strong>{{ item.label }}</strong><small>{{ item.section }} · {{ item.key }}</small><p>{{ item.instruction === null ? '检查说明未填写' : item.instruction === '' ? '无说明（置空）' : item.instruction }}</p><template v-for="answer in value.observations.filter(row=>row.key===item.key)" :key="answer.key"><b>{{ resultLabel[answer.result] }}</b><p>{{ answer.note === null ? '备注未填写' : answer.note === '' ? '无备注（置空）' : answer.note }}</p></template><p v-if="!value.observations.some(row=>row.key===item.key)" class="unanswered">待填报（不计入正常项）</p></li></ol>
  <details><summary>查看绑定摘要</summary><p class="hash">模板 SHA256：{{ value.template_content_sha256 }}</p><p class="hash">执行单 SHA256：{{ value.content_sha256 }}</p></details>
 </div>
</template>
<style scoped>
.worksheet-facts{min-width:0}.worksheet-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.worksheet-facts dl>div{padding:10px 12px;background:#f8fafd;border:1px solid #e7edf5;border-radius:6px}.worksheet-facts dt{font-size:12px;color:#65758a}.worksheet-facts dd{margin:4px 0 0;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.worksheet-facts small{display:block;font-size:12px;color:#617187;margin-top:4px;overflow-wrap:anywhere}.worksheet-summary{font-variant-numeric:tabular-nums;background:#f5f9ff;border:1px solid #e3ecf8;padding:8px 12px;border-radius:6px;font-size:13px}.worksheet-facts ol{padding-left:24px;margin:12px 0}.worksheet-facts li{padding:12px 8px;border-bottom:1px solid #e1e8f0;overflow-wrap:anywhere;transition:background-color .15s ease}.worksheet-facts li:hover{background:#f8fbff}.worksheet-facts li p{white-space:pre-wrap;overflow-wrap:anywhere;margin:6px 0}.worksheet-facts .warning{color:#80601c;font-size:12px}.worksheet-facts .unanswered{color:#68768a;font-size:12px}.worksheet-facts .hash{font:12px/1.6 "Cascadia Code",Consolas,monospace;overflow-wrap:anywhere}.worksheet-facts summary{cursor:pointer;min-height:44px;padding-top:12px;color:#3567a8;font-size:13px}@media(max-width:600px){.worksheet-facts dl{grid-template-columns:1fr}}
</style>
