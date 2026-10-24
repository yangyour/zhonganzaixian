<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createManualRecoveryWorkspace, emptyManualRecoveryState, type ManualRecoveryState } from '../utils/manualRecoveryWorkspace'
import type { ManualIncidentEntry } from '../utils/manualIncidentReader'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { displayTime } from '../utils/display'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<ManualRecoveryState>(emptyManualRecoveryState())
const workspace = createManualRecoveryWorkspace(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('manual_recovery:read') && !switchingTenant.value))
const writable=computed(()=>permitted.value&&can('manual_recovery:confirm'))
let disposed=false
const confirming=ref(false)
const note = ref(''), recoveredAt = ref(''), humanConfirmed = ref(false), validation = ref('')
const hasDraft = computed(() => Boolean(state.value.parent && !state.value.receipt && (note.value || recoveredAt.value || humanConfirmed.value)))
const locked = computed(() => !writable.value || state.value.current?.version!==0 || Boolean(state.value.busy) || Boolean(state.value.attempt) || confirming.value)
function inputSnapshot(){return JSON.stringify([contextKey.value,state.value.parent?.id,note.value,recoveredAt.value,humanConfirmed.value,workspace.unresolved()])}
async function confirmDiscard(){
 if(disposed||confirming.value)return false
 if(!hasDraft.value)return true
 const before=inputSnapshot(),owner=contextKey.value;confirming.value=true
 try{await ElMessageBox.confirm('将离开或替换尚未发送的人工恢复说明，是否继续？','保留当前说明',{type:'warning',confirmButtonText:'确认继续',cancelButtonText:'保留当前内容'});return !disposed&&before===inputSnapshot()&&!workspace.unresolved()}
 catch{return false}finally{if(!disposed&&owner===contextKey.value)confirming.value=false}
}
async function prepare(parent:ManualIncidentEntry){
 if(!permitted.value||disposed||confirming.value)return false
 if(workspace.unresolved()||state.value.busy){open.value=true;ElMessage.warning('原确认请求尚未处理完，不能更换事件');return false}
 const generation=contextKey.value,before=inputSnapshot(),target=JSON.stringify(parent)
 if(!await confirmDiscard()||generation!==contextKey.value||before!==inputSnapshot()||target!==JSON.stringify(parent)||!permitted.value||disposed)return false
 try{if(!workspace.begin(parent))return false}catch{validation.value='原事件范围或登记快照已失效，请重新读取并选择事件';return false}
 note.value='';recoveredAt.value='';humanConfirmed.value=false;validation.value='';open.value=true;return workspace.refresh()
}
async function save(){
 if(locked.value||state.value.current?.version!==0)return
 validation.value='';if(!humanConfirmed.value){validation.value='请先勾选已通过人工方式核验确认恢复';return}
 const before=inputSnapshot()
 try{if(await workspace.submit(note.value,recoveredAt.value===''?null:recoveredAt.value)&&!disposed&&before===inputSnapshot())ElMessage.success('人工恢复确认已记录')}
 catch(error){if(!disposed&&before===inputSnapshot())validation.value=error instanceof Error?error.message:'请核对人工说明与恢复时间格式'}
}
async function retry(){const owner=contextKey.value,parent=state.value.parent?.id,form=JSON.stringify([note.value,recoveredAt.value,humanConfirmed.value]);if(await workspace.retry()&&!disposed&&owner===contextKey.value&&parent===state.value.parent?.id&&form===JSON.stringify([note.value,recoveredAt.value,humanConfirmed.value]))ElMessage.success('原人工确认回执已核验，未重复写入')}
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value, writable.value]))
watch(contextKey, () => { confirming.value=false;const current = session.value; workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version,canWrite:writable.value } : null); note.value = ''; recoveredAt.value = ''; humanConfirmed.value = false; validation.value = '' }, { immediate: true, flush: 'sync' })
async function mayLeave() { if (workspace.unresolved()) { ElMessage.warning('人工确认结果尚未核验，请先重试提交'); return false } return confirmDiscard() }
const router=useRouter();let routeEpoch=0,removeResolve:(()=>void)|undefined
function clearRouteApproval(){removeResolve?.();removeResolve=undefined;routeEpoch++}
async function mayLeaveRoute(){clearRouteApproval();const turn=routeEpoch,before=inputSnapshot();if(!await mayLeave()||turn!==routeEpoch||disposed||before!==inputSnapshot())return false;removeResolve=router.beforeResolve(()=>!disposed&&turn===routeEpoch&&before===inputSnapshot()&&!workspace.unresolved());return true}
onBeforeRouteLeave(mayLeaveRoute)
const removeAfterRoute=router.afterEach(clearRouteApproval),removeRouteError=router.onError(clearRouteApproval)
const unregisterContextGuard = contextTransitionGuards.register(mayLeave,inputSnapshot)
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved() || hasDraft.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed=true;clearRouteApproval();removeAfterRoute();removeRouteError();unregisterContextGuard(); workspace.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ prepare, hasUnresolved: workspace.unresolved, inputSnapshot, cancelRead:workspace.cancelRead, retryRead:workspace.retryRead })
</script>
<template>
  <el-drawer v-model="open" title="人工恢复确认记录" size="800px" class="manual-recovery-panel" :close-on-click-modal="false">
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前授权已失效，原范围内容已清除" />
    <template v-else>
      <h3 v-if="state.parent" class="recovery-original">{{ state.parent.title }}</h3><p v-if="state.parent" class="recovery-id">关联手工事件：{{ state.parent.id }}（原登记时恢复状态保持不变）</p>
      <ReadRecoveryActions :busy="state.busy" :can-retry="workspace.canRetryRead()" :scope-key="contextKey" label="人工恢复" :cancel="workspace.cancelRead" :retry="workspace.retryRead" />
      <ApiErrorNotice :error="state.error" />
      <div class="recovery-actions"><el-button :disabled="Boolean(state.busy) || confirming || !state.parent" @click="workspace.refresh()">刷新恢复状态</el-button><el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || !writable || confirming" @click="retry">重试提交</el-button><el-button v-if="state.attempt?.state === 'rejected' && state.parent" :disabled="Boolean(state.busy)" @click="prepare(state.parent)">重新加载并填写</el-button></div>
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="提交结果尚未确认，当前填写内容已锁定，请点击“重试提交”继续处理。" />
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="本次提交未通过。请先刷新核对当前恢复确认记录，已保存的确认记录不可覆盖或重复创建。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="'已核验原确认回执：' + state.receipt.confirmation?.id + ' · 人工恢复已记录'" />
      <p v-if="!state.current" class="field-help">当前人工恢复尚未成功读取，不能据此认定“未恢复”或“未确认”{{ state.receipt ? '；已确认成功的原回执仍保留，不要另建重复请求' : '' }}</p>
      <section v-else-if="state.current.version === 1 && state.current.confirmation" class="recovery-evidence"><h3>人工恢复确认详情</h3><p>记录来源：工程师人工核验确认（独立于监控自动判定）</p><p v-if="state.attempt?.state === 'uncertain'">以下是当前读取到的确认记录，原提交回执仍待重试核验</p><dl><dt>确认人ID</dt><dd>{{ state.current.confirmation.confirmed_by }}</dd><dt>记录归档时间</dt><dd>{{ displayTime(state.current.confirmation.recorded_at) }}</dd><dt>实际恢复时间</dt><dd>{{ state.current.confirmation.reported_recovered_at ? displayTime(state.current.confirmation.reported_recovered_at) : '未填写（不以记录归档时间自动补齐）' }}</dd><dt>恢复核验说明</dt><dd class="recovery-original">{{ state.current.confirmation.note }}</dd><dt>内容校验指纹 (SHA-256)</dt><dd>{{ state.current.confirmation.note_sha256 }}</dd></dl><p>人工恢复确认记录归档后不可覆盖或篡改。</p></section>
      <template v-if="state.current?.version===0 || hasDraft">
        <p v-if="state.current?.version===0">当前状态：尚无人工恢复确认记录（版本 0）。</p>
        <p v-else class="field-help">{{ state.attempt?.state==='uncertain' ? '原请求的说明和时间已保留并锁定；当前读取不能确认原提交结果，请重试原提交。' : state.current?.version===1 ? '当前已有不可变人工确认记录，保留的说明不会覆盖它；请核对记录或切换事件。' : '填写的说明和时间已保留；须重新读取并确认当前仍为版本 0 后才能提交。' }}</p>
        <el-form v-if="writable" label-position="top" @submit.prevent="save">
          <el-form-item label="人工恢复核验说明（1–2000 字符）"><el-input v-model="note" type="textarea" :rows="5" :disabled="locked" aria-label="人工恢复说明" placeholder="请填写现场或人工核验恢复的依据，请勿输入账号密码或敏感凭据" /></el-form-item>
          <el-form-item label="实际恢复时间（选填，需包含时区与秒）"><el-input v-model="recoveredAt" :disabled="locked" aria-label="人工恢复发生时间" placeholder="留空表示暂不指定，例如 2026-10-03T09:00:00+08:00" /></el-form-item>
          <el-checkbox v-model="humanConfirmed" :disabled="locked">我已通过人工方式核验并确认该手工事件已恢复</el-checkbox>
          <p v-if="validation" role="alert" class="recovery-error">{{ validation }}</p><div class="recovery-actions"><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !humanConfirmed">提交人工恢复确认</el-button></div>
        </el-form>
      </template>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><p class="field-help">收起窗口不会中断已发送的请求；会话失效将清除未提交内容。</p></template>
  </el-drawer>
</template>
<style>
.manual-recovery-panel{max-width:100vw}.manual-recovery-panel .el-alert{margin:12px 0}.recovery-actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.recovery-original{white-space:pre-wrap;overflow-wrap:anywhere}.recovery-id{overflow-wrap:anywhere;color:var(--el-text-color-secondary)}.recovery-evidence dl{display:grid;grid-template-columns:170px minmax(0,1fr);gap:12px}.recovery-evidence dd{margin:0;overflow-wrap:anywhere}.recovery-evidence dt{color:var(--el-text-color-secondary)}.manual-recovery-panel .el-checkbox{height:auto;white-space:normal}.manual-recovery-panel .el-checkbox__label{white-space:normal;line-height:1.6}.recovery-error{color:var(--el-color-danger)}
@media(max-width:600px){.manual-recovery-panel .el-drawer__body{padding:14px}.recovery-evidence dl{grid-template-columns:1fr;gap:6px}.recovery-evidence dd{margin-bottom:10px}.manual-recovery-panel .el-button{min-height:44px;height:auto;white-space:normal}.manual-recovery-panel .el-input__inner,.manual-recovery-panel .el-textarea__inner{font-size:16px}}
</style>
