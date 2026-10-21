<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue'
import {api} from '../api/client'
import {session,contextGeneration,switchingTenant} from '../stores/session'
import {useIdentityEditor} from '../utils/useIdentityEditor'
import {wecomStatusLabels,type WeComDelivery} from '../utils/wecom'
import IdentityCommandNotice from './IdentityCommandNotice.vue'
const props=defineProps<{messageId:string}>(),receipt=ref<WeComDelivery|null>(null)
let epoch=0,controller:AbortController|null=null
const scope=computed(()=>JSON.stringify([props.messageId,contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,switchingTenant.value]))
async function readback(){const id=(editor.command.value.result as WeComDelivery|null)?.id;if(!id)return false;const turn=epoch;controller?.abort();controller=new AbortController();const result=await api.request<WeComDelivery>(`/wecom/deliveries/${id}`,{signal:controller.signal});if(turn!==epoch||result.tenant_id!==session.value?.tenant.id||result.actor_id!==session.value?.user.id||result.source_message_id!==props.messageId)return false;receipt.value=result;return true}
const editor=useIdentityEditor(readback),command=editor.command,message=editor.message
watch(scope,()=>{epoch++;controller?.abort();receipt.value=null;editor.clear()},{flush:'sync'})
onUnmounted(()=>{epoch++;controller?.abort()})
async function send(){if(editor.busy()||switchingTenant.value||receipt.value)return;await editor.save(`/my/notifications/${props.messageId}/wecom`,'POST',{expected_version:0})}
</script>
<template><div class="wecom-notification-action"><button v-if="!receipt" type="button" :disabled="editor.busy()||switchingTenant" @click="send">发送到我的企业微信</button><template v-else><span>{{wecomStatusLabels[receipt.status]}}</span><button type="button" :disabled="editor.busy()||switchingTenant" @click="readback">刷新发送结果</button></template><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry"/></div></template>
<style scoped>.wecom-notification-action{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:12px}.wecom-notification-action button{min-height:36px}@media(max-width:600px){.wecom-notification-action button{min-height:44px}}</style>
