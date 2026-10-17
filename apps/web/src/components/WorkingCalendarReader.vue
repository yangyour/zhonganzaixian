<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createWorkingCalendarReader, emptyCalendarReadState, type CalendarReadState } from '../utils/workingCalendarReader'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
import WorkingCalendarEditor from './WorkingCalendarEditor.vue'
import type { CalendarSnapshot } from '../utils/workingCalendarReader'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const permitted = computed(() => Boolean(session.value?.user.role === 'padmin' && can('configurations:read') && !switchingTenant.value))
const state = shallowRef<CalendarReadState>(emptyCalendarReadState())
const reader = createWorkingCalendarReader(api, value => { state.value = value })
const editorOpen = ref(false), editor = ref<InstanceType<typeof WorkingCalendarEditor> | null>(null)
async function afterSaved(value: CalendarSnapshot) { if (open.value && permitted.value) { await reader.list(); await reader.select(value.id) } }
const start = ref(''), seconds = ref(''), validation = ref('')
watch([start, seconds], () => { reader.clearPreview(); validation.value = '' })
const contextKey = computed(() => JSON.stringify([open.value, contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => {
  start.value = ''; seconds.value = ''; validation.value = ''
  const s = session.value
  reader.reset(open.value && permitted.value && s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null)
  if (open.value && permitted.value) void reader.list()
}, { immediate: true })
async function select(id: string, before: number | null = null) { validation.value = ''; await reader.select(id, before) }
async function preview() {
  validation.value = ''
  try { const amount = seconds.value === '' ? null : /^[0-9]+$/.test(seconds.value) ? Number(seconds.value) : NaN; await reader.preview(start.value === '' ? null : start.value, amount) }
  catch (error) { validation.value = error instanceof Error ? error.message : '请核对显式试算输入' }
}
const reasons: Record<string, string> = { DURATION_MISSING: '时长未知', START_MISSING: '起点未知', COVERAGE_INCOMPLETE: '覆盖声明不完整', START_OUTSIDE_COVERAGE: '起点不在覆盖范围内', INSUFFICIENT_WORKING_TIME: '已知工作窗口不足' }
onUnmounted(reader.dispose)
</script>

<template>
  <WorkingCalendarEditor ref="editor" v-model="editorOpen" @saved="afterSaved" />
  <el-drawer v-model="open" title="工作日历草稿 · 查询与试算" size="920px" class="calendar-reader" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" show-icon title="当前工作日历记录均为待审批生效草稿；工作时段试算仅供排班与方案核对，不代表正式 SLA 承诺。" />
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前租户配置读取权限不可用，原范围数据已清除" />
    <template v-else>
      <div class="calendar-actions"><el-button :loading="state.busy" @click="reader.list()">刷新列表</el-button><el-button :disabled="state.busy || !state.page?.has_more" @click="reader.list(true)">下一页</el-button><el-button v-if="can('configurations:write')" :disabled="state.busy" @click="editor?.prepare(null)">新建未生效草稿</el-button><span>{{ state.paged ? '后续页' : '第一页' }} · 每页15份</span></div>
      <ApiErrorNotice :error="state.error" />
      <p v-if="!state.page" class="field-help">正在加载日历草稿列表…</p>
      <el-empty v-else-if="!state.page.items.length" description="本页没有工作日历草稿" />
      <div class="calendar-list"><button v-for="item in state.page?.items || []" :key="item.id" class="calendar-item" :disabled="state.busy" :aria-pressed="state.selected?.id === item.id" @click="select(item.id)"><strong>{{ item.draft.name }}</strong><span>v{{ item.version }} · {{ item.draft.working_windows.length }}个原始窗口 · {{ item.draft.coverage_complete ? '填报为完整' : '填报为不完整' }}</span><small>{{ displayTime(item.updated_at) }} · 未生效</small></button></div>
      <section v-if="state.selected" class="calendar-section">
        <div class="calendar-actions"><h3>{{ state.selected.draft.name }} · v{{ state.selected.version }}</h3><el-button :disabled="state.busy" @click="select(state.selected.id)">重新读取所选版本</el-button></div>
        <p class="calendar-id">{{ state.selected.id }}</p><el-button v-if="can('configurations:write')" :disabled="state.busy" @click="editor?.prepare(state.selected)">以所选v{{ state.selected.version }}明确编辑</el-button>
        <p>时区：Asia/Shanghai · 覆盖：{{ displayTime(state.selected.draft.coverage_start) }} 至 {{ displayTime(state.selected.draft.coverage_end) }}</p>
        <p>覆盖完整性：{{ state.selected.draft.coverage_complete ? '填报人声明完整（待客户核验）' : '未声明完整（暂不计算截止时间）' }}</p>
        <p class="calendar-text">说明：{{ state.selected.draft.note ?? '未提供' }}</p>
        <details><summary>原始工作窗口（{{ state.selected.draft.working_windows.length }}个，保留顺序与重复，试算按并集）</summary><p v-if="!state.selected.draft.working_windows.length">未配置工作窗口</p><ol class="calendar-windows"><li v-for="(window, index) in state.selected.draft.working_windows" :key="index">{{ displayTime(window.start) }} → {{ displayTime(window.end) }}<small>{{ window.start }} → {{ window.end }}</small></li></ol></details>
        <h3>工作时长截止试算</h3><p class="field-help">基于上方所选日历版本执行只读试算（不修改或发布日历草稿；起点与所需时长请按实际测算场景填写）。</p>
        <el-form label-position="top" @submit.prevent="preview">
          <el-form-item label="计时起点（含时区及秒，留空表示暂未指定）"><el-input v-model="start" :disabled="state.busy" aria-label="日历试算起点" placeholder="例如 2026-10-01T09:00:00+08:00" /></el-form-item>
          <el-form-item label="所需工作时长（整数秒，留空表示暂未指定）"><el-input v-model="seconds" :disabled="state.busy" inputmode="numeric" aria-label="日历试算所需秒数" placeholder="请输入所需工作秒数" /></el-form-item>
          <p v-if="validation" role="alert" class="calendar-error">{{ validation }}</p><el-button type="primary" native-type="submit" :disabled="state.busy">对所选 v{{ state.selected.version }} 执行只读试算</el-button>
        </el-form>
        <div v-if="state.preview" class="calendar-result" aria-live="polite"><strong>{{ state.preview.status === 'CALCULATED' ? '草稿工作窗口试算已完成' : '无法得出截止：' + (reasons[state.preview.reason || ''] || state.preview.reason) }}</strong><p>试算版本：v{{ state.preview.calendar_version }} · 实际提交起点：{{ state.preview.start ? displayTime(state.preview.start) : '未指定' }} · 时长：{{ state.preview.required_seconds === null ? '未指定' : state.preview.required_seconds + '秒' }}</p><p>草稿试算截止：{{ state.preview.scenario_deadline ? displayTime(state.preview.scenario_deadline) : '无法确定' }}</p><p><strong>正式 SLA 截止时间：暂未启用</strong></p><p>剩余时长：{{ state.preview.remaining_duration ?? '未指定' }} · 消耗工作区间：{{ state.preview.consumed.length }}个</p></div>
        <h3>不可变修订历史</h3><div class="calendar-actions"><span>{{ state.historyPaged ? '历史后续页' : '最新历史页' }}</span><el-button :disabled="state.busy || !state.history?.has_more" @click="select(state.selected.id, state.history?.next_before_version ?? null)">更早版本</el-button></div>
        <article v-for="revision in state.history?.items || []" :key="revision.version" class="calendar-revision"><strong>v{{ revision.version }} · {{ displayTime(revision.recorded_at) }}</strong><p>名称：{{ revision.after.name }} · 窗口{{ revision.after.working_windows.length }}个 · {{ revision.after.coverage_complete ? '声明完整' : '不完整' }}</p><details><summary>{{ revision.before ? '查看该版本前后原始草稿' : '查看初版原始草稿' }}</summary><pre>{{ JSON.stringify({ before: revision.before, after: revision.after }, null, 2) }}</pre></details></article>
      </section>
    </template>
    <template #footer><el-button @click="open = false">关闭查询</el-button><p class="field-help">查询和试算不修改日历草稿；创建/编辑需在独立面板明确保存，客户生效配置仍未启用</p></template>
  </el-drawer>
</template>
<style>
.calendar-reader{max-width:100vw}.calendar-reader .el-alert{margin-bottom:14px}.calendar-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0}.calendar-actions h3{margin:0}.calendar-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.calendar-item{display:flex;flex-direction:column;gap:7px;text-align:left;padding:14px;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:var(--el-text-color-primary);cursor:pointer;overflow-wrap:anywhere}.calendar-item[aria-pressed=true]{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.calendar-item:disabled{cursor:wait}.calendar-item span,.calendar-item small,.calendar-id{font-size:12px;color:var(--el-text-color-secondary)}.calendar-id{overflow-wrap:anywhere}.calendar-section{margin-top:24px;border-top:1px solid var(--el-border-color);padding-top:8px}.calendar-text{white-space:pre-wrap;overflow-wrap:anywhere}.calendar-windows{padding-left:22px}.calendar-windows li{margin:10px 0}.calendar-windows small{display:block;overflow-wrap:anywhere}.calendar-error{color:var(--el-color-danger)}.calendar-result{margin:18px 0;padding:16px;background:var(--el-color-info-light-9);border-radius:8px}.calendar-revision{padding:14px 0;border-bottom:1px solid var(--el-border-color)}.calendar-revision pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light);padding:12px}.calendar-reader summary{cursor:pointer}
@media(max-width:600px){.calendar-list{grid-template-columns:1fr}.calendar-reader .el-drawer__body{padding:14px}.calendar-reader .el-button{min-height:44px;height:auto;white-space:normal}.calendar-reader .el-input__inner{font-size:16px}.calendar-section p{overflow-wrap:anywhere}.calendar-actions>span{width:100%}}
</style>
