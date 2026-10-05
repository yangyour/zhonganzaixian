<script setup lang="ts">
import {computed,onMounted,onUnmounted,reactive,ref,shallowRef,useId,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import type {Asset,AssetType} from '../api/types'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {assetTypes} from '../utils/display'
import {createAssetDraftWriter,emptyAssetDraftState,type AssetDraftState} from '../utils/assetDraftWriter'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{sites?:readonly string[]}>(),emit=defineEmits<{created:[asset:Asset];close:[]}>()
const fresh=()=>({name:'',type:'switch' as AssetType,management_ip:'',site_id:''})
const form=reactive(fresh()),dirty=ref(false),acknowledge=ref(false),confirming=ref(false),state=shallowRef<AssetDraftState>(emptyAssetDraftState())
const writer=createAssetDraftWriter(api,next=>{state.value=next}),siteListId=useId()
let disposed=false
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['padmin','msp','tadmin'].includes(session.value.user.role)&&can('assets:write')&&!switchingTenant.value))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value]))
const locked=computed(()=>!permitted.value||state.value.busy||confirming.value||state.value.attempt!==null)
watch(contextKey,()=>{Object.assign(form,fresh());dirty.value=false;acknowledge.value=false;confirming.value=false;const current=session.value;writer.reset(!disposed&&permitted.value&&current?{tenantId:current.tenant.id,actorId:current.user.id,permissionVersion:current.permission_version,role:current.user.role,canWrite:true}:null)},{immediate:true,flush:'sync'})
function change(field:keyof typeof form,value:string){if(disposed||locked.value)return false;if(field==='type'&&!assetTypes.some(t=>t.value===value))return false;Object.assign(form,{[field]:value});dirty.value=true;acknowledge.value=false;return true}
function ack(value:boolean){if(disposed||locked.value)return false;acknowledge.value=value;return true}
const value=(event:Event)=>(event.target as HTMLInputElement).value
async function submit(){if(disposed||locked.value||!acknowledge.value)return false;const turn=contextKey.value,done=await writer.submit({...form});if(done&&!disposed&&turn===contextKey.value&&state.value.receipt){dirty.value=false;emit('created',structuredClone(state.value.receipt))}return done}
async function retry(){if(disposed||!permitted.value||state.value.busy||confirming.value)return false;const turn=contextKey.value,done=await writer.retry();if(done&&!disposed&&turn===contextKey.value&&state.value.receipt){dirty.value=false;emit('created',structuredClone(state.value.receipt))}return done}
function editRejected(){if(disposed||!permitted.value||confirming.value||state.value.attempt?.state!=='rejected'||!writer.begin())return false;acknowledge.value=false;dirty.value=true;return true}
async function canLeave(){
 if(disposed)return false
 if(writer.unresolved()){ElMessage.warning('资产创建原请求正在提交或结果未确认，请保留并重试完全相同的原请求');return false}
 if(confirming.value)return false
 if(!dirty.value||state.value.attempt?.state==='accepted')return true
 const turn=contextKey.value;confirming.value=true
 try{await ElMessageBox.confirm('离开将清除本页尚未保存的资产输入，是否继续？','离开资产草稿',{confirmButtonText:'清除并离开',cancelButtonText:'继续编辑',type:'warning'});return !disposed&&turn===contextKey.value&&!writer.unresolved()}
 catch{return false}finally{if(!disposed&&turn===contextKey.value)confirming.value=false}
}
async function close(){if(await canLeave())emit('close')}
function beforeUnload(event:BeforeUnloadEvent){if(writer.unresolved()||dirty.value&&state.value.attempt?.state!=='accepted'){event.preventDefault();event.returnValue=''}}
onBeforeRouteLeave(canLeave);const unregister=contextTransitionGuards.register(canLeave)
onMounted(()=>window.addEventListener('beforeunload',beforeUnload))
onUnmounted(()=>{disposed=true;unregister();window.removeEventListener('beforeunload',beforeUnload);writer.dispose()})
defineExpose({state,form,acknowledge,change,ack,submit,retry,editRejected,canLeave,hasUnresolved:writer.unresolved})
</script>
<template>
 <section class="asset-draft-create">
  <p class="draft-boundary">仅创建当前租户的手工资产草稿，不自动接入监控。站点须已登记，服务器会重新核验归属</p>
  <p v-if="!permitted" role="alert">当前会话无资产创建权限，或正在切换租户；原范围表单已清除</p>
  <template v-else>
   <form @submit.prevent="submit"><fieldset :disabled="locked"><legend>明确资产资料</legend>
    <label>资产名称<input aria-label="资产名称" :value="form.name" maxlength="100" required @input="change('name',value($event))" /></label>
    <label>设备类型<select aria-label="设备类型" :value="form.type" @change="change('type',value($event))"><option v-for="type in assetTypes" :key="type.value" :value="type.value">{{type.label}}</option></select></label>
    <label>管理 IP<input aria-label="管理 IP" :value="form.management_ip" maxlength="45" required placeholder="IPv4 或 IPv6 字面量" @input="change('management_ip',value($event))" /></label>
    <label>站点 ID<input aria-label="站点 ID" :value="form.site_id" :list="siteListId" maxlength="64" required @input="change('site_id',value($event))" /><datalist :id="siteListId"><option v-for="site in props.sites||[]" :key="site" :value="site" /></datalist></label>
    <label class="draft-ack"><input type="checkbox" aria-label="确认仅创建资产草稿" :checked="acknowledge" @change="ack(($event.target as HTMLInputElement).checked)" />已核对资料，确认仅创建草稿，不代表监控已接入</label>
   </fieldset><button type="submit" :disabled="locked||!acknowledge">{{state.busy?'正在提交原请求…':'保存草稿'}}</button></form>
   <ApiErrorNotice :error="state.error" />
   <section v-if="state.attempt" class="original-create-request" aria-label="资产创建原请求">
    <h3>{{state.attempt.state==='uncertain'?'原请求结果未确认':state.attempt.state==='accepted'?'原创建回执已确认':'本次创建请求已明确拒绝'}}</h3>
    <p v-if="state.attempt.state==='uncertain'">不要换名称或另起请求。请使用下面的原请求重试；列表回读或后续409不能确认这次原结果</p>
    <p class="draft-memory-warning">原请求仅保留在本页内存，请勿刷新或强制关闭浏览器；尚不支持跨刷新自动恢复</p>
    <p class="request-key">原请求标识：{{state.attempt.key}}</p><pre>{{state.attempt.payload}}</pre>
    <button v-if="state.attempt.state==='uncertain'" type="button" :disabled="state.busy||confirming" @click="retry">重试完全相同的原请求</button>
    <button v-if="state.attempt.state==='rejected'" type="button" :disabled="confirming" @click="editRejected">重新编辑后发起新请求</button>
   </section>
   <p v-if="state.receipt" role="status">已确认创建资产 {{state.receipt.name}}（{{state.receipt.id}}），仍为未采集草稿</p>
  </template>
  <button type="button" class="close-create" :disabled="state.busy||confirming" @click="close">关闭</button>
 </section>
</template>
<style scoped>
.asset-draft-create{display:grid;gap:16px;min-width:0}.draft-boundary{margin:0;line-height:1.7;color:#4c647d}.asset-draft-create fieldset{margin:0 0 16px;border:1px solid #d4dfeb;border-radius:8px;padding:16px;display:grid;gap:16px}.asset-draft-create legend{padding:0 6px;color:#536b82}.asset-draft-create label{display:grid;gap:7px;color:#344d65}.asset-draft-create input:not([type=checkbox]),.asset-draft-create select,.asset-draft-create button{min-height:44px;border:1px solid #aebed0;border-radius:6px;padding:8px 12px;font:inherit;max-width:100%;background:#fff;color:#29435e}.asset-draft-create input:not([type=checkbox]),.asset-draft-create select{width:100%;box-sizing:border-box}.asset-draft-create button{cursor:pointer}.asset-draft-create button:disabled{cursor:not-allowed;color:#7b8998;background:#f3f5f8}.asset-draft-create .draft-ack{display:flex;align-items:flex-start;line-height:1.6}.draft-ack input{width:18px;height:18px;flex-shrink:0}.original-create-request{padding:16px;border:1px solid #cdd9e6;border-radius:8px;background:#f8fafc}.original-create-request h3{margin:0 0 12px}.original-create-request p{line-height:1.7;font-size:13px}.original-create-request pre,.request-key{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}.draft-memory-warning{color:#855c17}.close-create{justify-self:end}.asset-draft-create :focus-visible{outline:3px solid #4c8fd5;outline-offset:3px}@media(max-width:760px){.asset-draft-create fieldset,.original-create-request{padding:12px}.asset-draft-create button{width:100%}}
</style>
