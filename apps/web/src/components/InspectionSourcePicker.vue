<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {inspectionMaxVersion,inspectionUuid} from '../utils/inspectionTemplates'
import {createInspectionSourceReader,emptyInspectionSourceReadState,type InspectionSourceRevision} from '../utils/inspectionSourceRevision'
import type {SourceCollectionContext} from '../utils/inspectionSourceCollections'
import ApiErrorNotice from './ApiErrorNotice.vue'
import InspectionWorksheetFacts from './InspectionWorksheetFacts.vue'
const props=defineProps<{context:SourceCollectionContext|null;disabled:boolean;selectedKeys:string[]}>(),emit=defineEmits<{add:[source:InspectionSourceRevision];dirty:[value:boolean]}>()
const worksheetId=ref(''),version=ref(''),validation=ref(''),state=shallowRef(emptyInspectionSourceReadState()),reader=createInspectionSourceReader(api,v=>{state.value=v})
function target(){const id=worksheetId.value.trim().toLowerCase(),text=version.value.trim();if(!inspectionUuid(id)||! /^[1-9][0-9]{0,15}$/.test(text)||!Number.isSafeInteger(Number(text))||Number(text)>inspectionMaxVersion)return null;return{worksheetId:id,version:Number(text)}}
const currentCandidate=computed(()=>{const t=target(),s=state.value.data;return s&&t&&s.worksheet_id===t.worksheetId&&s.version===t.version?s:null})
const duplicate=computed(()=>Boolean(currentCandidate.value&&props.selectedKeys.includes(currentCandidate.value.worksheet_id+'/'+currentCandidate.value.version)))
watch(()=>JSON.stringify(props.context),()=>{worksheetId.value='';version.value='';validation.value='';reader.reset(props.context)},{immediate:true,flush:'sync'})
watch([worksheetId,version],()=>{validation.value='';reader.reset(props.context);emit('dirty',Boolean(worksheetId.value||version.value))},{flush:'sync'})
watch(()=>props.disabled,disabled=>{if(disabled)reader.reset(props.context)},{flush:'sync'})
async function load(){if(props.disabled||!props.context)return false;const selected=target();if(!selected){reader.reset(props.context);validation.value='请输入执行单UUID和明确正整数修订号；不会自动查最新版本';return false}validation.value='';return reader.load(selected)}
function add(){const source=currentCandidate.value;if(props.disabled||!props.context||!source||duplicate.value||props.selectedKeys.length>=1000)return false;emit('add',structuredClone(source));worksheetId.value='';version.value='';reader.reset(props.context);return true}
onUnmounted(()=>reader.dispose())
</script>
<template>
 <section class="explicit-source-picker" aria-label="明确巡检修订选择">
  <h3>1. 指定并核对来源修订</h3><p>从执行单或修订历史取得ID和版本，必须手动选择；不自动查最新版本，不按设备合并。</p>
  <form v-form-feedback @submit.prevent="load"><fieldset :disabled="disabled||!context"><div class="source-inputs"><label>执行单ID<input v-model="worksheetId" aria-label="集合来源执行单ID" autocomplete="off" spellcheck="false" /></label><label>明确修订号<input v-model="version" aria-label="集合来源明确修订号" inputmode="numeric" autocomplete="off" /></label></div><button type="submit" :disabled="state.busy">读取并核对这一修订</button></fieldset></form>
  <p v-if="validation" class="warning" role="alert">{{ validation }}</p><ApiErrorNotice :error="state.error" /><p v-if="state.busy" role="status">正在核对模板、执行单和完整来源摘要；旧内容已清除</p>
  <section v-if="currentCandidate" class="verified-candidate"><h4>{{ currentCandidate.asset_name }} · 执行单 v{{ currentCandidate.version }}</h4><p>三层摘要一致性已核对，人工事实仍未核验 · {{ currentCandidate.archived?'所选修订已归档':'所选修订未归档' }}</p><p class="hash">{{ currentCandidate.source_snapshot_sha256 }}</p><details><summary>核对来源人工内容</summary><InspectionWorksheetFacts :value="currentCandidate" /></details><p v-if="duplicate" class="warning">这一执行单修订已在明确选择中，不会重复添加</p><button type="button" :disabled="disabled||duplicate||selectedKeys.length>=1000" @click="add">加入明确选择</button></section>
  <p v-else-if="!state.busy&&!state.error&&!validation" class="hint">尚无可加入的已核对来源；修改ID或版本会清除旧核对结果</p>
 </section>
</template>
<style scoped>
.explicit-source-picker{min-width:0}.explicit-source-picker h3{font-size:16px}.source-inputs{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,1fr);gap:14px}.explicit-source-picker label{display:grid;gap:7px;min-width:0}.explicit-source-picker input{width:100%;min-height:44px;padding:10px;border:1px solid #bccbdc;border-radius:7px;box-sizing:border-box;font:inherit}.explicit-source-picker fieldset{padding:0;border:0;min-width:0}.explicit-source-picker button,.explicit-source-picker summary{min-height:44px;cursor:pointer}.explicit-source-picker button{padding:9px 14px;margin-top:12px;border:1px solid #cbd7e5;border-radius:8px;background:#fff;color:#294665;font:inherit}.explicit-source-picker button:disabled{opacity:.5;cursor:not-allowed}.verified-candidate{margin-top:14px;border:1px solid #dce5f0;border-radius:10px;padding:14px}.verified-candidate h4{margin:0;overflow-wrap:anywhere}.hash{font:12px monospace;overflow-wrap:anywhere;line-height:1.6}.warning{color:#80551c}.hint{color:#64758a;font-size:13px}@media(max-width:760px){.source-inputs{grid-template-columns:1fr}.verified-candidate{padding:12px}}
</style>
