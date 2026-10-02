<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { displayTime } from '../utils/display'
import { createAuditWorkspace, emptyAuditState, type AuditFilters, type AuditRecord, type AuditState } from '../utils/auditWorkspace'
import AppTable from '../components/AppTable.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
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
const proofTitle = computed(() => state.value.proof?.status === 'VALID' ? '所选区间在本次数据库快照中校验一致' : state.value.proof?.status === 'INVALID' ? '区间校验未通过，请保留证据并排查' : '当前快照没有记录，未形成有效性证明')
onUnmounted(() => workspace.dispose())
</script>
<template>
  <section class="audit-page">
    <div class="page-heading"><div><span class="eyebrow">ADMINISTRATION / AUDIT</span><h1>审计检索与区间校验</h1><p>当前租户授权日志，筛选使用精确值；分页与校验分别保留各自快照边界。</p></div><el-button :loading="state.loading" :disabled="!permitted" @click="refresh">刷新已应用筛选</el-button></div>
    <el-alert class="page-note" type="info" :closable="false" title="日志保存在项目PostgreSQL。生产留存仍待确认；本页不提供修改、删除或重签名。hash链没有外部可信锚，不等同于WORM或防特权整链重写证明。" />
    <el-empty v-if="!permitted" description="当前身份没有本租户审计权限" />
    <template v-else>
      <div class="panel">
        <el-form label-position="top" class="audit-filter-grid" @submit.prevent="search">
          <el-form-item v-for="field in fields" :key="field.key" :label="field.label"><el-input v-model="draft[field.key]" clearable :aria-label="field.label" :placeholder="field.placeholder" /></el-form-item>
          <el-form-item label="每页条数"><el-select v-model="pageSize" aria-label="审计每页条数"><el-option v-for="size in [10, 15, 25, 50, 100]" :key="size" :value="size" :label="`${size}条`" /></el-select></el-form-item>
          <div class="audit-actions"><el-button type="primary" native-type="submit" :loading="state.loading">查询并回到第一页</el-button><el-button @click="resetFilters">清空筛选</el-button></div>
        </el-form>
        <p class="field-help">时间使用含秒的UTC Z格式，最多3位毫秒；范围为开始包含、结束不包含。留空即不增加对应筛选，不代表没有历史记录。</p>
        <p v-if="unapplied" class="audit-warning">筛选输入尚未应用，请点击查询；下一页暂不可用。</p>
        <ApiErrorNotice :error="state.error"><el-button size="small" @click="refresh">重读已应用筛选</el-button></ApiErrorNotice>
        <AppTable :rows="state.page?.items || []" :loading="state.loading" :empty-text="state.page ? '本页没有匹配的授权记录' : '审计记录尚未成功读取'">
          <el-table-column label="序号 / 时间（北京）" min-width="190"><template #default="{ row }"><strong>#{{ row.sequence }}</strong><span class="cell-sub">{{ displayTime(row.occurred_at) }}</span></template></el-table-column>
          <el-table-column prop="actor_name" label="操作者" min-width="130" />
          <el-table-column prop="action" label="动作" min-width="190" />
          <el-table-column label="对象" min-width="190"><template #default="{ row }">{{ row.resource_type }}<span class="cell-sub mono">{{ row.resource_id ?? '无对象ID' }}</span></template></el-table-column>
          <el-table-column label="结果 / 原因" min-width="220"><template #default="{ row }"><el-tag :type="['SUCCESS', 'SUCCEEDED', 'success'].includes(row.result) ? 'success' : 'warning'">{{ row.result }}</el-tag><span class="cell-sub">{{ row.reason }}</span></template></el-table-column>
          <el-table-column label="trace_id" min-width="250"><template #default="{ row }"><span class="mono trace-value">{{ row.trace_id }}</span></template></el-table-column>
          <el-table-column label="摘要" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="chooseRecordRange(row)">查看 / 选区间</el-button></template></el-table-column>
        </AppTable>
        <div class="table-footer audit-pagination"><span>{{ state.paged ? '后续页' : '第一页' }} · 本页 {{ state.page?.items.length ?? '—' }} 条 · 序号上界 {{ state.page?.snapshot_sequence ?? '—' }}</span><div class="audit-actions"><el-button :disabled="state.loading || !state.paged" @click="refresh">返回第一页</el-button><el-button :disabled="state.loading || !state.page?.has_more || unapplied" @click="nextPage">下一页</el-button></div></div>
        <p class="field-help">新追加日志不会进入已固定上界的后续页，刷新第一页才能查看。列表仅校验每条本地摘要，不据此宣称整链完整。</p>
      </div>
      <section class="panel audit-proof-panel">
        <h2>只读区间校验</h2><p>校验范围是当前租户完整审计链的序号区间，不受上方筛选条件裁剪；每批最多1000条，包含首尾。</p>
        <el-form label-position="top" class="audit-range-form" @submit.prevent="verifyRange"><el-form-item label="开始序号"><el-input v-model="from" inputmode="numeric" aria-label="审计校验开始序号" /></el-form-item><el-form-item label="结束序号（可留空）"><el-input v-model="to" inputmode="numeric" aria-label="审计校验结束序号" placeholder="留空取本批最多1000条" /></el-form-item><el-button type="primary" native-type="submit" :loading="state.verifying">校验此区间（只读）</el-button></el-form>
        <p v-if="rangeError" role="alert" class="audit-warning">{{ rangeError }}</p><ApiErrorNotice :error="state.proofError" />
        <template v-if="state.proof">
          <el-alert :type="state.proof.status === 'VALID' ? 'success' : state.proof.status === 'INVALID' ? 'error' : 'info'" :closable="false" :title="proofTitle" />
          <el-descriptions :column="1" border class="audit-proof-details"><el-descriptions-item label="当次快照时间">{{ state.proof.checked_at }}</el-descriptions-item><el-descriptions-item label="快照尾序号">{{ state.proof.snapshot_sequence }}</el-descriptions-item><el-descriptions-item label="本次区间">{{ state.proof.from_sequence ?? '无' }} → {{ state.proof.to_sequence ?? '无' }}</el-descriptions-item><el-descriptions-item label="已验证记录数">{{ state.proof.verified_records }}</el-descriptions-item><el-descriptions-item label="校验失败">{{ state.proof.failure }}<span v-if="state.proof.failure_sequence !== null"> · #{{ state.proof.failure_sequence }}</span></el-descriptions-item><el-descriptions-item label="本次是否覆盖快照整链">{{ state.proof.whole_snapshot_chain_checked ? '是，仅此快照且无外部可信锚' : '否，不将部分区间或空记录当作整链证明' }}</el-descriptions-item><el-descriptions-item label="本地前驱摘要"><span class="audit-hash">{{ state.proof.anchor_hash ?? '未取得' }}</span></el-descriptions-item><el-descriptions-item label="本批末尾摘要"><span class="audit-hash">{{ state.proof.final_hash ?? '未取得' }}</span></el-descriptions-item></el-descriptions>
          <el-button :disabled="state.verifying || state.proof.status !== 'VALID' || !state.proof.next_from_sequence" @click="verifyNext">明确校验后续一批</el-button><p class="field-help">每次点击产生独立只读快照，不自动循环，也不把多次快照拼成一个整链证明。校验失败不会自动修复。</p>
        </template>
      </section>
      <el-dialog :model-value="Boolean(selected)" title="审计记录摘要" width="650px" class="audit-record-dialog" @update:model-value="toggleRecord"><template v-if="selected"><p>#{{ selected.sequence }} · {{ selected.action }}</p><p class="audit-hash">记录ID：{{ selected.id }}</p><p class="audit-hash">操作者ID：{{ selected.actor_id ?? '未认证主体' }}</p><p class="audit-hash">前驱：{{ selected.previous_hash }}</p><p class="audit-hash">本条：{{ selected.entry_hash }}</p><p>已将本条序号填入校验表单，需关闭后明确点击校验；查看摘要不会自动发起校验或写动作。</p></template><template #footer><el-button @click="selected = null">关闭</el-button></template></el-dialog>
    </template>
  </section>
</template>
<style>
.audit-filter-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.audit-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.audit-filter-grid .el-select{width:100%}.audit-warning{color:var(--el-color-warning-dark-2)}.audit-proof-panel{margin-top:24px}.audit-range-form{display:flex;gap:16px;flex-wrap:wrap;align-items:center}.audit-range-form .el-form-item{flex:1;min-width:180px}.audit-proof-details{margin:16px 0}.audit-hash{font-family:monospace;overflow-wrap:anywhere}.audit-record-dialog{max-width:calc(100vw - 24px)}
@media(max-width:650px){.audit-filter-grid{grid-template-columns:minmax(0,1fr)}.audit-page .el-button{min-height:44px;height:auto;white-space:normal}.audit-page .el-input__wrapper,.audit-page .el-select__wrapper{min-height:44px}.audit-pagination{align-items:flex-start;flex-direction:column;gap:8px}.audit-range-form{display:block}.audit-proof-details .el-descriptions__label{width:110px}.audit-proof-details .el-descriptions__content{overflow-wrap:anywhere}}
</style>
