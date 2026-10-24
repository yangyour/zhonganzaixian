<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createControlCommands,readControlEntry,type ControlEntry,type ControlCommandState} from '../utils/notificationControl'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{draftId:string|null;draftVersion:number|null;blocked:boolean}>()
const record=shallowRef<ControlEntry|null>(null),loaded=ref(false),loading=ref(false),error=shallowRef<unknown>(null),ack=ref(false),notice=ref('')
const command=shallowRef<ControlCommandState>({pending:null,busy:false,error:null,receipt:null,restorationBlocked:false})
const storage={getItem:(key:string)=>window.sessionStorage.getItem(key),setItem:(key:string,value:string)=>window.sessionStorage.setItem(key,value),removeItem:(key:string)=>window.sessionStorage.removeItem(key)}
const commands=createControlCommands(api,storage,value=>command.value=value)
const permitted=computed(()=>session.value?.mode==='remote_test'&&can('notification_preferences:read_own')&&!switchingTenant.value)
const writable=computed(()=>permitted.value&&can('notification_preferences:write_own'))
const scope=computed(()=>JSON.stringify(['preference-publication',contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,permitted.value,writable.value]))
const locked=computed(()=>loading.value||command.value.busy||!!command.value.pending||command.value.restorationBlocked)
let epoch=0,abort:AbortController|null=null
async function read(){
 if(!permitted.value||locked.value)return
 const turn=++epoch,controller=new AbortController();abort=controller;loading.value=true;loaded.value=false;error.value=null;record.value=null
 try{const result=await api.request<{record:unknown;external_dispatch_enabled:boolean}>('/my/notification-preferences/publication',{signal:controller.signal});if(turn!==epoch||controller.signal.aborted)return;if(!result||result.external_dispatch_enabled!==false)throw new Error('偏好发布状态未通过核对');const current=result.record===null?null:readControlEntry(result.record);if(current&&(current.kind!=='PREFERENCE'||current.id!==session.value?.user.id))throw new Error('偏好发布记录不属于当前用户');record.value=current;loaded.value=true}catch(failure){if(turn===epoch)error.value=failure}finally{if(turn===epoch){loading.value=false;abort=null}}
}
async function publish(published:boolean){
 if(!writable.value||locked.value||props.blocked||!props.draftId||props.draftVersion===null||!loaded.value||!ack.value)return
 const owner=scope.value
 if(await commands.submit('/my/notification-preferences/publication',{expected_version:record.value?.version??-1,draft_id:props.draftId,draft_version:props.draftVersion,published},'PREFERENCE',session.value!.user.id)&&owner===scope.value){notice.value=published?'当前偏好已发布供内部路由使用，未发送外部消息。':'已撤回偏好发布，后续路由将等待明确配置。';ack.value=false;await read()}
}
async function recover(retry=false){const owner=scope.value;if(await (retry?commands.retry():commands.recover())&&owner===scope.value){ack.value=false;notice.value='已核对原发布命令。';await read()}}
function mayLeave(){if(command.value.pending||command.value.busy){notice.value='请先核对未确认的偏好发布命令。';return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(event:BeforeUnloadEvent){if(command.value.pending){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
watch(scope,()=>{epoch++;abort?.abort();abort=null;loading.value=false;loaded.value=false;record.value=null;error.value=null;notice.value='';ack.value=false;commands.reset(permitted.value?scope.value:null);if(permitted.value&&!command.value.pending&&!command.value.restorationBlocked)void read()},{immediate:true,flush:'sync'})
watch(()=>[props.draftId,props.draftVersion,props.blocked],()=>{ack.value=false})
onUnmounted(()=>{epoch++;abort?.abort();commands.dispose();unregister();window.removeEventListener('beforeunload',beforeUnload)})
</script>
<template>
 <section v-if="permitted" class="preference-publication proto-panel">
  <h2>内部路由使用的偏好</h2><p>保存草稿后，可明确发布该版本供路由使用。发布不启用邮件、短信或其他外部发送。</p>
  <ApiErrorNotice :error="error"/><ApiErrorNotice :error="command.error"/>
  <p v-if="notice" role="status">{{notice}}</p><p v-if="loading" role="status">正在读取本人偏好发布状态…</p>
  <p v-if="loaded">{{record?.state.published?`已发布草稿版本 ${record.state.draft_version}`:'当前没有已发布的偏好'}}。</p>
  <p v-if="command.restorationBlocked" role="alert">原请求暂不能从浏览器恢复，请核对后台记录后再继续。</p>
  <div v-if="command.pending" role="alert"><p>发布结果未确认，请核对或重试同一原请求。</p><button :disabled="command.busy" @click="recover()">核对原命令</button><button :disabled="command.busy" @click="recover(true)">重试原请求</button><button v-if="command.busy" @click="commands.cancelWait">停止等待</button></div>
  <button :disabled="locked" @click="read">刷新发布状态</button>
  <template v-if="writable&&loaded"><label><input type="checkbox" v-model="ack" :disabled="locked||blocked||!draftId">我已核对当前已保存的草稿版本 {{draftVersion??'—'}}</label><button :disabled="locked||blocked||!draftId||!ack" @click="publish(true)">发布当前偏好</button><button :disabled="locked||blocked||!record?.state.published||!ack" @click="publish(false)">撤回发布</button></template>
 </section>
</template>
<style scoped>.preference-publication{padding:16px;display:grid;gap:12px}.preference-publication h2,.preference-publication p{margin:0}.preference-publication label{display:flex;gap:8px;align-items:center}.preference-publication button{justify-self:start}</style>
