<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError, isCancelled } from '../api/client'
import { session, contextGeneration, switchingTenant } from '../stores/session'
import type { OwnAccount } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const account = ref<OwnAccount | null>(null), error = ref<unknown>(null), current = ref(''), replacement = ref(''), repeated = ref('')
let controller: AbortController | null = null, generation = 0, disposed = false
const loading = ref(false)
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.tenant.id, session.value?.user.role, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), contextGeneration.value, switchingTenant.value]))
const readable = () => !disposed && !!session.value?.user.id && !switchingTenant.value
async function load() {
  const turn = ++generation, owner = scope.value, actor = session.value?.user.id
  controller?.abort(); controller = null; account.value = null; error.value = null; loading.value = false
  current.value = replacement.value = repeated.value = ''
  if (!readable()) return false
  const request = new AbortController(); controller = request; loading.value = true
  const valid = () => turn === generation && owner === scope.value && readable()
  try {
    const value = await api.request<OwnAccount>('/account', { signal: request.signal })
    if (!valid()) return false
    if (!value || value.id !== actor) throw new ApiError(200, 'INVALID_ACCOUNT_RESPONSE', '账号响应与当前登录身份不一致，请重新读取', '—')
    account.value = value; return true
  } catch (e) { if (valid() && !isCancelled(e)) error.value = e; return false }
  finally { if (turn === generation) { controller = null; loading.value = false } }
}
const editor = useIdentityEditor(async () => { current.value = replacement.value = repeated.value = ''; window.location.assign('/login'); return 'redirected' as const }), command = editor.command, message = editor.message
function clearView() {
  generation++; controller?.abort(); controller = null; account.value = null; error.value = null; loading.value = false
  current.value = replacement.value = repeated.value = ''; editor.clear()
}
watch(scope, clearView, { flush: 'sync' })
onMounted(load); onUnmounted(() => { disposed = true; clearView() })
async function refresh() {
  const owner = scope.value
  if (!readable() || editor.busy() || !await editor.leave() || owner !== scope.value) return
  editor.clear(); await load()
}
async function save() { if (!readable() || loading.value || !account.value?.active || !account.value.username || account.value.id !== session.value?.user.id) return; error.value = null; if (replacement.value !== repeated.value) { error.value = new Error('两次新密码输入不一致'); return } await editor.save('/account/password', 'POST', { expected_version: account.value!.version, current_password: current.value, new_password: replacement.value }) }
</script>
<template><section class="identity-page admin-prototype" v-loading="loading">
  <div class="page-heading"><div><h1>我的账号</h1><p>个人资料、当前授权范围与登录安全</p></div><el-button v-if="session" :disabled="editor.busy() || switchingTenant" @click="refresh">重新读取账号</el-button></div><ApiErrorNotice :error="error"><el-button @click="refresh">重新读取</el-button></ApiErrorNotice><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
  <div v-if="account" class="admin-setting-layout"><aside class="admin-setting-nav"><div class="admin-member"><span class="admin-avatar" aria-hidden="true">{{ account.display_name.slice(0, 1) }}</span><h2>{{ account.display_name }}</h2></div><p>{{ account.username || '统一身份认证账号' }}</p><hr><p>当前租户</p><strong>{{ session?.tenant.name }}</strong><p>当前可访问 {{ session?.capabilities.length }} 项业务权限。</p><p>授权有疑问时请联系项目管理员。</p></aside><div class="admin-setting-main">
    <section class="admin-card"><div class="admin-card-head"><h2>基本资料</h2><span class="muted">本人账号</span></div><dl class="admin-facts"><dt>显示名称</dt><dd>{{ account.display_name }}</dd><dt>登录账号</dt><dd>{{ account.username || '统一身份认证账号' }}</dd><dt>当前租户</dt><dd>{{ session?.tenant.name }}</dd><dt>最近登录</dt><dd>{{ account.last_login_at ? new Date(account.last_login_at).toLocaleString() : '暂无本地登录记录' }}</dd></dl></section>
    <form v-form-feedback v-if="account.username" class="admin-card identity-form" @submit.prevent="save" @input="editor.dirty.value = true"><div class="admin-card-head"><h2>修改密码</h2><span class="muted">修改成功后重新登录</span></div><div class="admin-card-body"><p v-if="account.must_change_password">当前账号需要先修改密码。</p><p>修改成功后需重新登录，已有会话同时失效。</p><p v-if="account.password_policy.minimum_password_length">当前最低长度：{{ account.password_policy.minimum_password_length }} 个字符。</p><fieldset :disabled="editor.busy()"><label>当前密码<input v-model="current" type="password" autocomplete="current-password" required maxlength="1024" /></label><label>新密码<input v-model="replacement" type="password" autocomplete="new-password" required maxlength="1024" /></label><label>再次输入新密码<input v-model="repeated" type="password" autocomplete="new-password" required maxlength="1024" /></label><el-button type="primary" native-type="submit" :loading="command.state === 'sending'">修改密码并重新登录</el-button></fieldset></div></form>
  </div></div>
</section></template>
