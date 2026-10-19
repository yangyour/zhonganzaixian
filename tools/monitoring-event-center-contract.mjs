export function addMonitoringEventCenterContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const uuid = { type: 'string', format: 'uuid' }
  const time = { type: 'string', format: 'date-time' }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const severityEnum = { enum: ['DISASTER', 'HIGH', 'AVERAGE', 'WARNING', 'INFO'] }
  const monitorStateEnum = { enum: ['PROBLEM', 'RECOVERED'] }
  const handlingStateEnum = { enum: ['NEW', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] }
  const suppressionStateEnum = { enum: ['NONE', 'FLAP_SILENCED', 'DEPENDENCY_SUPPRESSED', 'MAINTENANCE_SILENCED'] }
  const emptyStateEnum = {
    enum: [
      'NORMAL_DISPATCH_ELIGIBLE',
      'SILENCED_NO_NOTIFICATION',
      'RECOVERED_within_2MIN_CANCELLED',
      'BELOW_SUBSCRIPTION_LEVEL_UNSENT',
    ],
  }
  const emptyStateLabelEnum = {
    enum: [
      '符合外发条件（按已启用渠道投递）',
      '已被静默规则抑制，未发送通知',
      '2分钟内自动恢复，通知已取消',
      '低于客户通知订阅级别，未发送',
    ],
  }
  const noiseLayerEnum = {
    enum: [
      'RAW_INTAKE',
      'MERGED_DEDUPLICATED',
      'FLAP_SILENCED',
      'DEPENDENCY_SUPPRESSED',
      'MAINTENANCE_SILENCED',
      'EFFECTIVE_ALERT',
    ],
  }
  const autoTicketStatusEnum = {
    enum: ['NOT_REQUIRED', 'RETRY_SCHEDULED', 'CREATED', 'QUARANTINED_MANUAL_REQUIRED', 'MANUAL_CREATED'],
  }
  const windowEnum = { enum: ['24h', '7d', '30d'] }
  const versionPos = { type: 'integer', minimum: 1, maximum: 9007199254740990 }
  const nonNegInt = { type: 'integer', minimum: 0 }
  const nonNegLong = { type: 'integer', minimum: 0, maximum: 9007199254740990 }
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 413, 422, 503].map(code => [
    code,
    { description: '监控事件中心、降噪漏斗、维护窗口、隔离区人工决策或自动建单补偿授权/版本/参数/依赖错误', content: { 'application/json': { schema: ref('Error') } } },
  ]))
  const envelope = data => object({ data, trace_id: { type: 'string' } })
  const writeHeaders = [
    { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9._:-]{8,128}$' } },
    { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } },
  ]

  const eventSummary = object({
    id: uuid,
    tenant_id: uuid,
    asset_id: uuid,
    version: versionPos,
    rule_code: { type: 'string', minLength: 1, maxLength: 64 },
    rule_name: { type: 'string', minLength: 1, maxLength: 160 },
    severity: severityEnum,
    severity_rank: { type: 'integer', minimum: 1, maximum: 5 },
    fingerprint: { type: 'string', minLength: 1, maxLength: 256 },
    occurrence_count: { type: 'integer', minimum: 1 },
    flap_flip_count: nonNegInt,
    flap_silenced_until: nullable(time),
    monitor_state: monitorStateEnum,
    handling_state: handlingStateEnum,
    suppression_state: suppressionStateEnum,
    suppression_reason: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
    notification_empty_state: emptyStateEnum,
    notification_empty_state_label: emptyStateLabelEnum,
    sla_unknown_marked: { type: 'boolean' },
    review_policy_status: { const: 'CONFIG_PENDING_Q29' },
    problem_at: time,
    last_occurred_at: time,
    recovered_at: nullable(time),
    first_ack_at: nullable(time),
    resolved_at: nullable(time),
    closed_at: nullable(time),
    root_cause_note: nullable({ type: 'string', minLength: 1, maxLength: 1000 }),
    ticket_id: nullable(uuid),
    updated_by: uuid,
    updated_at: time,
  })

  const eventMember = object({
    id: uuid,
    tenant_id: uuid,
    event_id: uuid,
    raw_event_id: nullable(uuid),
    sequence_no: { type: 'integer', minimum: 1 },
    source_event_id: { type: 'string', minLength: 1, maxLength: 128 },
    source_phase: monitorStateEnum,
    occurred_at: time,
    merged_within_window: { type: 'boolean' },
    recorded_at: time,
  })

  const noiseRecord = object({
    id: uuid,
    tenant_id: uuid,
    event_id: nullable(uuid),
    layer: noiseLayerEnum,
    detail: { type: 'string', minLength: 1, maxLength: 500 },
    recorded_at: time,
  })

  const noiseFunnel = object({
    tenant_id: uuid,
    window: windowEnum,
    raw_intake_count: nonNegLong,
    merged_deduplicated_count: nonNegLong,
    flap_silenced_count: nonNegLong,
    dependency_suppressed_count: nonNegLong,
    maintenance_silenced_count: nonNegLong,
    effective_alert_count: nonNegLong,
    observed_at: time,
  })

  const maintenanceWindow = object({
    id: uuid,
    tenant_id: uuid,
    version: versionPos,
    name: { type: 'string', minLength: 1, maxLength: 120 },
    asset_id: nullable(uuid),
    starts_at: time,
    ends_at: time,
    silence_mode: { enum: ['SUPPRESS_NOTIFY', 'SILENCE_ALL_EXCEPT_DISASTER'] },
    status: { enum: ['SCHEDULED', 'ACTIVE', 'ENDED', 'CANCELLED'] },
    sla_exclusion_requested: { type: 'boolean' },
    sla_exclusion_confirmed: { type: 'boolean' },
    sla_exclusion_confirmed_by: nullable(uuid),
    sla_exclusion_confirmed_at: nullable(time),
    reason: { type: 'string', minLength: 1, maxLength: 500 },
    created_by: uuid,
    updated_by: uuid,
    updated_at: time,
  })

  const quarantineItem = object({
    quarantine_id: uuid,
    pool_id: uuid,
    source_event_id: { type: 'string' },
    source_phase: { enum: ['PROBLEM', 'RECOVERY'] },
    claimed_tenant_id: nullable(uuid),
    claimed_asset_id: nullable(uuid),
    quarantine_reason: { type: 'string' },
    occurred_at: time,
    received_at: time,
    quarantine_status: { type: 'string' },
    decision: nullable({ enum: ['BOUND', 'DISCARDED'] }),
    bound_asset_id: nullable(uuid),
    result_event_id: nullable(uuid),
    decision_reason: nullable({ type: 'string' }),
    decided_by: nullable(uuid),
    decided_at: nullable(time),
  })

  const autoTicketAttempt = object({
    id: uuid,
    tenant_id: uuid,
    event_id: uuid,
    version: versionPos,
    attempt_count: nonNegInt,
    max_retries: { const: 5 },
    backoff_schedule_sec: { const: '30,120,600,600,600' },
    status: autoTicketStatusEnum,
    mapped_priority: { enum: ['P1', 'P2', 'P3', 'P4'] },
    mapping_policy_status: { const: 'CONFIG_PENDING_Q14' },
    ticket_id: nullable(uuid),
    next_retry_at: nullable(time),
    last_error: nullable({ type: 'string' }),
    updated_by: uuid,
    updated_at: time,
  })

  const ingestEventInput = object({
    asset_id: uuid,
    rule_code: { type: 'string', minLength: 1, maxLength: 64 },
    rule_name: { type: 'string', minLength: 1, maxLength: 160 },
    severity: severityEnum,
    source_phase: monitorStateEnum,
    source_event_id: { type: 'string', minLength: 1, maxLength: 128 },
    occurred_at: time,
    flap_flip_count_in_10m: nonNegInt,
    subscription_min_severity: nullable(severityEnum),
    simulate_autoticket_failures: nonNegInt,
  })

  const transitionEventInput = object({
    expected_version: versionPos,
    action: { enum: ['ACKNOWLEDGE', 'START_PROGRESS', 'RESOLVE', 'CLOSE'] },
    root_cause_note: nullable({ type: 'string', minLength: 1, maxLength: 1000 }),
  })

  const createMaintenanceWindowInput = object({
    name: { type: 'string', minLength: 1, maxLength: 120 },
    asset_id: nullable(uuid),
    starts_at: time,
    ends_at: time,
    silence_mode: { enum: ['SUPPRESS_NOTIFY', 'SILENCE_ALL_EXCEPT_DISASTER'] },
    sla_exclusion_requested: { type: 'boolean' },
    reason: { type: 'string', minLength: 1, maxLength: 500 },
  })

  const updateMaintenanceWindowInput = object({
    expected_version: versionPos,
    action: { enum: ['EXTEND', 'END_EARLY', 'CANCEL', 'CONFIRM_SLA_EXCLUSION'] },
    new_ends_at: nullable(time),
  })

  const quarantineDecisionInput = object({
    decision: { enum: ['BOUND', 'DISCARDED'] },
    bound_asset_id: nullable(uuid),
    reason: { type: 'string', minLength: 1, maxLength: 500 },
  })

  const manualAutoTicketInput = object({
    expected_version: versionPos,
    reason: { type: 'string', minLength: 1, maxLength: 500 },
  })

  Object.assign(contract.components.schemas, {
    MonitoringEventSummary: eventSummary,
    MonitoringEventSummaryResult: envelope(ref('MonitoringEventSummary')),
    MonitoringEventMember: eventMember,
    MonitoringNoiseRecord: noiseRecord,
    MonitoringNoiseFunnel: noiseFunnel,
    MonitoringNoiseFunnelResult: envelope(ref('MonitoringNoiseFunnel')),
    MaintenanceWindowEntry: maintenanceWindow,
    MaintenanceWindowResult: envelope(ref('MaintenanceWindowEntry')),
    MonitoringQuarantineItem: quarantineItem,
    MonitoringQuarantineDecisionResult: envelope(ref('MonitoringQuarantineItem')),
    AutoTicketAttemptEntry: autoTicketAttempt,
    AutoTicketAttemptResult: envelope(ref('AutoTicketAttemptEntry')),
    MonitoringIngestEventInput: ingestEventInput,
    MonitoringTransitionEventInput: transitionEventInput,
    CreateMaintenanceWindowInput: createMaintenanceWindowInput,
    UpdateMaintenanceWindowInput: updateMaintenanceWindowInput,
    MonitoringQuarantineDecisionInput: quarantineDecisionInput,
    ManualAutoTicketInput: manualAutoTicketInput,
    MonitoringEventWorkspaceResult: envelope(object({
      tenant_id: uuid,
      events: { type: 'array', maxItems: 100, items: ref('MonitoringEventSummary') },
      noise_funnel: ref('MonitoringNoiseFunnel'),
      maintenance_windows: { type: 'array', maxItems: 100, items: ref('MaintenanceWindowEntry') },
      quarantine_items: { type: 'array', maxItems: 100, items: ref('MonitoringQuarantineItem') },
      autoticket_attempts: { type: 'array', maxItems: 100, items: ref('AutoTicketAttemptEntry') },
      review_policy_status: { const: 'CONFIG_PENDING_Q29' },
      mapping_policy_status: { const: 'CONFIG_PENDING_Q14' },
      observed_at: time,
      source: { const: 'postgresql' },
    })),
    MonitoringEventDetailResult: envelope(object({
      event: ref('MonitoringEventSummary'),
      members: { type: 'array', maxItems: 200, items: ref('MonitoringEventMember') },
      noise_records: { type: 'array', maxItems: 200, items: ref('MonitoringNoiseRecord') },
      autoticket_attempt: nullable(ref('MonitoringAutoTicketAttemptRef')),
      observed_at: time,
      source: { const: 'postgresql' },
    })),
    MonitoringAutoTicketAttemptRef: autoTicketAttempt,
  })

  const base = (operationId, summary, response, options = {}) => ({
    operationId,
    summary,
    'x-mop-current-membership-recheck': true,
    'x-mop-source': 'postgresql',
    responses: { 200: { description: summary, content: { 'application/json': { schema: response } } }, ...errors },
    ...options,
  })

  contract.paths['/events'] = {
    get: base('getMonitoringEventWorkspace', '读取监控事件中心主事件流、降噪漏斗、维护窗口、隔离区与自动建单补偿列表（D15/M4-7..M4-11/M4-14/M5-4）', ref('MonitoringEventWorkspaceResult'), {
      parameters: [
        { name: 'window', in: 'query', schema: windowEnum },
        { name: 'page_size', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 25 } },
      ],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
    }),
    post: base('ingestMonitoringEvent', '归一化接入监控事件：5分钟指纹去重合并×N、闪断静默、依赖拓扑抑制、维护窗口静默、2分钟自愈取消通知与自动建单指数退避（M4-7..M4-11/M5-4）', ref('MonitoringEventSummaryResult'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringIngestEventInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }

  contract.paths['/events/{id}'] = {
    get: base('getMonitoringEventDetail', '读取监控事件抽屉详情：原始合并成员列表、降噪分层轨迹与通知空状态精确解释（M4-7/M4-14）', ref('MonitoringEventDetailResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
    }),
  }

  contract.paths['/events/{id}/actions'] = {
    post: base('transitionMonitoringEvent', '执行事件人工处理状态流转（确认固化first_ack_at、开始处理、解决、关闭；监控状态与处理状态正交独立，M4-9）', ref('MonitoringEventSummaryResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringTransitionEventInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }

  contract.paths['/events/noise-funnel'] = {
    get: base('getMonitoringNoiseFunnel', '按24h/7d/30d窗口统计告警降噪漏斗6层计数（原始到达/去重合并/闪断静默/依赖抑制/维护期静默/有效告警，M4-11）', ref('MonitoringNoiseFunnelResult'), {
      parameters: [{ name: 'window', in: 'query', schema: windowEnum }],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
    }),
  }

  contract.paths['/maintenance-windows'] = {
    post: base('createMaintenanceWindow', '创建计划维护窗口（默认sla_exclusion_confirmed=false，未获平台管理员二次确认前不计入SLA排除时长，M4-10）', ref('MaintenanceWindowResult'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('CreateMaintenanceWindowInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }

  contract.paths['/maintenance-windows/{id}/actions'] = {
    post: base('updateMaintenanceWindow', '延长、提前结束、取消维护窗口或由平台管理员二次确认SLA排除生效（M4-10）', ref('MaintenanceWindowResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('UpdateMaintenanceWindowInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }

  contract.paths['/monitoring-quarantine/{id}/decisions'] = {
    post: base('resolveMonitoringQuarantine', '人工处置监控隔离区记录（绑定当前租户资产回灌事件流或明确原因丢弃；追加式账本不修改V30隔离原表，M1-2）', ref('MonitoringQuarantineDecisionResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringQuarantineDecisionInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }

  contract.paths['/autoticket-attempts/{id}/manual-create'] = {
    post: base('manualCreateAutoTicket', '对连续5次自动建单重试耗尽进入隔离态的事件执行一键人工补建工单（M5-4）', ref('AutoTicketAttemptResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('ManualAutoTicketInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }

  contract.info.version = '0.57.0'
}
