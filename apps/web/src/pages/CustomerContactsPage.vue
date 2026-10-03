<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createContactWorkspace, emptyContactState, type ContactState, type CustomerContact, type ContactRole, type ContactPriority, type ContactView } from '../utils/customerContactWorkspace'
import { contactActionLabels, contactRoleLabels } from '../utils/customerContactPresentation'
import { displayTime } from '../utils/display'
import CustomerContactFacts from '../components/CustomerContactFacts.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const query = ref(''), view = ref<ContactView>('CURRENT'), validation = ref('')
const mode = ref<'CREATE' | 'UPDATE' | 'RESTORE' | null>(null), baseline = shallowRef<CustomerContact | null>(null), initial = ref('')
const form = reactive({ name: '', roles: [] as ContactRole[], mobile: '', email: '', wecom: '', prioritiesKnown: false, priorities: [] as ContactPriority[], quietMode: 'not_provided', quietStart: '', quietEnd: '', enabled: null as boolean | null })
const state = shallowRef<ContactState>(emptyContactState()), workspace = createContactWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['padmin', 'tadmin'].includes(session.value.user.role) && can('contacts:read') && !switchingTenant.value))
const writable = computed(() => permitted.value && can('contacts:write'))
const locked = computed(() => !writable.value || Boolean(state.value.busy) || Boolean(state.value.attempt))
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.user.role, permitted.value, writable.value]))
const nullable = (v: string) => v === '' ? null : v
function quiet() { switch (form.quietMode) { case 'not_provided': return null; case 'unknown': return { enabled: null, start: null, end: null }; case 'disabled': return { enabled: false, start: null, end: null }; case 'enabled': return { enabled: true, start: nullable(form.quietStart), end: nullable(form.quietEnd) }; default: return { invalid: true } } }
function payload() { return { expected_version: baseline.value?.version ?? 0, name: form.name, roles: [...form.roles], mobile: nullable(form.mobile), email: nullable(form.email), wecom_user_id: nullable(form.wecom), preferred_priorities: form.prioritiesKnown ? [...form.priorities] : null, quiet_window: quiet(), enabled: form.enabled } }
const unsaved = computed(() => mode.value !== null && state.value.attempt?.state !== 'accepted' && (state.value.attempt?.state === 'rejected' || JSON.stringify(payload()) !== initial.value))
const nextList = computed(() => state.value.page?.has_more && query.value === state.value.query && view.value === state.value.view && !state.value.busy)
function clearEditor() { mode.value = null; baseline.value = null; initial.value = ''; validation.value = ''; Object.assign(form, { name: '', roles: [], mobile: '', email: '', wecom: '', prioritiesKnown: false, priorities: [], quietMode: 'not_provided', quietStart: '', quietEnd: '', enabled: null }) }
watch(contextKey, () => { const s = session.value; workspace.reset(s && permitted.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, role: s.user.role, canRead: true, canWrite: writable.value } : null); clearEditor(); query.value = ''; view.value = 'CURRENT'; if (permitted.value) void workspace.list('', 'CURRENT') }, { immediate: true, flush: 'sync' })
async function discard() { if (!unsaved.value) return true; try { await ElMessageBox.confirm('将替换尚未成功保存的联系人输入，不会自动合并到较新版本。确认继续？', '保留未保存输入', { confirmButtonText: '确认替换', cancelButtonText: '保留输入', type: 'warning' }); return true } catch { return false } }
function canPrepare() { if (!writable.value || state.value.busy) return false; if (workspace.unresolved()) { ElMessage.warning('原请求结果尚未确认，不能替换或新建；请使用完全相同原请求重试'); return false } return true }
async function prepareCreate() { if (!canPrepare()) return false; const turn = contextKey.value; if (!await discard() || turn !== contextKey.value || !writable.value || !workspace.begin()) return false; clearEditor(); mode.value = 'CREATE'; initial.value = JSON.stringify(payload()); return true }
async function prepareFrom(row: CustomerContact) { if (!canPrepare()) return false; const turn = contextKey.value, target = structuredClone(row); if (!await discard() || turn !== contextKey.value || !writable.value || !workspace.begin()) return false; clearEditor(); baseline.value = target; mode.value = target.archived ? 'RESTORE' : 'UPDATE'; Object.assign(form, { name: target.name, roles: [...target.roles], mobile: target.mobile ?? '', email: target.email ?? '', wecom: target.wecom_user_id ?? '', prioritiesKnown: target.preferred_priorities !== null, priorities: [...(target.preferred_priorities ?? [])], quietMode: target.quiet_window === null ? 'not_provided' : target.quiet_window.enabled === null ? 'unknown' : target.quiet_window.enabled ? 'enabled' : 'disabled', quietStart: target.quiet_window?.start ?? '', quietEnd: target.quiet_window?.end ?? '', enabled: target.enabled }); initial.value = JSON.stringify(payload()); return true }
async function browse(next = false) { validation.value = ''; return workspace.list(query.value, view.value, next) }
async function select(id: string) { validation.value = ''; return workspace.select(id) }
async function history(id: string, next = false) { validation.value = ''; return workspace.history(id, next) }
async function save() { if (locked.value || !mode.value) return false; validation.value = ''; const turn = contextKey.value; const success = await workspace.submit(mode.value, payload(), baseline.value); if (turn !== contextKey.value) return false; if (success) ElMessage.success('原请求收据已确认；联系资料未核验，未发送通知'); else if (!state.value.attempt) validation.value = '请检查姓名、至少一个联系角色、明确启用状态、国际手机号/邮箱及静音起止；未发送新请求'; return success }
async function retry() { const turn = contextKey.value; const success = await workspace.retry(); if (success && turn === contextKey.value) ElMessage.success('原请求收据已确认，没有另建联系人或重复修订') }
async function archiveContact(row: CustomerContact) { if (!canPrepare() || row.archived) return false; const turn = contextKey.value, target = structuredClone(row); if (!await discard() || turn !== contextKey.value) return false; try { await ElMessageBox.confirm('将归档并停用“' + target.name + '”，保留历史且可恢复。最后一名启用决策人不能归档。', '确认归档联系人', { confirmButtonText: '归档并停用', cancelButtonText: '取消', type: 'warning' }) } catch { return false } if (turn !== contextKey.value || !writable.value || !workspace.begin()) return false; clearEditor(); const success = await workspace.submit('ARCHIVE', { expected_version: target.version }, target); if (turn !== contextKey.value) return false; if (success) ElMessage.success('已归档停用，历史保留；没有永久删除或发送通知'); return success }
function unlockRejected() { if (state.value.attempt?.state !== 'rejected' || !workspace.begin()) return; validation.value = '已保留输入并解锁；版本不会自动更新。版本冲突时请只读查看当前事实，再明确重新准备，不能自动覆盖。' }
async function closeEditor() { if (workspace.unresolved()) { ElMessage.warning('原请求结果未知，不能关闭并丢失原请求'); return false } const turn = contextKey.value; if (!await discard() || turn !== contextKey.value || state.value.busy) return false; if (state.value.attempt?.state === 'rejected' && !workspace.begin()) return false; clearEditor(); return true }
async function mayLeave() { if (workspace.unresolved()) { ElMessage.warning('先恢复原请求结果；只读列表/详情不能替代原收据确认'); return false } const turn = contextKey.value; return await discard() && turn === contextKey.value }
onBeforeRouteLeave(mayLeave)
const unregister = contextTransitionGuards.register(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || unsaved.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { unregister(); workspace.dispose(); clearEditor(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ prepareCreate, prepareFrom, browse, hasUnresolved: workspace.unresolved })
</script>
<template>
 <section class="contacts-page">
  <div class="page-heading"><div><span class="eyebrow">CUSTOMER CONTACTS</span><h1>客户联系人</h1><p>当前授权租户 · 自述联系资料 · 历史可追溯</p></div></div>
  <el-alert type="warning" :closable="false" title="联系角色不是账号权限，启用不等于可送达">资料和渠道尚未核验；维护联系人不会创建账号或授予权限。通知路由仍待确认，本页不发送通知。每个租户必须保留至少一名启用决策人。</el-alert>
  <el-empty v-if="!permitted" description="当前身份没有联系人读取权限" />
  <template v-else>
   <section v-if="state.attempt" class="request-status" aria-live="polite">
    <h2>固定原请求：{{ contactActionLabels[state.attempt.action] }}</h2>
    <p v-if="state.attempt.state === 'uncertain'">结果尚未确认。即使本次重试被拒绝，也不能证明此前未写入；不能新建或替换请求。</p>
    <p v-else-if="state.attempt.state === 'rejected'">本次首次请求已明确拒绝；输入仍保留。只读刷新不自动改变版本，也不自动覆盖。</p>
    <p v-else>原请求收据已确认；它不等于对象当前最新状态，请单独只读回读。</p>
    <div class="contact-actions"><el-button v-if="state.attempt.state === 'uncertain'" type="primary" :disabled="Boolean(state.busy) || !writable" @click="retry">完全相同原请求重试</el-button><el-button v-if="state.attempt.state === 'rejected' && mode" :disabled="Boolean(state.busy) || !writable" @click="unlockRejected">保留输入并解锁（不自动改版本）</el-button></div>
    <details><summary>查看固定原请求</summary><p>{{ state.attempt.method }} {{ state.attempt.path }}</p><p class="contact-long">幂等键：{{ state.attempt.key }}</p><pre>{{ state.attempt.payload }}</pre></details>
   </section>
   <ApiErrorNotice :error="state.error" />
   <el-alert v-if="validation" type="warning" :closable="false" :title="validation" />
   <section class="contact-section">
    <h2>只读查询</h2><div class="contact-query">
     <el-input v-model="query" aria-label="联系人姓名查询" placeholder="姓名字面查询，最多200字符" :disabled="state.busy === 'write'" @keyup.enter="browse(false)" />
     <el-select v-model="view" aria-label="联系人记录视图" :disabled="state.busy === 'write'"><el-option value="CURRENT" label="未归档（含停用）" /><el-option value="ARCHIVED" label="已归档" /><el-option value="ALL" label="全部记录" /></el-select>
     <el-button :disabled="Boolean(state.busy)" @click="browse(false)">只读刷新第一页</el-button><el-button :disabled="!nextList" @click="browse(true)">下一页</el-button>
     <el-button v-if="writable" type="primary" :disabled="Boolean(state.busy) || workspace.unresolved()" @click="prepareCreate">准备新建联系人</el-button>
    </div>
    <p class="contact-help">本次查询筛选：{{ state.query || '全部姓名' }} / {{ state.view }}。每页15条，每次查询是当前快照；刷新不会确认原写入或自动提交。</p>
    <p v-if="state.busy === 'read'" role="status">正在只读查询…</p>
    <div v-if="state.page" class="table-scroll"><table class="contact-table"><thead><tr><th>姓名 / 版本</th><th>联系角色</th><th>状态</th><th>联系资料</th><th>操作</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.id"><td><span class="verbatim">{{ row.name }}</span><small>v{{ row.version }} · {{ displayTime(row.updated_at) }}</small></td><td>{{ row.roles.map(r => contactRoleLabels[r]).join('、') }}</td><td>{{ row.archived ? '已归档' : row.enabled ? '启用 / 未核验渠道' : '已停用' }}</td><td>{{ row.mobile ?? '手机号未提供' }}<br>{{ row.email ?? '邮箱未提供' }}</td><td><div class="contact-actions"><el-button :disabled="Boolean(state.busy)" data-action="select" :data-contact-id="row.id" @click="select(row.id)">查看</el-button><el-button v-if="writable" :disabled="Boolean(state.busy) || workspace.unresolved()" @click="prepareFrom(row)">{{ row.archived ? '准备恢复' : '准备修改' }}</el-button><el-button v-if="writable && !row.archived" :disabled="Boolean(state.busy) || workspace.unresolved()" @click="archiveContact(row)">归档停用</el-button></div></td></tr></tbody></table><el-empty v-if="state.page.items.length === 0" description="当前筛选没有记录" /></div>
   </section>
   <section v-if="mode" class="contact-section">
    <h2>{{ mode === 'CREATE' ? '准备新建联系人' : mode === 'RESTORE' ? '准备恢复（不会自动启用）' : '准备修改联系人' }}</h2><p v-if="baseline" class="contact-help contact-long">固定编辑基线：{{ baseline.id }} / v{{ baseline.version }}。只读刷新不会更换该版本或合并输入。</p>
    <el-form label-position="top" @submit.prevent="save">
     <div class="contact-fields">
      <el-form-item label="姓名（必填，最多200字符）"><el-input v-model="form.name" aria-label="联系人姓名" :disabled="locked" placeholder="明确姓名，不填密码或token" /></el-form-item>
      <el-form-item label="联系角色（至少一项，不授予账号权限）"><el-select v-model="form.roles" multiple aria-label="联系人角色" :disabled="locked"><el-option v-for="(label, role) in contactRoleLabels" :key="role" :value="role" :label="label" /></el-select></el-form-item>
      <el-form-item label="国际手机号"><el-input v-model="form.mobile" aria-label="联系人国际手机号" :disabled="locked" placeholder="明确国际区号，未知留空；不自动补区号" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" aria-label="联系人邮箱" :disabled="locked" placeholder="未知留空；地址格式通过不代表送达" /></el-form-item>
      <el-form-item label="企业微信 userid"><el-input v-model="form.wecom" aria-label="联系人企业微信userid" :disabled="locked" placeholder="自述标识，未知留空；不建立身份绑定" /></el-form-item>
      <el-form-item label="启用状态（必须明确选择）"><el-select v-model="form.enabled" aria-label="联系人启用状态" :disabled="locked" placeholder="请选择，不自动启用"><el-option :value="true" label="启用（渠道仍未核验）" /><el-option :value="false" label="停用" /></el-select></el-form-item>
     </div>
     <h3>订阅级别偏好</h3><el-checkbox v-model="form.prioritiesKnown" aria-label="明确联系人级别偏好" :disabled="locked">明确提供级别偏好；未勾选表示未知</el-checkbox><el-select v-if="form.prioritiesKnown" v-model="form.priorities" multiple aria-label="联系人订阅级别" :disabled="locked" placeholder="空选择表示明确未选级别"><el-option v-for="p in ['P1','P2','P3','P4']" :key="p" :value="p" :label="p" /></el-select>
     <h3>静音偏好（Asia/Shanghai）</h3><el-select v-model="form.quietMode" aria-label="联系人静音方式" :disabled="locked"><el-option value="not_provided" label="未提供静音组" /><el-option value="unknown" label="已声明，方式未知" /><el-option value="disabled" label="明确关闭静音" /><el-option value="enabled" label="明确静音窗口" /></el-select>
     <div v-if="form.quietMode === 'enabled'" class="contact-fields"><el-form-item label="开始 HH:mm"><el-input v-model="form.quietStart" aria-label="联系人静音开始" :disabled="locked" placeholder="HH:mm" /></el-form-item><el-form-item label="结束 HH:mm"><el-input v-model="form.quietEnd" aria-label="联系人静音结束" :disabled="locked" placeholder="HH:mm" /></el-form-item></div><p class="contact-help">窗口两端必须明确且不同，支持跨午夜；不默认全天，不选择P1/静音/决策人必达的优先规则。</p>
     <div class="contact-actions"><el-button type="primary" native-type="submit" :disabled="locked" :loading="state.busy === 'write'">提交{{ contactActionLabels[mode] }}</el-button><el-button native-type="button" :disabled="state.busy === 'write' || workspace.unresolved()" @click="closeEditor">关闭编辑</el-button></div>
    </el-form>
   </section>
   <section v-if="state.receipt" class="contact-section receipt"><h2>原请求收据（不是当前最新事实）</h2><p>{{ contactActionLabels[state.receipt.action] }} · v{{ state.receipt.version }} · {{ displayTime(state.receipt.recorded_at) }}</p><p class="contact-long">{{ state.receipt.id }}</p><CustomerContactFacts :facts="state.receipt" /><el-button :disabled="Boolean(state.busy)" @click="select(state.receipt.id)">只读回读当前记录</el-button></section>
   <section v-if="state.selected" class="contact-section"><h2>当前只读事实</h2><p class="contact-help">以下是最近一次只读查询快照，不是实时订阅；其他会话修改后请刷新。时间为记录更新时间。</p><p>{{ state.selected.archived ? '已归档' : '未归档' }} · v{{ state.selected.version }} · {{ displayTime(state.selected.updated_at) }}</p><p class="contact-long">{{ state.selected.id }}</p><CustomerContactFacts :facts="state.selected" /><div class="contact-actions"><el-button :disabled="Boolean(state.busy)" @click="select(state.selected.id)">只读刷新本记录</el-button><el-button :disabled="Boolean(state.busy)" @click="history(state.selected.id)">只读查看历史</el-button><el-button v-if="writable" :disabled="Boolean(state.busy) || workspace.unresolved()" @click="prepareFrom(state.selected)">{{ state.selected.archived ? '按这些事实准备恢复' : '按这些事实重新准备修改' }}</el-button><el-button v-if="writable && !state.selected.archived" :disabled="Boolean(state.busy) || workspace.unresolved()" @click="archiveContact(state.selected)">归档停用</el-button></div></section>
   <section v-if="state.history" class="contact-section"><h2>不可变修订历史</h2><p class="contact-long">联系人 {{ state.history.contact_id }}</p><details v-for="revision in state.history.items" :key="revision.version"><summary>v{{ revision.version }} · {{ contactActionLabels[revision.action] }} · {{ displayTime(revision.recorded_at) }}</summary><CustomerContactFacts :facts="revision" /></details><el-button :disabled="Boolean(state.busy) || !state.history.has_more" @click="history(state.history.contact_id,true)">下一页历史</el-button></section>
   <p class="contact-help">没有批量导入或通知派发入口。主动离开/切租户/退出会保护未保存输入和未知请求；强制会话失效仍会清空受保护数据。</p>
  </template>
 </section>
</template>
<style scoped>
.contacts-page{max-width:1300px;line-height:1.65}.contact-section,.request-status{padding:20px;margin:20px 0;border:1px solid #dce4ee;border-radius:12px;background:white;overflow-wrap:anywhere}.request-status{border-color:#e6bb63;background:#fffaf0}.receipt{border-color:#a9c7eb}.contact-help{color:#526176}.contact-query,.contact-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.contact-query>.el-input{max-width:340px}.contact-query>.el-select{max-width:240px}.contact-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 20px}.contact-fields .el-form-item{min-width:0}.contact-fields .el-select{width:100%}.table-scroll{overflow-x:auto}.contact-table{border-collapse:collapse;width:100%;min-width:650px}.contact-table th,.contact-table td{text-align:left;vertical-align:top;padding:12px;border-bottom:1px solid #dce4ee;max-width:320px;overflow-wrap:anywhere}.contact-table small{display:block;color:#526176}.contact-long,pre{overflow-wrap:anywhere;white-space:pre-wrap}.verbatim{white-space:pre-wrap}.contacts-page details{margin:12px 0}.contacts-page summary{cursor:pointer}.contact-actions .el-button{margin-left:0}@media(max-width:700px){.contact-fields{grid-template-columns:1fr}.contact-section,.request-status{padding:14px}.contact-query>.el-input,.contact-query>.el-select{max-width:none;width:100%}.contacts-page .el-button{min-height:44px}.contacts-page :deep(.el-input__wrapper),.contacts-page :deep(.el-select__wrapper){min-height:44px}}
</style>
