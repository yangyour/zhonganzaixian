<script setup lang="ts">
import type { InspectionDetail, InspectionRevision } from '../utils/inspectionTemplates'
import { displayTime } from '../utils/display'
defineProps<{ value: InspectionDetail | InspectionRevision }>()
</script>
<template>
 <div class="inspection-facts">
  <dl><div><dt>设备类型标识</dt><dd>{{ value.device_type }}</dd></div><div><dt>版本 / 检查项</dt><dd>v{{ value.version }} · {{ value.item_count }} 项</dd></div><div><dt>记录时间</dt><dd>{{ displayTime(value.recorded_at) }}</dd></div><div><dt>审批与发布状态</dt><dd>待审批 / 未发布</dd></div></dl>
  <p class="inspection-hash">内容 SHA256：{{ value.content_sha256 }}</p>
  <ol class="inspection-read-items"><li v-for="item in value.items" :key="item.key"><div><strong>{{ item.label }}</strong><span>{{ item.section }} · {{ item.key }}</span></div><p>{{ item.instruction === null ? '说明未填写' : item.instruction === '' ? '无说明（置空）' : item.instruction }}</p></li></ol>
  
 </div>
</template>
<style scoped>
.inspection-facts{min-width:0}.inspection-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:12px 0}.inspection-facts dl>div{padding:10px 12px;background:#f8fafd;border:1px solid #e7edf5;border-radius:6px}.inspection-facts dt{font-size:12px;color:#607086}.inspection-facts dd{margin:4px 0 0;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.inspection-hash{font:12px/1.6 "Cascadia Code",Consolas,monospace;overflow-wrap:anywhere;color:#65738a;background:#f8fafd;padding:8px 10px;border-radius:4px;border:1px solid #edf2f8}.inspection-read-items{padding-left:24px;margin:12px 0}.inspection-read-items li{padding:12px 8px;border-bottom:1px solid #e5eaf0;overflow-wrap:anywhere;transition:background-color .15s ease}.inspection-read-items li:hover{background:#f8fbff}.inspection-read-items li span{display:block;margin-top:4px;color:#607086;font-size:12px}.inspection-read-items li p{white-space:pre-wrap;margin:8px 0 0;overflow-wrap:anywhere;color:#425469}.inspection-note{color:#80641d;font-size:12px;margin-top:12px}@media(max-width:600px){.inspection-facts dl{grid-template-columns:1fr}}
</style>
