<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElAlert, ElButton, ElDivider, ElForm, ElFormItem, ElInput, type InputInstance } from 'element-plus'
import { Hide, View } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, isCancelled } from '../api/client'
import type { AuthOptions, Session } from '../api/types'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import loginBackground from '../assets/login-infrastructure-20261015.webp'
import { sessionExpired, loginReturnPath } from '../stores/session'
import { safeWeComAuthorizationUrl } from '../utils/wecom'
import { loginDestination } from '../utils/loginDestination'
const router = useRouter()
const route = useRoute(), options = ref<AuthOptions | null>(null), loading = ref(true), submitting = ref(false), error = ref<unknown>(null)
const username = ref(''), password = ref(''), help = ref(false), controller = new AbortController()
const usernameInput = ref<InputInstance | null>(null), passwordInput = ref<InputInstance | null>(null), errorNotice = ref<HTMLElement | null>(null)
const usernameError = ref(''), passwordError = ref(''), passwordVisible = ref(false), capsLock = ref(false)
const backgroundFailed = ref(false)
const loginMethod = ref<'local' | 'unified' | 'wecom' | null>(null)
const passwordDescription = computed(() => [passwordError.value ? 'login-password-error' : '', capsLock.value ? 'login-caps-lock' : ''].filter(Boolean).join(' ') || undefined)
const oidcFailure = computed(() => route.query.auth_error === 'WECOM_IDENTITY_NOT_BOUND' ? '此企业微信成员尚未绑定平台账号，请联系管理员。' : route.query.auth_error ? '外部身份登录未完成，请重试或使用项目账号。' : '')
const connectionError = computed(() => error.value instanceof ApiError && error.value.code === 'NETWORK_ERROR')
const errorTitle = computed(() => connectionError.value ? options.value ? '暂时无法确认登录结果' : '登录服务暂时无法连接' : undefined)
const errorGuidance = computed(() => connectionError.value ? options.value ? '请检查网络连接后重试登录。' : '请检查网络，或稍后点击“重新连接”。' : '')
let optionsPending = false
function hidePasswordWhenAway() { if (document.visibilityState === 'hidden') { passwordVisible.value = false; capsLock.value = false } }
onMounted(() => document.addEventListener('visibilitychange', hidePasswordWhenAway))
onUnmounted(() => { controller.abort(); password.value = ''; document.removeEventListener('visibilitychange', hidePasswordWhenAway) })
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
  usernameError.value = username.value.trim() ? '' : '请输入登录账号。'
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
    password.value = ''; window.location.assign(loginDestination(current, loginReturnPath.value, router.getRoutes()))
  } catch (cause) {
    if (!controller.signal.aborted && !isCancelled(cause)) {
      error.value = cause
      focusAfterFailure = cause instanceof ApiError && cause.code === 'LOGIN_FAILED' ? 'password' : 'notice'
    }
  } finally { submitting.value = false; loginMethod.value = null }
  if (focusAfterFailure && !controller.signal.aborted) {
    await nextTick()
    ;(focusAfterFailure === 'password' ? passwordInput.value : errorNotice.value)?.focus()
    if (focusAfterFailure === 'password') passwordInput.value?.select()
  }
}
async function wecomLogin() {
  if (submitting.value || loading.value || !options.value?.wecom_enabled || controller.signal.aborted) return
  submitting.value = true; loginMethod.value = 'wecom'; error.value = null
  try {
    const csrf = await api.request<{csrf_token:string}>('/auth/csrf', {signal:controller.signal})
    if (controller.signal.aborted) return
    api.setCsrfToken(csrf.csrf_token)
    const result = await api.request<{authorization_url:string}>('/auth/wecom/start', {method:'POST',body:{mode:/wxwork/i.test(navigator.userAgent)?'work':'web'},signal:controller.signal})
    if (controller.signal.aborted) return
    if (!safeWeComAuthorizationUrl(result.authorization_url)) throw new ApiError(200,'INVALID_RESPONSE','企业微信登录地址未通过校验','—')
    window.location.assign(result.authorization_url)
  } catch(cause) { if (!controller.signal.aborted && !isCancelled(cause)) error.value = cause }
  finally { submitting.value=false;loginMethod.value=null }
}
function unifiedLogin() {
  if (submitting.value || loading.value || controller.signal.aborted) return
  const path = options.value?.login_url
  if (!path || !path.startsWith('/oauth2/authorization/') || path.startsWith('//')) { error.value = new ApiError(0, 'IDENTITY_SOURCE_UNAVAILABLE', '统一身份认证尚未配置，请联系项目管理员。', '—'); return }
  error.value = null; submitting.value = true; loginMethod.value = 'unified'; passwordVisible.value = false; window.location.assign(path)
}
function updateCapsLock(event: KeyboardEvent | Event) {
  if (event instanceof KeyboardEvent) capsLock.value = event.getModifierState('CapsLock')
}
</script>
<template>
  <div class="login-screen">
    <img v-show="!backgroundFailed" class="login-background" :src="loginBackground" alt="" aria-hidden="true" fetchpriority="high" decoding="async" width="1672" height="941" @error="backgroundFailed = true" />
    <header class="login-header">
      <div class="login-brand"><span class="brand-mark" aria-hidden="true">M</span><span>MOP 运维托管</span></div>
    </header>
    <main class="login-main">
    <section class="login-card" aria-labelledby="login-title">
      <div class="login-card-heading"><h1 id="login-title">{{ options?.local_enabled ? '账号登录' : '登录' }}</h1></div>
      <p class="login-progress" role="status">{{ loading ? '正在连接登录服务…' : submitting ? loginMethod === 'unified' ? '正在跳转至统一身份认证，请稍候…' : '正在验证登录信息，请稍候…' : '' }}</p>
      <el-alert v-if="sessionExpired" class="page-note" role="status" title="登录状态已过期，请重新登录。若此前有正在提交的操作，登录后请先核对结果。" type="warning" :closable="false" show-icon />
      <el-alert v-if="oidcFailure" :title="oidcFailure" type="error" :closable="false" show-icon class="page-note" />
      <div v-if="error" ref="errorNotice" class="login-error" tabindex="-1" aria-label="登录未完成">
        <ApiErrorNotice :error="error" :title="errorTitle" :guidance="errorGuidance"><el-button v-if="!options" size="small" :loading="loading" @click="loadOptions">重新连接</el-button></ApiErrorNotice>
      </div>
      <el-form v-if="options?.local_enabled" class="local-login-form" label-position="top" size="large" hide-required-asterisk :aria-busy="submitting" novalidate @submit.prevent="localLogin">
        <el-form-item class="login-field" label="账号" for="login-username" :error="usernameError">
          <el-input id="login-username" ref="usernameInput" v-model="username" placeholder="请输入账号" aria-label="登录账号" autocomplete="username" autocapitalize="none" :spellcheck="false" maxlength="64" required :validate-event="false" :disabled="submitting" :aria-invalid="usernameError ? true : undefined" :aria-describedby="usernameError ? 'login-username-error' : undefined" @input="usernameError = ''" />
          <template #error><p id="login-username-error" class="login-field-error" role="alert">{{ usernameError }}</p></template>
        </el-form-item>
        <div class="password-input-group">
        <div class="password-label-row"><label for="login-password">密码</label></div>
        <el-form-item class="login-field password-field" :error="passwordError">
          <el-input id="login-password" ref="passwordInput" v-model="password" placeholder="请输入密码" aria-label="登录密码" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" maxlength="1024" required :validate-event="false" :disabled="submitting" :aria-invalid="passwordError ? true : undefined" :aria-describedby="passwordDescription" @input="passwordError = ''" @keydown="updateCapsLock" @keyup="updateCapsLock" @blur="capsLock = false">
            <template #suffix><el-button native-type="button" text size="small" class="password-toggle" :icon="passwordVisible ? Hide : View" :title="passwordVisible ? '隐藏密码' : '显示密码'" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" aria-controls="login-password" :disabled="submitting" @click="passwordVisible = !passwordVisible" /></template>
          </el-input>
          <template #error><p id="login-password-error" class="login-field-error" role="alert">{{ passwordError }}</p></template>
        </el-form-item>
        <el-button native-type="button" link type="primary" class="password-help" :aria-expanded="help" aria-controls="login-password-help" @click="help = !help">{{ help ? '收起找回说明' : '忘记密码？' }}</el-button>
        </div>
        <el-alert v-if="capsLock" id="login-caps-lock" class="login-caps-lock" role="status" title="大写锁定已开启，请留意密码的大小写。" type="warning" :closable="false" show-icon />
        <el-alert v-if="help" id="login-password-help" class="login-help" title="请联系项目管理员重置密码。" type="info" :closable="false" show-icon />
        <el-button native-type="submit" type="primary" size="large" :loading="loginMethod === 'local'" :disabled="submitting" class="login-submit">{{ loginMethod === 'local' ? '正在登录…' : '登录' }}</el-button>
      </el-form>
      <el-button v-if="options?.wecom_enabled" size="large" :loading="loginMethod === 'wecom'" :disabled="submitting" class="login-submit" @click="wecomLogin">使用企业微信登录</el-button>
      <el-divider v-if="options?.local_enabled && options.sso_enabled && options.login_url" class="login-alternative">或</el-divider>
      <el-button v-if="options?.sso_enabled && options.login_url" size="large" :loading="loginMethod === 'unified'" :disabled="submitting" class="login-submit" @click="unifiedLogin">{{ loginMethod === 'unified' ? '正在前往统一认证…' : '使用统一身份认证' }}</el-button>
      <el-button v-else-if="!options?.local_enabled && options?.login_url" type="primary" size="large" :loading="loginMethod === 'unified'" :disabled="submitting" class="login-submit" @click="unifiedLogin">{{ loginMethod === 'unified' ? '正在前往统一认证…' : '使用统一身份认证' }}</el-button>
      <el-alert v-else-if="!loading && !error && !options?.local_enabled" title="登录服务暂不可用，请联系项目管理员。" type="warning" :closable="false" show-icon />
    </section>
    </main>
  </div>
</template>
<style scoped>
.login-screen { position: relative; isolation: isolate; display: flex; flex-direction: column; min-height: 100vh; min-height: 100svh; background: #edf5fc; color: #20354e; }
.login-background { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: center; pointer-events: none; }
.login-header { display: flex; align-items: center; padding: 32px clamp(24px, 4.5vw, 80px); }
.login-brand { display: inline-flex; align-items: center; gap: 11px; font-size: 18px; font-weight: 600; line-height: 1.5; }
.login-brand .brand-mark { display: grid; place-items: center; flex-shrink: 0; width: 34px; height: 36px; border-radius: 8px; background: #2165c5; color: #fff; font-size: 23px; font-weight: 600; }
.login-main { flex: 1; display: flex; align-items: center; justify-content: flex-end; min-width: 0; padding: 24px clamp(40px, 9vw, 180px) 100px; }
.login-card { --el-color-primary: #2165c5; width: 400px; max-width: 100%; min-width: 0; padding: 32px; border: 1px solid #ffffffdc; border-radius: 20px; background: #fffffff7; box-shadow: 0 24px 70px #244c7a12, 0 2px 8px #244c7a05; font-family: 'Microsoft YaHei', 'PingFang SC', 'Segoe UI', sans-serif; }
.login-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 24px; }
.login-card h1 { margin: 0; color: #20354e; font-size: 22px; line-height: 1.4; font-weight: 600; letter-spacing: .2px; }
.login-environment { padding: 3px 8px; border: 1px solid #e5ebf3; border-radius: 6px; background: #f5f7fb; color: #62738a; font-size: 11px; line-height: 1.5; white-space: nowrap; }
.local-login-form { display: flex; flex-direction: column; margin: 0; }
.login-field { min-width: 0; margin-bottom: 20px; }
.password-input-group { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px 12px; margin-bottom: 24px; min-width: 0; }
.password-field { grid-column: 1 / -1; grid-row: 2; margin-bottom: 0; }
.local-login-form :deep(.el-form-item__label), .password-label-row > label { height: auto; margin: 0 0 8px; padding: 0; color: #475d77; font-size: 13px; font-weight: 500; line-height: 1.6; }
.local-login-form :deep(.el-input__wrapper) { min-height: 50px; padding: 0 15px; border-radius: 10px; background: #f3f6fa; box-shadow: 0 0 0 1px #e7edf4 inset; transition: box-shadow .15s, background-color .15s; }
.local-login-form :deep(.el-input__wrapper:hover) { background: #eef3f9; box-shadow: 0 0 0 1px #c6d5e7 inset; }
.local-login-form :deep(.el-input__wrapper.is-focus) { background: #fff; box-shadow: 0 0 0 1px #2165c5 inset, 0 0 0 3px #2165c515; }
.local-login-form :deep(.el-form-item.is-error .el-input__wrapper) { background: #fffafb; box-shadow: 0 0 0 1px #b43746 inset; }
.local-login-form :deep(.el-input__inner) { height: 48px; color: #263b54; font-family: inherit; font-size: 14px; }
.local-login-form :deep(.el-input__inner::placeholder) { color: #5b6e86; font-size: 14px; }
.local-login-form :deep(.el-input.is-disabled .el-input__wrapper) { background: #f1f5f9; }
.password-toggle { min-width: 44px; min-height: 44px; margin-right: -9px; padding: 0 8px; border-radius: 8px; color: #667c97; font-size: 18px; }
.password-toggle:hover { color: #2165c5; background: #e7eef8; }
.password-label-row { grid-column: 1; grid-row: 1; }
.password-label-row > label { margin: 0; }
.password-help { grid-column: 2; grid-row: 1; min-height: 32px; height: auto; margin: 0 !important; padding: 3px 0; font-size: 12px; line-height: 1.5; }
.login-submit { width: 100%; min-height: 48px; height: auto; margin: 0; padding: 12px 16px; border-radius: 10px; font-size: 15px; font-weight: 600; letter-spacing: .12em; white-space: normal; }
.login-submit.el-button--primary { box-shadow: 0 6px 16px #2165c51c; }
.login-field-error { width: 100%; margin: 7px 0 0; color: #a52e40; font-size: 12px; line-height: 1.6; }
.login-progress { margin: 0 0 18px; color: #536b86; font-size: 13px; line-height: 1.7; }
.login-progress:empty { margin: 0; }
.login-caps-lock, .login-help { margin-bottom: 18px; }
.login-alternative { margin: 24px 0; }
.login-alternative :deep(.el-divider__text) { background: #fff; color: #62738a; font-size: 12px; }
.login-card :deep(.el-alert) { border-radius: 8px; padding: 11px 12px; }
.login-card :deep(.el-alert__title) { font-size: 12px !important; line-height: 1.65 !important; }
.login-card :deep(.el-alert__icon) { width: 16px; font-size: 16px; }
.login-error { border-radius: 8px; }
.password-toggle:focus-visible, .password-help:focus-visible, .login-error:focus-visible { outline: 3px solid #2165c5; outline-offset: 3px; }
@media (max-width: 1100px) { .login-main { padding-inline: 5vw; } }
@media (max-width: 900px) {
  .login-main { justify-content: center; padding: 24px 24px 64px; }
  .login-header { padding: 26px 28px; }
  .login-background { object-position: 35% center; }
}
@media (max-width: 540px) {
  .login-screen::before { content: ''; position: absolute; inset: 0; z-index: -1; background: #edf5fc55; pointer-events: none; }
  .login-header { padding: 24px 20px; }
  .login-brand { font-size: 17px; }
  .login-main { padding: 16px 16px 40px; }
  .login-background { object-position: 24% center; }
  .login-card { padding: 26px 24px; border-radius: 18px; background: #fffffffa; }
  .login-card-heading { margin-bottom: 22px; }
  .login-card h1 { font-size: 22px; }
  .password-help { min-height: 44px; }
}
@media (max-width: 360px) { .login-card { padding-inline: 22px; } .login-header { padding-inline: 18px; } }
@media (max-height: 600px) { .login-header { padding-block: 18px; } .login-main { padding-top: 12px; padding-bottom: 24px; } }
@media (prefers-reduced-motion: reduce) { .local-login-form :deep(.el-input__wrapper) { transition: none; } }
</style>
