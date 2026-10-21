<script setup lang="ts">
import { ElMessageBox, ElTableColumn } from 'element-plus'
import AssetImportTemplateDownload from './AssetImportTemplateDownload.vue'
import AssetImportRawPreview from './AssetImportRawPreview.vue'
import CmdbSourcePreflightPanel from './CmdbSourcePreflightPanel.vue'
import { computed, onUnmounted, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { feedback as ElMessage } from '../utils/feedback'
import type { AssetImportTarget } from '../api/assetImportTypes'
import { contextGeneration, session } from '../stores/session'
import { assetImportTargets, assetImportUnaddressedColumns } from '../utils/assetImportMapping'
import { createAssetImportWorkspace } from '../utils/assetImportWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import AppTable from './AppTable.vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const workspace = createAssetImportWorkspace()
const { file, format, source, artifact, operation, policy, policyLoading, policyError, permitted, busy, error, uploadError, parseError, mappingError, fields, selectionMessage, selection, mappingDraft,
  uploadAttempt, parseAttempt, mappingAttempt, hasUnresolved, canReset, isParsed, selectionLocked, mappingLocked } = workspace
const unaddressed = computed(() => artifact.value && mappingDraft.value ? assetImportUnaddressedColumns(artifact.value.columns, mappingDraft.value) : [])
const conflict = computed(() => parseAttempt.value?.state === 'CONFLICT' ? { kind: 'parse' as const, attempt: parseAttempt.value } : mappingAttempt.value?.state === 'CONFLICT' ? { kind: 'mapping' as const, attempt: mappingAttempt.value } : null)
const stage = computed(() => source.value?.mapping ? 4 : isParsed.value ? 3 : source.value?.parse_status === 'SELECTION_REQUIRED' || parseAttempt.value?.state === 'ACCEPTED' ? 2 : source.value?.storage_status === 'READY' ? 1 : 0)
const unsaved = computed(() => Boolean(file.value && !source.value?.mapping))
let disposed = false, confirming = false
const contextKey = () => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version, session.value?.account_version, permitted.value])
const inputKey = () => JSON.stringify([source.value?.id, source.value?.version, selection.value, mappingDraft.value])
function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement, selected = input.files?.[0]; input.value = ''
  if (selected) workspace.chooseFile(selected)
}
function updateTarget(target: AssetImportTarget, value: unknown) {
  if (!mappingDraft.value || mappingLocked.value) return
  if (value === 'UNMAPPED') mappingDraft.value.targets[target] = null
  else if (typeof value === 'number') mappingDraft.value.targets[target] = value
}
function updateHeader(value: string | number) { selection.value.headerRow = value === '' ? null : Number(value) }
watch(() => [open.value, contextGeneration.value, session.value], () => { if (open.value && !policy.value) void workspace.loadPolicy() }, { immediate: true })
watch(permitted, value => { if (!value) open.value = false })
async function mayLeave() {
  if (hasUnresolved.value) { ElMessage.warning('导入请求状态待确认，请先刷新状态或重试后再离开页面。'); return false }
  if (!unsaved.value) return true
  if (disposed || confirming) return false
  const scope = contextKey(), input = inputKey(), original = file.value
  confirming = true
  try {
    await ElMessageBox.confirm('离开将清除当前选中的源文件、解析配置和未保存的字段映射；服务端已受理的记录仍会保留。', '尚未完成的来源输入', { type: 'warning', confirmButtonText: '舍弃并离开', cancelButtonText: '继续编辑' })
    // Approval is not cleanup: a later navigation/context guard may still refuse departure.
    return !disposed && !hasUnresolved.value && scope === contextKey() && input === inputKey() && original === file.value
  } catch { return false } finally { confirming = false }
}
onBeforeRouteLeave(mayLeave)
const unregisterContextGuard = contextTransitionGuards.register(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (hasUnresolved.value || unsaved.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onUnmounted(() => { disposed = true; unregisterContextGuard(); window.removeEventListener('beforeunload', beforeUnload); workspace.dispose() })
</script>

<template>
  <el-drawer v-model="open" title="资产台账批量导入" size="940px" class="asset-import-drawer" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" title="本工作区仅暂存源文件、解析和保存旧 15 列映射，正式批量入库尚未开放。客户 33 列字典校验独立进行，不自动转换为旧模板。" />
    <AssetImportTemplateDownload />
    <el-steps class="asset-import-steps" :active="stage" simple aria-label="资产来源处理阶段"><el-step title="上传源文件"/><el-step title="配置解析"/><el-step title="表头预览"/><el-step title="字段映射"/><el-step title="待策略入库"/></el-steps>
    <el-skeleton v-if="policyLoading" :rows="3" animated aria-label="读取来源技术政策" />
    <ApiErrorNotice :error="policyError"><el-button size="small" :disabled="policyLoading" @click="workspace.loadPolicy">重新读取解析规范</el-button></ApiErrorNotice>
    <el-empty v-if="!permitted" description="当前账号无资产批量导入权限" />
    <template v-else>
      <section class="asset-import-section">
        <h3>1. 源文件上传与受理</h3>
        <p v-if="policy" class="field-help tabular-nums">支持 CSV / XLSX 格式文件：单文件大小上限 {{ (policy.max_source_bytes / 1024 / 1024).toLocaleString() }} MiB，最多支持 {{ policy.max_data_rows }} 数据行、{{ policy.max_source_columns }} 源列及 {{ policy.max_sheets }} 个工作表。</p>
        <p class="field-help">上传后系统将对源文件计算 SHA-256 摘要并执行结构合法性校验。</p>
        <input type="file" class="asset-import-file-input" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :disabled="!policy || Boolean(busy || uploadAttempt || source)" aria-label="选择资产导入文件" @change="chooseFile">
        <p v-if="selectionMessage" class="import-error">{{ selectionMessage }}</p>
        <p v-if="file" class="mono tabular-nums">{{ file.name }} · {{ file.size.toLocaleString() }} bytes · 格式 {{ format }}</p>
        <el-empty v-else-if="policy" :image-size="60" description="请选择待导入的 CSV 或 XLSX 文件" />
        <div class="asset-import-actions">
          <el-button type="primary" :loading="busy === 'upload'" :disabled="!policy || !file || Boolean(busy || source) || Boolean(uploadAttempt && uploadAttempt.state !== 'UNCERTAIN')" @click="workspace.upload">{{ uploadAttempt?.state === 'UNCERTAIN' ? '重试上传源文件' : '上传并受理源文件' }}</el-button>
          <el-button v-if="file" :disabled="!canReset" @click="workspace.reset">重新选择文件</el-button>
        </div>
        <ApiErrorNotice :error="uploadError" />
        <p v-if="uploadAttempt?.state === 'UNCERTAIN'" class="import-error">上传状态待确认：已锁定当前文件与请求参数，请点击“重试上传源文件”继续处理。</p>
        <p v-if="uploadAttempt?.state === 'ACCEPTED'" class="field-help">源文件已受理，正在完成后台存储校验，请点击“刷新处理进度”查看状态。</p>
        <p v-if="uploadAttempt?.state === 'REJECTED' || uploadAttempt?.state === 'CONFLICT'" class="import-error">源文件上传未通过校验，请核对上方提示后点击“重新选择文件”。</p>
      </section>

      <section v-if="source || operation || uploadAttempt?.sourceId" class="asset-import-section">
        <h3>2. 存储状态与解析配置</h3>
        <p v-if="source" class="tabular-nums">存储状态：{{ source.storage_status }} · 解析状态：{{ source.parse_status }} · 来源版本 v{{ source.version }}</p>
        <p v-if="source" class="mono">来源编号 {{ source.id }}<br>SHA-256 {{ source.sha256 }}</p>
        <p v-if="operation" class="field-help">{{ operation.kind }}：{{ operation.status }} / {{ operation.step }}<template v-if="operation.error"> · {{ operation.error.message }}</template></p>
        <div class="asset-import-actions"><el-button :loading="busy === 'read'" :disabled="Boolean(busy)" @click="workspace.refresh">刷新处理进度</el-button><span v-if="hasUnresolved" class="field-help">当前存在待确认请求，请先完成核对或重试后再切换文件。</span></div>
        <ApiErrorNotice :error="error" />
        <el-alert v-if="source?.storage_status === 'PENDING'" type="info" :closable="false" title="源文件正在写入受控存储，请稍后点击“刷新处理进度”查看最新状态。" />
        <el-alert v-if="source?.storage_status === 'FAILED'" type="error" :closable="false" title="源文件存储失败，请查看操作详情中的错误说明。" />
        <el-alert v-if="source?.parse_status === 'FAILED'" type="error" :closable="false" title="源文件解析未通过（为保障数据准确性，包含计算公式的 XLSX 单元格将被安全拦截，请转为纯文本数值后重新上传）。" />
        <template v-if="source?.storage_status === 'READY' && !source.mapping">
          <el-button v-if="format === 'XLSX' && !artifact" :loading="busy === 'parse'" :disabled="selectionLocked" @click="workspace.parse(true)">读取工作表目录</el-button>
          <p v-if="format === 'XLSX'" class="field-help">先读取工作表目录，再选择目标工作表与表头所在行。</p>
          <el-form label-position="top" class="asset-import-selection" :disabled="selectionLocked">
            <el-form-item v-if="format === 'CSV'" label="CSV 字符编码"><el-select v-model="selection.encoding" placeholder="请选择字符编码" aria-label="CSV编码"><el-option v-for="value in policy?.csv_encodings || []" :key="value" :label="value" :value="value"/></el-select></el-form-item>
            <el-form-item v-if="format === 'CSV'" label="字段分隔符"><el-select v-model="selection.delimiter" placeholder="请选择分隔符" aria-label="CSV分隔符"><el-option label="逗号 (,)" value=","/><el-option label="分号 (;)" value=";"/><el-option label="制表符 (TAB)" :value="'\t'"/></el-select></el-form-item>
            <el-form-item v-if="format === 'XLSX'" label="目标工作表（索引从 0 开始）"><el-select v-model="selection.sheetIndex" placeholder="先读取目录，再选择" aria-label="XLSX工作表"><el-option v-for="sheet in artifact?.sheets || []" :key="sheet.index" :value="sheet.index" :label="`${sheet.index}: ${sheet.name}${sheet.hidden ? ' · 隐藏' : ''}`"/></el-select></el-form-item>
            <el-form-item label="表头行号（从 1 开始）"><el-input type="number" :model-value="selection.headerRow ?? ''" :min="1" :max="policy?.max_header_row" placeholder="请输入表头行号" aria-label="表头行" @update:model-value="updateHeader"/></el-form-item>
          </el-form>
          <p v-for="(message, field) in fields" :key="field" class="import-error">{{ message }}</p>
          <el-button type="primary" :loading="busy === 'parse'" :disabled="Boolean(busy) || parseAttempt?.state === 'ACCEPTED' || parseAttempt?.state === 'CONFLICT' || source.parse_status === 'PENDING' || format === 'XLSX' && !artifact" @click="workspace.parse()">{{ parseAttempt?.state === 'UNCERTAIN' ? '重试文件解析' : '执行文件解析' }}</el-button>
          <ApiErrorNotice :error="parseError" />
          <p v-if="parseAttempt?.state === 'UNCERTAIN'" class="import-error">解析请求状态待确认：已锁定当前解析参数与版本，请点击“重试文件解析”继续处理。</p>
          <p v-if="parseAttempt?.state === 'ACCEPTED'" class="field-help">解析任务已受理，请点击“刷新处理进度”获取解析结果。</p>
        </template>
      </section>

      <section v-if="conflict" class="asset-import-section">
        <el-alert type="warning" :closable="false" title="检测到版本冲突：请先刷新最新来源记录并核对请求参数差异后，再准备新请求。" />
        <p class="tabular-nums">请求基准版本 v{{ conflict.attempt.body.expected_version }} · 当前最新版本 v{{ source?.version }}</p>
        <p class="mono">来源编号 {{ conflict.attempt.sourceId }} · 操作编号 {{ conflict.attempt.operationId ?? '不适用' }} · 回执标识 {{ conflict.attempt.receiptId ?? '未返回' }}</p>
        <p v-if="source?.parsed_artifact" class="mono">当前解析摘要 {{ source.parsed_artifact.parse_hash }}</p>
        <details><summary>查看锁定的请求参数</summary><pre class="asset-import-request">{{ JSON.stringify(conflict.attempt.body, null, 2) }}</pre></details>
        <div class="asset-import-actions"><el-button :disabled="Boolean(busy)" @click="workspace.refresh">刷新当前版本</el-button><el-button :disabled="Boolean(busy) || !conflict.attempt.compared" @click="workspace.reviseConflict(conflict.kind)">已核对差异，重新配置</el-button></div>
      </section>

      <section v-if="artifact" class="asset-import-section">
        <h3>3. 源文件表头解析结果</h3>
        <p>状态：{{ artifact.state }} · 识别格式：{{ artifact.detected_format }} · 日期基准：{{ artifact.date_epoch }}</p>
        <p class="field-help">解析引擎 {{ artifact.parser_version }} · 资源规范 {{ artifact.resource_policy_version }} · 结构版本 {{ artifact.schema_version }}</p>
        <p v-if="artifact.state === 'SELECTION_REQUIRED'" class="field-help">工作表目录已加载，请在上方选择目标工作表与表头行号后执行解析。</p>
        <AppTable v-if="artifact.columns.length" :rows="artifact.columns.slice()" row-key="index">
          <el-table-column label="源列序号" width="100" prop="index"/>
          <el-table-column label="源文件表头" min-width="190"><template #default="{ row }">{{ row.header === null ? '空表头' : row.header }}<span class="cell-sub">{{ row.target_suggestions.length ? `匹配标准字段建议：${row.target_suggestions.join(' / ')}` : '请手动指定映射关系' }}</span></template></el-table-column>
        </AppTable>
      </section>

      <CmdbSourcePreflightPanel v-if="isParsed && source && artifact" :source="source" />
      <section v-if="isParsed && mappingDraft && source && artifact" class="asset-import-section">
        <h3>4. 标准 15 列字段映射与数据预览</h3>
        <p class="field-help">请为每个平台标准字段指定对应的源列或选择“不映射（留空）”。其中“备注”为必映射字段，同一源列不可重复绑定；未绑定的其余源列请勾选“忽略源列”以确认跳过。</p>
        <div class="asset-import-mapping-grid">
          <div v-for="target in assetImportTargets" :key="target.code" class="asset-import-map-row">
            <label>{{ target.label }} <span class="mono">{{ target.code }}</span></label>
            <el-select :model-value="mappingDraft.targets[target.code] === null ? 'UNMAPPED' : mappingDraft.targets[target.code]" :disabled="mappingLocked" :aria-label="`映射${target.label}`" @update:model-value="updateTarget(target.code, $event)">
              <el-option label="待选择" value="UNDECIDED" disabled/>
              <el-option label="不映射（留空）" value="UNMAPPED" :disabled="target.alwaysMapped"/>
              <el-option v-for="column in artifact.columns" :key="column.index" :label="`${column.index}: ${column.header === null ? '无表头' : column.header}`" :value="column.index"/>
            </el-select>
          </div>
        </div>
        <p v-if="unaddressed.length" class="import-error">尚未指定映射或忽略的源列：{{ unaddressed.map(column => `${column.index}: ${column.header ?? '无表头'}`).join('、') }}。</p>
        <el-checkbox-group v-model="mappingDraft.ignored" :disabled="mappingLocked"><el-checkbox v-for="column in artifact.columns" :key="column.index" :value="column.index" :disabled="Object.values(mappingDraft.targets).includes(column.index)">忽略源列 {{ column.index }}：{{ column.header ?? '无表头' }}</el-checkbox></el-checkbox-group>
        <p v-for="(message, field) in fields" :key="field" class="import-error">{{ message }}</p>
        <ApiErrorNotice :error="mappingError" />
        <p v-if="mappingAttempt?.state === 'UNCERTAIN'" class="import-error">映射保存状态待确认：已锁定当前映射配置，请点击“重试保存映射”继续处理。</p>
        <p v-if="mappingAttempt?.state === 'ACCEPTED'" class="field-help">映射保存请求已受理，请点击“刷新处理进度”确认最新状态。</p>
        <el-button type="primary" :loading="busy === 'mapping'" :disabled="Boolean(busy || source.mapping) || mappingAttempt?.state === 'ACCEPTED' || mappingAttempt?.state === 'CONFLICT'" @click="workspace.saveMapping">{{ mappingAttempt?.state === 'UNCERTAIN' ? '重试保存映射' : '保存 15 列映射' }}</el-button>
        <el-alert v-if="source.mapping && mappingAttempt?.state === 'COMPLETE'" type="success" :closable="false" :title="`字段映射已保存（版本 v${source.mapping.version} · 待入库校验策略启用）`" />
        <p v-if="source.mapping" class="mono">解析摘要 {{ source.mapping.source_parse_hash }}<br>预览摘要 {{ source.mapping.preview_hash }}</p>
        <AssetImportRawPreview :artifact="artifact" />
      </section>

      <section v-if="source" class="asset-import-section"><h3>5. 批量入库策略状态</h3>
        <el-alert type="info" :closable="false" title="正式批量入库未开放，不代表仅启用配置即可完成"/>
        <ul aria-label="当前来源实际完成情况"><li>源文件归档：{{source.storage_status==='READY'?'已完成校验':source.storage_status==='FAILED'?'失败':'处理中'}}</li><li>表头与数据解析：{{isParsed?'已解析':source.parse_status==='FAILED'?'失败':source.parse_status==='SELECTION_REQUIRED'?'待选择工作表与表头':'尚未完成'}}</li><li>旧 15 列映射：{{source.mapping?'已保存':'尚未保存'}}</li></ul>
        <p class="field-help">仍需完成引用与地址策略、配额及事务入库链路，并核验 Q20 / Q31 等未确认规则。本工作区不执行正式资产入库；来源检查或映射保存都不是导入批准。</p>
      </section>
    </template>
  </el-drawer>
</template>

<style>
.asset-import-drawer{max-width:100vw}.tabular-nums{font-variant-numeric:tabular-nums}.asset-import-steps{margin:16px 0}.asset-import-section{margin:18px 0;padding-top:14px;border-top:1px solid #e4eaf2}.asset-import-section h3{font-size:15px;margin:0 0 10px}.asset-import-section p{overflow-wrap:anywhere}.asset-import-file-input{display:block;width:100%;box-sizing:border-box;padding:10px 12px;margin:8px 0;border:1px dashed #b8c7d9;border-radius:8px;background:#f8fafc;cursor:pointer;transition:border-color .15s,background-color .15s}.asset-import-file-input:hover:not(:disabled){border-color:#2563eb;background:#f0f6ff}.asset-import-file-input:disabled{cursor:not-allowed;opacity:.65}.asset-import-selection{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:14px 0}.asset-import-selection .el-form-item{margin-bottom:0}.asset-import-mapping-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.asset-import-map-row{display:grid;grid-template-columns:minmax(120px,1fr) minmax(170px,1.4fr);gap:8px;align-items:center;font-size:12px;padding:8px 10px;background:#f8fafc;border:1px solid #edf2f7;border-radius:6px}.asset-import-map-row label{overflow-wrap:anywhere;font-weight:500;color:#334155}.asset-import-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:12px 0}.import-error{color:#9b3b1b;font-size:12px;overflow-wrap:anywhere}.asset-import-cell{display:block;margin:3px 0;white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px}.asset-import-cell b{display:inline-block;min-width:20px}.asset-import-request{font-size:11px;white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f7fb;padding:10px;border-radius:6px}.asset-import-drawer .el-checkbox{display:flex;margin:7px 0;white-space:normal;height:auto}.asset-import-drawer .el-checkbox__label{white-space:normal;overflow-wrap:anywhere}.asset-import-drawer .el-alert{margin:10px 0}.asset-import-drawer .el-button{min-height:44px;height:auto;white-space:normal}
@media(max-width:760px){.asset-import-selection,.asset-import-mapping-grid{grid-template-columns:minmax(0,1fr)}.asset-import-map-row{grid-template-columns:minmax(100px,1fr) minmax(145px,1.4fr)}.asset-import-drawer .el-button{min-height:44px;height:auto;white-space:normal}.asset-import-steps{padding:10px 12px;flex-direction:column;align-items:stretch;gap:6px}.asset-import-steps .el-step.is-simple{flex:none!important;max-width:none!important;width:100%;min-height:44px}.asset-import-steps .el-step.is-simple .el-step__main{justify-content:flex-start}.asset-import-steps .el-step.is-simple .el-step__title{max-width:none;font-size:14px;line-height:1.5;word-break:normal;overflow-wrap:normal}.asset-import-steps .el-step.is-simple .el-step__arrow{display:none}.asset-import-drawer .el-checkbox{min-height:44px;align-items:center}.asset-import-drawer .el-select__wrapper{min-height:44px}}
</style>
