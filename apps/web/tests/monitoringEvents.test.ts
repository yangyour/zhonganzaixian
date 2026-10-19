import { describe, expect, it } from 'vitest'
import { ApiError } from '../src/api/client'
import {
  createMonitoringEventWorkspace,
  type MonitoringEventCenterState,
  type MonitoringEventContext,
} from '../src/utils/monitoringEvents'

const TENANT = '55000000-0000-4000-8000-000000000001'
const ACTOR = '55000000-0000-4000-8000-000000000011'
const ASSET = '66000000-0000-4000-8000-000000000001'
const EVENT_ID = '77000000-0000-4000-8000-000000000001'
const WINDOW_ID = '88000000-0000-4000-8000-000000000001'
const ATTEMPT_ID = '99000000-0000-4000-8000-000000000001'

const CONTEXT: MonitoringEventContext = {
  tenantId: TENANT,
  actorId: ACTOR,
  permissionVersion: 3,
  canWriteInternal: true,
  canAdminConfirm: true,
}

function sampleEvent() {
  return {
    id: EVENT_ID,
    tenant_id: TENANT,
    asset_id: ASSET,
    version: 2,
    rule_code: 'LINK_DOWN',
    rule_name: '核心上联端口中断',
    severity: 'DISASTER',
    severity_rank: 5,
    fingerprint: `FP_V1|${TENANT}|${ASSET}|LINK_DOWN`,
    occurrence_count: 5,
    flap_flip_count: 0,
    flap_silenced_until: null,
    monitor_state: 'RECOVERED',
    handling_state: 'IN_PROGRESS',
    suppression_state: 'NONE',
    suppression_reason: null,
    notification_empty_state: 'RECOVERED_within_2MIN_CANCELLED',
    notification_empty_state_label: '2分钟内自动恢复，通知已取消',
    sla_unknown_marked: false,
    review_policy_status: 'CONFIG_PENDING_Q29',
    problem_at: '2026-10-18T10:00:00Z',
    last_occurred_at: '2026-10-18T10:01:30Z',
    recovered_at: '2026-10-18T10:01:30Z',
    first_ack_at: '2026-10-18T10:00:45Z',
    resolved_at: null,
    closed_at: null,
    root_cause_note: null,
    ticket_id: null,
    updated_by: ACTOR,
    updated_at: '2026-10-18T10:01:30Z',
  }
}

function sampleWorkspace() {
  return {
    data: {
      tenant_id: TENANT,
      events: [sampleEvent()],
      noise_funnel: {
        tenant_id: TENANT,
        window: '24h',
        raw_intake_count: 10,
        merged_deduplicated_count: 4,
        flap_silenced_count: 1,
        dependency_suppressed_count: 1,
        maintenance_silenced_count: 1,
        effective_alert_count: 3,
        observed_at: '2026-10-18T10:05:00Z',
      },
      maintenance_windows: [
        {
          id: WINDOW_ID,
          tenant_id: TENANT,
          version: 1,
          name: '核心交换机割接',
          asset_id: ASSET,
          starts_at: '2026-10-18T10:00:00Z',
          ends_at: '2026-10-18T12:00:00Z',
          silence_mode: 'SUPPRESS_NOTIFY',
          status: 'ACTIVE',
          sla_exclusion_requested: true,
          sla_exclusion_confirmed: false,
          sla_exclusion_confirmed_by: null,
          sla_exclusion_confirmed_at: null,
          reason: '计划维护',
          created_by: ACTOR,
          updated_by: ACTOR,
          updated_at: '2026-10-18T10:00:00Z',
        },
      ],
      quarantine_items: [],
      autoticket_attempts: [
        {
          id: ATTEMPT_ID,
          tenant_id: TENANT,
          event_id: EVENT_ID,
          version: 1,
          attempt_count: 5,
          max_retries: 5,
          backoff_schedule_sec: '30,120,600,600,600',
          status: 'QUARANTINED_MANUAL_REQUIRED',
          mapped_priority: 'P1',
          mapping_policy_status: 'CONFIG_PENDING_Q14',
          ticket_id: null,
          next_retry_at: null,
          last_error: '连续5次失败',
          updated_by: ACTOR,
          updated_at: '2026-10-18T10:05:00Z',
        },
      ],
      review_policy_status: 'CONFIG_PENDING_Q29',
      mapping_policy_status: 'CONFIG_PENDING_Q14',
      observed_at: '2026-10-18T10:05:00Z',
      source: 'postgresql',
    },
    trace_id: 'trace-v37',
  }
}

describe('monitoringEvents workspace (V37 D15)', () => {
  it('loads workspace with ×N occurrence count, orthogonal states, noise funnel, and drawer detail', async () => {
    const ws = createMonitoringEventWorkspace(
      {
        async request<T>(path: string): Promise<T> {
          if (path.startsWith('/events?')) return sampleWorkspace() as unknown as T
          if (path === `/events/${EVENT_ID}`) {
            return {
              data: {
                event: sampleEvent(),
                members: [
                  {
                    id: '11000000-0000-4000-8000-000000000001',
                    tenant_id: TENANT,
                    event_id: EVENT_ID,
                    raw_event_id: null,
                    sequence_no: 1,
                    source_event_id: 'src-1',
                    source_phase: 'PROBLEM',
                    occurred_at: '2026-10-18T10:00:00Z',
                    merged_within_window: false,
                    recorded_at: '2026-10-18T10:00:00Z',
                  },
                ],
                noise_records: [],
                autoticket_attempt: null,
                observed_at: '2026-10-18T10:05:00Z',
                source: 'postgresql',
              },
              trace_id: 'trace-detail',
            } as unknown as T
          }
          throw new Error('unexpected path ' + path)
        },
      },
      () => {},
      () => 'idem-fixed-key-0001',
    )

    ws.reset(CONTEXT)
    expect(await ws.load('24h')).toBe(true)
    expect(ws.state().workspace?.events[0]?.occurrence_count).toBe(5)
    expect(ws.state().workspace?.events[0]?.monitor_state).toBe('RECOVERED')
    expect(ws.state().workspace?.events[0]?.handling_state).toBe('IN_PROGRESS')
    expect(ws.state().workspace?.events[0]?.notification_empty_state_label).toBe('2分钟内自动恢复，通知已取消')
    expect(ws.state().workspace?.noise_funnel.raw_intake_count).toBe(10)

    expect(await ws.loadEventDetail(EVENT_ID)).toBe(true)
    expect(ws.state().detail?.members.length).toBe(1)
  })

  it('enforces admin role for CONFIRM_SLA_EXCLUSION and preserves idempotency key on uncertain write failure', async () => {
    const seenKeys: string[] = []
    let failWrite = true
    const ws = createMonitoringEventWorkspace(
      {
        async request<T>(path: string, options?: { idempotencyKey?: string }): Promise<T> {
          if (path.startsWith('/events?')) return sampleWorkspace() as unknown as T
          seenKeys.push(String(options?.idempotencyKey))
          if (failWrite) throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', '模拟网络中断', 'trace-err')
          return { data: sampleWorkspace().data.maintenance_windows[0], trace_id: 'ok' } as unknown as T
        },
      },
      () => {},
      () => 'idem-sla-confirm-001',
    )

    // Non-admin cannot confirm SLA exclusion
    ws.reset({ ...CONTEXT, canAdminConfirm: false })
    expect(
      await ws.submitWindowAction(WINDOW_ID, {
        expected_version: 1,
        action: 'CONFIRM_SLA_EXCLUSION',
        new_ends_at: null,
      }),
    ).toBe(false)
    expect(seenKeys.length).toBe(0)

    // Admin submits, first fails with 503 -> keeps attempt for retry with same idempotency key
    ws.reset(CONTEXT)
    expect(
      await ws.submitWindowAction(WINDOW_ID, {
        expected_version: 1,
        action: 'CONFIRM_SLA_EXCLUSION',
        new_ends_at: null,
      }),
    ).toBe(false)
    expect(ws.state().attempt?.key).toBe('idem-sla-confirm-001')

    failWrite = false
    expect(await ws.retry()).toBe(true)
    expect(seenKeys).toEqual(['idem-sla-confirm-001', 'idem-sla-confirm-001'])
  })
})
