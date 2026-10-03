<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createTicketCommentWorkspace, emptyCommentState, validateTicketCommentText, type CommentState } from '../utils/ticketCommentWorkspace'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean; ticket: RegisteredTicket | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<CommentState>(emptyCommentState())
const workspace = createTicketCommentWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.ticket?.tenant_id === session.value.tenant.id && ['msp', 'padmin', 'viewer', 'tadmin'].includes(session.value.user.role) && (!['viewer', 'tadmin'].includes(session.value.user.role) || props.ticket?.reporter_id === session.value.user.id) && can('ticket_comments:read') && !switchingTenant.value))
const authorKind = computed<'REPORTER' | 'ENGINEER'>(() => session.value && ['viewer', 'tadmin'].includes(session.value.user.role) ? 'REPORTER' : 'ENGINEER')
const writable = computed(() => permitted.value && can('ticket_comments:write'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !writable.value || Boolean(state.value.busy) || unresolved.value)
const text = ref(''), baseline = ref<number | null>(null), validation = ref('')
function clearForm() { text.value = ''; baseline.value = null; validation.value = '' }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, writable.value, authorKind.value, props.ticket?.id]))
watch(contextKey, () => { clearForm(); const s = session.value; workspace.reset(permitted.value && s && props.ticket ? { tenantId: s.tenant.id, ticketId: props.ticket.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: writable.value, authorKind: authorKind.value } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true })
watch(open, value => { if (value && permitted.value && !state.value.page && !state.value.busy) void workspace.refresh() })
async function beginNote() { if (locked.value || !await workspace.refresh() || !state.value.parentVersion) return; validation.value = ''; baseline.value = state.value.parentVersion }
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.parentVersion !== baseline.value) { validation.value = '父登记已有更新，请重新选择当前登记版本，核对后再提交备注'; return }
  try { validateTicketCommentText(text.value); validation.value = ''; if (await workspace.submit(text.value, baseline.value)) clearForm() }
  catch (error) { validation.value = error instanceof Error ? error.message : '请检查客户可见评论文字' }
}
async function retryOriginal() { if (await workspace.retry()) clearForm() }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('客户可见评论结果尚未确认，请先使用原请求重试，再离开本页'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('客户评论保存结果尚未确认，请先处理原请求再切换租户或退出'); return false })
onUnmounted(() => { unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved })
</script>

<template>
  <el-drawer v-model="open" title="客户可见评论（报障人及授权工程师）" size="820px" class="ticket-comment-drawer" :close-on-click-modal="false">
    <p v-if="ticket && permitted"><strong>{{ ticket.title }}</strong><span class="ticket-comment-id">{{ ticket.receipt_no }} · {{ ticket.id }}</span></p>
    <el-alert type="info" :closable="false" title="评论会向报障人和当前租户获准内部人员公开，保存后不可编辑或删除。不要复制内部备注或填写密码、密钥。保存不等于已读或通知送达，不改变工单/SLA，尚不支持附件或ITSM同步。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有客户可见评论权限" />
    <template v-else>
      <div class="ticket-comment-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">刷新第一页（只读）</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more" @click="workspace.refresh(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第一页' }} · 每页15条</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="追加结果尚未确认。原文、父版本和幂等键已保留；刷新只查询。请勿刷新浏览器或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">使用原请求重试</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次追加被拒绝。请重新读取并核对父登记版本，不会自动覆盖或重试。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`最近确认追加：${state.receipt.id}；仅为该次客户可见评论回执`" />
      <p v-if="state.receipt" class="ticket-comment-id">回执原文摘要：{{ state.receipt.text_sha256 }}（一致性检查，不是外部核验）</p>
      <section class="ticket-comment-section"><h3>已保存的客户可见评论</h3><el-empty v-if="state.page && !state.page.items.length" description="本页暂无客户可见评论" /><p v-else-if="!state.page" class="field-help">尚未成功读取，不能视为没有评论</p><article v-for="note in state.page?.items || []" :key="note.id" class="ticket-comment-card"><strong>{{ note.author_label }} · {{ displayTime(note.created_at) }}</strong><span class="ticket-comment-id">提交所见登记 v{{ note.observed_ticket_version }}</span><p class="ticket-comment-text">{{ note.text }}</p><details><summary>记录标识与原文摘要</summary><p class="ticket-comment-id">{{ note.id }}<br>{{ note.text_sha256 }}</p><p class="field-help">人工填报 · 未独立核验 · 报障人及授权工程师可见 · 不改工单/SLA</p></details></article></section>
      <section v-if="writable" class="ticket-comment-section"><h3>追加客户可见评论</h3><el-button :disabled="locked" @click="beginNote">以当前登记版本准备评论</el-button><el-form v-if="baseline !== null" label-position="top" @submit.prevent="save"><p>明确基准：父登记 v{{ baseline }}；新增备注不会推进父业务版本</p><el-alert v-if="state.parentVersion && state.parentVersion !== baseline" type="warning" :closable="false" title="父登记已有新版本。原文保留，旧基准提交已阻止，请重新核对。" /><el-form-item label="评论原文（1至2000字符，可换行）"><el-input v-model="text" type="textarea" :rows="5" :disabled="locked" aria-label="客户可见评论原文" placeholder="明确填写要向报障人和工程师公开的内容；不要粘贴内部备注或连接秘密" /></el-form-item><p class="field-help">{{ Array.from(text).length }} / 2000 字符。原文不会自动清洗或改写。</p><p v-if="validation" role="alert" class="ticket-comment-validation">{{ validation }}</p><div class="ticket-comment-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || state.parentVersion !== baseline">确认公开并保存评论</el-button><el-button :disabled="locked" @click="clearForm">放弃未发送文字</el-button></div></el-form></section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起不取消已发送请求，未确认原请求保留在本页</span></template>
  </el-drawer>
</template>

<style>
.ticket-comment-drawer{max-width:100vw}.ticket-comment-drawer .el-alert{margin-bottom:12px}.ticket-comment-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}.ticket-comment-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.ticket-comment-section{border-top:1px solid var(--el-border-color);padding-top:12px;margin-top:24px}.ticket-comment-card{padding:16px 0;border-bottom:1px solid var(--el-border-color)}.ticket-comment-card summary{cursor:pointer}.ticket-comment-text{white-space:pre-wrap;overflow-wrap:anywhere}.ticket-comment-validation{color:var(--el-color-danger)}
@media(max-width:600px){.ticket-comment-drawer .el-drawer__body{padding:14px}.ticket-comment-drawer .el-button{min-height:44px;height:auto;white-space:normal}.ticket-comment-drawer .el-textarea__inner{font-size:16px}.ticket-comment-actions>span{width:100%}.ticket-comment-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}.ticket-comment-section p{overflow-wrap:anywhere}}
</style>
