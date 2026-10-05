<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, isCancelled } from '../api/client'
import { can } from '../stores/session'
import type { IdentityPolicy } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const policy = ref<IdentityPolicy | null>(null), error = ref<unknown>(null), provider = ref<{ unified_identity: { enabled: boolean } } | null>(null), controller = new AbortController()
const fields = [{ key: 'minimum_password_length', label: '密码最低长度', max: 128 }, { key: 'password_history_count', label: '密码历史次数', max: 20 }, { key: 'failed_attempt_limit', label: '连续失败锁定次数', max: 100 }, { key: 'lock_minutes', label: '锁定时长（分钟）', max: 1440 }, { key: 'idle_minutes', label: '闲置退出（分钟）', max: 1440 }] as const
async function load() { error.value = null; try { policy.value = await api.request<IdentityPolicy>('/identity/policy', { signal: controller.signal }); if (can('users:read')) provider.value = await api.request('/identity/providers', { signal: controller.signal }) ; return true } catch (e) { if (!isCancelled(e)) error.value = e; return false } }
const editor = useIdentityEditor(load), command = editor.command, message = editor.message
onMounted(load); onUnmounted(() => controller.abort())
function number(key: typeof fields[number]['key'], event: Event) { const value = (event.target as HTMLInputElement).value; if (policy.value) policy.value[key] = value === '' ? null : Number(value); editor.dirty.value = true }
async function save() { const value = policy.value!; await editor.save('/identity/policy', 'PUT', { expected_version: value.version, ...Object.fromEntries(fields.map(f => [f.key, value[f.key]])), force_change_on_reset: value.force_change_on_reset }) }
</script>
<template><section class="identity-page"><h1>身份认证设置</h1><ApiErrorNotice :error="error"><el-button @click="load">重新读取</el-button></ApiErrorNotice><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" /><div class="identity-card"><h2>统一身份认证接口</h2><p>项目账号登录已提供；统一认证采用标准 OIDC，通过明确的 Issuer 和 Subject 绑定到项目用户。</p><p>统一认证状态：{{ provider ? provider.unified_identity.enabled ? '已启用' : '未启用' : '当前账号未读取接入状态' }}</p><p>认证服务的客户端凭据由部署配置管理。角色权限继续以项目内授权为准。</p></div>
  <form v-if="policy" class="identity-card identity-form" @submit.prevent="save"><h2>可配置策略</h2><p>这些配置影响所有项目账号。留空表示未设置；未设置闲置时间时沿用现有服务会话设置。当前按项目决定保留空值，仅在明确确定策略后保存。</p><fieldset :disabled="editor.busy() || !can('roles:write')"><label v-for="field in fields" :key="field.key">{{ field.label }}<input type="number" min="1" :max="field.max" :value="policy[field.key] ?? ''" placeholder="未设置" @input="number(field.key, $event)" /></label><label>首次登录及管理员重置后强制改密<select v-model="policy.force_change_on_reset" @change="editor.dirty.value = true"><option :value="null">未设置</option><option :value="true">启用</option><option :value="false">停用</option></select></label><p class="muted">失败次数与锁定时间须同时填写或同时清空。闲置设置从后续登录开始应用。</p><el-button v-if="can('roles:write')" type="primary" native-type="submit">保存明确的策略配置</el-button></fieldset></form></section></template>
