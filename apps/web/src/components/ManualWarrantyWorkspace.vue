<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createWarrantyWorkspace, emptyWarrantyState, validateWarrantyInput, type WarrantyInput, type WarrantyState } from '../utils/manualWarrantyWorkspace'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ modelValue: boolean; asset: Asset | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<WarrantyState>(emptyWarrantyState())
const workspace = createWarrantyWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && props.asset?.tenant_id === session.value.tenant.id && ['msp', 'padmin'].includes(session.value.user.role) && can('assets:write') && !switchingTenant.value))
const unresolved = computed(() => state.value.busy === 'write' || state.value.attempt?.state === 'uncertain')
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || unresolved.value)
const baseline = ref<number | null>(null), validation = ref('')
const form = reactive<Record<keyof WarrantyInput, string>>({ warranty_start: '', warranty_expiry: '', warranty_supplier: '', overdue_service_date: '', note: '' })
const dates = [{ key: 'warranty_start', label: '维保起始日' }, { key: 'warranty_expiry', label: '维保到期日' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
const blockers: Record<string, string> = { MANUAL_DRAFT_REQUIRED: '仅允许未接入的手工草稿', MONITORING_STATE_NOT_PRISTINE: '已有监控状态或采样事实，不能覆盖', MANUAL_METADATA_CORE_DRIFT: '核心字段与资料快照不一致，需先核查' }
function clearForm() { for (const key of Object.keys(form) as (keyof WarrantyInput)[]) form[key] = ''; baseline.value = null; validation.value = '' }
function input(): WarrantyInput { return { warranty_start: form.warranty_start === '' ? null : form.warranty_start, warranty_expiry: form.warranty_expiry === '' ? null : form.warranty_expiry, warranty_supplier: form.warranty_supplier === '' ? null : form.warranty_supplier, overdue_service_date: form.overdue_service_date === '' ? null : form.overdue_service_date, note: form.note } }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, props.asset?.id]))
watch(contextKey, () => { clearForm(); const s = session.value; workspace.reset(permitted.value && s && props.asset ? { tenantId: s.tenant.id, assetId: props.asset.id, actorId: s.user.id, permissionVersion: s.permission_version, canWrite: true } : null); workspace.setVisible(open.value); if (open.value && permitted.value) void workspace.refresh() }, { immediate: true })
watch(open, value => { workspace.setVisible(value); if (value && permitted.value && !state.value.busy) void workspace.refresh() })
async function beginEdit() { if (locked.value || !await workspace.refresh() || !state.value.current?.can_edit) return; const current = state.value.current; clearForm(); form.warranty_start = current.warranty_details?.warranty_start ?? ''; form.warranty_supplier = current.warranty_details?.warranty_supplier ?? ''; form.warranty_expiry = current.metadata?.warranty_expiry ?? ''; form.overdue_service_date = current.metadata?.overdue_service_date ?? ''; form.note = current.metadata?.note ?? ''; baseline.value = current.asset.version }
async function save() { if (locked.value || baseline.value === null) return; if (state.value.current?.asset.version !== baseline.value) { validation.value = '当前资料已更新，请重新选择当前版本编辑并核对'; return } try { const facts = input(); validateWarrantyInput(facts); validation.value = ''; if (await workspace.submit(facts, baseline.value)) { clearForm(); emit('saved') } } catch (error) { validation.value = error instanceof Error ? error.message : '请核对维保字段' } }
async function retryOriginal() { if (await workspace.retry()) { clearForm(); emit('saved') } }
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => { if (!workspace.unresolved()) return true; ElMessage.warning('维保保存结果尚未确认，请先使用原请求重试'); return false })
const unregister = contextTransitionGuards.register(() => { if (!workspace.unresolved()) return true; ElMessage.warning('维保保存结果尚未确认，请先处理原请求再切换租户或退出'); return false })
onUnmounted(() => { unregister(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ hasUnresolved: workspace.unresolved })
</script>
<template>
  <el-drawer v-model="open" title="维保资料维护与历史" size="820px" class="warranty-drawer" :close-on-click-modal="false">
    <p v-if="asset"><strong>{{ asset.name }}</strong><span class="warranty-id">{{ asset.id }}</span></p>
    <el-alert type="info" :closable="false" title="人工填报的起始日、到期日、供应商和超期日。供应商与维保覆盖未核验；保存不会生成隐患、发送通知或改变监控。" />
    <el-empty v-if="!permitted" description="当前身份或租户没有内部维保资料维护权限" />
    <template v-else>
      <div class="warranty-actions"><el-button :loading="state.busy === 'read'" :disabled="state.busy === 'write'" @click="workspace.refresh()">只读刷新资料与历史</el-button><span>不重发保存，不替换表单基准</span></div>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="保存结果未知，原对象、内容、版本与幂等键已保留。不要刷新浏览器；只读查询到新值也不能确认原请求。" />
      <el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retryOriginal">使用原请求重试</el-button>
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次请求已拒绝；重新读取并明确选择编辑版本，不自动覆盖" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="`原请求确认回执：v${state.receipt.asset.version}。此为该次结果，当前版本以新回读为准`" />
      <section v-if="state.current" class="warranty-section">
        <h3>当前资产 v{{ state.current.asset.version }} · 资料版本 {{ state.current.metadata_version ?? '尚无' }}</h3>
        <p class="warranty-id">管理IP {{ state.current.asset.management_ip }} · 站点 {{ state.current.asset.site_id }}</p>
        <el-alert v-for="reason in state.current.edit_blockers" :key="reason" type="warning" :closable="false" :title="blockers[reason] || reason" />
        <p>{{ state.current.warranty_details === null ? '尚未登记补充维保资料' : '已明确登记补充资料；空值表示未知' }}</p>
        <dl class="warranty-facts"><dt>维保起始日</dt><dd>{{ state.current.warranty_details?.warranty_start ?? '未知' }}</dd><dt>维保到期日</dt><dd>{{ state.current.metadata?.warranty_expiry ?? '未知' }}</dd><dt>供应商（未核验）</dt><dd>{{ state.current.warranty_details?.warranty_supplier ?? '未知' }}</dd><dt>超期服役日期</dt><dd>{{ state.current.metadata?.overdue_service_date ?? '未知' }}</dd><dt>资产资料备注</dt><dd>{{ state.current.metadata?.note ?? '尚无' }}</dd></dl>
        <el-button type="primary" plain :disabled="locked || !state.current.can_edit" @click="beginEdit">以当前版本编辑维保</el-button>
      </section>
      <section v-if="baseline !== null" class="warranty-section">
        <h3>明确编辑基准 v{{ baseline }}</h3><el-alert v-if="state.current && state.current.asset.version !== baseline" type="warning" :closable="false" title="当前版本已变化，表单保持原内容；须重新选择编辑基准" />
        <el-form label-position="top" @submit.prevent="save"><div class="warranty-grid"><el-form-item v-for="date in dates" :key="date.key" :label="date.label"><el-input v-model="form[date.key]" :disabled="locked" :aria-label="date.label" placeholder="YYYY-MM-DD；未知留空" /></el-form-item><el-form-item label="供应商（可选，最多200 Unicode字符）"><el-input v-model="form.warranty_supplier" :disabled="locked" aria-label="维保供应商" placeholder="人工填写；未知留空，不填凭据" /></el-form-item></div>
          <p class="field-help">起始和到期都已知时，起始不能晚于到期。留空将明确保存null，不补当前日期或默认供应商。其他基础资料保留。</p>
          <el-form-item label="资产资料备注（必填，最多2000字符）"><el-input v-model="form.note" type="textarea" :rows="3" :disabled="locked" aria-label="维保维护的资产资料备注" /></el-form-item><p class="field-help">已预填当前资料备注；修改此处会明确替换资产资料备注，原文保留在历史中。</p>
          <p v-if="validation" role="alert" class="warranty-validation">{{ validation }}</p><div class="warranty-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.current?.can_edit || state.current.asset.version !== baseline">确认保存维保资料修订</el-button><el-button :disabled="locked" @click="clearForm">放弃未发送编辑</el-button></div>
        </el-form>
      </section>
      <section v-if="state.history" class="warranty-section"><h3>共享资产资料修订历史</h3><p class="field-help">包含基础资料与维保维护；当前一页最多15条，不是仅维保变更或正式季度导出。</p><el-empty v-if="!state.history.items.length" description="尚无资料历史" /><details v-for="revision in state.history.items" :key="revision.asset_version" class="warranty-history"><summary>v{{ revision.asset_version }} · {{ revision.recorded_at }}</summary><p class="warranty-id">操作者 {{ revision.actor_id }}</p><p>补充资料：{{ revision.before.warranty_details === null ? '未登记' : '明确登记' }} → {{ revision.warranty_details === null ? '未登记' : '明确登记' }}</p><dl class="warranty-facts"><dt>起始日</dt><dd>{{ revision.before.warranty_details?.warranty_start ?? '未知' }} → {{ revision.warranty_details?.warranty_start ?? '未知' }}</dd><dt>到期日</dt><dd>{{ revision.before.metadata?.warranty_expiry ?? '未知' }} → {{ revision.metadata.warranty_expiry ?? '未知' }}</dd><dt>供应商（未核验）</dt><dd>{{ revision.before.warranty_details?.warranty_supplier ?? '未知' }} → {{ revision.warranty_details?.warranty_supplier ?? '未知' }}</dd><dt>超期服役日</dt><dd>{{ revision.before.metadata?.overdue_service_date ?? '未知' }} → {{ revision.metadata.overdue_service_date ?? '未知' }}</dd><dt>资产资料备注</dt><dd>{{ revision.before.metadata?.note ?? '尚无' }} → {{ revision.metadata.note }}</dd></dl></details><el-button :disabled="Boolean(state.busy) || !state.history.has_more" @click="workspace.refresh(true)">更早资料修订</el-button></section>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><span class="field-help">收起取消迟到读取，但不会取消已发送保存或丢弃未确认请求</span></template>
  </el-drawer>
</template>
<style>
.warranty-drawer{max-width:100vw}.warranty-drawer .el-alert{margin-bottom:12px}.warranty-actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:16px 0}.warranty-id{display:block;font-size:12px;overflow-wrap:anywhere;color:var(--el-text-color-secondary)}.warranty-section{border-top:1px solid var(--el-border-color);margin-top:24px;padding-top:12px}.warranty-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.warranty-facts{display:grid;grid-template-columns:150px minmax(0,1fr);gap:8px 12px}.warranty-facts dt{color:var(--el-text-color-secondary)}.warranty-facts dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.warranty-history{padding:12px 0;border-bottom:1px solid var(--el-border-color)}.warranty-history summary{cursor:pointer;overflow-wrap:anywhere}.warranty-validation{color:var(--el-color-danger)}
@media(max-width:600px){.warranty-drawer .el-drawer__body{padding:14px}.warranty-grid,.warranty-facts{grid-template-columns:1fr}.warranty-facts dd{margin-bottom:10px}.warranty-drawer .el-button{min-height:44px;height:auto;white-space:normal}.warranty-drawer .el-input__wrapper{min-height:44px}.warranty-drawer .el-drawer__footer .field-help{display:block;margin-top:8px}}
</style>
