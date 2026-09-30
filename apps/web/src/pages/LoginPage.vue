<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError, isCancelled } from '../api/client'
import type { AuthOptions } from '../api/types'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const route = useRoute()
const options = ref<AuthOptions | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref<unknown>(null)
const controller = new AbortController()
const oidcFailure = computed(() => route.query.auth_error ? '登录未完成。请重试；若仍失败，请检查测试身份及项目成员授权。' : '')
onUnmounted(() => controller.abort())
async function loadOptions() {
  loading.value = true; error.value = null
  try { options.value = await api.request<AuthOptions>('/auth/options', { signal: controller.signal }) }
  catch (cause) { if (!isCancelled(cause)) error.value = cause }
  finally { loading.value = false }
}
onMounted(loadOptions)
function login() {
  const path = options.value?.login_url
  if (!path || !path.startsWith('/oauth2/authorization/') || path.startsWith('//')) {
    error.value = new ApiError(0, 'IDENTITY_SOURCE_UNAVAILABLE', '测试身份入口不可用，请检查 API 与 Keycloak 连接', '—')
    return
  }
  submitting.value = true
  window.location.assign(path)
}
</script>
<template>
  <div class="login-screen">
    <section class="login-intro"><div class="login-brand"><span class="brand-mark">M</span>MOP 运维托管</div><h1>让每个资产的状态<br>都有可信依据。</h1><p>网络设备运维平台 · 共享测试环境</p><div class="login-scope"><span>01 资产台账与持久草稿</span><span>02 配置确认与版本</span><span>03 租户权限与审计</span></div></section>
    <section class="login-card" v-loading="loading">
      <span class="eyebrow">TEST ENVIRONMENT ACCESS</span><h2>登录测试环境</h2>
      <p class="muted">通过共享测试服务器的 Keycloak 验证身份，再按项目成员关系进入授权租户。</p>
      <el-alert v-if="oidcFailure" :title="oidcFailure" type="error" :closable="false" show-icon class="page-note" />
      <ApiErrorNotice :error="error"><el-button size="small" @click="loadOptions">重试连接</el-button></ApiErrorNotice>
      <el-button v-if="options?.mode === 'remote_test' && options.login_url" type="primary" size="large" :loading="submitting" class="login-submit" @click="login">使用 Keycloak 登录</el-button>
      <el-alert v-else-if="!loading && !error" title="当前未启用共享测试身份源。请使用 remote_test 运行配置连接指定环境。" type="warning" :closable="false" show-icon />
      <p class="login-foot">共享测试环境 · PostgreSQL / Keycloak · 非生产。客户正式身份源和生产许可按待确认项登记。</p>
    </section>
  </div>
</template>
