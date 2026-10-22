<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,ref,shallowRef,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {api} from '../api/client'
import {can,session,contextGeneration,switchingTenant} from '../stores/session'
import {useIdentityEditor} from '../utils/useIdentityEditor'
import {createNotificationDeliveryReader,readNotificationDelivery,type DeliveryScope,type DeliveryReadState,type ChannelActivity} from '../utils/notificationDelivery'
import {emailStatusLabels} from '../utils/email'
import {wecomStatusLabels} from '../utils/wecom'
import IdentityCommandNotice from './IdentityCommandNotice.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
const props=defineProps<{messageId:string;channel:'email'|'wecom';ownerScope?:string;parentBusy?:boolean}>(),emit=defineEmits<{activity:[value:ChannelActivity]}>()
const state=shallowRef<DeliveryReadState>({current:null,busy:false,error:null}),confirming=ref(false),notice=ref<HTMLElement|null>(null)
let epoch=0,disposed=false
const permitted=computed(()=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(props.messageId)&&!!session.value&&session.value.mode==='remote_test'&&can('tickets:read')&&!switchingTenant.value)
const scopeKey=computed(()=>JSON.stringify([props.messageId,props.channel,props.ownerScope,contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,session.value?.mode,[...(session.value?.capabilities??[])].sort(),permitted.value]))
const scope=():DeliveryScope=>({channel:props.channel,messageId:props.messageId,tenantId:session.value?.tenant.id??'',actorId:session.value?.user.id??''})
const reader=createNotificationDeliveryReader(api,value=>{state.value=value})
const editor=useIdentityEditor(readback,{snapshot:()=>scopeKey.value,preserveUnknown:true,validateResult:(_path,_body,result)=>{readNotificationDelivery(result,scope())}}),command=editor.command,message=editor.message
const label=computed(()=>props.channel==='email'?'邮件':'企业微信'),blocked=computed(()=>confirming.value||editor.busy()||state.value.busy)
watch(scopeKey,()=>{epoch++;confirming.value=false;editor.clear();reader.reset(permitted.value?scope():null)},{immediate:true,flush:'sync'})
watch(blocked,value=>emit('activity',{scopeKey:props.ownerScope??'',channel:props.channel,messageId:props.messageId,blocked:value}),{immediate:true,flush:'sync'})
async function readback(){
 if(disposed||!permitted.value||state.value.busy||command.value.state!=='accepted')return false
 const turn=epoch,owner=scopeKey.value
 const ok=await reader.load(readNotificationDelivery(command.value.result,scope()))
 if(ok||state.value.error){await nextTick();if(!disposed&&turn===epoch&&owner===scopeKey.value)notice.value?.focus()}
 return ok
}
async function refreshRead(){if(disposed||!permitted.value||state.value.busy||confirming.value)return false;const turn=epoch,ok=await reader.load();if(ok||state.value.error){await nextTick();if(!disposed&&turn===epoch)notice.value?.focus()}return ok}
const hasAccepted=()=>command.value.state==='accepted'
async function send(){
 if(disposed||!permitted.value||props.parentBusy||blocked.value||hasAccepted())return false
 const turn=epoch,owner=scopeKey.value;confirming.value=true
 try{await ElMessageBox.confirm(`将本条站内通知发送到管理员为当前账号绑定的${label.value}接收地址。服务器受理不等于实际收到。`,'确认发送通知',{confirmButtonText:'发送',cancelButtonText:'取消'})}catch{return false}finally{if(turn===epoch&&!disposed)confirming.value=false}
 if(disposed||turn!==epoch||owner!==scopeKey.value||!permitted.value||props.parentBusy||blocked.value||hasAccepted())return false
 return editor.save(`/my/notifications/${props.messageId}/${props.channel}`,'POST',{expected_version:0})
}
onBeforeUnmount(()=>{disposed=true;epoch++;reader.dispose();emit('activity',{scopeKey:props.ownerScope??'',channel:props.channel,messageId:props.messageId,blocked:false})})
defineExpose({send,readback,refreshRead,editor,reader,state,confirming,blocked})
</script>
<template><div class="notification-channel-action" role="group" :aria-label="label+'通知发送与回执'">
 <button v-if="command.state!=='accepted'" type="button" :disabled="!permitted||blocked||parentBusy" @click="send">发送到我的{{channel==='email'?'邮箱':'企业微信'}}</button>
 <template v-else><p v-if="state.current" class="channel-receipt-status" ref="notice" tabindex="-1" role="status">{{(channel==='email'?emailStatusLabels:wecomStatusLabels)[state.current.status]}}。通道受理不代表收件人已收到或已读。</p><p v-else class="channel-receipt-status" ref="notice" tabindex="-1" role="status">原发送请求已受理，详情尚未读取确认；请读取原回执，不要重新发送。</p><button type="button" :disabled="!permitted||blocked||parentBusy" @click="refreshRead">刷新{{label}}回执</button></template>
 <ApiErrorNotice :error="state.error" />
 <ReadRecoveryActions :busy="state.busy?'read':null" :can-retry="reader.canRetryRead()" :scope-key="scopeKey" :label="label+'回执'" :cancel="reader.cancelRead" :retry="refreshRead" />
 <IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
</div></template>
<style scoped>
.notification-channel-action{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:12px;min-width:0}.notification-channel-action button{min-height:44px;padding:8px 12px;white-space:normal}.notification-channel-action p{overflow-wrap:anywhere}.notification-channel-action :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
</style>
