<script setup lang="ts">
import {computed,onMounted,onUnmounted,reactive,ref,shallowRef,useId,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
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
 if(writer.unresolved()){ElMessage.warning('资产创建请求正在处理或状态待确认，请先完成重试或核对');return false}
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
  
  <p v-if="!permitted" role="alert">当前会话无资产创建权限，或正在切换租户；原范围表单已清除</p>
  <template v-else>
   <form v-form-feedback @submit.prevent="submit"><fieldset class="draft-fields" :disabled="locked"><legend>基础资产资料</legend>
    <label>资产名称<input aria-label="资产名称" :value="form.name" maxlength="100" required placeholder="输入设备名称，如：核心交换机-01" @input="change('name',value($event))" /></label>
    <label>设备类型<select aria-label="设备类型" :value="form.type" @change="change('type',value($event))"><option v-for="type in assetTypes" :key="type.value" :value="type.value">{{type.label}}</option></select></label>
    <label>管理 IP<input aria-label="管理 IP" :value="form.management_ip" maxlength="45" required placeholder="输入 IPv4 或 IPv6 地址" @input="change('management_ip',value($event))" /></label>
    <label>站点 ID<input aria-label="站点 ID" :value="form.site_id" :list="siteListId" maxlength="64" required placeholder="选择或输入站点 ID" @input="change('site_id',value($event))" /><datalist :id="siteListId"><option v-for="site in props.sites||[]" :key="site" :value="site" /></datalist></label>
    <label class="draft-ack"><input type="checkbox" aria-label="确认仅创建资产草稿" :checked="acknowledge" @change="ack(($event.target as HTMLInputElement).checked)" />已核对资产基础信息，确认先创建台账草稿（后续按流程接入监控）</label>
   </fieldset><div class="create-actions"><button type="button" class="close-create" :disabled="state.busy||confirming" @click="close">关闭</button><button type="submit" class="submit-create" :disabled="locked||!acknowledge">{{state.busy?'正在提交…':'保存资产草稿'}}</button></div></form>
   <ApiErrorNotice :error="state.error" />
   <section v-if="state.attempt" class="original-create-request" aria-label="资产创建原请求">
    <h3>{{state.attempt.state==='uncertain'?'提交状态待确认':state.attempt.state==='accepted'?'资产草稿已创建':'创建请求未通过'}}</h3>
    <p v-if="state.attempt.state==='uncertain'">提交状态待确认：请先刷新台账列表核对是否已创建成功，或点击“重试提交”继续处理。</p>
    <p class="draft-memory-warning">刷新或关闭页面前请先完成确认，尚不支持跨刷新自动恢复。</p>
    <details class="request-payload-details"><summary class="request-key">请求参数详情（幂等标识：{{state.attempt.key}}）</summary><pre>{{state.attempt.payload}}</pre></details>
    <button v-if="state.attempt.state==='uncertain'" type="button" :disabled="state.busy||confirming" @click="retry">重试提交</button>
    <button v-if="state.attempt.state==='rejected'" type="button" :disabled="confirming" @click="editRejected">修改参数并重新提交</button>
   </section>
   <p v-if="state.receipt" role="status" class="create-receipt-status">已创建资产草稿 {{state.receipt.name}}（{{state.receipt.id}}），待接入监控</p>
  </template>
  <button v-if="!permitted" type="button" class="close-create" :disabled="state.busy||confirming" @click="close">关闭</button>
 </section>
</template>
<style scoped>
.asset-draft-create .create-actions{display:flex;justify-content:flex-end;gap:8px;flex-wrap:wrap;margin-top:4px;padding-top:14px;border-top:1px solid #edf1f7}.asset-draft-create fieldset.draft-fields{margin:0;padding:0;border:0;background:none;gap:14px}.asset-draft-create .draft-fields>legend{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}
.asset-draft-create{display:grid;gap:16px;min-width:0}.draft-boundary{margin:0;line-height:1.7;color:#4c647d;font-size:13px}.asset-draft-create fieldset{margin:0 0 16px;border:1px solid #d4dfeb;border-radius:8px;padding:16px;display:grid;gap:16px;background:#fcfdff}.asset-draft-create legend{padding:0 6px;color:#3d556d;font-weight:600;font-size:13px}.asset-draft-create label{display:grid;gap:7px;color:#344d65;font-size:13px;font-weight:500}.asset-draft-create input:not([type=checkbox]),.asset-draft-create select,.asset-draft-create button{min-height:44px;border:1px solid #aebed0;border-radius:6px;padding:8px 12px;font:inherit;max-width:100%;background:#fff;color:#29435e;transition:border-color .15s,background-color .15s,box-shadow .15s}.asset-draft-create input:not([type=checkbox]):hover:not(:disabled),.asset-draft-create select:hover:not(:disabled){border-color:#7d97b5}.asset-draft-create input:not([type=checkbox]),.asset-draft-create select{width:100%;box-sizing:border-box}.asset-draft-create button{cursor:pointer;font-weight:500}.asset-draft-create .submit-create:not(:disabled){background:#2563eb;border-color:#2563eb;color:#fff}.asset-draft-create .submit-create:hover:not(:disabled){background:#1d4ed8;border-color:#1d4ed8}.asset-draft-create button:disabled{cursor:not-allowed;color:#7b8998;background:#f3f5f8;border-color:#d8e0e8}.asset-draft-create .draft-ack{display:flex;align-items:flex-start;gap:8px;line-height:1.6;font-weight:400}.draft-ack input{width:18px;height:18px;margin-top:2px;flex-shrink:0}.original-create-request{padding:16px;border:1px solid #cdd9e6;border-radius:8px;background:#f8fafc;display:grid;gap:10px}.original-create-request h3{margin:0;font-size:15px}.original-create-request p{margin:0;line-height:1.7;font-size:13px}.request-payload-details{font-size:12px;color:#536b82}.request-payload-details summary{cursor:pointer}.original-create-request pre,.request-key{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}.draft-memory-warning{color:#855c17}.create-receipt-status{margin:0;padding:10px 12px;border-radius:6px;background:#f0fdf4;border:1px solid #bbf7d0;color:#166534;font-size:13px}.close-create{justify-self:end}.asset-draft-create :focus-visible{outline:2px solid #2563eb;outline-offset:2px}@media(max-width:760px){.asset-draft-create fieldset,.original-create-request{padding:12px}.asset-draft-create button{width:100%}}
</style>
