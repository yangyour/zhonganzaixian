<script setup lang="ts">
import {computed,ref,watchEffect} from 'vue'
import {exportUuid} from '../utils/assetExportJobs'
import type {OnCallMemberCandidate} from '../utils/onCallMemberCandidates'
const props=defineProps<{modelValue:string;scopeKey:string;idPrefix:string;label:string;items:OnCallMemberCandidate[];busy:boolean;locked:boolean;required?:boolean;excludedId?:string}>()
const emit=defineEmits<{ 'update:modelValue':[value:string,scopeKey:string] }>()
const manual=ref<HTMLInputElement|null>(null)
const known=computed(()=>props.items.find(row=>row.id===props.modelValue.trim()))
const available=computed(()=>props.items.filter(row=>row.id!==props.excludedId?.trim()))
const invalid=computed(()=>props.modelValue.length>0&&(!exportUuid(props.modelValue.trim())||props.modelValue.trim()===props.excludedId?.trim()))
const error=computed(()=>props.modelValue.trim()===props.excludedId?.trim()&&props.modelValue.length>0?'此人员与本字段要求区分的人员相同，请修改。':'请输入完整有效的人员UUID。')
watchEffect(()=>{manual.value?.setCustomValidity(invalid.value?error.value:'')})
function choose(event:Event){if(props.locked||props.busy)return;const value=(event.target as HTMLSelectElement).value;if(value===''||available.value.some(row=>row.id===value))emit('update:modelValue',value,props.scopeKey)}
function type(event:Event){if(!props.locked)emit('update:modelValue',(event.target as HTMLInputElement).value,props.scopeKey)}
</script>
<template>
 <fieldset class="member-picker">
  <legend>{{label}}{{required?'（必填）':'（可选）'}}</legend>
  <label :for="idPrefix+'-choice'">按姓名选择
   <select :id="idPrefix+'-choice'" :value="modelValue.trim()" :disabled="locked||busy||!available.length" :aria-describedby="idPrefix+'-hint'" @change="choose">
    <option value="">{{busy?'正在读取候选…':available.length?'请选择内部候选人员':'尚无可选候选，可手动输入UUID'}}</option>
    <option v-if="modelValue.trim()&&!available.some(row=>row.id===modelValue.trim())" :value="modelValue.trim()">保留当前UUID，未在本次可选候选中</option>
    <option v-for="row in available" :key="row.id" :value="row.id">{{row.display_name}} · {{row.role==='padmin'?'平台管理员':'服务工程师'}} · {{row.id}}</option>
   </select>
  </label>
  <label :for="idPrefix+'-uuid'">人员UUID（可手动填写）
   <input ref="manual" :id="idPrefix+'-uuid'" type="text" :value="modelValue" :disabled="locked" :required="required" :aria-invalid="invalid" :aria-describedby="idPrefix+'-hint'+(invalid?' '+idPrefix+'-error':'')" autocomplete="off" spellcheck="false" @input="type" />
  </label>
  <p :id="idPrefix+'-hint'">{{known?'当前匹配：'+known.display_name:'候选读取或变化不会清除当前UUID。'}}选择候选只填写本地表单，保存时仍由服务端核验。</p>
  <p v-if="invalid" :id="idPrefix+'-error'" role="alert">{{error}}</p>
 </fieldset>
</template>
<style scoped>
.member-picker{display:grid;gap:8px;min-width:0;margin:0;padding:12px;border:1px solid #8495aa;border-radius:5px}.member-picker legend{font-weight:600;padding:0 4px}.member-picker label{display:grid;gap:6px}.member-picker select,.member-picker input{box-sizing:border-box;width:100%;max-width:100%;min-width:0;min-height:44px;color:#26384f;background:#fff;border:1px solid #8495aa;padding:8px;font:inherit;border-radius:4px}.member-picker p{margin:0;font-size:13px;line-height:1.6;color:#46566c;overflow-wrap:anywhere}.member-picker [role=alert]{color:#9f2424}.member-picker :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.member-picker :disabled{color:#526176;background:#f1f5f9}
</style>
