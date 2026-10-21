<script setup lang="ts">
import '../styles/adminPrototype.css'
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {api,ApiError,isCancelled} from '../api/client'
import {can,session,contextGeneration,switchingTenant} from '../stores/session'
import {useIdentityEditor} from '../utils/useIdentityEditor'
import type {UserPage} from '../utils/projectIdentity'
import {wecomStatusLabels,wecomErrorLabels,type WeComStatus,type WeComBinding,type WeComDelivery} from '../utils/wecom'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const status=ref<WeComStatus|null>(null),bindings=ref<WeComBinding[]>([]),deliveries=ref<WeComDelivery[]>([]),users=ref<UserPage|null>(null)
const loading=ref(false),error=ref<unknown>(null),userId=ref(''),memberId=ref('')
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,session.value?.mode,can('users:read'),can('users:write'),switchingTenant.value]))
let epoch=0,disposed=false,controller:AbortController|null=null
const readable=()=>!disposed&&!switchingTenant.value&&!!session.value&&can('users:read')
function clearFacts(){status.value=null;bindings.value=[];deliveries.value=[];users.value=null}
async function load(){
 clearFacts()
 const turn=++epoch,owner=scope.value;controller?.abort();controller=new AbortController();loading.value=false;error.value=null
 if(!readable())return false
 const signal=controller.signal;loading.value=true
 try{const [s,b,d,u]=await Promise.all([api.request<WeComStatus>('/wecom/status',{signal}),api.request<{items:WeComBinding[]}>('/wecom/bindings',{signal}),api.request<{items:WeComDelivery[]}>('/wecom/deliveries',{signal}),api.request<UserPage>('/identity/users?include_inactive=false',{signal})]);if(turn!==epoch||owner!==scope.value||!readable())return false
  if(!s||!u||!b||!d||!s.last_check||typeof s.last_check.ready!=='boolean'||typeof s.dispatch_enabled!=='boolean'||!Array.isArray(u.items)||s.tenant_id!==session.value?.tenant.id||u.tenant_id!==session.value?.tenant.id||!Array.isArray(b.items)||!Array.isArray(d.items)||d.items.some(row=>row.tenant_id!==s.tenant_id))throw new ApiError(200,'INVALID_RESPONSE','企业微信读取结果范围不一致','—')
  status.value=s;bindings.value=b.items;deliveries.value=d.items;users.value=u;return true
 }catch(cause){if(turn===epoch){controller?.abort();if(!isCancelled(cause))error.value=cause}return false}finally{if(turn===epoch){loading.value=false;controller=null}}
}
const editor=useIdentityEditor(load),command=editor.command,message=editor.message
const locked=computed(()=>!readable()||!status.value||!users.value||loading.value||editor.busy()||!can('users:write'))
function clear(){epoch++;controller?.abort();status.value=null;bindings.value=[];deliveries.value=[];users.value=null;userId.value='';memberId.value='';error.value=null;loading.value=false;editor.clear()}
watch([userId,memberId],()=>{editor.dirty.value=!!userId.value||!!memberId.value},{flush:'sync'})
watch(scope,()=>{clear();if(readable())void load()},{flush:'sync'});onMounted(load);onUnmounted(()=>{disposed=true;clear()})
async function refresh(){const owner=scope.value;if(editor.busy()||!await editor.leave()||owner!==scope.value||disposed)return;userId.value='';memberId.value='';editor.clear();await load()}
async function check(){if(!locked.value&&!editor.dirty.value)await editor.save('/wecom/check','POST',{})}
async function test(){if(locked.value||editor.dirty.value||!status.value?.dispatch_enabled||!status.value.test_user_id)return;const owner=scope.value,recipient=status.value.test_user_id;try{await ElMessageBox.confirm(`将向 ${status.value.test_user_id} 发送“【MOP 联调测试】企业微信应用消息通道验证，无需处理”。`,'发送测试消息',{confirmButtonText:'发送',cancelButtonText:'取消'})}catch{return}if(owner===scope.value&&!locked.value&&!editor.dirty.value&&status.value?.dispatch_enabled&&status.value.test_user_id===recipient)await editor.save('/wecom/test-messages','POST',{expected_version:0})}
async function bind(){if(locked.value||!userId.value||!memberId.value)return;const old=bindings.value.find(row=>row.user_id===userId.value);const owner=scope.value,selected=userId.value,value=memberId.value;const saved=await editor.save(`/wecom/bindings/${userId.value}`,'PUT',{expected_version:old?.version??0,wecom_user_id:memberId.value,active:true});if(saved&&owner===scope.value&&selected===userId.value&&value===memberId.value){userId.value='';memberId.value='';editor.dirty.value=false}}
async function disable(row:WeComBinding){if(locked.value||editor.dirty.value)return;await editor.save(`/wecom/bindings/${row.user_id}`,'PUT',{expected_version:row.version,wecom_user_id:row.wecom_user_id,active:false})}
</script>
<template><section class="identity-page admin-prototype wecom-page">
 <div class="page-heading"><div><h1>企业微信</h1><p>检查应用连接，管理成员与平台账号的绑定，查看消息发送记录。</p></div><el-button :disabled="editor.busy()||loading||switchingTenant" @click="refresh">刷新</el-button></div>
 <ApiErrorNotice v-if="error" :error="error"/><IdentityCommandNotice v-if="command.state!=='idle'||message" :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry"/>
 <p v-if="loading" role="status">正在读取企业微信配置…</p>
 <p v-if="error instanceof ApiError&&error.status===404" class="admin-card">当前租户尚未启用企业微信接入。请联系管理员配置应用。</p>
 <p v-if="!readable()" role="status">当前没有读取企业微信配置的权限。</p>
 <el-button v-if="error&&readable()" :disabled="loading||editor.busy()" @click="refresh">重新读取配置</el-button>
 <p v-if="editor.dirty.value" role="status">绑定内容尚未保存。请保存绑定，或刷新并确认放弃后再执行其他操作。</p>
 <template v-if="status">
  <section class="admin-card"><div class="admin-card-head"><h2>应用连接</h2><span>{{status.last_check.ready?'连接正常':status.last_check.reason==='NOT_CHECKED'?'尚未检查':'需要检查'}}</span></div>
   <dl class="admin-facts"><dt>企业 ID</dt><dd>{{status.corp_id}}</dd><dt>应用 ID</dt><dd>{{status.agent_id}}</dd><dt>应用名称</dt><dd>{{status.last_check.application_name??'尚未读取'}}</dd><dt>应用密钥</dt><dd>{{status.secret_configured?'已在服务端配置':'未配置'}}</dd><dt>消息发送</dt><dd>{{status.dispatch_enabled?'已启用':'未启用'}}</dd><dt>企业微信登录</dt><dd>{{status.login_enabled?'已启用':'未启用'}}</dd><dt>最近检查</dt><dd>{{status.last_check.checked_at?displayTime(status.last_check.checked_at):'尚未检查'}}</dd></dl>
   <p v-if="status.last_check.reason">{{wecomErrorLabels[status.last_check.reason]??status.last_check.reason}}</p>
   <div class="wecom-actions"><el-button :disabled="locked||editor.dirty.value" @click="check">检查应用连接</el-button><el-button :disabled="locked||editor.dirty.value||!status.dispatch_enabled||!status.test_user_id" @click="test">发送测试消息</el-button></div>
  </section>
  <section class="admin-card"><div class="admin-card-head"><h2>成员与平台账号</h2><span>登录后使用平台账号已有权限</span></div>
   <p>新成员需先创建平台账号，再确认企业微信 UserID。停用绑定后，该账号的既有登录会话也会失效。</p>
   <form class="wecom-bind" @submit.prevent="bind"><label>平台账号<el-select v-model="userId" aria-label="绑定的平台账号" :disabled="locked" filterable><el-option v-for="user in users?.items" :key="user.id" :value="user.id" :label="`${user.display_name}（${user.username??user.id}）`"/></el-select></label><label>企业微信 UserID<el-input v-model="memberId" aria-label="绑定的企业微信UserID" maxlength="64" :disabled="locked"/></label><el-button native-type="submit" type="primary" :disabled="locked||!userId||!memberId">核验并绑定</el-button></form>
   <div class="admin-table-scroll" tabindex="0" role="region" aria-label="企业微信账号绑定，可横向滚动"><table class="admin-table"><caption>企业微信账号绑定</caption><thead><tr><th scope="col">平台用户</th><th scope="col">企业微信成员</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="row in bindings" :key="row.id"><td>{{row.display_name??row.user_id}}</td><td>{{row.wecom_user_id}}</td><td>{{row.active?'已绑定':'已停用'}}</td><td><el-button v-if="row.active" :disabled="locked||editor.dirty.value" @click="disable(row)">停用绑定</el-button></td></tr><tr v-if="!bindings.length"><td colspan="4">尚无账号绑定。</td></tr></tbody></table></div>
  </section>
  <section class="admin-card"><div class="admin-card-head"><h2>消息发送记录</h2><span>最近 100 条</span></div><p>“企业微信已受理”表示接口接受了请求。实际收到或已读需要单独核对；结果待核对的消息不会自动重发。</p>
   <div class="admin-table-scroll" tabindex="0" role="region" aria-label="企业微信发送记录，可横向滚动"><table class="admin-table"><caption>企业微信发送记录</caption><thead><tr><th scope="col">时间</th><th scope="col">接收人</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">说明</th></tr></thead><tbody><tr v-for="row in deliveries" :key="row.id"><td>{{displayTime(row.created_at)}}</td><td>{{row.recipient}}</td><td>{{row.kind==='TEST'?'联调测试':'工单通知'}}</td><td>{{wecomStatusLabels[row.status]}}</td><td>{{row.error_code?(wecomErrorLabels[row.error_code]??row.error_code):row.status==='ACCEPTED'?'等待实际接收确认':'—'}}</td></tr><tr v-if="!deliveries.length"><td colspan="5">尚无发送记录。</td></tr></tbody></table></div>
  </section>
 </template>
</section></template>
<style scoped>.wecom-page{display:grid;gap:18px}.wecom-actions,.wecom-bind{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.wecom-bind{margin:18px 16px}.wecom-page .admin-card>p{margin:14px 16px}.wecom-bind label{display:grid;gap:8px;min-width:220px;flex:1}.wecom-page dd{overflow-wrap:anywhere}.wecom-page .admin-table{min-width:680px}@media(max-width:600px){.wecom-bind{display:grid}.wecom-bind label{min-width:0}.wecom-actions>*{min-height:44px}}.wecom-page :deep(.el-button),.wecom-page :deep(.el-input__wrapper),.wecom-page :deep(.el-select__wrapper){min-height:44px}.wecom-page .admin-table-scroll:focus-visible{outline:2px solid #2563eb;outline-offset:2px}</style>
