<script setup lang="ts">
import { computed, onUnmounted, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { AssetImportTarget } from '../api/assetImportTypes'
import { contextGeneration, session } from '../stores/session'
import { assetImportTargets, assetImportUnaddressedColumns } from '../utils/assetImportMapping'
import { createAssetImportWorkspace } from '../utils/assetImportWorkspace'
import ApiErrorNotice from './ApiErrorNotice.vue'
import AppTable from './AppTable.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const workspace = createAssetImportWorkspace()
const { file, format, source, artifact, operation, policy, policyLoading, policyError, permitted, busy, error, uploadError, parseError, mappingError, fields, selectionMessage, selection, mappingDraft,
  uploadAttempt, parseAttempt, mappingAttempt, hasUnresolved, canReset, isParsed, selectionLocked, mappingLocked } = workspace
const unaddressed = computed(() => artifact.value && mappingDraft.value ? assetImportUnaddressedColumns(artifact.value.columns, mappingDraft.value) : [])
const conflict = computed(() => parseAttempt.value?.state === 'CONFLICT' ? { kind: 'parse' as const, attempt: parseAttempt.value } : mappingAttempt.value?.state === 'CONFLICT' ? { kind: 'mapping' as const, attempt: mappingAttempt.value } : null)
const stage = computed(() => source.value?.mapping ? 4 : isParsed.value ? 3 : source.value?.parse_status === 'SELECTION_REQUIRED' || parseAttempt.value?.state === 'ACCEPTED' ? 2 : source.value?.storage_status === 'READY' ? 1 : 0)
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
onBeforeRouteLeave(() => {
  if (!hasUnresolved.value) return true
  ElMessage.warning('来源请求结果尚未确认；请先回读或使用原请求重试，再离开资产页面。')
  return false
})
onUnmounted(workspace.dispose)
</script>

<template>
  <el-drawer v-model="open" title="导入资产来源" size="940px" class="asset-import-drawer" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" title="保存原文件、选择解析参数、确认15列映射。实际资产写入仍待Q20/Q31确认。" />
    <el-steps class="asset-import-steps" :active="stage" simple aria-label="资产来源处理阶段"><el-step title="原文件"/><el-step title="明确解析"/><el-step title="原始结果"/><el-step title="保存映射"/><el-step title="业务待确认"/></el-steps>
    <el-skeleton v-if="policyLoading" :rows="3" animated aria-label="读取来源技术政策" />
    <ApiErrorNotice :error="policyError"><el-button size="small" :disabled="policyLoading" @click="workspace.loadPolicy">重新读取技术政策</el-button></ApiErrorNotice>
    <el-empty v-if="!permitted" description="当前身份没有资产来源技术处理权限" />
    <template v-else>
      <section class="asset-import-section">
        <h3>1. 原文件与受理</h3>
        <p v-if="policy" class="field-help">CSV/XLSX 原文件上限 {{ (policy.max_source_bytes / 1024 / 1024).toLocaleString() }}MiB；最多 {{ policy.max_data_rows }} 数据行、{{ policy.max_source_columns }} 源列、{{ policy.max_sheets }} 工作表。这些是技术保护上限。</p>
        <p class="field-help">文件声明不代表实际格式已验证。扫描 UNSCANNED · 生产许可 UNVERIFIED · 留存时间未确认。</p>
        <input type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :disabled="!policy || Boolean(busy || uploadAttempt || source)" aria-label="选择资产导入文件" @change="chooseFile">
        <p v-if="selectionMessage" class="import-error">{{ selectionMessage }}</p>
        <p v-if="file" class="mono">{{ file.name }} · {{ file.size.toLocaleString() }} bytes · 声明格式 {{ format }}</p>
        <el-empty v-else-if="policy" :image-size="60" description="请选择原始CSV或XLSX文件" />
        <div class="asset-import-actions">
          <el-button type="primary" :loading="busy === 'upload'" :disabled="!policy || !file || Boolean(busy || source) || Boolean(uploadAttempt && uploadAttempt.state !== 'UNCERTAIN')" @click="workspace.upload">{{ uploadAttempt?.state === 'UNCERTAIN' ? '原文件与原请求重试' : '受理原文件' }}</el-button>
          <el-button v-if="file" :disabled="!canReset" @click="workspace.reset">开始新的来源</el-button>
        </div>
        <ApiErrorNotice :error="uploadError" />
        <p v-if="uploadAttempt?.state === 'UNCERTAIN'" class="import-error">上传结果不确定。原 File、声明格式和幂等键保留，只有原请求可重试。</p>
        <p v-if="uploadAttempt?.state === 'ACCEPTED'" class="field-help">上传已受理，尚未确认存储完成。后续只查询操作与来源。</p>
        <p v-if="uploadAttempt?.state === 'REJECTED' || uploadAttempt?.state === 'CONFLICT'" class="import-error">原文件请求被明确拒绝。原文件仍保留，可在查看错误后开始新的来源。</p>
      </section>

      <section v-if="source || operation || uploadAttempt?.sourceId" class="asset-import-section">
        <h3>2. 存储与解析选择</h3>
        <p v-if="source">存储 {{ source.storage_status }} · 解析 {{ source.parse_status }} · 来源 v{{ source.version }}</p>
        <p v-if="source" class="mono">来源 {{ source.id }}<br>SHA-256 {{ source.sha256 }}</p>
        <p v-if="operation" class="field-help">{{ operation.kind }}：{{ operation.status }} / {{ operation.step }}<template v-if="operation.error"> · {{ operation.error.message }}</template></p>
        <div class="asset-import-actions"><el-button :loading="busy === 'read'" :disabled="Boolean(busy)" @click="workspace.refresh">回读操作与来源</el-button><span v-if="hasUnresolved" class="field-help">关闭抽屉会保留原请求；当前不能更换来源或离开页面。</span></div>
        <ApiErrorNotice :error="error" />
        <el-alert v-if="source?.storage_status === 'PENDING'" type="info" :closable="false" title="原文件仍在存储；READY并不代表已解析或已导入。" />
        <el-alert v-if="source?.storage_status === 'FAILED'" type="error" :closable="false" title="原文件存储失败；请查看操作失败原因。" />
        <el-alert v-if="source?.parse_status === 'FAILED'" type="error" :closable="false" title="原文件解析失败；存储结果仍保留。含公式的XLSX会被安全拒绝，不读取公式缓存。" />
        <template v-if="source?.storage_status === 'READY' && !source.mapping">
          <el-button v-if="format === 'XLSX' && !artifact" :loading="busy === 'parse'" :disabled="selectionLocked" @click="workspace.parse(true)">读取工作表目录</el-button>
          <p v-if="format === 'XLSX'" class="field-help">先读取工作表目录，再明确选择工作表与表头。隐藏或多个工作表也不会自动选择。</p>
          <el-form label-position="top" class="asset-import-selection" :disabled="selectionLocked">
            <el-form-item v-if="format === 'CSV'" label="CSV编码"><el-select v-model="selection.encoding" placeholder="必须明确选择" aria-label="CSV编码"><el-option v-for="value in policy?.csv_encodings || []" :key="value" :label="value" :value="value"/></el-select></el-form-item>
            <el-form-item v-if="format === 'CSV'" label="分隔符"><el-select v-model="selection.delimiter" placeholder="必须明确选择" aria-label="CSV分隔符"><el-option label="逗号 (,)" value=","/><el-option label="分号 (;)" value=";"/><el-option label="TAB" :value="'\t'"/></el-select></el-form-item>
            <el-form-item v-if="format === 'XLSX'" label="工作表（位置从0开始）"><el-select v-model="selection.sheetIndex" placeholder="先读取目录，再选择" aria-label="XLSX工作表"><el-option v-for="sheet in artifact?.sheets || []" :key="sheet.index" :value="sheet.index" :label="`${sheet.index}: ${sheet.name}${sheet.hidden ? ' · 隐藏' : ''}`"/></el-select></el-form-item>
            <el-form-item label="表头行（从1开始）"><el-input type="number" :model-value="selection.headerRow ?? ''" :min="1" :max="policy?.max_header_row" placeholder="必须明确" aria-label="表头行" @update:model-value="updateHeader"/></el-form-item>
          </el-form>
          <p v-for="(message, field) in fields" :key="field" class="import-error">{{ message }}</p>
          <el-button type="primary" :loading="busy === 'parse'" :disabled="Boolean(busy) || parseAttempt?.state === 'ACCEPTED' || parseAttempt?.state === 'CONFLICT' || source.parse_status === 'PENDING' || format === 'XLSX' && !artifact" @click="workspace.parse()">{{ parseAttempt?.state === 'UNCERTAIN' ? '原解析参数与原请求重试' : '使用明确选择解析' }}</el-button>
          <ApiErrorNotice :error="parseError" />
          <p v-if="parseAttempt?.state === 'UNCERTAIN'" class="import-error">解析结果不确定。来源 ID、原参数、expected_version 与幂等键保留；重试不会改用当前表单。</p>
          <p v-if="parseAttempt?.state === 'ACCEPTED'" class="field-help">解析已受理，后续只查询操作与来源。</p>
        </template>
      </section>

      <section v-if="conflict" class="asset-import-section">
        <el-alert type="warning" :closable="false" title="版本冲突：先回读当前来源，比较原请求后，手动准备新的请求。" />
        <p>原请求版本 {{ conflict.attempt.body.expected_version }} · 当前回读版本 {{ source?.version }}</p>
        <p class="mono">原来源 {{ conflict.attempt.sourceId }} · 原操作 {{ conflict.attempt.operationId ?? '不适用' }} · 原确认结果 {{ conflict.attempt.receiptId ?? '未返回结果ID' }}</p>
        <p v-if="source?.parsed_artifact" class="mono">当前解析 hash {{ source.parsed_artifact.parse_hash }}</p>
        <details><summary>比较保留的原请求参数</summary><pre class="asset-import-request">{{ JSON.stringify(conflict.attempt.body, null, 2) }}</pre></details>
        <div class="asset-import-actions"><el-button :disabled="Boolean(busy)" @click="workspace.refresh">回读当前版本</el-button><el-button :disabled="Boolean(busy) || !conflict.attempt.compared" @click="workspace.reviseConflict(conflict.kind)">已比较，准备新请求</el-button></div>
      </section>

      <section v-if="artifact" class="asset-import-section">
        <h3>3. 原始解析结果</h3>
        <p>状态 {{ artifact.state }} · 格式 {{ artifact.detected_format }} · 日期系统 {{ artifact.date_epoch }}</p>
        <p class="field-help">parser {{ artifact.parser_version }} · 资源政策 {{ artifact.resource_policy_version }} · schema {{ artifact.schema_version }}</p>
        <p v-if="artifact.state === 'SELECTION_REQUIRED'" class="field-help">已保存工作表目录；工作表或表头未明确，尚无数据行可映射。</p>
        <AppTable v-if="artifact.columns.length" :rows="artifact.columns" row-key="index">
          <el-table-column label="源列位置" width="100" prop="index"/>
          <el-table-column label="原始表头" min-width="190"><template #default="{ row }">{{ row.header === null ? '表头缺失' : row.header }}<span class="cell-sub">{{ row.target_suggestions.length ? `精确表头建议：${row.target_suggestions.join(' / ')}` : '无精确建议' }}</span></template></el-table-column>
        </AppTable>
      </section>

      <section v-if="isParsed && mappingDraft && source && artifact" class="asset-import-section">
        <h3>4. 15列映射与原始预览</h3>
        <p class="field-help">每个目标都须明确选择源列或“未映射”。备注必须映射；源列不得重复分配。没有使用的源列须逐项明确忽略，重复表头按源列位置区分。</p>
        <div class="asset-import-mapping-grid">
          <div v-for="target in assetImportTargets" :key="target.code" class="asset-import-map-row">
            <label>{{ target.label }} <span class="mono">{{ target.code }}</span></label>
            <el-select :model-value="mappingDraft.targets[target.code] === null ? 'UNMAPPED' : mappingDraft.targets[target.code]" :disabled="mappingLocked" :aria-label="`映射${target.label}`" @update:model-value="updateTarget(target.code, $event)">
              <el-option label="待明确" value="UNDECIDED" disabled/>
              <el-option label="未映射（明确null）" value="UNMAPPED" :disabled="target.alwaysMapped"/>
              <el-option v-for="column in artifact.columns" :key="column.index" :label="`${column.index}: ${column.header === null ? '无表头' : column.header}`" :value="column.index"/>
            </el-select>
          </div>
        </div>
        <p v-if="unaddressed.length" class="import-error">还未明确的源列：{{ unaddressed.map(column => `${column.index}: ${column.header ?? '无表头'}`).join('、') }}。</p>
        <el-checkbox-group v-model="mappingDraft.ignored" :disabled="mappingLocked"><el-checkbox v-for="column in artifact.columns" :key="column.index" :value="column.index" :disabled="Object.values(mappingDraft.targets).includes(column.index)">明确忽略源列 {{ column.index }}：{{ column.header ?? '无表头' }}</el-checkbox></el-checkbox-group>
        <p v-for="(message, field) in fields" :key="field" class="import-error">{{ message }}</p>
        <ApiErrorNotice :error="mappingError" />
        <p v-if="mappingAttempt?.state === 'UNCERTAIN'" class="import-error">映射结果不确定。来源 ID、原 body 与幂等键保留；只能使用原请求重试。</p>
        <p v-if="mappingAttempt?.state === 'ACCEPTED'" class="field-help">保存响应已收到，等待来源回读确认。后续只查询来源。</p>
        <el-button type="primary" :loading="busy === 'mapping'" :disabled="Boolean(busy || source.mapping) || mappingAttempt?.state === 'ACCEPTED' || mappingAttempt?.state === 'CONFLICT'" @click="workspace.saveMapping">{{ mappingAttempt?.state === 'UNCERTAIN' ? '原映射与原请求重试' : '保存15列映射' }}</el-button>
        <el-alert v-if="source.mapping && mappingAttempt?.state === 'COMPLETE'" type="success" :closable="false" :title="`映射已保存并回读 · v${source.mapping.version} · 预检 NOT_RUN`" />
        <p v-if="source.mapping" class="mono">解析 hash {{ source.mapping.source_parse_hash }}<br>预览 hash {{ source.mapping.preview_hash }}</p>
        <p class="field-help">原始数据 {{ artifact.rows.length }} 行，下方展示前10行。lexical/display/kind/style/format 与日期系统保留；这里没有批准日期、IP或业务字典转换。</p>
        <el-empty v-if="!artifact.rows.length" :image-size="60" description="解析结果没有数据行" />
        <AppTable v-else :rows="artifact.rows.slice(0, 10)" row-key="logical_record_number">
          <el-table-column label="逻辑记录 / 源行" min-width="130"><template #default="{ row }">{{ row.logical_record_number }} / {{ row.source_row_number }}<span class="cell-sub">物理行 {{ row.physical_start_line ?? '—' }} 至 {{ row.physical_end_line ?? '—' }}</span></template></el-table-column>
          <el-table-column label="原始单元格" min-width="480"><template #default="{ row }"><span v-for="cell in row.cells" :key="cell.column_index" class="asset-import-cell"><b>{{ cell.column_index }}</b> {{ cell.kind }} · lexical={{ cell.lexical === null ? 'null' : cell.lexical }} · display={{ cell.display === null ? 'null' : cell.display }} · style={{ cell.style_index ?? 'null' }} · format={{ cell.format_code ?? 'null' }}</span></template></el-table-column>
        </AppTable>
      </section>

      <section v-if="source" class="asset-import-section"><h3>5. 业务门禁</h3><el-alert type="warning" :closable="false" title="CONFIG_PENDING · can_import=false · Q20/Q31待确认"/><p class="field-help">有效规则版本与配额占用为 null；预检尚未运行。存储就绪、原始解析与映射保存不代表资产导入成功。</p></section>
    </template>
  </el-drawer>
</template>

<style>
.asset-import-drawer{max-width:100vw}.asset-import-steps{margin:16px 0}.asset-import-section{margin:18px 0;padding-top:12px;border-top:1px solid #e4eaf2}.asset-import-section h3{font-size:15px;margin:0 0 10px}.asset-import-section p{overflow-wrap:anywhere}.asset-import-selection{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:14px 0}.asset-import-selection .el-form-item{margin-bottom:0}.asset-import-mapping-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.asset-import-map-row{display:grid;grid-template-columns:minmax(120px,1fr) minmax(170px,1.4fr);gap:8px;align-items:center;font-size:12px}.asset-import-map-row label{overflow-wrap:anywhere}.asset-import-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:12px 0}.import-error{color:#9b3b1b;font-size:12px;overflow-wrap:anywhere}.asset-import-cell{display:block;margin:3px 0;white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px}.asset-import-cell b{display:inline-block;min-width:20px}.asset-import-request{font-size:11px;white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f7fb;padding:10px}.asset-import-drawer .el-checkbox{display:flex;margin:7px 0;white-space:normal;height:auto}.asset-import-drawer .el-checkbox__label{white-space:normal;overflow-wrap:anywhere}.asset-import-drawer .el-alert{margin:10px 0}
@media(max-width:760px){.asset-import-selection,.asset-import-mapping-grid{grid-template-columns:minmax(0,1fr)}.asset-import-map-row{grid-template-columns:minmax(100px,1fr) minmax(145px,1.4fr)}.asset-import-drawer .el-button{min-height:44px;height:auto;white-space:normal}.asset-import-steps{padding:10px 12px;flex-direction:column;align-items:stretch;gap:6px}.asset-import-steps .el-step.is-simple{flex:none;min-height:44px}.asset-import-steps .el-step__main{justify-content:flex-start}.asset-import-steps .el-step__title{max-width:none;font-size:14px;line-height:1.5;word-break:normal;overflow-wrap:normal}.asset-import-steps .el-step__arrow{display:none}.asset-import-drawer .el-checkbox{min-height:44px;align-items:center}.asset-import-drawer .el-select__wrapper{min-height:44px}}
</style>
