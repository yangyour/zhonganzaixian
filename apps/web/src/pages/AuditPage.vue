<script setup lang="ts">
import '../styles/adminPrototype.css'
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { displayTime } from '../utils/display'
import { displayTenantName } from '../utils/displayTenantName'
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
  { key: 'actor_id', label: '操作者ID（精确）', placeholder: '完整UUID，可留空' }, { key: 'action', label: '操作类型（精确）', placeholder: '例如 local.login' },
  { key: 'result', label: '结果（精确）', placeholder: '全部结果' }, { key: 'resource_type', label: '对象类型（精确）', placeholder: '例如 asset / worklog' },
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
const proofTitle = computed(() => state.value.proof?.status === 'VALID' ? '所选区间在本次数据库快照中校验一致' : state.value.proof?.status === 'INVALID' ? '区间校验未通过，请保留证据并排查' : '当前快照区间暂无记录，尚无校验结果')
// Display only. The exact code stays visible because filters match it exactly; unknown codes are never shown as success.
const resultBadges: Record<string, { label: string; tone: string }> = { SUCCEEDED: { label: '成功', tone: 'ok' }, SUCCESS: { label: '成功', tone: 'ok' }, success: { label: '成功', tone: 'ok' }, FAILED: { label: '失败', tone: 'warn' }, DENIED: { label: '已拒绝', tone: 'danger' } }
const resultBadge = (result: string) => resultBadges[result] ?? { label: '未识别结果', tone: 'unknown' }
const resultOptions = [{ value: 'SUCCEEDED', label: '成功' }, { value: 'FAILED', label: '失败' }, { value: 'DENIED', label: '已拒绝' }]
const shortId = (value: string | null) => value ? `…${value.slice(-6)}` : '—'
// The reader rejects any row outside the current tenant, so the session tenant names every row.
const tenantName = computed(() => displayTenantName(session.value?.tenant.name ?? ''))
async function retryList(){focusEpoch++;listStatus.value='';selected.value=null;return workspace.retryList()}
async function retryProof(){focusEpoch++;proofStatus.value='';rangeError.value='';return workspace.retryProof()}
async function cancelList(){if(!workspace.cancelList())return false;const turn=++focusEpoch;selected.value=null;listStatus.value='已取消列表读取；可重试原查询参数，续页仍使用原快照上界。';await nextTick();if(turn===focusEpoch)listNotice.value?.focus();return true}
async function cancelProof(){if(!workspace.cancelProof())return false;const turn=++focusEpoch;proofStatus.value='已取消区间校验等待；没有修改审计记录。重试相同序号参数仍是一次新的数据库快照核验。';await nextTick();if(turn===focusEpoch)proofNotice.value?.focus();return true}
onUnmounted(() => {focusEpoch++;workspace.dispose()})
defineExpose({state,workspace,draft,pageSize,from,to,search,refresh,nextPage,verifyRange,verifyNext,retryList,retryProof,cancelList,cancelProof,listStatus,proofStatus})
</script>
<template>
  <section class="audit-page admin-prototype">
    <h1 class="page-title-sr">审计检索与区间校验</h1>
        <el-empty v-if="!permitted" description="当前身份没有本租户审计权限" />
    <template v-else>
      <div class="panel audit-card">
        <header class="mop-card-head">
          <div class="mop-card-title"><h2>审计日志</h2><span>{{ tenantName }} · 追加式记录，页面不提供修改或删除</span></div>
          <div class="mop-card-actions"><el-button :loading="state.loading" :disabled="!permitted" @click="refresh">刷新列表</el-button></div>
        </header>
        <el-form label-position="top" class="audit-filter-grid" @submit.prevent="search">
          <el-form-item :label="fields[1]!.label"><el-input v-model="draft.action" clearable :aria-label="fields[1]!.label" :placeholder="fields[1]!.placeholder" /></el-form-item>
          <el-form-item :label="fields[2]!.label"><el-select v-model="draft.result" clearable value-on-clear="" filterable allow-create default-first-option :aria-label="fields[2]!.label" :placeholder="fields[2]!.placeholder"><el-option v-for="option in resultOptions" :key="option.value" :value="option.value" :label="`${option.label}（${option.value}）`" /></el-select></el-form-item>
          <el-form-item :label="fields[0]!.label"><el-input v-model="draft.actor_id" clearable :aria-label="fields[0]!.label" :placeholder="fields[0]!.placeholder" /></el-form-item>
          <el-form-item label="每页条数"><el-select v-model="pageSize" aria-label="审计每页条数"><el-option v-for="size in [10, 15, 25, 50, 100]" :key="size" :value="size" :label="`${size}条`" /></el-select></el-form-item>
          <div class="audit-actions"><el-button type="primary" native-type="submit" :loading="state.loading">查询</el-button><el-button @click="resetFilters">重置</el-button></div>
          <details class="audit-advanced"><summary>更多筛选<span v-if="fields.slice(3).some(field => draft[field.key])" class="mop-pill-count">{{ fields.slice(3).filter(field => draft[field.key]).length }}</span></summary><div class="audit-advanced-grid"><el-form-item v-for="field in fields.slice(3)" :key="field.key" :label="field.label"><el-input v-model="draft[field.key]" clearable :aria-label="field.label" :placeholder="field.placeholder" /></el-form-item></div><p class="field-help">时间使用 UTC ISO-8601（如 2026-10-21T00:00:00Z），区间含开始、不含结束。所有条件均为精确匹配，不支持关键词检索。</p></details>
        </el-form>
        <p v-if="unapplied" class="audit-warning">筛选条件已修改但尚未应用，请点击“查询”生效。</p>
        <div class="audit-feedback">
          <ApiErrorNotice :error="state.error" /><p v-if="workspace.listRetryTarget()" class="audit-retry-target">重试目标：{{workspace.listRetryTarget()?.next?'续页':'第一页'}} · 页长 {{workspace.listRetryTarget()?.size}}<template v-if="workspace.listRetryTarget()?.bound?.snapshot"> · 快照上界 {{workspace.listRetryTarget()?.bound?.snapshot}}</template></p><el-button v-if="workspace.canRetryList()" @click="retryList">重试原审计页</el-button><el-button v-if="state.loading" @click="cancelList">取消审计列表读取</el-button><p v-if="listStatus" ref="listNotice" tabindex="-1" role="status">{{listStatus}}</p>
        </div>
        <AppTable :error="state.error" :rows="state.page?.items || []" :loading="state.loading" label="审计记录，M2-12 八列及操作列，可横向滚动" :empty-text="state.page ? '本页没有匹配的授权记录' : '审计记录尚未成功读取'">
          <el-table-column label="时间（北京）" min-width="150"><template #default="{ row }"><span class="audit-time">{{ displayTime(row.occurred_at) }}</span><span class="cell-sub">序号 #{{ row.sequence }}</span></template></el-table-column>
          <el-table-column label="操作者" min-width="124"><template #default="{ row }"><span class="audit-nowrap">{{ row.actor_name }}</span><span class="cell-sub mono" :title="row.actor_id ?? undefined">{{ row.actor_id ? shortId(row.actor_id) : '无账号ID' }}</span></template></el-table-column>
          <el-table-column label="租户" min-width="120"><template #default="{ row }"><span class="audit-nowrap" :title="row.tenant_id">{{ tenantName }}</span></template></el-table-column>
          <el-table-column label="操作类型" min-width="170"><template #default="{ row }"><span class="mono audit-code"><template v-for="(part, index) in row.action.split('.')" :key="index"><template v-if="index">.<wbr /></template>{{ part }}</template></span></template></el-table-column>
          <el-table-column label="描述 · 对象" min-width="210"><template #default="{ row }">{{ row.reason || '—' }}<span class="cell-sub">{{ row.resource_type }}<template v-if="row.resource_id"> · <span class="mono" :title="row.resource_id">{{ shortId(row.resource_id) }}</span></template></span></template></el-table-column>
          <el-table-column label="结果" min-width="104"><template #default="{ row }"><span class="mop-badge" :class="resultBadge(row.result).tone" :title="row.result">{{ resultBadge(row.result).label }}</span><span class="cell-sub mono">{{ row.result }}</span></template></el-table-column>
          <el-table-column label="trace_id" min-width="170"><template #default="{ row }"><span class="mono trace-value">{{ row.trace_id }}</span></template></el-table-column>
          <el-table-column label="来源IP" min-width="76"><template #default><span class="audit-missing" title="当前接口未提供来源IP">—</span></template></el-table-column>
          <el-table-column label="操作" width="76" fixed="right"><template #default="{ row }"><el-button link type="primary" :aria-label="`详情：第 ${row.sequence} 条审计记录`" title="查看摘要，并把序号填入区间校验" @click="chooseRecordRange(row)">详情</el-button></template></el-table-column>
        </AppTable>
        <div class="mop-card-foot audit-pagination"><span>{{ state.paged ? '后续页' : '第一页' }} · 本页 {{ state.page?.items.length ?? '—' }} 条 · 快照序号上界 {{ state.page?.snapshot_sequence ?? '—' }}</span><div class="audit-actions"><el-button :disabled="state.loading || !state.paged" @click="refresh">返回第一页</el-button><el-button :disabled="state.loading || !state.page?.has_more || unapplied" @click="nextPage">下一页</el-button></div></div>
      </div>
      <details class="panel audit-proof-panel" :open="!!state.proof || state.verifying || !!state.proofError || !!proofStatus"><summary><h2>哈希链区间校验</h2><span class="audit-proof-scope">按序号重算本租户本地数据库快照中的哈希链；不是外部存证</span></summary><div class="audit-proof-body">
        <el-form label-position="top" class="audit-range-form" @submit.prevent="verifyRange"><el-form-item label="开始序号"><el-input v-model="from" inputmode="numeric" aria-label="审计校验开始序号" /></el-form-item><el-form-item label="结束序号（可留空）"><el-input v-model="to" inputmode="numeric" aria-label="审计校验结束序号" placeholder="留空取本批最多1000条" /></el-form-item><el-button type="primary" native-type="submit" :loading="state.verifying">校验区间</el-button></el-form>
        <p v-if="rangeError" role="alert" class="audit-warning">{{ rangeError }}</p><ApiErrorNotice :error="state.proofError" /><p v-if="workspace.proofRetryTarget()" class="audit-retry-target">重试参数：{{workspace.proofRetryTarget()?.from}} → {{workspace.proofRetryTarget()?.to??'本批上限'}}</p><el-button v-if="workspace.canRetryProof()" @click="retryProof">重试原校验参数</el-button><el-button v-if="state.verifying" @click="cancelProof">取消区间校验等待</el-button><p v-if="proofStatus" ref="proofNotice" tabindex="-1" role="status">{{proofStatus}}</p>
        <template v-if="state.proof">
          <el-alert :type="state.proof.status === 'VALID' ? 'success' : state.proof.status === 'INVALID' ? 'error' : 'info'" :closable="false" :title="proofTitle" />
          <dl class="mop-facts audit-proof-facts">
            <div><dt>当次快照时间</dt><dd>{{ displayTime(state.proof.checked_at) }}<small class="mono">{{ state.proof.checked_at }}</small></dd></div>
            <div><dt>快照尾序号</dt><dd>{{ state.proof.snapshot_sequence }}</dd></div>
            <div><dt>本次区间</dt><dd>{{ state.proof.from_sequence ?? '无' }} → {{ state.proof.to_sequence ?? '无' }}</dd></div>
            <div><dt>已验证记录数</dt><dd>{{ state.proof.verified_records }}</dd></div>
            <div><dt>校验失败</dt><dd class="mono">{{ state.proof.failure }}<template v-if="state.proof.failure_sequence !== null"> · #{{ state.proof.failure_sequence }}</template></dd></div>
            <div><dt>本次是否覆盖快照整链</dt><dd>{{ state.proof.whole_snapshot_chain_checked ? '是（覆盖当前数据库快照全链）' : '否（当前为指定子区间核验）' }}</dd></div>
            <div class="audit-fact-wide"><dt>本地前驱摘要</dt><dd class="audit-hash">{{ state.proof.anchor_hash ?? '未取得' }}</dd></div>
            <div class="audit-fact-wide"><dt>本批末尾摘要</dt><dd class="audit-hash">{{ state.proof.final_hash ?? '未取得' }}</dd></div>
          </dl>
          <el-button :disabled="state.verifying || state.proof.status !== 'VALID' || !state.proof.next_from_sequence" @click="verifyNext">校验下一批</el-button>
        </template>
      </div></details>
      <el-dialog :model-value="Boolean(selected)" title="审计记录摘要" width="650px" class="audit-record-dialog" @update:model-value="toggleRecord"><template v-if="selected"><dl class="audit-record-facts"><div><dt>序号 · 操作类型</dt><dd>#{{ selected.sequence }} · <span class="mono">{{ selected.action }}</span></dd></div><div><dt>记录ID</dt><dd class="audit-hash">{{ selected.id }}</dd></div><div><dt>操作者ID</dt><dd class="audit-hash">{{ selected.actor_id ?? '未认证主体' }}</dd></div><div><dt>前驱摘要</dt><dd class="audit-hash">{{ selected.previous_hash }}</dd></div><div><dt>本条摘要</dt><dd class="audit-hash">{{ selected.entry_hash }}</dd></div></dl><p class="audit-record-note">已将当前记录序号同步至下方区间校验表单，关闭弹窗后可点击“校验区间”执行核验。</p></template><template #footer><el-button @click="selected = null">关闭</el-button></template></el-dialog>
    </template>
  </section>
</template>
<style>
.admin-prototype.audit-page .el-table .cell{overflow-wrap:anywhere;word-break:break-word;white-space:normal}.admin-prototype.audit-page .el-table .trace-value{white-space:normal;word-break:break-all}.admin-prototype.audit-page .el-table .cell-sub{display:block;max-width:100%;overflow-wrap:anywhere}
.audit-card{overflow:hidden}
.audit-filter-grid{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(0,1fr) minmax(0,1.3fr) 128px auto;align-items:end;gap:0 12px;padding:14px 16px 0}.audit-filter-grid .el-form-item{margin-bottom:12px}.audit-filter-grid .el-select{width:100%}
.audit-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.audit-actions .el-button+.el-button{margin-left:0}.audit-filter-grid>.audit-actions{margin-bottom:12px}
.audit-advanced{grid-column:1/-1;padding:0 0 12px;font-size:13px}.audit-advanced>summary{display:inline-flex;align-items:center;gap:6px;min-height:28px;color:var(--mop-primary);cursor:pointer}.audit-advanced-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:0 12px;margin-top:10px}
.audit-advanced .field-help{margin:0;color:var(--mop-muted);font-size:12px}
.admin-prototype .audit-card>.audit-warning{margin:0;padding:0 16px 10px}.audit-warning{color:var(--el-color-warning-dark-2)}
.audit-feedback:empty{display:none}.audit-feedback{display:flex;flex-direction:column;align-items:flex-start;gap:6px;padding:0 16px 10px}.audit-feedback>.api-error{width:100%}.audit-feedback .el-button+.el-button{margin-left:0}
.audit-retry-target{margin:0;font-size:12px;color:var(--mop-muted)}
.audit-time{font-variant-numeric:tabular-nums;white-space:nowrap}
/* Auto table layout sizes columns from content: short labels keep one line, codes break only after a dot. */
.admin-prototype.audit-page .audit-card .el-table th .cell{white-space:nowrap}
.admin-prototype.audit-page .el-table :is(.audit-nowrap,.mop-badge){white-space:nowrap;overflow-wrap:normal;word-break:normal}
.admin-prototype.audit-page .el-table .audit-code{overflow-wrap:normal;word-break:normal}
.audit-missing{color:var(--mop-faint)}
.audit-proof-panel{margin-top:12px;overflow:hidden}
.audit-proof-panel>summary{display:flex;align-items:center;flex-wrap:wrap;gap:4px 12px;min-height:48px;padding:8px 16px;cursor:pointer}.audit-proof-panel>summary h2{display:inline;margin:0;font-size:14px;font-weight:600;color:var(--mop-ink)}
.audit-proof-scope{color:var(--mop-muted);font-size:12px}
.audit-proof-panel[open]>summary{border-bottom:1px solid var(--mop-line-soft)}
.audit-proof-body{display:grid;gap:12px;padding:14px 16px 16px}.audit-proof-body>.el-button{justify-self:start}
.audit-range-form{display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end}.audit-range-form .el-form-item{flex:0 1 220px;min-width:160px;margin-bottom:0}
.audit-proof-facts .audit-fact-wide{grid-column:1/-1}
.audit-hash{font-family:var(--mop-font-mono,monospace);overflow-wrap:anywhere;word-break:break-all}
.audit-record-dialog{max-width:calc(100vw - 24px)}
.audit-record-facts{display:grid;gap:10px;margin:0}.audit-record-facts dt{margin:0 0 2px;color:#5d6e83;font-size:12px}.audit-record-facts dd{margin:0;color:#1c2a3b;font-size:13px}
.audit-record-note{margin:14px 0 0;color:#5d6e83;font-size:12px;line-height:1.6}
@media(max-width:1180px){.audit-filter-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.audit-filter-grid>.audit-actions{grid-column:span 2;justify-content:flex-end}.audit-advanced-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:800px){.audit-filter-grid,.audit-advanced-grid{grid-template-columns:1fr}.audit-filter-grid>.audit-actions{grid-column:auto;justify-content:flex-start;margin-bottom:14px}}
@media(max-width:650px){.audit-filter-grid{grid-template-columns:minmax(0,1fr)}.audit-page .el-button{min-height:44px;height:auto;white-space:normal}.audit-page .el-input__wrapper,.audit-page .el-select__wrapper{min-height:44px}.audit-pagination{align-items:flex-start;flex-direction:column;gap:8px}.audit-range-form{display:block}.audit-proof-details .el-descriptions__label{width:110px}.audit-proof-details .el-descriptions__content{overflow-wrap:anywhere}}
</style>
