<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createWorklogWorkspace, emptyWorklogState, validateWorklogInput, worklogInstant, type WorklogInput, type WorklogKind, type WorklogState } from '../utils/worklogWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import AppTable from './AppTable.vue'
const props = defineProps<{ modelValue: boolean; ticket: RegisteredTicket | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<WorklogState>(emptyWorklogState())
const workspace = createWorklogWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.ticket?.tenant_id === session.value.tenant.id && can('worklogs:read_own') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('worklogs:write_own'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !writable.value || Boolean(state.value.busy) || unresolved.value)
const kind = ref<WorklogKind | ''>(''), startedAt = ref(''), endedAt = ref(''), note = ref(''), editingId = ref(''), editingVersion = ref<number | null>(null), validation = ref('')
const labels: Record<WorklogKind, string> = { REMOTE: '远程处理', ONSITE: '现场处理', INSPECTION: '巡检', COMMUTE: '通勤' }
function clearForm() { kind.value = ''; startedAt.value = ''; endedAt.value = ''; note.value = ''; editingId.value = ''; editingVersion.value = null; validation.value = '' }
function input(): WorklogInput { return { kind: kind.value as WorklogKind, started_at: startedAt.value.trim(), ended_at: endedAt.value.trim(), note: note.value === '' ? null : note.value } }
const elapsed = computed(() => { try { const facts = input(); validateWorklogInput(facts); return worklogInstant(facts.ended_at) - worklogInstant(facts.started_at) } catch { return null } })
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, writable.value, props.ticket?.id]))
watch(contextKey, () => { clearForm(); const current = session.value; workspace.reset(permitted.value && current && props.ticket ? { tenantId: current.tenant.id, ticketId: props.ticket.id, actorId: current.user.id, permissionVersion: current.permission_version, canWrite: writable.value } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true })
watch(open, value => { if (value && permitted.value && !state.value.page && !state.value.busy) void workspace.refresh() })
async function editSelected() {
  const selected = state.value.selected
  if (locked.value || !selected) return
  // Re-read before establishing the edit baseline; never change an unresolved original command.
  if (!await workspace.show(selected.id)) return
  const current = state.value.selected
  if (!current) return
  kind.value = current.kind; startedAt.value = current.started_at; endedAt.value = current.ended_at; note.value = current.note ?? ''; editingId.value = current.id; editingVersion.value = current.version; validation.value = ''
}
async function save() {
  if (locked.value) return
  if (!state.value.parentVersion || editingId.value && (state.value.selected?.id !== editingId.value || state.value.selected?.version !== editingVersion.value)) { validation.value = '请先刷新列表，并重新读取需要修改的本人记录'; return }
  try { const facts = input(); validateWorklogInput(facts); validation.value = ''; if (await workspace.submit(facts, editingId.value && editingVersion.value ? { id: editingId.value, version: editingVersion.value } : null)) clearForm() }
  catch (error) { validation.value = error instanceof Error ? error.message : '请检查实填字段' }
}
async function retryOriginal() { if (await workspace.retry()) clearForm() }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('工时保存结果尚未确认，请先使用原请求重试，再离开本页'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('工时保存结果尚未确认，请先处理原请求再切换租户或退出'); return false })
onUnmounted(() => { unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved })
</script>

<template>
  <el-drawer v-model="open" title="我的实填工时" size="900px" class="worklog-drawer" :close-on-click-modal="false">
    <p v-if="ticket"><strong>{{ ticket.title }}</strong><span class="worklog-id">{{ ticket.receipt_no }} · {{ ticket.id }}</span></p>
    <el-alert type="info" :closable="false" title="仅记录本人实际填写的起止时间，未做考勤核验。默认工时、成本和毛利待确认；不改变报障状态或SLA。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有本人工时权限，请重新打开详情" />
    <template v-else>
      <div class="worklog-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">刷新第一页（只读）</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more" @click="workspace.refresh(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第一页' }} · 每页15条 · 仅本人</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存结果尚未确认。原内容、版本和幂等键已保留；刷新只查询，不会自动重发。请勿刷新浏览器或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">使用原请求重试</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次请求被拒绝。请刷新并检查当前版本，再明确提交；不会自动覆盖。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`最近确认保存：${state.receipt.id} · v${state.receipt.version}；这是该次请求回执，当前记录以重新读取为准`" />
      <AppTable :rows="state.page?.items || []" :loading="state.busy === 'read'" :empty-text="state.page ? '本页暂无本人实填工时，不表示默认工时为0' : '工时尚未成功读取'">
        <el-table-column label="类型" min-width="100"><template #default="{ row }">{{ labels[row.kind as WorklogKind] }}</template></el-table-column>
        <el-table-column label="实际起止（UTC）" min-width="240"><template #default="{ row }"><span class="worklog-id">{{ row.started_at }}<br>{{ row.ended_at }}</span></template></el-table-column>
        <el-table-column label="物理时长（秒）" min-width="140"><template #default="{ row }">{{ row.elapsed_millis / 1000 }}</template></el-table-column>
        <el-table-column label="备注 / 版本" min-width="180"><template #default="{ row }"><span class="worklog-note">{{ row.note ?? '未填写备注' }}</span><span class="worklog-id">v{{ row.version }}</span></template></el-table-column>
        <el-table-column label="操作" width="115" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="Boolean(state.busy)" @click="workspace.show(row.id)">读取及历史</el-button></template></el-table-column>
      </AppTable>
      <section v-if="state.selected" class="worklog-section">
        <h3>本人记录 v{{ state.selected.version }}</h3><p class="worklog-id">{{ state.selected.id }}</p>
        <p>{{ labels[state.selected.kind] }} · {{ state.selected.started_at }} → {{ state.selected.ended_at }}</p><p class="worklog-note">{{ state.selected.note ?? '未填写备注' }}</p>
        <el-button :disabled="locked" @click="editSelected">以当前版本修改</el-button>
        <h4>不可变修订历史</h4><p class="field-help">新修改追加修订，历史保留。每页最多15条，以下仅为已读取页。</p>
        <ul class="worklog-history"><li v-for="revision in state.history?.items || []" :key="revision.version"><strong>v{{ revision.version }} · {{ revision.action === 'CREATE' ? '创建' : '修改' }} · {{ labels[revision.kind] }}</strong><span class="worklog-id">{{ revision.started_at }} → {{ revision.ended_at }} · {{ revision.elapsed_millis / 1000 }} 秒</span><p class="worklog-note">{{ revision.note ?? '未填写备注' }}</p></li></ul>
        <el-button :disabled="Boolean(state.busy) || !state.history?.has_more" @click="workspace.show(state.selected!.id, true)">更早修订</el-button>
      </section>
      <section v-if="writable" class="worklog-section">
        <h3>{{ editingId ? '修改本人记录' : '新增本人实填工时' }}</h3><p v-if="editingId" class="worklog-id">{{ editingId }} · 修改保留原记录身份及所有历史</p>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="工作类型（必须明确选择）"><el-select v-model="kind" :disabled="locked" aria-label="实填工时类型" placeholder="请选择类型"><el-option v-for="(label, value) in labels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
          <el-form-item label="实际开始时间（含秒和时区）"><el-input v-model="startedAt" :disabled="locked" aria-label="实填开始时间" placeholder="例如 2026-10-02T09:00:00+08:00" /></el-form-item>
          <el-form-item label="实际结束时间（含秒和时区）"><el-input v-model="endedAt" :disabled="locked" aria-label="实填结束时间" placeholder="例如 2026-10-02T10:30:00+08:00" /></el-form-item>
          <p class="field-help">明确输入日期、秒与Z或±HH:MM时区，最多3位毫秒；不会自动使用当前时间。相同起止表示明确实填0，缺值不会补0或4小时。</p>
          <el-form-item label="原始备注（可留空，最多2000字符）"><el-input v-model="note" type="textarea" :rows="3" :disabled="locked" aria-label="实填工时备注" /></el-form-item>
          <p v-if="elapsed !== null">起止物理差：{{ elapsed / 1000 }} 秒。未计费取整，未换算成本。</p><p v-if="validation" role="alert" class="worklog-validation">{{ validation }}</p>
          <div class="worklog-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.parentVersion">{{ editingId ? '确认追加修改修订' : '保存本人工时' }}</el-button><el-button :disabled="locked" @click="clearForm">清空表单 / 改为新增</el-button></div>
        </el-form>
      </section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起不会取消已发送的保存，未确认请求保留在当前页面。</span></template>
  </el-drawer>
</template>

<style>
.worklog-drawer{max-width:100vw}.worklog-drawer .el-alert{margin-bottom:12px}.worklog-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.worklog-note{white-space:pre-wrap;overflow-wrap:anywhere}.worklog-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0}.worklog-section{border-top:1px solid var(--el-border-color);padding-top:12px;margin-top:24px}.worklog-section .el-select{width:100%}.worklog-validation{color:var(--el-color-danger)}.worklog-history{padding-left:20px}.worklog-history li{margin:16px 0}.worklog-history p{margin:4px 0}
@media(max-width:600px){.worklog-drawer .el-drawer__body{padding:14px}.worklog-drawer .el-button{min-height:44px;height:auto;white-space:normal}.worklog-drawer .el-input__wrapper,.worklog-drawer .el-select__wrapper{min-height:44px}.worklog-actions>span{width:100%}.worklog-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}.worklog-section p{overflow-wrap:anywhere}}
</style>
