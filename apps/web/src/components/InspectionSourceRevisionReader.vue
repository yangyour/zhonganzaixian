<script setup lang="ts">
import {onUnmounted,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {createInspectionSourceReader,emptyInspectionSourceReadState,type InspectionSourceTarget} from '../utils/inspectionSourceRevision'
import type {WorksheetContext} from '../utils/inspectionWorksheets'
import {displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
import InspectionWorksheetFacts from './InspectionWorksheetFacts.vue'
const props=defineProps<{context:WorksheetContext|null;target:InspectionSourceTarget}>()
const emit=defineEmits<{close:[]}>()
const state=shallowRef(emptyInspectionSourceReadState()),reader=createInspectionSourceReader(api,v=>{state.value=v})
watch(()=>JSON.stringify([props.context,props.target]),()=>{reader.reset(props.context);if(props.context)void reader.load(props.target)},{immediate:true,flush:'sync'})
onUnmounted(()=>reader.dispose())
</script>
<template>
 <section class="source-revision-reader" aria-label="明确版本来源追溯" :aria-busy="state.busy">
  <div class="source-heading"><h2>来源追溯 · 指定 v{{ target.version }}</h2><button type="button" @click="emit('close')">关闭来源追溯</button></div>
  <p class="source-id">执行单：{{ target.worksheetId }}</p><p class="source-warning">核对所选历史修订版本快照（不影响当前编辑草稿；摘要校验用于确认记录完整性）。</p>
  <button type="button" :disabled="state.busy||!context" @click="reader.load(target)">重新读取所选版本</button>
  <p v-if="state.busy" role="status">正在读取并校验模板、执行单及来源快照摘要…</p>
  <ApiErrorNotice :error="state.error" />
  <template v-if="state.data"><p class="source-verification">模板、执行单与完整来源 SHA256 已一致性核对（人工填报内容待复核）</p><dl class="source-meta"><div><dt>所选修订状态</dt><dd>{{ state.data.archived?'该修订已归档':'该修订未归档' }} · {{ state.data.action }}</dd></div><div><dt>原创建人 / 创建时间</dt><dd>{{ state.data.created_by }}<small>{{ displayTime(state.data.created_at) }}</small></dd></div><div><dt>本修订记录人 / 记录时间</dt><dd>{{ state.data.recorded_by }}<small>{{ displayTime(state.data.recorded_at) }}</small></dd></div></dl><p class="source-hash">完整来源 SHA256：{{ state.data.source_snapshot_sha256 }}</p><InspectionWorksheetFacts :value="state.data" /></template>
  <p v-else-if="!state.busy&&!state.error" class="source-warning">暂未加载修订版本记录</p>
 </section>
</template>
<style scoped>
.source-revision-reader{min-width:0;padding:20px;border:1px solid #cbd8e8;border-radius:12px;margin-top:18px;background:#fff}.source-heading{display:flex;gap:12px;flex-wrap:wrap;align-items:center;justify-content:space-between}.source-heading h2{font-size:18px;margin:0}.source-revision-reader button{min-height:44px;padding:9px 14px;border:1px solid #cbd7e5;border-radius:8px;background:white;color:#294665;font:inherit;cursor:pointer;transition:background-color .15s ease,border-color .15s ease}.source-revision-reader button:hover:not(:disabled){background:#f2f7ff;border-color:#8cb8f6}.source-revision-reader button:disabled{opacity:.5;cursor:not-allowed}.source-id,.source-hash,.source-meta dd{overflow-wrap:anywhere}.source-hash{font:12px/1.6 "Cascadia Code",Consolas,monospace;background:#f8fafd;padding:8px 10px;border-radius:6px;border:1px solid #edf2f8}.source-warning{color:#80601c}.source-verification{color:#31597a}.source-meta{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.source-meta>div{padding:10px 12px;background:#f8fafd;border:1px solid #e7edf5;border-radius:6px}.source-meta dt{font-size:12px;color:#65758a}.source-meta dd{margin:4px 0 0;font-variant-numeric:tabular-nums}.source-meta small{display:block;margin-top:4px;color:#65758a}@media(max-width:760px){.source-revision-reader{padding:14px}.source-meta{grid-template-columns:1fr}}
</style>
