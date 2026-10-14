<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import type { Asset, AssetType } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { assetTypes, typeLabel } from '../utils/display'
import { createMetadataWorkspace, emptyMetadataState, validateMetadataInput, type MetadataInput, type MetadataState } from '../utils/assetMetadataWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
const props = defineProps<{ modelValue: boolean; asset: Asset | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<MetadataState>(emptyMetadataState())
const workspace = createMetadataWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.asset?.tenant_id === session.value.tenant.id && ['msp', 'padmin'].includes(session.value.user.role) && can('assets:write') && !switchingTenant.value))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || unresolved.value)
const baseline = ref<number | null>(null), validation = ref(''), referenceQuery = ref('')
const form = reactive<Record<keyof MetadataInput, string>>({ name: '', type: '', site_id: '', brand: '', model: '', serial_number: '', location: '', owner_id: '', business_description: '', purchase_date: '', warranty_expiry: '', overdue_service_date: '', collection_method_note: '', note: '' })
const optionalText = [{ key: 'brand', label: '品牌', max: 100 }, { key: 'model', label: '型号', max: 100 }, { key: 'serial_number', label: '序列号', max: 128 }, { key: 'location', label: '位置', max: 200 }, { key: 'business_description', label: '所属业务说明（未核验CMDB）', max: 200 }, { key: 'collection_method_note', label: '采集方式说明（不配置协议）', max: 100 }] as const
const dateFields = [{ key: 'purchase_date', label: '采购日期' }, { key: 'warranty_expiry', label: '维保到期日' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
const blockers: Record<string, string> = { MANUAL_DRAFT_REQUIRED: '只允许未接入的手工草稿；此资产来源或生命周期不符合条件', MONITORING_STATE_NOT_PRISTINE: '已有监控状态或采样事实，不能用草稿维护覆盖', MANUAL_METADATA_CORE_DRIFT: '核心字段与上次资料修订不一致，需要先核查来源' }
function clearForm() { for (const key of Object.keys(form) as (keyof MetadataInput)[]) form[key] = ''; baseline.value = null; validation.value = '' }
function input(): MetadataInput {
  const nullable = (key: keyof MetadataInput) => form[key] === '' ? null : form[key]
  return { name: form.name, type: form.type as AssetType, site_id: form.site_id, brand: nullable('brand'), model: nullable('model'), serial_number: nullable('serial_number'), location: nullable('location'), owner_id: nullable('owner_id'), business_description: nullable('business_description'), purchase_date: nullable('purchase_date'), warranty_expiry: nullable('warranty_expiry'), overdue_service_date: nullable('overdue_service_date'), collection_method_note: nullable('collection_method_note'), note: form.note }
}
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, props.asset?.id]))
watch(contextKey, () => { clearForm(); referenceQuery.value = ''; const s = session.value; workspace.reset(permitted.value && s && props.asset ? { tenantId: s.tenant.id, assetId: props.asset.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: true } : null); if (open.value && permitted.value) void load() }, { immediate: true })
watch(open, value => { if (value && permitted.value && !state.value.current && !state.value.busy) void load() })
async function load() { if (await workspace.refresh() && !state.value.references) await workspace.references() }
async function beginEdit() {
  if (locked.value || !await workspace.refresh() || !state.value.current?.can_edit) return
  const current = state.value.current; clearForm()
  if (current.metadata) for (const key of Object.keys(form) as (keyof MetadataInput)[]) form[key] = current.metadata[key] ?? ''
  else { form.name = current.asset.name; form.type = current.asset.type; form.site_id = current.asset.site_id }
  baseline.value = current.asset.version
  if (!state.value.references) await workspace.references()
}
async function save() {
  if (locked.value || baseline.value === null) return
  if (state.value.current?.asset.version !== baseline.value) { validation.value = '读取到更新版本，请重新点击“以当前版本编辑”，核对后再提交'; return }
  try { const facts = input(); validateMetadataInput(facts); validation.value = ''; if (await workspace.submit(facts, baseline.value)) { clearForm(); emit('saved') } }
  catch (error) { validation.value = error instanceof Error ? error.message : '请检查资料字段' }
}
async function retryOriginal() { if (await workspace.retry()) { clearForm(); emit('saved') } }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('资料保存结果尚未确认，请先使用原请求重试，再离开本页'); return false })
const unregisterContextGuard = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('资产资料保存结果尚未确认，请先处理原请求再切换租户或退出'); return false })
onUnmounted(() => { unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved })
</script>

<template>
  <el-drawer v-model="open" title="手工草稿资料与历史" size="880px" class="metadata-drawer" :close-on-click-modal="false">
    <p v-if="asset"><strong>{{ asset.name }}</strong><span class="metadata-id">{{ asset.id }}</span></p>
    <el-alert type="info" :closable="false" title="只维护未接入的手工草稿资料。内容为人工填报、未独立核验；管理IP、CMDB及监控配置不变。未知项保留为空。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有内部资料维护权限，请重新打开资产" />
    <template v-else>
      <div class="metadata-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="load">刷新资料与历史（只读）</el-button><span>刷新不会替换表单基准，也不会自动保存</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存结果尚未确认，原内容、版本及幂等键已保留。请勿刷新浏览器或关闭页面；只读刷新不会重发。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retryOriginal">使用原请求重试</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次请求被拒绝。请重新读取并明确选择编辑版本，不会自动覆盖。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`最近确认回执：资产v${state.receipt.asset.version}；仅代表该次资料保存，当前值以回读为准`" />
      <section v-if="state.current" class="metadata-section">
        <h3>当前资产 v{{ state.current.asset.version }}</h3><p>{{ state.current.asset.name }} · {{ typeLabel(state.current.asset.type) }}</p><p>固定管理IP：<span class="metadata-id-inline">{{ state.current.asset.management_ip }}</span> · 站点：{{ state.current.asset.site_id }}</p>
        <el-alert v-for="reason in state.current.edit_blockers" :key="reason" type="warning" :closable="false" :title="blockers[reason] || reason" />
        <p v-if="!state.current.metadata" class="field-help">尚无资料修订；可选资料与负责人未知，不会自动补值</p>
        <dl v-else class="metadata-facts"><template v-for="field in [...optionalText, ...dateFields, { key: 'owner_id' as const, label: '负责人ID' }, { key: 'note' as const, label: '维护备注' }]" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ state.current.metadata[field.key] ?? '未填写 / 未知' }}</dd></template></dl>
        <el-button type="primary" plain :disabled="locked || !state.current.can_edit" @click="beginEdit">以当前版本编辑</el-button>
      </section>
      <section v-if="baseline !== null" class="metadata-section">
        <h3>明确编辑基准 v{{ baseline }}</h3><el-alert v-if="state.current && state.current.asset.version !== baseline" type="warning" :closable="false" title="当前资产已有更新。表单保留原内容，提交已阻止；请重新选择当前版本核对。" />
        <div class="metadata-actions"><el-input v-model="referenceQuery" :disabled="locked" aria-label="站点与负责人搜索" placeholder="搜索当前租户站点或活跃负责人" @keyup.enter="workspace.references(referenceQuery)" /><el-button :disabled="locked" @click="workspace.references(referenceQuery)">查询引用选项</el-button></div>
        <p class="field-help">各类最多100个选项；查询不改变已选值，实际保存仍会重新核验归属及活跃状态</p><el-alert v-if="state.references?.sites_truncated || state.references?.owners_truncated" type="warning" :closable="false" title="选项超过100条，请缩小搜索；当前列表不是完整目录" />
        <el-form label-position="top" @submit.prevent="save">
          <div class="metadata-grid">
            <el-form-item label="设备名称（必填）"><el-input v-model="form.name" :disabled="locked" aria-label="资料设备名称" /></el-form-item>
            <el-form-item label="设备类型（必选）"><el-select v-model="form.type" :disabled="locked" aria-label="资料设备类型"><el-option v-for="type in assetTypes" :key="type.value" :value="type.value" :label="type.label" /></el-select></el-form-item>
            <el-form-item label="站点（当前租户，必选）"><el-select v-model="form.site_id" :disabled="locked" aria-label="资料站点"><el-option v-if="form.site_id && !state.references?.sites.some(x => x.id === form.site_id)" :value="form.site_id" :label="`已记录：${form.site_id}（本次选项未匹配）`" disabled /><el-option v-for="site in state.references?.sites || []" :key="site.id" :value="site.id" :label="`${site.label} · ${site.id}`" /></el-select></el-form-item>
            <el-form-item label="负责人（可明确选择未知）"><el-select v-model="form.owner_id" :disabled="locked" aria-label="资料负责人"><el-option value="" label="未填写 / 未知（保存null）" /><el-option v-if="form.owner_id && !state.references?.owners.some(x => x.id === form.owner_id)" :value="form.owner_id" :label="`已记录：${form.owner_id}（本次选项未匹配）`" disabled /><el-option v-for="owner in state.references?.owners || []" :key="owner.id" :value="owner.id" :label="`${owner.label} · ${owner.id}`" /></el-select></el-form-item>
            <el-form-item v-for="field in optionalText" :key="field.key" :label="`${field.label}（可选，最多${field.max}字符）`"><el-input v-model="form[field.key]" :disabled="locked" :aria-label="field.label" placeholder="未知留空，保存null" /></el-form-item>
            <el-form-item v-for="field in dateFields" :key="field.key" :label="`${field.label}（可选）`"><el-input v-model="form[field.key]" :disabled="locked" :aria-label="field.label" placeholder="YYYY-MM-DD，未知留空" /></el-form-item>
          </div>
          <p class="field-help">日期须是0001—9999年的有效日期，不补当前日期、不自动推断逾期。采集方式仅为说明文字，不填账号、密码或密钥。</p>
          <el-form-item label="维护备注（必填，最多2000字符）"><el-input v-model="form.note" type="textarea" :rows="3" :disabled="locked" aria-label="资料维护备注" /></el-form-item>
          <p v-if="validation" role="alert" class="metadata-validation">{{ validation }}</p><div class="metadata-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.current?.can_edit || state.current.asset.version !== baseline">确认保存资料修订</el-button><el-button :disabled="locked" @click="clearForm">放弃未发送编辑</el-button></div>
        </el-form>
      </section>
      <section v-if="state.history" class="metadata-section"><h3>不可变修订历史</h3><p class="field-help">以下仅为已读取的一页，每页15条；手工填报不等于独立核验或监控生效</p><el-empty v-if="!state.history.items.length" description="没有资料修订历史" /><details v-for="revision in state.history.items" :key="revision.asset_version" class="metadata-history"><summary>资料修订 v{{ revision.asset_version }} · {{ revision.recorded_at }}</summary><p class="metadata-id">操作者 {{ revision.actor_id }}</p><p>{{ revision.before.asset.name }} → {{ revision.metadata.name }}；{{ typeLabel(revision.before.asset.type) }} → {{ typeLabel(revision.metadata.type) }}</p><p>站点 {{ revision.before.asset.site_id }} → {{ revision.metadata.site_id }}；管理IP保持 {{ revision.management_ip }}</p><dl class="metadata-facts"><template v-for="field in [...optionalText, ...dateFields, { key: 'owner_id' as const, label: '负责人ID' }, { key: 'note' as const, label: '维护备注' }]" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ revision.before.metadata?.[field.key] ?? '未填写 / 未知' }} → {{ revision.metadata[field.key] ?? '未填写 / 未知' }}</dd></template></dl></details><el-button :disabled="Boolean(state.busy) || !state.history.has_more" @click="workspace.refresh(true)">更早资料修订</el-button></section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起不会取消已发送的请求；未确认请求保留在本页</span></template>
  </el-drawer>
</template>

<style>
.metadata-drawer{max-width:100vw}.metadata-drawer .el-alert{margin-bottom:12px}.metadata-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:16px 0}.metadata-actions>.el-input{flex:1;min-width:200px}.metadata-id{display:block;font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.metadata-id-inline{font-family:monospace;overflow-wrap:anywhere}.metadata-section{border-top:1px solid var(--el-border-color);padding-top:12px;margin-top:24px}.metadata-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.metadata-grid .el-select{width:100%}.metadata-facts{display:grid;grid-template-columns:160px minmax(0,1fr);gap:8px 12px}.metadata-facts dt{color:var(--el-text-color-secondary)}.metadata-facts dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.metadata-history{padding:12px 0;border-bottom:1px solid var(--el-border-color)}.metadata-history summary{cursor:pointer;overflow-wrap:anywhere}.metadata-validation{color:var(--el-color-danger)}
@media(max-width:600px){.metadata-drawer .el-drawer__body{padding:14px}.metadata-grid{grid-template-columns:1fr}.metadata-drawer .el-button{min-height:44px;height:auto;white-space:normal}.metadata-drawer .el-input__wrapper,.metadata-drawer .el-select__wrapper{min-height:44px}.metadata-facts{grid-template-columns:1fr;gap:4px}.metadata-facts dd{margin-bottom:10px}.metadata-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}.metadata-section p{overflow-wrap:anywhere}}
</style>
