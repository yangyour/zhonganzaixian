<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElTimeline, ElTimelineItem } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import { api, isCancelled } from '../api/client'
import type { RegisteredTicket } from '../api/types'
import { can, contextGeneration, session } from '../stores/session'
import { displayTime } from '../utils/display'
import { readRegisteredTicket, readTicketPage, type TicketReadContext } from '../utils/ticketReader'
import { impactScopeLabel, ticketCategories, urgencyLabel } from '../utils/ticket'
import AppTable from '../components/AppTable.vue'
import AppliedFilters from '../components/AppliedFilters.vue'
import { createSearchCompositionGuard, syncFilterDraft } from '../utils/listSearch'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import TicketReportForm from '../components/TicketReportForm.vue'
import WorklogWorkspace from '../components/WorklogWorkspace.vue'
import InternalNoteWorkspace from '../components/InternalNoteWorkspace.vue'
import TicketCommentWorkspace from '../components/TicketCommentWorkspace.vue'
import ReportPhotoPanel from '../components/ReportPhotoPanel.vue'
import RetainedPhotoNotice from '../components/RetainedPhotoNotice.vue'
import { createPhotoQueue } from '../utils/photoQueue'
import { createTicketPhotoWorkspace } from '../utils/ticketPhotoWorkspace'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createReportOperationReader, type ReportOperationReadState } from '../utils/reportOperation'

const route = useRoute(), router = useRouter()
const queryString = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : ''
const pageSizes = [10, 15, 25, 50, 100]
const pageSize = computed(() => pageSizes.includes(Number(queryString('page_size'))) ? Number(queryString('page_size')) : 15)
const filters = reactive({ q: '', category: '', urgency: '' })
const appliedFilters = computed(() => ({ q: queryString('q'), category: queryString('category'), urgency: queryString('urgency') }))
let previousAppliedFilters: typeof filters | undefined
const pendingFilters = computed(() => Object.entries(filters).some(([key, value]) => value.trim() !== queryString(key)))
const filterItems = computed(() => [
  { key: 'q', label: '关键词', value: appliedFilters.value.q },
  { key: 'category', label: '分类', value: appliedFilters.value.category },
  { key: 'urgency', label: '紧急程度', value: appliedFilters.value.urgency ? urgencyLabel[appliedFilters.value.urgency as keyof typeof urgencyLabel] || appliedFilters.value.urgency : '' },
].filter(item => item.value))
const searchComposition = createSearchCompositionGuard()
const customerView = computed(() => ['viewer', 'tadmin'].includes(session.value?.user.role || ''))
const mayReport = computed(() => customerView.value && can('tickets:report'))
const readContext = (): TicketReadContext => ({ tenantId: session.value?.tenant.id || '', actorId: session.value?.user.id || '', role: session.value?.user.role || '', permissionVersion: session.value?.permission_version || 0 })
const rows = ref<RegisteredTicket[]>([])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const cursorHistory = ref<string[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref<unknown>(null)
const pageSizeLimit = ref(100)
let listController: AbortController | undefined
let listGeneration = 0
const signature = computed(() => JSON.stringify(['q', 'category', 'urgency', 'cursor', 'page_size'].map(queryString)))
watch(signature, () => {
  syncFilterDraft(filters, appliedFilters.value, previousAppliedFilters)
  previousAppliedFilters = { ...appliedFilters.value }
  void loadTickets()
}, { immediate: true })
async function loadTickets() {
  listController?.abort(); const controller = new AbortController(); listController = controller
  const generation = ++listGeneration, context = readContext(), requestedSize = pageSize.value
  const requestedFilters = { category: queryString('category'), urgency: queryString('urgency') }
  loading.value = true; loaded.value = false; error.value = null; rows.value = []; nextCursor.value = null; hasMore.value = false
  const params = new URLSearchParams({ page_size: String(requestedSize) })
  for (const key of ['q', 'category', 'urgency', 'cursor']) if (queryString(key)) params.set(key, queryString(key))
  try {
    const response = await api.request<unknown>(`/tickets?${params}`, { signal: controller.signal })
    if (generation !== listGeneration || controller.signal.aborted) return
    const page = readTicketPage(response, context, requestedSize, requestedFilters)
    rows.value = page.items; nextCursor.value = page.next_cursor; hasMore.value = page.has_more; pageSizeLimit.value = page.page_size_limit; loaded.value = true
  } catch (cause) { if (!isCancelled(cause) && generation === listGeneration) error.value = cause }
  finally { if (generation === listGeneration) loading.value = false }
}
function applyFilters() {
  if (loading.value || searchComposition.isComposing()) return
  for (const key of Object.keys(filters) as (keyof typeof filters)[]) filters[key] = filters[key].trim()
  cursorHistory.value = []
  const query = { ...route.query, ...Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value || undefined])), cursor: undefined, page_size: String(pageSize.value) }
  const unchanged = !queryString('cursor') && Object.entries(filters).every(([key, value]) => value === queryString(key))
  if (unchanged) void loadTickets()
  else void router.replace({ query })
}
function resetFilters() { if (loading.value) return; Object.assign(filters, { q: '', category: '', urgency: '' }); applyFilters() }
function removeFilter(key: string) {
  if (loading.value || !Object.hasOwn(filters, key)) return
  filters[key as keyof typeof filters] = ''
  cursorHistory.value = []
  void router.replace({ query: { ...route.query, [key]: undefined, cursor: undefined } })
}
function resizePage(value: unknown) { cursorHistory.value = []; void router.replace({ query: { ...route.query, cursor: undefined, page_size: String(value) } }) }
function nextPage() { if (!nextCursor.value) return; cursorHistory.value.push(queryString('cursor')); void router.replace({ query: { ...route.query, cursor: nextCursor.value, page_size: String(pageSize.value) } }) }
function previousPage() { const cursor = cursorHistory.value.pop(); void router.replace({ query: { ...route.query, cursor: cursor || undefined } }) }
function firstPage() { cursorHistory.value = []; void router.replace({ query: { ...route.query, cursor: undefined } }) }

const detailOpen = computed({ get: () => Boolean(queryString('ticket_id')), set: value => { if (!value) void router.replace({ query: { ...route.query, ticket_id: undefined } }) } })
const ticket = ref<RegisteredTicket | null>(null)
const detailError = ref<unknown>(null)
const detailLoading = ref(false)
const operationRead = ref<ReportOperationReadState>({ status: 'idle' })
const registrationOperation = computed(() => operationRead.value.status === 'ready' ? operationRead.value.operation : null)
const operationReader = createReportOperationReader({
  context: () => ({ tenantId: session.value?.tenant.id || '', key: JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version]) }),
  onChange: value => { operationRead.value = value },
})
function readRegistrationOperation() { if (ticket.value) return operationReader.load(ticket.value) }
watch(() => ticket.value ? JSON.stringify([ticket.value.id, ticket.value.tenant_id, ticket.value.operation_id]) : '', () => {
  operationReader.reset()
  if (ticket.value) void readRegistrationOperation()
}, { flush: 'sync' })
const detailPhotos = createPhotoQueue()
const photoWorkspace = createTicketPhotoWorkspace(detailPhotos)
const photoTargetId = photoWorkspace.targetId, appendTicket = photoWorkspace.ticket, detailAppender = photoWorkspace.appender
const retainedPhotos = computed(() => photoWorkspace.unresolved.value && Boolean(photoTargetId.value) && queryString('ticket_id') !== photoTargetId.value)
const matchingPhotoTarget = computed(() => ticket.value?.id === photoTargetId.value)
watch(appendTicket, result => { if (result && result.id === queryString('ticket_id')) { ticket.value = result; void loadTickets() } })
let detailController: AbortController | undefined
let detailGeneration = 0
watch(() => queryString('ticket_id'), id => { void loadDetail(id) }, { immediate: true })
async function loadDetail(id = queryString('ticket_id')) {
  detailController?.abort(); const controller = new AbortController(); detailController = controller
  const generation = ++detailGeneration, context = readContext()
  photoWorkspace.visit(id)
  ticket.value = null; detailError.value = null
  if (!id) { detailLoading.value = false; return }
  detailLoading.value = true
  try {
    const response = await api.request<unknown>(`/tickets/${encodeURIComponent(id)}`, { signal: controller.signal })
    if (generation === detailGeneration && !controller.signal.aborted) {
      const result = readRegisteredTicket(response, context, id)
      ticket.value = result
      if (photoWorkspace.accept(result) && mayReport.value && !detailPhotos.policy.value) void detailPhotos.loadPolicy()
    }
  } catch (cause) { if (!isCancelled(cause) && generation === detailGeneration) detailError.value = cause }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function openDetail(id: string) { void router.replace({ query: { ...route.query, ticket_id: id } }) }
function discardRetainedPhotos() {
  const id = queryString('ticket_id')
  if (photoWorkspace.discard(id) && id) void loadDetail(id)
}
const commentOpen = ref(false), commentTarget = ref<RegisteredTicket | null>(null)
const commentWorkspace = ref<InstanceType<typeof TicketCommentWorkspace> | null>(null)
function openComments() {
  if (!ticket.value || !can('ticket_comments:read')) return
  if (commentTarget.value?.id !== ticket.value.id && commentWorkspace.value?.hasUnresolved()) { ElMessage.warning('上一条登记的评论尚未确认，请先恢复原面板并重试原请求'); commentOpen.value = true; return }
  commentTarget.value = ticket.value; commentOpen.value = true
}
const noteOpen = ref(false), noteTarget = ref<RegisteredTicket | null>(null)
const noteWorkspace = ref<InstanceType<typeof InternalNoteWorkspace> | null>(null)
function openInternalNotes() {
  if (!ticket.value || !can('ticket_notes:read_internal')) return
  if (noteTarget.value?.id !== ticket.value.id && noteWorkspace.value?.hasUnresolved()) { ElMessage.warning('上一条登记的内部备注尚未确认，请先恢复原面板并重试原请求'); noteOpen.value = true; return }
  noteTarget.value = ticket.value; noteOpen.value = true
}
const worklogOpen = ref(false), worklogTarget = ref<RegisteredTicket | null>(null)
const worklogWorkspace = ref<InstanceType<typeof WorklogWorkspace> | null>(null)
async function openWorklogs() {
  if (!ticket.value || !can('worklogs:read_own')) return
  const target = ticket.value, previous = worklogTarget.value, generation = contextGeneration.value
  if (previous?.id !== target.id && worklogWorkspace.value && !await worklogWorkspace.value.canLeave('切换报障登记')) {
    if (generation === contextGeneration.value && worklogTarget.value === previous) worklogOpen.value = true
    return
  }
  if (generation !== contextGeneration.value || ticket.value !== target || worklogTarget.value !== previous || !can('worklogs:read_own') || target.tenant_id !== session.value?.tenant.id) return
  worklogTarget.value = target; worklogOpen.value = true
}
const createOpen = ref(false)
const acceptedOperationId = ref('')
const pendingRegistered = ref<{ id: string; context: number } | null>(null)
function recordAcceptance(id: string) { acceptedOperationId.value = id }
async function showRegistered(id: string) {
  const generation = contextGeneration.value
  if (createOpen.value) {
    // Keep photo upload/append controls reachable until the registration form closes.
    pendingRegistered.value = { id, context: generation }
    await loadTickets()
    return
  }
  pendingRegistered.value = null
  const alreadySelected = queryString('ticket_id') === id
  await router.replace({ query: { ...route.query, ticket_id: id } })
  if (generation !== contextGeneration.value) return
  if (alreadySelected) await loadDetail(id)
  if (generation !== contextGeneration.value) return
  await loadTickets()
}
watch(createOpen, open => {
  const pending = pendingRegistered.value
  if (open || !pending) return
  pendingRegistered.value = null
  if (pending.context === contextGeneration.value) void showRegistered(pending.id)
}, { flush: 'post' })
function resetContext() {
  listController?.abort(); detailController?.abort(); ++listGeneration; ++detailGeneration
  rows.value = []; nextCursor.value = null; hasMore.value = false; cursorHistory.value = []; loading.value = false; loaded.value = false; error.value = null
  ticket.value = null; detailError.value = null; detailLoading.value = false; pendingRegistered.value = null; createOpen.value = false; acceptedOperationId.value = ''
  worklogOpen.value = false; worklogTarget.value = null
  noteOpen.value = false; noteTarget.value = null
  commentOpen.value = false; commentTarget.value = null
  photoWorkspace.reset()
  operationReader.reset()
}
// Navigation re-reads /session and replaces its object even when scope is unchanged.
// Watch each scope value so that a normal query change cannot discard retained editors or commands.
watch([() => contextGeneration.value, () => session.value?.tenant.id, () => session.value?.user.id, () => session.value?.user.role, () => session.value?.permission_version], resetContext, { flush: 'sync' })
function canLeavePhotos() { if (!photoWorkspace.unresolved.value) return true; ElMessage.warning('已有报障照片或追加请求未处理完，请先恢复原登记并处理，再离开页面、切换租户或退出'); return false }
function beforeUnload(event: BeforeUnloadEvent) { if (photoWorkspace.unresolved.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => canLeavePhotos())
const unregisterContextGuard = contextTransitionGuards.register(() => canLeavePhotos())
onUnmounted(() => { unregisterContextGuard(); window.removeEventListener('beforeunload', beforeUnload); resetContext(); detailPhotos.dispose(); photoWorkspace.dispose() })
</script>

<template>
  <section class="tickets-page">
    <div class="page-heading proto-page-head"><div><h1>报障与受理 <span class="proto-badge">{{ session?.tenant.name }}</span></h1><p>{{ customerView ? '查看本人报障登记，填写故障现象与联系方式。' : '只读查看当前授权租户的报障登记。' }}</p></div><el-button v-if="mayReport" type="primary" @click="createOpen = true">填写报障信息</el-button></div>
    <div class="proto-kpis"><div><span>本页报障登记</span><b>{{ loading || error || !loaded ? '—' : rows.length }}</b><small>{{ customerView ? '仅本人记录' : '当前租户记录' }}</small></div><div><span>正式处理状态</span><b>待接入</b><small>登记不代表接单或解决</small></div><div><span>响应 / 恢复截止</span><b>待配置</b><small>SLA 规则尚未落实</small></div><div><span>客户确认闭环</span><b>待接入</b><small>不推断关单状态</small></div></div>
    <el-alert class="page-note" type="warning" :closable="false" show-icon title="一期已选择本平台为工单权威。当前报障可登记与查询；正式状态、SLA 时限和处理资格规则仍待落实。" />
    <el-alert v-if="acceptedOperationId" class="page-note" type="info" :closable="false" show-icon title="报障已耐久登记，正式工单规则待确认。登记结果以详情回读为准，不重复提交或推断为外部建单成功。" />
    <RetainedPhotoNotice v-if="retainedPhotos" :target-id="photoTargetId" :ticket="appendTicket" :count="detailPhotos.rows.value.length" :can-discard="photoWorkspace.canDiscard.value" @resume="openDetail(photoTargetId)" @discard="discardRetainedPhotos" />
    <div class="panel ticket-register-panel">
      <div class="proto-section-head"><h2>{{ customerView ? '我的报障登记' : '全部报障登记' }}</h2><span class="proto-sub">{{ customerView ? '仅本人可见' : '当前租户只读' }} · 正式工单状态待接入</span></div>
      <el-form :inline="true" class="filter-form ticket-filter-form" @submit.prevent="applyFilters" @compositionstart="searchComposition.start" @compositionend="searchComposition.end" @keydown="searchComposition.keydown"><el-form-item label="搜索"><el-input v-model="filters.q" clearable placeholder="登记号 / 标题 / 描述" aria-label="搜索报障登记" enterkeyhint="search" /></el-form-item><el-form-item label="故障分类"><el-select v-model="filters.category" clearable placeholder="全部分类" aria-label="报障分类筛选"><el-option v-for="category in ticketCategories" :key="category" :label="category" :value="category" /></el-select></el-form-item><el-form-item label="紧急程度"><el-select v-model="filters.urgency" clearable placeholder="全部程度" aria-label="报障紧急程度筛选"><el-option label="一般" value="normal" /><el-option label="很急（参考）" value="urgent" /></el-select></el-form-item><el-form-item class="filter-actions"><el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button :disabled="loading" @click="resetFilters">重置</el-button><el-button :loading="loading" title="仅刷新已应用条件，保留未提交输入" @click="loadTickets">刷新</el-button></el-form-item></el-form>
      <AppliedFilters :items="filterItems" :pending="pendingFilters" :loading="loading" :failed="Boolean(error)" :loaded="loaded" :count="rows.length" @remove="removeFilter" @clear="resetFilters" />
      <ApiErrorNotice :error="error"><el-button size="small" @click="loadTickets">重试</el-button></ApiErrorNotice>
      <AppTable :error="error" :rows="rows" :loading="loading" :empty-text="error ? '报障登记加载失败，请重试' : !loaded ? '报障登记尚未读取' : filterItems.length ? '没有找到匹配登记，可调整或清空筛选' : customerView ? '暂无本人报障登记' : '当前租户暂无报障登记'">
        <el-table-column label="登记号 / 标题" min-width="250"><template #default="{ row }"><el-button type="primary" link class="asset-link ticket-detail-link" @click="openDetail(row.id)">{{ row.title }}</el-button><span class="cell-sub mono">{{ row.receipt_no }}</span></template></el-table-column>
        <el-table-column label="故障分类 / 设备" min-width="160"><template #default="{ row }">{{ row.category }}<span class="cell-sub">{{ row.asset_name || '设备不确定' }}</span></template></el-table-column>
        <el-table-column label="影响 / 紧急程度" min-width="150"><template #default="{ row }">{{ impactScopeLabel[row.impact_scope as keyof typeof impactScopeLabel] }}<span class="cell-sub">{{ urgencyLabel[row.urgency as keyof typeof urgencyLabel] }}</span></template></el-table-column>
        <el-table-column label="登记 / 正式状态" min-width="175"><template #default="{ row }"><el-tag type="info">{{ row.registration_status === 'REGISTERED' ? '已登记' : row.registration_status }}</el-tag><span class="cell-sub">{{ row.state === null ? '正式工单状态未设' : row.state }}</span></template></el-table-column>
        <el-table-column label="提交时间" min-width="170"><template #default="{ row }">{{ displayTime(row.created_at) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button type="primary" link class="ticket-detail-link" @click="openDetail(row.id)">查看详情</el-button></template></el-table-column>
        <template #empty-action><el-button v-if="loaded && !error && filterItems.length" @click="resetFilters">清空筛选，查看全部登记</el-button></template>
      </AppTable>
      <div class="table-footer"><span>{{ customerView ? '仅本人登记' : '当前租户只读' }} · 本页 {{ loading || error || !loaded ? '—' : rows.length }} 条</span><div class="cursor-pagination ticket-pagination"><el-select :model-value="pageSize" :disabled="loading" aria-label="报障每页条数" @change="resizePage"><el-option v-for="size in pageSizes.filter(size => size <= pageSizeLimit)" :key="size" :label="`${size} 条 / 页`" :value="size" /></el-select><el-button v-if="queryString('cursor') && !cursorHistory.length" :disabled="loading" @click="firstPage">返回首页</el-button><el-button v-else :disabled="loading || !cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="loading || !hasMore" @click="nextPage">下一页</el-button></div></div>
    </div>
    <TicketReportForm v-if="mayReport" v-model="createOpen" @accepted="recordAcceptance" @registered="showRegistered" />
    <TicketCommentWorkspace ref="commentWorkspace" v-model="commentOpen" :ticket="commentTarget" />
    <el-button v-if="commentTarget && commentTarget.tenant_id === session?.tenant.id && (!customerView || commentTarget.reporter_id === session?.user.id) && can('ticket_comments:read')" class="retained-worklog-button" @click="commentOpen = true">恢复“{{ commentTarget.receipt_no }}”的客户可见评论面板</el-button>
    <InternalNoteWorkspace ref="noteWorkspace" v-model="noteOpen" :ticket="noteTarget" />
    <el-button v-if="noteTarget && noteTarget.tenant_id === session?.tenant.id && can('ticket_notes:read_internal')" class="retained-worklog-button" @click="noteOpen = true">恢复“{{ noteTarget.receipt_no }}”的内部备注面板</el-button>
    <WorklogWorkspace ref="worklogWorkspace" v-model="worklogOpen" :ticket="worklogTarget" />
    <el-button v-if="worklogTarget && can('worklogs:read_own')" class="retained-worklog-button" @click="worklogOpen = true">恢复“{{ worklogTarget.receipt_no }}”的本人工时面板</el-button>
    <el-drawer v-model="detailOpen" title="报障登记详情" size="820px" class="ticket-detail-drawer" destroy-on-close>
      <RetainedPhotoNotice v-if="retainedPhotos" :target-id="photoTargetId" :ticket="appendTicket" :count="detailPhotos.rows.value.length" :can-discard="photoWorkspace.canDiscard.value" @resume="openDetail(photoTargetId)" @discard="discardRetainedPhotos" />
      <ApiErrorNotice :error="detailError"><el-button size="small" @click="loadDetail()">重试详情查询</el-button></ApiErrorNotice>
      <el-skeleton v-if="detailLoading" :rows="9" animated />
      <template v-if="ticket">
        <div class="detail-title"><span class="eyebrow">报障登记</span><h2>{{ ticket.title }}</h2><el-tag type="info">已登记 · 正式工单规则待确认</el-tag><p class="ticket-receipt mono">{{ ticket.receipt_no }}</p></div>
        <el-alert type="warning" :closable="false" show-icon title="本平台为一期工单权威；正式处理状态与时限尚未设定，不能据登记时间推断响应或恢复承诺。" />
        <section class="ticket-detail-summary"><div><span>故障分类</span><strong>{{ ticket.category }}</strong></div><div><span>关联设备</span><strong>{{ ticket.asset_name || '不确定' }}</strong></div><div><span>影响范围</span><strong>{{ impactScopeLabel[ticket.impact_scope] }}</strong></div><div><span>紧急程度</span><strong>{{ urgencyLabel[ticket.urgency] }}</strong></div><div><span>正式工单状态</span><strong>{{ ticket.state === null ? '— · 未设' : ticket.state }}</strong></div><div><span>优先级</span><strong>{{ ticket.priority === null ? '— · 未设' : ticket.priority }}</strong></div><div><span>响应截止</span><strong>{{ ticket.response_due_at === null ? '— · 未设' : displayTime(ticket.response_due_at) }}</strong></div><div><span>恢复截止</span><strong>{{ ticket.recovery_due_at === null ? '— · 未设' : displayTime(ticket.recovery_due_at) }}</strong></div></section>
        <div class="ticket-detail-actions"><el-button v-if="can('ticket_comments:read')" @click="openComments">查看或追加客户可见评论</el-button><el-button v-if="can('ticket_notes:read_internal')" @click="openInternalNotes">打开内部备注</el-button><el-button v-if="can('worklogs:read_own')" type="primary" @click="openWorklogs">打开本人工时</el-button></div>
        <section class="detail-section"><h3>问题描述</h3><p class="ticket-description">{{ ticket.description }}</p></section>
        <section class="detail-section"><h3>联系人与登记信息</h3><div class="ticket-detail-summary"><div><span>联系人</span><strong>{{ ticket.contact_name }}</strong></div><div><span>联系电话</span><strong>{{ ticket.contact_phone }}</strong></div><div><span>提交时间</span><strong>{{ displayTime(ticket.created_at) }}</strong></div><div><span>更新时间</span><strong>{{ displayTime(ticket.updated_at) }}</strong></div></div><p class="muted">联系信息尚未核验账号绑定关系 · 登记来源：客户报障 · 工单权威：本平台，正式规则待确认</p></section>
        <div class="detail-section ticket-registration-operation" aria-label="登记操作回读">
          <h3>登记操作</h3>
          <p class="muted">仅查询本次登记操作。查询失败不会撤销已登记报障，不据等待时间推断处理进度。</p>
          <ApiErrorNotice v-if="operationRead.status === 'error'" :error="operationRead.error" />
          <el-skeleton v-if="operationRead.status === 'loading'" :rows="3" animated />
          <el-descriptions v-if="registrationOperation" :column="1" border class="asset-descriptions">
            <el-descriptions-item label="操作状态"><el-tag type="info">{{ registrationOperation.status }}</el-tag><span class="cell-sub">待正式工单规则配置</span></el-descriptions-item>
            <el-descriptions-item label="规则门禁"><span class="ticket-description">{{ registrationOperation.error.message }}</span><span class="cell-sub mono">{{ registrationOperation.error.code }}</span></el-descriptions-item>
            <el-descriptions-item label="待确认事项">{{ registrationOperation.error.question_ids.join(' / ') || '服务端未提供待确认编号' }}</el-descriptions-item>
            <el-descriptions-item label="处理进度">— · 服务端未提供</el-descriptions-item>
            <el-descriptions-item label="操作更新时间">{{ displayTime(registrationOperation.updated_at) }}</el-descriptions-item>
          </el-descriptions>
          <el-button :loading="operationRead.status === 'loading'" @click="readRegistrationOperation">{{ operationRead.status === 'error' ? '重新查询登记操作' : '刷新登记操作' }}</el-button>
        </div>
        <div class="detail-section"><h3>登记记录</h3><el-timeline class="ticket-history"><el-timeline-item v-for="(item, index) in ticket.history" :key="`${item.occurred_at}:${index}`" :timestamp="displayTime(item.occurred_at)" placement="top"><strong>{{ item.action === 'ADD_ATTACHMENTS' ? '报障照片已追加' : '报障已登记' }}</strong><p>{{ item.action === 'ADD_ATTACHMENTS' ? '仅追加附件引用；原报障正文与联系信息保留。' : '正式工单规则待确认，尚无处理阶段记录。' }}</p><p v-if="!customerView" class="mono">操作主体：{{ item.actor_id }} · v{{ item.version }}</p></el-timeline-item></el-timeline></div>
        <ReportPhotoPanel :queue="detailPhotos" :existing="ticket.attachments" :selectable="matchingPhotoTarget && detailAppender.permitted.value" :show-selections="matchingPhotoTarget" :disabled="detailLoading" :appender="matchingPhotoTarget ? detailAppender : undefined" />
        <div class="detail-section"><h3>后续处理</h3><p class="muted">接单、处置与客户确认将在后续功能按实际规则落实。</p></div>
        <el-descriptions v-if="!customerView" :column="1" border class="asset-descriptions"><el-descriptions-item label="记录 ID"><span class="mono">{{ ticket.id }}</span></el-descriptions-item><el-descriptions-item label="租户 ID"><span class="mono">{{ ticket.tenant_id }}</span></el-descriptions-item><el-descriptions-item label="报障人 ID"><span class="mono">{{ ticket.reporter_id }}</span></el-descriptions-item><el-descriptions-item label="登记操作 ID"><span class="mono">{{ ticket.operation_id }}</span></el-descriptions-item><el-descriptions-item label="版本">v{{ ticket.version }}</el-descriptions-item><el-descriptions-item label="外部同步">不适用 · 未创建外部工单</el-descriptions-item></el-descriptions>
      </template>
      <template #footer><el-button :loading="detailLoading" :disabled="detailAppender.busy.value || detailAppender.reading.value" @click="loadDetail()">刷新详情</el-button><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-drawer>
  </section>
</template>

<style>
.retained-worklog-button{margin-top:12px;max-width:100%;height:auto;min-height:44px;white-space:normal;overflow-wrap:anywhere}.ticket-detail-drawer{max-width:100vw}.ticket-receipt{margin:16px 0 0;color:#61718a}.ticket-description{white-space:pre-wrap;overflow-wrap:anywhere}.ticket-history{padding-left:4px;margin-top:20px}.ticket-history p{font-size:12px;margin:8px 0}.ticket-detail-drawer .detail-section p{font-size:12px}
@media(max-width:760px){.tickets-page .page-heading .el-button{min-height:44px}.tickets-page .ticket-detail-link{min-height:44px}.ticket-pagination .el-button{min-height:44px}.ticket-pagination .el-select__wrapper{min-height:44px}.ticket-filter-form .el-input__wrapper,.ticket-filter-form .el-select__wrapper,.ticket-filter-form .el-button{min-height:44px}.ticket-detail-drawer .el-drawer__footer .el-button{min-height:44px}}
@media(max-width:760px){.ticket-registration-operation .el-button{min-height:44px}}
.tickets-page .ticket-register-panel{padding:0;margin-top:12px;overflow:hidden}.tickets-page .ticket-register-panel .filter-form{padding:14px 18px 0;margin:0}.tickets-page .ticket-register-panel .table-footer{padding:12px 18px}.ticket-detail-summary{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px 28px;padding:20px 0}.ticket-detail-summary>div{display:grid;grid-template-columns:100px minmax(0,1fr);gap:12px;font-size:12px}.ticket-detail-summary span{color:#8a96a4}.ticket-detail-summary strong{font-weight:400;overflow-wrap:anywhere}.ticket-detail-actions{display:flex;gap:8px;flex-wrap:wrap;border-top:1px solid #edf1f7;border-bottom:1px solid #edf1f7;padding:14px 0}.ticket-detail-drawer .detail-section{margin-top:22px}.ticket-detail-drawer .detail-section h3{font-size:14px;font-weight:500;border-left:3px solid #2d79f6;padding-left:10px}.ticket-detail-drawer .detail-title h2{font-size:20px}.ticket-detail-drawer .ticket-registration-operation .asset-descriptions{margin-bottom:14px}@media(max-width:760px){.ticket-detail-summary{grid-template-columns:1fr}.ticket-detail-actions .el-button{min-height:44px;margin-left:0}}
</style>

<style scoped>
.ticket-filter-form :deep(.el-form-item){min-width:0}
.ticket-filter-form :deep(.filter-actions .el-form-item__content){gap:8px}
.ticket-filter-form :deep(.filter-actions .el-button){margin-left:0}
@media(max-width:760px){.tickets-page .ticket-filter-form{grid-template-columns:minmax(0,1fr)}.ticket-filter-form :deep(.el-form-item){grid-column:auto;width:100%}.ticket-filter-form :deep(.el-input),.ticket-filter-form :deep(.el-select){width:100%}.ticket-filter-form :deep(.filter-actions .el-button){flex:1}.ticket-pagination{flex-wrap:wrap;max-width:100%}}
</style>
