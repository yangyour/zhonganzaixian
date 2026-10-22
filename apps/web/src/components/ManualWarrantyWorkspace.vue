<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createWarrantyWorkspace, emptyWarrantyState, validateWarrantyInput, type WarrantyInput, type WarrantyState } from '../utils/manualWarrantyWorkspace'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
const props = defineProps<{ modelValue: boolean; asset: Asset | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
let disposed = false, focusEpoch = 0
const readNotice = ref<HTMLElement>(), readError = ref<HTMLElement>(), currentHeading = ref<HTMLElement>(), historyHeading = ref<HTMLElement>()
const state = shallowRef<WarrantyState>(emptyWarrantyState())
const workspace = createWarrantyWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.asset?.tenant_id === session.value.tenant.id && ['msp', 'padmin'].includes(session.value.user.role) && can('assets:write') && !switchingTenant.value))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || unresolved.value)
const baseline = ref<number | null>(null), validation = ref('')
const form = reactive<Record<keyof WarrantyInput, string>>({ warranty_start: '', warranty_expiry: '', warranty_supplier: '', overdue_service_date: '', note: '' })
const dates = [{ key: 'warranty_start', label: '维保起始日' }, { key: 'warranty_expiry', label: '维保到期日' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
const blockers: Record<string, string> = { MANUAL_DRAFT_REQUIRED: '仅支持维护尚未接入监控的手工台账草稿', MONITORING_STATE_NOT_PRISTINE: '该资产已存在实时监控采样状态，不支持通过草稿模式覆盖', MANUAL_METADATA_CORE_DRIFT: '核心台账字段与资料快照不一致，请先核查数据来源' }
function clearForm() { for (const key of Object.keys(form) as (keyof WarrantyInput)[]) form[key] = ''; baseline.value = null; validation.value = '' }
function input(): WarrantyInput { return { warranty_start: form.warranty_start === '' ? null : form.warranty_start, warranty_expiry: form.warranty_expiry === '' ? null : form.warranty_expiry, warranty_supplier: form.warranty_supplier === '' ? null : form.warranty_supplier, overdue_service_date: form.overdue_service_date === '' ? null : form.overdue_service_date, note: form.note } }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, session.value?.mode, session.value?.capabilities, permitted.value, props.asset?.tenant_id, props.asset?.id]))
watch(contextKey, () => { focusEpoch++; clearForm(); const s = session.value; workspace.reset(permitted.value && s && props.asset ? { tenantId: s.tenant.id, assetId: props.asset.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: true } : null); workspace.setVisible(open.value); if (open.value && permitted.value) void load() }, { immediate: true, flush: 'sync' })
watch(open, value => { workspace.setVisible(value); if (value && permitted.value && !state.value.busy) void load() })
async function withReadFocus(action: () => Promise<boolean>) {
  if (disposed || state.value.busy) return false
  const mine = ++focusEpoch, owner = contextKey.value, active = document.activeElement
  const ok = await action(); await nextTick()
  if (disposed || mine !== focusEpoch || owner !== contextKey.value || !permitted.value || !open.value) return false
  if (document.activeElement === active || document.activeElement === document.body) {
    if (state.value.error) readError.value?.focus()
    else if (ok) { if (state.value.readTarget?.beforeVersion !== null) historyHeading.value?.focus(); else currentHeading.value?.focus() }
  }
  return ok
}
function load() { return withReadFocus(() => workspace.refresh()) }
const retryRead = () => withReadFocus(workspace.retryRead)
const earlierHistory = () => withReadFocus(() => workspace.refresh(true))
const readMessage = computed(() => state.value.busy === 'read' ? '正在读取维保资料与历史，可以取消。' : state.value.cancelledRead ? '读取已取消；原输入与保存请求保持不变。' : state.value.error ? '读取或保存未完成，请查看错误及恢复方式。' : state.value.current ? `当前档案 v${state.value.current.asset.version}，本页修订 ${state.value.history?.items.length ?? 0} 条。` : '尚未读取维保资料。')
function clearSubmitted(payload: WarrantyInput & { expected_version: number }) {
  if (baseline.value === payload.expected_version && (Object.keys(form) as (keyof WarrantyInput)[]).every(key => form[key] === (payload[key] ?? ''))) clearForm()
  else validation.value = '原保存已确认，后来填写的内容已保留；请核对基准版本后再提交'
}
async function beginEdit() { if (locked.value || !await workspace.refresh() || !state.value.current?.can_edit) return; const current = state.value.current; clearForm(); form.warranty_start = current.warranty_details?.warranty_start ?? ''; form.warranty_supplier = current.warranty_details?.warranty_supplier ?? ''; form.warranty_expiry = current.metadata?.warranty_expiry ?? ''; form.overdue_service_date = current.metadata?.overdue_service_date ?? ''; form.note = current.metadata?.note ?? ''; baseline.value = current.asset.version }
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.current?.asset.version !== baseline.value) { validation.value = '资产版本尚未核对或已更新，请重新点击“基于当前版本编辑”核对后再提交'; return }
  const owner = contextKey.value
  try { const facts = input(), expected = baseline.value; validateWarrantyInput(facts); validation.value = ''; if (await workspace.submit(facts, expected) && !disposed && owner === contextKey.value) { clearSubmitted({ ...facts, expected_version: expected }); emit('saved') } }
  catch (error) { if (!disposed && owner === contextKey.value) validation.value = error instanceof Error ? error.message : '请核对维保字段' }
}
async function retryOriginal() {
  const owner = contextKey.value, attempt = state.value.attempt
  if (!attempt || attempt.state !== 'uncertain' || state.value.busy) return
  const payload = JSON.parse(attempt.payload) as WarrantyInput & { expected_version: number }
  if (await workspace.retry() && !disposed && owner === contextKey.value) { clearSubmitted(payload); emit('saved') }
}
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('维保档案保存状态待确认，请先完成重试后再离开页面'); return false })
const unregister = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('维保档案保存状态待确认，请先完成重试再切换租户或退出'); return false })
onUnmounted(() => { disposed = true; focusEpoch++; unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved, cancelRead: workspace.cancelRead, retryRead })
</script>
<template>
  <el-drawer v-model="open" title="维保档案维护与修订历史" size="820px" class="warranty-drawer" :close-on-click-modal="false">
    <div v-if="asset" class="warranty-asset-banner"><strong>{{ asset.name }}</strong><span class="warranty-id mono">{{ asset.id }}</span></div>
    <el-alert type="info" :closable="false" title="维护手工台账设备的维保起始日、维保到期日、维保供应商与超期服役日期（选填项留空记为未填写）。" />
    <el-empty v-if="!permitted" description="当前账号无维保档案维护权限" />
    <template v-else>
      <div class="warranty-actions"><el-button :disabled="Boolean(state.busy)" @click="load">刷新维保资料与历史</el-button><span>刷新仅同步最新档案记录，不会覆盖下方正在编辑的草稿</span></div>
      <p ref="readNotice" tabindex="-1" role="status">{{ readMessage }}</p>
      <ReadRecoveryActions :busy="state.busy" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="维保资料" :cancel="workspace.cancelRead" :retry="retryRead" />
      <p v-if="state.readTarget && state.readTarget.beforeVersion !== null" class="field-help">本次历史边界：版本 {{ state.readTarget.beforeVersion }} 之前。重试仍沿用此原始边界；刷新首页需显式点击上方按钮。</p>
      <div v-if="state.error" ref="readError" role="group" aria-label="维保资料读取或保存结果" tabindex="-1"><ApiErrorNotice :error="state.error" /></div>
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存请求响应超时或中断：当前提交内容与版本已锁定，请点击“重试保存”继续处理，请勿刷新或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retryOriginal">重试保存</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="提交未通过：请刷新最新记录后重新进入编辑。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`维保档案已保存（资产版本 v${state.receipt.asset.version}）`" />
      <section v-if="state.current" class="warranty-section">
        <h3 ref="currentHeading" tabindex="-1" class="tabular-nums">当前档案（资产版本 v{{ state.current.asset.version }} · 资料版本 {{ state.current.metadata_version ? 'v' + state.current.metadata_version : '未登记' }}）</h3>
        <p class="warranty-id">管理 IP {{ state.current.asset.management_ip }} · 站点 {{ state.current.asset.site_id }}</p>
        <el-alert v-for="reason in state.current.edit_blockers" :key="reason" type="warning" :closable="false" :title="blockers[reason] || reason" />
        <p class="field-help">{{ state.current.warranty_details === null ? '暂未登记补充维保信息' : '已登记补充维保信息（未填写项显示为未填写）' }}</p>
        <dl class="warranty-facts"><dt>维保起始日</dt><dd class="tabular-nums">{{ state.current.warranty_details?.warranty_start ?? '未填写' }}</dd><dt>维保到期日</dt><dd class="tabular-nums">{{ state.current.metadata?.warranty_expiry ?? '未填写' }}</dd><dt>维保供应商</dt><dd>{{ state.current.warranty_details?.warranty_supplier ?? '未填写' }}</dd><dt>超期服役日期</dt><dd class="tabular-nums">{{ state.current.metadata?.overdue_service_date ?? '未填写' }}</dd><dt>资产资料备注</dt><dd>{{ state.current.metadata?.note ?? '未填写' }}</dd></dl>
        <el-button type="primary" plain :disabled="locked || !state.current.can_edit" @click="beginEdit">基于当前版本编辑</el-button>
      </section>
      <section v-if="baseline !== null" class="warranty-section">
        <h3 class="tabular-nums">编辑草稿（基准版本 v{{ baseline }}）</h3><el-alert v-if="state.current && state.current.asset.version !== baseline" type="warning" :closable="false" title="检测到档案版本已更新，当前表单已暂停提交；请点击“基于当前版本编辑”重新加载最新基准。" />
        <el-form label-position="top" @submit.prevent="save"><div class="warranty-grid"><el-form-item v-for="date in dates" :key="date.key" :label="date.label"><el-input v-model="form[date.key]" :disabled="locked" :aria-label="date.label" placeholder="YYYY-MM-DD，选填" /></el-form-item><el-form-item label="维保供应商（选填，最多 200 字符）"><el-input v-model="form.warranty_supplier" :disabled="locked" aria-label="维保供应商" placeholder="输入维保服务供应商名称，选填" /></el-form-item></div>
          <p class="field-help">同时填写维保起始日与到期日时，起始日不得晚于到期日；选填项留空记为未填写，其余扩展资料保持不变。</p>
          <el-form-item label="资产资料备注（必填，最多 2000 字符）"><el-input v-model="form.note" type="textarea" :rows="3" :disabled="locked" placeholder="记录本次维保信息维护原因或核对依据" aria-label="维保维护的资产资料备注" /></el-form-item><p class="field-help">已带出当前资料备注；保存后将同步更新资产资料备注，历史版本自动留存。</p>
          <p v-if="validation" role="alert" class="warranty-validation">{{ validation }}</p><div class="warranty-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.current?.can_edit || state.current.asset.version !== baseline">保存维保修订</el-button><el-button :disabled="locked" @click="clearForm">取消编辑</el-button></div>
        </el-form>
      </section>
      <section v-if="state.history" class="warranty-section"><h3 ref="historyHeading" tabindex="-1">共享资产资料修订历史</h3><p class="field-help">展示基础扩展资料与维保档案的合并修订记录（每页 15 条，按版本倒序排列）。</p><el-empty v-if="!state.history.items.length" description="暂无修订历史" /><details v-for="revision in state.history.items" :key="revision.asset_version" class="warranty-history"><summary class="tabular-nums">v{{ revision.asset_version }} · {{ revision.recorded_at }}</summary><p class="warranty-id">操作者 {{ revision.actor_id }}</p><p>补充资料：{{ revision.before.warranty_details === null ? '未登记' : '已登记' }} → {{ revision.warranty_details === null ? '未登记' : '已登记' }}</p><dl class="warranty-facts"><dt>起始日</dt><dd class="tabular-nums">{{ revision.before.warranty_details?.warranty_start ?? '未填写' }} → {{ revision.warranty_details?.warranty_start ?? '未填写' }}</dd><dt>到期日</dt><dd class="tabular-nums">{{ revision.before.metadata?.warranty_expiry ?? '未填写' }} → {{ revision.metadata.warranty_expiry ?? '未填写' }}</dd><dt>维保供应商</dt><dd>{{ revision.before.warranty_details?.warranty_supplier ?? '未填写' }} → {{ revision.warranty_details?.warranty_supplier ?? '未填写' }}</dd><dt>超期服役日</dt><dd class="tabular-nums">{{ revision.before.metadata?.overdue_service_date ?? '未填写' }} → {{ revision.metadata.overdue_service_date ?? '未填写' }}</dd><dt>资产资料备注</dt><dd>{{ revision.before.metadata?.note ?? '未填写' }} → {{ revision.metadata.note }}</dd></dl></details><el-button :disabled="Boolean(state.busy) || !state.history.has_more" @click="earlierHistory">加载更早修订记录</el-button></section>
    </template>
    <template #footer><el-button @click="open = false">收起面板</el-button><span class="field-help">收起面板不会中断正在执行的保存请求；待确认状态将保留在当前会话中</span></template>
  </el-drawer>
</template>
<style>
.warranty-drawer{max-width:100vw}.tabular-nums{font-variant-numeric:tabular-nums}.warranty-drawer .el-alert{margin-bottom:12px}.warranty-asset-banner{display:flex;align-items:baseline;justify-content:space-between;flex-wrap:wrap;gap:8px;padding:10px 14px;background:#f8fafc;border:1px solid #e4ebf3;border-radius:8px;margin-bottom:12px}.warranty-actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:16px 0}.warranty-id{display:block;font-size:12px;overflow-wrap:anywhere;color:var(--el-text-color-secondary)}.warranty-section{border-top:1px solid var(--el-border-color);margin-top:24px;padding-top:14px}.warranty-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.warranty-facts{display:grid;grid-template-columns:150px minmax(0,1fr);gap:8px 12px;padding:12px;background:#f8fafc;border-radius:6px;margin:12px 0}.warranty-facts dt{color:var(--el-text-color-secondary)}.warranty-facts dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.warranty-history{padding:12px 0;border-bottom:1px solid var(--el-border-color)}.warranty-history summary{cursor:pointer;overflow-wrap:anywhere;font-weight:500}.warranty-validation{color:var(--el-color-danger)}
@media(max-width:600px){.warranty-drawer .el-drawer__body{padding:14px}.warranty-grid,.warranty-facts{grid-template-columns:1fr}.warranty-facts dd{margin-bottom:10px}.warranty-drawer .el-button{min-height:44px;height:auto;white-space:normal}.warranty-drawer .el-input__wrapper{min-height:44px}.warranty-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}}
</style>
