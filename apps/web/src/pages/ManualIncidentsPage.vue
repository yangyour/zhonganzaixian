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
    <header class="proto-page-head"><div><h1>手工故障与事件登记 <span class="proto-badge">{{ session?.tenant.name }}</span></h1><p>登记电话报障、现场反馈及人工巡检发现的异常事件 · 当前租户内部归档</p></div><el-button v-if="permitted && can('manual_incidents:write')" :disabled="state.busy" type="primary" @click="editor?.prepare()">新建手工登记</el-button></header>
    <el-alert type="warning" :closable="false" show-icon title="人工登记事件独立记录现场反馈；监控正常不会让手工记录自动恢复，正式工单关联与 SLA 考核待流程启用后联动。" />
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前读取权限不可用，原范围数据已清除" />
    <template v-else>
      <div class="proto-kpis"><div><span>本页手工登记</span><b>{{ state.page ? state.page.items.length : '—' }}</b><small>仅统计当前加载页</small></div><div><span>本页涉及资产</span><b>{{ state.page ? new Set(state.page.items.map(row => row.asset_id)).size : '—' }}</b><small>当前页按资产去重</small></div><div><span>本页严重 / 灾难</span><b>{{ state.page ? state.page.items.filter(row => ['high','disaster'].includes(row.severity)).length : '—' }}</b><small>人工上报严重等级</small></div><div><span>当前登记详情</span><b>{{ state.selected ? '已选择' : '未选择' }}</b><small>支持独立确认恢复状态</small></div></div>
      <div class="manual-workspace" :class="{ 'has-selection': state.selected }"><div class="proto-panel manual-registers">
      <div class="proto-section-head"><h2>手工登记列表</h2><el-button :loading="state.busy" @click="reader.list()">刷新列表</el-button></div>
      <ApiErrorNotice :error="state.error" />
      <p v-if="!state.page" class="proto-empty field-help">列表尚未成功读取，不能据此认定没有事件</p>
      <el-empty v-else-if="!state.page.items.length" description="当前页暂无手工登记记录" />
      <div v-else class="proto-table-wrap"><table class="proto-table manual-list"><thead><tr><th>事件 / 登记时资产</th><th>严重级别</th><th>登记时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in state.page.items" :key="item.id" :class="{ selected: state.selected?.id === item.id }"><td><button class="manual-row proto-link" :disabled="state.busy" :aria-pressed="state.selected?.id === item.id" @click="reader.select(item.id)"><strong class="manual-original">{{ item.title }}</strong></button><span class="proto-sub">{{ item.asset_name_at_registration }} · v{{ item.observed_asset_version }}</span></td><td><span class="proto-badge warning">{{ item.severity_label }}</span><span class="proto-sub">登记时未确认恢复</span></td><td>{{ displayTime(item.registered_at) }}<span class="proto-sub">不是故障发生时间</span></td><td><el-button link type="primary" :disabled="state.busy" @click="reader.select(item.id)">查看详情</el-button></td></tr></tbody></table></div>
      <div class="proto-table-footer"><span>{{ state.paged ? '后续页' : '第一页' }} · 每页 15 条</span><el-button :disabled="state.busy || !state.page?.has_more" @click="reader.list(true)">下一页</el-button></div></div>
      <section v-if="state.selected" class="manual-detail proto-panel" aria-live="polite"><div class="manual-actions"><h2>登记记录详情</h2><el-button v-if="can('manual_recovery:read')" :disabled="state.busy" @click="recovery?.prepare(state.selected)">人工恢复确认记录</el-button><el-button :disabled="state.busy" @click="reader.select(state.selected.id)">刷新详情</el-button></div><p class="manual-source">人工登记 · 原始快照不可篡改</p><h3 class="manual-original">{{ state.selected.title }}</h3><dl><dt>严重级别</dt><dd>{{ state.selected.severity_label }}（未推断工单优先级）</dd><dt>登记时资产快照</dt><dd>{{ state.selected.asset_name_at_registration }} · v{{ state.selected.observed_asset_version }}</dd><dt>资产ID</dt><dd>{{ state.selected.asset_id }}</dd><dt>登记时间</dt><dd>{{ displayTime(state.selected.registered_at) }}</dd><dt>内部登记人ID</dt><dd>{{ state.selected.registered_by }}</dd><dt>原登记ID</dt><dd>{{ state.selected.id }}</dd><dt>实际发生时间</dt><dd>待确认</dd><dt>监控状态</dt><dd>待确认（不据此推断正常）</dd><dt>正式处置状态</dt><dd>暂未关联</dd><dt>工单优先级</dt><dd>暂未关联</dd><dt>原登记时恢复状态</dt><dd>登记时尚未确认恢复。请打开独立人工恢复面板读取当前确认，不能用原快照推断当前恢复状态；监控自动恢复不适用</dd></dl><p class="field-help">此记录未自动创建工单。人工恢复通过独立面板记录；后续正式处置与建单仍待完成。</p></section>
      </div>
    </template>
  </section>
</template>
<style scoped>
.manual-incidents-page header p{color:var(--el-text-color-secondary)}
.manual-workspace{display:grid;grid-template-columns:minmax(0,1fr);gap:12px;align-items:start;margin-top:12px}
.manual-workspace.has-selection{grid-template-columns:minmax(0,1.3fr) minmax(340px,1fr)}
.manual-registers{min-width:0}
.manual-list{display:table;width:100%;border-collapse:collapse}
.manual-list .selected{background:#f0f6ff}
.manual-list .manual-original{font-weight:500;line-height:1.6}
.manual-row{display:block;padding:0;border:0;border-radius:0;background:none;color:#2d79f6;text-align:left;cursor:pointer;overflow-wrap:anywhere}
.manual-row[aria-pressed=true]{background:none;font-weight:600}
.manual-row:disabled{cursor:wait}
.manual-source{color:var(--el-color-warning-dark-2);font-weight:600}
.manual-original{white-space:pre-wrap;overflow-wrap:anywhere}
.manual-detail{margin:0;padding:18px;border:1px solid #eaf0f8}
.manual-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:0 0 18px}
.manual-actions h2{margin:0;font-size:14px}
.manual-detail h3{font-size:15px;margin:18px 0}
.manual-detail dl{display:grid;grid-template-columns:110px minmax(0,1fr);font-size:12px;gap:12px}
.manual-detail dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}
.manual-detail dt{color:var(--el-text-color-secondary)}
.manual-detail .field-help{font-size:12px;line-height:1.7}
@media(max-width:1100px){.manual-workspace.has-selection{grid-template-columns:1fr}}
@media(max-width:700px){.manual-list{grid-template-columns:1fr}.manual-incidents-page .el-button{min-height:44px;height:auto;white-space:normal}.manual-actions>span{width:100%}.manual-detail dl{grid-template-columns:1fr;gap:6px}.manual-detail dd{margin-bottom:10px}}
</style>
