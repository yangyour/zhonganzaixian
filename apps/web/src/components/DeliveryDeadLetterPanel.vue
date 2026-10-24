<script setup lang="ts">
import { computed,nextTick,onUnmounted,ref,watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../api/client'
import { session,can,contextGeneration,switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createDeadLetterCommands,type DeadLetterCommand } from '../utils/deliveryDeadLetter'
import { createRequestId } from '../utils/browserCrypto'
import type { DeliveryEntry } from '../utils/deliveryJournal'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{entry:DeliveryEntry|null}>()
const emit=defineEmits<{changed:[id:string]}>()
const allowed=computed(()=>session.value?.mode==='remote_test'&&session.value.user.role==='padmin'&&!switchingTenant.value&&can('ticket_notes:read_internal')&&can('audit:read')&&can('configurations:write'))
const key=computed(()=>allowed.value?JSON.stringify([session.value!.tenant.id,session.value!.user.id,session.value!.permission_version,session.value!.account_version,session.value!.user.role,[...session.value!.capabilities].sort()]):null)
const commands=createDeadLetterCommands(api,{getItem:k=>sessionStorage.getItem(k),setItem:(k,v)=>sessionStorage.setItem(k,v),removeItem:k=>sessionStorage.removeItem(k)},()=>key.value?{key:key.value,tenant:session.value!.tenant.id,actor:session.value!.user.id}:null)
const modal=ref(false),reason=ref(''),ack=ref(false),candidate=ref<{id:string;pool:string;version:number}|null>(null),notice=ref(''),trigger=ref<HTMLButtonElement>(),retry=ref<HTMLButtonElement>(),modalRetry=ref<HTMLButtonElement>()
const locked=computed(()=>commands.busy.value||!!commands.pending.value||commands.blocked.value)
const valid=computed(()=>reason.value.trim().length>0&&reason.value.length<=1000&&ack.value)
const selectable=computed(()=>allowed.value&&props.entry?.recorded_status==='FAILED'&&props.entry.tenant_id===session.value?.tenant.id&&!locked.value)
function begin(){if(!selectable.value||!props.entry)return;candidate.value={id:props.entry.delivery_id,pool:props.entry.resource_pool_id,version:props.entry.version};reason.value='';ack.value=false;modal.value=true;notice.value=''}
async function submit(){if(!candidate.value||!valid.value||locked.value||!selectable.value)return
 const c:DeadLetterCommand={command_id:createRequestId(),tenant_id:session.value!.tenant.id,actor_id:session.value!.user.id,delivery_id:candidate.value.id,resource_pool_id:candidate.value.pool,expected_version:candidate.value.version,reason:reason.value}
 if(await commands.submit(c)){modal.value=false;notice.value='已记录人工死信处置；这是原命令回执，不代表供应商送达、当前头状态或允许重发。';emit('changed',c.delivery_id)}
}
async function recover(){const id=commands.pending.value?.delivery_id;if(await commands.retry()){modal.value=false;notice.value='已核对原处置命令，未生成新的发送请求。';if(id)emit('changed',id)}}
async function cancelWait(){commands.cancel();commands.reset();notice.value="已停止等待，原命令保留；结果仍需核对。";await nextTick();(modal.value?modalRetry.value:retry.value)?.focus()}
function mayLeave(){if(commands.pending.value||commands.busy.value){notice.value='请先按原命令核对未确认的处置结果。';return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(e:BeforeUnloadEvent){if(commands.pending.value){e.preventDefault();e.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
watch([key,contextGeneration],()=>{commands.reset();modal.value=false;candidate.value=null;reason.value='';ack.value=false;notice.value=''},{flush:'sync'})
watch(()=>[props.entry?.delivery_id,props.entry?.version,props.entry?.recorded_status],()=>{if(!commands.pending.value&&!commands.busy.value){modal.value=false;candidate.value=null;ack.value=false}})
onUnmounted(()=>{commands.dispose();unregister();window.removeEventListener('beforeunload',beforeUnload)})
</script>
<template>
 <section v-if="allowed" aria-label="投递失败人工处置">
  <button v-if="entry?.recorded_status==='FAILED'" ref="trigger" :disabled="locked" @click="begin">将失败记录转为死信</button>
  <p v-if="commands.pending.value" role="alert">处置结果待确认：{{commands.pending.value.delivery_id}} · 原命令 {{commands.pending.value.command_id}}。不得改用新命令重复操作。</p>
  <button v-if="commands.pending.value" ref="retry" :disabled="commands.busy.value||commands.blocked.value" @click="recover">按原命令核对处置</button>
  <button v-if="commands.busy.value" @click="cancelWait">停止等待处置</button>
  <p v-if="commands.blocked.value" role="alert">本地原命令无法安全恢复，已阻止新增处置。请核对日志和审计。</p>
  <ApiErrorNotice :error="commands.error.value" />
  <p v-if="notice" role="status">{{notice}}</p>
  <el-dialog v-model="modal" title="确认人工死信处置" width="min(560px, 92vw)" :close-on-click-modal="false" :close-on-press-escape="!commands.busy.value" :show-close="!commands.busy.value" @closed="trigger?.focus()">
   <p>投递 {{candidate?.id}} · 版本 {{candidate?.version}}</p>
   <p>只对当前本地失败声明追加死信记录。不会认证供应商回执、撤回消息或重新发送；结果不明的记录不能使用此操作。</p>
   <label>处置原因<textarea v-model="reason" maxlength="1000" rows="3" :disabled="locked" /></label>
   <label><input v-model="ack" type="checkbox" :disabled="locked" />我已核对该失败记录，只登记人工处置，不重发消息</label>
   <ApiErrorNotice :error="commands.error.value" />
   <template #footer><button :disabled="commands.busy.value" @click="modal=false">{{commands.pending.value?'关闭并保留原命令':'返回核对'}}</button><button :disabled="locked||!valid" @click="submit">确认转为死信</button><button v-if="commands.pending.value" ref="modalRetry" :disabled="commands.busy.value||commands.blocked.value" @click="recover">核对原处置</button><button v-if="commands.busy.value" @click="cancelWait">停止等待处置</button></template>
  </el-dialog>
 </section>
</template>
<style scoped>section{min-width:0;overflow-wrap:anywhere}label{display:block;margin:8px 0}textarea{display:block;box-sizing:border-box;width:100%}button{min-height:32px;margin-right:6px}</style>
