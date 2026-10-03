<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createManualIncidentWriter, emptyManualIncidentWriteState, type ManualIncidentWriteState } from '../utils/manualIncidentWriter'
import { manualSeverityLabels, type ManualSeverity, type ManualIncidentEntry } from '../utils/manualIncidentReader'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [value: ManualIncidentEntry] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const state = shallowRef<ManualIncidentWriteState>(emptyManualIncidentWriteState()), writer = createManualIncidentWriter(api, value => { state.value = value })
const permitted = computed(() => Boolean(session.value && ['msp', 'padmin'].includes(session.value.user.role) && can('manual_incidents:write') && !switchingTenant.value))
const form = reactive({ title: '', severity: '' }), query = ref(''), validation = ref('')
const locked = computed(() => !permitted.value || Boolean(state.value.busy) || Boolean(state.value.attempt))
const hasDraft = computed(() => state.value.editing && Boolean(form.title || form.severity || state.value.asset))
async function confirmDiscard() { if (!hasDraft.value) return true; try { await ElMessageBox.confirm('将离开或替换当前尚未发送的手工登记内容，是否继续？', '保留未提交内容', { type: 'warning', confirmButtonText: '确认继续', cancelButtonText: '保留当前内容' }); return true } catch { return false } }
async function prepare() { if (!permitted.value) return false; if (writer.unresolved() || state.value.busy) { open.value = true; ElMessage.warning('当前结果尚未确认，请先处理原请求'); return false } const generation = contextKey.value; if (!await confirmDiscard() || generation !== contextKey.value || !permitted.value || !writer.begin()) return false; form.title = ''; form.severity = ''; query.value = ''; validation.value = ''; open.value = true; await writer.search(''); return true }
async function submitted() { if (state.value.receipt) { ElMessage.success('手工事实已登记，尚未完成正式处置或建单'); emit('saved', state.value.receipt) } }
async function save() { if (locked.value) return; validation.value = ''; try { if (!form.severity || !state.value.asset) throw new Error('请明确选择严重级别并读取关联资产，不会补默认值'); if (await writer.submit({ title: form.title, severity: form.severity as ManualSeverity })) await submitted() } catch (error) { validation.value = error instanceof Error ? error.message : '请核对原始输入' } }
async function retry() { if (await writer.retry()) await submitted() }
async function adopt() { if (writer.unresolved() || state.value.busy) return; const generation = contextKey.value; if (!await writer.refreshAsset() || !state.value.lastAssetRead) return; const latest = state.value.lastAssetRead; try { await ElMessageBox.confirm(`确认采用资产“${latest.name}”的v${latest.version}作为新基准？当前标题与级别保持不变，之后仍需明确提交。`, '核对关联资产最新版本', { confirmButtonText: '采用此基准', cancelButtonText: '保持原基准', type: 'warning' }); if (generation === contextKey.value && permitted.value) writer.adoptLatestAsset(latest) } catch { /* Keep original baseline and all input. */ } }
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => { const s = session.value; writer.reset(permitted.value && s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null); form.title = ''; form.severity = ''; query.value = ''; validation.value = '' }, { immediate: true, flush: 'sync' })
async function mayLeave() { if (writer.unresolved()) { ElMessage.warning('登记结果尚未确认，请先使用完全相同的原请求重试'); return false } return confirmDiscard() }
onBeforeRouteLeave(mayLeave)
const unregisterContextGuard = contextTransitionGuards.register(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (writer.unresolved() || hasDraft.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { unregisterContextGuard(); writer.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
defineExpose({ prepare, hasUnresolved: writer.unresolved })
</script>
<template>
  <el-drawer v-model="open" title="登记手工来源事件" size="820px" class="manual-incident-editor" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" title="仅登记电话、现场等手工来源事实；不自动建单、不补优先级/SLA，也不根据监控正常自动恢复。请勿输入密码或token。" />
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前授权已失效，原范围输入已清除" />
    <template v-else>
      <ApiErrorNotice :error="state.error" />
      <el-alert v-if="state.attempt?.state === 'uncertain'" type="warning" :closable="false" title="提交结果尚未确认。原内容、关联资产、版本和幂等键已保留，请勿刷新浏览器；只读查询不会自动重发。" />
      <el-alert v-if="state.attempt?.state === 'rejected'" type="warning" :closable="false" title="请求被拒绝，未自动重试。当前输入保留，请核对关联资产后明确采用基准，再提交新请求。" />
      <div class="manual-actions"><el-button v-if="state.asset" :disabled="Boolean(state.busy)" @click="writer.refreshAsset()">只读刷新关联资产</el-button><el-button v-if="state.attempt?.state === 'uncertain'" type="warning" :disabled="Boolean(state.busy)" @click="retry">使用完全相同原请求重试</el-button><el-button v-if="state.editing && state.asset && !writer.unresolved()" :disabled="Boolean(state.busy)" @click="adopt">读取并明确采用最新资产基准</el-button></div>
      <p v-if="state.asset">固定关联基准：{{ state.asset.name }} · v{{ state.asset.version }}<small class="manual-asset-id">{{ state.asset.id }}</small></p>
      <p v-if="state.lastAssetRead">最近只读所见：{{ state.lastAssetRead.name }} · v{{ state.lastAssetRead.version }}。刷新不替换固定基准</p>
      <el-alert v-if="state.asset && state.lastAssetRead && state.asset.version !== state.lastAssetRead.version" type="warning" :closable="false" title="已发现资产新版本，旧基准的新提交已阻止。原请求重试仍使用原内容；不得自动改版本重发。" />
      <el-alert v-if="state.receipt" type="success" :closable="false" :title="'确认登记回执：' + state.receipt.id + ' · 手工来源 · 仅登记，自述未核验'" />
      <p v-if="state.receipt">{{ state.verifiedReceipt ? '详情已只读核对，原登记一致' : '已有确认成功回执，后续详情尚未成功核对；不要新建重复登记' }}<el-button text :disabled="Boolean(state.busy)" @click="writer.refreshReceipt()">只读核对原登记</el-button></p>
      <el-form v-if="state.editing" label-position="top" @submit.prevent="save">
        <el-form-item label="原始标题（1至200字符，保留原文与空格）"><el-input v-model="form.title" :disabled="locked" aria-label="手工事件原始标题" /></el-form-item>
        <el-form-item label="明确严重级别（不默认、不等同工单优先级）"><el-select v-model="form.severity" :disabled="locked" placeholder="尚未选择" aria-label="手工事件严重级别"><el-option v-for="(label, value) in manualSeverityLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item>
        <h3>关联资产（当前授权租户）</h3><p class="field-help">按名称或管理地址查询，只列真实读取结果。选择时重新读取资产，固定其所见版本；不会沿用监控状态作为事件状态。</p>
        <div class="manual-asset-search"><el-input v-model="query" :disabled="locked" aria-label="手工登记关联资产搜索" placeholder="名称或管理地址，可留空" /><el-button :disabled="locked || query.length > 100" @click="writer.search(query)">只读查询资产</el-button><el-button :disabled="locked || !state.nextAssetCursor || query !== state.assetSearch" @click="writer.search(query, true)">下一页资产</el-button></div>
        <p v-if="!state.choices" class="field-help">候选资产尚未成功读取，不能视为没有可选资产</p><p v-else-if="!state.choices.length">本页没有匹配资产</p>
        <div class="manual-asset-options"><button v-for="item in state.choices || []" :key="item.id" type="button" :disabled="locked" :aria-pressed="state.asset?.id === item.id" @click="writer.choose(item.id)"><strong>{{ item.name }}</strong><span>v{{ item.version }} · 点击后重新核对</span><small>{{ item.id }}</small></button></div>
        <p v-if="validation" role="alert" class="manual-validation">{{ validation }}</p><el-button type="primary" native-type="submit" :loading="state.busy === 'write'" :disabled="locked || !state.asset || !state.lastAssetRead">明确提交手工登记</el-button>
      </el-form>
    </template>
    <template #footer><el-button @click="open = false">暂时收起，保留本页内容</el-button><p class="field-help">收起不撤销已发送请求；未确认结果须处理原请求。强制会话失效会清除受保护上下文。</p></template>
  </el-drawer>
</template>
<style>
.manual-incident-editor{max-width:100vw}.manual-incident-editor .el-alert{margin:12px 0}.manual-incident-editor .el-select{width:100%}.manual-incident-editor .manual-actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.manual-asset-id{display:block;overflow-wrap:anywhere}.manual-asset-search{display:flex;gap:8px;margin:12px 0}.manual-asset-options{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:14px 0}.manual-asset-options button{display:flex;flex-direction:column;gap:7px;text-align:left;padding:12px;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary);overflow-wrap:anywhere;cursor:pointer}.manual-asset-options button[aria-pressed=true]{border-color:var(--el-color-primary)}.manual-validation{color:var(--el-color-danger)}
@media(max-width:600px){.manual-incident-editor .el-drawer__body{padding:14px}.manual-asset-search{flex-wrap:wrap}.manual-asset-options{grid-template-columns:1fr}.manual-incident-editor .el-button{min-height:44px;height:auto;white-space:normal}.manual-incident-editor .el-input__inner{font-size:16px}}
</style>
