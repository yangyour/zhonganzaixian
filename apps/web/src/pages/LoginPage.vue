<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError, isCancelled } from '../api/client'
import type { AuthOptions, Session } from '../api/types'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const route = useRoute(), options = ref<AuthOptions | null>(null), loading = ref(true), submitting = ref(false), error = ref<unknown>(null)
const username = ref(''), password = ref(''), help = ref(false), controller = new AbortController()
const oidcFailure = computed(() => route.query.auth_error ? '统一认证登录未完成，请重试或使用项目账号。' : '')
onUnmounted(() => { controller.abort(); password.value = '' })
async function loadOptions() {
  loading.value = true; error.value = null
  try { options.value = await api.request<AuthOptions>('/auth/options', { signal: controller.signal }) }
  catch (cause) { if (!isCancelled(cause)) error.value = cause }
  finally { loading.value = false }
}
onMounted(loadOptions)
async function localLogin() {
  if (submitting.value || !username.value || !password.value) return
  submitting.value = true; error.value = null
  try {
    const csrf = await api.request<{ csrf_token: string }>('/auth/csrf', { signal: controller.signal })
    if (typeof csrf.csrf_token !== 'string' || !csrf.csrf_token) throw new ApiError(200, 'INVALID_RESPONSE', '登录校验返回异常', '—')
    api.setCsrfToken(csrf.csrf_token)
    const current = await api.request<Session>('/auth/local/login', { method: 'POST', body: { username: username.value.trim(), password: password.value }, signal: controller.signal })
    if (current.identity_source !== 'project_local' || !current.user?.id || !Array.isArray(current.capabilities)) throw new ApiError(200, 'INVALID_RESPONSE', '登录回读未确认，请重新检查当前会话', '—')
    password.value = ''; window.location.assign(current.password_change_required ? '/account' : current.capabilities.includes('assets:read') ? '/assets' : '/account')
  } catch (cause) { if (!isCancelled(cause)) error.value = cause }
  finally { submitting.value = false }
}
function unifiedLogin() {
  const path = options.value?.login_url
  if (!path || !path.startsWith('/oauth2/authorization/') || path.startsWith('//')) { error.value = new ApiError(0, 'IDENTITY_SOURCE_UNAVAILABLE', '统一身份认证尚未配置', '—'); return }
  submitting.value = true; window.location.assign(path)
}
</script>
<template>
  <div class="login-screen">
    <section class="login-intro"><div class="login-brand"><span class="brand-mark">M</span>MOP 运维托管</div><h1>让每个资产的状态<br>都有可信依据。</h1><p>网络设备运维平台</p><div class="login-scope"><span>01 项目账号与租户授权</span><span>02 角色权限与操作审计</span><span>03 统一身份认证可选接入</span></div></section>
    <section class="login-card" v-loading="loading">
      <span class="eyebrow">MOP ACCOUNT</span><h2>登录 MOP</h2>
      <p class="muted">使用项目账号登录，按已分配的租户、角色与权限访问业务。</p>
      <el-alert v-if="oidcFailure" :title="oidcFailure" type="error" :closable="false" show-icon class="page-note" />
      <ApiErrorNotice :error="error"><el-button v-if="!options" size="small" @click="loadOptions">重试连接</el-button></ApiErrorNotice>
      <form v-if="options?.local_enabled" class="local-login-form" @submit.prevent="localLogin">
        <label>账号<input v-model="username" aria-label="登录账号" autocomplete="username" maxlength="64" required :disabled="submitting" /></label>
        <label>密码<input v-model="password" aria-label="登录密码" type="password" autocomplete="current-password" maxlength="1024" required :disabled="submitting" /></label>
        <el-button native-type="submit" type="primary" size="large" :loading="submitting" class="login-submit">登录</el-button>
        <button type="button" class="password-help" @click="help = !help">忘记密码？</button>
        <p v-if="help" class="muted">请联系项目管理员重置密码。目前未配置邮件或短信找回通道。</p>
      </form>
      <el-button v-if="options?.sso_enabled && options.login_url" size="large" :loading="submitting" class="login-submit" @click="unifiedLogin">使用统一身份认证</el-button>
      <el-button v-else-if="!options?.local_enabled && options?.login_url" type="primary" size="large" :loading="submitting" class="login-submit" @click="unifiedLogin">使用统一身份认证</el-button>
      <el-alert v-else-if="!loading && !error && !options?.local_enabled" title="登录服务尚未就绪，请联系项目管理员。" type="warning" :closable="false" show-icon />
      <p class="login-foot">当前为项目测试环境。账号授权由项目管理，统一认证须配置后启用。</p>
    </section>
  </div>
</template>
<style scoped>
.local-login-form{display:grid;gap:18px;margin:24px 0}.local-login-form label{display:grid;gap:8px;color:#344d65}.local-login-form input{width:100%;box-sizing:border-box;min-height:46px;border:1px solid #b8c7d8;border-radius:8px;padding:10px 12px;font:inherit}.password-help{justify-self:end;border:0;background:transparent;color:#2269ba;cursor:pointer;min-height:36px}.local-login-form .login-submit{margin-top:4px}.local-login-form :focus-visible{outline:3px solid #4c8fd5;outline-offset:2px}
</style>
