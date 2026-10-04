<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import type {BackupPlanDetail,BackupPlanRevision} from '../utils/backupPlanDrafts'
import {displayTime} from '../utils/display'
const props=defineProps<{value:BackupPlanDetail|BackupPlanRevision}>()
const targetPage=ref(0),pages=computed(()=>Math.ceil(props.value.target_asset_ids.length/10)),targets=computed(()=>props.value.target_asset_ids.slice(targetPage.value*10,targetPage.value*10+10))
watch(()=>('id' in props.value?props.value.id:props.value.plan_id)+'/'+props.value.version,()=>{targetPage.value=0})
</script>
<template>
 <div class="backup-plan-facts">
  <dl><div><dt>版本 / 记录时间</dt><dd>v{{value.version}} · {{displayTime(value.recorded_at)}}</dd></div><div><dt>每日时间 / 固定业务时区</dt><dd>{{value.daily_local_time}} · Asia/Shanghai</dd></div><div><dt>启用意向</dt><dd>{{value.requested_enabled?'后续申请启用':'暂不申请启用'}}；实际仍未启用</dd></div><div><dt>重试建议</dt><dd>2次 / 间隔10分钟（合同可配置默认提案，未生效）</dd></div><div><dt>凭据引用</dt><dd>{{value.credential_ref??'未提供引用（null）'}}<span>仅引用，未核验；未配置凭据访问</span></dd></div><div><dt>执行状态</dt><dd>未启用 · 未批准 · 不执行SSH · 下次执行时间未知</dd></div></dl>
  <h3>明确目标引用 · {{value.target_count}} 个</h3><p>仅保存当前租户设备引用，不证明设备在线、SSH已授权或真实备份已完成</p>
  <ol :start="targetPage*10+1" class="target-references"><li v-for="id in targets" :key="id">{{id}}</li></ol>
  <div v-if="pages>1" class="target-pages"><button type="button" :disabled="targetPage===0" @click="targetPage--">上一组目标</button><span>第{{targetPage+1}} / {{pages}}组 · 每组10个</span><button type="button" :disabled="targetPage+1>=pages" @click="targetPage++">下一组目标</button></div>
  <h3>备注</h3><p v-if="value.note===null">未填写（null）</p><p v-else-if="value.note===''">明确空备注</p><template v-else><p>原文保留空白与换行</p><pre>{{value.note}}</pre></template>
 </div>
</template>
<style scoped>
.backup-plan-facts{min-width:0}.backup-plan-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.backup-plan-facts dt{font-size:12px;color:#607086}.backup-plan-facts dd{margin:5px 0 0;overflow-wrap:anywhere;line-height:1.65}.backup-plan-facts dd span{display:block;font-size:12px;color:#607086}.backup-plan-facts h3{font-size:15px;margin:18px 0 7px}.backup-plan-facts p{color:#607086;line-height:1.65;font-size:13px}.target-references{padding-left:28px;overflow-wrap:anywhere}.target-references li{padding:7px 0;font:13px monospace}.target-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.backup-plan-facts button{min-height:44px;padding:8px 12px;cursor:pointer}.backup-plan-facts pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font:inherit;font-size:13px;background:#f5f8fc;padding:12px;border-radius:8px}.backup-plan-facts button:focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:700px){.backup-plan-facts dl{grid-template-columns:1fr}}
</style>
