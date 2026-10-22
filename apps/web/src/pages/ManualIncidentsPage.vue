<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createManualIncidentReader, emptyManualIncidentReadState, type ManualIncidentReadState } from '../utils/manualIncidentReader'
import { displayTime } from '../utils/display'
import ManualRecoveryPanel from '../components/ManualRecoveryPanel.vue'
import ManualIncidentEditor from '../components/ManualIncidentEditor.vue'
import type { ManualIncidentEntry } from '../utils/manualIncidentReader'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state = shallowRef<ManualIncidentReadState>(emptyManualIncidentReadState())
const reader = createManualIncidentReader(api, value => { state.value = value })
let disposed=false,lastOpener:HTMLElement|null=null,lastOpenerId:string|null=null
const listHeading=ref<HTMLElement|null>(null),detailHeading=ref<HTMLElement|null>(null)
const recoveryOpen = ref(false), recovery = ref<InstanceType<typeof ManualRecoveryPanel> | null>(null)
const editorOpen = ref(false), editor = ref<InstanceType<typeof ManualIncidentEditor> | null>(null)
async function afterSaved(value: ManualIncidentEntry) { const owner=contextKey.value;if(!permitted.value||value.tenant_id!==session.value?.tenant.id)return;if(await reader.list()&&owner===contextKey.value&&permitted.value&&value.tenant_id===session.value?.tenant.id)await reader.select(value.id,value) }
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('manual_incidents:read') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value]))
watch(contextKey, () => { lastOpener=null;lastOpenerId=null;const current = session.value; reader.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version } : null); if (permitted.value) void reader.list() }, { immediate: true, flush: 'sync' })
async function focusResult(owner:string,id?:string){await nextTick();if(disposed||owner!==contextKey.value||state.value.busy||!permitted.value)return;if(id){if(state.value.selected?.id===id)detailHeading.value?.focus()}else listHeading.value?.focus()}
async function openEntry(item:ManualIncidentEntry,event?:Event){if(disposed||!permitted.value||state.value.busy||!state.value.page?.items.some(row=>row===item))return false;const owner=contextKey.value;lastOpener=event?.currentTarget instanceof HTMLElement?event.currentTarget:null;lastOpenerId=item.id;if(!await reader.select(item.id,item))return false;await focusResult(owner,item.id);return true}
async function list(next=false){if(disposed||!permitted.value||state.value.busy)return false;const owner=contextKey.value;if(!await reader.list(next))return false;await focusResult(owner);return true}
async function refreshDetail(){const selected=state.value.selected;if(disposed||!permitted.value||state.value.busy||!selected)return false;const owner=contextKey.value;if(!await reader.select(selected.id,selected))return false;await focusResult(owner,selected.id);return true}
async function retryRead(){const owner=contextKey.value;if(disposed||!permitted.value||!await reader.retryRead())return false;await focusResult(owner,state.value.selected?.id);return true}
async function closeDetails(){const owner=contextKey.value,id=state.value.selected?.id,opener=id===lastOpenerId?lastOpener:null;if(!reader.closeDetails())return;await nextTick();if(disposed||owner!==contextKey.value||!permitted.value)return;if(opener?.isConnected&&!opener.hasAttribute('disabled'))opener.focus();else listHeading.value?.focus()}
onUnmounted(()=>{disposed=true;reader.dispose()})
</script>
<template>
  <ManualRecoveryPanel ref="recovery" v-model="recoveryOpen" />
  <ManualIncidentEditor ref="editor" v-model="editorOpen" @saved="afterSaved" />
  <section class="manual-incidents-page">
    <header class="compact-page-toolbar proto-page-head"><div><h1 class="page-title-sr">手工故障与事件登记 </h1></div><el-button v-if="permitted && can('manual_incidents:write')" :disabled="state.busy" type="primary" @click="editor?.prepare()">新建手工登记</el-button></header>
    
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前读取权限不可用，原范围数据已清除" />
    <template v-else>
      
      <div class="manual-workspace" :class="{ 'has-selection': state.selected }"><div class="proto-panel manual-registers">
      <div class="proto-section-head"><h2 ref="listHeading" tabindex="-1">手工登记列表</h2><el-button :loading="state.busy" :disabled="state.busy" @click="list()">刷新列表</el-button></div>
      <ReadRecoveryActions :busy="state.busy?'read':null" :can-retry="reader.canRetryRead()" :scope-key="contextKey" label="手工登记列表详情" :cancel="reader.cancelRead" :retry="retryRead" />
      <ApiErrorNotice :error="state.error" />
      <p v-if="!state.page" class="proto-empty field-help">列表尚未成功读取，不能据此认定没有事件</p>
      <el-empty v-else-if="!state.page.items.length" description="当前页暂无手工登记记录" />
      <div v-else class="proto-table-wrap" tabindex="0" role="region" aria-label="手工登记列表，可横向滚动"><table class="proto-table manual-list"><caption class="manual-visually-hidden">当前租户手工事件登记页</caption><thead><tr><th scope="col">事件 / 登记时资产</th><th scope="col">严重级别</th><th scope="col">登记时间</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="item in state.page.items" :key="item.id" :class="{ selected: state.selected?.id === item.id }"><td><button class="manual-row proto-link" :disabled="state.busy" :aria-pressed="state.selected?.id === item.id" @click="openEntry(item,$event)"><strong class="manual-original">{{ item.title }}</strong></button><span class="proto-sub">{{ item.asset_name_at_registration }} · v{{ item.observed_asset_version }}</span></td><td><span class="proto-badge warning">{{ item.severity_label }}</span><span class="proto-sub">登记时未确认恢复</span></td><td>{{ displayTime(item.registered_at) }}<span class="proto-sub">不是故障发生时间</span></td><td><el-button link type="primary" :disabled="state.busy" @click="openEntry(item,$event)">查看详情</el-button></td></tr></tbody></table></div>
      <div class="proto-table-footer"><span>{{ state.paged ? '后续页' : '第一页' }} · 每页 15 条</span><el-button :disabled="state.busy || !state.page?.has_more" @click="list(true)">下一页</el-button></div></div>
      <section v-if="state.selected" class="manual-detail proto-panel" aria-labelledby="manual-detail-title"><div class="manual-actions"><h2 id="manual-detail-title" ref="detailHeading" tabindex="-1">登记记录详情</h2><el-button v-if="can('manual_recovery:read')" :disabled="state.busy" @click="recovery?.prepare(state.selected)">人工恢复确认</el-button><el-button :disabled="state.busy" @click="refreshDetail">刷新详情</el-button><el-button :disabled="state.busy" @click="closeDetails">收起详情</el-button></div><p class="manual-source">人工登记 · 原始快照归档</p><h3 class="manual-original">{{ state.selected.title }}</h3><dl><dt>严重级别</dt><dd>{{ state.selected.severity_label }}</dd><dt>登记时资产快照</dt><dd>{{ state.selected.asset_name_at_registration }} · v{{ state.selected.observed_asset_version }}</dd><dt>资产ID</dt><dd>{{ state.selected.asset_id }}</dd><dt>登记时间</dt><dd>{{ displayTime(state.selected.registered_at) }}</dd><dt>登记人ID</dt><dd>{{ state.selected.registered_by }}</dd><dt>登记记录ID</dt><dd>{{ state.selected.id }}</dd><dt>实际发生时间</dt><dd>待确认</dd><dt>监控状态</dt><dd>待确认</dd><dt>正式处置状态</dt><dd>暂未关联</dd><dt>工单优先级</dt><dd>暂未关联</dd><dt>恢复状态</dt><dd>登记时未确认恢复（点击上方“人工恢复确认”查看或登记恢复记录）</dd></dl></section>
      </div>
    </template>
  </section>
</template>
<style scoped>
.manual-visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
.manual-incidents-page .el-button{min-height:44px;height:auto;white-space:normal}
.manual-incidents-page :focus-visible{outline:3px solid var(--el-color-primary);outline-offset:3px}
.manual-incidents-page header p{color:var(--el-text-color-secondary)}
.manual-workspace{display:grid;grid-template-columns:minmax(0,1fr);gap:12px;align-items:start;margin-top:12px}
.manual-workspace.has-selection{grid-template-columns:minmax(0,1.3fr) minmax(340px,1fr)}
.manual-registers{min-width:0}
.manual-list{display:table;width:100%;border-collapse:collapse}
.manual-list .selected{background:#f0f6ff}
.manual-list .manual-original{font-weight:500;line-height:1.6}
.manual-row{display:block;min-height:44px;padding:8px 0;border:0;border-radius:0;background:none;color:#2d79f6;text-align:left;cursor:pointer;overflow-wrap:anywhere}
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
