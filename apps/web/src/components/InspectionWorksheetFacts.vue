<script setup lang="ts">
import type { WorksheetDetail, WorksheetRevision } from '../utils/inspectionWorksheets'
import { displayTime } from '../utils/display'
defineProps<{ value: WorksheetDetail | WorksheetRevision }>()
const resultLabel = { NORMAL: '正常（人工填写）', ATTENTION: '建议关注（人工填写）', REQUIRES_ACTION: '需处理（人工填写）' }
</script>
<template>
 <div class="worksheet-facts">
  <dl><div><dt>绑定资产 / 保存时登记名称</dt><dd>{{ value.asset_name }}<small>{{ value.asset_id }}</small></dd></div><div><dt>冻结模板</dt><dd>{{ value.template_name }} · 模板 v{{ value.template_version }}<small>{{ value.template_device_type }} · 适用性未核验</small></dd></div><div><dt>自述巡检时间</dt><dd>{{ value.reported_performed_at === null ? '未填写（null）' : displayTime(value.reported_performed_at) }}<small v-if="value.reported_performed_at">原 UTC：{{ value.reported_performed_at }}</small></dd></div><div><dt>系统记录时间（不是巡检时间）</dt><dd>{{ displayTime(value.recorded_at) }}</dd></div></dl>
  <p>已填 {{ value.summary.answered }} / {{ value.summary.total }} 项 · 未填 {{ value.summary.unanswered }} 项 · 正常 {{ value.summary.normal }} / 建议关注 {{ value.summary.attention }} / 需处理 {{ value.summary.requires_action }}</p>
  <p class="warning">全部为人工自述，尚未核验；填写完整不表示已经真实巡检、设备健康或报告已发布。证据文件绑定尚未实现。</p>
  <ol><li v-for="item in value.template_items" :key="item.key"><strong>{{ item.label }}</strong><small>{{ item.section }} · {{ item.key }}</small><p>{{ item.instruction === null ? '检查说明未填写' : item.instruction === '' ? '明确空说明' : item.instruction }}</p><template v-for="answer in value.observations.filter(row=>row.key===item.key)" :key="answer.key"><b>{{ resultLabel[answer.result] }}</b><p>{{ answer.note === null ? '备注未填写（null）' : answer.note === '' ? '明确空备注' : answer.note }}</p></template><p v-if="!value.observations.some(row=>row.key===item.key)" class="unanswered">此项未填写，不算正常</p></li></ol>
  <details><summary>查看绑定摘要</summary><p class="hash">模板 SHA256：{{ value.template_content_sha256 }}</p><p class="hash">执行单 SHA256：{{ value.content_sha256 }}</p></details>
 </div>
</template>
<style scoped>
.worksheet-facts{min-width:0}.worksheet-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.worksheet-facts dt{font-size:12px;color:#65758a}.worksheet-facts dd{margin:5px 0;overflow-wrap:anywhere}.worksheet-facts small{display:block;font-size:12px;color:#617187;margin-top:5px;overflow-wrap:anywhere}.worksheet-facts ol{padding-left:24px}.worksheet-facts li{padding:14px 0;border-bottom:1px solid #e1e8f0;overflow-wrap:anywhere}.worksheet-facts li p{white-space:pre-wrap;overflow-wrap:anywhere}.worksheet-facts .warning{color:#80601c}.worksheet-facts .unanswered{color:#68768a}.worksheet-facts .hash{font:12px monospace;overflow-wrap:anywhere}.worksheet-facts summary{cursor:pointer;min-height:44px;padding-top:12px}@media(max-width:600px){.worksheet-facts dl{grid-template-columns:1fr}}
</style>
