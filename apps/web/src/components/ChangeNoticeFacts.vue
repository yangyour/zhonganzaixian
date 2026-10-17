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
  <dl><div><dt>版本 / 记录时间（Asia/Shanghai）</dt><dd>v{{value.version}} · {{displayTime(value.recorded_at)}}</dd></div><div><dt>原申请人 / 本版记录人</dt><dd>{{value.applicant_id}}<span>{{value.recorded_by}}</span></dd></div><div><dt>计划开始（Asia/Shanghai）</dt><dd>{{displayTime(value.planned_start_at)}}<span>原UTC：{{value.planned_start_at}}</span></dd></div><div><dt>计划结束（Asia/Shanghai）</dt><dd>{{value.planned_end_at===null?'未指定（没有默认窗口）':displayTime(value.planned_end_at)}}<span v-if="value.planned_end_at">原UTC：{{value.planned_end_at}}</span></dd></div><div><dt>可选工单引用</dt><dd>{{value.ticket_id??'未关联'}}<span>引用存在不等于审批证据</span></dd></div><div><dt>审批 / 匹配 / 执行</dt><dd>待提交审批 · 匹配规则待配置 · 暂未生效 · 暂不抑制告警</dd></div><div class="full-width"><dt>内容SHA-256</dt><dd>{{value.content_sha256}}<span>已核对本提案内容，不是签名、批准或真实设备证明</span></dd></div></dl>
  <h3>明确目标引用 · {{value.asset_count}} 个</h3><p>仅保存当前租户设备引用，不证明设备在线、SSH已授权或变更已实际执行</p>
  <ol :start="targetPage*10+1" class="target-references"><li v-for="id in targets" :key="id">{{id}}</li></ol>
  <div v-if="pages>1" class="target-pages"><button type="button" :disabled="targetPage===0" @click="targetPage--">上一组目标</button><span>第{{targetPage+1}} / {{pages}}组 · 每组10个</span><button type="button" :disabled="targetPage+1>=pages" @click="targetPage++">下一组目标</button></div>
  <h3>变更摘要原文</h3><p>保留空白与换行，不作为审批结论</p><pre>{{value.change_summary}}</pre>
 </div>
</template>
<style scoped>
.change-notice-facts{min-width:0}.change-notice-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.change-notice-facts .full-width{grid-column:1/-1}.change-notice-facts dt{font-size:12px;color:#607086}.change-notice-facts dd{margin:5px 0 0;overflow-wrap:anywhere;line-height:1.65}.change-notice-facts dd span{display:block;font-size:12px;color:#607086}.change-notice-facts h3{font-size:15px;margin:18px 0 7px}.change-notice-facts p{color:#607086;line-height:1.65;font-size:13px}.target-references{padding-left:28px;overflow-wrap:anywhere}.target-references li{padding:7px 0;font:13px monospace}.target-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.change-notice-facts button{min-height:44px;border:1px solid #c6d4e6;border-radius:4px;background:#fff;padding:6px 12px;color:#1d5bbf;cursor:pointer}.change-notice-facts pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font:inherit;font-size:13px;background:#f5f8fc;padding:12px;border-radius:8px}.change-notice-facts button:focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:700px){.change-notice-facts dl{grid-template-columns:1fr}}
</style>
