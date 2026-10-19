<script setup lang="ts">
import {computed,onUnmounted,shallowRef,watch,ref} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {blockerLabels,createNotificationWorkspace,emptyNotificationState,eventLabel,pendingReasonLabels,type NotificationState} from '../utils/ticketNotifications'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import WeComNotificationAction from '../components/WeComNotificationAction.vue'
const state=shallowRef<NotificationState>(emptyNotificationState()),workspace=createNotificationWorkspace(api,s=>{state.value=s})
const internal=computed(()=>['padmin','msp'].includes(session.value?.user.role??''))
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&can('tickets:read')&&!switchingTenant.value))
const ledgerAllowed=computed(()=>permitted.value&&internal.value&&can('ticket_notes:read_internal'))
const tab=ref<'mine'|'ledger'>('mine')
const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,permitted.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved())
watch(context,()=>{const s=session.value;tab.value='mine';workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,internal:internal.value}:null);if(permitted.value)void workspace.list(false)},{immediate:true,flush:'sync'})
function openLedger(){tab.value='ledger';if(!state.value.intents)void workspace.intents()}
async function recover(){await workspace.retry()}
const blocked=ref(false)
function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
onUnmounted(()=>{unregister();workspace.dispose()})
defineExpose({state,workspace,mayLeave})
</script>
<template>
 <section class="notifications">
  <div class="page-heading proto-page-head"><div><h1>工单通知 <span class="proto-badge">{{ session?.tenant.name }}</span></h1><p>工单受理、处理、确认与 SLA 节点的站内通知记录；仅显示发给本人账号的通知。</p></div><RouterLink to="/ticket-workflows" class="back">工单处理</RouterLink></div>
  <p class="panel" v-if="!permitted">当前身份没有工单通知权限，或正在切换租户。</p>
  <template v-else>
   <p class="proto-scope-note external">企业微信接入启用且账号完成绑定后，可将本站通知发送给本人。自动渠道策略仍按配置生效；接口受理与实际收到分别记录。</p>
   <div class="proto-kpis"><div><span>未读通知</span><b>{{ state.page ? state.page.unread_count : '—' }}</b><small>当前租户本人</small></div><div><span>本页通知</span><b>{{ state.page ? state.page.items.length : '—' }}</b><small>按时间倒序</small></div><div><span>通知渠道</span><b>站内</b><small>自动派发未启用</small></div></div>
   <div class="proto-panel workspace">
    <div class="proto-toolbar">
     <div class="proto-tabs" aria-label="通知视图">
      <button type="button" :class="{active:tab==='mine'&&!state.unreadOnly}" :aria-pressed="tab==='mine'&&!state.unreadOnly" :disabled="locked" @click="tab='mine';workspace.list(false)">全部通知</button>
      <button type="button" :class="{active:tab==='mine'&&state.unreadOnly}" :aria-pressed="tab==='mine'&&state.unreadOnly" :disabled="locked" @click="tab='mine';workspace.list(true)">未读通知</button>
      <button v-if="ledgerAllowed" type="button" :class="{active:tab==='ledger'}" :aria-pressed="tab==='ledger'" @click="openLedger">通知派发台账</button>
     </div>
     <button type="button" :disabled="tab==='mine'?locked:state.intentBusy" @click="tab==='mine'?workspace.list():workspace.intents()">刷新</button>
    </div>
    <template v-if="tab==='mine'">
     <ApiErrorNotice :error="state.error" />
     <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">标记已读结果尚未确认，请点击下方按钮重试。</p><button v-if="state.attempt&&!state.busy" type="button" @click="recover">重试标记已读</button>
     <p v-if="state.receipt" role="status">已读状态已记录：{{ displayTime(state.receipt.read_at) }}。</p>
     <p v-if="state.busy" role="status" aria-live="polite">{{ state.busy==='write'?'正在记录已读状态…':'正在读取通知…' }}</p>
     <p v-if="!state.page&&!state.busy">{{ state.error?'读取未完成，通知数量未知。':'尚未读取通知。' }}</p>
     <template v-if="state.page">
      <p>更新于 {{ displayTime(state.page.observed_at) }}</p>
      <p v-if="!state.page.items.length" class="panel">{{ state.unreadOnly?'当前没有未读通知。':'当前没有工单通知。' }}</p>
      <ul v-else class="message-list">
       <li v-for="m in state.page.items" :key="m.id" :class="{unread:!m.read_at}">
        <div class="message-head"><span class="proto-badge">{{ eventLabel(m.event_type) }}</span><strong>{{ m.title }}</strong></div>
        <p class="body">{{ m.body }}</p>
        <WeComNotificationAction :message-id="m.id"/>
        <div class="meta"><span>{{ displayTime(m.created_at) }}</span><span>{{ m.read_at?`已读于 ${displayTime(m.read_at)}`:'未读' }}</span><RouterLink :to="{path:'/ticket-workflows',query:{ticket:m.ticket_id}}">查看工单</RouterLink><button v-if="!m.read_at" type="button" :disabled="locked" @click="workspace.mark(m.id)">标记已读</button></div>
       </li>
      </ul>
      <button type="button" :disabled="locked||!state.page.has_more" @click="workspace.list(state.unreadOnly,true)">下一页</button>
     </template>
    </template>
    <template v-else>
     <p class="proto-scope-note">每条工单处理事件生成一条通知派发记录。“待确认规则”表示接收对象依赖尚未配置的值班、升级或角色规则，系统不会自动指定默认接收人。</p>
     <ApiErrorNotice :error="state.intentError" />
     <p v-if="state.intentBusy" role="status">正在读取通知派发记录…</p>
     <div v-if="state.intents" class="proto-table-wrap"><table class="proto-table"><thead><tr><th>工单</th><th>事件</th><th>优先级</th><th>接收对象</th><th>待确认事项</th><th>外部渠道</th><th>生成时间</th></tr></thead><tbody>
      <tr v-for="i in state.intents.items" :key="i.id"><td>{{ i.receipt_no }}</td><td>{{ eventLabel(i.event_type) }}</td><td>{{ i.priority ?? '—' }}</td><td>{{ i.recipient_status==='COMPLETE'?`已生成 ${i.recipient_count} 条`:i.recipient_status==='PARTIAL'?`部分生成 ${i.recipient_count} 条`:'待确认规则' }}</td><td><span v-if="!i.pending_reasons.length">—</span><span v-for="r in i.pending_reasons" :key="r" class="reason">{{ pendingReasonLabels[r] ?? r }}</span></td><td><span v-for="b in i.external_blockers" :key="b" class="reason">{{ blockerLabels[b] ?? b }}</span></td><td>{{ displayTime(i.created_at) }}</td></tr>
     </tbody></table><p v-if="!state.intents.items.length" class="panel">尚无通知派发记录。</p></div>
     <button v-if="state.intents" type="button" :disabled="state.intentBusy||!state.intents.has_more" @click="workspace.intents(true)">下一页</button>
    </template>
   </div>
  </template>
 </section>
</template>
<style scoped>
.notifications{min-width:0}.notifications .panel{padding:20px;margin:16px 0}.notifications p{line-height:1.7;overflow-wrap:anywhere}.external{margin:12px 0;color:#80501c}.workspace{margin-top:12px}.workspace>p,.workspace>button{margin:12px 18px;font-size:12px}.notifications .proto-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:0 18px}.notifications button,.back{display:inline-flex;align-items:center;min-height:32px;padding:6px 10px;font-size:12px;border:1px solid #cbd7e5;border-radius:3px;background:#fff;color:#245a85;font-family:inherit;cursor:pointer}.notifications button:disabled{opacity:.5;cursor:not-allowed}.notifications .proto-tabs{display:flex;gap:24px}.notifications .proto-tabs button{border:0;border-bottom:2px solid transparent;border-radius:0;min-height:48px;padding:0 4px;background:none;color:#5f6b78}.notifications .proto-tabs button.active{border-bottom-color:#2d79f6;color:#1f5fc8}.message-list{list-style:none;margin:0;padding:0 18px}.message-list li{border-bottom:1px solid #e6edf5;padding:12px 0}.message-list li.unread .message-head strong{color:#1f5fc8}.message-head{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.body{margin:6px 0;font-size:13px;color:#334155}.meta{display:flex;gap:14px;align-items:center;flex-wrap:wrap;font-size:12px;color:#5f6b78}.meta button{border:0;background:none;color:#1f5fc8;padding:0}.reason{display:block;font-size:12px}.notifications :is(button,a):focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:760px){.notifications button,.back{min-height:44px}.notifications .proto-toolbar{padding:0 12px;flex-wrap:wrap}}
</style>
