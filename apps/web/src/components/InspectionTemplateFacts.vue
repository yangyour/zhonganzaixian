<script setup lang="ts">
import type { InspectionDetail, InspectionRevision } from '../utils/inspectionTemplates'
import { displayTime } from '../utils/display'
defineProps<{ value: InspectionDetail | InspectionRevision }>()
</script>
<template>
 <div class="inspection-facts">
  <dl><div><dt>设备类型标识</dt><dd>{{ value.device_type }}</dd></div><div><dt>版本 / 检查项</dt><dd>v{{ value.version }} · {{ value.item_count }} 项</dd></div><div><dt>记录时间</dt><dd>{{ displayTime(value.recorded_at) }}</dd></div><div><dt>模板批准 / 发布</dt><dd>未核验 / 未实现发布</dd></div></dl>
  <p class="inspection-hash">内容 SHA256：{{ value.content_sha256 }}</p>
  <ol class="inspection-read-items"><li v-for="item in value.items" :key="item.key"><div><strong>{{ item.label }}</strong><span>{{ item.section }} · {{ item.key }}</span></div><p>{{ item.instruction === null ? '说明未填写（null）' : item.instruction === '' ? '明确空说明' : item.instruction }}</p></li></ol>
  <p class="inspection-note">这是冻结的模板定义，不是已完成巡检、设备健康结论或已发布报告</p>
 </div>
</template>
<style scoped>
.inspection-facts{min-width:0}.inspection-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:12px 0}.inspection-facts dt{font-size:12px;color:#607086}.inspection-facts dd{margin:4px 0 0;overflow-wrap:anywhere}.inspection-hash{font:12px monospace;overflow-wrap:anywhere;color:#65738a}.inspection-read-items{padding-left:24px}.inspection-read-items li{padding:12px 0;border-bottom:1px solid #e5eaf0;overflow-wrap:anywhere}.inspection-read-items li span{display:block;margin-top:4px;color:#607086;font-size:12px}.inspection-read-items li p{white-space:pre-wrap;margin:8px 0 0;overflow-wrap:anywhere}.inspection-note{color:#80641d;font-size:13px}@media(max-width:600px){.inspection-facts dl{grid-template-columns:1fr}}
</style>
