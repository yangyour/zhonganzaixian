<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createManualIncidentReader, emptyManualIncidentReadState, type ManualIncidentReadState } from '../utils/manualIncidentReader'
import { displayTime } from '../utils/display'
import ManualRecoveryPanel from '../components/ManualRecoveryPanel.vue'
import ManualIncidentEditor from '../components/ManualIncidentEditor.vue'
import type { ManualIncidentEntry } from '../utils/manualIncidentReader'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state = shallowRef<ManualIncidentReadState>(emptyManualIncidentReadState())
const reader = createManualIncidentReader(api, value => { state.value = value })
const recoveryOpen = ref(false), recovery = ref<InstanceType<typeof ManualRecoveryPanel> | null>(null)
const editorOpen = ref(false), editor = ref<InstanceType<typeof ManualIncidentEditor> | null>(null)
async function afterSaved(value: ManualIncidentEntry) { if (permitted.value && await reader.list()) await reader.select(value.id) }
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('manual_incidents:read') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => { const current = session.value; reader.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version } : null); if (permitted.value) void reader.list() }, { immediate: true, flush: 'sync' })
onUnmounted(reader.dispose)
</script>
<template>
  <ManualRecoveryPanel ref="recovery" v-model="recoveryOpen" />
  <ManualIncidentEditor ref="editor" v-model="editorOpen" @saved="afterSaved" />
  <section class="manual-incidents-page">
    <header><h1>手工来源事件登记</h1><p>电话、现场等外部来源的明确登记事实 · 当前授权租户内部可见</p></header>
    <details class="page-guidance"><summary>使用范围与说明</summary><el-alert type="warning" :closable="false" show-icon title="仅完成登记，内容为自述、未经独立核验。监控正常不会让手工记录自动恢复；正式处置、工单关联和SLA尚未完成。" /></details>
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前读取权限不可用，原范围数据已清除" />
    <template v-else>
      <div class="manual-actions"><el-button v-if="can('manual_incidents:write')" :disabled="state.busy" type="primary" @click="editor?.prepare()">新建手工登记</el-button><el-button :loading="state.busy" @click="reader.list()">刷新第一页（只读）</el-button><el-button :disabled="state.busy || !state.page?.has_more" @click="reader.list(true)">下一页</el-button><span>{{ state.paged ? '后续页' : '第一页' }} · 每页15条 · 翻页按各次读取快照</span></div>
      <ApiErrorNotice :error="state.error" />
      <p v-if="!state.page" class="field-help">列表尚未成功读取，不能据此认定没有事件</p>
      <el-empty v-else-if="!state.page.items.length" description="本页没有手工登记" />
      <div class="manual-list"><button v-for="item in state.page?.items || []" :key="item.id" class="manual-row" :disabled="state.busy" :aria-pressed="state.selected?.id === item.id" @click="reader.select(item.id)"><div class="manual-row-tags"><span class="manual-source">手工来源</span><span>{{ item.severity_label }}</span><span>仅登记 · 原始快照</span></div><strong class="manual-original">{{ item.title }}</strong><span>登记时关联资产：{{ item.asset_name_at_registration }} · v{{ item.observed_asset_version }}</span><small>登记时间：{{ displayTime(item.registered_at) }}（不是故障发生时间）</small></button></div>
      <section v-if="state.selected" class="manual-detail" aria-live="polite"><div class="manual-actions"><h2>原始登记详情</h2><el-button v-if="can('manual_recovery:read')" :disabled="state.busy" @click="recovery?.prepare(state.selected)">读取当前人工恢复 / 明确确认</el-button><el-button :disabled="state.busy" @click="reader.select(state.selected.id)">重新读取详情</el-button></div><p class="manual-source">手工来源 · 自述未核验 · 原登记不可编辑</p><h3 class="manual-original">{{ state.selected.title }}</h3><dl><dt>严重级别</dt><dd>{{ state.selected.severity_label }}（未推断工单优先级）</dd><dt>登记时资产快照</dt><dd>{{ state.selected.asset_name_at_registration }} · v{{ state.selected.observed_asset_version }}</dd><dt>资产ID</dt><dd>{{ state.selected.asset_id }}</dd><dt>登记时间</dt><dd>{{ displayTime(state.selected.registered_at) }}</dd><dt>内部登记人ID</dt><dd>{{ state.selected.registered_by }}</dd><dt>原登记ID</dt><dd>{{ state.selected.id }}</dd><dt>实际发生时间</dt><dd>未知（null）</dd><dt>监控状态</dt><dd>未知（null），不据此推断正常</dd><dt>正式处置状态</dt><dd>未提供（null）</dd><dt>工单优先级</dt><dd>未提供（null）</dd><dt>原登记时恢复状态</dt><dd>登记时尚未确认，原登记恢复时间未知（null）。请打开独立人工恢复面板读取当前确认，不能用原快照推断当前恢复状态；监控自动恢复不适用</dd></dl><p class="field-help">此记录未自动创建工单。人工恢复通过独立面板记录；后续正式处置与建单仍待完成，不能把登记或人工确认当作完整闭环</p></section>
    </template>
  </section>
</template>
<style scoped>
.manual-incidents-page header p{color:var(--el-text-color-secondary)}.manual-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:18px 0}.manual-actions h2{margin:0}.manual-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.manual-row{display:flex;flex-direction:column;gap:10px;text-align:left;padding:16px;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:var(--el-text-color-primary);cursor:pointer;overflow-wrap:anywhere}.manual-row[aria-pressed=true]{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.manual-row:disabled{cursor:wait}.manual-row-tags{display:flex;gap:10px;flex-wrap:wrap;font-size:12px}.manual-source{color:var(--el-color-warning-dark-2);font-weight:600}.manual-original{white-space:pre-wrap;overflow-wrap:anywhere}.manual-row small{color:var(--el-text-color-secondary)}.manual-detail{margin-top:24px;border-top:1px solid var(--el-border-color);padding-top:10px}.manual-detail dl{display:grid;grid-template-columns:150px minmax(0,1fr);gap:12px}.manual-detail dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}.manual-detail dt{color:var(--el-text-color-secondary)}
@media(max-width:700px){.manual-list{grid-template-columns:1fr}.manual-incidents-page .el-button{min-height:44px;height:auto;white-space:normal}.manual-actions>span{width:100%}.manual-detail dl{grid-template-columns:1fr;gap:6px}.manual-detail dd{margin-bottom:10px}}
</style>
