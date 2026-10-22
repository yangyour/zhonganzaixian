<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {inspectionMaxVersion,inspectionUuid} from '../utils/inspectionTemplates'
import {createInspectionSourceReader,emptyInspectionSourceReadState,type InspectionSourceRevision,type InspectionSourceTarget} from '../utils/inspectionSourceRevision'
import type {SourceCollectionContext} from '../utils/inspectionSourceCollections'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import {createWorksheetWorkspace,emptyWorksheetState} from '../utils/inspectionWorksheetWorkspace'
import type {WorksheetSummary} from '../utils/inspectionWorksheets'
import InspectionWorksheetFacts from './InspectionWorksheetFacts.vue'
const props=defineProps<{context:SourceCollectionContext|null;disabled:boolean;selectedKeys:string[]}>(),emit=defineEmits<{add:[source:InspectionSourceRevision];dirty:[value:boolean]}>()
const worksheetId=ref(''),version=ref(''),validation=ref(''),state=shallowRef(emptyInspectionSourceReadState()),reader=createInspectionSourceReader(api,v=>{state.value=v})
const pinnedTarget=shallowRef<InspectionSourceTarget|null>(null),candidates=shallowRef(emptyWorksheetState()),candidateScope=ref(0)
const candidateWorkspace=createWorksheetWorkspace(api,value=>{candidates.value=value})
const candidateLocked=computed(()=>props.disabled||!props.context||Boolean(candidates.value.busy)||state.value.busy)
const sourceScope=computed(()=>JSON.stringify([props.context,props.disabled,worksheetId.value,version.value,pinnedTarget.value]))
function resetCandidates(){candidateScope.value++;candidateWorkspace.reset(props.context?{...props.context,canWrite:false}:null)}
async function browseCandidates(next=false){if(props.disabled||!props.context||candidates.value.busy)return false;return candidateWorkspace.list('','CURRENT',next)}
async function chooseCandidate(row:WorksheetSummary){if(candidateLocked.value||!candidates.value.page?.items.includes(row))return false;worksheetId.value=row.id;version.value=String(row.version);pinnedTarget.value={worksheetId:row.id,version:row.version,contentSha256:row.content_sha256};return load()}
function target(){const id=worksheetId.value.trim().toLowerCase(),text=version.value.trim();if(!inspectionUuid(id)||! /^[1-9][0-9]{0,15}$/.test(text)||!Number.isSafeInteger(Number(text))||Number(text)>inspectionMaxVersion)return null;return{worksheetId:id,version:Number(text)}}
const currentCandidate=computed(()=>{const t=target(),s=state.value.data;return s&&t&&s.worksheet_id===t.worksheetId&&s.version===t.version?s:null})
const duplicate=computed(()=>Boolean(currentCandidate.value&&props.selectedKeys.includes(currentCandidate.value.worksheet_id+'/'+currentCandidate.value.version)))
watch(()=>JSON.stringify(props.context),()=>{pinnedTarget.value=null;worksheetId.value='';version.value='';validation.value='';reader.reset(props.context);resetCandidates()},{immediate:true,flush:'sync'})
watch([worksheetId,version],()=>{pinnedTarget.value=null;validation.value='';reader.reset(props.context);emit('dirty',Boolean(worksheetId.value||version.value))},{flush:'sync'})
watch(()=>props.disabled,disabled=>{if(disabled){reader.reset(props.context);resetCandidates()}},{flush:'sync'})
async function load(){if(props.disabled||!props.context)return false;const selected=target();if(!selected){reader.reset(props.context);validation.value='请输入巡检执行单 UUID 与正整数修订版本号';return false}validation.value='';const pinned=pinnedTarget.value;return reader.load(pinned&&pinned.worksheetId===selected.worksheetId&&pinned.version===selected.version?{...pinned}:selected)}
function add(){const source=currentCandidate.value;if(props.disabled||!props.context||!source||duplicate.value||props.selectedKeys.length>=1000)return false;emit('add',structuredClone(source));worksheetId.value='';version.value='';reader.reset(props.context);return true}
function inputSnapshot(){return JSON.stringify([worksheetId.value,version.value,pinnedTarget.value])}
defineExpose({inputSnapshot,browseCandidates,chooseCandidate,candidates})
onUnmounted(()=>{reader.dispose();candidateWorkspace.dispose()})
</script>
<template>
 <section class="explicit-source-picker" aria-label="明确巡检修订选择">
  <h3>1. 指定并核对来源修订</h3>
  <section class="source-candidates" aria-label="已保存执行单候选" :aria-busy="candidates.busy==='read'">
   <h4>从已保存执行单选择</h4><p class="hint">当前租户未归档执行单，每页 15 条；历史或归档修订可在下方手工指定。</p>
   <div class="candidate-actions"><button type="button" :disabled="disabled||!context||Boolean(candidates.busy)" @click="browseCandidates(false)">读取执行单候选</button><button type="button" :disabled="disabled||!context||Boolean(candidates.busy)||!candidates.page?.has_more" @click="browseCandidates(true)">下一页执行单候选</button></div>
   <ReadRecoveryActions :busy="candidates.busy" :can-retry="!disabled&&candidateWorkspace.canRetryRead()" :scope-key="String(candidateScope)" :cancel="candidateWorkspace.cancelRead" :retry="candidateWorkspace.retryRead" label="执行单候选" />
   <ApiErrorNotice :error="candidates.error" /><p v-if="!candidates.page" class="hint">{{candidates.busy==='read'?'正在读取候选，数量未知':'尚无已核对候选页；读取失败或取消不代表没有执行单。'}}</p>
   <template v-else><div class="candidate-table-wrap" role="region" aria-label="执行单候选列表，可横向滚动" tabindex="0"><table><caption>{{candidates.paged?'后续候选页':'第一页候选'}} · 本页{{candidates.page.items.length}}条，非总数</caption><thead><tr><th scope="col">资产与执行单</th><th scope="col">冻结模板</th><th scope="col">人工填写</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="row in candidates.page.items" :key="row.id"><td>{{row.asset_name}}<small>{{row.id}} · 执行单 v{{row.version}}</small></td><td>{{row.template_name}}<small>模板 v{{row.template_version}}</small></td><td>已填{{row.summary.answered}}/{{row.summary.total}}<small>人工自述未核验</small></td><td><button type="button" :disabled="candidateLocked" :aria-label="'核对 '+row.asset_name+' 执行单 v'+row.version+' '+row.id" @click="chooseCandidate(row)">核对此修订</button></td></tr></tbody></table></div><p v-if="!candidates.page.items.length" class="hint">当前候选页为空，不代表没有历史或归档执行单。</p></template>
  </section>
  <form v-form-feedback @submit.prevent="load"><fieldset :disabled="disabled||!context"><div class="source-inputs"><label>执行单ID<input v-model="worksheetId" aria-label="集合来源执行单ID" autocomplete="off" spellcheck="false" /></label><label>修订版本号<input v-model="version" aria-label="集合来源明确修订号" inputmode="numeric" autocomplete="off" /></label></div><button type="submit" :disabled="state.busy">读取并核对版本</button></fieldset></form>
  <ReadRecoveryActions :busy="state.busy?'read':null" :can-retry="!disabled&&reader.canRetryRead()" :scope-key="sourceScope" :cancel="reader.cancelRead" :retry="reader.retryRead" label="来源核对" />
  <p v-if="validation" class="warning" role="alert">{{ validation }}</p><ApiErrorNotice :error="state.error" /><p v-if="state.busy" role="status">正在校验模板、执行单及来源快照摘要…</p>
  <section v-if="currentCandidate" class="verified-candidate"><h4>{{ currentCandidate.asset_name }} · 执行单 v{{ currentCandidate.version }}</h4><p>模板、执行单与快照摘要一致性校验通过 · {{ currentCandidate.archived?'所选修订已归档':'所选修订未归档' }}</p><p class="hash">{{ currentCandidate.source_snapshot_sha256 }}</p><details><summary>查看该版本填报明细</summary><InspectionWorksheetFacts :value="currentCandidate" /></details><p v-if="duplicate" class="warning">该执行单修订版本已添加至列表，请勿重复选择</p><button type="button" :disabled="disabled||duplicate||selectedKeys.length>=1000" @click="add">添加至集合</button></section>
  <p v-else-if="!state.busy&&!state.error&&!validation" class="hint">请先输入执行单 ID 与版本号并完成读取核对</p>
 </section>
</template>
<style scoped>
.source-candidates{margin:14px 0;padding:12px;border:1px solid #dce5f0;border-radius:8px}.candidate-actions{display:flex;gap:10px;flex-wrap:wrap}.candidate-table-wrap{max-width:100%;overflow:auto}.candidate-table-wrap table{width:100%;min-width:640px;border-collapse:collapse;table-layout:fixed}.candidate-table-wrap caption{text-align:left;padding:10px 0}.candidate-table-wrap th,.candidate-table-wrap td{text-align:left;padding:10px;border-bottom:1px solid #dce5f0;overflow-wrap:anywhere}.candidate-table-wrap small{display:block;font-size:12px;margin-top:6px}.explicit-source-picker :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.explicit-source-picker{min-width:0}.explicit-source-picker h3{font-size:16px}.source-inputs{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,1fr);gap:14px}.explicit-source-picker label{display:grid;gap:7px;min-width:0}.explicit-source-picker input{width:100%;min-height:44px;padding:10px;border:1px solid #dce4ee;border-radius:7px;box-sizing:border-box;font:inherit;transition:border-color .15s ease,box-shadow .15s ease}.explicit-source-picker input:focus{border-color:#2878f5;box-shadow:0 0 0 2px #2878f520;outline:none}.explicit-source-picker input:focus-visible{outline:3px solid #176bb5;outline-offset:3px}.explicit-source-picker fieldset{padding:0;border:0;min-width:0}.explicit-source-picker button,.explicit-source-picker summary{min-height:44px;cursor:pointer}.explicit-source-picker button{padding:9px 14px;margin-top:12px;border:1px solid #cbd7e5;border-radius:8px;background:#fff;color:#294665;font:inherit;transition:background-color .15s ease,border-color .15s ease}.explicit-source-picker button:hover:not(:disabled){background:#f2f7ff;border-color:#8cb8f6}.explicit-source-picker button:disabled{opacity:.5;cursor:not-allowed}.verified-candidate{margin-top:14px;border:1px solid #dce5f0;border-radius:10px;padding:14px;background:#fbfdff}.verified-candidate h4{margin:0;overflow-wrap:anywhere}.hash{font:12px/1.6 "Cascadia Code",Consolas,monospace;overflow-wrap:anywhere}.warning{color:#80551c}.hint{color:#64758a;font-size:13px}@media(max-width:760px){.source-inputs{grid-template-columns:1fr}.verified-candidate{padding:12px}}
</style>
