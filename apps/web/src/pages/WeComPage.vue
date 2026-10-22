<script setup lang="ts">
import '../styles/adminPrototype.css'
import {computed,nextTick,onMounted,onUnmounted,ref,shallowRef,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {api,ApiError,isCancelled} from '../api/client'
import {can,session,contextGeneration,switchingTenant} from '../stores/session'
import {createTargetBindingWorkspace,emptyTargetBindingState,type TargetBindingState} from '../utils/channelTargetBinding'
import {useIdentityEditor} from '../utils/useIdentityEditor'
import {readUserPage,type UserPage} from '../utils/projectIdentity'
import {wecomStatusLabels,wecomErrorLabels,type WeComStatus,type WeComBinding,type WeComDelivery} from '../utils/wecom'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const status=ref<WeComStatus|null>(null),bindings=ref<WeComBinding[]>([]),deliveries=ref<WeComDelivery[]>([]),users=ref<UserPage|null>(null)
const loading=ref(false),error=ref<unknown>(null),userId=ref(''),memberId=ref('')
const accountSearchInput=ref<HTMLInputElement|null>(null)
const accountQuery=ref(''),appliedAccountQuery=ref(''),accountPage=ref(1)
const targetBinding=shallowRef<TargetBindingState>(emptyTargetBindingState())
const targetWorkspace=createTargetBindingWorkspace(api,value=>{targetBinding.value=value})
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,session.value?.mode,can('users:read'),can('users:write'),can('tickets:read'),switchingTenant.value]))
let epoch=0,disposed=false,controller:AbortController|null=null,confirmationEpoch=0
const confirming=ref(false)
const readable=()=>!disposed&&!switchingTenant.value&&!!session.value&&can('users:read')
function clearFacts(){targetWorkspace.reset();status.value=null;bindings.value=[];deliveries.value=[];users.value=null}
async function load(){
 clearFacts()
 const turn=++epoch,owner=scope.value;controller?.abort();controller=new AbortController();loading.value=false;error.value=null
 if(!readable())return false
 const signal=controller.signal;loading.value=true
 try{const [s,b,d,u]=await Promise.all([api.request<WeComStatus>('/wecom/status',{signal}),api.request<{items:WeComBinding[]}>('/wecom/bindings',{signal}),can('tickets:read')?api.request<{items:WeComDelivery[]}>('/wecom/deliveries',{signal}):Promise.resolve({items:[]}),api.request<UserPage>(`/identity/users?include_inactive=false&query=${encodeURIComponent(appliedAccountQuery.value)}&page=${accountPage.value}`,{signal})]);if(turn!==epoch||owner!==scope.value||!readable())return false
  if(!s||!u||!b||!d||!s.last_check||typeof s.last_check.ready!=='boolean'||typeof s.dispatch_enabled!=='boolean'||!Array.isArray(u.items)||s.tenant_id!==session.value?.tenant.id||u.tenant_id!==session.value?.tenant.id||!Array.isArray(b.items)||!Array.isArray(d.items)||d.items.some(row=>row.tenant_id!==s.tenant_id))throw new ApiError(200,'INVALID_RESPONSE','企业微信读取结果范围不一致','—')
  const userPage=readUserPage(u,s.tenant_id);if(userPage.page!==accountPage.value||userPage.items.some(user=>!user.active||!user.member_active)||new Set(userPage.items.map(user=>user.id)).size!==userPage.items.length)throw new ApiError(200,'INVALID_RESPONSE','账号选项页或启用状态不一致','—')
  status.value=s;bindings.value=b.items;deliveries.value=d.items;users.value=userPage;return true
 }catch(cause){if(turn===epoch){controller?.abort();if(!isCancelled(cause))error.value=cause}return false}finally{if(turn===epoch){loading.value=false;controller=null}}
}
function inputSnapshot(){return JSON.stringify([scope.value,userId.value,memberId.value,accountQuery.value,appliedAccountQuery.value,accountPage.value,status.value,targetBinding.value.binding])}
const editor=useIdentityEditor(load,{snapshot:inputSnapshot,preserveUnknown:true}),command=editor.command,message=editor.message
const locked=computed(()=>confirming.value||!readable()||!status.value||!users.value||loading.value||editor.busy()||!can('users:write'))
const memberInvalid=computed(()=>!!memberId.value&&(!/^[A-Za-z0-9_.@-]{1,64}$/.test(memberId.value)||memberId.value.toLowerCase()==='@all'))
const memberMismatch=computed(()=>{const b=targetBinding.value.binding;return !!memberId.value&&!!b&&'wecom_user_id' in b&&b.wecom_user_id.toLowerCase()!==memberId.value.toLowerCase()})
const bindingLocked=computed(()=>locked.value||targetBinding.value.busy||!targetBinding.value.ready||targetBinding.value.context?.userId!==userId.value)
async function readSelectedBinding(){
 if(!readable()||loading.value||editor.busy()||!status.value||!users.value?.items.some(user=>user.id===userId.value&&user.active&&user.member_active))return false
 return targetWorkspace.load({kind:'wecom',tenantId:session.value!.tenant.id,userId:userId.value,channelIdentity:status.value.corp_id})
}
watch(userId,()=>{targetWorkspace.reset();if(userId.value)void readSelectedBinding()},{flush:'sync'})
function clear(){confirmationEpoch++;confirming.value=false;targetWorkspace.reset();accountQuery.value='';appliedAccountQuery.value='';accountPage.value=1;epoch++;controller?.abort();status.value=null;bindings.value=[];deliveries.value=[];users.value=null;userId.value='';memberId.value='';error.value=null;loading.value=false;editor.clear()}
watch([userId,memberId],()=>{editor.dirty.value=!!userId.value||!!memberId.value},{flush:'sync'})
watch(scope,()=>{clear();if(readable())void load()},{flush:'sync'});onMounted(load);onUnmounted(()=>{disposed=true;clear();targetWorkspace.dispose()})
async function refresh(){const owner=scope.value,before=inputSnapshot();if(editor.busy()||!await editor.leave()||owner!==scope.value||disposed||before!==inputSnapshot())return;userId.value='';memberId.value='';editor.clear();await load()}
async function browseAccounts(nextPage:number,search=false){
 const owner=scope.value,before=inputSnapshot(),query=search?accountQuery.value.trim():appliedAccountQuery.value
 if(!readable()||loading.value||editor.busy()||!Number.isSafeInteger(nextPage)||nextPage<1||nextPage>10000||query.length>100||(!search&&nextPage>accountPage.value&&!users.value?.has_more))return
 if(!await editor.leave()||owner!==scope.value||disposed||loading.value||editor.busy()||before!==inputSnapshot())return
 userId.value='';memberId.value='';editor.clear();accountPage.value=nextPage;appliedAccountQuery.value=query;accountQuery.value=query;const ok=await load();if(ok&&owner===scope.value&&!disposed){await nextTick();if(owner===scope.value&&!disposed)accountSearchInput.value?.focus()}
}
async function check(){if(!locked.value&&!editor.dirty.value)await editor.save('/wecom/check','POST',{})}
async function test(){
 if(locked.value||editor.dirty.value||!status.value?.dispatch_enabled||!status.value.test_user_id)return
 const owner=scope.value,before=inputSnapshot(),turn=++confirmationEpoch;confirming.value=true
 try{await ElMessageBox.confirm(`将向 ${status.value.test_user_id} 发送“【MOP 联调测试】企业微信应用消息通道验证，无需处理”。`,'发送测试消息',{confirmButtonText:'发送',cancelButtonText:'取消'})}catch{return}finally{if(turn===confirmationEpoch)confirming.value=false}
 if(turn!==confirmationEpoch||owner!==scope.value||before!==inputSnapshot()||locked.value||editor.dirty.value||!status.value?.dispatch_enabled)return
 await editor.save('/wecom/test-messages','POST',{expected_version:0})
}
async function bind(){if(bindingLocked.value||memberInvalid.value||memberMismatch.value||!userId.value||!memberId.value||!users.value?.items.some(user=>user.id===userId.value&&user.active&&user.member_active))return;const old=targetBinding.value.binding;if(old&&'wecom_user_id' in old&&old.wecom_user_id.toLowerCase()!==memberId.value.toLowerCase())return;const owner=scope.value,selected=userId.value,value=memberId.value;const saved=await editor.save(`/wecom/bindings/${userId.value}`,'PUT',{expected_version:old?.version??0,wecom_user_id:memberId.value,active:true});if(saved&&owner===scope.value&&selected===userId.value&&value===memberId.value){userId.value='';memberId.value='';editor.dirty.value=false}}
async function disableSelected(){
 const b=targetBinding.value.binding,owner=scope.value,selected=userId.value,draft=memberId.value
 if(bindingLocked.value||!b?.active||!('wecom_user_id' in b))return
 const before=inputSnapshot(),turn=++confirmationEpoch;confirming.value=true
 try{await ElMessageBox.confirm('将停用所选账号的现有绑定，并放弃本页未保存的绑定编辑。企业微信既有登录会话也会失效。','停用所选账号绑定',{confirmButtonText:'确认停用',cancelButtonText:'取消',type:'warning'})}catch{return}finally{if(turn===confirmationEpoch)confirming.value=false}
 if(turn!==confirmationEpoch||before!==inputSnapshot()||owner!==scope.value||bindingLocked.value||userId.value!==selected||memberId.value!==draft||targetBinding.value.binding?.id!==b.id||targetBinding.value.binding.version!==b.version)return
 userId.value='';memberId.value='';editor.clear();await editor.save(`/wecom/bindings/${selected}`,'PUT',{expected_version:b.version,wecom_user_id:b.wecom_user_id,active:false})
}
async function disable(row:WeComBinding){if(!bindings.value.some(item=>item===row)||!row.active)return;if(locked.value||editor.dirty.value)return;await editor.save(`/wecom/bindings/${row.user_id}`,'PUT',{expected_version:row.version,wecom_user_id:row.wecom_user_id,active:false})}
</script>
<template><section :aria-busy="loading" class="identity-page admin-prototype wecom-page">
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
   <form class="account-search" @submit.prevent="browseAccounts(1,true)"><label>搜索平台账号<input ref="accountSearchInput" v-model="accountQuery" aria-label="搜索平台账号" maxlength="100" :disabled="loading||editor.busy()" /></label><el-button native-type="submit" :disabled="loading||editor.busy()">搜索账号</el-button></form>
   <div class="account-pages" aria-label="账号选项分页"><el-button :disabled="loading||editor.busy()||accountPage<=1" @click="browseAccounts(accountPage-1)">上一页账号</el-button><span role="status">账号选项第 {{accountPage}} 页，本页 {{users?.items.length??0}} 个{{users?.has_more?'，还有下一页':''}}</span><el-button :disabled="loading||editor.busy()||accountPage>=10000||!users?.has_more" @click="browseAccounts(accountPage+1)">下一页账号</el-button></div>
   <p v-if="users&&!users.items.length">当前搜索或页面没有可选的启用账号。可更改搜索，或返回上一页。</p>
   <div v-if="userId" class="target-binding-status" :aria-busy="targetBinding.busy">
    <p v-if="targetBinding.busy" role="status">正在读取所选账号的精确绑定与版本…</p>
    <p v-else-if="targetBinding.ready" role="status">{{targetBinding.binding?'已读取所选账号的绑定，版本 '+targetBinding.binding.version+'；'+(targetBinding.binding.active?'当前已启用。':'当前已停用。'):'已明确读取：所选账号在当前通道尚未绑定。'}}</p>
    <p v-else role="status">所选账号绑定尚未确认，不能保存。读取失败不代表未绑定。</p>
    <ApiErrorNotice v-if="targetBinding.error" :error="targetBinding.error" />
    <el-button v-if="targetBinding.ready&&targetBinding.binding?.active" :disabled="bindingLocked" @click="disableSelected">停用所选账号绑定</el-button>
    <el-button :disabled="loading||editor.busy()||targetBinding.busy" @click="readSelectedBinding">重新读取所选账号绑定</el-button>
   </div>
   <p v-if="targetBinding.ready&&targetBinding.binding&&'wecom_user_id' in targetBinding.binding">既有企业微信 UserID：{{targetBinding.binding.wecom_user_id}}。本入口只支持保持此成员并启用/停用，不能替换成员。</p>
   <p v-if="memberInvalid" role="alert">企业微信 UserID 仅支持1–64个英文字母、数字、下划线、点、@或连字符，不能使用 @all。</p>
   <p v-else-if="memberMismatch" role="alert">输入成员与既有绑定不同；本入口不能替换成员，请核对后使用原 UserID。</p>
   <form class="wecom-bind" @submit.prevent="bind"><label>平台账号<el-select v-model="userId" aria-label="绑定的平台账号" :disabled="locked" filterable><el-option v-for="user in users?.items" :key="user.id" :value="user.id" :label="`${user.display_name}（${user.username??user.id}）`"/></el-select></label><label>企业微信 UserID<el-input v-model="memberId" aria-label="绑定的企业微信UserID" maxlength="64" :disabled="locked"/></label><el-button native-type="submit" type="primary" :disabled="bindingLocked||memberInvalid||memberMismatch||!userId||!memberId">核验并绑定</el-button></form>
   <div class="admin-table-scroll" tabindex="0" role="region" aria-label="企业微信账号绑定，可横向滚动"><table class="admin-table"><caption>企业微信账号绑定</caption><thead><tr><th scope="col">平台用户</th><th scope="col">企业微信成员</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="row in bindings" :key="row.id"><td>{{row.display_name??row.user_id}}</td><td>{{row.wecom_user_id}}</td><td>{{row.active?'已绑定':'已停用'}}</td><td><el-button v-if="row.active" :disabled="locked||editor.dirty.value" @click="disable(row)">停用绑定</el-button></td></tr><tr v-if="!bindings.length"><td colspan="4">尚无账号绑定。</td></tr></tbody></table></div>
  </section>
  <p v-if="!can('tickets:read')">当前没有读取发送记录的权限；账号绑定与连接配置仍可按已有权限操作。</p>
  <section v-if="can('tickets:read')" class="admin-card"><div class="admin-card-head"><h2>消息发送记录</h2><span>最近 100 条</span></div><p>“企业微信已受理”表示接口接受了请求。实际收到或已读需要单独核对；结果待核对的消息不会自动重发。</p>
   <div class="admin-table-scroll" tabindex="0" role="region" aria-label="企业微信发送记录，可横向滚动"><table class="admin-table"><caption>企业微信发送记录</caption><thead><tr><th scope="col">时间</th><th scope="col">接收人</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">说明</th></tr></thead><tbody><tr v-for="row in deliveries" :key="row.id"><td>{{displayTime(row.created_at)}}</td><td>{{row.recipient}}</td><td>{{row.kind==='TEST'?'联调测试':'工单通知'}}</td><td>{{wecomStatusLabels[row.status]}}</td><td>{{row.error_code?(wecomErrorLabels[row.error_code]??row.error_code):row.status==='ACCEPTED'?'等待实际接收确认':'—'}}</td></tr><tr v-if="!deliveries.length"><td colspan="5">尚无发送记录。</td></tr></tbody></table></div>
  </section>
 </template>
</section></template>
<style scoped>.wecom-page{display:grid;gap:18px}.wecom-actions,.wecom-bind{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.wecom-bind{margin:18px 16px}.wecom-page .admin-card>p{margin:14px 16px}.wecom-bind label{display:grid;gap:8px;min-width:220px;flex:1}.wecom-page dd{overflow-wrap:anywhere}.wecom-page .admin-table{min-width:680px}@media(max-width:600px){.wecom-bind{display:grid}.wecom-bind label{min-width:0}.wecom-actions>*{min-height:44px}}.wecom-page :deep(.el-button),.wecom-page :deep(.el-input__wrapper),.wecom-page :deep(.el-select__wrapper){min-height:44px}.wecom-page .admin-table-scroll:focus-visible{outline:2px solid #2563eb;outline-offset:2px}.account-search,.account-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:16px}.account-search label{display:grid;gap:6px;min-width:0}.account-search input{min-height:44px;max-width:100%;box-sizing:border-box}.wecom-page{min-width:0}.target-binding-status{margin:16px;overflow-wrap:anywhere}</style>
