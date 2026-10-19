<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import type {ChangeNoticeDetail,ChangeNoticeRevision} from '../utils/changeNoticeDrafts'
import {displayTime} from '../utils/display'
const props=defineProps<{value:ChangeNoticeDetail|ChangeNoticeRevision}>()
const targetPage=ref(0),pages=computed(()=>Math.ceil(props.value.asset_ids.length/10)),targets=computed(()=>props.value.asset_ids.slice(targetPage.value*10,targetPage.value*10+10))
watch(()=>('id' in props.value?props.value.id:props.value.notice_id)+'/'+props.value.version,()=>{targetPage.value=0})
</script>
<template>
 <div class="change-notice-facts">
  <dl><div><dt>版本 / 记录时间（Asia/Shanghai）</dt><dd>v{{value.version}} · {{displayTime(value.recorded_at)}}</dd></div><div><dt>申请人 / 本版记录人</dt><dd>{{value.applicant_id}}<span>{{value.recorded_by}}</span></dd></div><div><dt>计划开始（Asia/Shanghai）</dt><dd>{{displayTime(value.planned_start_at)}}<span>UTC：{{value.planned_start_at}}</span></dd></div><div><dt>计划结束（Asia/Shanghai）</dt><dd>{{value.planned_end_at===null?'未指定（没有默认窗口）':displayTime(value.planned_end_at)}}<span v-if="value.planned_end_at">UTC：{{value.planned_end_at}}</span></dd></div><div><dt>关联工单引用</dt><dd>{{value.ticket_id??'未关联'}}<span>工单关联参考（待审批确认）</span></dd></div><div><dt>审批与执行状态</dt><dd>待提交审批 · 匹配规则待配置 · 暂未生效 · 暂不抑制告警</dd></div><div class="full-width"><dt>内容校验摘要 (SHA-256)</dt><dd class="mono">{{value.content_sha256}}<span>用于校验当前草稿内容完整性</span></dd></div></dl>
  <h3>关联变更设备 · {{value.asset_count}} 台</h3><p>当前租户设备引用（实际变更窗口执行状态待关联）</p>
  <ol :start="targetPage*10+1" class="target-references"><li v-for="id in targets" :key="id">{{id}}</li></ol>
  <div v-if="pages>1" class="target-pages"><button type="button" :disabled="targetPage===0" @click="targetPage--">上一组目标</button><span>第{{targetPage+1}} / {{pages}}组 · 每组10个</span><button type="button" :disabled="targetPage+1>=pages" @click="targetPage++">下一组目标</button></div>
  <h3>变更摘要</h3><p>按填报内容保存，待进入正式审批流程</p><pre>{{value.change_summary}}</pre>
 </div>
</template>
<style scoped>
.change-notice-facts{min-width:0}.change-notice-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.change-notice-facts dl>div{padding:10px 12px;background:#f8fafd;border:1px solid #e7edf5;border-radius:6px}.change-notice-facts .full-width{grid-column:1/-1}.change-notice-facts dt{font-size:12px;color:#607086}.change-notice-facts dd{margin:4px 0 0;overflow-wrap:anywhere;line-height:1.65;font-variant-numeric:tabular-nums}.change-notice-facts dd span{display:block;font-size:12px;color:#607086;margin-top:2px}.change-notice-facts h3{font-size:14px;margin:18px 0 6px}.change-notice-facts p{color:#607086;line-height:1.65;font-size:13px}.target-references{padding-left:28px;overflow-wrap:anywhere;margin:8px 0}.target-references li{padding:6px 0;font:12px/1.6 "Cascadia Code",Consolas,monospace;border-bottom:1px dashed #edf2f8}.target-references li:last-child{border-bottom:0}.target-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:10px;font-variant-numeric:tabular-nums}.change-notice-facts button{min-height:44px;border:1px solid #c6d4e6;border-radius:4px;background:#fff;padding:6px 12px;color:#1d5bbf;cursor:pointer;transition:background-color .15s ease,border-color .15s ease}.change-notice-facts button:hover:not(:disabled){background:#f0f6ff;border-color:#8cb8f6}.change-notice-facts button:disabled{opacity:.55;cursor:not-allowed}.change-notice-facts pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font:inherit;font-size:13px;background:#f5f8fc;border:1px solid #e6edf6;padding:12px;border-radius:6px;margin:6px 0 0}.change-notice-facts button:focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:700px){.change-notice-facts dl{grid-template-columns:1fr}}
</style>
