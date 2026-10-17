<script setup lang="ts">
import '../styles/adminPrototype.css'
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
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
const contextKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
watch(contextKey, () => {
  for (const field of fields) draft[field.key] = ''
  pageSize.value = 15; from.value = '1'; to.value = ''; selected.value = null; rangeError.value = ''
  const current = session.value
  workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version } : null)
  if (permitted.value) void workspace.load()
}, { immediate: true, flush: 'sync' })
async function search() { selected.value = null; await workspace.load(filters(), pageSize.value) }
function resetFilters() { for (const field of fields) draft[field.key] = ''; pageSize.value = 15; void search() }
async function refresh() { selected.value = null; await workspace.load() }
async function nextPage() { if (unapplied.value) return; selected.value = null; await workspace.next() }
async function verifyRange() {
  rangeError.value = ''
  if (!/^[1-9][0-9]{0,15}$/.test(from.value) || to.value !== '' && !/^[1-9][0-9]{0,15}$/.test(to.value)) { rangeError.value = '起止序号必须为安全正整数，结束可留空由服务器限定本批范围'; await workspace.verify(Number.NaN); return }
  await workspace.verify(Number(from.value), to.value === '' ? null : Number(to.value))
}
async function verifyNext() {
  const next = state.value.proof?.next_from_sequence
  if (!next || state.value.proof?.status !== 'VALID') return
  from.value = String(next); to.value = ''; rangeError.value = ''; await workspace.verifyNext()
}
function toggleRecord(value: boolean) { if (!value) selected.value = null }
function chooseRecordRange(row: AuditRecord) { from.value = String(row.sequence); to.value = String(row.sequence); rangeError.value = ''; selected.value = { ...row } }
const proofTitle = computed(() => state.value.proof?.status === 'VALID' ? '所选区间在本次数据库快照中校验一致' : state.value.proof?.status === 'INVALID' ? '区间校验未通过，请保留证据并排查' : '当前快照区间暂无记录，未生成校验结果')
onUnmounted(() => workspace.dispose())
</script>
<template>
  <section class="audit-page admin-prototype">
    <div class="page-heading"><div><span class="eyebrow">ADMINISTRATION / AUDIT</span><h1>审计检索与区间校验</h1><p>检索当前租户已授权审计日志（支持精确匹配筛选）· 列表分页与链式摘要校验各自保持独立快照边界。</p></div><el-button :loading="state.loading" :disabled="!permitted" @click="refresh">刷新已应用筛选</el-button></div>
    <details class="admin-explainer"><summary>审计日志存证说明 · 链式哈希摘要校验范围</summary><p>审计日志以只读追加方式持久化存储，不支持修改、删除或重签名。区间校验用于核对数据库快照内相邻记录的哈希链连续性（长期归档与外部可信时间戳锚点按平台合规策略配置）。</p></details>
    <el-empty v-if="!permitted" description="当前身份没有本租户审计权限" />
    <template v-else>
      <div class="admin-stats"><div class="admin-stat"><span>本页审计记录</span><strong>{{ state.page?.items.length ?? '—' }}</strong><small>当前页已加载记录</small></div><div class="admin-stat"><span>查询快照序号上界</span><strong>{{ state.page?.snapshot_sequence ?? '—' }}</strong><small>后续翻页沿用同一快照上界</small></div><div class="admin-stat"><span>区间校验</span><strong>{{ state.proof ? state.proof.status : '未校验' }}</strong><small>独立于列表筛选条件</small></div><div class="admin-stat"><span>本次已校验记录</span><strong>{{ state.proof?.verified_records ?? '—' }}</strong><small>当前校验批次记录数</small></div></div>
      <div class="panel">
        <el-form label-position="top" class="audit-filter-grid" @submit.prevent="search">
          <el-form-item v-for="field in fields.slice(0, 3)" :key="field.key" :label="field.label"><el-input v-model="draft[field.key]" clearable :aria-label="field.label" :placeholder="field.placeholder" /></el-form-item>
          <details class="audit-advanced"><summary>更多筛选 · 对象、请求与时间范围</summary><div class="audit-advanced-grid"><el-form-item v-for="field in fields.slice(3)" :key="field.key" :label="field.label"><el-input v-model="draft[field.key]" clearable :aria-label="field.label" :placeholder="field.placeholder" /></el-form-item></div></details>
          <el-form-item label="每页条数"><el-select v-model="pageSize" aria-label="审计每页条数"><el-option v-for="size in [10, 15, 25, 50, 100]" :key="size" :value="size" :label="`${size}条`" /></el-select></el-form-item>
          <div class="audit-actions"><el-button type="primary" native-type="submit" :loading="state.loading">查询并回到第一页</el-button><el-button @click="resetFilters">清空筛选</el-button></div>
        </el-form>
        <p class="field-help">时间格式采用含秒的 UTC ISO-8601（Z）标准，支持最多 3 位毫秒；时间区间为左闭右开（含开始时间、不含结束时间）。</p>
        <p v-if="unapplied" class="audit-warning">筛选条件已修改但尚未应用，请点击“查询并回到第一页”生效。</p>
        <ApiErrorNotice :error="state.error"><el-button size="small" @click="refresh">重读已应用筛选</el-button></ApiErrorNotice>
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
        <p class="field-help">翻页过程中新写入的审计记录归入最新序号之后，点击“返回第一页”或刷新可加载最新快照。</p>
      </div>
      <details class="panel audit-proof-panel" :open="!!state.proof || state.verifying || !!state.proofError"><summary>哈希链区间校验 · 展开设置起止序号</summary><div class="admin-card-body">
        <h2>哈希链区间校验</h2><p>针对当前租户完整审计链的指定序号区间执行连续性核验（不受上方列表筛选条件影响，单批上限 1000 条，包含起止序号）。</p>
        <el-form label-position="top" class="audit-range-form" @submit.prevent="verifyRange"><el-form-item label="开始序号"><el-input v-model="from" inputmode="numeric" aria-label="审计校验开始序号" /></el-form-item><el-form-item label="结束序号（可留空）"><el-input v-model="to" inputmode="numeric" aria-label="审计校验结束序号" placeholder="留空取本批最多1000条" /></el-form-item><el-button type="primary" native-type="submit" :loading="state.verifying">校验此区间（只读）</el-button></el-form>
        <p v-if="rangeError" role="alert" class="audit-warning">{{ rangeError }}</p><ApiErrorNotice :error="state.proofError" />
        <template v-if="state.proof">
          <el-alert :type="state.proof.status === 'VALID' ? 'success' : state.proof.status === 'INVALID' ? 'error' : 'info'" :closable="false" :title="proofTitle" />
          <el-descriptions :column="1" border class="audit-proof-details"><el-descriptions-item label="当次快照时间">{{ state.proof.checked_at }}</el-descriptions-item><el-descriptions-item label="快照尾序号">{{ state.proof.snapshot_sequence }}</el-descriptions-item><el-descriptions-item label="本次区间">{{ state.proof.from_sequence ?? '无' }} → {{ state.proof.to_sequence ?? '无' }}</el-descriptions-item><el-descriptions-item label="已验证记录数">{{ state.proof.verified_records }}</el-descriptions-item><el-descriptions-item label="校验失败">{{ state.proof.failure }}<span v-if="state.proof.failure_sequence !== null"> · #{{ state.proof.failure_sequence }}</span></el-descriptions-item><el-descriptions-item label="本次是否覆盖快照整链">{{ state.proof.whole_snapshot_chain_checked ? '是（覆盖当前数据库快照全链）' : '否（当前为指定子区间核验）' }}</el-descriptions-item><el-descriptions-item label="本地前驱摘要"><span class="audit-hash">{{ state.proof.anchor_hash ?? '未取得' }}</span></el-descriptions-item><el-descriptions-item label="本批末尾摘要"><span class="audit-hash">{{ state.proof.final_hash ?? '未取得' }}</span></el-descriptions-item></el-descriptions>
          <el-button :disabled="state.verifying || state.proof.status !== 'VALID' || !state.proof.next_from_sequence" @click="verifyNext">明确校验后续一批</el-button><p class="field-help">每次核验基于当前读取快照独立执行，支持按批次顺延核对后续区间。</p>
        </template>
      </div></details>
      <el-dialog :model-value="Boolean(selected)" title="审计记录摘要" width="650px" class="audit-record-dialog" @update:model-value="toggleRecord"><template v-if="selected"><p>#{{ selected.sequence }} · {{ selected.action }}</p><p class="audit-hash">记录ID：{{ selected.id }}</p><p class="audit-hash">操作者ID：{{ selected.actor_id ?? '未认证主体' }}</p><p class="audit-hash">前驱：{{ selected.previous_hash }}</p><p class="audit-hash">本条：{{ selected.entry_hash }}</p><p>已将当前记录序号同步至下方区间校验表单，关闭弹窗后可点击“校验此区间”执行核验。</p></template><template #footer><el-button @click="selected = null">关闭</el-button></template></el-dialog>
    </template>
  </section>
</template>
<style>
.admin-prototype.audit-page .el-table .cell{overflow-wrap:anywhere;word-break:break-word;white-space:normal}.admin-prototype.audit-page .el-table .trace-value{white-space:normal;word-break:break-all}.admin-prototype.audit-page .el-table .cell-sub{max-width:100%;overflow-wrap:anywhere}.admin-prototype.audit-page .el-table .el-tag{height:auto;min-height:24px;white-space:normal}.admin-prototype.audit-page .el-table .el-tag__content{white-space:normal;overflow-wrap:anywhere}
.audit-filter-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));padding:16px 18px 0;gap:0 14px}.audit-filter-grid .el-form-item{margin-bottom:12px}.audit-filter-grid .el-select{width:100%}.audit-advanced{grid-column:1/-1;padding:0 0 12px}.audit-advanced-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 14px;margin-top:12px}.audit-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.audit-filter-grid>.audit-actions{grid-column:2/-1;justify-content:flex-end}.admin-prototype .panel>.field-help,.admin-prototype .panel>.audit-warning{padding:0 18px}.audit-warning{color:var(--el-color-warning-dark-2)}.audit-proof-panel{margin-top:16px;border-radius:5px}.audit-proof-panel>summary{padding:15px 18px;color:#3d5b7f;font-size:13px}.audit-proof-panel[open]>summary{border-bottom:1px solid #e7edf5}.audit-range-form{display:flex;gap:16px;flex-wrap:wrap;align-items:center}.audit-range-form .el-form-item{flex:1;min-width:180px}.audit-proof-details{margin:16px 0}.audit-hash{font-family:monospace;overflow-wrap:anywhere}.audit-record-dialog{max-width:calc(100vw - 24px)}
@media(max-width:800px){.audit-filter-grid,.audit-advanced-grid{grid-template-columns:1fr}.audit-filter-grid>.audit-actions{grid-column:auto;justify-content:flex-start;margin-bottom:14px}}
@media(max-width:650px){.audit-filter-grid{grid-template-columns:minmax(0,1fr)}.audit-page .el-button{min-height:44px;height:auto;white-space:normal}.audit-page .el-input__wrapper,.audit-page .el-select__wrapper{min-height:44px}.audit-pagination{align-items:flex-start;flex-direction:column;gap:8px}.audit-range-form{display:block}.audit-proof-details .el-descriptions__label{width:110px}.audit-proof-details .el-descriptions__content{overflow-wrap:anywhere}}
</style>
