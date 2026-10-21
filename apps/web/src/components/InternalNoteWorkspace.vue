<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createInternalNoteWorkspace, emptyNoteState, validateInternalNoteText, type NoteState } from '../utils/internalNoteWorkspace'
import { displayTime } from '../utils/display'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean; ticket: RegisteredTicket | null; guardedByParent?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [ticketId: string] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<NoteState>(emptyNoteState())
const workspace = createInternalNoteWorkspace(api, value => { state.value = value })
watch(() => state.value.receipt ? state.value.receipt.id : '', key => { if (key && state.value.receipt) emit('saved', state.value.receipt.ticket_id) })
const permitted = computed(() => Boolean(session.value && props.ticket?.tenant_id === session.value.tenant.id && ['msp', 'padmin'].includes(session.value.user.role) && can('ticket_notes:read_internal') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('ticket_notes:write_internal'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
let disposed = false
const confirming = ref(false)
const locked = computed(() => !writable.value || Boolean(state.value.busy) || unresolved.value || confirming.value)
const text = ref(''), baseline = ref<number | null>(null), validation = ref('')
function clearForm() { text.value = ''; baseline.value = null; validation.value = '' }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value, writable.value, props.ticket?.id]))
watch(contextKey, () => { clearForm(); confirming.value = false; const s = session.value; workspace.reset(permitted.value && s && props.ticket ? { tenantId: s.tenant.id, ticketId: props.ticket.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: writable.value } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true, flush: 'sync' })
watch(open, value => { if (value && permitted.value && !state.value.page && !state.value.busy) void workspace.refresh() })
function inputSnapshot() { return JSON.stringify([contextKey.value,text.value,baseline.value,workspace.unresolved()]) }
async function beginNote() { if (locked.value) return; const snapshot=inputSnapshot(); if (!await workspace.refresh() || disposed || snapshot!==inputSnapshot() || !state.value.parentVersion) return; validation.value = ''; baseline.value = state.value.parentVersion }
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.parentVersion !== baseline.value) { validation.value = '工单版本已更新，请重新核对最新工单版本后再提交备注'; return }
  const snapshot=inputSnapshot()
  try { validateInternalNoteText(text.value); validation.value = ''; if (await workspace.submit(text.value, baseline.value) && !disposed && snapshot===inputSnapshot()) clearForm() }
  catch (error) { if (!disposed && snapshot===inputSnapshot()) validation.value = error instanceof Error ? error.message : '请检查内部备注内容格式' }
}
async function retryOriginal() { const owner=contextKey.value, originalText=text.value, originalBaseline=baseline.value; if (await workspace.retry() && !disposed && owner===contextKey.value && originalText===text.value && originalBaseline===baseline.value) clearForm() }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || text.value.length>0) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
async function canLeave(action='离开页面、切换租户或退出') {
  if(disposed||confirming.value)return false
  if(workspace.unresolved()){ElMessage.warning('内部备注提交结果尚未确认，请先按原请求恢复');return false}
  if(!text.value.length)return true
  const snapshot=inputSnapshot(),owner=contextKey.value;confirming.value=true
  try{await ElMessageBox.confirm(`${action}将放弃本页当前填写的内部备注；已保存记录仍保留，不会自动发送。是否继续？`,'保留当前文字',{confirmButtonText:'放弃输入并继续',cancelButtonText:'继续编辑',type:'warning'});return !disposed&&snapshot===inputSnapshot()&&!workspace.unresolved()}
  catch{return false}finally{if(!disposed&&owner===contextKey.value)confirming.value=false}
}
onBeforeRouteLeave(() => props.guardedByParent ? true : canLeave())
const unregisterContextGuard = contextTransitionGuards.register(() => props.guardedByParent ? true : canLeave(),inputSnapshot)
onUnmounted(() => { disposed=true; unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved, canLeave, inputSnapshot, cancelRead:workspace.cancelRead, retryRead:workspace.retryRead })
</script>

<template>
  <el-drawer v-model="open" title="内部处理备注（仅内部人员可见）" size="820px" class="internal-note-drawer" :close-on-click-modal="false">
    <p v-if="ticket && permitted"><strong>{{ ticket.title }}</strong><span class="internal-note-id">{{ ticket.receipt_no }} · {{ ticket.id }}</span></p>
    <el-alert type="info" :closable="false" title="内部备注仅对当前租户授权的内部技术与运营人员可见，保存后作为排障协同记录留存（不可编辑或删除）。如需向客户同步进展，请使用独立的“客户沟通”面板。请勿填写密码或密钥信息。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有内部备注权限" />
    <template v-else>
      <div class="internal-note-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">刷新列表</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more" @click="workspace.refresh(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第 1 页' }} · 每页 15 条</span></div>
      <ReadRecoveryActions :busy="state.busy" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="内部备注" :cancel="workspace.cancelRead" :retry="workspace.retryRead" />
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="提交结果待确认：已保留并锁定原请求。刷新仅查看当前列表，不能确认原请求；请使用“重试提交”恢复完全相同的原请求。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">重试提交</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次提交未通过：工单版本可能已更新，请先刷新并核对最新工单信息后再试。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`内部备注已保存（记录编号：${state.receipt.id}）`" />
      <p v-if="state.receipt" class="internal-note-id">内容校验摘要：{{ state.receipt.text_sha256 }}</p>
      <section class="internal-note-section"><h3>内部处理备注记录</h3><el-empty v-if="state.page && !state.page.items.length" description="本页暂无内部备注" /><p v-else-if="!state.page" class="field-help">{{state.busy==='read'?'正在读取内部备注…':state.error?'内部备注读取未完成，请重试':'尚未读取内部备注'}}</p><article v-for="note in state.page?.items || []" :key="note.id" class="internal-note-card"><strong>{{ note.actor_id === session?.user.id ? '我' : '同租户内部人员' }} · {{ displayTime(note.created_at) }}</strong><span class="internal-note-id">记录人 ID {{ note.actor_id }} · 关联工单版本 v{{ note.observed_ticket_version }}</span><p class="internal-note-text">{{ note.text }}</p><details><summary>查看记录编号与校验摘要</summary><p class="internal-note-id">{{ note.id }}<br>{{ note.text_sha256 }}</p><p class="field-help">人工填报 · 仅内部人员可见</p></details></article></section>
      <section v-if="writable" class="internal-note-section"><h3>新增内部备注</h3><el-button :disabled="locked" @click="beginNote">按当前工单版本新增备注</el-button><el-form v-if="baseline !== null" label-position="top" @submit.prevent="save"><p>当前关联工单版本：v{{ baseline }}</p><el-alert v-if="state.parentVersion && state.parentVersion !== baseline" type="warning" :closable="false" title="工单版本已更新，当前填写内容已保留，请先重新核对最新版本后再提交。" /><el-form-item label="内部备注内容（1 至 2000 字符，支持换行）"><el-input v-model="text" type="textarea" :rows="5" :disabled="locked" aria-label="内部备注原文" placeholder="请记录内部排查过程、技术分析或协同事项，请勿填写密码或连接密钥" /></el-form-item><p class="field-help">{{ Array.from(text).length }} / 2000 字符 · 内容按原样保存</p><p v-if="validation" role="alert" class="internal-note-validation">{{ validation }}</p><div class="internal-note-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || state.parentVersion !== baseline">保存内部备注</el-button><el-button :disabled="locked" @click="clearForm">清空内容</el-button></div></el-form></section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起面板不会中断正在提交的请求，未保存内容将保留在当前页面</span></template>
  </el-drawer>
</template>

<style scoped>
:deep(.internal-note-drawer){max-width:100vw}
:deep(.internal-note-drawer .el-alert){margin-bottom:12px}
.internal-note-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}
.internal-note-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere;margin-top:2px}
.internal-note-section{border-top:1px solid var(--el-border-color);padding-top:14px;margin-top:24px}
.internal-note-card{padding:14px 12px;border-bottom:1px solid var(--el-border-color-lighter);border-radius:6px;transition:background-color .15s}
.internal-note-card:hover{background:#f8fafd}
.internal-note-card details{margin-top:8px;font-size:12px;color:var(--el-text-color-secondary)}
.internal-note-card summary{cursor:pointer;user-select:none}
.internal-note-text{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0;line-height:1.75;color:#1e293b}
.internal-note-validation{color:var(--el-color-danger);font-size:12px}
@media(max-width:600px){
  :deep(.internal-note-drawer .el-drawer__body){padding:14px}
  :deep(.internal-note-drawer .el-button){min-height:44px;height:auto;white-space:normal}
  :deep(.internal-note-drawer .el-textarea__inner){font-size:16px}
  .internal-note-actions>span{width:100%}
  :deep(.internal-note-drawer .el-drawer__footer .field-help){display:block;margin-top:8px}
  .internal-note-section p{overflow-wrap:anywhere}
}
</style>

