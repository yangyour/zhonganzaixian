<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api/client'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import { session } from '../stores/session'
import {
  createMonitoringEventWorkspace,
  emptyMonitoringEventCenterState,
  type FunnelWindow,
  type MonitoringEventCenterState,
} from '../utils/monitoringEvents'

const state = ref<MonitoringEventCenterState>(emptyMonitoringEventCenterState())
const workspace = createMonitoringEventWorkspace(api, next => { state.value = next })

const canWrite = computed(() => Boolean(session.value?.capabilities.includes('ticket_notes:write_internal')))
const canAdminConfirm = computed(() => session.value?.user.role === 'padmin' && canWrite.value)

const rootCauseNote = ref('')
const quarantineAssetId = ref('')
const quarantineReason = ref('')
const manualTicketReason = ref('')

watch(
  () => [session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, canWrite.value, canAdminConfirm.value] as const,
  ([tenantId, actorId, permissionVersion, write, admin]) => {
    if (!tenantId || !actorId || typeof permissionVersion !== 'number') {
      workspace.reset(null)
      return
    }
    workspace.reset({ tenantId, actorId, permissionVersion, canWriteInternal: write, canAdminConfirm: admin })
    void workspace.load('24h')
  },
  { immediate: true },
)

onBeforeUnmount(() => workspace.dispose())

function switchWindow(w: FunnelWindow) {
  void workspace.load(w)
}
</script>

<template>
  <section class="page-section" aria-labelledby="monitoring-events-title">
    <header class="section-header">
      <div>
        <h1 id="monitoring-events-title">监控事件中心与降噪漏斗</h1>
        <p class="muted">
          5分钟同指纹去重合并（×N）、闪断10m≥3次静默30m、拓扑上游故障抑制、维护窗口SLA排除双确认、隔离区人工处置与自动建单补偿（Q14/Q29保持CONFIG_PENDING）。
        </p>
      </div>
      <div class="actions">
        <button type="button" :disabled="state.busy !== null" @click="switchWindow('24h')">24h 漏斗</button>
        <button type="button" :disabled="state.busy !== null" @click="switchWindow('7d')">7d 漏斗</button>
        <button type="button" :disabled="state.busy !== null" @click="switchWindow('30d')">30d 漏斗</button>
      </div>
    </header>

    <div v-if="state.error" class="alert alert-error" role="alert">
      <ApiErrorNotice :error="state.error" />
      <button v-if="state.attempt" type="button" @click="workspace.retry()">原幂等键重试</button>
    </div>

    <div v-if="state.workspace" class="workspace-grid">
      <section class="card" aria-label="告警降噪漏斗">
        <h2>告警降噪漏斗（{{ state.workspace.noise_funnel.window }}）</h2>
        <ul class="kpi-list">
          <li>原始到达：<strong>{{ state.workspace.noise_funnel.raw_intake_count }}</strong></li>
          <li>去重合并（5m窗口）：<strong>{{ state.workspace.noise_funnel.merged_deduplicated_count }}</strong></li>
          <li>闪断静默（10m≥3次→30m）：<strong>{{ state.workspace.noise_funnel.flap_silenced_count }}</strong></li>
          <li>拓扑依赖抑制：<strong>{{ state.workspace.noise_funnel.dependency_suppressed_count }}</strong></li>
          <li>维护期静默：<strong>{{ state.workspace.noise_funnel.maintenance_silenced_count }}</strong></li>
          <li>有效告警：<strong>{{ state.workspace.noise_funnel.effective_alert_count }}</strong></li>
        </ul>
      </section>

      <section class="card" aria-label="监控事件主列表">
        <h2>监控事件流（监控状态与处理状态正交独立）</h2>
        <table>
          <thead>
            <tr>
              <th>规则 / 去重角标</th>
              <th>级别</th>
              <th>监控状态</th>
              <th>人工处理状态</th>
              <th>抑制状态</th>
              <th>通知空状态原因</th>
              <th>操作</th>
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
                <button type="button" @click="workspace.loadEventDetail(ev.id)">查看抽屉</button>
                <button
                  v-if="canWrite && ev.handling_state === 'NEW'"
                  type="button"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'ACKNOWLEDGE', root_cause_note: null })"
                >
                  确认 (固化first_ack_at)
                </button>
                <button
                  v-if="canWrite && ev.handling_state !== 'RESOLVED' && ev.handling_state !== 'CLOSED'"
                  type="button"
                  @click="workspace.submitTransition(ev.id, { expected_version: ev.version, action: 'RESOLVE', root_cause_note: rootCauseNote || '已定位根因并处理' })"
                >
                  解决
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <aside v-if="state.detail" class="card" aria-label="监控事件详情抽屉">
        <h2>事件详情抽屉：{{ state.detail.event.rule_name }} (×{{ state.detail.event.occurrence_count }})</h2>
        <p>首次确认时间 (first_ack_at)：{{ state.detail.event.first_ack_at ?? '未确认' }} | 复盘门槛状态：{{ state.detail.event.review_policy_status }}</p>
        <p>通知状态说明：<strong>{{ state.detail.event.notification_empty_state_label }}</strong></p>
        <h3>合并的原始事件成员（{{ state.detail.members.length }} 条）</h3>
        <ul>
          <li v-for="m in state.detail.members" :key="m.id">
            #{{ m.sequence_no }} · {{ m.source_event_id }} · {{ m.source_phase }} · {{ m.occurred_at }}
          </li>
        </ul>
      </aside>

      <section class="card" aria-label="计划维护窗口">
        <h2>计划维护窗口与 SLA 排除确认</h2>
        <ul>
          <li v-for="w in state.workspace.maintenance_windows" :key="w.id">
            <strong>{{ w.name }}</strong> ({{ w.starts_at }} ~ {{ w.ends_at }}) · 状态：{{ w.status }}
            · SLA排除申请：{{ w.sla_exclusion_requested ? '是' : '否' }}
            · 管理员确认：<strong>{{ w.sla_exclusion_confirmed ? '已确认生效' : '未确认（不计入SLA排除）' }}</strong>
            <button
              v-if="canAdminConfirm && w.sla_exclusion_requested && !w.sla_exclusion_confirmed"
              type="button"
              @click="workspace.submitWindowAction(w.id, { expected_version: w.version, action: 'CONFIRM_SLA_EXCLUSION', new_ends_at: null })"
            >
              管理员确认SLA排除
            </button>
          </li>
        </ul>
      </section>

      <section class="card" aria-label="监控隔离区与自动建单补偿">
        <h2>监控隔离区（{{ state.workspace.quarantine_items.length }}）与自动建单补偿（{{ state.workspace.autoticket_attempts.length }}）</h2>
        <ul>
          <li v-for="q in state.workspace.quarantine_items" :key="q.quarantine_id">
            隔离事件 {{ q.source_event_id }} ({{ q.quarantine_reason }}) · 处置：{{ q.decision ?? '待人工决策' }}
            <button
              v-if="canWrite && !q.decision && quarantineAssetId && quarantineReason"
              type="button"
              @click="workspace.submitQuarantineDecision(q.quarantine_id, { decision: 'BOUND', bound_asset_id: quarantineAssetId, reason: quarantineReason })"
            >
              绑定资产并回灌
            </button>
          </li>
        </ul>
        <ul>
          <li v-for="a in state.workspace.autoticket_attempts" :key="a.id">
            事件 {{ a.event_id }} · 重试 {{ a.attempt_count }}/{{ a.max_retries }} ({{ a.backoff_schedule_sec }}s)
            · 状态：<strong>{{ a.status }}</strong> · 映射策略：{{ a.mapping_policy_status }}
            <button
              v-if="canWrite && a.status === 'QUARANTINED_MANUAL_REQUIRED'"
              type="button"
              @click="workspace.submitManualAutoTicket(a.id, { expected_version: a.version, reason: manualTicketReason || '值班人工一键补建工单' })"
            >
              一键人工补建工单
            </button>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
