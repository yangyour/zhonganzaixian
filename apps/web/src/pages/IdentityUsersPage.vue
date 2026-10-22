<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { can, session, contextGeneration, switchingTenant } from '../stores/session'
import { baseRoleLabels, readRolePage, readUserPage, type ProjectUser, type RolePage, type UserPage, type IdentityBinding, type IdentityMembership } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import {readMemberUser,readMemberList,readMemberRoles,readMemberRelations,invalidMemberRead,type MemberReadTarget,type MemberListTarget} from '../utils/identityUserRead'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const users = ref<UserPage | null>(null), roles = ref<RolePage | null>(null), selected = ref<ProjectUser | null>(null), opened = ref(false), error = ref<unknown>(null), loading = ref(false), query = ref(''), includeInactive = ref(true), page = ref(1)
const memberships = ref<IdentityMembership[]>([]), bindings = ref<IdentityBinding[]>([]), tab = ref('profile'), detailLoading = ref(false)
const extraTenant = ref(''), extraRole = ref(''), extraRoles = ref<RolePage | null>(null), extraActive = ref(true)
const form = reactive({ username: '', display_name: '', password: '', active: true, role_id: '', member_active: true, issuer: '', subject: '' })
let controller = new AbortController(), readGeneration = 0, extraGeneration = 0, disposed = false
const editor = useIdentityEditor(load,{snapshot:inputSnapshot,preserveUnknown:true}), command = editor.command, message = editor.message
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.user.role, session.value?.tenant.id, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), (session.value?.allowed_tenants ?? []).map(t => t.id).sort(), contextGeneration.value, switchingTenant.value]))
const canRead = () => !disposed && !switchingTenant.value && can('users:read')
const canWrite = () => !loading.value&&canRead() && can('users:write') && opened.value && !detailLoading.value
// Sensitive input remains transient in memory and is never logged/exported.
function inputSnapshot(){return JSON.stringify([scope.value,opened.value,selected.value,form,query.value,includeInactive.value,page.value,tab.value,extraTenant.value,extraRole.value,extraActive.value])}
function clearView() {
  readGeneration++; extraGeneration++; controller.abort(); if (!disposed) controller = new AbortController()
  users.value = null; roles.value = null; selected.value = null; opened.value = false; error.value = null; loading.value = false; detailLoading.value = false
  memberships.value = []; bindings.value = []; extraTenant.value = ''; extraRole.value = ''; extraRoles.value = null; extraActive.value = true
  query.value = ''; includeInactive.value = true; page.value = 1; tab.value = 'profile'
  Object.assign(form, { username: '', display_name: '', password: '', active: true, role_id: '', member_active: true, issuer: '', subject: '' })
  editor.clear()
}
const appliedQuery=ref(''),appliedInactive=ref(true),mainTarget=ref<MemberReadTarget|null>(null),retrying=ref(false)
const pageHeading=ref<HTMLElement|null>(null),editorHeading=ref<HTMLElement|null>(null),readFeedback=ref<HTMLElement|null>(null),queryInput=ref<HTMLInputElement|null>(null)
let recoveryEpoch=0,knownUser:ProjectUser|null=null
const copy=<T,>(v:T):T=>JSON.parse(JSON.stringify(v)) as T
const unapplied=computed(()=>query.value!==appliedQuery.value||includeInactive.value!==appliedInactive.value)
const mainBusy=computed(()=>loading.value||detailLoading.value)
function formSnapshot(){return JSON.stringify([scope.value,opened.value,selected.value?.id,form,tab.value,extraTenant.value,extraRole.value,extraActive.value])}
function showReadRetry(){return canRead()&&!mainBusy.value&&mainTarget.value!==null&&!['sending','uncertain'].includes(command.value.state)}
function cancelRead(){if(!mainBusy.value||disposed)return false;readGeneration++;controller.abort();loading.value=false;detailLoading.value=false;error.value=null;if(mainTarget.value?.kind==='DETAIL'||mainTarget.value?.user)opened.value=false;return true}
async function focusResult(owner:string,ok:boolean,turn=readGeneration){if(turn!==readGeneration||!ok&&!error.value)return;await nextTick();if(disposed||turn!==readGeneration||owner!==scope.value||mainBusy.value)return;(error.value?readFeedback.value:opened.value?editorHeading.value:pageHeading.value)?.focus()}
function stopRead(){readGeneration++;extraGeneration++;controller.abort();controller=new AbortController();loading.value=false;detailLoading.value=false;mainTarget.value=null;recoveryEpoch++;retrying.value=false}
function floor(user:ProjectUser){if(knownUser?.id===user.id)readMemberUser(user,session.value!.tenant.id,knownUser);return user}
async function relations(user:ProjectUser,turn:number,signal:AbortSignal,owner:string,before:string){
 const needRoles=can('roles:read')&&!roles.value
 const [members,ids,catalog]=await Promise.all([api.request<unknown>(`/identity/users/${user.id}/memberships`,{signal}),api.request<unknown>(`/identity/users/${user.id}/external-identities`,{signal}),needRoles?api.request<unknown>('/identity/roles',{signal}):Promise.resolve(null)])
 if(turn!==readGeneration||owner!==scope.value||!canRead()||signal.aborted)return false
 if(before!==formSnapshot())throw invalidMemberRead()
 const values=readMemberRelations(members,ids,new Set([session.value!.tenant.id,...session.value!.allowed_tenants.map(t=>t.id)]))
 const newRoles=catalog?readMemberRoles(catalog,session.value!.tenant.id):null
 memberships.value=values.memberships;bindings.value=values.bindings;if(newRoles)roles.value=newRoles
 selected.value=user;Object.assign(form,{username:user.username||'',display_name:user.display_name,active:user.active,password:'',role_id:user.role_id,member_active:user.member_active,issuer:'',subject:''});return true
}
async function runRead(target:MemberReadTarget){
 if(!canRead())return false
 stopRead();const turn=readGeneration,owner=scope.value,signal=controller.signal,tenant=session.value!.tenant.id;mainTarget.value=copy(target);error.value=null
 loading.value=target.kind==='LIST';detailLoading.value=!!target.user;const before=formSnapshot()
 if(target.kind==='LIST'){users.value=null;roles.value=null;page.value=target.page}
 try{
  if(target.kind==='LIST'){
   const raw=await api.request<unknown>(`/identity/users?query=${encodeURIComponent(target.query)}&include_inactive=${target.includeInactive}&page=${target.page}`,{signal})
   if(turn!==readGeneration||owner!==scope.value||!canRead()||signal.aborted)return false
   users.value=readMemberList(raw,tenant,target,knownUser);appliedQuery.value=target.query;appliedInactive.value=target.includeInactive
   if(can('roles:read')){const rawRoles=await api.request<unknown>('/identity/roles',{signal});if(turn!==readGeneration||owner!==scope.value||!canRead()||signal.aborted)return false;roles.value=readMemberRoles(rawRoles,tenant)}
   if(target.user){const rawUser=await api.request<unknown>(`/identity/users/${target.user.id}`,{signal});if(turn!==readGeneration||owner!==scope.value||!canRead()||signal.aborted)return false;const user=floor(readMemberUser(rawUser,tenant,target.user));knownUser=copy(user);target={...target,user};mainTarget.value=copy(target);if(!await relations(user,turn,signal,owner,before))return false}
  }else{floor(target.user);knownUser=copy(target.user);if(!await relations(target.user,turn,signal,owner,before))return false}
  if(turn!==readGeneration||owner!==scope.value||!canRead()||signal.aborted)return false;mainTarget.value=null;return true
 }catch(e){if(turn===readGeneration&&owner===scope.value){controller.abort();if(!isCancelled(e))error.value=e;if(target.user)opened.value=false}return false}
 finally{if(turn===readGeneration){loading.value=false;detailLoading.value=false}}
}
async function readAndFocus(target:MemberReadTarget,owner:string){const pending=runRead(target),turn=readGeneration,ok=await pending;await focusResult(owner,ok,turn);return ok}
async function load(){const target:MemberListTarget={kind:'LIST',query:appliedQuery.value,includeInactive:appliedInactive.value,page:page.value,user:selected.value?copy(selected.value):null};return runRead(target)}
async function retryRead(){
 if(!showReadRetry()||editor.busy()||retrying.value||!mainTarget.value)return false
 const owner=scope.value,before=inputSnapshot(),target=copy(mainTarget.value),turn=++recoveryEpoch;retrying.value=true
 try{if(!await editor.leave()||owner!==scope.value||before!==inputSnapshot()||disposed)return false;editor.clear();selected.value=target.user?copy(target.user):null;opened.value=!!target.user;tab.value='profile';memberships.value=[];bindings.value=[];form.password='';return await readAndFocus(target,owner)}finally{if(turn===recoveryEpoch)retrying.value=false}
}
watch(scope,()=>{clearView();appliedQuery.value='';appliedInactive.value=true;mainTarget.value=null;knownUser=null;recoveryEpoch++;retrying.value=false},{flush:'sync'})
onUnmounted(()=>{disposed=true;clearView();mainTarget.value=null;knownUser=null;recoveryEpoch++})
onMounted(load)
async function edit(user?:ProjectUser){
 const owner=scope.value,before=inputSnapshot(),target=user?JSON.stringify(user):null
 if(user&&!users.value?.items.some(item=>item===user))return
 if(!canRead()||(!user&&(!can('users:write')||!can('roles:read')||!roles.value))||editor.busy()||!await editor.leave()||owner!==scope.value||before!==inputSnapshot()||user&&(!users.value?.items.some(item=>item===user)||JSON.stringify(user)!==target))return
 if(user){try{floor(user)}catch(e){error.value=e;return}}
 stopRead();editor.clear();selected.value=user??null;opened.value=true;tab.value='profile';memberships.value=[];bindings.value=[];extraTenant.value='';extraRoles.value=null;extraRole.value='';extraActive.value=true;editor.dirty.value=false;form.password='';error.value=null
 if(user){await readAndFocus({kind:'DETAIL',user:copy(user)},owner)}
 else{Object.assign(form,{username:'',display_name:'',password:'',active:true,role_id:roles.value?.items.find(r=>r.enabled&&r.base_role==='viewer')?.id||'',member_active:true,issuer:'',subject:''});await focusResult(owner,true)}
}
async function saveProfile() { const owner=scope.value;if (!canWrite() || (!selected.value && !can('roles:read'))) return; const user = selected.value;if(!user&&!roles.value?.items.some(r=>r.id===form.role_id&&r.enabled))return; if (await editor.save(user ? `/identity/users/${user.id}` : '/identity/users', user ? 'PATCH' : 'POST', user ? { expected_version: user.version, display_name: form.display_name, active: form.active } : { expected_version: 0, username: form.username, display_name: form.display_name, password: form.password, active: form.active, role_id: form.role_id })&&owner===scope.value&&!disposed) { form.password = ''; if (!user){opened.value = false;await focusResult(owner,true)} } }
async function action(kind: 'password' | 'unlock' | 'revoke-sessions' | 'local-account') { if (!canWrite() || !selected.value) return; const owner = scope.value, user = selected.value!, originalPassword=form.password; const ok=await editor.save(`/identity/users/${user.id}/${kind}`, 'POST', { expected_version: user.version, ...(kind === 'password' ? { new_password: form.password } : kind === 'local-account' ? { username: form.username, password: form.password } : {}) }); if (ok&&owner === scope.value&&form.password===originalPassword) form.password = '' }
async function assign() { if (!canWrite() || !can('roles:read') || !selected.value || !roles.value?.items.some(r=>r.id===form.role_id&&r.enabled)) return; const user = selected.value!; await editor.save(`/identity/users/${user.id}/memberships/${session.value!.tenant.id}`, 'PUT', { expected_version: user.permission_version, role_id: form.role_id, active: form.member_active }) }
async function tenantRoles() {
  const turn = ++extraGeneration, owner = scope.value, userId = selected.value?.id, target = extraTenant.value
  extraRole.value = ''; extraRoles.value = null
  if (!canWrite() || !can('roles:read') || !userId || !target || !session.value?.allowed_tenants.some(t => t.id === target)) return
  const current = () => turn === extraGeneration && owner === scope.value && userId === selected.value?.id && target === extraTenant.value && canWrite() && can('roles:read')
  try { const result = readRolePage(await api.request(`/identity/roles?tenant_id=${encodeURIComponent(target)}`, { signal: controller.signal }), target); if (current()) { extraRoles.value = result; extraRole.value = result.items.find(x => x.enabled && x.base_role === 'viewer')?.id ?? '' } }
  catch (e) { if (current() && !isCancelled(e)) error.value = e }
}
async function grantTenant() { if (!canWrite() || !can('roles:read') || !selected.value || !extraTenant.value || extraRoles.value?.tenant_id !== extraTenant.value || !session.value?.allowed_tenants.some(t => t.id === extraTenant.value) || !extraRoles.value.items.some(r => r.id === extraRole.value && r.enabled)) return; const target = extraTenant.value, previous = memberships.value.find(x => x.tenant_id === target); await editor.save(`/identity/users/${selected.value!.id}/memberships/${target}`, 'PUT', { expected_version: previous?.permission_version ?? 0, role_id: extraRole.value, active: extraActive.value }) }
async function bind() { if (!canWrite() || !selected.value) return; await editor.save(`/identity/users/${selected.value!.id}/external-identities`, 'POST', { expected_version: 0, issuer: form.issuer, subject: form.subject }) }
async function disable(binding: IdentityBinding) { if (!canWrite() || !selected.value || !binding.active || !bindings.value.some(row=>row===binding)) return; await editor.save(`/identity/users/${selected.value!.id}/external-identities/${binding.id}/disable`, 'POST', { expected_version: binding.version }) }
async function search(){const owner=scope.value,before=inputSnapshot(),target:MemberListTarget={kind:'LIST',query:query.value,includeInactive:includeInactive.value,page:1,user:null};if(!canRead()||editor.busy()||!await editor.leave()||owner!==scope.value||before!==inputSnapshot())return false;editor.clear();selected.value=null;opened.value=false;editor.dirty.value=false;return await readAndFocus(target,owner)}
async function move(delta:number){if(![-1,1].includes(delta)||unapplied.value||delta<0&&page.value<=1||delta>0&&!users.value?.has_more)return false;const owner=scope.value,before=inputSnapshot(),target:MemberListTarget={kind:'LIST',query:appliedQuery.value,includeInactive:appliedInactive.value,page:page.value+delta,user:null};if(!canRead()||editor.busy()||!await editor.leave()||owner!==scope.value||before!==inputSnapshot())return false;editor.clear();selected.value=null;opened.value=false;editor.dirty.value=false;return await readAndFocus(target,owner)}
async function closeEditor(){const owner=scope.value,before=inputSnapshot();if(editor.busy()||!await editor.leave()||owner!==scope.value||before!==inputSnapshot())return;stopRead();editor.clear();opened.value=false;selected.value=null;form.password='';await focusResult(owner,true)}
async function restoreQuery(){if(editor.busy()||disposed)return;const owner=scope.value;query.value=appliedQuery.value;includeInactive.value=appliedInactive.value;await nextTick();if(owner===scope.value&&!disposed)queryInput.value?.focus()}
</script>
<template><section class="identity-page admin-prototype" :aria-busy="mainBusy">
  <div class="page-heading"><div><h1 ref="pageHeading" tabindex="-1">用户与成员授权</h1><p>管理当前租户的账号、成员角色及统一身份绑定。</p></div><el-button v-if="can('users:write') && can('roles:read')" type="primary" :disabled="editor.busy()||!roles" @click="edit()">新建用户</el-button></div>
  <p v-if="!can('users:read')">当前会话无用户读取权限。</p><p v-if="canRead()&&can('roles:read')&&!roles&&!loading">角色目录尚未完整读取，请查询成员或恢复原读取后再新建账号。</p>
  <ReadRecoveryActions :busy="mainBusy?'read':null" :can-retry="showReadRetry()" :scope-key="scope" label="成员列表详情" :cancel="cancelRead" :retry="retryRead" /><p v-if="loading" role="status">正在读取原成员列表与角色目录…</p><p v-if="!users&&!mainBusy&&!error&&canRead()">成员列表尚未读取成功，不能据此认定没有成员。</p><p v-if="mainTarget&&!mainBusy">原读取目标：{{mainTarget.kind==='LIST'?'成员列表第 '+mainTarget.page+' 页':'所选成员关联详情'}}。恢复不使用尚未查询的新筛选。</p><div ref="readFeedback" tabindex="-1" role="group" aria-label="成员读取结果"><ApiErrorNotice :error="error" /></div><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
  <div class="admin-stats" v-if="users">
    <div class="admin-stat"><span>本页成员</span><strong>{{ users.items.length }}</strong><small>当前租户 · 第 {{ page }} 页</small></div>
    <div class="admin-stat"><span>账号与成员均启用</span><strong>{{ users.items.filter(u => u.active && u.member_active).length }}</strong><small>有效账号及成员关系</small></div>
    <div class="admin-stat"><span>停用记录</span><strong>{{ users.items.filter(u => !u.active || !u.member_active).length }}</strong><small>本页账号或成员已停用</small></div>
    <div class="admin-stat"><span>当前授权版本</span><strong>{{ session?.permission_version ?? '—' }}</strong><small>每次请求重新核验授权</small></div>
  </div>
  <section class="admin-card">
    <form v-form-feedback class="identity-form identity-toolbar admin-toolbar" @submit.prevent="search"><label>搜索账号或姓名<input ref="queryInput" v-model="query" :disabled="editor.busy()" maxlength="100" placeholder="输入账号 / 姓名" /></label><label class="check"><input v-model="includeInactive" :disabled="editor.busy()" type="checkbox" />包含停用记录</label><el-button native-type="submit" type="primary" :disabled="editor.busy()">查询</el-button><span class="muted">{{ session?.tenant.name }}</span></form>
    <p v-if="unapplied" role="status">筛选条件已修改但尚未查询；翻页暂不可用。<button type="button" :disabled="editor.busy()" @click="restoreQuery">恢复已应用筛选</button></p><div class="admin-table-scroll" tabindex="0" role="region" aria-label="成员列表，可横向滚动"><table class="admin-table"><caption class="member-caption">当前租户授权成员页</caption><thead><tr><th scope="col">成员 / 登录账号</th><th scope="col">角色</th><th scope="col">租户范围</th><th scope="col">登录方式</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
      <tr v-for="user in users?.items" :key="user.id"><td><div class="admin-member"><span class="admin-avatar" aria-hidden="true">{{ user.display_name.slice(0, 1) }}</span><div><strong>{{ user.display_name }}</strong><small>{{ user.username || '未开通本地账号' }}</small></div></div></td><td>{{ user.role_name }}<small>{{ baseRoleLabels[user.base_role] }}</small></td><td>{{ session?.tenant.name }}<small>当前授权成员关系</small></td><td>{{ user.local_login_enabled ? '本地账号' : '本地登录未开通' }}</td><td><el-tag :type="user.active && user.member_active ? 'success' : 'info'">{{ !user.active ? '账号停用' : !user.member_active ? '成员停用' : '正常' }}</el-tag></td><td><div class="admin-row-actions"><el-button :disabled="editor.busy()" @click="edit(user)">管理账号</el-button></div></td></tr>
    </tbody></table><p v-if="users && !users.items.length" class="admin-empty">没有匹配的用户。</p></div>
    <div class="admin-footer"><span>本页已加载 {{ users?.items.length ?? '—' }} 条成员记录</span><div class="identity-actions"><el-button :disabled="page <= 1 || editor.busy() || unapplied" @click="move(-1)">上一页</el-button><span>第 {{ page }} 页</span><el-button :disabled="!users?.has_more || editor.busy() || unapplied" @click="move(1)">下一页</el-button></div></div>
  </section>
  <p v-if="opened && detailLoading" role="status">正在读取所选账号详情…</p>
  <section v-if="opened && !detailLoading" class="identity-card identity-form admin-editor-panel" aria-label="用户账号编辑与授权"><div class="admin-editor-title"><h2 ref="editorHeading" tabindex="-1">{{ selected ? selected.display_name : '新建用户' }}</h2><el-button :disabled="editor.busy()" @click="closeEditor">关闭编辑</el-button></div>
    <IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
    <el-tabs v-model="tab"><el-tab-pane label="基本资料" name="profile" /><el-tab-pane v-if="selected" label="登录与会话" name="security" /><el-tab-pane v-if="selected" label="成员授权" name="membership" /><el-tab-pane v-if="selected" label="统一身份绑定" name="bindings" /></el-tabs>
    <form v-form-feedback v-if="tab === 'profile'" @submit.prevent="saveProfile" @input="editor.dirty.value = true" @change="editor.dirty.value = true"><fieldset :disabled="editor.busy() || !can('users:write')"><label v-if="!selected">登录账号<input v-model="form.username" required pattern="[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}" maxlength="64" autocomplete="off" /></label><label>显示名称<input v-model="form.display_name" required maxlength="100" /></label><label v-if="!selected">初始密码<input v-model="form.password" type="password" required maxlength="1024" autocomplete="new-password" /></label><label v-if="!selected">所属角色<select v-model="form.role_id" required><option v-for="role in roles?.items.filter(r => r.enabled)" :key="role.id" :value="role.id">{{ role.name }}</option></select></label><label class="check"><input v-model="form.active" type="checkbox" />启用账号</label><p class="muted">账号基本资料与启用状态为平台全局属性；登录账号创建后不可修改。</p><el-button v-if="can('users:write')" native-type="submit" type="primary">保存账号</el-button></fieldset></form>
    <div v-if="tab === 'security' && selected"><p>重置密码或注销会话后，该账号已建立的登录会话将立即失效。忘记密码请由平台管理员核实身份后重置。</p><form v-form-feedback @submit.prevent="action(selected.local_login_enabled ? 'password' : 'local-account')" @input="editor.dirty.value = true"><fieldset :disabled="editor.busy() || !can('users:write') || selected.id === session?.user.id"><label v-if="!selected.local_login_enabled">开通登录账号<input v-model="form.username" required pattern="[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}" maxlength="64" /></label><label>新密码<input v-model="form.password" type="password" required autocomplete="new-password" maxlength="1024" /></label><el-button v-if="can('users:write')" native-type="submit" type="primary">{{ selected.local_login_enabled ? '重置密码' : '开通本地登录' }}</el-button></fieldset></form><p v-if="selected.id === session?.user.id">当前登录账号的密码请前往“我的账号”修改。</p><p>锁定截至：{{ selected.locked_until || '未锁定' }}</p><div v-if="can('users:write')" class="identity-actions"><el-button :disabled="editor.busy() || !selected.local_login_enabled" @click="action('unlock')">解除登录锁定</el-button><el-button :disabled="editor.busy()" @click="action('revoke-sessions')">注销全部会话</el-button></div></div>
    <div v-if="tab === 'membership' && selected"><p v-for="member in memberships" :key="member.tenant_id">{{ member.tenant_name }} · {{ baseRoleLabels[member.base_role] }} · {{ member.active ? '有效' : '停用' }}</p><p class="muted">配置该账号在当前租户及其他已授权租户下的成员角色与启用状态。</p><form v-form-feedback @submit.prevent="assign" @change="editor.dirty.value = true"><fieldset :disabled="editor.busy() || !can('users:write') || !can('roles:read')"><label>当前租户角色<select v-model="form.role_id" required><option v-for="role in roles?.items.filter(r => r.enabled)" :key="role.id" :value="role.id">{{ role.name }}</option></select></label><label class="check"><input v-model="form.member_active" type="checkbox" />启用当前租户成员资格</label><el-button v-if="can('users:write') && can('roles:read')" native-type="submit" type="primary">保存成员授权</el-button></fieldset></form><form v-form-feedback v-if="can('users:write') && can('roles:read') && (session?.allowed_tenants.length ?? 0) > 1" @submit.prevent="grantTenant" @change="editor.dirty.value = true"><h3>管理其他授权租户的成员资格</h3><fieldset :disabled="editor.busy()"><label>目标租户<select v-model="extraTenant" required @change="tenantRoles"><option value="">请选择</option><option v-for="tenant in session?.allowed_tenants.filter(x => x.id !== session?.tenant.id)" :key="tenant.id" :value="tenant.id">{{ tenant.name }}</option></select></label><label>目标租户角色<select v-model="extraRole" required><option v-for="role in extraRoles?.items.filter(x => x.enabled)" :key="role.id" :value="role.id">{{ role.name }}</option></select></label><label class="check"><input v-model="extraActive" type="checkbox" />启用目标租户成员资格</label><el-button native-type="submit" type="primary" :disabled="!extraRole">保存目标租户授权</el-button></fieldset></form></div>
    <div v-if="tab === 'bindings' && selected"><p>通过 OIDC 签发方（Issuer）与唯一主体标识（Subject）精确绑定外部统一身份。绑定前请确保平台已启用统一身份认证服务。</p><article v-for="binding in bindings" :key="binding.id" class="identity-card"><p>{{ binding.issuer }}</p><p>Subject：{{ binding.subject }}</p><el-tag>{{ binding.active ? '有效绑定' : '已停用' }}</el-tag><el-button v-if="binding.active && can('users:write')" :disabled="editor.busy()" @click="disable(binding)">停用绑定</el-button></article><form v-form-feedback v-if="can('users:write')" @submit.prevent="bind" @input="editor.dirty.value = true"><fieldset :disabled="editor.busy()"><label>OIDC Issuer<input v-model="form.issuer" type="url" required maxlength="2000" placeholder="https://identity.example.com" /></label><label>外部 Subject<input v-model="form.subject" required maxlength="255" /></label><el-button native-type="submit" type="primary">添加身份绑定</el-button></fieldset></form></div>
  </section>
</section></template>
<style scoped>
.identity-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.identity-page .el-button,.identity-page button{min-height:44px;height:auto;white-space:normal}.member-caption{text-align:left;padding:10px}

.admin-member{min-width:240px}
.admin-table :is(th,td){min-width:120px}
.admin-table :is(th,td):nth-child(3){min-width:180px}
.admin-table td:nth-child(2){min-width:180px;max-width:300px;overflow-wrap:anywhere}
.admin-editor-title h2{min-width:0;overflow-wrap:anywhere}.admin-editor-title>.el-button{flex-shrink:0}.admin-member>div{min-width:0;overflow-wrap:anywhere}
@media(max-width:650px){.admin-prototype.identity-page .admin-editor-panel{inset:0;width:100%;height:100dvh;max-height:100dvh;padding:0 16px 20px;z-index:1000}.admin-prototype.identity-page .admin-editor-title{top:0;margin-bottom:16px;padding:14px 0;min-height:72px}.admin-prototype.identity-page .admin-editor-title>.el-button{min-height:44px;min-width:88px;margin-left:0}}
</style>
