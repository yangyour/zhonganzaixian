<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createContactPreviewWorkspace, type ContactPreviewState } from '../utils/contactImportPreviewWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import CustomerContactFacts from '../components/CustomerContactFacts.vue'
let disposed = false
const previewScope = ref(0)
const state = shallowRef<ContactPreviewState>({ text: '', result: null, busy: false, error: null })
const workspace = createContactPreviewWorkspace(api, value => { state.value = value })
watch(() => state.value.text, () => { previewScope.value++ }, {flush:'sync'})
const permitted = computed(() => Boolean(session.value && ['padmin', 'tadmin'].includes(session.value.user.role) && can('contacts:read') && can('contacts:write') && !switchingTenant.value))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value]))
watch(contextKey, () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true, canWrite: true } : null) }, { immediate: true, flush: 'sync' })
async function discard() { const turn = contextKey.value, input = state.value.text; if (input) { try { await ElMessageBox.confirm('此页输入和预览只在当前页面内存中，离开后将清除；没有保存联系人或导入作业。确认放弃输入？', '保留预览输入', { confirmButtonText: '放弃并继续', cancelButtonText: '保留输入', type: 'warning' }) } catch { return false } } else await Promise.resolve(); return !disposed && turn === contextKey.value && input === state.value.text }
async function clearInput() { const turn = contextKey.value, input = state.value.text; if (!await discard() || disposed || turn !== contextKey.value || input !== state.value.text) return false; workspace.change(''); return true }
onBeforeRouteLeave(discard)
const unregister = contextTransitionGuards.register(discard)
function beforeUnload(event: BeforeUnloadEvent) { if (state.value.text) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed = true; unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
const example = JSON.stringify({ rows: [{ name: '仅格式示例，请替换为明确资料', roles: ['REPORTER'], mobile: null, email: null, wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false }] }, null, 2)
defineExpose({ change: workspace.change, preview: workspace.preview, clearInput, state })
</script>
<template>
 <section class="preview-page admin-prototype">
  <div class="compact-page-toolbar page-heading"><div><h1 class="page-title-sr">联系人导入格式预览</h1></div></div>
  
  <nav class="admin-tabs" aria-label="联系人工作区"><router-link to="/customer-contacts">联系人列表</router-link><router-link to="/contact-imports">批量新建与作业</router-link><span class="active">导入格式预览</span></nav>
  <el-empty v-if="!permitted" description="当前身份没有联系人预览权限" />
  <template v-else>
   <section class="preview-input admin-card">
    <div class="admin-card-head"><h2>待校验 JSON 数据</h2><span class="muted">1–100 行 · ≤512KiB · 仅诊断不保存</span></div><div class="admin-card-body">
    <details><summary>JSON 格式示例</summary><p>根对象含 <code>rows</code> 数组，每行 8 个字段（姓名、角色、手机、邮箱、企微 UserID、级别偏好、静音配置、启用状态），选填项填 <code>null</code>，最多 5 层嵌套。</p><pre>{{ example }}</pre><p>支持角色：REPORTER、DECISION_MAKER、TECHNICAL_CONTACT；级别偏好：P1–P4 数组（null 表示未指定，[] 表示空集合）；免打扰配置：null 或完整 enabled/start/end（Asia/Shanghai 时区）。</p></details>
    <el-input type="textarea" :rows="14" :model-value="state.text" aria-label="联系人预览原始JSON" placeholder="请粘贴待校验的联系人 JSON 数据（请勿填入密码、Token 或密钥）" @update:model-value="workspace.change($event)" />
    <div class="preview-actions"><el-button type="primary" :disabled="state.busy || !state.text" :loading="state.busy" @click="workspace.preview()">校验数据格式</el-button><el-button :disabled="!state.text" @click="clearInput">清空内容</el-button></div>
   </div></section>
   <ApiErrorNotice :error="state.error" /><ReadRecoveryActions :busy="state.busy?'read':null" :can-retry="workspace.canRetryRead()" :scope-key="contextKey+':'+previewScope" :cancel="workspace.cancelRead" :retry="workspace.retryRead" label="格式预览" />
   <section v-if="state.result" class="preview-results" aria-live="polite">
    <h2>服务端格式诊断结果</h2><div class="compact-result-summary"><div class="admin-stat"><span>格式通过</span><strong>{{ state.result.valid_rows }}</strong><small>字段符合规范</small></div><div class="admin-stat"><span>字段错误</span><strong>{{ state.result.invalid_rows }}</strong><small>请按行号修正</small></div><div class="admin-stat"><span>重复行提示</span><strong>{{ state.result.identical_rows }}</strong><small>字段完全相同</small></div><div class="admin-stat"><span>入库状态</span><strong>仅预览</strong><small>未执行数据写入</small></div></div>
    <ol class="preview-rows"><li v-for="row in state.result.rows" :key="row.row_number"><h3>第{{ row.row_number }}行：{{ row.status === 'FORMAT_VALID' ? '格式通过' : '字段格式错误' }}</h3><p v-if="row.status === 'INVALID'">请核对字段是否完整、格式是否合法。</p><p v-if="row.identical_to_row !== null" class="duplicate-warning">该行字段与第{{ row.identical_to_row }}行完全相同，请核对是否需要保留。</p><details v-if="row.contact"><summary>查看本行解析预览</summary><CustomerContactFacts :facts="row.contact" /></details></li></ol>
    <p><router-link to="/contact-imports">前往批量新建与作业页面</router-link></p>
   </section>
  </template>
 </section>
</template>
<style scoped>
.preview-page{max-width:1100px;line-height:1.7}
.preview-input,.preview-results{margin:16px 0;padding:22px;border:1px solid #dce4ee;border-radius:5px;background:#fff;overflow-wrap:anywhere}
.admin-prototype .preview-input{padding:0}
.preview-help{color:#526176}
.preview-actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:16px}
.preview-actions .el-button{margin-left:0}
.preview-rows{list-style:none;padding:0}
.preview-rows>li{display:block;border:1px solid #e4eaf2;padding:14px 16px;margin-bottom:10px;border-radius:4px}
.preview-rows h3{margin-bottom:6px}
.duplicate-warning{color:#805000}
pre{white-space:pre-wrap;overflow-wrap:anywhere}
.preview-page summary{cursor:pointer}
.preview-page details{margin:12px 0}
.preview-page :deep(textarea){font-family:monospace}
@media(max-width:700px){.preview-input,.preview-results{padding:14px}.preview-page .el-button{min-height:44px}.page-heading{align-items:flex-start;gap:12px;flex-direction:column}}
</style>
