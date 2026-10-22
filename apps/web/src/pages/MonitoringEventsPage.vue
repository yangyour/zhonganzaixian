<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import { session, switchingTenant, contextGeneration } from '../stores/session'
import { isNavigationFailure, NavigationFailureType, onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { exportUuid } from '../utils/assetExportJobs'
import { utcInstantNanos } from '../utils/utcInstant'
import {
  createMonitoringEventWorkspace,
  canTransition,
  validMaintenanceTime,
  emptyMonitoringEventCenterState,
  type FunnelWindow,
  type MonitoringEventCenterState,
} from '../utils/monitoringEvents'

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
    <header class="compact-page-toolbar section-header">
      <div>
        <h1 class="page-title-sr" id="monitoring-events-title">监控事件中心与降噪漏斗</h1>
        
      </div>
      <div class="actions">
        <button type="button" :disabled="!canRead || locked" @click="switchWindow('24h')">刷新事件</button>
      </div>
    </header>

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
        <h2>事件详情抽屉：{{ state.detail.event.rule_name }} (×{{ state.detail.event.occurrence_count }})</h2>
        <p>规则代码：{{ state.detail.event.rule_code }} · 级别：{{ state.detail.event.severity }} · 监控状态：{{ state.detail.event.monitor_state }} · 人工处理状态：{{ state.detail.event.handling_state }}</p>
        <p>首次确认时间 (first_ack_at)：{{ state.detail.event.first_ack_at ?? '未确认' }} | 复盘门槛状态：{{ state.detail.event.review_policy_status }}</p>
        <p>通知状态说明：<strong>{{ state.detail.event.notification_empty_state_label }}</strong></p>
        <h3>合并的原始事件成员（{{ state.detail.members.length }} 条）</h3>
        <ul><li v-for="m in state.detail.members" :key="m.id">#{{ m.sequence_no }} · {{ m.source_event_id }} · {{ m.source_phase }} · {{ m.occurred_at }}</li></ul>
      </aside>
    </section>
    <p v-if="state.workspaceBusy" role="status">正在读取最新事件…</p>
    <div v-if="state.workspace" class="event-workspace">
      <section class="card" aria-label="监控事件主列表">
        <h2>监控事件流 <small class="event-count-note">最新 25 条</small></h2>
        <div class="event-table-scroll" role="region" aria-label="监控事件列表，可横向滚动" tabindex="0">
        <table>
          <thead>
            <tr>
              <th scope="col">规则 / 去重角标</th>
              <th scope="col">级别</th>
              <th scope="col">监控状态</th>
              <th scope="col">人工处理状态</th>
              <th scope="col">抑制状态</th>
              <th scope="col">通知空状态原因</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ev in state.workspace.events" :key="ev.id">
              <td>
                <strong>{{ ev.rule_name }}</strong>
                <span v-if="ev.occurrence_count > 1" class="badge">×{{ ev.occurrence_count }}</span>
              </td>
              <td>{{ ev.severity }}</td>
              <td>{{ ev.monitor_state }}</td>
              <td>{{ ev.handling_state }}</td>
              <td>{{ ev.suppression_state }} <small v-if="ev.suppression_reason">({{ ev.suppression_reason }})</small></td>
              <td>{{ ev.notification_empty_state_label }}</td>
              <td>
                <button type="button" :disabled="state.busy === 'write' || state.attempt !== null || navigating" @click="selectEvent(ev.id)">查看抽屉</button>
                <button
                  v-if="canWrite && canTransition(ev, 'ACKNOWLEDGE')"
                  :disabled="locked"
                  type="button"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'ACKNOWLEDGE', root_cause_note: null })"
                >
                  确认 (固化first_ack_at)
                </button>
                <button v-if="canWrite && canTransition(ev, 'START')" type="button" :disabled="locked"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'START', root_cause_note: null })">开始处理</button>
                <button v-if="canWrite && canTransition(ev, 'MARK_PENDING_REVIEW')" type="button" :disabled="locked"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'MARK_PENDING_REVIEW', root_cause_note: null })">转为待复核</button>
                <template v-if="canWrite && ev.handling_state === 'PENDING_REVIEW'">
                  <label :for="`root-cause-${ev.id}`">人工复核根因与处置说明（至少20个字符，最多500个字符）</label>
                  <textarea :id="`root-cause-${ev.id}`" v-model="rootCauseNotes[ev.id]" :disabled="locked" :aria-label="`${ev.rule_name}根因说明`" />
                  <button type="button" :disabled="locked || !canTransition(ev, 'RESOLVE', rootCauseNotes[ev.id] ?? null)"
                    @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'RESOLVE', root_cause_note: rootCauseNotes[ev.id] ?? null })">确认解决</button>
                </template>
                <button v-if="canWrite && canTransition(ev, 'CLOSE')" type="button" :disabled="locked"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'CLOSE', root_cause_note: null })">关闭事件</button>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
        <p v-if="!state.workspace.events.length" role="status">暂无监控事件</p>
      </section>

      <section class="card" aria-label="计划维护窗口">
        <h2>计划维护窗口与 SLA 排除确认</h2>
        <ul>
          <li v-for="w in state.workspace.maintenance_windows" :key="w.id">
            <strong>{{ w.name }}</strong> ({{ w.starts_at }} ~ {{ w.ends_at }}) · 状态：{{ w.status }}
            · SLA排除申请：{{ w.sla_exclusion_requested ? '是' : '否' }}
            · 管理员确认：<strong>{{ w.sla_exclusion_confirmed ? '已记录管理员确认' : '未记录管理员确认' }}</strong>
            <button
              v-if="canAdminConfirm && w.sla_exclusion_requested && !w.sla_exclusion_confirmed"
              :disabled="locked"
              type="button"
              @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'CONFIRM_SLA_EXCLUSION', new_ends_at: null })"
            >
              管理员确认SLA排除
            </button>
            <template v-if="canWrite && (w.status === 'ACTIVE' || w.status === 'SCHEDULED')">
              <label :for="`window-end-${w.id}`">延长至（UTC，含Z，最多毫秒精度）</label>
              <input :id="`window-end-${w.id}`" v-model="windowEnds[w.id]" :disabled="locked" />
              <button type="button" :disabled="locked || !validExtension(w.id, w.ends_at)" @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'EXTEND', new_ends_at: windowEnds[w.id] ?? null })">延长维护</button>
              <button type="button" :disabled="locked" @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'END', new_ends_at: null })">结束维护</button>
            </template>
          </li>
        </ul>
      </section>

      <section class="card" aria-label="监控隔离区与自动建单补偿">
        <h2>监控隔离区（{{ state.workspace.quarantine_items.length }}）与自动建单补偿（{{ state.workspace.autoticket_attempts.length }}）</h2>
        <ul>
          <li v-for="q in state.workspace.quarantine_items" :key="q.quarantine_id">
            隔离事件 {{ q.source_event_id }} ({{ q.quarantine_reason }}) · 处置：{{ q.decision ?? '待人工决策' }}
            <template v-if="canWrite && !q.decision">
              <label :for="`quarantine-asset-${q.quarantine_id}`">核验后绑定的资产UUID</label>
              <input :id="`quarantine-asset-${q.quarantine_id}`" v-model="quarantineAssets[q.quarantine_id]" :disabled="locked" />
              <label :for="`quarantine-reason-${q.quarantine_id}`">人工处置原因</label>
              <textarea :id="`quarantine-reason-${q.quarantine_id}`" v-model="quarantineReasons[q.quarantine_id]" :disabled="locked" />
              <button type="button" :disabled="locked || !exportUuid(quarantineAssets[q.quarantine_id]) || !validReason(quarantineReasons[q.quarantine_id])"
                @click="workspace.submitQuarantineDecision(q.quarantine_id, { decision: 'BIND_ASSET', bound_asset_id: quarantineAssets[q.quarantine_id] ?? null, reason: quarantineReasons[q.quarantine_id] ?? '' })">绑定资产并回灌</button>
              <button type="button" :disabled="locked || !validReason(quarantineReasons[q.quarantine_id])"
                @click="workspace.submitQuarantineDecision(q.quarantine_id, { decision: 'DISCARD', bound_asset_id: null, reason: quarantineReasons[q.quarantine_id] ?? '' })">丢弃隔离事件</button>
            </template>
          </li>
        </ul>
        <ul>
          <li v-for="a in state.workspace.autoticket_attempts" :key="a.id">
            事件 {{ a.event_id }} · 重试 {{ a.attempt_count }}/{{ a.max_retries }} ({{ a.backoff_schedule_sec }}s)
            · 状态：<strong>{{ a.status }}</strong> · 映射策略：{{ a.mapping_policy_status }}
            <p v-if="a.next_retry_at" class="field-help">计划最早重试（UTC）：<time :datetime="a.next_retry_at">{{ a.next_retry_at }}</time>。是否执行取决于 Worker 配置及到期时的权限、告警和抑制状态检查。</p>
            <p v-else class="field-help">当前记录没有安排自动重试时间。</p>
            <p v-if="a.last_error" class="field-help">最近处理说明：{{ a.last_error }}</p>
            <p v-if="a.status === 'SUCCEEDED' || a.status === 'MANUAL_CREATED'" class="field-help">登记已保存，不等于正式工单流程已启动或外部通知已送达。</p>
            <template v-if="canWrite && (a.status === 'QUARANTINED_MANUAL_REQUIRED' || a.status === 'RETRY_SCHEDULED')">
              <label :for="`manual-ticket-reason-${a.id}`">人工补建原因（最多492个字符）</label>
              <textarea :id="`manual-ticket-reason-${a.id}`" v-model="manualTicketReasons[a.id]" :disabled="locked" />
              <button type="button" :disabled="locked || !validManualReason(manualTicketReasons[a.id])"
                @click="workspace.submitManualAutoTicket(a.id, { expected_version: a.version, reason: manualTicketReasons[a.id] ?? '' })">人工补建工单</button>
            </template>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<style scoped>
.monitoring-events-page,.event-workspace{min-width:0;max-width:100%}.event-workspace{display:grid;grid-template-columns:minmax(0,1fr);gap:12px}.event-workspace>.card,.event-detail{min-width:0;padding:16px;overflow-wrap:anywhere}.event-workspace h2{margin:0 0 12px;font-size:16px}.event-table-scroll{max-width:100%;overflow-x:auto;border:1px solid #e2e8f0;border-radius:6px}.event-table-scroll table{width:100%;min-width:960px;border-collapse:collapse;text-align:left;table-layout:fixed}.event-table-scroll th,.event-table-scroll td{padding:12px;border-bottom:1px solid #e2e8f0;vertical-align:top;overflow-wrap:anywhere}.event-table-scroll th{background:#f8fafc}.event-table-scroll th:first-child{width:18%}.event-table-scroll th:last-child{width:26%}.monitoring-events-page button{min-height:44px;padding:6px 12px;margin:2px;max-width:100%;white-space:normal}.monitoring-events-page input,.monitoring-events-page textarea{box-sizing:border-box;max-width:100%;min-height:44px}.monitoring-events-page textarea{width:100%}.monitoring-events-page :focus-visible{outline:2px solid #2563eb;outline-offset:2px}.monitoring-events-page li{margin-bottom:12px}.monitoring-events-page .actions{display:flex;flex-wrap:wrap;gap:8px}
@media(max-width:760px){.event-workspace>.card,.event-detail{padding:12px}}
.event-count-note{font-size:12px;font-weight:400;color:#6a7a8e;margin-left:6px}
</style>
