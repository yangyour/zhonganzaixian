<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createManualRecoveryWorkspace, emptyManualRecoveryState, type ManualRecoveryState } from '../utils/manualRecoveryWorkspace'
import type { ManualIncidentEntry } from '../utils/manualIncidentReader'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<ManualRecoveryState>(emptyManualRecoveryState())
const workspace = createManualRecoveryWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('manual_recovery:read') && !switchingTenant.value))
const note = ref(''), recoveredAt = ref(''), humanConfirmed = ref(false), validation = ref('')
const hasDraft = computed(() => Boolean(state.value.parent && !state.value.receipt && (note.value || recoveredAt.value || humanConfirmed.value)))
const locked = computed(() => !permitted.value || !can('manual_recovery:confirm') || Boolean(state.value.busy) || Boolean(state.value.attempt))
async function confirmDiscard() { if (!hasDraft.value) return true; try { await ElMessageBox.confirm('将离开或替换尚未发送的人工恢复说明，是否继续？', '保留当前说明', { type: 'warning', confirmButtonText: '确认继续', cancelButtonText: '保留当前内容' }); return true } catch { return false } }
async function prepare(parent: ManualIncidentEntry) { if (!permitted.value) return false; if (workspace.unresolved() || state.value.busy) { open.value = true; ElMessage.warning('原确认请求尚未处理完，不能更换事件'); return false } const generation = contextKey.value; if (!await confirmDiscard() || generation !== contextKey.value || !permitted.value || !workspace.begin(parent)) return false; note.value = ''; recoveredAt.value = ''; humanConfirmed.value = false; validation.value = ''; open.value = true; return workspace.refresh() }
async function save() { if (locked.value || state.value.current?.version !== 0) return; validation.value = ''; if (!humanConfirmed.value) { validation.value = '必须明确勾选已通过人工方式确认恢复；监控正常不能代替此确认'; return } try { if (await workspace.submit(note.value, recoveredAt.value === '' ? null : recoveredAt.value)) ElMessage.success('人工确认已记录，仍为自述证据，不代表正式结单') } catch (error) { validation.value = error instanceof Error ? error.message : '请核对人工说明与可空恢复时间' } }
async function retry() { if (await workspace.retry()) ElMessage.success('原人工确认回执已核验，未重复写入') }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => { const current = session.value; workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version } : null); note.value = ''; recoveredAt.value = ''; humanConfirmed.value = false; validation.value = '' }, { immediate: true, flush: 'sync' })
async function mayLeave() { if (workspace.unresolved()) { ElMessage.warning('人工确认结果尚未核验，请先使用相同原请求重试'); return false } return confirmDiscard() }
onBeforeRouteLeave(mayLeave)
const unregisterContextGuard = contextTransitionGuards.register(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || hasDraft.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ prepare, hasUnresolved: workspace.unresolved })
</script>
<template>
  <el-drawer v-model="open" title="当前人工恢复 · 独立确认记录" size="800px" class="manual-recovery-panel" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" title="此处单独读取当前人工恢复。原登记快照不被修改；确认仍为自述证据，不改监控状态、正式处置、工单状态或SLA。" />
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前授权已失效，原范围内容已清除" />
    <template v-else>
      <h3 v-if="state.parent" class="recovery-original">{{ state.parent.title }}</h3><p v-if="state.parent" class="recovery-id">固定手工事件：{{ state.parent.id }}</p>
      <ApiErrorNotice :error="state.error" />
      <div class="recovery-actions"><el-button :disabled="Boolean(state.busy) || !state.parent" @click="workspace.refresh()">只读查询当前人工恢复</el-button><el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retry">使用完全相同原确认请求重试</el-button><el-button v-if="state.attempt?.state === 'rejected' && state.parent" :disabled="Boolean(state.busy)" @click="prepare(state.parent)">重新读取并明确准备</el-button></div>
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="原请求结果尚未确认。内容、事件、版本和幂等键保留；即使只读发现已有确认，也不能自动冒充本次请求成功，请处理原请求。" />
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次请求被拒绝，未自动重试。请只读核对当前确认；已有证据不能覆盖或创建第二份。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="'已核验原确认回执：' + state.receipt.confirmation?.id + '；不代表正式结单'" />
      <p v-if="!state.current" class="field-help">当前人工恢复尚未成功读取，不能据此认定“未恢复”或“未确认”{{ state.receipt ? '；已确认成功的原回执仍保留，不要另建重复请求' : '' }}</p>
      <section v-else-if="state.current.version === 1 && state.current.confirmation" class="recovery-evidence"><h3>已有人工作出的恢复确认</h3><p>来源：人工确认 · 核验属性：自述（SELF_REPORTED），不是监控自动判断</p><p v-if="state.attempt?.state === 'uncertain'">以下是当前读取到的证据，不等同于已取得原请求回执</p><dl><dt>确认人ID</dt><dd>{{ state.current.confirmation.confirmed_by }}</dd><dt>技术记录时间</dt><dd>{{ displayTime(state.current.confirmation.recorded_at) }}</dd><dt>人工提供的实际恢复时间</dt><dd>{{ state.current.confirmation.reported_recovered_at ? displayTime(state.current.confirmation.reported_recovered_at) : '未知（null），不以技术记录时间补齐' }}</dd><dt>原始说明</dt><dd class="recovery-original">{{ state.current.confirmation.note }}</dd><dt>原文SHA256</dt><dd>{{ state.current.confirmation.note_sha256 }}</dd></dl><p>原登记/监控/正式处置/工单/SLA未改变。此证据不可覆盖，更正与撤回入口尚未提供。</p></section>
      <template v-else>
        <p>当前已读取：尚无独立人工确认（技术版本0）。这不表示监控判断为故障或正常。</p>
        <el-form v-if="can('manual_recovery:confirm')" label-position="top" @submit.prevent="save">
          <el-form-item label="人工确认说明（1至2000字符，保留原文）"><el-input v-model="note" type="textarea" :rows="5" :disabled="locked" aria-label="人工恢复说明" placeholder="说明人工核验依据，不要输入密码或token" /></el-form-item>
          <el-form-item label="人工提供的恢复发生时间（可留空保持未知；须带时区及秒）"><el-input v-model="recoveredAt" :disabled="locked" aria-label="人工恢复发生时间" placeholder="不默认当前时间，例如2026-10-03T09:00:00+08:00" /></el-form-item>
          <el-checkbox v-model="humanConfirmed" :disabled="locked">我已通过人工方式核验，并明确确认该手工事件已恢复；监控正常不作为自动确认</el-checkbox>
          <p v-if="validation" role="alert" class="recovery-error">{{ validation }}</p><div class="recovery-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !humanConfirmed">明确记录首次人工恢复确认</el-button></div>
        </el-form>
      </template>
    </template>
    <template #footer><el-button @click="open = false">暂时收起，保留原请求与输入</el-button><p class="field-help">收起不撤销已发送请求；强制会话失效仍清除受保护上下文。</p></template>
  </el-drawer>
</template>
<style>
.manual-recovery-panel{max-width:100vw}.manual-recovery-panel .el-alert{margin:12px 0}.recovery-actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.recovery-original{white-space:pre-wrap;overflow-wrap:anywhere}.recovery-id{overflow-wrap:anywhere;color:var(--el-text-color-secondary)}.recovery-evidence dl{display:grid;grid-template-columns:170px minmax(0,1fr);gap:12px}.recovery-evidence dd{margin:0;overflow-wrap:anywhere}.recovery-evidence dt{color:var(--el-text-color-secondary)}.manual-recovery-panel .el-checkbox{height:auto;white-space:normal}.manual-recovery-panel .el-checkbox__label{white-space:normal;line-height:1.6}.recovery-error{color:var(--el-color-danger)}
@media(max-width:600px){.manual-recovery-panel .el-drawer__body{padding:14px}.recovery-evidence dl{grid-template-columns:1fr;gap:6px}.recovery-evidence dd{margin-bottom:10px}.manual-recovery-panel .el-button{min-height:44px;height:auto;white-space:normal}.manual-recovery-panel .el-input__inner,.manual-recovery-panel .el-textarea__inner{font-size:16px}}
</style>
