<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { can, session, contextGeneration, switchingTenant } from '../stores/session'
import { baseRoleLabels, readRolePage, readUserPage, type ProjectUser, type RolePage, type UserPage, type IdentityBinding, type IdentityMembership } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const users = ref<UserPage | null>(null), roles = ref<RolePage | null>(null), selected = ref<ProjectUser | null>(null), opened = ref(false), error = ref<unknown>(null), loading = ref(false), query = ref(''), includeInactive = ref(true), page = ref(1)
const memberships = ref<IdentityMembership[]>([]), bindings = ref<IdentityBinding[]>([]), tab = ref('profile'), detailLoading = ref(false)
const extraTenant = ref(''), extraRole = ref(''), extraRoles = ref<RolePage | null>(null), extraActive = ref(true)
const form = reactive({ username: '', display_name: '', password: '', active: true, role_id: '', member_active: true, issuer: '', subject: '' })
let controller = new AbortController(), readGeneration = 0, extraGeneration = 0, disposed = false
const editor = useIdentityEditor(load), command = editor.command, message = editor.message
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.user.role, session.value?.tenant.id, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), (session.value?.allowed_tenants ?? []).map(t => t.id).sort(), contextGeneration.value, switchingTenant.value]))
const canRead = () => !disposed && !switchingTenant.value && can('users:read')
const canWrite = () => canRead() && can('users:write') && opened.value && !detailLoading.value
function clearView() {
  readGeneration++; extraGeneration++; controller.abort(); if (!disposed) controller = new AbortController()
  users.value = null; roles.value = null; selected.value = null; opened.value = false; error.value = null; loading.value = false; detailLoading.value = false
  memberships.value = []; bindings.value = []; extraTenant.value = ''; extraRole.value = ''; extraRoles.value = null; extraActive.value = true
  query.value = ''; includeInactive.value = true; page.value = 1; tab.value = 'profile'
  Object.assign(form, { username: '', display_name: '', password: '', active: true, role_id: '', member_active: true, issuer: '', subject: '' })
  editor.clear()
}
watch(scope, clearView, { flush: 'sync' })
onUnmounted(() => { disposed = true; clearView() })
async function load() { const turn = ++readGeneration; users.value = null; roles.value = null; loading.value = false; error.value = null; if (!canRead()) return false; loading.value = true; detailLoading.value = !!selected.value; try {
  const tenant = session.value!.tenant.id
  const value = readUserPage(await api.request(`/identity/users?query=${encodeURIComponent(query.value)}&include_inactive=${includeInactive.value}&page=${page.value}`, { signal: controller.signal }), tenant)
  if (turn !== readGeneration || !canRead()) return false; users.value = value
  if (can('roles:read')) { const freshRoles = readRolePage(await api.request('/identity/roles', { signal: controller.signal }), tenant); if (turn !== readGeneration || !canRead()) return false; roles.value = freshRoles }
  if (selected.value) { const fresh = await api.request<ProjectUser>(`/identity/users/${selected.value.id}`, { signal: controller.signal }); if (turn !== readGeneration || !canRead()) return false; selected.value = fresh; await detail(fresh, turn) }
  return turn === readGeneration
} catch (e) { if (turn === readGeneration && !isCancelled(e)) { error.value = e; opened.value = false } return false } finally { if (turn === readGeneration) { loading.value = false; detailLoading.value = false } } }
onMounted(load)
async function detail(user: ProjectUser, turn: number) {
  const [members, ids] = await Promise.all([api.request<{items: IdentityMembership[]}>(`/identity/users/${user.id}/memberships`, { signal: controller.signal }), api.request<{items: IdentityBinding[]}>(`/identity/users/${user.id}/external-identities`, { signal: controller.signal })])
  if (turn !== readGeneration || !canRead()) return
  memberships.value = members.items; bindings.value = ids.items
  Object.assign(form, { username: user.username || '', display_name: user.display_name, active: user.active, password: '', role_id: user.role_id, member_active: user.member_active, issuer: '', subject: '' })
}
async function edit(user?: ProjectUser) { const owner = scope.value; if (!canRead() || (!user && (!can('users:write') || !can('roles:read'))) || editor.busy() || !await editor.leave() || owner !== scope.value) return; const turn = ++readGeneration; loading.value = false; detailLoading.value = !!user; editor.clear(); selected.value = user ?? null; opened.value = true; tab.value = 'profile'; memberships.value = []; bindings.value = []; extraTenant.value = ''; extraRoles.value = null; extraRole.value = ''; extraActive.value = true; editor.dirty.value = false; form.password = ''; error.value = null
  if (user) { try { await detail(user, turn) } catch (e) { if (turn === readGeneration && !isCancelled(e)) { error.value = e; opened.value = false } } finally { if (turn === readGeneration) detailLoading.value = false } }
  else Object.assign(form, { username: '', display_name: '', password: '', active: true, role_id: roles.value?.items.find(r => r.enabled && r.base_role === 'viewer')?.id || '', member_active: true })
}
async function saveProfile() { if (!canWrite() || (!selected.value && !can('roles:read'))) return; const user = selected.value; if (await editor.save(user ? `/identity/users/${user.id}` : '/identity/users', user ? 'PATCH' : 'POST', user ? { expected_version: user.version, display_name: form.display_name, active: form.active } : { expected_version: 0, username: form.username, display_name: form.display_name, password: form.password, active: form.active, role_id: form.role_id })) { form.password = ''; if (!user) opened.value = false } }
async function action(kind: 'password' | 'unlock' | 'revoke-sessions' | 'local-account') { if (!canWrite() || !selected.value) return; const owner = scope.value, user = selected.value!; await editor.save(`/identity/users/${user.id}/${kind}`, 'POST', { expected_version: user.version, ...(kind === 'password' ? { new_password: form.password } : kind === 'local-account' ? { username: form.username, password: form.password } : {}) }); if (owner === scope.value) form.password = '' }
async function assign() { if (!canWrite() || !can('roles:read') || !selected.value) return; const user = selected.value!; await editor.save(`/identity/users/${user.id}/memberships/${session.value!.tenant.id}`, 'PUT', { expected_version: user.permission_version, role_id: form.role_id, active: form.member_active }) }
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
async function disable(binding: IdentityBinding) { if (!canWrite() || !selected.value) return; await editor.save(`/identity/users/${selected.value!.id}/external-identities/${binding.id}/disable`, 'POST', { expected_version: binding.version }) }
async function search() { const owner = scope.value; if (!canRead() || editor.busy() || !await editor.leave() || owner !== scope.value) return; editor.clear(); selected.value = null; opened.value = false; editor.dirty.value = false; page.value = 1; await load() }
async function move(delta: number) { const owner = scope.value; if (!canRead() || editor.busy() || !await editor.leave() || owner !== scope.value) return; editor.clear(); selected.value = null; opened.value = false; editor.dirty.value = false; page.value += delta; await load() }
async function closeEditor() { const owner = scope.value; if (editor.busy() || !await editor.leave() || owner !== scope.value) return; editor.clear(); opened.value = false; selected.value = null; form.password = '' }
</script>
<template><section class="identity-page admin-prototype" v-loading="loading">
  <div class="page-heading"><div><h1>用户与成员授权</h1><p>管理当前租户的账号、成员角色及统一身份绑定。</p></div><el-button v-if="can('users:write') && can('roles:read')" type="primary" :disabled="editor.busy()" @click="edit()">新建用户</el-button></div>
  <p v-if="!can('users:read')">当前会话无用户读取权限。</p>
  <ApiErrorNotice :error="error"><el-button @click="search">重新读取</el-button></ApiErrorNotice><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
  <div class="admin-stats" v-if="users">
    <div class="admin-stat"><span>本页成员</span><strong>{{ users.items.length }}</strong><small>当前租户 · 第 {{ page }} 页</small></div>
    <div class="admin-stat"><span>账号与成员均启用</span><strong>{{ users.items.filter(u => u.active && u.member_active).length }}</strong><small>有效账号及成员关系</small></div>
    <div class="admin-stat"><span>停用记录</span><strong>{{ users.items.filter(u => !u.active || !u.member_active).length }}</strong><small>本页账号或成员已停用</small></div>
    <div class="admin-stat"><span>当前授权版本</span><strong>{{ session?.permission_version ?? '—' }}</strong><small>每次请求重新核验授权</small></div>
  </div>
  <section class="admin-card">
    <form v-form-feedback class="identity-form identity-toolbar admin-toolbar" @submit.prevent="search"><label>搜索账号或姓名<input v-model="query" maxlength="100" placeholder="输入账号 / 姓名" /></label><label class="check"><input v-model="includeInactive" type="checkbox" />包含停用记录</label><el-button native-type="submit" type="primary" :disabled="editor.busy()">查询</el-button><span class="muted">{{ session?.tenant.name }}</span></form>
    <div class="admin-table-scroll"><table class="admin-table"><thead><tr><th>成员 / 登录账号</th><th>角色</th><th>租户范围</th><th>登录方式</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="user in users?.items" :key="user.id"><td><div class="admin-member"><span class="admin-avatar" aria-hidden="true">{{ user.display_name.slice(0, 1) }}</span><div><strong>{{ user.display_name }}</strong><small>{{ user.username || '未开通本地账号' }}</small></div></div></td><td>{{ user.role_name }}<small>{{ baseRoleLabels[user.base_role] }}</small></td><td>{{ session?.tenant.name }}<small>当前授权成员关系</small></td><td>{{ user.local_login_enabled ? '本地账号' : '本地登录未开通' }}</td><td><el-tag :type="user.active && user.member_active ? 'success' : 'info'">{{ !user.active ? '账号停用' : !user.member_active ? '成员停用' : '正常' }}</el-tag></td><td><div class="admin-row-actions"><el-button :disabled="editor.busy()" @click="edit(user)">管理账号</el-button></div></td></tr>
    </tbody></table><p v-if="users && !users.items.length" class="admin-empty">没有匹配的用户。</p></div>
    <div class="admin-footer"><span>本页 {{ users?.items.length ?? '—' }} 条 · 不代表全部成员数量</span><div class="identity-actions"><el-button :disabled="page <= 1 || editor.busy()" @click="move(-1)">上一页</el-button><span>第 {{ page }} 页</span><el-button :disabled="!users?.has_more || editor.busy()" @click="move(1)">下一页</el-button></div></div>
  </section>
  <p v-if="opened && detailLoading" role="status">正在读取所选账号详情…</p>
  <section v-if="opened && !detailLoading" class="identity-card identity-form admin-editor-panel" aria-label="用户账号编辑与授权"><div class="admin-editor-title"><h2>{{ selected ? selected.display_name : '新建用户' }}</h2><el-button :disabled="editor.busy()" @click="closeEditor">关闭编辑</el-button></div>
    <IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
    <el-tabs v-model="tab"><el-tab-pane label="基本资料" name="profile" /><el-tab-pane v-if="selected" label="登录与会话" name="security" /><el-tab-pane v-if="selected" label="成员授权" name="membership" /><el-tab-pane v-if="selected" label="统一身份绑定" name="bindings" /></el-tabs>
    <form v-form-feedback v-if="tab === 'profile'" @submit.prevent="saveProfile" @input="editor.dirty.value = true" @change="editor.dirty.value = true"><fieldset :disabled="editor.busy() || !can('users:write')"><label v-if="!selected">登录账号<input v-model="form.username" required pattern="[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}" maxlength="64" autocomplete="off" /></label><label>显示名称<input v-model="form.display_name" required maxlength="100" /></label><label v-if="!selected">初始密码<input v-model="form.password" type="password" required maxlength="1024" autocomplete="new-password" /></label><label v-if="!selected">所属角色<select v-model="form.role_id" required><option v-for="role in roles?.items.filter(r => r.enabled)" :key="role.id" :value="role.id">{{ role.name }}</option></select></label><label class="check"><input v-model="form.active" type="checkbox" />启用账号</label><p class="muted">账号资料和启停是全局操作；后端会核验其他租户的管理范围。账号名称创建后保留不变。</p><el-button v-if="can('users:write')" native-type="submit" type="primary">保存账号</el-button></fieldset></form>
    <div v-if="tab === 'security' && selected"><p>重置密码、注销会话后，已登录会话会失效。忘记密码由项目管理员核实身份后处理。</p><form v-form-feedback @submit.prevent="action(selected.local_login_enabled ? 'password' : 'local-account')" @input="editor.dirty.value = true"><fieldset :disabled="editor.busy() || !can('users:write') || selected.id === session?.user.id"><label v-if="!selected.local_login_enabled">开通登录账号<input v-model="form.username" required pattern="[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}" maxlength="64" /></label><label>新密码<input v-model="form.password" type="password" required autocomplete="new-password" maxlength="1024" /></label><el-button v-if="can('users:write')" native-type="submit" type="primary">{{ selected.local_login_enabled ? '重置密码' : '开通本地登录' }}</el-button></fieldset></form><p v-if="selected.id === session?.user.id">自己的密码请在“我的账号”中修改。</p><p>锁定截至：{{ selected.locked_until || '未锁定' }}</p><div v-if="can('users:write')" class="identity-actions"><el-button :disabled="editor.busy() || !selected.local_login_enabled" @click="action('unlock')">解除登录锁定</el-button><el-button :disabled="editor.busy()" @click="action('revoke-sessions')">注销全部会话</el-button></div></div>
    <div v-if="tab === 'membership' && selected"><p v-for="member in memberships" :key="member.tenant_id">{{ member.tenant_name }} · {{ baseRoleLabels[member.base_role] }} · {{ member.active ? '有效' : '停用' }}</p><p class="muted">以下修改当前租户的成员授权。其他租户授权在切换到对应租户后管理。</p><form v-form-feedback @submit.prevent="assign" @change="editor.dirty.value = true"><fieldset :disabled="editor.busy() || !can('users:write') || !can('roles:read')"><label>当前租户角色<select v-model="form.role_id" required><option v-for="role in roles?.items.filter(r => r.enabled)" :key="role.id" :value="role.id">{{ role.name }}</option></select></label><label class="check"><input v-model="form.member_active" type="checkbox" />启用当前租户成员资格</label><el-button v-if="can('users:write') && can('roles:read')" native-type="submit" type="primary">保存成员授权</el-button></fieldset></form><form v-form-feedback v-if="can('users:write') && can('roles:read') && (session?.allowed_tenants.length ?? 0) > 1" @submit.prevent="grantTenant" @change="editor.dirty.value = true"><h3>管理其他授权租户的成员资格</h3><fieldset :disabled="editor.busy()"><label>目标租户<select v-model="extraTenant" required @change="tenantRoles"><option value="">请选择</option><option v-for="tenant in session?.allowed_tenants.filter(x => x.id !== session?.tenant.id)" :key="tenant.id" :value="tenant.id">{{ tenant.name }}</option></select></label><label>目标租户角色<select v-model="extraRole" required><option v-for="role in extraRoles?.items.filter(x => x.enabled)" :key="role.id" :value="role.id">{{ role.name }}</option></select></label><label class="check"><input v-model="extraActive" type="checkbox" />启用目标租户成员资格</label><el-button native-type="submit" type="primary" :disabled="!extraRole">保存目标租户授权</el-button></fieldset></form></div>
    <div v-if="tab === 'bindings' && selected"><p>使用 OIDC 签发者地址与 subject 精确绑定。外部同名账号不会自动获得项目权限；认证服务需要另行配置后启用。</p><article v-for="binding in bindings" :key="binding.id" class="identity-card"><p>{{ binding.issuer }}</p><p>Subject：{{ binding.subject }}</p><el-tag>{{ binding.active ? '有效绑定' : '已停用' }}</el-tag><el-button v-if="binding.active && can('users:write')" :disabled="editor.busy()" @click="disable(binding)">停用绑定</el-button></article><form v-form-feedback v-if="can('users:write')" @submit.prevent="bind" @input="editor.dirty.value = true"><fieldset :disabled="editor.busy()"><label>OIDC Issuer<input v-model="form.issuer" type="url" required maxlength="2000" placeholder="https://identity.example.com" /></label><label>外部 Subject<input v-model="form.subject" required maxlength="255" /></label><el-button native-type="submit" type="primary">新增明确绑定</el-button></fieldset></form></div>
  </section>
</section></template>
<style scoped>
.admin-editor-title h2{min-width:0;overflow-wrap:anywhere}.admin-editor-title>.el-button{flex-shrink:0}.admin-member>div{min-width:0;overflow-wrap:anywhere}
@media(max-width:650px){.admin-prototype.identity-page .admin-editor-panel{inset:0;width:100%;height:100dvh;max-height:100dvh;padding:0 16px 20px;z-index:1000}.admin-prototype.identity-page .admin-editor-title{top:0;margin-bottom:16px;padding:14px 0;min-height:72px}.admin-prototype.identity-page .admin-editor-title>.el-button{min-height:44px;min-width:88px;margin-left:0}}
</style>
