<script setup lang="ts">
import '../styles/adminPrototype.css'
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {api,ApiError,isCancelled} from '../api/client'
import {can,session,contextGeneration,switchingTenant} from '../stores/session'
import {useIdentityEditor} from '../utils/useIdentityEditor'
import {readUserPage,type UserPage} from '../utils/projectIdentity'
import {emailStatusLabels,emailErrorLabels,type EmailStatus,type EmailBinding,type EmailDelivery} from '../utils/email'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const status=ref<EmailStatus|null>(null),bindings=ref<EmailBinding[]>([]),deliveries=ref<EmailDelivery[]>([]),users=ref<UserPage|null>(null)
const loading=ref(false),error=ref<unknown>(null),userId=ref(''),email=ref('')
const accountSearchInput=ref<HTMLInputElement|null>(null)
const accountQuery=ref(''),appliedAccountQuery=ref(''),accountPage=ref(1)
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,session.value?.mode,can('users:read'),can('users:write'),can('tickets:read'),switchingTenant.value]))
let epoch=0,disposed=false,controller:AbortController|null=null
const readable=()=>!disposed&&!switchingTenant.value&&!!session.value&&can('users:read')
function clearFacts(){status.value=null;bindings.value=[];deliveries.value=[];users.value=null}
async function load(){
 clearFacts()
 const turn=++epoch,owner=scope.value;controller?.abort();controller=new AbortController();error.value=null;loading.value=false
 if(!readable())return false
 const signal=controller.signal;loading.value=true
 try{const [s,b,d,u]=await Promise.all([api.request<EmailStatus>('/email/status',{signal}),api.request<{items:EmailBinding[]}>('/email/bindings',{signal}),can('tickets:read')?api.request<{items:EmailDelivery[]}>('/email/deliveries',{signal}):Promise.resolve({items:[]}),api.request<UserPage>(`/identity/users?include_inactive=false&query=${encodeURIComponent(appliedAccountQuery.value)}&page=${accountPage.value}`,{signal})]);if(turn!==epoch||owner!==scope.value||!readable())return false
  if(!s||!u||!b||!d||!s.last_check||typeof s.last_check.ready!=='boolean'||typeof s.dispatch_enabled!=='boolean'||!Array.isArray(u.items)||s.tenant_id!==session.value?.tenant.id||u.tenant_id!==session.value?.tenant.id||!Array.isArray(s.allowed_recipients)||s.tls_mode!=='IMPLICIT_TLS'||s.certificate_verification!==true||!Array.isArray(b.items)||!Array.isArray(d.items)||d.items.some(row=>row.tenant_id!==s.tenant_id||row.sender!==s.sender))throw new ApiError(200,'INVALID_RESPONSE','邮件读取结果范围不一致','—')
  const userPage=readUserPage(u,s.tenant_id);if(userPage.page!==accountPage.value||userPage.items.some(user=>!user.active||!user.member_active)||new Set(userPage.items.map(user=>user.id)).size!==userPage.items.length)throw new ApiError(200,'INVALID_RESPONSE','账号选项页或启用状态不一致','—')
  status.value=s;bindings.value=b.items;deliveries.value=d.items;users.value=userPage;return true
 }catch(cause){if(turn===epoch){controller?.abort();if(!isCancelled(cause))error.value=cause}return false}finally{if(turn===epoch){loading.value=false;controller=null}}
}
const editor=useIdentityEditor(load),command=editor.command,message=editor.message
const locked=computed(()=>!readable()||!status.value||!users.value||loading.value||editor.busy()||!can('users:write'))
function clear(){accountQuery.value='';appliedAccountQuery.value='';accountPage.value=1;epoch++;controller?.abort();status.value=null;bindings.value=[];deliveries.value=[];users.value=null;userId.value='';email.value='';error.value=null;loading.value=false;editor.clear()}
watch([userId,email],()=>{editor.dirty.value=!!userId.value||!!email.value},{flush:'sync'})
watch(scope,()=>{clear();if(readable())void load()},{flush:'sync'});onMounted(load);onUnmounted(()=>{disposed=true;clear()})
async function refresh(){const owner=scope.value;if(editor.busy()||!await editor.leave()||owner!==scope.value||disposed)return;userId.value='';email.value='';editor.clear();await load()}
async function browseAccounts(nextPage:number,search=false){
 const owner=scope.value,query=search?accountQuery.value.trim():appliedAccountQuery.value
 if(!readable()||loading.value||editor.busy()||!Number.isSafeInteger(nextPage)||nextPage<1||nextPage>10000||query.length>100||(!search&&nextPage>accountPage.value&&!users.value?.has_more))return
 if(!await editor.leave()||owner!==scope.value||disposed||loading.value||editor.busy())return
 userId.value='';email.value='';editor.clear();accountPage.value=nextPage;appliedAccountQuery.value=query;accountQuery.value=query;const ok=await load();if(ok&&owner===scope.value&&!disposed){await nextTick();if(owner===scope.value&&!disposed)accountSearchInput.value?.focus()}
}
async function check(){if(!locked.value&&!editor.dirty.value)await editor.save('/email/check','POST',{})}
async function test(){
 if(locked.value||editor.dirty.value||!status.value?.dispatch_enabled)return
 const owner=scope.value,recipient=status.value.test_recipient;try{await ElMessageBox.confirm(`将向 ${status.value.test_recipient} 发送一封“【MOP 联调测试】邮件通道验证”。`,'发送测试邮件',{confirmButtonText:'发送',cancelButtonText:'取消'})}catch{return}
 if(owner===scope.value&&!locked.value&&!editor.dirty.value&&status.value?.dispatch_enabled&&status.value.test_recipient===recipient)await editor.save('/email/test-messages','POST',{expected_version:0})
}
async function bind(){if(locked.value||!userId.value||!email.value||!users.value?.items.some(user=>user.id===userId.value&&user.active&&user.member_active)||!status.value?.allowed_recipients.includes(email.value))return;const old=bindings.value.find(row=>row.user_id===userId.value);const owner=scope.value,selected=userId.value,value=email.value;const saved=await editor.save(`/email/bindings/${userId.value}`,'PUT',{expected_version:old?.version??0,email:email.value,active:true});if(saved&&owner===scope.value&&selected===userId.value&&value===email.value){userId.value='';email.value='';editor.dirty.value=false}}
async function disable(row:EmailBinding){if(!locked.value&&!editor.dirty.value)await editor.save(`/email/bindings/${row.user_id}`,'PUT',{expected_version:row.version,email:row.email,active:false})}
</script>
<template><section :aria-busy="loading" class="identity-page admin-prototype email-page">
 <div class="page-heading"><div><h1>邮件通知</h1><p>检查发件连接，配置收件地址，查看邮件发送记录。</p></div><el-button :disabled="editor.busy()||loading||switchingTenant" @click="refresh">刷新</el-button></div>
 <ApiErrorNotice v-if="error" :error="error"/><IdentityCommandNotice v-if="command.state!=='idle'||message" :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry"/>
 <p v-if="loading" role="status">正在读取邮件配置…</p><p v-if="error instanceof ApiError&&error.status===404" class="admin-card">当前租户尚未启用邮件通知，请联系管理员配置。</p>
 <p v-if="!readable()" role="status">当前没有读取邮件配置的权限。</p>
 <el-button v-if="error&&readable()" :disabled="loading||editor.busy()" @click="refresh">重新读取配置</el-button>
 <p v-if="editor.dirty.value" role="status">绑定内容尚未保存。请保存绑定，或刷新并确认放弃后再执行其他操作。</p>
 <template v-if="status">
  <section class="admin-card"><div class="admin-card-head"><h2>发件连接</h2><span>{{status.last_check.ready?'连接正常':status.last_check.reason==='NOT_CHECKED'?'尚未检查':'需要检查'}}</span></div>
   <dl class="admin-facts"><dt>发件邮箱</dt><dd>{{status.sender}}</dd><dt>发件名称</dt><dd>{{status.sender_name}}</dd><dt>邮件服务器</dt><dd>{{status.smtp_host}}:{{status.smtp_port}}</dd><dt>加密连接</dt><dd>SSL/TLS，校验证书</dd><dt>邮箱凭据</dt><dd>{{status.secret_configured?'已在服务端配置':'未配置'}}</dd><dt>人工发送</dt><dd>{{status.dispatch_enabled?'已启用':'未启用'}}</dd><dt>自动业务通知</dt><dd>{{status.automatic_notifications_enabled?'已启用':'未启用'}}</dd><dt>最近检查</dt><dd>{{status.last_check.checked_at?displayTime(status.last_check.checked_at):'尚未检查'}}</dd></dl>
   <p v-if="status.last_check.reason">{{emailErrorLabels[status.last_check.reason]??status.last_check.reason}}</p>
   <div class="email-actions"><el-button :disabled="locked||editor.dirty.value" @click="check">检查邮件连接</el-button><el-button :disabled="locked||editor.dirty.value||!status.dispatch_enabled" @click="test">发送测试邮件</el-button><span>测试收件人：{{status.test_recipient}}</span></div>
  </section>
  <section class="admin-card"><div class="admin-card-head"><h2>平台账号收件地址</h2><span>由管理员明确绑定</span></div>
   <p>请确认地址归属后绑定。用户可以将自己的站内通知发到已绑定邮箱。收件地址需列入服务端允许范围。</p>
   <form class="account-search" @submit.prevent="browseAccounts(1,true)"><label>搜索平台账号<input ref="accountSearchInput" v-model="accountQuery" aria-label="搜索平台账号" maxlength="100" :disabled="loading||editor.busy()" /></label><el-button native-type="submit" :disabled="loading||editor.busy()">搜索账号</el-button></form>
   <div class="account-pages" aria-label="账号选项分页"><el-button :disabled="loading||editor.busy()||accountPage<=1" @click="browseAccounts(accountPage-1)">上一页账号</el-button><span role="status">账号选项第 {{accountPage}} 页，本页 {{users?.items.length??0}} 个{{users?.has_more?'，还有下一页':''}}</span><el-button :disabled="loading||editor.busy()||accountPage>=10000||!users?.has_more" @click="browseAccounts(accountPage+1)">下一页账号</el-button></div>
   <p v-if="users&&!users.items.length">当前搜索或页面没有可选的启用账号。可更改搜索，或返回上一页。</p>
   <form class="email-bind" @submit.prevent="bind"><label>平台账号<el-select v-model="userId" aria-label="邮件绑定的平台账号" :disabled="locked" filterable><el-option v-for="user in users?.items" :key="user.id" :value="user.id" :label="`${user.display_name}（${user.username??user.id}）`"/></el-select></label><label>收件地址<el-select v-model="email" aria-label="绑定的收件邮箱" :disabled="locked"><el-option v-for="address in status.allowed_recipients" :key="address" :value="address" :label="address"/></el-select></label><el-button native-type="submit" type="primary" :disabled="locked||!userId||!email">保存绑定</el-button></form>
   <div class="admin-table-scroll" tabindex="0" role="region" aria-label="邮件账号绑定，可横向滚动"><table class="admin-table"><caption>邮件账号绑定</caption><thead><tr><th scope="col">平台用户</th><th scope="col">收件地址</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="row in bindings" :key="row.id"><td>{{row.display_name??row.user_id}}</td><td>{{row.email}}</td><td>{{row.active?'已绑定':'已停用'}}</td><td><el-button v-if="row.active" :disabled="locked||editor.dirty.value" @click="disable(row)">停用绑定</el-button></td></tr><tr v-if="!bindings.length"><td colspan="4">尚无收件地址绑定。</td></tr></tbody></table></div>
  </section>
  <p v-if="!can('tickets:read')">当前没有读取发送记录的权限；账号绑定与连接配置仍可按已有权限操作。</p>
  <section v-if="can('tickets:read')" class="admin-card"><div class="admin-card-head"><h2>邮件发送记录</h2><span>最近 100 条</span></div><p>“邮件服务器已受理”表示邮件已提交给服务器，实际收件情况仍需核对。结果待核对的邮件不会自动重发。</p>
   <div class="admin-table-scroll" tabindex="0" role="region" aria-label="邮件发送记录，可横向滚动"><table class="admin-table"><caption>邮件发送记录</caption><thead><tr><th scope="col">时间</th><th scope="col">收件地址</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">说明</th></tr></thead><tbody><tr v-for="row in deliveries" :key="row.id"><td>{{displayTime(row.created_at)}}</td><td>{{row.recipient}}</td><td>{{row.kind==='TEST'?'联调测试':'工单通知'}}</td><td>{{emailStatusLabels[row.status]}}</td><td>{{row.error_code?(emailErrorLabels[row.error_code]??row.error_code):row.status==='ACCEPTED'?'等待实际收件确认':'—'}}</td></tr><tr v-if="!deliveries.length"><td colspan="5">尚无邮件发送记录。</td></tr></tbody></table></div>
  </section>
 </template>
</section></template>
<style scoped>.email-page{display:grid;gap:18px;min-width:0}.email-actions,.email-bind{display:flex;gap:12px;align-items:end;flex-wrap:wrap;margin:16px}.email-bind label{display:grid;gap:8px;min-width:220px;flex:1}.email-page .admin-card>p{margin:14px 16px}.email-page dd,.email-actions span{overflow-wrap:anywhere}.email-page .admin-table{min-width:680px}@media(max-width:600px){.email-bind{display:grid}.email-bind label{min-width:0}.email-actions>*{min-height:44px}}.email-page :deep(.el-button),.email-page :deep(.el-input__wrapper),.email-page :deep(.el-select__wrapper){min-height:44px}.email-page .admin-table-scroll:focus-visible{outline:2px solid #2563eb;outline-offset:2px}.account-search,.account-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:16px}.account-search label{display:grid;gap:6px;min-width:0}.account-search input{min-height:44px;max-width:100%;box-sizing:border-box}.email-page{min-width:0}</style>
