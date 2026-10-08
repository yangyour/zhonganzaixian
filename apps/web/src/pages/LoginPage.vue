<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError, isCancelled } from '../api/client'
import type { AuthOptions, Session } from '../api/types'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const route = useRoute(), options = ref<AuthOptions | null>(null), loading = ref(true), submitting = ref(false), error = ref<unknown>(null)
const username = ref(''), password = ref(''), help = ref(false), controller = new AbortController()
const usernameInput = ref<HTMLInputElement | null>(null), passwordInput = ref<HTMLInputElement | null>(null), errorNotice = ref<HTMLElement | null>(null)
const usernameError = ref(''), passwordError = ref(''), passwordVisible = ref(false), capsLock = ref(false)
const loginMethod = ref<'local' | 'unified' | null>(null)
const passwordDescription = computed(() => [passwordError.value ? 'login-password-error' : '', capsLock.value ? 'login-caps-lock' : ''].filter(Boolean).join(' ') || undefined)
const oidcFailure = computed(() => route.query.auth_error ? '统一认证登录未完成，请重试或使用项目账号。' : '')
let optionsPending = false
onUnmounted(() => { controller.abort(); password.value = '' })
async function loadOptions() {
  if (optionsPending || controller.signal.aborted) return
  optionsPending = true
  loading.value = true; error.value = null
  try {
    const result = await api.request<AuthOptions>('/auth/options', { signal: controller.signal })
    if (!controller.signal.aborted) options.value = result
  }
  catch (cause) { if (!controller.signal.aborted && !isCancelled(cause)) error.value = cause }
  finally { loading.value = false; optionsPending = false }
}
onMounted(loadOptions)
async function localLogin() {
  if (submitting.value || loading.value || !options.value?.local_enabled || controller.signal.aborted) return
  error.value = null
  usernameError.value = username.value.trim() ? '' : '请输入账号，不能只填写空格。'
  passwordError.value = password.value ? '' : '请输入密码。'
  if (usernameError.value || passwordError.value) {
    await nextTick()
    ;(usernameError.value ? usernameInput.value : passwordInput.value)?.focus()
    return
  }
  submitting.value = true; loginMethod.value = 'local'; passwordVisible.value = false; capsLock.value = false
  let focusAfterFailure: 'password' | 'notice' | null = null
  try {
    const csrf = await api.request<{ csrf_token: string }>('/auth/csrf', { signal: controller.signal })
    if (controller.signal.aborted) return
    if (typeof csrf.csrf_token !== 'string' || !csrf.csrf_token) throw new ApiError(200, 'INVALID_RESPONSE', '登录校验未完成，请重新登录。', '—')
    api.setCsrfToken(csrf.csrf_token)
    const current = await api.request<Session>('/auth/local/login', { method: 'POST', body: { username: username.value.trim(), password: password.value }, signal: controller.signal })
    if (controller.signal.aborted) return
    if (current.identity_source !== 'project_local' || !current.user?.id || !Array.isArray(current.capabilities)) throw new ApiError(200, 'INVALID_RESPONSE', '暂时无法确认登录状态，请重新登录。', '—')
    password.value = ''; window.location.assign(current.password_change_required ? '/account' : current.capabilities.includes('assets:read') ? '/assets' : '/account')
  } catch (cause) {
    if (!controller.signal.aborted && !isCancelled(cause)) {
      error.value = cause
      focusAfterFailure = cause instanceof ApiError && cause.code === 'LOGIN_FAILED' ? 'password' : 'notice'
    }
  } finally { submitting.value = false; loginMethod.value = null }
  if (focusAfterFailure && !controller.signal.aborted) {
    await nextTick()
    ;(focusAfterFailure === 'password' ? passwordInput.value : errorNotice.value)?.focus()
  }
}
function unifiedLogin() {
  if (submitting.value || loading.value || controller.signal.aborted) return
  const path = options.value?.login_url
  if (!path || !path.startsWith('/oauth2/authorization/') || path.startsWith('//')) { error.value = new ApiError(0, 'IDENTITY_SOURCE_UNAVAILABLE', '统一身份认证尚未配置，请联系项目管理员。', '—'); return }
  error.value = null; submitting.value = true; loginMethod.value = 'unified'; passwordVisible.value = false; window.location.assign(path)
}
function updateCapsLock(event: KeyboardEvent) { capsLock.value = event.getModifierState('CapsLock') }
</script>
<template>
  <div class="login-screen">
    <section class="login-intro"><div class="login-brand"><span class="brand-mark">M</span>MOP 运维托管</div><h1>查看设备状态，<br>跟进运维工作。</h1><p>网络设备运维平台</p><div class="login-scope"><span>项目账号登录，按租户授权访问</span><span>按角色分配权限，保留操作记录</span><span>支持接入统一身份认证</span></div></section>
    <section class="login-card">
      <span class="eyebrow">MOP ACCOUNT</span><h2>登录 MOP</h2>
      <p class="muted">请使用项目账号登录。可访问的租户和功能由管理员分配。</p>
      <p class="login-progress" role="status">{{ loading ? '正在连接登录服务…' : submitting ? loginMethod === 'unified' ? '正在打开统一认证页面，请稍候。' : '正在核对账号信息，请稍候。' : '' }}</p>
      <el-alert v-if="oidcFailure" :title="oidcFailure" type="error" :closable="false" show-icon class="page-note" />
      <div v-if="error" ref="errorNotice" class="login-error" tabindex="-1" aria-label="登录未完成">
        <ApiErrorNotice :error="error"><el-button v-if="!options" size="small" :loading="loading" @click="loadOptions">重新连接</el-button></ApiErrorNotice>
      </div>
      <form v-if="options?.local_enabled" class="local-login-form" :aria-busy="submitting" novalidate @submit.prevent="localLogin">
        <div class="login-field">
          <label for="login-username">账号</label>
          <input id="login-username" ref="usernameInput" v-model="username" aria-label="登录账号" autocomplete="username" autocapitalize="none" :spellcheck="false" maxlength="64" required :disabled="submitting" :aria-invalid="usernameError ? true : undefined" :aria-describedby="usernameError ? 'login-username-error' : undefined" @input="usernameError = ''" />
          <p v-if="usernameError" id="login-username-error" class="login-field-error" role="alert">{{ usernameError }}</p>
        </div>
        <div class="login-field">
          <label for="login-password">密码</label>
          <div class="login-password-control">
            <input id="login-password" ref="passwordInput" v-model="password" aria-label="登录密码" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" maxlength="1024" required :disabled="submitting" :aria-invalid="passwordError ? true : undefined" :aria-describedby="passwordDescription" @input="passwordError = ''" @keydown="updateCapsLock" @keyup="updateCapsLock" @blur="capsLock = false" />
            <button type="button" class="password-toggle" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" aria-controls="login-password" :disabled="submitting" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? '隐藏' : '显示' }}</button>
          </div>
          <p v-if="passwordError" id="login-password-error" class="login-field-error" role="alert">{{ passwordError }}</p>
          <p v-if="capsLock" id="login-caps-lock" class="login-caps-lock" role="status">大写锁定已开启，请留意密码的大小写。</p>
        </div>
        <el-button native-type="submit" type="primary" size="large" :loading="loginMethod === 'local'" :disabled="submitting" class="login-submit">{{ loginMethod === 'local' ? '正在登录…' : '登录' }}</el-button>
        <button type="button" class="password-help" :aria-expanded="help" aria-controls="login-password-help" @click="help = !help">{{ help ? '收起找回说明' : '忘记密码？' }}</button>
        <p v-if="help" id="login-password-help" class="muted login-help">请联系项目管理员重置密码。目前还不能通过邮件或短信找回。</p>
      </form>
      <el-button v-if="options?.sso_enabled && options.login_url" size="large" :loading="loginMethod === 'unified'" :disabled="submitting" class="login-submit" @click="unifiedLogin">{{ loginMethod === 'unified' ? '正在前往统一认证…' : '使用统一身份认证' }}</el-button>
      <el-button v-else-if="!options?.local_enabled && options?.login_url" type="primary" size="large" :loading="loginMethod === 'unified'" :disabled="submitting" class="login-submit" @click="unifiedLogin">{{ loginMethod === 'unified' ? '正在前往统一认证…' : '使用统一身份认证' }}</el-button>
      <el-alert v-else-if="!loading && !error && !options?.local_enabled" title="登录服务暂不可用，请联系项目管理员。" type="warning" :closable="false" show-icon />
      <p class="login-foot">当前为项目测试环境。账号由项目管理员授权，统一认证配置完成后才能使用。</p>
    </section>
  </div>
</template>
<style scoped>
.login-progress:empty{margin:0}
.local-login-form{display:grid;gap:18px;margin:24px 0}.login-field{display:grid;gap:8px;min-width:0}.local-login-form label{color:#344d65}.local-login-form input{width:100%;box-sizing:border-box;min-height:46px;border:1px solid #b8c7d8;border-radius:8px;padding:10px 12px;font:inherit}.local-login-form input[aria-invalid=true]{border-color:#b42318}.local-login-form input:disabled{background:#f3f6fa;color:#62748a}.login-password-control{position:relative}.login-password-control input{padding-right:70px}.password-toggle{position:absolute;right:4px;top:1px;min-height:44px;min-width:60px;border:0;border-radius:6px;background:transparent;color:#2269ba;font:inherit;cursor:pointer}.password-toggle:disabled{color:#62748a;cursor:default}.password-help{justify-self:end;border:0;background:transparent;color:#2269ba;font:inherit;cursor:pointer;min-height:44px}.login-field-error,.login-caps-lock,.login-help,.login-progress{margin:0;font-size:13px;line-height:1.6}.login-field-error{color:#b42318}.login-caps-lock{color:#875900}.login-progress{margin-top:16px;color:#344d65}.local-login-form .login-submit{margin-top:4px}.local-login-form :focus-visible,.login-error:focus-visible{outline:3px solid #4c8fd5;outline-offset:2px}.login-error{border-radius:8px}
</style>
