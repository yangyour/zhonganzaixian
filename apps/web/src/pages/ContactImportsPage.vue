<script setup lang="ts">
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
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.user.role, permitted.value]))
const busy = computed(() => state.value.busy !== null || previewState.value.busy || confirming.value)
const locked = computed(() => busy.value || state.value.attempt !== null)
const readyToSubmit = computed(() => permitted.value && !locked.value && previewState.value.result !== null && previewState.value.result.invalid_rows === 0 && (previewState.value.result.identical_rows === 0 || acknowledge.value))
function stopTracking() { ++trackingEpoch; if (timer !== null) clearTimeout(timer); timer = null; tracking.value = null }
watch(contextKey, () => { stopTracking(); confirming.value = false; acknowledge.value = false; const s = session.value, context = s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true, canWrite: true } : null; workspace.reset(context); preview.reset(context) }, { immediate: true, flush: 'sync' })
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
 if (!previewState.value.text) return true
 const turn = contextKey.value
 confirming.value = true
 try { await ElMessageBox.confirm(state.value.receipt ? '已受理作业保留在服务器，可从“我的导入作业”回读。离开会清除本页原输入与回执显示，确认继续？' : '输入与格式预览尚未持久化，离开将清除；确认放弃？', '保留本页输入', { confirmButtonText: '清除并继续', cancelButtonText: '保留输入', type: 'warning' }); return turn === contextKey.value && !workspace.unresolved() } catch { return false } finally { if (turn === contextKey.value && !disposed) confirming.value = false }
}
async function beginNew() { const turn = contextKey.value; if (!permitted.value || busy.value || !await discard() || turn !== contextKey.value) return false; if (!workspace.begin()) return false; stopTracking(); acknowledge.value = false; preview.change(''); return true }
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
 <section class="contact-import-page">
  <div class="page-heading"><div><span class="eyebrow">CREATE-ONLY CONTACT IMPORT</span><h1>联系人批量新建与作业</h1><p>明确资料 · 整批受理 · 独立回读结果</p></div><router-link to="/customer-contacts">返回联系人管理</router-link></div>
  <el-alert type="warning" :closable="false" title="只新建自述联系人，不合并、不授予身份、不派发通知">本页接收完整JSON，不提供CSV/XLSX文件解析。格式预览不是导入许可；每次受理和后台执行都核验当前授权。单批1–100行为技术上限，非合同配额。Worker在线状态未知，受理不保证即时完成。</el-alert>
  <el-empty v-if="!permitted" description="当前身份没有联系人批量新建权限" />
  <template v-else>
   <section class="import-panel"><h2>1. 核对明确输入</h2>
    <p>每行完整8字段；未知填写null，enabled必须明确true或false。角色仅是联系人标签，不是账号权限；优先级偏好与静音资料不等于已生效通知规则。</p>
    <details><summary>查看示例（不自动填入或发送）</summary><pre>{{ example }}</pre><p>角色：REPORTER、DECISION_MAKER、TECHNICAL_CONTACT。preferred_priorities为P1–P4数组，null未知、[]明确为空。quiet_window为null或完整enabled/start/end；启用窗口用不同HH:mm，可跨午夜，时区Asia/Shanghai。</p></details>
    <el-input type="textarea" :rows="12" :model-value="previewState.text" :disabled="locked" aria-label="批量新建联系人原始JSON" placeholder="粘贴明确资料；不要包含密码、token或API密钥" @update:model-value="change($event)" />
    <p class="help">512KiB / 5层 / 8000 JSON token技术边界；拒绝重复键。编辑立即清除旧预览和重复行确认。</p>
    <div class="actions"><el-button :disabled="locked || !previewState.text" :loading="previewState.busy" @click="checkFormat">只读检查格式</el-button><el-button :disabled="busy || workspace.unresolved()" @click="beginNew">清除并准备新批次</el-button></div>
    <ApiErrorNotice :error="previewState.error" />
    <div v-if="previewState.result" aria-live="polite"><p>服务端格式通过 {{ previewState.result.valid_rows }} 行；无效 {{ previewState.result.invalid_rows }} 行；完全相同行 {{ previewState.result.identical_rows }} 行</p><p>can_import=false仅表示预览不授予写入许可，实际写入须下面的独立受理动作。</p><ol><li v-for="row in previewState.result.rows" :key="row.row_number">第{{ row.row_number }}行：{{ row.status === 'INVALID' ? '字段错误，请修正原文' : '格式通过 / 未核验' }}<span v-if="row.identical_to_row !== null">；与第{{ row.identical_to_row }}行完全相同</span></li></ol></div>
    <el-checkbox :model-value="acknowledge" :disabled="locked" aria-label="确认相同行分别新建" @update:model-value="changeAck($event)">如有字段完全相同行，我确认每行分别新建，绝不自动合并或跳过</el-checkbox>
    <h2>2. 显式受理整批</h2><el-button type="primary" :disabled="!readyToSubmit" :loading="state.busy === 'write' || confirming" @click="submit">确认并受理新建作业</el-button>
   </section>
   <ApiErrorNotice :error="state.error" />
   <section v-if="state.attempt?.state === 'uncertain'" class="import-panel uncertain" role="alert"><h2>原请求结果未知</h2><p>请求可能已经受理。输入与幂等键已在当前页面锁定；不要重新建批次。普通GET刷新不确认原POST，403重试也不表示之前未执行。</p><el-button :disabled="busy" :loading="state.busy === 'write'" @click="retry">完全相同原请求恢复</el-button><p>强制会话失效会清空本页私有输入；恢复身份后先查“我的导入作业”，不要直接重复提交。</p></section>
   <section v-if="state.attempt?.state === 'rejected'" class="import-panel"><h2>本次受理被明确拒绝</h2><p>可保留输入解锁并重新核对，未自动改写或重试。</p><el-button :disabled="busy" @click="unlockRejected">保留输入并解锁</el-button></section>
   <section v-if="state.receipt" class="import-panel" aria-live="polite"><h2>原始202受理回执（不是当前处理结果）</h2><p>作业 {{ state.receipt.operation_id }} · 原始状态 PENDING</p><p>此回执不会随后台完成改写。请独立读取当前作业。</p><div class="actions"><el-button :disabled="busy" @click="select(state.receipt.operation_id)">回读本批当前结果</el-button><el-button :disabled="busy || tracking !== null" @click="track(state.receipt.operation_id)">自动刷新本批进度</el-button></div></section>
   <section class="import-panel"><h2>3. 我的导入作业</h2><p>仅当前创建者与当前租户；另一个管理员不能查看你的作业。手动查询，不自动创建或补写。</p><div class="actions"><el-button :disabled="busy" @click="browse(false)">刷新第一页</el-button><el-button :disabled="busy || !state.page?.has_more" @click="browse(true)">下一页</el-button><el-button v-if="tracking" @click="stopTracking">停止自动刷新</el-button></div><p v-if="tracking" role="status">每2秒只读刷新目标作业；终态、查询失败、上下文改变或离开页面时停止。不会自动重试受理。</p>
    <ol v-if="state.page" class="jobs"><li v-for="job in state.page.items" :key="job.id"><p>{{ job.id }}</p><p>{{ statusText(job.status) }} · {{ stepText(job.step) }} · {{ job.row_count }}行 · 版本{{ job.version }}</p><p>受理时间 {{ job.created_at }}；尝试{{ job.attempts }}次</p><el-button :disabled="busy" @click="select(job.id)">查看当前结果</el-button></li></ol><p v-if="state.page && state.page.items.length === 0">此页没有可见作业</p><p v-if="state.page && !state.page.has_more">已到当前查询末页</p>
   </section>
   <section v-if="state.selected" class="import-panel" aria-live="polite"><h2>当前作业结果</h2><p>{{ state.selected.id }}</p><h3>{{ statusText(state.selected.status) }} · {{ stepText(state.selected.step) }}</h3><p>受理{{ state.selected.row_count }}行 · 完成{{ state.selected.completed_rows === null ? '未知 / 未完成' : state.selected.completed_rows }}行 · 尝试{{ state.selected.attempts }}次 · 版本{{ state.selected.version }}</p><p>Worker在线：未知；本页不派发通知、不核验渠道、不创建账号权限。</p><p v-if="state.selected.next_attempt_at">下一可领取时间 {{ state.selected.next_attempt_at }}（不是完成承诺）</p><p v-if="state.selected.error" role="alert">{{ state.selected.error.code }}：{{ state.selected.error.message }}</p><div class="actions"><el-button :disabled="busy" @click="select(state.selected.id)">只读刷新当前作业</el-button><el-button v-if="!['SUCCEEDED', 'FAILED'].includes(state.selected.status)" :disabled="busy || tracking !== null" @click="track(state.selected.id)">自动刷新当前作业</el-button></div>
    <p v-if="state.selected.status === 'FAILED'">本作业已终结，不能原作业重试；排查并修正原因后才能明确受理新批次。原202回执仍然只证明当时受理。</p><p v-if="state.selected.status === 'SUCCEEDED'">整批联系人已创建，下列链接事实固定为v1；之后的修订或归档需从联系人管理另读。</p><ol><li v-for="result in state.selected.results" :key="result.row_number">第{{ result.row_number }}行 → 联系人 {{ result.contact_id }} / 原修订 v{{ result.contact_version }}</li></ol>
   </section>
  </template>
 </section>
</template>
<style scoped>
.contact-import-page{max-width:1100px;line-height:1.7}.import-panel{margin:20px 0;padding:22px;border:1px solid #dce4ee;border-radius:12px;background:white;overflow-wrap:anywhere}.uncertain{border-color:#ca922e;background:#fff9ed}.help{color:#526176}.actions{display:flex;flex-wrap:wrap;gap:12px;margin:14px 0}.actions .el-button{margin-left:0}.jobs{list-style:none;padding:0}.jobs>li{padding:14px 0;border-bottom:1px solid #e1e7ef}pre{white-space:pre-wrap;overflow-wrap:anywhere}.contact-import-page summary{cursor:pointer}.contact-import-page :deep(textarea){font-family:monospace}.contact-import-page :deep(.el-checkbox){height:auto;white-space:normal}.contact-import-page :deep(.el-checkbox__label){white-space:normal}@media(max-width:700px){.import-panel{padding:14px}.contact-import-page .el-button{min-height:44px}.page-heading{align-items:flex-start;gap:12px;flex-direction:column}}
</style>
