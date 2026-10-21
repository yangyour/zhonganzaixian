<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createContactPreviewWorkspace, type ContactPreviewState } from '../utils/contactImportPreviewWorkspace'
import { createContactImportWorkspace, emptyContactImportState, type ContactImportState } from '../utils/contactImportWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const previewState = shallowRef<ContactPreviewState>({ text: '', result: null, busy: false, error: null })
const state = shallowRef<ContactImportState>(emptyContactImportState())
const preview = createContactPreviewWorkspace(api, value => { previewState.value = value })
const workspace = createContactImportWorkspace(api, value => { state.value = value })
const acknowledge = ref(false), confirming = ref(false), tracking = ref<string | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null, trackingEpoch = 0, disposed = false
const permitted = computed(() => Boolean(session.value && ['padmin', 'tadmin'].includes(session.value.user.role) && can('contacts:read') && can('contacts:write') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value]))
const busy = computed(() => state.value.busy !== null || previewState.value.busy || confirming.value)
const locked = computed(() => busy.value || state.value.attempt !== null)
const readyToSubmit = computed(() => permitted.value && !locked.value && previewState.value.result !== null && previewState.value.result.invalid_rows === 0 && (previewState.value.result.identical_rows === 0 || acknowledge.value))
function stopTracking() { ++trackingEpoch; if (timer !== null) clearTimeout(timer); timer = null; tracking.value = null }
watch(contextKey, () => { stopTracking(); confirming.value = false; acknowledge.value = false; const s = session.value, context = s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true, canWrite: true } : null; workspace.reset(context); preview.reset(context); if (context) void workspace.list() }, { immediate: true, flush: 'sync' })
function change(text: string) { if (!locked.value && permitted.value) { acknowledge.value = false; preview.change(text) } }
function changeAck(value: boolean) { if (!locked.value && permitted.value && typeof value === 'boolean') acknowledge.value = value }
async function checkFormat() { if (!locked.value && permitted.value && previewState.value.text) return preview.preview(); return false }
async function submit() {
 if (!readyToSubmit.value) return false
 const turn = contextKey.value, input = previewState.value.text, ack = acknowledge.value, count = previewState.value.result!.valid_rows
 confirming.value = true
 try { await ElMessageBox.confirm(`将按原行序分别新建${count}条自述联系人。相同行不会合并，不更新已有联系人，也不发送通知；202只表示耐久受理。确认提交？`, '确认新建整批联系人', { confirmButtonText: '确认受理', cancelButtonText: '返回核对', type: 'warning' }); if (disposed || turn !== contextKey.value || input !== previewState.value.text || ack !== acknowledge.value) return false; return await workspace.submit(input, ack) } catch { return false } finally { if (turn === contextKey.value && !disposed) confirming.value = false }
}
async function retry() { if (!permitted.value || busy.value) return false; return workspace.retry() }
async function discard() {
 if (workspace.unresolved()) { ElMessage.warning('原请求结果未知或正在提交，请先用完全相同原请求恢复；普通刷新不能确认。强制会话失效会清空本页私有输入。'); return false }
 if (confirming.value) return false
 if (disposed) return false
 const turn = contextKey.value, input = previewState.value.text, ack = acknowledge.value
 if (!input) { await Promise.resolve(); return !disposed && turn === contextKey.value && input === previewState.value.text && ack === acknowledge.value && !workspace.unresolved() }
 confirming.value = true
 try { await ElMessageBox.confirm(state.value.receipt ? '已受理作业保留在服务器，可从“我的导入作业”回读。离开会清除本页原输入与回执显示，确认继续？' : '输入与格式预览尚未持久化，离开将清除；确认放弃？', '保留本页输入', { confirmButtonText: '清除并继续', cancelButtonText: '保留输入', type: 'warning' }); return !disposed && turn === contextKey.value && input === previewState.value.text && ack === acknowledge.value && !workspace.unresolved() } catch { return false } finally { if (turn === contextKey.value && !disposed) confirming.value = false }
}
async function beginNew() { const turn = contextKey.value, input = previewState.value.text, ack = acknowledge.value; if (disposed || !permitted.value || busy.value || !await discard() || disposed || turn !== contextKey.value || input !== previewState.value.text || ack !== acknowledge.value) return false; if (!workspace.begin()) return false; stopTracking(); acknowledge.value = false; preview.change(''); return true }
async function unlockRejected() { if (!permitted.value || busy.value || state.value.attempt?.state !== 'rejected') return false; return workspace.begin() }
async function browse(next = false) { if (!permitted.value || busy.value) return false; stopTracking(); return workspace.list(next) }
async function select(id: string) { if (!permitted.value || busy.value) return false; stopTracking(); return workspace.select(id) }
async function track(id: string) {
 if (!permitted.value || busy.value) return false
 stopTracking(); tracking.value = id; const turn = trackingEpoch, context = contextKey.value
 async function tick(): Promise<boolean> { if (disposed || turn !== trackingEpoch || context !== contextKey.value) return false; if (state.value.busy !== null || previewState.value.busy || confirming.value) { timer = setTimeout(() => { void tick() }, 2000); return false } const ok = await workspace.select(id); if (disposed || turn !== trackingEpoch || context !== contextKey.value) return false; if (!ok || !state.value.selected || ['SUCCEEDED', 'FAILED'].includes(state.value.selected.status)) { stopTracking(); return ok } timer = setTimeout(() => { void tick() }, 2000); return true }
 return tick()
}
onBeforeRouteLeave(discard)
const unregister = contextTransitionGuards.register(discard)
function beforeUnload(event: BeforeUnloadEvent) { if (previewState.value.text || workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed = true; stopTracking(); unregister(); workspace.dispose(); preview.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
const statusText = (status: string) => ({ PENDING: '待处理', RUNNING: '处理租约中（在线未知）', SUCCEEDED: '整批已新建', FAILED: '整批未执行 / 失败' }[status] ?? '未知')
const stepText = (step: string) => ({ QUEUED: '已入队', RETRY_WAIT: '依赖失败，等待重试', APPLYING: '领取后处理', APPLIED: '全部行原子完成', AUTHORIZATION_REVOKED: '受理权限已失效', PAYLOAD_INVALID: '耐久载荷核对失败', LAST_ENABLED_DECISION_MAKER: '租户启用决策人约束未满足' }[step] ?? '未知')
const example = JSON.stringify({ rows: [{ name: '格式示例，请替换为明确资料', roles: ['REPORTER'], mobile: null, email: null, wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false }] }, null, 2)
defineExpose({ change, changeAck, checkFormat, submit, retry, beginNew, unlockRejected, browse, select, track, stopTracking, hasUnresolved: workspace.unresolved, state, previewState, acknowledge, tracking })
</script>
<template>
 <section class="contact-import-page admin-prototype">
  <div class="page-heading"><div><h1>联系人批量新建与作业</h1><p>按标准 JSON 结构整批导入客户联系人档案 · 独立跟踪异步导入进度</p></div><router-link to="/customer-contacts">返回联系人管理</router-link></div>
  <el-alert type="warning" :closable="false" title="批量导入仅新增客户联系人档案，不合并既有记录、不开通系统登录权限">本页接收标准 JSON 格式数据（单批上限 1–100 行）。格式校验通过后需显式确认提交导入作业，本页没有批量写入按钮之外的自动覆盖动作。</el-alert>
  <nav class="admin-tabs" aria-label="联系人工作区"><router-link to="/customer-contacts">联系人列表</router-link><span class="active">批量新建与作业</span><router-link to="/contact-import-preview">导入格式预览</router-link></nav>
  <ol class="admin-stepbar"><li :class="{active: !previewState.result}"><strong>01 填写导入数据</strong><small>标准 8 字段 JSON</small></li><li :class="{active: previewState.result && !state.receipt}"><strong>02 格式校验与受理</strong><small>核对后提交导入作业</small></li><li :class="{active: !!state.receipt}"><strong>03 跟踪作业结果</strong><small>查询后台执行状态</small></li></ol>
  <el-empty v-if="!permitted" description="当前身份没有联系人批量新建权限" />
  <template v-else>
   <details class="admin-compose"><summary>新建导入批次 · 展开填写与格式校验</summary><section class="import-panel"><h2>1. 填写导入 JSON 数据</h2>
    <p>每行需完整包含 8 个标准字段（选填项请显式填写 <code>null</code>，<code>enabled</code> 须为布尔值）。</p>
    <details><summary>查看 JSON 格式示例</summary><pre>{{ example }}</pre><p>支持角色：REPORTER、DECISION_MAKER、TECHNICAL_CONTACT；订阅级别：P1–P4 数组（null 表示未指定，[] 表示空集合）；免打扰时段：null 或完整 enabled/start/end（Asia/Shanghai 时区）。</p></details>
    <el-input type="textarea" :rows="12" :model-value="previewState.text" :disabled="locked" aria-label="批量新建联系人原始JSON" placeholder="请粘贴符合规范的联系人 JSON 数据（请勿包含密码、Token 或密钥）" @update:model-value="change($event)" />
    <p class="help">支持单次最多 512KiB、5 层嵌套；修改文本后需重新执行格式校验。</p>
    <div class="actions"><el-button :disabled="locked || !previewState.text" :loading="previewState.busy" @click="checkFormat">校验数据格式</el-button><el-button :disabled="busy || workspace.unresolved()" @click="beginNew">重置输入</el-button></div>
    <ApiErrorNotice :error="previewState.error" />
    <div v-if="previewState.result" aria-live="polite"><p>格式校验通过 {{ previewState.result.valid_rows }} 行；字段错误 {{ previewState.result.invalid_rows }} 行；完全相同行 {{ previewState.result.identical_rows }} 行</p><p>can_import=false 表示格式预览本身不执行入库，实际导入请点击下方按钮提交作业。</p><ol><li v-for="row in previewState.result.rows" :key="row.row_number">第{{ row.row_number }}行：{{ row.status === 'INVALID' ? '字段格式有误，请修正' : '格式校验通过' }}<span v-if="row.identical_to_row !== null">；与第{{ row.identical_to_row }}行完全相同</span></li></ol></div>
    <el-checkbox :model-value="acknowledge" :disabled="locked" aria-label="确认相同行分别新建" @update:model-value="changeAck($event)">如有字段完全相同行，我确认每行分别新建，绝不自动合并或跳过</el-checkbox>
    <h2>2. 提交批量导入作业</h2><el-button type="primary" :disabled="!readyToSubmit" :loading="state.busy === 'write' || confirming" @click="submit">提交导入作业</el-button>
   </section></details>
   <ApiErrorNotice :error="state.error" />
   <section v-if="state.attempt?.state === 'uncertain'" class="import-panel uncertain" role="alert"><h2>导入请求结果待确认</h2><p>当前批次可能已被服务端受理，系统已锁定输入内容与幂等键。请先点击下方按钮恢复原请求结果，请勿重复新建批次。</p><el-button :disabled="busy" :loading="state.busy === 'write'" @click="retry">重试提交</el-button><p>若会话失效，重新登录后请先在“我的导入作业”中核对是否已生成任务。</p></section>
   <section v-if="state.attempt?.state === 'rejected'" class="import-panel"><h2>本次导入受理未通过</h2><p>已保留当前输入内容，可点击下方按钮解锁后重新核对。</p><el-button :disabled="busy" @click="unlockRejected">保留输入并解锁</el-button></section>
   <section v-if="state.receipt" class="import-panel" aria-live="polite"><h2>原始202受理回执（不是当前处理结果）</h2><p>作业 {{ state.receipt.operation_id }} · 初始状态 PENDING</p><p>该回执仅代表任务已入队受理，请点击下方按钮查询最新执行进度。</p><div class="actions"><el-button :disabled="busy" @click="select(state.receipt.operation_id)">查看当前进度</el-button><el-button :disabled="busy || tracking !== null" @click="track(state.receipt.operation_id)">自动刷新进度</el-button></div></section>
   <section class="import-panel admin-job-list"><div class="admin-card-head"><h2>我的导入作业</h2><span class="muted">当前账号 · 当前租户</span></div><p>展示当前账号在本租户下提交的批量导入作业记录。</p><div class="actions"><el-button :disabled="busy" :loading="state.busy === 'read'" @click="browse(false)">刷新列表</el-button><el-button :disabled="busy || !state.page?.has_more" @click="browse(true)">下一页</el-button><el-button v-if="tracking" @click="stopTracking">停止自动刷新</el-button></div><p v-if="tracking" role="status">每 2 秒自动刷新目标作业状态；任务进入终态或离开页面时自动停止。</p>
    <div class="admin-table-scroll" role="region" aria-label="我的导入作业列表" tabindex="0"><table class="admin-table import-jobs-table"><colgroup><col class="job-identity-col"><col class="job-status-col"><col class="job-step-col"><col class="job-count-col"><col class="job-version-col"><col class="job-action-col"></colgroup><thead><tr><th>作业 ID / 受理时间</th><th>状态</th><th>当前步骤</th><th>行数</th><th>尝试 / 版本</th><th>操作</th></tr></thead><tbody><tr v-for="job in state.page?.items" :key="job.id"><td><strong class="mono">{{ job.id }}</strong><small>{{ job.created_at }}</small></td><td>{{ statusText(job.status) }}</td><td>{{ stepText(job.step) }}</td><td>{{ job.row_count }}</td><td>{{ job.attempts }} 次 / v{{ job.version }}</td><td><div class="admin-row-actions"><el-button :disabled="busy" @click="select(job.id)">查看当前结果</el-button></div></td></tr><tr v-if="!state.page"><td colspan="6" class="job-list-state"><p v-if="state.busy === 'read'" role="status">正在加载本人当前租户的导入作业…</p><p v-else-if="state.attempt" role="status">作业列表待刷新；请先确认当前导入请求状态。</p><p v-else-if="state.error" role="alert">作业列表加载失败，请核对上方错误提示后点击“刷新列表”。</p><p v-else role="status">作业列表尚未加载，请点击“刷新列表”。</p></td></tr><tr v-else-if="!state.page.items.length"><td colspan="6" class="job-list-state"><p>当前页暂无导入作业记录</p><small>可展开上方“新建导入批次”提交批量导入任务。</small></td></tr></tbody></table></div><div class="admin-footer"><span>{{ state.paged ? '后续页' : '第一页' }} · 本页 {{ state.page?.items.length ?? '—' }} 条</span><span v-if="state.page && !state.page.has_more">已到当前查询末页</span></div>
   </section>
   <section v-if="state.selected" class="import-panel admin-editor-panel" aria-live="polite"><h2>当前作业结果</h2><p>{{ state.selected.id }}</p><h3>{{ statusText(state.selected.status) }} · {{ stepText(state.selected.step) }}</h3><p>受理 {{ state.selected.row_count }} 行 · 已完成 {{ state.selected.completed_rows === null ? '未知 / 未完成' : state.selected.completed_rows }} 行 · 尝试 {{ state.selected.attempts }} 次 · 版本 {{ state.selected.version }}</p><p>Worker在线：未知；本页不派发通知、不核验渠道、不创建账号权限。</p><p v-if="state.selected.next_attempt_at">下次调度时间：{{ state.selected.next_attempt_at }}</p><p v-if="state.selected.error" role="alert">{{ state.selected.error.code }}：{{ state.selected.error.message }}</p><div class="actions"><el-button :disabled="busy" @click="select(state.selected.id)">刷新当前作业</el-button><el-button v-if="!['SUCCEEDED', 'FAILED'].includes(state.selected.status)" :disabled="busy || tracking !== null" @click="track(state.selected.id)">自动刷新当前作业</el-button></div>
    <p v-if="state.selected.status === 'FAILED'">当前作业已终止，不支持原任务直接重跑；请排查原因并修正数据后重新提交新批次。</p><p v-if="state.selected.status === 'SUCCEEDED'">整批联系人已成功创建（初始版本 v1），后续维护请前往“联系人列表”。</p><ol><li v-for="result in state.selected.results" :key="result.row_number">第{{ result.row_number }}行 → 联系人 {{ result.contact_id }} / 初始版本 v{{ result.contact_version }}</li></ol>
   </section>
  </template>
 </section>
</template>
<style scoped>
.contact-import-page{max-width:1100px;line-height:1.7}
.import-panel{margin:16px 0;padding:22px;border:1px solid #dce4ee;border-radius:5px;background:#fff;overflow-wrap:anywhere}
.admin-prototype .admin-compose>.import-panel{margin:0}
.admin-prototype .admin-job-list{padding:0;border-radius:5px}
.admin-prototype .admin-job-list>p,.admin-prototype .admin-job-list>.actions{padding-left:18px;padding-right:18px}
.admin-prototype .import-jobs-table{table-layout:fixed;min-width:900px}
.job-identity-col{width:29%}.job-status-col{width:19%}.job-step-col{width:18%}.job-count-col{width:7%}.job-version-col{width:12%}.job-action-col{width:15%}
.admin-prototype .import-jobs-table td{overflow-wrap:anywhere}
.admin-prototype .job-list-state{text-align:center;height:150px;color:#8290a5}
.job-list-state p{margin:0}
.job-list-state small{max-width:none!important;margin-top:7px}
.uncertain{border-color:#ca922e;background:#fff9ed}
.help{color:#526176}
.actions{display:flex;flex-wrap:wrap;gap:12px;margin:14px 0}
.actions .el-button{margin-left:0}
.jobs{list-style:none;padding:0}
.jobs>li{padding:14px 0;border-bottom:1px solid #e1e7ef}
pre{white-space:pre-wrap;overflow-wrap:anywhere}
.contact-import-page summary{cursor:pointer}
.contact-import-page :deep(textarea){font-family:monospace}
.contact-import-page :deep(.el-checkbox){height:auto;white-space:normal}
.contact-import-page :deep(.el-checkbox__label){white-space:normal}
@media(max-width:700px){.import-panel{padding:14px}.contact-import-page .el-button{min-height:44px}.page-heading{align-items:flex-start;gap:12px;flex-direction:column}}
</style>
