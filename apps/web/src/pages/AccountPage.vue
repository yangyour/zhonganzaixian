<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, isCancelled } from '../api/client'
import { session } from '../stores/session'
import type { OwnAccount } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const account = ref<OwnAccount | null>(null), error = ref<unknown>(null), current = ref(''), replacement = ref(''), repeated = ref('')
const controller = new AbortController(); onUnmounted(() => { controller.abort(); current.value = replacement.value = repeated.value = '' })
async function load() { try { account.value = await api.request<OwnAccount>('/account', { signal: controller.signal }) } catch (e) { if (!isCancelled(e)) error.value = e } }
const editor = useIdentityEditor(async () => { current.value = replacement.value = repeated.value = ''; window.location.assign('/login'); return 'redirected' as const }), command = editor.command, message = editor.message
onMounted(load)
async function save() { error.value = null; if (replacement.value !== repeated.value) { error.value = new Error('两次新密码输入不一致'); return } await editor.save('/account/password', 'POST', { expected_version: account.value!.version, current_password: current.value, new_password: replacement.value }) }
</script>
<template><section class="identity-page"><h1>我的账号</h1><ApiErrorNotice :error="error"><el-button @click="load">重新读取</el-button></ApiErrorNotice><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" /><div v-if="account" class="identity-card"><h2>{{ account.display_name }}</h2><p>登录账号：{{ account.username || '统一身份认证账号' }}</p><p>当前租户：{{ session?.tenant.name }}</p><p>最近登录：{{ account.last_login_at ? new Date(account.last_login_at).toLocaleString() : '暂无本地登录记录' }}</p><p>当前可访问 {{ session?.capabilities.length }} 项业务权限。授权有疑问时请联系项目管理员。</p></div>
  <form v-if="account?.username" class="identity-card identity-form" @submit.prevent="save" @input="editor.dirty.value = true"><h2>修改密码</h2><p v-if="account.must_change_password">当前账号需要先修改密码。</p><p>修改成功后需重新登录，已有会话同时失效。</p><p v-if="account.password_policy.minimum_password_length">当前最低长度：{{ account.password_policy.minimum_password_length }} 个字符。</p><fieldset :disabled="editor.busy()"><label>当前密码<input v-model="current" type="password" autocomplete="current-password" required maxlength="1024" /></label><label>新密码<input v-model="replacement" type="password" autocomplete="new-password" required maxlength="1024" /></label><label>再次输入新密码<input v-model="repeated" type="password" autocomplete="new-password" required maxlength="1024" /></label><el-button type="primary" native-type="submit" :loading="command.state === 'sending'">修改密码并重新登录</el-button></fieldset></form></section></template>
