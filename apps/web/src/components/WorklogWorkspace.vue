<script setup lang="ts">
import { ElMessageBox, ElTableColumn } from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createWorklogWorkspace, emptyWorklogState, validateWorklogInput, worklogInstant, type WorklogInput, type WorklogKind, type WorklogState } from '../utils/worklogWorkspace'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import AppTable from './AppTable.vue'
import AutoTextarea from './AutoTextarea.vue'
const props = defineProps<{ modelValue: boolean; ticket: RegisteredTicket | null; guardedByParent?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [ticketId: string] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<WorklogState>(emptyWorklogState())
const workspace = createWorklogWorkspace(api, value => { state.value = value })
watch(() => state.value.receipt ? `${state.value.receipt.id}:${state.value.receipt.version}` : '', key => { if (key && state.value.receipt) emit('saved', state.value.receipt.ticket_id) })
const permitted = computed(() => Boolean(session.value && props.ticket?.tenant_id === session.value.tenant.id && can('worklogs:read_own') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('worklogs:write_own'))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const confirming = ref(false)
let disposed = false
const locked = computed(() => !writable.value || Boolean(state.value.busy) || unresolved.value || confirming.value)
const kind = ref<WorklogKind | ''>(''), startedAt = ref(''), endedAt = ref(''), note = ref(''), editingId = ref(''), editingVersion = ref<number | null>(null), validation = ref('')
const formSnapshot = computed(() => JSON.stringify([kind.value, startedAt.value, endedAt.value, note.value]))
const formBaseline = ref(formSnapshot.value)
const dirty = computed(() => formSnapshot.value !== formBaseline.value)
const labels: Record<WorklogKind, string> = { REMOTE: '远程处理', ONSITE: '现场处理', INSPECTION: '巡检', COMMUTE: '通勤' }
function clearForm() { kind.value = ''; startedAt.value = ''; endedAt.value = ''; note.value = ''; editingId.value = ''; editingVersion.value = null; validation.value = ''; formBaseline.value = formSnapshot.value }
function input(): WorklogInput { return { kind: kind.value as WorklogKind, started_at: startedAt.value.trim(), ended_at: endedAt.value.trim(), note: note.value === '' ? null : note.value } }
const elapsed = computed(() => { try { const facts = input(); validateWorklogInput(facts); return worklogInstant(facts.ended_at) - worklogInstant(facts.started_at) } catch { return null } })
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value, writable.value, props.ticket?.id]))
watch(contextKey, () => { clearForm(); confirming.value = false; const current = session.value; workspace.reset(permitted.value && current && props.ticket ? { tenantId: current.tenant.id, ticketId: props.ticket.id, actorId: current.user.id, permissionVersion: current.permission_version, canWrite: writable.value } : null); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true, flush: 'sync' })
watch(open, value => { if (value && permitted.value && !state.value.page && !state.value.busy) void workspace.refresh() })
async function editSelected() {
  const selected = state.value.selected
  if (locked.value || !selected) return
  const turn = contextKey.value, snapshot=inputSnapshot()
  if (!await canLeave('替换当前工时输入') || turn !== contextKey.value || locked.value) return
  // Re-read before establishing the edit baseline; never change an unresolved original command.
  if (!await workspace.show(selected.id)) return
  const current = state.value.selected
  if (disposed || turn !== contextKey.value || !current || current.id !== selected.id || snapshot!==inputSnapshot()) return
  kind.value = current.kind; startedAt.value = current.started_at; endedAt.value = current.ended_at; note.value = current.note ?? ''; editingId.value = current.id; editingVersion.value = current.version; validation.value = ''
  formBaseline.value = formSnapshot.value
}
async function save() {
  if (locked.value) return
  const turn = contextKey.value, snapshot=inputSnapshot()
  if (!state.value.parentVersion || editingId.value && (state.value.selected?.id !== editingId.value || state.value.selected?.version !== editingVersion.value)) { validation.value = '记录状态已变更，请先刷新列表并重新选择需修改的工时记录'; return }
  try { const facts = input(); validateWorklogInput(facts); validation.value = ''; if (await workspace.submit(facts, editingId.value && editingVersion.value ? { id: editingId.value, version: editingVersion.value } : null) && !disposed && turn === contextKey.value && snapshot===inputSnapshot()) clearForm() }
  catch (error) { if (!disposed && turn === contextKey.value && snapshot===inputSnapshot()) validation.value = error instanceof Error ? error.message : '请检查工时填写内容' }
}
async function retryOriginal() { const turn = contextKey.value, originalForm=formSnapshot.value, originalId=editingId.value, originalVersion=editingVersion.value; if (await workspace.retry() && !disposed && turn === contextKey.value && originalForm===formSnapshot.value && originalId===editingId.value && originalVersion===editingVersion.value) clearForm() }
async function canLeave(action = '离开本页、切换租户或退出') {
  if (disposed || confirming.value) return false
  if (workspace.unresolved()) { ElMessage.warning('工时提交结果尚未确认，请先完成重试核对后再切换工单或离开页面'); return false }
  if (!dirty.value) return true
  const turn = contextKey.value, snapshot = formSnapshot.value
  confirming.value = true
  try {
    await ElMessageBox.confirm(`${action}将清除尚未保存的工时输入，是否继续？`, '工时输入尚未保存', { confirmButtonText: '放弃输入并继续', cancelButtonText: '继续编辑', type: 'warning' })
    // Approval alone must not erase a draft: another guard or the transition itself may still fail.
    return !disposed && turn === contextKey.value && snapshot === formSnapshot.value && !workspace.unresolved()
  } catch { return false }
  finally { if (!disposed && turn === contextKey.value) confirming.value = false }
}
async function resetForm() { if (!locked.value && await canLeave('清空表单并改为新增')) clearForm() }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
function inputSnapshot(){return JSON.stringify([contextKey.value,formSnapshot.value,editingId.value,editingVersion.value,workspace.unresolved()])}
onBeforeRouteLeave(() => props.guardedByParent ? true : canLeave())
const unregisterContextGuard = contextTransitionGuards.register(() => props.guardedByParent ? true : canLeave(),inputSnapshot)
onUnmounted(() => { unregisterContextGuard(); disposed = true; workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved, canLeave, inputSnapshot, cancelRead:workspace.cancelRead, retryRead:workspace.retryRead })
</script>

<template>
  <el-drawer v-model="open" title="本人工时填报与记录" size="900px" class="worklog-drawer" :close-on-click-modal="false">
    <p v-if="ticket"><strong>{{ ticket.title }}</strong><span class="worklog-id">{{ ticket.receipt_no }} · {{ ticket.id }}</span></p>
    <el-alert type="info" :closable="false" title="记录工程师实际作业起止时间（跨日记录按原始时段保存，不自动拆分；计费核算以商务结算规则为准）。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有本人工时权限，请重新打开详情" />
    <template v-else>
      <div class="worklog-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">刷新列表</el-button><el-button :disabled="Boolean(state.busy) || !state.page?.has_more" @click="workspace.refresh(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第 1 页' }} · 每页 15 条 · 仅本人记录</span></div>
      <ReadRecoveryActions :busy="state.busy" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="工时" :cancel="workspace.cancelRead" :retry="workspace.retryRead" />
      <p v-if="state.historyRestarted" role="status">记录版本已更新，历史已从最新版本重新读取，未沿用旧分页边界。</p>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="提交结果待确认：已保留并锁定原请求；刷新仅查看当前记录，不能确认原请求，请点击“重试提交”恢复完全相同的原请求。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable" @click="retryOriginal">重试提交</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次提交未通过：请刷新列表并核对当前记录版本后再重新提交。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`工时记录已保存（记录编号：${state.receipt.id} · v${state.receipt.version}）`" />
      <AppTable :rows="state.page?.items || []" :loading="state.busy === 'read'" :empty-text="state.page ? '当前页暂无本人工时记录' : state.busy==='read' ? '正在读取工时记录…' : state.error ? '工时读取未完成，请重试' : '尚未读取工时记录'">
        <el-table-column label="类型" min-width="100"><template #default="{ row }">{{ labels[row.kind as WorklogKind] }}</template></el-table-column>
        <el-table-column label="实际起止（UTC）" min-width="240"><template #default="{ row }"><span class="worklog-id">{{ row.started_at }}<br>{{ row.ended_at }}</span></template></el-table-column>
        <el-table-column label="作业时长（秒）" min-width="140"><template #default="{ row }">{{ row.elapsed_millis / 1000 }}</template></el-table-column>
        <el-table-column label="工作描述 / 版本" min-width="180"><template #default="{ row }"><span class="worklog-note">{{ row.note ?? '未填写' }}</span><span class="worklog-id">v{{ row.version }} · 人工填报</span></template></el-table-column>
        <el-table-column label="操作" width="115" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="Boolean(state.busy)" @click="workspace.show(row.id)">详情与历史</el-button></template></el-table-column>
      </AppTable>
      <section v-if="state.selected" class="worklog-section">
        <h3>工时记录详情 v{{ state.selected.version }}</h3><p class="worklog-id">{{ state.selected.id }}</p>
        <p>{{ labels[state.selected.kind] }} · {{ state.selected.started_at }} → {{ state.selected.ended_at }}</p><p class="worklog-note">{{ state.selected.note ?? '未填写' }}</p>
        <el-button :disabled="locked" @click="editSelected">按当前版本修改</el-button>
        <h4>修订历史记录</h4><p class="field-help">每次修改均保留完整修订轨迹（每页最多展示 15 条）。</p>
        <ul class="worklog-history"><li v-for="revision in state.history?.items || []" :key="revision.version"><strong>v{{ revision.version }} · {{ revision.action === 'CREATE' ? '创建' : '修改' }} · {{ labels[revision.kind] }}</strong><span class="worklog-id">{{ revision.started_at }} → {{ revision.ended_at }} · {{ revision.elapsed_millis / 1000 }} 秒</span><p class="worklog-note">{{ revision.note ?? '未填写' }}</p></li></ul>
        <el-button :disabled="Boolean(state.busy) || !state.history?.has_more" @click="workspace.show(state.selected!.id, true)">加载更早修订</el-button>
      </section>
      <section v-if="writable" class="worklog-section">
        <h3>{{ editingId ? '修改工时记录' : '新增工时记录' }}</h3><p v-if="editingId" class="worklog-id">{{ editingId }} · 修改后将生成新版本并保留历史记录</p>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="工作类型"><el-select v-model="kind" :disabled="locked" aria-label="实填工时类型" placeholder="请选择工作类型"><el-option v-for="(label, value) in labels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
          <el-form-item label="实际开始时间（含秒和时区）"><el-input v-model="startedAt" :disabled="locked" aria-label="实填开始时间" placeholder="例如 2026-10-02T09:00:00+08:00" /></el-form-item>
          <el-form-item label="实际结束时间（含秒和时区）"><el-input v-model="endedAt" :disabled="locked" aria-label="实填结束时间" placeholder="例如 2026-10-02T10:30:00+08:00" /></el-form-item>
          <p class="field-help">请按 ISO 格式填写含秒与时区偏移（<code>Z</code> 或 <code>±HH:MM</code>）的完整时间；跨日时段将按原始起止时间保存。</p>
          <el-form-item label="工作描述（选填，最多 2000 字符）"><AutoTextarea v-model="note" :min-rows="4" :max-rows="10" :disabled="locked" aria-label="实填工时备注" /></el-form-item>
          <p v-if="elapsed !== null" class="worklog-elapsed-preview">本次核算时长：<strong>{{ elapsed / 1000 }} 秒</strong>（人工填报）</p><p v-if="validation" role="alert" class="worklog-validation">{{ validation }}</p>
          <div class="worklog-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.parentVersion">{{ editingId ? '保存修改' : '保存工时记录' }}</el-button><el-button :disabled="locked" @click="resetForm">清空表单 / 改为新增</el-button></div>
        </el-form>
      </section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起面板将保留当前未保存输入与待确认请求。</span></template>
  </el-drawer>
</template>

<style scoped>
:deep(.worklog-drawer){max-width:100vw}
:deep(.worklog-drawer .el-alert){margin-bottom:12px}
.worklog-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere;margin-top:2px}
.worklog-note{white-space:pre-wrap;overflow-wrap:anywhere}
.worklog-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0}
.worklog-section{border-top:1px solid var(--el-border-color);padding-top:14px;margin-top:24px}
.worklog-section :deep(.el-select){width:100%}
.worklog-elapsed-preview{padding:8px 12px;background:#f4f8ff;border:1px solid #dbe8ff;border-radius:6px;font-size:13px;color:#1e3a8a}
.worklog-validation{color:var(--el-color-danger);font-size:12px}
.worklog-history{list-style:none;padding:0;margin:12px 0}
.worklog-history li{margin:10px 0;padding:10px 12px;background:#f8fafd;border:1px solid #e5edf5;border-radius:6px}
.worklog-history p{margin:6px 0 0}
@media(max-width:600px){
  :deep(.worklog-drawer .el-drawer__body){padding:14px}
  :deep(.worklog-drawer .el-button){min-height:44px;height:auto;white-space:normal}
  :deep(.worklog-drawer .el-input__wrapper),:deep(.worklog-drawer .el-select__wrapper){min-height:44px}
  .worklog-actions>span{width:100%}
  :deep(.worklog-drawer .el-drawer__footer .field-help){display:block;margin-top:8px}
  .worklog-section p{overflow-wrap:anywhere}
}
</style>

