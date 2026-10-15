<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createInternalNoteWorkspace, emptyNoteState, validateInternalNoteText, type NoteState } from '../utils/internalNoteWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean; ticket: RegisteredTicket | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [ticketId: string] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<NoteState>(emptyNoteState())
const workspace = createInternalNoteWorkspace(api, value => { state.value = value })
watch(() => state.value.receipt ? state.value.receipt.id : '', key => { if (key && state.value.receipt) emit('saved', state.value.receipt.ticket_id) })
const permitted = computed(() => Boolean(session.value && props.ticket?.tenant_id === session.value.tenant.id && ['msp', 'padmin'].includes(session.value.user.role) && can('ticket_notes:read_internal') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('ticket_notes:write_internal'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !writable.value || Boolean(state.value.busy) || unresolved.value)
const text = ref(''), baseline = ref<number | null>(null), validation = ref('')
function clearForm() { text.value = ''; baseline.value = null; validation.value = '' }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, writable.value, props.ticket?.id]))
watch(contextKey, () => { clearForm(); const s = session.value; workspace.reset(permitted.value && s && props.ticket ? { tenantId: s.tenant.id, ticketId: props.ticket.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: writable.value } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true })
watch(open, value => { if (value && permitted.value && !state.value.page && !state.value.busy) void workspace.refresh() })
async function beginNote() { if (locked.value || !await workspace.refresh() || !state.value.parentVersion) return; validation.value = ''; baseline.value = state.value.parentVersion }
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.parentVersion !== baseline.value) { validation.value = '父登记已有更新，请重新选择当前登记版本，核对后再提交备注'; return }
  try { validateInternalNoteText(text.value); validation.value = ''; if (await workspace.submit(text.value, baseline.value)) clearForm() }
  catch (error) { validation.value = error instanceof Error ? error.message : '请检查内部备注文字' }
}
async function retryOriginal() { if (await workspace.retry()) clearForm() }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('内部备注结果尚未确认，请先使用原请求重试，再离开本页'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('内部备注保存结果尚未确认，请先处理原请求再切换租户或退出'); return false })
onUnmounted(() => { unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved })
</script>

<template>
  <el-drawer v-model="open" title="内部备注（仅内部人员）" size="820px" class="internal-note-drawer" :close-on-click-modal="false">
    <p v-if="ticket && permitted"><strong>{{ ticket.title }}</strong><span class="internal-note-id">{{ ticket.receipt_no }} · {{ ticket.id }}</span></p>
    <el-alert type="info" :closable="false" title="备注仅在当前租户内部人员间可见，追加后不可编辑或删除。内容为人工记录，不改变工单状态或SLA；客户可见评论请使用独立面板；本内部面板不含附件、通知或ITSM同步。不要填写密码或密钥。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有内部备注权限" />
    <template v-else>
      <div class="internal-note-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">刷新第一页（只读）</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more" @click="workspace.refresh(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第一页' }} · 每页15条</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="追加结果尚未确认。原文、父版本和幂等键已保留；刷新只查询。请勿刷新浏览器或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">使用原请求重试</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次追加被拒绝。请重新读取并核对父登记版本，不会自动覆盖或重试。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`最近确认追加：${state.receipt.id}；仅为该次内部备注回执`" />
      <p v-if="state.receipt" class="internal-note-id">回执原文摘要：{{ state.receipt.text_sha256 }}（一致性检查，不是外部核验）</p>
      <section class="internal-note-section"><h3>不可变内部记录</h3><el-empty v-if="state.page && !state.page.items.length" description="本页暂无内部备注" /><p v-else-if="!state.page" class="field-help">尚未成功读取，不能视为没有备注</p><article v-for="note in state.page?.items || []" :key="note.id" class="internal-note-card"><strong>{{ note.actor_id === session?.user.id ? '我' : '同租户内部人员' }} · {{ note.created_at }}</strong><span class="internal-note-id">内部作者ID {{ note.actor_id }} · 提交所见登记 v{{ note.observed_ticket_version }}</span><p class="internal-note-text">{{ note.text }}</p><details><summary>记录标识与原文摘要</summary><p class="internal-note-id">{{ note.id }}<br>{{ note.text_sha256 }}</p><p class="field-help">人工填报 · 未独立核验 · 仅内部可见 · 不改工单/SLA</p></details></article></section>
      <section v-if="writable" class="internal-note-section"><h3>追加内部备注</h3><el-button :disabled="locked" @click="beginNote">以当前登记版本新增</el-button><el-form v-if="baseline !== null" label-position="top" @submit.prevent="save"><p>明确基准：父登记 v{{ baseline }}；新增备注不会推进父业务版本</p><el-alert v-if="state.parentVersion && state.parentVersion !== baseline" type="warning" :closable="false" title="父登记已有新版本。原文保留，旧基准提交已阻止，请重新核对。" /><el-form-item label="内部原文（1至2000字符，可换行）"><el-input v-model="text" type="textarea" :rows="5" :disabled="locked" aria-label="内部备注原文" placeholder="明确记录内部调查或协作内容，不填连接秘密" /></el-form-item><p class="field-help">{{ Array.from(text).length }} / 2000 字符。原文不会自动清洗或改写。</p><p v-if="validation" role="alert" class="internal-note-validation">{{ validation }}</p><div class="internal-note-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || state.parentVersion !== baseline">确认追加不可变备注</el-button><el-button :disabled="locked" @click="clearForm">放弃未发送文字</el-button></div></el-form></section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起不取消已发送请求，未确认原请求保留在本页</span></template>
  </el-drawer>
</template>

<style>
.internal-note-drawer{max-width:100vw}.internal-note-drawer .el-alert{margin-bottom:12px}.internal-note-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}.internal-note-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.internal-note-section{border-top:1px solid var(--el-border-color);padding-top:12px;margin-top:24px}.internal-note-card{padding:16px 0;border-bottom:1px solid var(--el-border-color)}.internal-note-card summary{cursor:pointer}.internal-note-text{white-space:pre-wrap;overflow-wrap:anywhere}.internal-note-validation{color:var(--el-color-danger)}
@media(max-width:600px){.internal-note-drawer .el-drawer__body{padding:14px}.internal-note-drawer .el-button{min-height:44px;height:auto;white-space:normal}.internal-note-drawer .el-textarea__inner{font-size:16px}.internal-note-actions>span{width:100%}.internal-note-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}.internal-note-section p{overflow-wrap:anywhere}}
</style>
