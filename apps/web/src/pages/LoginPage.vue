<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElAlert, ElButton, ElDivider, ElForm, ElFormItem, ElInput, ElTag, type InputInstance } from 'element-plus'
import { Hide, Lock, User, View } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, isCancelled } from '../api/client'
import type { AuthOptions, Session } from '../api/types'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import loginBackground from '../assets/login-datacenter.png'
import { sessionExpired, loginReturnPath } from '../stores/session'
import { loginDestination } from '../utils/loginDestination'
const router = useRouter()
const route = useRoute(), options = ref<AuthOptions | null>(null), loading = ref(true), submitting = ref(false), error = ref<unknown>(null)
const username = ref(''), password = ref(''), help = ref(false), controller = new AbortController()
const usernameInput = ref<InputInstance | null>(null), passwordInput = ref<InputInstance | null>(null), errorNotice = ref<HTMLElement | null>(null)
const usernameError = ref(''), passwordError = ref(''), passwordVisible = ref(false), capsLock = ref(false)
const loginMethod = ref<'local' | 'unified' | null>(null)
const passwordDescription = computed(() => [passwordError.value ? 'login-password-error' : '', capsLock.value ? 'login-caps-lock' : ''].filter(Boolean).join(' ') || undefined)
const oidcFailure = computed(() => route.query.auth_error ? '统一认证登录未完成，请重试或使用项目账号。' : '')
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
  <div class="login-screen prototype-login">
    <img class="login-background" :src="loginBackground" alt="" aria-hidden="true" fetchpriority="high" />
    <section class="login-story"><header class="login-header">
      <div class="login-brand"><span class="brand-mark">M</span>MOP 运维托管</div>
      <el-tag class="login-environment" type="info" effect="plain">测试环境</el-tag>
    </header><div class="login-story-content"><h2>让运维信息清晰可见，<br />让每一步操作有据可查</h2><p>在统一的运维门户中查看资产、跟进登记与维护资料。按租户和角色提供所需入口，未确认的数据始终明确展示。</p><div class="login-feature-grid"><article><h3>统一资源视图</h3><p>资产台账、设备采样与资料修订集中查看。</p></article><article><h3>清楚的处理记录</h3><p>报障、评论、工时及历史记录保持可追溯。</p></article><article><h3>明确的访问范围</h3><p>按照当前租户与角色授予操作和查看权限。</p></article><article><h3>可信的数据状态</h3><p>草稿、待确认与未知状态分别呈现。</p></article></div></div><p class="login-story-footer">MOP 运维托管平台 · 项目测试环境</p></section>
    <main class="login-main">
    <section class="login-card" aria-labelledby="login-title">
      <h1 id="login-title">登录到运维门户</h1><p class="login-intro">使用项目账号登录，访问已授权的运维工作区。</p>
      <p class="login-progress" role="status">{{ loading ? '正在连接登录服务…' : submitting ? loginMethod === 'unified' ? '正在打开统一认证页面，请稍候。' : '正在核对账号信息，请稍候。' : '' }}</p>
      <el-alert v-if="sessionExpired" class="page-note" role="status" title="登录状态已失效，请重新登录。登录后请先核对刚才未确认的提交结果。" type="warning" :closable="false" show-icon />
      <el-alert v-if="oidcFailure" :title="oidcFailure" type="error" :closable="false" show-icon class="page-note" />
      <div v-if="error" ref="errorNotice" class="login-error" tabindex="-1" aria-label="登录未完成">
        <ApiErrorNotice :error="error" :title="errorTitle" :guidance="errorGuidance"><el-button v-if="!options" size="small" :loading="loading" @click="loadOptions">重新连接</el-button></ApiErrorNotice>
      </div>
      <el-form v-if="options?.local_enabled" class="local-login-form" label-position="top" size="large" hide-required-asterisk :aria-busy="submitting" novalidate @submit.prevent="localLogin">
        <el-form-item class="login-field" label="账号" for="login-username" :error="usernameError">
          <el-input id="login-username" ref="usernameInput" v-model="username" :prefix-icon="User" placeholder="请输入账号" aria-label="登录账号" autocomplete="username" autocapitalize="none" :spellcheck="false" maxlength="64" required :validate-event="false" :disabled="submitting" :aria-invalid="usernameError ? true : undefined" :aria-describedby="usernameError ? 'login-username-error' : undefined" @input="usernameError = ''" />
          <template #error><p id="login-username-error" class="login-field-error" role="alert">{{ usernameError }}</p></template>
        </el-form-item>
        <el-form-item class="login-field password-field" label="密码" for="login-password" :error="passwordError">
          <el-input id="login-password" ref="passwordInput" v-model="password" :prefix-icon="Lock" placeholder="请输入密码" aria-label="登录密码" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" maxlength="1024" required :validate-event="false" :disabled="submitting" :aria-invalid="passwordError ? true : undefined" :aria-describedby="passwordDescription" @input="passwordError = ''" @keydown="updateCapsLock" @keyup="updateCapsLock" @blur="capsLock = false">
            <template #suffix><el-button native-type="button" text size="small" class="password-toggle" :icon="passwordVisible ? Hide : View" :title="passwordVisible ? '隐藏密码' : '显示密码'" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" aria-controls="login-password" :disabled="submitting" @click="passwordVisible = !passwordVisible" /></template>
          </el-input>
          <el-button native-type="button" link type="primary" class="password-help" :aria-expanded="help" aria-controls="login-password-help" @click="help = !help">{{ help ? '收起找回说明' : '忘记密码？' }}</el-button>
          <template #error><p id="login-password-error" class="login-field-error" role="alert">{{ passwordError }}</p></template>
        </el-form-item>
        <el-alert v-if="capsLock" id="login-caps-lock" class="login-caps-lock" role="status" title="大写锁定已开启，请留意密码的大小写。" type="warning" :closable="false" show-icon />
        <el-alert v-if="help" id="login-password-help" class="login-help" title="请联系项目管理员重置密码。目前还不能通过邮件或短信找回。" type="info" :closable="false" show-icon />
        <el-button native-type="submit" type="primary" size="large" :loading="loginMethod === 'local'" :disabled="submitting" class="login-submit">{{ loginMethod === 'local' ? '正在登录…' : '登录' }}</el-button>
      </el-form>
      <el-divider v-if="options?.local_enabled && options.sso_enabled && options.login_url" class="login-alternative">或</el-divider>
      <el-button v-if="options?.sso_enabled && options.login_url" size="large" :loading="loginMethod === 'unified'" :disabled="submitting" class="login-submit" @click="unifiedLogin">{{ loginMethod === 'unified' ? '正在前往统一认证…' : '使用统一身份认证' }}</el-button>
      <el-button v-else-if="!options?.local_enabled && options?.login_url" type="primary" size="large" :loading="loginMethod === 'unified'" :disabled="submitting" class="login-submit" @click="unifiedLogin">{{ loginMethod === 'unified' ? '正在前往统一认证…' : '使用统一身份认证' }}</el-button>
      <el-alert v-else-if="!loading && !error && !options?.local_enabled" title="登录服务暂不可用，请联系项目管理员。" type="warning" :closable="false" show-icon />
    </section>
    </main>
  </div>
</template>
<style scoped>
.login-screen{position:relative;isolation:isolate;display:flex;flex-direction:column;min-height:100vh;min-height:100svh;background:#10243b}
.login-background{position:absolute;inset:0;z-index:-2;width:100%;height:100%;object-fit:cover;object-position:center}
.login-screen::before{content:"";position:absolute;inset:0;z-index:-1;pointer-events:none;background:linear-gradient(90deg,transparent 35%,#0b1c2e16 65%,#0b1c2e38)}
.login-header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:32px clamp(24px,4.5vw,72px);color:#fff}
.login-brand{display:flex;align-items:center;gap:12px;font-size:19px;font-weight:600;letter-spacing:.3px}
.login-brand .brand-mark{width:40px;height:40px;border-radius:11px;font-size:25px;background:#2878e8;box-shadow:0 4px 16px #0002}
.login-environment{--el-tag-text-color:#dce8f7;--el-tag-bg-color:#ffffff0d;--el-tag-border-color:#ffffff30;height:28px;padding:0 12px;border-radius:20px;font-size:12px}
.login-main{flex:1;display:flex;align-items:center;justify-content:flex-end;padding:28px clamp(48px,9vw,160px) 100px;min-width:0}
.login-card{--el-font-size-base:14px;--el-border-radius-base:9px;--el-color-primary:#256ce4;width:420px;max-width:100%;min-width:0;padding:42px;background:#fffffffa;border:1px solid #fff;border-radius:20px;box-shadow:0 24px 80px #04102338}
.login-card h1{margin:0 0 30px;font-size:28px;line-height:1.35;font-weight:650;letter-spacing:-.5px;color:#162b47}
.local-login-form{margin:0;display:flex;flex-direction:column}
.login-field{min-width:0;margin-bottom:24px}
.local-login-form :deep(.el-form-item__label){display:flex;align-items:center;justify-content:space-between;font-size:14px;font-weight:500;line-height:24px;padding:0;margin:0 0 9px;color:#34465d}
.local-login-form :deep(.el-input__wrapper){min-height:50px;padding:0 14px;background:#fff;box-shadow:0 0 0 1px #d4deea inset;border-radius:9px;transition:box-shadow .18s}
.local-login-form :deep(.el-input__wrapper:hover){box-shadow:0 0 0 1px #9bb0cc inset}
.local-login-form :deep(.el-input__wrapper.is-focus){box-shadow:0 0 0 2px #256ce4 inset}
.local-login-form :deep(.el-form-item.is-error .el-input__wrapper){box-shadow:0 0 0 1px #c4424e inset}
.local-login-form :deep(.el-input__inner){height:48px;color:#243750;font-size:15px}
.local-login-form :deep(.el-input__prefix){font-size:17px;color:#8394ac}.local-login-form :deep(.el-input__inner::placeholder){font-size:14px;color:#8999ad}
.local-login-form :deep(.el-input.is-disabled .el-input__wrapper){background:#f3f6fa}
.password-toggle{min-height:42px;min-width:44px;margin-right:-7px;padding:0 8px;color:#617891;border-radius:6px;font-size:18px}
.login-submit{width:100%;height:50px;margin:4px 0 0;font-size:15px;font-weight:500;border-radius:9px}
.login-submit.el-button--primary{box-shadow:0 5px 12px #256ce421}
.password-help{position:absolute;right:0;top:-37px;height:32px;min-height:32px;margin:0!important;padding:0 2px;font-size:12px;font-weight:400}
.login-field-error{width:100%;margin:7px 0 0;color:#ba3543;font-size:12px;line-height:1.6}
.login-progress{margin:0 0 18px;color:#536b86;font-size:13px;line-height:1.7}
.login-progress:empty{margin:0}
.login-caps-lock,.login-help{margin-bottom:18px}.login-alternative{margin:26px 0 22px}.login-alternative :deep(.el-divider__text){background:#fefefe;color:#8796a8;font-size:12px}
.login-card :deep(.el-alert){border-radius:9px;padding:12px 14px}.login-card :deep(.el-alert__title){font-size:12px!important;line-height:1.65!important}.login-card :deep(.el-alert__icon){font-size:16px;width:16px}
.login-error{border-radius:9px}.password-toggle:focus-visible,.password-help:focus-visible,.login-error:focus-visible{outline:2px solid #256ce4;outline-offset:3px}
@media(max-width:1100px){.login-main{padding-right:6vw;padding-left:6vw}.login-card{width:400px;padding:36px}.login-background{object-position:42% center}}
@media(max-width:760px){.login-header{padding:24px;gap:10px}.login-brand{font-size:16px;gap:10px}.login-brand .brand-mark{width:34px;height:34px;font-size:22px;border-radius:9px}.login-environment{padding:0 9px;font-size:11px}.login-main{align-items:center;justify-content:center;padding:24px 20px 64px}.login-card{width:400px;padding:32px 28px;border-radius:18px}.login-card h1{font-size:25px;margin-bottom:26px}.login-background{object-position:27% center}.login-screen::before{background:#0c203b40}}
@media(max-width:360px){.login-header{padding:20px 16px}.login-brand{font-size:15px}.login-main{padding:16px 16px 40px}.login-card{padding:28px 22px}.login-environment{font-size:10px;padding:0 7px}}
@media(prefers-reduced-motion:reduce){.local-login-form :deep(.el-input__wrapper){transition:none}}
</style>
<style scoped>
.prototype-login{display:grid;grid-template-columns:minmax(0,1.05fr) minmax(0,.95fr);background:#f5f9ff;min-height:100vh;min-height:100svh}
.prototype-login::before,.prototype-login .login-background{display:none}
.login-story{position:relative;overflow:hidden;display:flex;flex-direction:column;background:linear-gradient(155deg,#101b36,#213e86 70%,#265de0);color:white;padding:56px 8.5%;min-height:100vh;min-height:100svh}
.login-story::after{content:'';position:absolute;width:600px;height:600px;right:-210px;bottom:-170px;border:80px solid #8cb0ff12;border-radius:50%;pointer-events:none}
.prototype-login .login-header{padding:0;align-items:flex-start}.prototype-login .login-brand{font-size:17px;letter-spacing:0}.prototype-login .login-brand .brand-mark{border-radius:3px;background:linear-gradient(135deg,#91d8e8,#2878f5);box-shadow:none}.prototype-login .login-environment{border-radius:3px;font-size:10px}
.login-story-content{margin-top:32px;position:relative;z-index:1}.login-story-content h2{font-size:34px;line-height:1.4;font-weight:650;margin:0 0 12px;letter-spacing:-.3px}.login-story-content>p{font-size:14px;line-height:1.9;color:#d7e3ff;max-width:540px;margin:0}
.login-feature-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:36px}.login-feature-grid article{background:#ffffff12;border:1px solid #ffffff25;border-radius:10px;padding:16px;min-height:96px}.login-feature-grid h3{font-size:13px;margin:0 0 6px}.login-feature-grid p{font-size:12px;line-height:1.8;color:#dce6ff;margin:0}.login-story-footer{position:relative;z-index:1;margin-top:auto;padding-top:50px;color:#9bb3e7;font-size:11px}
.prototype-login .login-main{justify-content:center;padding:40px}.prototype-login .login-card{width:420px;padding:32px;border:1px solid #e2eaf5;border-radius:16px;box-shadow:0 8px 26px #235a9b08;background:#fff}.prototype-login .login-card h1{font-size:22px;margin:0 0 4px;font-weight:600;letter-spacing:0}.login-intro{font-size:12px;color:#8593a6;line-height:1.8;margin-bottom:26px}.prototype-login .login-field{margin-bottom:18px}.prototype-login .local-login-form :deep(.el-form-item__label){font-size:12px;margin-bottom:5px}.prototype-login .local-login-form :deep(.el-input__wrapper){min-height:40px;border-radius:4px}.prototype-login .local-login-form :deep(.el-input__inner){height:38px;font-size:13px}.prototype-login .login-submit{height:40px;border-radius:3px;font-size:13px;box-shadow:none}.prototype-login .password-toggle{min-height:36px}.prototype-login .login-progress{font-size:12px}
@media(max-width:1100px){.login-story{padding:40px 7%}.login-story-content h2{font-size:28px}.login-feature-grid{grid-template-columns:1fr;margin-top:24px;gap:10px}.login-feature-grid article{min-height:0;padding:12px 14px}.prototype-login .login-main{padding:24px}}
@media(max-width:760px){.prototype-login{display:block}.login-story{min-height:0;padding:24px}.login-story-content{margin-top:22px}.login-story-content h2{font-size:25px}.login-story-content>p{font-size:12px}.login-feature-grid,.login-story-footer{display:none}.prototype-login .login-main{padding:24px 16px 40px}.prototype-login .login-card{padding:26px 24px;border-radius:12px}.prototype-login .local-login-form :deep(.el-input__wrapper),.prototype-login .login-submit{min-height:44px}.prototype-login .local-login-form :deep(.el-input__inner){height:42px}}
</style>
