<script setup lang="ts">
import { displayTenantName } from '../utils/displayTenantName'
import '../styles/adminPrototype.css'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError, isCancelled } from '../api/client'
import { session, contextGeneration, switchingTenant } from '../stores/session'
import type { OwnAccount } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const account = ref<OwnAccount | null>(null), error = ref<unknown>(null), current = ref(''), replacement = ref(''), repeated = ref('')
let controller: AbortController | null = null, generation = 0, disposed = false
const loading = ref(false)
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.tenant.id, session.value?.user.role, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), contextGeneration.value, switchingTenant.value]))
const readable = () => !disposed && !!session.value?.user.id && !switchingTenant.value
async function load() {
  recoveryEpoch++;retrying.value=false;retryable.value=false
  const turn = ++generation, owner = scope.value, actor = session.value?.user.id
  controller?.abort(); controller = null; account.value = null; error.value = null; loading.value = false
  current.value = replacement.value = repeated.value = ''
  if (!readable()) return false
  const request = new AbortController(); controller = request; loading.value = true;retryable.value=true
  const valid = () => turn === generation && owner === scope.value && readable()
  try {
    const value = await api.request<OwnAccount>('/account', { signal: request.signal })
    if (!valid()) return false
    if (!value || value.id !== actor) throw new ApiError(200, 'INVALID_ACCOUNT_RESPONSE', '账号信息校验不一致，请重新加载', '—')
    account.value = value;retryable.value=false; return true
  } catch (e) { if (valid() && !isCancelled(e)) error.value = e; return false }
  finally { if (turn === generation) { controller = null; loading.value = false } }
}
const editor = useIdentityEditor(async () => { current.value = replacement.value = repeated.value = ''; window.location.assign('/login'); return 'redirected' as const }, {snapshot:inputSnapshot,preserveUnknown:true}), command = editor.command, message = editor.message
let recoveryEpoch=0
const retrying=ref(false),retryable=ref(false),pageHeading=ref<HTMLElement|null>(null),readFeedback=ref<HTMLElement|null>(null)
function showReadRetry(){return !disposed&&!loading.value&&retryable.value&&!['sending','uncertain'].includes(command.value.state)&&readable()}
function canRetryRead(){return showReadRetry()&&!retrying.value&&!editor.busy()}
function cancelRead(){if(disposed||!loading.value)return false;generation++;controller?.abort();controller=null;loading.value=false;error.value=null;return true}
async function retryRead(){if(!canRetryRead())return false;const turn=++recoveryEpoch;retrying.value=true;try{return await refresh()}finally{if(turn===recoveryEpoch)retrying.value=false}}
async function focusRead(owner:string,ok:boolean){if(!ok&&!error.value)return;await nextTick();if(disposed||owner!==scope.value||loading.value)return;(error.value?readFeedback.value:pageHeading.value)?.focus()}
// Only transient in-memory comparison; never persist or log password snapshots.
function inputSnapshot(){return JSON.stringify([scope.value,account.value?.id,account.value?.version,current.value,replacement.value,repeated.value])}
function clearView() {
  recoveryEpoch++;retrying.value=false;retryable.value=false
  generation++; controller?.abort(); controller = null; account.value = null; error.value = null; loading.value = false
  current.value = replacement.value = repeated.value = ''; editor.clear()
}
watch(scope, clearView, { flush: 'sync' })
onMounted(load); onUnmounted(() => { disposed = true; clearView() })
async function refresh() {
  const owner = scope.value, before=inputSnapshot()
  if (!readable() || editor.busy() || !await editor.leave() || owner !== scope.value || before!==inputSnapshot()) return false
  editor.clear(); const ok=await load();await focusRead(owner,ok);return ok
}
async function save() { if (!readable() || loading.value || !account.value?.active || !account.value.username || account.value.id !== session.value?.user.id) return; error.value = null; if (replacement.value !== repeated.value) { error.value = new Error('两次新密码输入不一致'); return } await editor.save('/account/password', 'POST', { expected_version: account.value!.version, current_password: current.value, new_password: replacement.value }) }
</script>
<template><section class="identity-page admin-prototype" :aria-busy="loading">
  <h1 class="page-title-sr" ref="pageHeading" tabindex="-1">我的账号</h1>
<div v-if="!account" class="content-actions content-actions-recovery"><el-button v-if="session" :disabled="editor.busy() || switchingTenant" @click="refresh">刷新账号信息</el-button></div><ReadRecoveryActions :busy="loading?'read':null" :can-retry="showReadRetry()" :scope-key="scope" label="本人账号" :cancel="cancelRead" :retry="retryRead" /><p v-if="loading" role="status">正在读取本人账号…</p><div ref="readFeedback" tabindex="-1" role="group" aria-label="本人账号读取结果"><ApiErrorNotice :error="error" /></div><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
  <div v-if="account" class="admin-setting-layout"><aside class="admin-setting-nav"><div class="admin-member"><span class="admin-avatar" aria-hidden="true">{{ account.display_name.slice(0, 1) }}</span><h2>{{ account.display_name }}</h2></div><p>{{ account.username || '统一身份认证账号' }}</p><hr><p>当前租户</p><strong>{{ displayTenantName(session?.tenant.name ?? '') }}</strong><p>当前已开通 {{ session?.capabilities.length }} 项业务操作权限。</p><p class="muted">如需调整授权范围，请联系平台管理员。</p></aside><div class="admin-setting-main">
    <section class="admin-card"><div class="admin-card-head content-toolbar"><h2>基本资料</h2><span class="muted">本人账号</span><div class="content-actions"><el-button v-if="session" :disabled="editor.busy() || switchingTenant" @click="refresh">刷新账号信息</el-button></div></div><dl class="admin-facts"><dt>显示名称</dt><dd>{{ account.display_name }}</dd><dt>登录账号</dt><dd>{{ account.username || '统一身份认证账号' }}</dd><dt>当前租户</dt><dd>{{ displayTenantName(session?.tenant.name ?? '') }}</dd><dt>最近登录</dt><dd>{{ account.last_login_at ? new Date(account.last_login_at).toLocaleString() : '暂无本地登录记录' }}</dd></dl></section>
    <form v-form-feedback v-if="account.username" class="admin-card identity-form" @submit.prevent="save" @input="editor.dirty.value = true"><div class="admin-card-head"><h2>修改密码</h2><span class="muted">修改成功后需重新登录</span></div><div class="admin-card-body"><p v-if="account.must_change_password">根据安全策略，当前账号需先完成密码修改。</p><p>密码修改成功后当前会话将自动失效，请使用新密码重新登录。</p><p v-if="account.password_policy.minimum_password_length">密码长度要求：不少于 {{ account.password_policy.minimum_password_length }} 个字符。</p><fieldset :disabled="editor.busy()"><label>当前密码<input v-model="current" type="password" autocomplete="current-password" required maxlength="1024" placeholder="请输入当前登录密码" /></label><label>新密码<input v-model="replacement" type="password" autocomplete="new-password" required maxlength="1024" placeholder="请输入新密码" /></label><label>确认新密码<input v-model="repeated" type="password" autocomplete="new-password" required maxlength="1024" placeholder="请再次输入新密码" /></label><el-button type="primary" native-type="submit" :loading="command.state === 'sending'">修改密码并重新登录</el-button></fieldset></div></form>
  </div></div>
</section></template>

<style scoped>.identity-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}</style>
