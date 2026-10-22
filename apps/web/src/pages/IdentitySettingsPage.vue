<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { can, session, contextGeneration, switchingTenant } from '../stores/session'
import type { IdentityPolicy } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const policy = ref<IdentityPolicy | null>(null), error = ref<unknown>(null), provider = ref<{ unified_identity: { enabled: boolean } } | null>(null)
const loading = ref(false)
let controller: AbortController | null = null, generation = 0, disposed = false
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.user.role, session.value?.tenant.id, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), contextGeneration.value, switchingTenant.value]))
const readable = () => !disposed && !!session.value && !switchingTenant.value && can('roles:read')
const fields = [{ key: 'minimum_password_length', label: '密码最低长度', max: 128 }, { key: 'password_history_count', label: '密码历史次数', max: 20 }, { key: 'failed_attempt_limit', label: '连续失败锁定次数', max: 100 }, { key: 'lock_minutes', label: '锁定时长（分钟）', max: 1440 }, { key: 'idle_minutes', label: '闲置退出（分钟）', max: 1440 }] as const
async function load() {
  recoveryEpoch++;retrying.value=false;retryable.value=false
  const turn = ++generation, owner = scope.value
  controller?.abort(); controller = null; policy.value = null; provider.value = null; error.value = null; loading.value = false
  if (!readable()) return false
  const request = new AbortController(); controller = request; loading.value = true;retryable.value=true
  const current = () => turn === generation && owner === scope.value && readable()
  try {
    const value = await api.request<IdentityPolicy>('/identity/policy', { signal: request.signal })
    if (!current()) return false
    // Publish the editable policy only once the complete requested read has succeeded.
    let identity: { unified_identity: { enabled: boolean } } | null = null
    if (can('users:read')) identity = await api.request('/identity/providers', { signal: request.signal })
    if (!current()) return false
    policy.value = value; provider.value = identity;retryable.value=false; return true
  } catch (e) { if (current() && !isCancelled(e)) error.value = e; return false }
  finally { if (turn === generation) { controller = null; loading.value = false } }
}
const editor = useIdentityEditor(load,{snapshot:inputSnapshot,preserveUnknown:true}), command = editor.command, message = editor.message
let recoveryEpoch=0
const retrying=ref(false),retryable=ref(false),pageHeading=ref<HTMLElement|null>(null),readFeedback=ref<HTMLElement|null>(null)
function showReadRetry(){return !disposed&&!loading.value&&retryable.value&&!['sending','uncertain'].includes(command.value.state)&&can('roles:read')&&!switchingTenant.value}
function canRetryRead(){return showReadRetry()&&!retrying.value&&!editor.busy()}
function cancelRead(){if(disposed||!loading.value)return false;generation++;controller?.abort();controller=null;loading.value=false;error.value=null;return true}
async function retryRead(){if(!canRetryRead())return false;const turn=++recoveryEpoch;retrying.value=true;try{return await refresh()}finally{if(turn===recoveryEpoch)retrying.value=false}}
async function focusRead(owner:string,ok:boolean){if(!ok&&!error.value)return;await nextTick();if(disposed||owner!==scope.value||loading.value)return;(error.value?readFeedback.value:pageHeading.value)?.focus()}
function inputSnapshot(){return JSON.stringify([scope.value,policy.value,provider.value])}
function clearView() {
  recoveryEpoch++;retrying.value=false;retryable.value=false
  generation++; controller?.abort(); controller = null
  policy.value = null; provider.value = null; error.value = null; loading.value = false; editor.clear()
}
watch(scope, clearView, { flush: 'sync' })
onMounted(load); onUnmounted(() => { disposed = true; clearView() })
async function refresh() {
  const owner = scope.value, before=inputSnapshot()
  if (!readable() || editor.busy() || !await editor.leave() || owner !== scope.value || before!==inputSnapshot()) return false
  editor.clear(); const ok=await load();await focusRead(owner,ok);return ok
}
function number(key: typeof fields[number]['key'], event: Event) { const value = (event.target as HTMLInputElement).value; if (policy.value) policy.value[key] = value === '' ? null : Number(value); editor.dirty.value = true }
async function save() { if (!readable() || !can('roles:write') || loading.value || !policy.value) return; const value = policy.value; await editor.save('/identity/policy', 'PUT', { expected_version: value.version, ...Object.fromEntries(fields.map(f => [f.key, value[f.key]])), force_change_on_reset: value.force_change_on_reset }) }
</script>
<template><section class="identity-page admin-prototype" :aria-busy="loading">
  <div class="compact-page-toolbar page-heading"><div><h1 class="page-title-sr" ref="pageHeading" tabindex="-1">身份认证设置</h1></div><el-button v-if="can('roles:read')" :disabled="editor.busy() || switchingTenant" @click="refresh">重新读取设置</el-button></div>
  <p v-if="!can('roles:read')">当前会话无身份策略读取权限。</p><ReadRecoveryActions :busy="loading?'read':null" :can-retry="showReadRetry()" :scope-key="scope" label="安全设置" :cancel="cancelRead" :retry="retryRead" /><p v-if="loading" role="status">正在读取安全设置…</p><div ref="readFeedback" tabindex="-1" role="group" aria-label="安全设置读取结果"><ApiErrorNotice :error="error" /></div><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
  <div class="identity-layout">
    <form v-form-feedback v-if="policy" class="admin-card identity-form" @submit.prevent="save"><div class="admin-card-head"><h2>可配置策略</h2><span class="muted">v{{ policy.version }} · 适用于全部平台账号</span></div><fieldset :disabled="editor.busy() || !can('roles:write')"><div class="admin-table-scroll"><table class="admin-table"><thead><tr><th>策略项目</th><th>当前配置 / 编辑</th><th>状态</th></tr></thead><tbody><tr v-for="field in fields" :key="field.key"><td><label :for="'policy-' + field.key">{{ field.label }}</label></td><td><input :id="'policy-' + field.key" type="number" min="1" :max="field.max" :value="policy[field.key] ?? ''" placeholder="未设置" @input="number(field.key, $event)" /></td><td>{{ policy[field.key] === null ? '未设置' : '已填写' }}</td></tr><tr><td><label for="policy-force-change">首次登录及管理员重置后强制改密</label></td><td><select id="policy-force-change" v-model="policy.force_change_on_reset" @change="editor.dirty.value = true"><option :value="null">未设置</option><option :value="true">启用</option><option :value="false">停用</option></select></td><td>{{ policy.force_change_on_reset === null ? '未设置' : policy.force_change_on_reset ? '启用' : '停用' }}</td></tr></tbody></table></div><div class="admin-footer"><span>留空表示不启用该限制；锁定次数与锁定时长须同时设置或同时清空。</span><el-button v-if="can('roles:write')" type="primary" native-type="submit">保存策略配置</el-button></div></fieldset></form>
    <aside class="admin-card identity-side" aria-label="统一身份认证接口"><div class="admin-card-head"><h2>统一身份认证接口</h2><span>{{ provider ? provider.unified_identity.enabled ? '已启用' : '未启用' : '状态未读取' }}</span></div><dl class="admin-facts"><dt>项目账号</dt><dd>本地账号登录</dd><dt>统一认证状态</dt><dd>{{ provider ? provider.unified_identity.enabled ? '已启用' : '未启用' : '当前账号未读取接入状态' }}</dd><dt>绑定方式</dt><dd>标准 OIDC 协议（按 Issuer 与 Subject 唯一标识绑定）</dd><dt>权限来源</dt><dd>平台角色与租户成员授权</dd></dl></aside>
  </div>
</section></template>
<style scoped>
.identity-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}

.admin-table input,.admin-table select{max-width:220px}
.identity-layout{display:grid;grid-template-columns:minmax(0,1fr) minmax(300px,380px);gap:16px;align-items:start;margin-top:4px}.identity-layout>.admin-card{margin:0;min-width:0}.identity-side .admin-facts{grid-template-columns:96px minmax(0,1fr)}
@media(max-width:1100px){.identity-layout{grid-template-columns:1fr}}
</style>
