<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createTicketCommentWorkspace, emptyCommentState, validateTicketCommentText, type CommentState } from '../utils/ticketCommentWorkspace'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean; ticket: RegisteredTicket | null; guardedByParent?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [ticketId: string] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<CommentState>(emptyCommentState())
const workspace = createTicketCommentWorkspace(api, value => { state.value = value })
watch(() => state.value.receipt ? state.value.receipt.id : '', key => { if (key && state.value.receipt) emit('saved', state.value.receipt.ticket_id) })
const permitted = computed(() => Boolean(session.value && props.ticket?.tenant_id === session.value.tenant.id && ['msp', 'padmin', 'viewer', 'tadmin'].includes(session.value.user.role) && (!['viewer', 'tadmin'].includes(session.value.user.role) || props.ticket?.reporter_id === session.value.user.id) && can('ticket_comments:read') && !switchingTenant.value))
const authorKind = computed<'REPORTER' | 'ENGINEER'>(() => session.value && ['viewer', 'tadmin'].includes(session.value.user.role) ? 'REPORTER' : 'ENGINEER')
const writable = computed(() => permitted.value && can('ticket_comments:write'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
let disposed = false
const confirming = ref(false)
const locked = computed(() => !writable.value || Boolean(state.value.busy) || unresolved.value || confirming.value)
const text = ref(''), baseline = ref<number | null>(null), validation = ref('')
function clearForm() { text.value = ''; baseline.value = null; validation.value = '' }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value, writable.value, authorKind.value, props.ticket?.id]))
watch(contextKey, () => { clearForm(); confirming.value = false; const s = session.value; workspace.reset(permitted.value && s && props.ticket ? { tenantId: s.tenant.id, ticketId: props.ticket.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: writable.value, authorKind: authorKind.value } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true, flush: 'sync' })
watch(open, value => { if (value && permitted.value && !state.value.page && !state.value.busy) void workspace.refresh() })
function inputSnapshot() { return JSON.stringify([contextKey.value,text.value,baseline.value,workspace.unresolved()]) }
async function beginNote() { if (locked.value) return; const snapshot=inputSnapshot(); if (!await workspace.refresh() || disposed || snapshot!==inputSnapshot() || !state.value.parentVersion) return; validation.value = ''; baseline.value = state.value.parentVersion }
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.parentVersion !== baseline.value) { validation.value = '工单版本已更新，请重新核对最新工单版本后再提交评论'; return }
  const snapshot=inputSnapshot()
  try { validateTicketCommentText(text.value); validation.value = ''; if (await workspace.submit(text.value, baseline.value) && !disposed && snapshot===inputSnapshot()) clearForm() }
  catch (error) { if (!disposed && snapshot===inputSnapshot()) validation.value = error instanceof Error ? error.message : '请检查评论内容格式' }
}
async function retryOriginal() { const owner=contextKey.value, originalText=text.value, originalBaseline=baseline.value; if (await workspace.retry() && !disposed && owner===contextKey.value && originalText===text.value && originalBaseline===baseline.value) clearForm() }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || text.value.length>0) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
async function canLeave(action='离开页面、切换租户或退出') {
  if(disposed||confirming.value)return false
  if(workspace.unresolved()){ElMessage.warning('客户可见评论提交结果尚未确认，请先按原请求恢复');return false}
  if(!text.value.length)return true
  const snapshot=inputSnapshot(),owner=contextKey.value;confirming.value=true
  try{await ElMessageBox.confirm(`${action}将放弃本页当前填写的客户可见评论；已保存记录仍保留，不会自动发送。是否继续？`,'保留当前文字',{confirmButtonText:'放弃输入并继续',cancelButtonText:'继续编辑',type:'warning'});return !disposed&&snapshot===inputSnapshot()&&!workspace.unresolved()}
  catch{return false}finally{if(!disposed&&owner===contextKey.value)confirming.value=false}
}
onBeforeRouteLeave(() => props.guardedByParent ? true : canLeave())
const unregisterContextGuard = contextTransitionGuards.register(() => props.guardedByParent ? true : canLeave(),inputSnapshot)
onUnmounted(() => { disposed=true; unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved, canLeave, inputSnapshot })
</script>

<template>
  <el-drawer v-model="open" title="客户沟通记录（报障人及授权工程师可见）" size="820px" class="ticket-comment-drawer" :close-on-click-modal="false">
    <p v-if="ticket && permitted"><strong>{{ ticket.title }}</strong><span class="ticket-comment-id">{{ ticket.receipt_no }} · {{ ticket.id }}</span></p>
    <el-alert type="info" :closable="false" title="本栏评论对报障人及当前租户授权工程师公开，保存后作为正式沟通记录留存（不可编辑或删除）。请勿复制内部备注或填写密码、密钥信息。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有客户沟通评论权限" />
    <template v-else>
      <div class="ticket-comment-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">刷新列表</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more" @click="workspace.refresh(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第 1 页' }} · 每页 15 条</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="提交结果待确认：已保留并锁定原请求。刷新仅查看当前列表，不能确认原请求；请使用“重试提交”恢复完全相同的原请求。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">重试提交</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次提交未通过：工单版本可能已更新，请先刷新并核对最新工单信息后再试。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`评论已保存（记录编号：${state.receipt.id}）`" />
      <p v-if="state.receipt" class="ticket-comment-id">内容校验摘要：{{ state.receipt.text_sha256 }}</p>
      <section class="ticket-comment-section"><h3>已保存的客户沟通评论</h3><el-empty v-if="state.page && !state.page.items.length" description="本页暂无客户沟通评论" /><p v-else-if="!state.page" class="field-help">{{state.busy==='read'?'正在读取沟通记录…':state.error?'沟通记录读取未完成，请重试':'尚未读取沟通记录'}}</p><article v-for="note in state.page?.items || []" :key="note.id" class="ticket-comment-card"><strong>{{ note.author_label }} · {{ displayTime(note.created_at) }}</strong><span class="ticket-comment-id">关联工单版本 v{{ note.observed_ticket_version }}</span><p class="ticket-comment-text">{{ note.text }}</p><details><summary>查看记录编号与校验摘要</summary><p class="ticket-comment-id">{{ note.id }}<br>{{ note.text_sha256 }}</p><p class="field-help">人工填报 · 报障人及授权工程师可见</p></details></article></section>
      <section v-if="writable" class="ticket-comment-section"><h3>追加客户沟通评论</h3><el-button :disabled="locked" @click="beginNote">按当前工单版本填写评论</el-button><el-form v-if="baseline !== null" label-position="top" @submit.prevent="save"><p>当前关联工单版本：v{{ baseline }}</p><el-alert v-if="state.parentVersion && state.parentVersion !== baseline" type="warning" :closable="false" title="工单版本已更新，当前填写内容已保留，请先重新核对最新版本后再提交。" /><el-form-item label="评论内容（1 至 2000 字符，支持换行）"><el-input v-model="text" type="textarea" :rows="5" :disabled="locked" aria-label="客户可见评论原文" placeholder="请填写需向报障人及工程师同步的沟通内容；请勿粘贴内部备注或密码密钥" /></el-form-item><p class="field-help">{{ Array.from(text).length }} / 2000 字符 · 内容按原样保存</p><p v-if="validation" role="alert" class="ticket-comment-validation">{{ validation }}</p><div class="ticket-comment-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || state.parentVersion !== baseline">确认公开并保存评论</el-button><el-button :disabled="locked" @click="clearForm">清空内容</el-button></div></el-form></section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起面板不会中断正在提交的请求，未保存内容将保留在当前页面</span></template>
  </el-drawer>
</template>

<style scoped>
:deep(.ticket-comment-drawer){max-width:100vw}
:deep(.ticket-comment-drawer .el-alert){margin-bottom:12px}
.ticket-comment-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}
.ticket-comment-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere;margin-top:2px}
.ticket-comment-section{border-top:1px solid var(--el-border-color);padding-top:14px;margin-top:24px}
.ticket-comment-card{padding:14px 12px;border-bottom:1px solid var(--el-border-color-lighter);border-radius:6px;transition:background-color .15s}
.ticket-comment-card:hover{background:#f8fafd}
.ticket-comment-card details{margin-top:8px;font-size:12px;color:var(--el-text-color-secondary)}
.ticket-comment-card summary{cursor:pointer;user-select:none}
.ticket-comment-text{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0;line-height:1.75;color:#1e293b}
.ticket-comment-validation{color:var(--el-color-danger);font-size:12px}
@media(max-width:600px){
  :deep(.ticket-comment-drawer .el-drawer__body){padding:14px}
  :deep(.ticket-comment-drawer .el-button){min-height:44px;height:auto;white-space:normal}
  :deep(.ticket-comment-drawer .el-textarea__inner){font-size:16px}
  .ticket-comment-actions>span{width:100%}
  :deep(.ticket-comment-drawer .el-drawer__footer .field-help){display:block;margin-top:8px}
  .ticket-comment-section p{overflow-wrap:anywhere}
}
</style>

