<script setup lang="ts">
import { computed, onUnmounted, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createContactPreviewWorkspace, type ContactPreviewState } from '../utils/contactImportPreviewWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import CustomerContactFacts from '../components/CustomerContactFacts.vue'
const state = shallowRef<ContactPreviewState>({ text: '', result: null, busy: false, error: null })
const workspace = createContactPreviewWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['padmin', 'tadmin'].includes(session.value.user.role) && can('contacts:read') && can('contacts:write') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.user.role, permitted.value]))
watch(contextKey, () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true, canWrite: true } : null) }, { immediate: true, flush: 'sync' })
async function discard() { if (!state.value.text) return true; const turn = contextKey.value; try { await ElMessageBox.confirm('此页输入和预览只在当前页面内存中，离开后将清除；没有保存联系人或导入作业。确认放弃输入？', '保留预览输入', { confirmButtonText: '放弃并继续', cancelButtonText: '保留输入', type: 'warning' }); return turn === contextKey.value } catch { return false } }
async function clearInput() { if (await discard()) workspace.change('') }
onBeforeRouteLeave(discard)
const unregister = contextTransitionGuards.register(discard)
function beforeUnload(event: BeforeUnloadEvent) { if (state.value.text) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
const example = JSON.stringify({ rows: [{ name: '仅格式示例，请替换为明确资料', roles: ['REPORTER'], mobile: null, email: null, wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false }] }, null, 2)
defineExpose({ change: workspace.change, preview: workspace.preview, clearInput, state })
</script>
<template>
 <section class="preview-page">
  <div class="page-heading"><div><h1>联系人导入格式预览</h1><p>只读诊断 · 逐行核对 · 不保存联系人</p></div><router-link to="/customer-contacts">返回联系人</router-link></div>
<details class="page-guidance"><summary>计算口径与说明</summary>  <el-alert type="warning" :closable="false" title="格式通过仍不可导入">当前只检查显式JSON资料，不解析CSV/XLSX文件、不查询现有联系人、不检查最后决策人、不创建导入作业或派发通知。不能把本页结果当导入许可。</el-alert>
</details>
    <el-empty v-if="!permitted" description="当前身份没有联系人预览权限" />
  <template v-else>
   <div class="workbench-layout"><section class="preview-input workbench-form">
    <h2>原始JSON资料</h2><p>根对象只含rows；1–100条是单次技术上限，不是合同配额。每条需完整提供姓名、角色、手机、邮箱、企微userid、级别偏好、静音组、启用状态这8个字段，未知显式null；enabled必须明确true或false。</p>
    <details><summary>查看明确标记的格式示例（不会自动填入或发送）</summary><pre>{{ example }}</pre><p>角色：REPORTER、DECISION_MAKER、TECHNICAL_CONTACT；偏好为P1–P4数组，null表示未知，[]表示明确空。静音组为null，或完整enabled/start/end；启用窗口要求HH:mm且两端不同，时区Asia/Shanghai。</p></details>
    <el-input type="textarea" :rows="12" :model-value="state.text" aria-label="联系人预览原始JSON" placeholder="在此粘贴明确资料；不要填入密码、token或API密钥" @update:model-value="workspace.change($event)" />
    <p class="preview-help">最多512KiB、5层、8000个JSON token；拒绝重复键和非规范安全整数（含小数、指数及负零），业务字段不使用数值类型。编辑立即清除旧结果；不会自动预览、保存或合并相同行。</p>
    <div class="preview-actions"><el-button type="primary" :disabled="state.busy || !state.text" :loading="state.busy" @click="workspace.preview()">只读检查格式</el-button><el-button :disabled="!state.text" @click="clearInput">清除输入与结果</el-button></div>
   <ApiErrorNotice :error="state.error" /></section>
   <section class="workbench-result" aria-live="polite"><h2>格式检查结果</h2><div v-if="!state.result" class="workbench-empty"><strong>{{state.busy ? '正在检查…' : '等待检查'}}</strong><p>输入资料后点击左侧检查按钮。结果不会自动保存。</p></div><div v-else class="preview-results">
    <h2>服务端格式诊断</h2><p>通过 {{ state.result.valid_rows }} 行 · 错误 {{ state.result.invalid_rows }} 行 · 完全相同行警告 {{ state.result.identical_rows }} 行</p><p class="preview-help">所有原始行号均保留。格式通过不代表渠道核验、现有记录去重或正式客户身份；相同行只警告，不自动跳过或覆盖。</p>
    <ol class="preview-rows"><li v-for="row in state.result.rows" :key="row.row_number"><h3>第{{ row.row_number }}行：{{ row.status === 'FORMAT_VALID' ? '格式通过 / 未核验' : '字段错误 / 未写入' }}</h3><p v-if="row.status === 'INVALID'">请核对8个字段是否完整、类型/角色集合/手机号邮箱/启用状态/静音起止是否合法；无效行原文不回显，请在上方原始输入按行序修正。</p><p v-if="row.identical_to_row !== null" class="duplicate-warning">字段与第{{ row.identical_to_row }}行完全相同，仍保留本行；请自行核对，不认定为同一人。</p><details v-if="row.contact"><summary>查看本行格式事实</summary><CustomerContactFacts :facts="row.contact" /></details></li></ol>
    <el-alert type="info" :closable="false" title="本页结果不保存，批量新建需独立受理">can_import=false；现有联系人、最后决策人和真实通知送达均未验证。本页没有批量写入按钮。</el-alert><p><router-link to="/contact-imports">打开批量新建与我的作业（需重新明确输入）</router-link></p>
   </div></section></div>
  </template>
 </section>
</template>
<style scoped>
.preview-page{min-width:0;line-height:1.7}.preview-input,.preview-results{margin:20px 0;padding:22px;border:1px solid #dce4ee;border-radius:12px;background:white;overflow-wrap:anywhere}.preview-help{color:#526176}.preview-actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:16px}.preview-actions .el-button{margin-left:0}.preview-rows{list-style:none;padding:0}.preview-rows>li{padding:16px 0;border-bottom:1px solid #e1e7ef}.duplicate-warning{color:#805000}pre{white-space:pre-wrap;overflow-wrap:anywhere}.preview-page summary{cursor:pointer}.preview-page details{margin:12px 0}.preview-page :deep(textarea){font-family:monospace}@media(max-width:700px){.preview-input,.preview-results{padding:14px}.preview-page .el-button{min-height:44px}.page-heading{align-items:flex-start;gap:12px;flex-direction:column}}
.preview-page .workbench-form{margin:0}.workbench-result>.preview-results{border:0;padding:0;margin:0;background:transparent}.workbench-form>p{font-size:13px}
</style>
