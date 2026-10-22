<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createManualIncidentWriter, emptyManualIncidentWriteState, type ManualIncidentWriteState } from '../utils/manualIncidentWriter'
import { manualSeverityLabels, type ManualSeverity, type ManualIncidentEntry } from '../utils/manualIncidentReader'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import ReadRecoveryActions from './ReadRecoveryActions.vue'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [value: ManualIncidentEntry] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<ManualIncidentWriteState>(emptyManualIncidentWriteState()), writer = createManualIncidentWriter(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('manual_incidents:write') && !switchingTenant.value))
let disposed=false
const editorEpoch=ref(0),confirming=ref(false)
const form = reactive({ title: '', severity: '' }), query = ref(''), validation = ref('')
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || Boolean(state.value.attempt) || confirming.value)
const hasDraft = computed(() => state.value.editing && Boolean(form.title || form.severity || query.value || state.value.asset))
const queryInput=ref<{focus:()=>void}|null>(null)
async function restoreAppliedQuery(){if(locked.value||disposed)return;const owner=contextKey.value,epoch=editorEpoch.value,value=state.value.assetSearch;query.value=value;await nextTick();if(!disposed&&owner===contextKey.value&&epoch===editorEpoch.value&&query.value===value&&!locked.value)queryInput.value?.focus()}
const queryDirty=computed(()=>query.value!==state.value.assetSearch)
function chooseAsset(id:string){return locked.value||queryDirty.value?Promise.resolve(false):writer.choose(id)}
function inputSnapshot(){return JSON.stringify([contextKey.value,editorEpoch.value,form.title,form.severity,query.value,state.value.asset,writer.unresolved()])}
async function confirmDiscard(){if(disposed||confirming.value)return false;if(!hasDraft.value)return true;const owner=contextKey.value,before=inputSnapshot();confirming.value=true;try{await ElMessageBox.confirm('将离开或替换当前尚未发送的手工登记内容，是否继续？','保留未提交内容',{type:'warning',confirmButtonText:'确认继续',cancelButtonText:'保留当前内容'});return !disposed&&before===inputSnapshot()&&!writer.unresolved()}catch{return false}finally{if(!disposed&&owner===contextKey.value)confirming.value=false}}
async function prepare(){if(!permitted.value||disposed||confirming.value)return false;if(writer.unresolved()||state.value.busy){open.value=true;ElMessage.warning(state.value.busy==='read'?'当前正在读取，请先取消或等待读取完成':'当前结果尚未确认，请先处理原请求');return false}const owner=contextKey.value,before=inputSnapshot();if(!await confirmDiscard()||owner!==contextKey.value||before!==inputSnapshot()||!permitted.value||disposed||!writer.begin())return false;editorEpoch.value++;form.title='';form.severity='';query.value='';validation.value='';open.value=true;await writer.search('');return true}
function submitted(receipt:ManualIncidentEntry,owner:string){if(disposed||owner!==contextKey.value||!permitted.value)return;ElMessage.success('手工事实已登记，尚未完成正式处置或建单');if(!disposed&&owner===contextKey.value&&permitted.value)emit('saved',receipt)}
async function save(){if(locked.value)return;validation.value='';const owner=contextKey.value,before=inputSnapshot();try{if(!form.severity||!state.value.asset)throw new Error('请明确选择严重级别并读取关联资产，不会补默认值');if(await writer.submit({title:form.title,severity:form.severity as ManualSeverity})&&!disposed&&before===inputSnapshot()&&state.value.receipt)submitted(state.value.receipt,owner)}catch(error){if(!disposed&&before===inputSnapshot())validation.value=error instanceof Error?error.message:'请核对原始输入'}}
async function retry(){const owner=contextKey.value,epoch=editorEpoch.value,before=JSON.stringify([form.title,form.severity,query.value,state.value.asset]);if(await writer.retry()&&!disposed&&owner===contextKey.value&&epoch===editorEpoch.value&&before===JSON.stringify([form.title,form.severity,query.value,state.value.asset])&&state.value.receipt)submitted(state.value.receipt,owner)}
async function adopt(){
 if(writer.unresolved()||state.value.busy||confirming.value||!permitted.value||disposed)return false
 const owner=contextKey.value,before=inputSnapshot()
 if(!await writer.refreshAsset()||!state.value.lastAssetRead||disposed||owner!==contextKey.value||before!==inputSnapshot())return false
 const latest={...state.value.lastAssetRead};confirming.value=true
 try{await ElMessageBox.confirm(`确认采用资产“${latest.name}”的v${latest.version}作为新基准？当前标题与级别保持不变，之后仍需明确提交。`,'核对关联资产最新版本',{confirmButtonText:'采用此基准',cancelButtonText:'保持原基准',type:'warning'});return !disposed&&owner===contextKey.value&&before===inputSnapshot()&&permitted.value&&writer.adoptLatestAsset(latest)}catch{return false}finally{if(!disposed&&owner===contextKey.value)confirming.value=false}
}
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, permitted.value]))
watch(contextKey, () => { editorEpoch.value++;confirming.value=false;const s = session.value; writer.reset(permitted.value && s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null); form.title = ''; form.severity = ''; query.value = ''; validation.value = '' }, { immediate: true, flush: 'sync' })
async function mayLeave() { if (writer.unresolved()) { ElMessage.warning('登记结果尚未确认，请先点击重试提交'); return false } return confirmDiscard() }
const router=useRouter();let routeEpoch=0,removeResolve:(()=>void)|undefined
function clearRouteApproval(){removeResolve?.();removeResolve=undefined;routeEpoch++}
async function mayLeaveRoute(){clearRouteApproval();const turn=routeEpoch,before=inputSnapshot();if(!await mayLeave()||turn!==routeEpoch||disposed||before!==inputSnapshot())return false;removeResolve=router.beforeResolve(()=>!disposed&&turn===routeEpoch&&before===inputSnapshot()&&!writer.unresolved());return true}
onBeforeRouteLeave(mayLeaveRoute)
const removeAfterRoute=router.afterEach(clearRouteApproval),removeRouteError=router.onError(clearRouteApproval)
const unregisterContextGuard = contextTransitionGuards.register(mayLeave,inputSnapshot)
function beforeUnload(event: BeforeUnloadEvent) { if (writer.unresolved() || hasDraft.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed=true;clearRouteApproval();removeAfterRoute();removeRouteError();unregisterContextGuard(); writer.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ prepare, hasUnresolved: writer.unresolved, inputSnapshot, cancelRead:writer.cancelRead, retryRead:writer.retryRead })
</script>
<template>
  <el-drawer v-model="open" title="新建手工事件登记" size="820px" class="manual-incident-editor" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" title="用于登记电话报障或现场巡检发现的异常事件；记录与自动监控告警独立存放，请勿输入账号密码或敏感凭据。" />
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前授权已失效，原范围输入已清除" />
    <template v-else>
      <ReadRecoveryActions :busy="state.busy" :can-retry="writer.canRetryRead()" :scope-key="contextKey" label="手工登记" :cancel="writer.cancelRead" :retry="writer.retryRead" />
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="提交结果待确认：系统已锁定当前填写内容与关联资产版本，请点击“重试提交”继续处理。" />
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="提交未通过，表单内容已保留。请核对关联资产版本并采用最新基准后重新提交。" />
      <div class="manual-actions"><el-button v-if="state.asset" :disabled="Boolean(state.busy) || confirming || !permitted" @click="writer.refreshAsset()">刷新关联资产</el-button><el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy) || confirming || !permitted" @click="retry">重试提交</el-button><el-button v-if="state.editing && state.asset && !writer.unresolved()" :disabled="Boolean(state.busy) || confirming || !permitted" @click="adopt">采用最新资产版本</el-button></div>
      <p v-if="state.asset">当前关联资产：{{ state.asset.name }} · v{{ state.asset.version }}<small class="manual-asset-id">{{ state.asset.id }}</small></p>
      <p v-if="state.lastAssetRead">最新读取版本：{{ state.lastAssetRead.name }} · v{{ state.lastAssetRead.version }}（刷新不会自动替换已选版本基准）</p>
      <el-alert v-if="state.asset && state.lastAssetRead && state.asset.version !== state.lastAssetRead.version" type="warning" :closable="false" title="检测到关联资产版本已更新，请先点击“采用最新资产版本”确认后再提交。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="'登记成功回执：' + state.receipt.id + ' · 人工登记记录'" />
      <p v-if="state.receipt">{{ state.verifiedReceipt ? '已核对登记记录，数据一致' : '已收到登记回执，可点击右侧按钮核对详情，请勿重复新建' }}<el-button text :disabled="Boolean(state.busy) || confirming || !permitted" @click="writer.refreshReceipt()">核对登记记录</el-button></p>
      <el-form v-if="state.editing" label-position="top" @submit.prevent="save">
        <el-form-item label="事件标题（1–200 字符）"><el-input v-model="form.title" :disabled="locked" aria-label="手工事件原始标题" /></el-form-item>
        <el-form-item label="严重级别"><el-select v-model="form.severity" :disabled="locked" placeholder="请选择严重级别" aria-label="手工事件严重级别"><el-option v-for="(label, value) in manualSeverityLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item>
        <h3>关联资产（当前租户）</h3><p class="field-help">支持按资产名称或管理地址检索，选择后将锁定当前资产版本快照。</p>
        <div class="manual-asset-search"><el-input ref="queryInput" v-model="query" :disabled="locked" aria-label="手工登记关联资产搜索" placeholder="输入资产名称或管理地址检索" /><el-button :disabled="locked || query.length > 100" @click="writer.search(query)">查询资产</el-button><el-button :disabled="locked || !state.nextAssetCursor || query !== state.assetSearch" @click="writer.search(query, true)">下一页</el-button></div>
        <p class="field-help">当前候选对应已应用查询：{{ state.assetSearch || '全部资产' }}{{ state.assetPaged ? '（后续页）' : '（首页）' }}。</p>
        <p v-if="queryDirty" role="status">搜索文字尚未应用；原读取恢复仍使用已应用条件，请先查询或<el-button :disabled="locked" @click="restoreAppliedQuery">恢复已应用查询</el-button>后再选择资产。</p>
        <p v-if="!state.choices" class="field-help">候选资产尚未成功读取，不能视为没有可选资产</p><p v-else-if="!state.choices.length">当前条件下无匹配资产</p>
        <div class="manual-asset-options"><button v-for="item in state.choices || []" :key="item.id" type="button" :disabled="locked || queryDirty" :aria-pressed="state.asset?.id === item.id" @click="chooseAsset(item.id)"><strong>{{ item.name }}</strong><span>v{{ item.version }} · 点击选择此资产</span><small>{{ item.id }}</small></button></div>
        <p v-if="validation" role="alert" class="manual-validation">{{ validation }}</p><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.asset || !state.lastAssetRead || state.asset.version !== state.lastAssetRead.version">提交手工登记</el-button>
      </el-form>
    </template>
    <template #footer><el-button @click="open = false">暂时收起</el-button><p class="field-help">收起窗口不会中断已发起的提交请求；切换租户或会话失效将清空未提交草稿。</p></template>
  </el-drawer>
</template>
<style>
.manual-incident-editor{max-width:100vw}.manual-incident-editor .el-button{min-height:44px;height:auto;white-space:normal}.manual-asset-options button{min-height:44px}.manual-asset-options button:focus-visible{outline:3px solid var(--el-color-primary);outline-offset:3px}.manual-incident-editor .el-alert{margin:12px 0}.manual-incident-editor .el-select{width:100%}.manual-incident-editor .manual-actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.manual-asset-id{display:block;overflow-wrap:anywhere}.manual-asset-search{display:flex;gap:8px;margin:12px 0}.manual-asset-options{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:14px 0}.manual-asset-options button{display:flex;flex-direction:column;gap:7px;text-align:left;padding:12px;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary);overflow-wrap:anywhere;cursor:pointer}.manual-asset-options button[aria-pressed=true]{border-color:var(--el-color-primary)}.manual-validation{color:var(--el-color-danger)}
@media(max-width:600px){.manual-incident-editor .el-drawer__body{padding:14px}.manual-asset-search{flex-wrap:wrap}.manual-asset-options{grid-template-columns:1fr}.manual-incident-editor .el-button{min-height:44px;height:auto;white-space:normal}.manual-incident-editor .el-input__inner{font-size:16px}}
</style>
