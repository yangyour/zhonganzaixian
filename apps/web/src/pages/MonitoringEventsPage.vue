<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import { session, switchingTenant, contextGeneration } from '../stores/session'
import { isNavigationFailure, NavigationFailureType, onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { exportUuid } from '../utils/assetExportJobs'
import { utcInstantNanos } from '../utils/utcInstant'
import { compactTime, displayTime } from '../utils/display'
import {
  createMonitoringEventWorkspace,
  canTransition,
  validMaintenanceTime,
  emptyMonitoringEventCenterState,
  type FunnelWindow,
  type MonitoringEventCenterState,
  type EventSeverity,
  type MonitorState,
  type HandlingState,
  type SuppressionState,
  type MaintenanceWindowEntry,
  type MonitoringQuarantineItem,
  type AutoTicketStatus,
} from '../utils/monitoringEvents'

const severityLabels: Record<EventSeverity, string> = { DISASTER: '灾难', HIGH: '严重', AVERAGE: '一般', WARNING: '警告', INFO: '信息' }
const monitorLabels: Record<MonitorState, string> = { PROBLEM: '故障中', RECOVERED: '已恢复' }
const handlingLabels: Record<HandlingState, string> = { NEW: '待确认', ACKNOWLEDGED: '已确认', IN_PROGRESS: '处理中', PENDING_REVIEW: '待复核', RESOLVED: '已解决', CLOSED: '已关闭' }
const suppressionLabels: Record<SuppressionState, string> = { NONE: '未抑制', FLAP_SILENCED: '抖动静默', DEPENDENCY_SUPPRESSED: '依赖抑制', MAINTENANCE_SILENCED: '维护静默', INFO_DEFAULT_SILENT: '信息级静默' }
// Display-only labels typed against the reader's unions; the original codes stay in each badge title.
type WindowStatus = MaintenanceWindowEntry['status']
type QuarantineReason = MonitoringQuarantineItem['quarantine_reason']
const windowStatusLabels: Record<WindowStatus, string> = { ACTIVE: '进行中', SCHEDULED: '已计划', ENDED: '已结束', CANCELLED: '已取消' }
const windowStatusTone: Record<WindowStatus, string> = { ACTIVE: 'maint', SCHEDULED: 'info', ENDED: 'unknown', CANCELLED: 'unknown' }
const silenceLabels: Record<MaintenanceWindowEntry['silence_mode'], string> = { SUPPRESS_NOTIFY: '采集继续 · 抑制通知', PAUSE_COLLECTION: '暂停采集' }
// Source self-claims only; none of them establishes object ownership or authorization.
const quarantineReasonLabels: Record<QuarantineReason, string> = { UNKNOWN_MAPPING: '来源未映射', CROSS_TENANT_CLAIM: '跨租户声明', SOURCE_KEY_CONFLICT: '来源键冲突' }
const decisionLabels: Record<NonNullable<MonitoringQuarantineItem['decision']>, string> = { BIND_ASSET: '已绑定资产', DISCARD: '已丢弃' }
const attemptStatusLabels: Record<AutoTicketStatus, string> = { SUCCEEDED: '已登记', RETRY_SCHEDULED: '等待重试', QUARANTINED_MANUAL_REQUIRED: '待人工补建', MANUAL_CREATED: '已人工补建', SUPPRESSED_SKIPPED: '已抑制跳过' }
const attemptStatusTone: Record<AutoTicketStatus, string> = { SUCCEEDED: 'ok', MANUAL_CREATED: 'ok', RETRY_SCHEDULED: 'warn', QUARANTINED_MANUAL_REQUIRED: 'danger', SUPPRESSED_SKIPPED: 'unknown' }
const shortId = (value: string | null | undefined) => value ? `…${value.slice(-6)}` : '—'

const route = useRoute(), router = useRouter(), navigating = ref(false), navigationError = ref('')
const state = ref<MonitoringEventCenterState>(emptyMonitoringEventCenterState())
const workspace = createMonitoringEventWorkspace(api, next => {
  if (state.value.attempt && !next.attempt && !next.error) clearDrafts()
  state.value = next
})

const capabilityScope = computed(() => JSON.stringify([...new Set(session.value?.capabilities ?? [])].sort()))
const canRead = computed(() => Boolean(session.value?.mode === 'remote_test' && ['msp', 'padmin'].includes(session.value.user.role) && session.value.capabilities.includes('tickets:read') && !switchingTenant.value))
const canWrite = computed(() => canRead.value && Boolean(session.value?.capabilities.includes('ticket_notes:write_internal')))
const canAdminConfirm = computed(() => canRead.value && session.value?.user.role === 'padmin' && Boolean(session.value?.capabilities.includes('configurations:write')))
const locked = computed(() => state.value.busy !== null || state.value.attempt !== null)
const rootCauseNotes = ref<Record<string, string>>({})
const quarantineAssets = ref<Record<string, string>>({})
const quarantineReasons = ref<Record<string, string>>({})
const manualTicketReasons = ref<Record<string, string>>({})
const windowEnds = ref<Record<string, string>>({})
function clearDrafts() { rootCauseNotes.value = {}; quarantineAssets.value = {}; quarantineReasons.value = {}; manualTicketReasons.value = {}; windowEnds.value = {} }
const validReason = (s: string | undefined) => Boolean(s?.trim() && Array.from(s).length <= 500)
const validManualReason = (s: string | undefined) => validReason(s) && Array.from(s!.trim()).length <= 492
function validExtension(id: string, endsAt: string) { const next = utcInstantNanos(windowEnds.value[id]), previous = utcInstantNanos(endsAt); return validMaintenanceTime(windowEnds.value[id]) && next !== null && previous !== null && next > previous }

// Compare context fields, not array identity: navigation re-reads an equivalent session before route-update guards.
watch(
  [() => contextGeneration.value, () => session.value?.tenant.id, () => session.value?.user.id, () => session.value?.permission_version, () => session.value?.user.role, () => capabilityScope.value, () => canRead.value, () => canWrite.value, () => canAdminConfirm.value],
  () => {
    clearDrafts()
    const s = session.value
    workspace.reset(s && canRead.value ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, canWriteInternal: canWrite.value, canAdminConfirm: canAdminConfirm.value } : null)
    if (canRead.value) { void workspace.load('24h'); void workspace.selectEventDetail(route.query.event_id) }
  },
  { immediate: true, flush: 'sync' },
)

watch(() => route.query.event_id, value => { navigationError.value = ''; if (canRead.value) void workspace.selectEventDetail(value) }, { flush: 'sync' })

function mayLeave() { return !workspace.unresolved() }
onBeforeRouteLeave(mayLeave)
onBeforeRouteUpdate(mayLeave)
const unregister = contextTransitionGuards.register(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (workspace.unresolved()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { unregister(); window.removeEventListener('beforeunload', beforeUnload); workspace.dispose() })

async function selectEvent(id?: string) {
  if (!canRead.value || workspace.unresolved() || navigating.value || id !== undefined && !exportUuid(id)) return
  navigating.value = true; navigationError.value = ''
  const query = { ...route.query }; if (id === undefined) delete query.event_id; else query.event_id = id
  try {
    const failure = await router.push({ name: 'monitoring-events', query })
    if (isNavigationFailure(failure) && !isNavigationFailure(failure, NavigationFailureType.duplicated)) navigationError.value = '事件切换未完成，请先处理当前操作。'
  } catch { navigationError.value = '事件切换失败，请重试。' }
  finally { navigating.value = false }
}

function switchWindow(w: FunnelWindow) {
  void workspace.load(w)
}
</script>

<template>
  <section class="page-section monitoring-events-page" aria-labelledby="monitoring-events-title">
    <h1 class="page-title-sr" id="monitoring-events-title">监控事件中心与降噪漏斗</h1>

    <p v-if="!canRead" role="status">当前没有监控事件读取权限，或正在切换租户。</p>
    <div v-if="state.error" class="alert alert-error" role="alert">
      <ApiErrorNotice :error="state.error" />
      <p v-if="state.attempt">原操作结果尚未确认。输入、刷新和离开已锁定，请用原请求重试核对。</p>
      <button v-if="state.attempt" type="button" :disabled="state.busy !== null" @click="workspace.retry()">原请求与幂等键重试</button>
    </div>

    <p v-if="navigationError" role="alert">{{ navigationError }}</p>
    <section v-if="canRead && (state.detailId || state.detailError)" class="card event-detail" aria-label="监控事件详情" :aria-busy="state.detailBusy">
      <div class="actions"><button type="button" :disabled="state.busy === 'write' || state.attempt !== null || navigating" @click="selectEvent()">关闭事件详情</button></div>
      <p v-if="state.detailBusy" role="status">正在读取所选事件详情…</p>
      <div v-if="state.detailError" role="alert">
        <p v-if="state.detailError instanceof ApiError && state.detailError.status === 404">事件不存在或当前无权查看。</p>
        <ApiErrorNotice :error="state.detailError" />
        <button v-if="state.detailId" type="button" :disabled="locked" @click="workspace.loadEventDetail(state.detailId)">重新读取事件详情</button>
      </div>
      <aside v-if="state.detail" aria-label="监控事件详情抽屉">
        <div class="event-detail-head">
          <span class="event-status sev" :data-state="state.detail.event.severity" :title="state.detail.event.severity">{{ severityLabels[state.detail.event.severity] }}</span>
          <h2>事件详情：{{ state.detail.event.rule_name }} <span class="event-occurrences">×{{ state.detail.event.occurrence_count }}</span></h2>
          <span class="event-status" :data-state="state.detail.event.monitor_state">{{ monitorLabels[state.detail.event.monitor_state] }}</span>
          <span class="event-status" :data-state="state.detail.event.handling_state">{{ handlingLabels[state.detail.event.handling_state] }}</span>
        </div>
        <dl class="mop-facts event-detail-facts">
          <div><dt>规则代码</dt><dd class="mono">{{ state.detail.event.rule_code }}</dd></div>
          <div><dt>问题发生</dt><dd>{{ displayTime(state.detail.event.problem_at) }}</dd></div>
          <div><dt>最近发生</dt><dd>{{ displayTime(state.detail.event.last_occurred_at) }}</dd></div>
          <div><dt>首次确认时间 (first_ack_at)</dt><dd>{{ state.detail.event.first_ack_at ?? '未确认' }}</dd></div>
          <div><dt>抑制状态</dt><dd>{{ suppressionLabels[state.detail.event.suppression_state] }}</dd></div>
          <div><dt>复盘门槛状态</dt><dd class="mono">{{ state.detail.event.review_policy_status }}</dd></div>
        </dl>
        <p class="event-notice">通知状态说明：<strong>{{ state.detail.event.notification_empty_state_label }}</strong></p>
        <h3>合并的原始事件成员（{{ state.detail.members.length }} 条）</h3>
        <ul class="event-members"><li v-for="m in state.detail.members" :key="m.id"><span class="member-seq">#{{ m.sequence_no }}</span><span class="mono">{{ m.source_event_id }}</span><span class="event-status" :data-state="m.source_phase === 'PROBLEM' ? 'PROBLEM' : 'RECOVERED'">{{ m.source_phase }}</span><time :datetime="m.occurred_at">{{ m.occurred_at }}</time></li></ul>
      </aside>
    </section>
    <p v-if="state.workspaceBusy" role="status">正在读取最新事件…</p>
    <div v-if="canRead" class="event-workspace">
      <section class="card event-list-card" aria-label="监控事件主列表" :aria-busy="state.workspaceBusy">
        <div class="event-list-toolbar">
          <div class="event-list-caption"><h2>监控事件流</h2><span class="event-count-note">{{ state.workspace ? `本次 ${state.workspace.events.length} 条 · 最多 25 条` : '等待事件数据' }}</span><span class="event-scroll-hint">左右滑动查看状态与操作</span></div>
          <div class="actions"><span v-if="state.workspace" class="event-count-note">更新于 {{ displayTime(state.workspace.observed_at) }}</span><button type="button" :disabled="!canRead || locked" @click="switchWindow('24h')">刷新事件</button></div>
        </div>
        <template v-if="state.workspace">
        <div class="event-table-scroll" role="region" aria-label="监控事件列表，可横向滚动" tabindex="0">
        <table>
          <thead>
            <tr>
              <th scope="col">事件规则 / 合并次数</th>
              <th scope="col">级别</th>
              <th scope="col">监控状态</th>
              <th scope="col">人工处理状态</th>
              <th scope="col">抑制状态</th>
              <th scope="col">通知说明</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ev in state.workspace.events" :key="ev.id">
              <td>
                <strong>{{ ev.rule_name }}</strong>
                <span v-if="ev.occurrence_count > 1" class="event-occurrences" :aria-label="`合并 ${ev.occurrence_count} 次`">×{{ ev.occurrence_count }}</span>
              </td>
              <td><span class="event-status sev" :data-state="ev.severity" :title="ev.severity">{{ severityLabels[ev.severity] }}</span></td>
              <td><span class="event-status" :data-state="ev.monitor_state" :title="ev.monitor_state">{{ monitorLabels[ev.monitor_state] }}</span></td>
              <td><span class="event-status" :data-state="ev.handling_state" :title="ev.handling_state">{{ handlingLabels[ev.handling_state] }}</span></td>
              <td><span class="event-status" :data-state="ev.suppression_state" :title="ev.suppression_state">{{ suppressionLabels[ev.suppression_state] }}</span><small v-if="ev.suppression_reason" class="event-cell-note">{{ ev.suppression_reason }}</small></td>
              <td class="event-notification">{{ ev.notification_empty_state_label }}</td>
              <td>
                <div class="event-row-actions" :class="{ 'has-review': canWrite && ev.handling_state === 'PENDING_REVIEW' }">
                <button class="event-detail-button" type="button" :disabled="state.busy === 'write' || state.attempt !== null || navigating" @click="selectEvent(ev.id)">查看详情</button>
                <button
                  v-if="canWrite && canTransition(ev, 'ACKNOWLEDGE')"
                  :disabled="locked"
                  type="button"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'ACKNOWLEDGE', root_cause_note: null })"
                >
                  确认事件
                </button>
                <button v-if="canWrite && canTransition(ev, 'START')" type="button" :disabled="locked"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'START', root_cause_note: null })">开始处理</button>
                <button v-if="canWrite && canTransition(ev, 'MARK_PENDING_REVIEW')" type="button" :disabled="locked"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'MARK_PENDING_REVIEW', root_cause_note: null })">转为待复核</button>
                <button v-if="canWrite && canTransition(ev, 'CLOSE')" type="button" :disabled="locked"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'CLOSE', root_cause_note: null })">关闭事件</button>
                </div>
                <details v-if="canWrite && ev.handling_state === 'PENDING_REVIEW'" class="event-review" :open="state.attempt !== null && Boolean(rootCauseNotes[ev.id])">
                  <summary>人工复核<span v-if="rootCauseNotes[ev.id]"> · 已填写</span></summary>
                  <div class="event-review-form">
                    <label :for="`root-cause-${ev.id}`">根因与处置说明</label>
                    <textarea :id="`root-cause-${ev.id}`" v-model="rootCauseNotes[ev.id]" rows="4" :disabled="locked" :aria-label="`${ev.rule_name}根因说明`" :aria-describedby="`root-cause-help-${ev.id}`" />
                    <small :id="`root-cause-help-${ev.id}`" class="event-cell-note">至少20个字符，最多500个字符 · 已输入 {{ Array.from(rootCauseNotes[ev.id] ?? '').length }}</small>
                    <button class="primary" type="button" :disabled="locked || !canTransition(ev, 'RESOLVE', rootCauseNotes[ev.id] ?? null)"
                      @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'RESOLVE', root_cause_note: rootCauseNotes[ev.id] ?? null })">确认解决</button>
                  </div>
                </details>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
        <p v-if="!state.workspace.events.length" role="status">暂无监控事件</p>
        </template>
        <p v-else-if="!state.workspaceBusy" class="event-empty" role="status">事件尚未读取成功，请刷新重试。</p>
      </section>

      <template v-if="state.workspace">
      <section class="card event-sub-card" aria-label="计划维护窗口">
        <header class="event-sub-head"><div class="event-list-caption"><h2>计划维护窗口与 SLA 排除确认</h2><span class="event-count-note">{{ state.workspace.maintenance_windows.length }} 个窗口</span></div><span class="event-count-note">窗口内通知被抑制、事件照常记录；SLA 排除须管理员确认后生效</span></header>
        <p v-if="!state.workspace.maintenance_windows.length" class="event-empty">暂无计划维护窗口</p>
        <div v-else class="event-sub-scroll" role="region" aria-label="计划维护窗口，可横向滚动" tabindex="0">
          <table class="mop-table event-sub-table">
            <thead><tr><th scope="col">窗口名称 / 目标</th><th scope="col">起止时间（UTC）</th><th scope="col">状态</th><th scope="col">静默模式</th><th scope="col">SLA 排除</th><th scope="col">创建人</th><th scope="col">操作</th></tr></thead>
            <tbody>
              <tr v-for="w in state.workspace.maintenance_windows" :key="w.id">
                <td><strong>{{ w.name }}</strong><small>资产 <span class="mono" :title="w.asset_id">{{ shortId(w.asset_id) }}</span><template v-if="w.reason"> · {{ w.reason }}</template></small></td>
                <td class="event-time-cell"><time :datetime="w.starts_at">{{ w.starts_at }}</time><small>至 <time :datetime="w.ends_at">{{ w.ends_at }}</time></small></td>
                <td><span class="mop-badge" :class="windowStatusTone[w.status]" :title="w.status">{{ windowStatusLabels[w.status] ?? w.status }}</span></td>
                <td>{{ silenceLabels[w.silence_mode] ?? w.silence_mode }}</td>
                <td>{{ w.sla_exclusion_requested ? '已申请' : '未申请' }}<small><strong :class="{ 'window-confirmed': w.sla_exclusion_confirmed }">{{ w.sla_exclusion_confirmed ? '已记录管理员确认' : '未记录管理员确认' }}</strong></small></td>
                <td><span class="mono" :title="w.created_by">{{ shortId(w.created_by) }}</span></td>
                <td class="window-actions">
                  <button
                    v-if="canAdminConfirm && w.sla_exclusion_requested && !w.sla_exclusion_confirmed"
                    :disabled="locked"
                    type="button"
                    @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'CONFIRM_SLA_EXCLUSION', new_ends_at: null })"
                  >
                    管理员确认SLA排除
                  </button>
                  <div v-if="canWrite && (w.status === 'ACTIVE' || w.status === 'SCHEDULED')" class="window-extend">
                    <label :for="`window-end-${w.id}`">延长至（UTC，含Z，最多毫秒精度）</label>
                    <input :id="`window-end-${w.id}`" v-model="windowEnds[w.id]" :disabled="locked" placeholder="例如 2026-10-24T02:00:00Z" />
                    <div class="window-buttons"><button type="button" :disabled="locked || !validExtension(w.id, w.ends_at)" @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'EXTEND', new_ends_at: windowEnds[w.id] ?? null })">延长维护</button>
                    <button type="button" :disabled="locked" @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'END', new_ends_at: null })">结束维护</button></div>
                  </div>
                  <span v-else-if="!(canAdminConfirm && w.sla_exclusion_requested && !w.sla_exclusion_confirmed)" class="event-count-note">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card event-sub-card" aria-label="监控隔离区与自动建单补偿">
        <h2 class="sr-only">监控隔离区（{{ state.workspace.quarantine_items.length }}）与自动建单补偿（{{ state.workspace.autoticket_attempts.length }}）</h2>
        <p v-if="!state.workspace.quarantine_items.length && !state.workspace.autoticket_attempts.length" class="event-empty">暂无隔离事件或自动建单补偿记录</p>
        <div class="event-sub-grid">
          <div v-if="state.workspace.quarantine_items.length" class="event-sub-block">
            <header class="event-sub-head"><div class="event-list-caption"><h3>监控隔离区</h3><span class="mop-pill-count">{{ state.workspace.quarantine_items.length }}</span></div><span class="event-count-note">来源未知或跨租户事件不自动派发，须人工核验后绑定或丢弃</span></header>
            <ul class="event-rows">
              <li v-for="q in state.workspace.quarantine_items" :key="q.quarantine_id" :class="{ actionable: canWrite && !q.decision }">
                <div class="event-row-facts">
                  <strong class="mono">隔离事件 {{ q.source_event_id }}</strong>
                  <span class="mop-badge warn" :title="q.quarantine_reason">{{ quarantineReasonLabels[q.quarantine_reason] ?? q.quarantine_reason }}</span>
                  <span class="mop-badge" :class="q.decision ? 'ok' : 'unknown'" :title="q.decision ?? undefined">处置：{{ q.decision ? decisionLabels[q.decision] ?? q.decision : '待人工决策' }}</span>
                  <small>发生 <time :datetime="q.occurred_at">{{ compactTime(q.occurred_at) }}</time> · 接收 <time :datetime="q.received_at">{{ compactTime(q.received_at) }}</time> · {{ q.source_phase }}</small>
                </div>
                <div v-if="canWrite && !q.decision" class="event-row-form">
                  <label :for="`quarantine-asset-${q.quarantine_id}`">核验后绑定的资产UUID</label>
                  <input :id="`quarantine-asset-${q.quarantine_id}`" v-model="quarantineAssets[q.quarantine_id]" :disabled="locked" placeholder="仅绑定资产时填写" />
                  <label :for="`quarantine-reason-${q.quarantine_id}`">人工处置原因</label>
                  <textarea :id="`quarantine-reason-${q.quarantine_id}`" v-model="quarantineReasons[q.quarantine_id]" rows="2" :disabled="locked" />
                  <div class="window-buttons"><button type="button" :disabled="locked || !exportUuid(quarantineAssets[q.quarantine_id]) || !validReason(quarantineReasons[q.quarantine_id])"
                    @click="workspace.submitQuarantineDecision(q.quarantine_id, { decision: 'BIND_ASSET', bound_asset_id: quarantineAssets[q.quarantine_id] ?? null, reason: quarantineReasons[q.quarantine_id] ?? '' })">绑定资产并回灌</button>
                  <button type="button" :disabled="locked || !validReason(quarantineReasons[q.quarantine_id])"
                    @click="workspace.submitQuarantineDecision(q.quarantine_id, { decision: 'DISCARD', bound_asset_id: null, reason: quarantineReasons[q.quarantine_id] ?? '' })">丢弃隔离事件</button></div>
                </div>
              </li>
            </ul>
          </div>
          <div v-if="state.workspace.autoticket_attempts.length" class="event-sub-block">
            <header class="event-sub-head"><div class="event-list-caption"><h3>自动建单补偿</h3><span class="mop-pill-count">{{ state.workspace.autoticket_attempts.length }}</span></div><span class="event-count-note">失败按退避重试，仍失败进入隔离并可人工补建</span></header>
            <ul class="event-rows">
              <li v-for="a in state.workspace.autoticket_attempts" :key="a.id" :class="{ actionable: canWrite && (a.status === 'QUARANTINED_MANUAL_REQUIRED' || a.status === 'RETRY_SCHEDULED') }">
                <div class="event-row-facts">
                  <strong>事件 <span class="mono" :title="a.event_id">{{ a.event_id }}</span></strong>
                  <span class="mop-badge" :class="attemptStatusTone[a.status] ?? 'unknown'" :title="a.status">{{ attemptStatusLabels[a.status] ?? a.status }}</span>
                  <small>重试 {{ a.attempt_count }}/{{ a.max_retries }}（退避 {{ a.backoff_schedule_sec }} 秒）· 映射优先级 {{ a.mapped_priority ?? '—' }} · 映射策略 <span class="mono">{{ a.mapping_policy_status }}</span></small>
                </div>
                <p v-if="a.next_retry_at" class="field-help">计划最早重试（UTC）：<time :datetime="a.next_retry_at">{{ a.next_retry_at }}</time>。是否执行取决于 Worker 配置及到期时的权限、告警和抑制状态检查。</p>
                <p v-else class="field-help">当前记录没有安排自动重试时间。</p>
                <p v-if="a.last_error" class="field-help">最近处理说明：{{ a.last_error }}</p>
                <p v-if="a.status === 'SUCCEEDED' || a.status === 'MANUAL_CREATED'" class="field-help">登记已保存，不等于正式工单流程已启动或外部通知已送达。</p>
                <div v-if="canWrite && (a.status === 'QUARANTINED_MANUAL_REQUIRED' || a.status === 'RETRY_SCHEDULED')" class="event-row-form">
                  <label :for="`manual-ticket-reason-${a.id}`">人工补建原因（最多492个字符）</label>
                  <textarea :id="`manual-ticket-reason-${a.id}`" v-model="manualTicketReasons[a.id]" rows="2" :disabled="locked" />
                  <div class="window-buttons"><button type="button" :disabled="locked || !validManualReason(manualTicketReasons[a.id])"
                    @click="workspace.submitManualAutoTicket(a.id, { expected_version: a.version, reason: manualTicketReasons[a.id] ?? '' })">人工补建工单</button></div>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </section>
      </template>
    </div>
  </section>
</template>

<style scoped>
.monitoring-events-page,.event-workspace { min-width:0; max-width:100%; }
.event-workspace { display:grid; grid-template-columns:minmax(0,1fr); gap:12px; }
.event-workspace > .card,.event-detail {
  min-width:0; padding:14px 16px; overflow-wrap:anywhere;
  background:#fff; border:1px solid var(--mop-line,#e2e8f0); border-radius:8px;
  box-shadow:var(--mop-shadow-1,0 1px 2px rgba(16,42,84,.04));
}
.event-detail { margin-bottom:12px; border-top:3px solid var(--mop-primary,#2165c5); }
.event-detail .actions { justify-content:flex-end; }
.event-detail-head { display:flex; align-items:center; flex-wrap:wrap; gap:8px 10px; margin:4px 0 12px; }
.event-detail-head h2 { margin:0!important; font-size:16px!important; }
.event-detail-facts { margin:0 0 10px; }
.event-notice { margin:0 0 12px; padding:8px 12px; border-radius:6px; background:var(--mop-sunken,#f7f9fc); font-size:13px; }
.event-detail h3 { margin:12px 0 6px; font-size:13px; }
.event-members li { display:flex; align-items:center; flex-wrap:wrap; gap:6px 12px; padding:8px 0!important; }
.member-seq { min-width:32px; color:var(--mop-muted,#5d6e83); font-variant-numeric:tabular-nums; }
.event-members time { margin-left:auto; color:var(--mop-muted,#5d6e83); font-size:12px; font-variant-numeric:tabular-nums; }
.event-workspace > .event-list-card,.event-workspace > .event-sub-card { padding:0; }
.event-workspace h2 { margin:0 0 12px; font-size:15px; }
.event-sub-head { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:6px 12px; min-height:48px; padding:10px 16px; border-bottom:1px solid var(--mop-line-soft,#edf1f6); }
.event-sub-head h2,.event-sub-head h3 { margin:0!important; font-size:14px!important; }
.event-sub-scroll { max-width:100%; overflow-x:auto; }
.event-sub-table { min-width:980px; }
.event-sub-table td small { display:block; margin-top:2px; color:var(--mop-muted,#5d6e83); font-size:12px; }
.event-time-cell time { font-variant-numeric:tabular-nums; font-size:12px; }
.window-confirmed { color:var(--mop-ok,#1d7a4c)!important; }
.window-actions { min-width:250px; }
.window-extend { display:grid; gap:4px; }
.window-extend label { font-size:12px; color:var(--mop-muted,#5d6e83); }
.window-buttons { display:flex; flex-wrap:wrap; gap:6px; }
.monitoring-events-page .window-buttons button { margin:0!important; }
.event-sub-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(min(100%,460px),1fr)); }
.event-sub-block { min-width:0; }
.event-sub-block + .event-sub-block { border-left:1px solid var(--mop-line-soft,#edf1f6); }
.event-rows > li { padding:12px 16px!important; }
.event-rows > li.actionable { background:linear-gradient(90deg,var(--mop-warn-bg,#fff4dc) 0 3px,transparent 3px); }
.event-row-facts { display:flex; align-items:center; flex-wrap:wrap; gap:6px 8px; }
.event-row-facts small { flex-basis:100%; color:var(--mop-muted,#5d6e83); font-size:12px; }
.event-row-form { display:grid; gap:4px; margin-top:8px; padding:10px; border:1px solid var(--mop-line,#e2e8f0); border-radius:6px; background:var(--mop-sunken,#f7f9fc); }
.event-row-form label { margin-top:4px!important; }
.event-rows .field-help { margin:4px 0 0; }
.event-list-toolbar,.event-list-caption,.monitoring-events-page .actions {
  display:flex; align-items:center; flex-wrap:wrap; gap:8px 12px; min-width:0;
}
.event-list-toolbar { justify-content:space-between; padding:10px 14px; }
.event-list-caption h2 { margin:0; }
.event-scroll-hint { display:none; color:#526176; font-size:12px; }
.event-count-note,.event-cell-note,.event-empty { font-size:12px; font-weight:400; color:var(--ws-muted,#526176); line-height:1.6; }
.event-empty,.event-list-card > p { margin:0; padding:12px 14px; }
.event-table-scroll { max-width:100%; overflow-x:auto; border-top:1px solid #e3e9f2; border-radius:0 0 10px 10px; }
.event-table-scroll table { width:100%; min-width:1120px; border-collapse:collapse; text-align:left; table-layout:fixed; font-size:13px; }
.event-table-scroll th,.event-table-scroll td { padding:9px 12px; border-bottom:1px solid #edf1f7; vertical-align:middle; overflow-wrap:anywhere; line-height:1.6; }
.event-table-scroll th { background:#f6f8fb; color:#526176; font-size:12px; font-weight:600; }
.event-table-scroll th:first-child { width:22%; }
.event-table-scroll th:nth-child(2) { width:7%; }
.event-table-scroll th:nth-child(3) { width:9%; }
.event-table-scroll th:nth-child(4) { width:10%; }
.event-table-scroll th:nth-child(5) { width:13%; }
.event-table-scroll th:nth-child(6) { width:21%; }
.event-table-scroll th:last-child { width:18%; }
.event-table-scroll tbody tr:hover { background:#f8faff; }
.event-table-scroll tbody tr:last-child td { border-bottom:0; }
.event-table-scroll td:first-child strong { font-weight:600; color:#26384f; }
.event-cell-note { display:block; margin-top:4px; }
.event-notification { color:#526176; }
.event-status,.event-occurrences { display:inline-flex; align-items:center; white-space:nowrap; border-radius:999px; padding:1px 8px; font-size:12px; font-weight:500; line-height:20px; background:#eef2f6; color:#526176; }
.event-status.sev { border-radius:4px; font-weight:600; min-width:40px; justify-content:center; }
.event-status.sev[data-state="DISASTER"] { background:var(--mop-sev-disaster-bg,#fde3e1); color:var(--mop-sev-disaster,#a51b13); }
.event-status.sev[data-state="HIGH"] { background:var(--mop-sev-high-bg,#ffeadd); color:var(--mop-sev-high,#b93b0b); }
.event-status.sev[data-state="AVERAGE"] { background:var(--mop-sev-average-bg,#fff1d6); color:var(--mop-sev-average,#9a5b00); }
.event-status.sev[data-state="WARNING"] { background:var(--mop-sev-warning-bg,#fdf6c8); color:var(--mop-sev-warning,#7a5d00); }
.event-status.sev[data-state="INFO"] { background:var(--mop-sev-info-bg,#e5effd); color:var(--mop-sev-info,#1f5fb8); }
.event-occurrences { margin-left:6px; background:#edf3ff; color:#215eb2; font-weight:600; }
.event-status[data-state="INFO"],.event-status[data-state="ACKNOWLEDGED"],.event-status[data-state="IN_PROGRESS"] { background:#edf3ff; color:#215eb2; }
.event-status[data-state="RECOVERED"],.event-status[data-state="RESOLVED"] { background:#eaf7ef; color:#237346; }
.event-status[data-state="WARNING"],.event-status[data-state="AVERAGE"],.event-status[data-state="PENDING_REVIEW"],.event-status[data-state="NEW"] { background:#fff4df; color:#8a5700; }
.event-status[data-state="HIGH"],.event-status[data-state="DISASTER"],.event-status[data-state="PROBLEM"] { background:#fff0f0; color:#ad3444; }
.event-status[data-state="FLAP_SILENCED"],.event-status[data-state="DEPENDENCY_SUPPRESSED"],.event-status[data-state="MAINTENANCE_SILENCED"],.event-status[data-state="INFO_DEFAULT_SILENT"] { background:#f2eefb; color:#695092; }
.event-row-actions { display:flex; align-items:center; gap:6px; flex-wrap:wrap; }
.event-row-actions.has-review { display:inline-flex; }
.monitoring-events-page button { min-height:var(--ctl-h,32px); margin:0; max-width:100%; white-space:normal; }
.monitoring-events-page .event-row-actions button { padding:5px 9px; }
.event-review { display:inline-block; margin:0 0 0 6px; vertical-align:middle; }
.event-review[open] { display:block; margin:6px 0 0; }
.event-review summary { width:fit-content; min-height:32px; padding:5px 2px; box-sizing:border-box; color:#215eb2; cursor:pointer; font-size:13px; }
.event-review-form { display:grid; gap:6px; padding:10px; background:#f6f8fc; border:1px solid #dbe3ef; border-radius:6px; }
.event-review-form label { font-size:12px; font-weight:600; color:#33465c; }
.event-review-form button { justify-self:start; }
.monitoring-events-page input,.monitoring-events-page textarea { box-sizing:border-box; max-width:100%; min-height:44px; }
.monitoring-events-page textarea { width:100%; resize:vertical; }
.monitoring-events-page :focus-visible { outline:2px solid #2563eb; outline-offset:2px; }
.monitoring-events-page ul { list-style:none; padding:0; margin:0; }
.monitoring-events-page li { margin:0; padding:12px 0; border-bottom:1px solid #edf1f7; font-size:13px; line-height:1.7; }
.monitoring-events-page li:last-child { border-bottom:0; padding-bottom:0; }
.monitoring-events-page li label { display:block; margin-top:8px; font-size:12px; color:#526176; }
.monitoring-events-page li button { margin:6px 6px 0 0; }
@media(max-width:760px) {
  .event-workspace > .card,.event-detail { padding:12px; }
  .event-workspace > .event-list-card { padding:0; }
  .event-list-toolbar { padding:10px 12px; }
  .event-review summary { min-height:44px; padding-block:11px; }
}
@media(max-width:1200px) { .event-scroll-hint { display:block; } }
</style>
