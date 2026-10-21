<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import type {BackupPlanDetail,BackupPlanRevision} from '../utils/backupPlanDrafts'
import BackupSchedulePreview from './BackupSchedulePreview.vue'
import {displayTime} from '../utils/display'
const props=defineProps<{value:BackupPlanDetail|BackupPlanRevision}>()
const targetPage=ref(0),pages=computed(()=>Math.ceil(props.value.target_asset_ids.length/10)),targets=computed(()=>props.value.target_asset_ids.slice(targetPage.value*10,targetPage.value*10+10))
watch(()=>('id' in props.value?props.value.id:props.value.plan_id)+'/'+props.value.version,()=>{targetPage.value=0})
</script>
<template>
 <div class="backup-plan-facts">
  <dl><div><dt>版本 / 记录时间</dt><dd>v{{value.version}} · {{displayTime(value.recorded_at)}}</dd></div><div><dt>每日时间 / 业务时区</dt><dd>{{value.daily_local_time}} · Asia/Shanghai</dd></div><div><dt>启用意向</dt><dd>{{value.requested_enabled?'后续申请启用':'暂不申请启用'}}（当前未启用）</dd></div><div><dt>重试策略预设</dt><dd>2次 / 间隔10分钟（候选默认配置，待启用）</dd></div><div><dt>凭据引用</dt><dd>{{value.credential_ref??'未提供引用'}}<span>仅保存引用标识，未建立实际凭据连接</span></dd></div><div><dt>执行状态</dt><dd>草稿待审批启用 · 暂不执行自动调度 · 下次执行时间待定</dd></div></dl>
  <h3>关联目标设备 · {{value.target_count}} 台</h3><p>当前租户设备引用（设备在线状态与备份连通性待启用后校验）</p>
  <ol :start="targetPage*10+1" class="target-references"><li v-for="id in targets" :key="id">{{id}}</li></ol>
  <div v-if="pages>1" class="target-pages"><button type="button" :disabled="targetPage===0" @click="targetPage--">上一组目标</button><span>第{{targetPage+1}} / {{pages}}组 · 每组10个</span><button type="button" :disabled="targetPage+1>=pages" @click="targetPage++">下一组目标</button></div>
  <BackupSchedulePreview v-if="'id' in value" :plan="value" />
  <h3>备注说明</h3><p v-if="value.note===null">未填写</p><p v-else-if="value.note===''">无备注（置空）</p><template v-else><pre>{{value.note}}</pre></template>
 </div>
</template>
<style scoped>
.backup-plan-facts{min-width:0}.backup-plan-facts dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.backup-plan-facts dl>div{padding:10px 12px;background:#f8fafd;border:1px solid #e7edf5;border-radius:6px}.backup-plan-facts dt{font-size:12px;color:#607086}.backup-plan-facts dd{margin:4px 0 0;overflow-wrap:anywhere;line-height:1.65;font-variant-numeric:tabular-nums}.backup-plan-facts dd span{display:block;font-size:12px;color:#607086;margin-top:2px}.backup-plan-facts h3{font-size:14px;margin:18px 0 6px}.backup-plan-facts p{color:#607086;line-height:1.65;font-size:13px}.target-references{padding-left:28px;overflow-wrap:anywhere;margin:8px 0}.target-references li{padding:6px 0;font:12px/1.6 "Cascadia Code",Consolas,monospace;border-bottom:1px dashed #edf2f8}.target-references li:last-child{border-bottom:0}.target-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:10px;font-variant-numeric:tabular-nums}.backup-plan-facts button{min-height:44px;border:1px solid #c6d4e6;border-radius:4px;background:#fff;padding:6px 12px;color:#1d5bbf;cursor:pointer;transition:background-color .15s ease,border-color .15s ease}.backup-plan-facts button:hover:not(:disabled){background:#f0f6ff;border-color:#8cb8f6}.backup-plan-facts button:disabled{opacity:.55;cursor:not-allowed}.backup-plan-facts pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font:inherit;font-size:13px;background:#f5f8fc;border:1px solid #e6edf6;padding:12px;border-radius:6px;margin:6px 0 0}.backup-plan-facts button:focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:700px){.backup-plan-facts dl{grid-template-columns:1fr}}
</style>
