<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { Asset, AssetType } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { assetTypes, typeLabel } from '../utils/display'
import { createMetadataWorkspace, emptyMetadataState, validateMetadataInput, type MetadataInput, type MetadataState } from '../utils/assetMetadataWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean; asset: Asset | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
let disposed = false, focusEpoch = 0
const readNotice = ref<HTMLElement>(), readError = ref<HTMLElement>(), currentHeading = ref<HTMLElement>(), historyHeading = ref<HTMLElement>()
const state = shallowRef<MetadataState>(emptyMetadataState())
const workspace = createMetadataWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.asset?.tenant_id === session.value.tenant.id && ['msp', 'padmin'].includes(session.value.user.role) && can('assets:write') && !switchingTenant.value))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || unresolved.value)
const baseline = ref<number | null>(null), validation = ref(''), referenceQuery = ref('')
const form = reactive<Record<keyof MetadataInput, string>>({ name: '', type: '', site_id: '', brand: '', model: '', serial_number: '', location: '', owner_id: '', business_description: '', purchase_date: '', warranty_expiry: '', overdue_service_date: '', collection_method_note: '', note: '' })
const optionalText = [{ key: 'brand', label: '品牌', max: 100 }, { key: 'model', label: '型号', max: 100 }, { key: 'serial_number', label: '序列号', max: 128 }, { key: 'location', label: '位置', max: 200 }, { key: 'business_description', label: '所属业务说明', max: 200 }, { key: 'collection_method_note', label: '采集方式说明', max: 100 }] as const
const dateFields = [{ key: 'purchase_date', label: '采购日期' }, { key: 'warranty_expiry', label: '维保到期日' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
const blockers: Record<string, string> = { MANUAL_DRAFT_REQUIRED: '仅支持维护尚未接入监控的手工台账草稿', MONITORING_STATE_NOT_PRISTINE: '该资产已存在实时监控采样状态，不支持通过草稿模式覆盖', MANUAL_METADATA_CORE_DRIFT: '核心台账字段与上一版扩展资料不一致，请先核查数据来源' }
function clearForm() { for (const key of Object.keys(form) as (keyof MetadataInput)[]) form[key] = ''; baseline.value = null; validation.value = '' }
function input(): MetadataInput {
  const nullable = (key: keyof MetadataInput) => form[key] === '' ? null : form[key]
  return { name: form.name, type: form.type as AssetType, site_id: form.site_id, brand: nullable('brand'), model: nullable('model'), serial_number: nullable('serial_number'), location: nullable('location'), owner_id: nullable('owner_id'), business_description: nullable('business_description'), purchase_date: nullable('purchase_date'), warranty_expiry: nullable('warranty_expiry'), overdue_service_date: nullable('overdue_service_date'), collection_method_note: nullable('collection_method_note'), note: form.note }
}
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, session.value?.mode, session.value?.capabilities, permitted.value, props.asset?.tenant_id, props.asset?.id]))
watch(contextKey, () => { focusEpoch++; clearForm(); referenceQuery.value = ''; const s = session.value; workspace.reset(permitted.value && s && props.asset ? { tenantId: s.tenant.id, assetId: props.asset.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: true } : null); if (open.value && permitted.value) void load() }, { immediate: true, flush: 'sync' })
watch(open, value => { if (value && permitted.value && !state.value.current && !state.value.busy) void load() })
async function withReadFocus(action: () => Promise<boolean>) {
  if (disposed || state.value.busy) return false
  const mine = ++focusEpoch, owner = contextKey.value, active = document.activeElement
  const ok = await action(); await nextTick()
  if (disposed || mine !== focusEpoch || owner !== contextKey.value || !permitted.value) return false
  if (document.activeElement === active || document.activeElement === document.body) {
    if (state.value.error) readError.value?.focus()
    else if (ok) { if (state.value.readTarget?.kind === 'references') readNotice.value?.focus(); else if (state.value.readTarget?.kind === 'current-history' && state.value.readTarget.beforeVersion !== null) historyHeading.value?.focus(); else currentHeading.value?.focus() }
  }
  return ok
}
async function load() { const owner = contextKey.value; return withReadFocus(async () => { if (!await workspace.refresh() || disposed || owner !== contextKey.value) return false; return state.value.references ? true : workspace.references() }) }
const retryRead = () => withReadFocus(workspace.retryRead)
const searchReferences = () => withReadFocus(() => workspace.references(referenceQuery.value))
const earlierHistory = () => withReadFocus(() => workspace.refresh(true))
const readMessage = computed(() => state.value.busy === 'read' ? '正在读取资料、修订历史或候选项，可以取消。' : state.value.cancelledRead ? '读取已取消；原输入与保存请求保持不变。' : state.value.error ? '读取或保存未完成，请查看错误及恢复方式。' : state.value.readTarget?.kind === 'references' && state.value.references ? `已读取候选项，原查询：${state.value.references.query || '空关键词'}。` : state.value.current ? `当前档案 v${state.value.current.asset.version}，本页修订 ${state.value.history?.items.length ?? 0} 条。` : '尚未读取资料。')
async function beginEdit() {
  if (locked.value || !await workspace.refresh() || !state.value.current?.can_edit) return
  const current = state.value.current; clearForm()
  if (current.metadata) for (const key of Object.keys(form) as (keyof MetadataInput)[]) form[key] = current.metadata[key] ?? ''
  else { form.name = current.asset.name; form.type = current.asset.type; form.site_id = current.asset.site_id }
  baseline.value = current.asset.version
  if (!state.value.references) await workspace.references()
}
function clearSubmitted(payload: MetadataInput & { expected_version: number }) {
  if (baseline.value === payload.expected_version && (Object.keys(form) as (keyof MetadataInput)[]).every(key => form[key] === (payload[key] ?? ''))) clearForm()
  else validation.value = '原保存已确认，后来填写的内容已保留；请核对基准版本后再提交'
}
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.current?.asset.version !== baseline.value) { validation.value = '资产版本已更新，请重新点击“基于当前版本编辑”核对后再提交'; return }
  const owner = contextKey.value
  try { const facts = input(), expected = baseline.value; validateMetadataInput(facts); validation.value = ''; if (await workspace.submit(facts, expected) && !disposed && owner === contextKey.value) { clearSubmitted({ ...facts, expected_version: expected }); emit('saved') } }
  catch (error) { if (!disposed && owner === contextKey.value) validation.value = error instanceof Error ? error.message : '请检查资料字段' }
}
async function retryOriginal() {
  const owner = contextKey.value, attempt = state.value.attempt
  if (!attempt || attempt.state !== 'uncertain' || state.value.busy) return
  const payload = JSON.parse(attempt.payload) as MetadataInput & { expected_version: number }
  if (await workspace.retry() && !disposed && owner === contextKey.value) { clearSubmitted(payload); emit('saved') }
}
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('扩展资料保存状态待确认，请先完成重试后再离开页面'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('资产资料保存状态待确认，请先完成重试再切换租户或退出'); return false })
onUnmounted(() => { disposed = true; focusEpoch++; unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved, cancelRead: workspace.cancelRead, retryRead })
</script>

<template>
  <el-drawer v-model="open" title="资产扩展资料与修订历史" size="880px" class="metadata-drawer" :close-on-click-modal="false">
    <div v-if="asset" class="metadata-asset-banner"><strong>{{ asset.name }}</strong><span class="metadata-id mono">{{ asset.id }}</span></div>
    <el-alert type="info" :closable="false" title="维护手工台账资产的硬件属性、所属业务与维保日期等扩展档案（管理 IP 与监控采集策略保持不变，选填项留空记为未填写）。" />
    <el-empty v-if="!permitted" description="当前账号无资产扩展资料维护权限，请重新打开资产详情" />
    <template v-else>
      <div class="metadata-actions"><el-button :disabled="Boolean(state.busy)" @click="load">刷新资料与历史</el-button><span>刷新仅同步最新档案状态，不会覆盖下方正在编辑的草稿</span></div>
      <p ref="readNotice" tabindex="-1" role="status">{{ readMessage }}</p>
      <ReadRecoveryActions :busy="state.busy" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="资产资料" :cancel="workspace.cancelRead" :retry="retryRead" />
      <p v-if="state.readTarget?.kind === 'current-history' && state.readTarget.beforeVersion !== null" class="field-help">本次历史边界：版本 {{ state.readTarget.beforeVersion }} 之前。重试仍沿用此原始边界；刷新首页需显式点击上方按钮。</p>
      <div v-if="state.error" ref="readError" role="group" aria-label="资产资料读取或保存结果" tabindex="-1"><ApiErrorNotice :error="state.error" /></div>
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存请求响应超时或中断：当前提交内容与版本已锁定，请点击“重试保存”继续处理，请勿刷新或关闭页面。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retryOriginal">重试保存</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="提交未通过：请刷新最新记录后重新进入编辑。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`扩展资料已保存（资产版本 v${state.receipt.asset.version}）`" />
      <section v-if="state.current" class="metadata-section">
        <h3 ref="currentHeading" tabindex="-1" class="tabular-nums">当前档案（资产版本 v{{ state.current.asset.version }}）</h3><p>{{ state.current.asset.name }} · {{ typeLabel(state.current.asset.type) }}</p><p>固定管理 IP：<span class="metadata-id-inline">{{ state.current.asset.management_ip }}</span> · 站点：{{ state.current.asset.site_id }}</p>
        <el-alert v-for="reason in state.current.edit_blockers" :key="reason" type="warning" :closable="false" :title="blockers[reason] || reason" />
        <p v-if="!state.current.metadata" class="field-help">该资产暂未登记扩展维护资料。</p>
        <dl v-else class="metadata-facts"><template v-for="field in [...optionalText, ...dateFields, { key: 'owner_id' as const, label: '负责人ID' }, { key: 'note' as const, label: '维护备注' }]" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ state.current.metadata[field.key] ?? '未填写' }}</dd></template></dl>
        <el-button type="primary" plain :disabled="locked || !state.current.can_edit" @click="beginEdit">基于当前版本编辑</el-button>
      </section>
      <section v-if="baseline !== null" class="metadata-section">
        <h3 class="tabular-nums">编辑草稿（基准版本 v{{ baseline }}）</h3><el-alert v-if="state.current && state.current.asset.version !== baseline" type="warning" :closable="false" title="检测到资产档案已更新，当前表单已暂停提交；请点击“基于当前版本编辑”重新加载最新基准。" />
        <div class="metadata-actions"><el-input v-model="referenceQuery" :disabled="locked" aria-label="站点与负责人搜索" placeholder="搜索当前租户站点或活跃负责人" @keyup.enter="searchReferences" /><el-button :disabled="locked" @click="searchReferences">搜索可选项</el-button></div>
        <p v-if="state.references" class="field-help">当前候选结果对应查询：{{ state.references.query || '空关键词' }}，不是尚未提交的新搜索文字。</p><p class="field-help">单次加载最多 100 项可选站点与负责人；提交保存时将自动校验所选站点及负责人的有效性。</p><el-alert v-if="state.references?.sites_truncated || state.references?.owners_truncated" type="warning" :closable="false" title="候选项超过 100 条，请输入关键词缩小检索范围。" />
        <el-form label-position="top" @submit.prevent="save">
          <div class="metadata-grid">
            <el-form-item label="设备名称（必填）"><el-input v-model="form.name" :disabled="locked" aria-label="资料设备名称" /></el-form-item>
            <el-form-item label="设备类型（必选）"><el-select v-model="form.type" :disabled="locked" aria-label="资料设备类型"><el-option v-for="type in assetTypes" :key="type.value" :value="type.value" :label="type.label" /></el-select></el-form-item>
            <el-form-item label="站点（当前租户，必选）"><el-select v-model="form.site_id" :disabled="locked" aria-label="资料站点"><el-option v-if="form.site_id && !state.references?.sites.some(x => x.id === form.site_id)" :value="form.site_id" :label="`已记录：${form.site_id}（不在当前检索结果中）`" disabled /><el-option v-for="site in state.references?.sites || []" :key="site.id" :value="site.id" :label="`${site.label} · ${site.id}`" /></el-select></el-form-item>
            <el-form-item label="负责人（选填）"><el-select v-model="form.owner_id" :disabled="locked" aria-label="资料负责人"><el-option value="" label="暂不指定（未填写）" /><el-option v-if="form.owner_id && !state.references?.owners.some(x => x.id === form.owner_id)" :value="form.owner_id" :label="`已记录：${form.owner_id}（不在当前检索结果中）`" disabled /><el-option v-for="owner in state.references?.owners || []" :key="owner.id" :value="owner.id" :label="`${owner.label} · ${owner.id}`" /></el-select></el-form-item>
            <el-form-item v-for="field in optionalText" :key="field.key" :label="`${field.label}（选填，最多 ${field.max} 字符）`"><el-input v-model="form[field.key]" :disabled="locked" :aria-label="field.label" placeholder="选填，留空记为未填写" /></el-form-item>
            <el-form-item v-for="field in dateFields" :key="field.key" :label="`${field.label}（选填）`"><el-input v-model="form[field.key]" :disabled="locked" :aria-label="field.label" placeholder="YYYY-MM-DD，选填" /></el-form-item>
          </div>
          <p class="field-help">日期请按 YYYY-MM-DD 格式填写；采集方式说明仅记录接入方式描述，请勿填入登录密码、SNMP 团体字或私钥等敏感凭据。</p>
          <el-form-item label="维护备注（必填，最多 2000 字符）"><el-input v-model="form.note" type="textarea" :rows="3" :disabled="locked" placeholder="记录本次资料维护原因或核查说明" aria-label="资料维护备注" /></el-form-item>
          <p v-if="validation" role="alert" class="metadata-validation">{{ validation }}</p><div class="metadata-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.current?.can_edit || state.current.asset.version !== baseline">保存资料修订</el-button><el-button :disabled="locked" @click="clearForm">取消编辑</el-button></div>
        </el-form>
      </section>
      <section v-if="state.history" class="metadata-section"><h3 ref="historyHeading" tabindex="-1">资料修订历史</h3><p class="field-help">展示当前已加载的扩展资料修订记录（每页 15 条，按修订版本倒序排列）。</p><el-empty v-if="!state.history.items.length" description="暂无资料修订历史" /><details v-for="revision in state.history.items" :key="revision.asset_version" class="metadata-history"><summary class="tabular-nums">资料修订 v{{ revision.asset_version }} · {{ revision.recorded_at }}</summary><p class="metadata-id">操作者 {{ revision.actor_id }}</p><p>{{ revision.before.asset.name }} → {{ revision.metadata.name }}；{{ typeLabel(revision.before.asset.type) }} → {{ typeLabel(revision.metadata.type) }}</p><p>站点 {{ revision.before.asset.site_id }} → {{ revision.metadata.site_id }}；管理 IP 保持 {{ revision.management_ip }}</p><dl class="metadata-facts"><template v-for="field in [...optionalText, ...dateFields, { key: 'owner_id' as const, label: '负责人ID' }, { key: 'note' as const, label: '维护备注' }]" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ revision.before.metadata?.[field.key] ?? '未填写' }} → {{ revision.metadata[field.key] ?? '未填写' }}</dd></template></dl></details><el-button :disabled="Boolean(state.busy) || !state.history.has_more" @click="earlierHistory">加载更早修订记录</el-button></section>
    </template>
    <template #footer><el-button @click="open = false">收起面板</el-button><span class="field-help">收起面板不会中断正在执行的请求；待确认状态将保留在当前会话中</span></template>
  </el-drawer>
</template>

<style>
.metadata-drawer{max-width:100vw}.metadata-drawer [tabindex="-1"]:focus{outline:3px solid #175cd3;outline-offset:3px}.metadata-drawer .metadata-actions .el-button{min-height:44px;min-width:44px}.metadata-drawer .metadata-actions>.el-input{min-width:0}.tabular-nums{font-variant-numeric:tabular-nums}.metadata-drawer .el-alert{margin-bottom:12px}.metadata-asset-banner{display:flex;align-items:baseline;justify-content:space-between;flex-wrap:wrap;gap:8px;padding:10px 14px;background:#f8fafc;border:1px solid #e4ebf3;border-radius:8px;margin-bottom:12px}.metadata-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}.metadata-actions>.el-input{flex:1;min-width:200px}.metadata-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.metadata-id-inline{font-family:monospace;overflow-wrap:anywhere}.metadata-section{border-top:1px solid var(--el-border-color);padding-top:14px;margin-top:24px}.metadata-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.metadata-grid .el-select{width:100%}.metadata-facts{display:grid;grid-template-columns:160px minmax(0,1fr);gap:8px 12px;padding:12px;background:#f8fafc;border-radius:6px;margin:12px 0}.metadata-facts dt{color:var(--el-text-color-secondary)}.metadata-facts dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.metadata-history{padding:12px 0;border-bottom:1px solid var(--el-border-color)}.metadata-history summary{cursor:pointer;overflow-wrap:anywhere;font-weight:500}.metadata-validation{color:var(--el-color-danger)}
@media(max-width:600px){.metadata-drawer .el-drawer__body{padding:14px}.metadata-grid{grid-template-columns:1fr}.metadata-drawer .el-button{min-height:44px;height:auto;white-space:normal}.metadata-drawer .el-input__wrapper,.metadata-drawer .el-select__wrapper{min-height:44px}.metadata-facts{grid-template-columns:1fr;gap:4px}.metadata-facts dd{margin-bottom:10px}.metadata-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}.metadata-section p{overflow-wrap:anywhere}}
</style>
