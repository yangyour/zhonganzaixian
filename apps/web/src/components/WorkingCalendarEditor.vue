<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createWorkingCalendarWriter, emptyCalendarWriteState, type CalendarWriteState } from '../utils/workingCalendarWriter'
import type { CalendarSnapshot } from '../utils/workingCalendarReader'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [value: CalendarSnapshot] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const permitted = computed(() => Boolean(session.value?.user.role === 'padmin' && can('configurations:write') && !switchingTenant.value))
const state = shallowRef<CalendarWriteState>(emptyCalendarWriteState())
const writer = createWorkingCalendarWriter(api, value => { state.value = value })
const form = reactive({ name: '', coverage_start: '', coverage_end: '', note: '', completeness: '', windowsDeclared: false, windows: [] as { start: string; end: string }[] })
const validation = ref(''), windowPage = ref(1)
const visibleWindows = computed(() => form.windows.slice((windowPage.value - 1) * 20, windowPage.value * 20).map((window, offset) => ({ window, index: (windowPage.value - 1) * 20 + offset })))
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || writer.unresolved() || Boolean(state.value.attempt))
const hasDraft = computed(() => state.value.editing && Boolean(form.name || form.coverage_start || form.coverage_end || form.note || form.windows.length || form.completeness || form.windowsDeclared))
function fill(value: CalendarSnapshot | null) { const draft = value?.draft; Object.assign(form, { name: draft?.name ?? '', coverage_start: draft?.coverage_start ?? '', coverage_end: draft?.coverage_end ?? '', note: draft?.note ?? '', completeness: draft ? draft.coverage_complete ? 'complete' : 'incomplete' : '', windowsDeclared: Boolean(draft), windows: draft ? draft.working_windows.map(w => ({ ...w })) : [] }); validation.value = ''; windowPage.value = 1 }
async function confirmReplace() { if (!hasDraft.value) return true; try { await ElMessageBox.confirm('将替换当前尚未发送的编辑内容。要继续吗？', '重新准备日历草稿', { type: 'warning', confirmButtonText: '替换编辑内容', cancelButtonText: '保留当前内容' }); return true } catch { return false } }
async function prepare(value: CalendarSnapshot | null) {
  if (!permitted.value) return false
  if (writer.unresolved() || state.value.busy) { open.value = true; ElMessage.warning('请先处理当前原请求，不能更换目标或版本'); return false }
  const generation = contextKey.value
  if (!await confirmReplace() || generation !== contextKey.value || !permitted.value) return false
  if (!writer.begin(value)) return false
  fill(value); open.value = true; return true
}
function addWindow() { if (locked.value || form.windows.length >= 1000) return; form.windows.push({ start: '', end: '' }); windowPage.value = Math.ceil(form.windows.length / 20) }
function removeWindow(index: number) { if (locked.value) return; form.windows.splice(index, 1); windowPage.value = Math.max(1, Math.min(windowPage.value, Math.ceil(form.windows.length / 20))) }
async function saved() { if (state.value.receipt) { ElMessage.success('日历草稿已保存，仍未生效'); emit('saved', state.value.receipt) } }
async function save() {
  if (locked.value || !state.value.editing) return
  if (!form.windowsDeclared || !['complete', 'incomplete'].includes(form.completeness)) { validation.value = '请明确声明已知窗口列表和覆盖完整性，不会使用默认值'; return }
  validation.value = ''
  try { if (await writer.submit({ name: form.name, coverage_start: form.coverage_start, coverage_end: form.coverage_end, working_windows: form.windows.map(w => ({ ...w })), coverage_complete: form.completeness === 'complete', note: form.note === '' ? null : form.note })) await saved() }
  catch (error) { validation.value = error instanceof Error ? error.message : '请核对日历输入' }
}
async function retry() { if (await writer.retry()) await saved() }
async function rebase() { if (!writer.unresolved() && await writer.refresh() && state.value.lastRead) await prepare(state.value.lastRead) }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => { const s = session.value; writer.reset(permitted.value && s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null); fill(null) }, { immediate: true })
function beforeUnload(event: BeforeUnloadEvent) { if (writer.unresolved() || hasDraft.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(async () => { if (writer.unresolved()) { ElMessage.warning('保存结果尚未确认，请先使用原请求重试'); return false } return confirmReplace() })
const unregisterContextGuard = contextTransitionGuards.register(async () => { if (writer.unresolved()) { ElMessage.warning('日历保存结果尚未确认，请先处理原请求再切换租户或退出'); return false } return confirmReplace() })
onUnmounted(() => { unregisterContextGuard(); writer.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ prepare, hasUnresolved: writer.unresolved })
</script>
<template>
  <el-drawer v-model="open" title="维护工作日历草稿（未生效）" size="860px" class="calendar-editor" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" title="仅保存当前租户未生效草稿。客户确认、正式SLA、工作周和节假日不会自动补全。时间必须显式带时区及秒，最多毫秒；固定显示时区Asia/Shanghai。" />
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前配置维护权限已失效，原范围编辑内容已清除" />
    <template v-else>
      <p v-if="state.baseline" class="calendar-id">固定编辑基准：{{ state.baseline.id }} · v{{ state.baseline.version }}。只读刷新不会替换此基准或未发送内容</p><p v-else-if="state.editing">明确创建新草稿；不继承任何默认工作时段</p>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存结果尚未确认。原内容、目标、版本和幂等键保留；请勿刷新浏览器，查询不会自动重发。" />
      <el-alert v-if="state.baseline && state.lastRead && state.baseline.version !== state.lastRead.version" type="warning" :closable="false" title="只读发现新版本；旧基准提交已阻止。当前输入保留，必须明确重新读取并准备编辑。" />
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次请求被拒绝；未自动重试或覆盖。可只读刷新核对，再明确重新准备编辑。" />
      <div class="calendar-actions"><el-button v-if="state.receipt || state.baseline" :disabled="state.busy === 'write'" @click="writer.refresh()">只读刷新已知对象</el-button><el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retry">使用完全相同原请求重试</el-button><el-button v-if="(state.baseline || state.receipt) && !writer.unresolved()" :disabled="Boolean(state.busy)" @click="rebase">重新读取并明确编辑最新版本</el-button></div>
      <p v-if="state.lastRead">最近只读所见：v{{ state.lastRead.version }}。未改变固定编辑基准</p>
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="'确认保存回执：' + state.receipt.id + ' · v' + state.receipt.version + '；仍未生效，客户未核验'" />
      <el-form v-if="state.editing" label-position="top" @submit.prevent="save">
        <el-form-item label="草稿名称（1至100字符，保留原文）"><el-input v-model="form.name" :disabled="locked" aria-label="日历草稿名称" /></el-form-item>
        <div class="calendar-time-grid"><el-form-item label="覆盖起点（显式Z或offset）"><el-input v-model="form.coverage_start" :disabled="locked" aria-label="日历覆盖起点" placeholder="例如2026-10-01T00:00:00+08:00" /></el-form-item><el-form-item label="覆盖终点（必须晚于起点）"><el-input v-model="form.coverage_end" :disabled="locked" aria-label="日历覆盖终点" placeholder="明确终点，不默认24小时" /></el-form-item></div>
        <el-form-item label="覆盖完整性（必须明确选择；声明不等于客户确认）"><el-select v-model="form.completeness" :disabled="locked" aria-label="日历覆盖完整性" placeholder="未声明"><el-option value="complete" label="明确声明覆盖完整" /><el-option value="incomplete" label="明确声明覆盖不完整" /></el-select></el-form-item>
        <h3>已知工作窗口</h3><p class="field-help">必须在覆盖范围内，终点晚于起点。保留输入顺序和重叠；试算时另取并集。允许明确空列表，不把未知变成默认班次。</p>
        <el-checkbox v-model="form.windowsDeclared" :disabled="locked">我明确提供下列已知窗口列表（也可以明确为空）</el-checkbox>
        <div v-for="item in visibleWindows" :key="item.index" class="calendar-window-editor"><strong>窗口 {{ item.index + 1 }}</strong><el-input v-model="item.window.start" :disabled="locked" :aria-label="'窗口' + (item.index + 1) + '起点'" placeholder="显式带时区起点" /><el-input v-model="item.window.end" :disabled="locked" :aria-label="'窗口' + (item.index + 1) + '终点'" placeholder="显式带时区终点" /><el-button :disabled="locked" @click="removeWindow(item.index)">移除未提交窗口</el-button></div>
        <div class="calendar-actions"><el-button :disabled="locked || form.windows.length >= 1000" @click="addWindow">新增空白窗口</el-button><span>共{{ form.windows.length }} / 1000（技术上限） · 每页20行</span><el-button :disabled="windowPage <= 1" @click="windowPage--">上一组窗口</el-button><el-button :disabled="windowPage * 20 >= form.windows.length" @click="windowPage++">下一组窗口</el-button></div>
        <el-form-item label="可选说明（空输入保存null，最多2000字符）"><el-input v-model="form.note" type="textarea" :rows="4" :disabled="locked" aria-label="日历草稿说明" placeholder="不要输入密码、token或运行配置凭据" /></el-form-item>
        <p v-if="validation" role="alert" class="calendar-error">{{ validation }}</p><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked">明确保存未生效草稿</el-button>
      </el-form>
    </template>
    <template #footer><el-button @click="open = false">暂时收起，保留本页编辑</el-button><p class="field-help">收起不撤销已发送请求。未确认结果必须使用原请求处理，不自动写入</p></template>
  </el-drawer>
</template>
<style>
.calendar-editor{max-width:100vw}.calendar-editor .el-alert{margin:12px 0}.calendar-time-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.calendar-window-editor{display:grid;grid-template-columns:90px 1fr 1fr auto;gap:8px;align-items:center;margin:12px 0}.calendar-editor .el-checkbox{height:auto;white-space:normal;margin:12px 0}.calendar-editor .el-select{width:100%}.calendar-editor .el-input__inner{font-family:inherit}
@media(max-width:600px){.calendar-editor .el-drawer__body{padding:14px}.calendar-time-grid,.calendar-window-editor{grid-template-columns:1fr}.calendar-editor .el-button{min-height:44px;height:auto;white-space:normal}.calendar-editor .el-input__inner,.calendar-editor .el-textarea__inner{font-size:16px}}
</style>
