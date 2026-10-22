<script setup lang="ts">
import '../styles/adminPrototype.css'
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { displayTime } from '../utils/display'
import { createAuditWorkspace, emptyAuditState, type AuditFilters, type AuditRecord, type AuditState } from '../utils/auditWorkspace'
import AppTable from '../components/AppTable.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const ElTableColumn = ElementTableColumn<AuditRecord>
const state = shallowRef<AuditState>(emptyAuditState())
const workspace = createAuditWorkspace(api, value => { state.value = value })
const draft = reactive({ actor_id: '', action: '', result: '', resource_type: '', resource_id: '', trace_id: '', occurred_from: '', occurred_before: '' })
const listStatus=ref(''),proofStatus=ref(''),listNotice=ref<HTMLElement|null>(null),proofNotice=ref<HTMLElement|null>(null)
let focusEpoch=0
const pageSize = ref(15), from = ref('1'), to = ref(''), selected = ref<AuditRecord | null>(null), rangeError = ref('')
const permitted = computed(() => Boolean(session.value && can('audit:read') && !switchingTenant.value))
const fields: { key: keyof typeof draft; label: string; placeholder: string }[] = [
  { key: 'actor_id', label: '操作者ID（精确）', placeholder: '完整UUID，可留空' }, { key: 'action', label: '动作（精确）', placeholder: '例如 asset.create_draft' },
  { key: 'result', label: '结果（精确）', placeholder: '例如 SUCCEEDED / DENIED' }, { key: 'resource_type', label: '对象类型（精确）', placeholder: '例如 asset / worklog' },
  { key: 'resource_id', label: '对象ID（精确）', placeholder: '对象ID或配置key' }, { key: 'trace_id', label: '请求trace_id（精确）', placeholder: '完整trace_id' },
  { key: 'occurred_from', label: '开始时间（含，UTC）', placeholder: '2026-10-02T00:00:00Z' }, { key: 'occurred_before', label: '结束时间（不含，UTC）', placeholder: '2026-10-03T00:00:00Z' },
]
function filters(): AuditFilters { return Object.fromEntries(Object.entries(draft).filter(([, value]) => value !== '')) }
const unapplied = computed(() => JSON.stringify(filters()) !== JSON.stringify(state.value.filters) || pageSize.value !== state.value.pageSize)
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version,session.value?.user.role,session.value?.mode,permitted.value]))
watch(contextKey, () => {
  focusEpoch++;listStatus.value='';proofStatus.value=''
  for (const field of fields) draft[field.key] = ''
  pageSize.value = 15; from.value = '1'; to.value = ''; selected.value = null; rangeError.value = ''
  const current = session.value
  workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version } : null)
  if (permitted.value) void workspace.load()
}, { immediate: true, flush: 'sync' })
async function search() {focusEpoch++;listStatus.value='';selected.value = null; await workspace.load(filters(), pageSize.value) }
function resetFilters() { for (const field of fields) draft[field.key] = ''; pageSize.value = 15; void search() }
async function refresh() {focusEpoch++;listStatus.value='';selected.value = null; await workspace.load() }
async function nextPage() { if (unapplied.value) return;focusEpoch++;listStatus.value='';selected.value = null; await workspace.next() }
async function verifyRange() {
  focusEpoch++;proofStatus.value=''
  rangeError.value = ''
  if (!/^[1-9][0-9]{0,15}$/.test(from.value) || to.value !== '' && !/^[1-9][0-9]{0,15}$/.test(to.value)) { rangeError.value = '起止序号必须为安全正整数，结束可留空由服务器限定本批范围'; await workspace.verify(Number.NaN); return }
  await workspace.verify(Number(from.value), to.value === '' ? null : Number(to.value))
}
async function verifyNext() {
  const next = state.value.proof?.next_from_sequence
  if (!next || state.value.proof?.status !== 'VALID') return
  focusEpoch++;proofStatus.value='';from.value = String(next); to.value = ''; rangeError.value = ''; await workspace.verifyNext()
}
function toggleRecord(value: boolean) { if (!value) selected.value = null }
function chooseRecordRange(row: AuditRecord) { from.value = String(row.sequence); to.value = String(row.sequence); rangeError.value = ''; selected.value = { ...row } }
const proofTitle = computed(() => state.value.proof?.status === 'VALID' ? '所选区间在本次数据库快照中校验一致' : state.value.proof?.status === 'INVALID' ? '区间校验未通过，请保留证据并排查' : '当前快照区间暂无记录，未生成校验结果')
async function retryList(){focusEpoch++;listStatus.value='';selected.value=null;return workspace.retryList()}
async function retryProof(){focusEpoch++;proofStatus.value='';rangeError.value='';return workspace.retryProof()}
async function cancelList(){if(!workspace.cancelList())return false;const turn=++focusEpoch;selected.value=null;listStatus.value='已取消列表读取；可重试原查询参数，续页仍使用原快照上界。';await nextTick();if(turn===focusEpoch)listNotice.value?.focus();return true}
async function cancelProof(){if(!workspace.cancelProof())return false;const turn=++focusEpoch;proofStatus.value='已取消区间校验等待；没有修改审计记录。重试相同序号参数仍是一次新的数据库快照核验。';await nextTick();if(turn===focusEpoch)proofNotice.value?.focus();return true}
onUnmounted(() => {focusEpoch++;workspace.dispose()})
defineExpose({state,workspace,draft,pageSize,from,to,search,refresh,nextPage,verifyRange,verifyNext,retryList,retryProof,cancelList,cancelProof,listStatus,proofStatus})
</script>
<template>
  <section class="audit-page admin-prototype">
    <div class="compact-page-toolbar page-heading"><div><h1 class="page-title-sr">审计检索与区间校验</h1></div><el-button :loading="state.loading" :disabled="!permitted" @click="refresh">刷新列表</el-button></div>
        <el-empty v-if="!permitted" description="当前身份没有本租户审计权限" />
    <template v-else>
      
      <div class="panel">
        <el-form label-position="top" class="audit-filter-grid" @submit.prevent="search">
          <el-form-item v-for="field in fields.slice(0, 3)" :key="field.key" :label="field.label"><el-input v-model="draft[field.key]" clearable :aria-label="field.label" :placeholder="field.placeholder" /></el-form-item>
          <details class="audit-advanced"><summary>更多筛选</summary><div class="audit-advanced-grid"><el-form-item v-for="field in fields.slice(3)" :key="field.key" :label="field.label"><el-input v-model="draft[field.key]" clearable :aria-label="field.label" :placeholder="field.placeholder" /></el-form-item></div><p class="field-help">时间使用 UTC ISO-8601（如 2026-10-21T00:00:00Z），区间含开始、不含结束。</p></details>
          <el-form-item label="每页条数"><el-select v-model="pageSize" aria-label="审计每页条数"><el-option v-for="size in [10, 15, 25, 50, 100]" :key="size" :value="size" :label="`${size}条`" /></el-select></el-form-item>
          <div class="audit-actions"><el-button type="primary" native-type="submit" :loading="state.loading">查询</el-button><el-button @click="resetFilters">重置</el-button></div>
        </el-form>
        <p v-if="unapplied" class="audit-warning">筛选条件已修改但尚未应用，请点击“查询”生效。</p>
        <ApiErrorNotice :error="state.error" /><p v-if="workspace.listRetryTarget()" class="audit-retry-target">重试目标：{{workspace.listRetryTarget()?.next?'续页':'第一页'}} · 页长 {{workspace.listRetryTarget()?.size}}<template v-if="workspace.listRetryTarget()?.bound?.snapshot"> · 快照上界 {{workspace.listRetryTarget()?.bound?.snapshot}}</template></p><el-button v-if="workspace.canRetryList()" @click="retryList">重试原审计页</el-button><el-button v-if="state.loading" @click="cancelList">取消审计列表读取</el-button><p v-if="listStatus" ref="listNotice" tabindex="-1" role="status">{{listStatus}}</p>
        <AppTable :error="state.error" :rows="state.page?.items || []" :loading="state.loading" :empty-text="state.page ? '本页没有匹配的授权记录' : '审计记录尚未成功读取'">
          <el-table-column label="序号 / 时间（北京）" min-width="190"><template #default="{ row }"><strong>#{{ row.sequence }}</strong><span class="cell-sub">{{ displayTime(row.occurred_at) }}</span></template></el-table-column>
          <el-table-column prop="actor_name" label="操作者" min-width="130" />
          <el-table-column prop="action" label="动作" min-width="190" />
          <el-table-column label="对象" min-width="190"><template #default="{ row }">{{ row.resource_type }}<span class="cell-sub mono">{{ row.resource_id ?? '无对象ID' }}</span></template></el-table-column>
          <el-table-column label="结果 / 原因" min-width="220"><template #default="{ row }"><el-tag :type="['SUCCESS', 'SUCCEEDED', 'success'].includes(row.result) ? 'success' : 'warning'">{{ row.result }}</el-tag><span class="cell-sub">{{ row.reason }}</span></template></el-table-column>
          <el-table-column label="trace_id" min-width="250"><template #default="{ row }"><span class="mono trace-value">{{ row.trace_id }}</span></template></el-table-column>
          <el-table-column label="摘要" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="chooseRecordRange(row)">查看 / 选区间</el-button></template></el-table-column>
        </AppTable>
        <div class="table-footer audit-pagination"><span>{{ state.paged ? '后续页' : '第一页' }} · 本页 {{ state.page?.items.length ?? '—' }} 条 · 序号上界 {{ state.page?.snapshot_sequence ?? '—' }}</span><div class="audit-actions"><el-button :disabled="state.loading || !state.paged" @click="refresh">返回第一页</el-button><el-button :disabled="state.loading || !state.page?.has_more || unapplied" @click="nextPage">下一页</el-button></div></div>
      </div>
      <details class="panel audit-proof-panel" :open="!!state.proof || state.verifying || !!state.proofError || !!proofStatus"><summary><h2>哈希链区间校验</h2></summary><div class="admin-card-body">
        <el-form label-position="top" class="audit-range-form" @submit.prevent="verifyRange"><el-form-item label="开始序号"><el-input v-model="from" inputmode="numeric" aria-label="审计校验开始序号" /></el-form-item><el-form-item label="结束序号（可留空）"><el-input v-model="to" inputmode="numeric" aria-label="审计校验结束序号" placeholder="留空取本批最多1000条" /></el-form-item><el-button type="primary" native-type="submit" :loading="state.verifying">校验区间</el-button></el-form>
        <p v-if="rangeError" role="alert" class="audit-warning">{{ rangeError }}</p><ApiErrorNotice :error="state.proofError" /><p v-if="workspace.proofRetryTarget()" class="audit-retry-target">重试参数：{{workspace.proofRetryTarget()?.from}} → {{workspace.proofRetryTarget()?.to??'本批上限'}}</p><el-button v-if="workspace.canRetryProof()" @click="retryProof">重试原校验参数</el-button><el-button v-if="state.verifying" @click="cancelProof">取消区间校验等待</el-button><p v-if="proofStatus" ref="proofNotice" tabindex="-1" role="status">{{proofStatus}}</p>
        <template v-if="state.proof">
          <el-alert :type="state.proof.status === 'VALID' ? 'success' : state.proof.status === 'INVALID' ? 'error' : 'info'" :closable="false" :title="proofTitle" />
          <el-descriptions :column="1" border class="audit-proof-details"><el-descriptions-item label="当次快照时间">{{ state.proof.checked_at }}</el-descriptions-item><el-descriptions-item label="快照尾序号">{{ state.proof.snapshot_sequence }}</el-descriptions-item><el-descriptions-item label="本次区间">{{ state.proof.from_sequence ?? '无' }} → {{ state.proof.to_sequence ?? '无' }}</el-descriptions-item><el-descriptions-item label="已验证记录数">{{ state.proof.verified_records }}</el-descriptions-item><el-descriptions-item label="校验失败">{{ state.proof.failure }}<span v-if="state.proof.failure_sequence !== null"> · #{{ state.proof.failure_sequence }}</span></el-descriptions-item><el-descriptions-item label="本次是否覆盖快照整链">{{ state.proof.whole_snapshot_chain_checked ? '是（覆盖当前数据库快照全链）' : '否（当前为指定子区间核验）' }}</el-descriptions-item><el-descriptions-item label="本地前驱摘要"><span class="audit-hash">{{ state.proof.anchor_hash ?? '未取得' }}</span></el-descriptions-item><el-descriptions-item label="本批末尾摘要"><span class="audit-hash">{{ state.proof.final_hash ?? '未取得' }}</span></el-descriptions-item></el-descriptions>
          <el-button :disabled="state.verifying || state.proof.status !== 'VALID' || !state.proof.next_from_sequence" @click="verifyNext">校验下一批</el-button>
        </template>
      </div></details>
      <el-dialog :model-value="Boolean(selected)" title="审计记录摘要" width="650px" class="audit-record-dialog" @update:model-value="toggleRecord"><template v-if="selected"><p>#{{ selected.sequence }} · {{ selected.action }}</p><p class="audit-hash">记录ID：{{ selected.id }}</p><p class="audit-hash">操作者ID：{{ selected.actor_id ?? '未认证主体' }}</p><p class="audit-hash">前驱：{{ selected.previous_hash }}</p><p class="audit-hash">本条：{{ selected.entry_hash }}</p><p>已将当前记录序号同步至下方区间校验表单，关闭弹窗后可点击“校验区间”执行核验。</p></template><template #footer><el-button @click="selected = null">关闭</el-button></template></el-dialog>
    </template>
  </section>
</template>
<style>
.admin-prototype.audit-page .el-table .cell{overflow-wrap:anywhere;word-break:break-word;white-space:normal}.admin-prototype.audit-page .el-table .trace-value{white-space:normal;word-break:break-all}.admin-prototype.audit-page .el-table .cell-sub{max-width:100%;overflow-wrap:anywhere}.admin-prototype.audit-page .el-table .el-tag{height:auto;min-height:24px;white-space:normal}.admin-prototype.audit-page .el-table .el-tag__content{white-space:normal;overflow-wrap:anywhere}
.audit-filter-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));padding:16px 18px 0;gap:0 14px}.audit-filter-grid .el-form-item{margin-bottom:12px}.audit-filter-grid .el-select{width:100%}.audit-advanced{grid-column:1/-1;padding:0 0 12px}.audit-advanced-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 14px;margin-top:12px}.audit-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.audit-filter-grid>.audit-actions{grid-column:2/-1;justify-content:flex-end}.admin-prototype .panel>.field-help,.admin-prototype .panel>.audit-warning{padding:0 18px}.audit-warning{color:var(--el-color-warning-dark-2)}.audit-proof-panel{margin-top:16px;border-radius:5px}.audit-proof-panel>summary{padding:15px 18px;color:#3d5b7f;font-size:13px}.audit-proof-panel[open]>summary{border-bottom:1px solid #e7edf5}.audit-range-form{display:flex;gap:16px;flex-wrap:wrap;align-items:center}.audit-range-form .el-form-item{flex:1;min-width:180px}.audit-proof-details{margin:16px 0}.audit-hash{font-family:monospace;overflow-wrap:anywhere}.audit-record-dialog{max-width:calc(100vw - 24px)}
.audit-advanced .field-help{margin:0;color:#64748b;font-size:12px}.audit-proof-panel>summary{display:flex;align-items:center;gap:8px;min-height:44px;padding:0 18px;cursor:pointer}.audit-proof-panel>summary h2{display:inline;margin:0;font-size:15px;font-weight:600}.audit-retry-target{margin:8px 18px;font-size:12px;color:#64748b}
@media(max-width:800px){.audit-filter-grid,.audit-advanced-grid{grid-template-columns:1fr}.audit-filter-grid>.audit-actions{grid-column:auto;justify-content:flex-start;margin-bottom:14px}}
@media(max-width:650px){.audit-filter-grid{grid-template-columns:minmax(0,1fr)}.audit-page .el-button{min-height:44px;height:auto;white-space:normal}.audit-page .el-input__wrapper,.audit-page .el-select__wrapper{min-height:44px}.audit-pagination{align-items:flex-start;flex-direction:column;gap:8px}.audit-range-form{display:block}.audit-proof-details .el-descriptions__label{width:110px}.audit-proof-details .el-descriptions__content{overflow-wrap:anywhere}}
</style>
