export function addMonitoringEventCenterContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const uuid = { type: 'string', format: 'uuid', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' }
  const time = { type: 'string', format: 'date-time' }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const severityEnum = { enum: ['DISASTER', 'HIGH', 'AVERAGE', 'WARNING', 'INFO'] }
  const monitorStateEnum = { enum: ['PROBLEM', 'RECOVERED'] }
  const handlingStateEnum = { enum: ['NEW', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_REVIEW', 'RESOLVED', 'CLOSED'] }
  const suppressionStateEnum = { enum: ['NONE', 'FLAP_SILENCED', 'DEPENDENCY_SUPPRESSED', 'MAINTENANCE_SILENCED', 'INFO_DEFAULT_SILENT'] }
  const emptyStateEnum = {
    enum: [
      'NORMAL_DISPATCH_ELIGIBLE',
      'DEPENDENCY_SUPPRESSED_NO_NOTIFY',
      'MAINTENANCE_WINDOW_SUPPRESSED',
      'FLAP_SILENCED_NO_NOTIFY',
      'INFO_LEVEL_NO_NOTIFY',
    ],
  }
  const emptyStateLabelEnum = {
    enum: [
      '符合派发条件（外部渠道待Q14/Q18配置）',
      '依赖抑制：未发送通知',
      '维护窗口：通知已抑制',
      '抖动静默：5分钟内不再重复通知',
      '无通知记录（信息级默认不通知）',
    ],
  }
  const noiseLayerEnum = {
    enum: [
      'RAW_INTAKE',
      'MERGE',
      'FLAP',
      'DEPENDENCY',
      'MAINTENANCE',
      'EFFECTIVE_ALERT',
    ],
  }
  const autoTicketStatusEnum = {
    enum: ['SUCCEEDED', 'RETRY_SCHEDULED', 'QUARANTINED_MANUAL_REQUIRED', 'MANUAL_CREATED', 'SUPPRESSED_SKIPPED'],
  }
  const windowEnum = { enum: ['24h', '7d', '30d'] }
  const versionPos = { type: 'integer', minimum: 1, maximum: 9007199254740991 }
  const nonNegInt = { type: 'integer', minimum: 0 }
  const nonNegLong = { type: 'integer', minimum: 0 }
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
    rule_name: { type: 'string', minLength: 1, maxLength: 128 },
    severity: severityEnum,
    severity_rank: { type: 'integer', minimum: 1, maximum: 5 },
    fingerprint: { type: 'string', minLength: 5, maxLength: 160 },
    occurrence_count: { type: 'integer', minimum: 1 },
    flap_flip_count: nonNegInt,
    flap_silenced_until: nullable(time),
    monitor_state: monitorStateEnum,
    handling_state: handlingStateEnum,
    suppression_state: suppressionStateEnum,
    suppression_reason: nullable({ type: 'string', minLength: 1, maxLength: 256 }),
    notification_empty_state: emptyStateEnum,
    notification_empty_state_label: emptyStateLabelEnum,
    sla_unknown_marked: { type: 'boolean' },
    review_policy_status: { enum: ['CONFIG_PENDING_Q29', 'PUBLISHED_Q29'], description: '当前运行写入固定CONFIG_PENDING_Q29；结构保留PUBLISHED_Q29，不表示本次已发布或客户已确认' },
    problem_at: time,
    last_occurred_at: time,
    recovered_at: nullable(time),
    first_ack_at: nullable(time),
    resolved_at: nullable(time),
    closed_at: nullable(time),
    root_cause_note: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
    ticket_id: nullable(uuid),
    updated_by: uuid,
    updated_at: time,
  })

  // Labels come from MonitoringEventPipeline.NotificationEmptyState, not frontend copy.
  eventSummary.allOf = emptyStateEnum.enum.map((state, index) => ({
    if: { properties: { notification_empty_state: { const: state } } },
    then: { properties: { notification_empty_state_label: { const: emptyStateLabelEnum.enum[index] } } },
  }))

  const eventMember = object({
    id: uuid,
    tenant_id: uuid,
    event_id: uuid,
    raw_event_id: nullable(uuid),
    sequence_no: { type: 'integer', minimum: 1 },
    source_event_id: { type: 'string', minLength: 1, maxLength: 256 },
    source_phase: monitorStateEnum,
    occurred_at: time,
    merged_within_window: { type: 'boolean' },
    recorded_at: time,
  })

  const noiseRecord = object({
    id: uuid,
    tenant_id: uuid,
    event_id: uuid,
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
    name: { type: 'string', minLength: 1, maxLength: 128 },
    asset_id: uuid,
    starts_at: time,
    ends_at: time,
    silence_mode: { enum: ['SUPPRESS_NOTIFY', 'PAUSE_COLLECTION'] },
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
    source_event_id: { type: 'string', minLength: 1, maxLength: 256 },
    source_phase: monitorStateEnum,
    claimed_tenant_id: nullable(uuid),
    claimed_asset_id: nullable(uuid),
    quarantine_reason: { enum: ['UNKNOWN_MAPPING', 'CROSS_TENANT_CLAIM', 'SOURCE_KEY_CONFLICT'] },
    occurred_at: time,
    received_at: time,
    quarantine_status: { const: 'HELD_UNVERIFIED' },
    decision: nullable({ enum: ['BIND_ASSET', 'DISCARD'] }),
    bound_asset_id: nullable(uuid),
    result_event_id: nullable(uuid),
    decision_reason: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
    decided_by: nullable(uuid),
    decided_at: nullable(time),
  })

  const autoTicketAttempt = object({
    id: uuid,
    tenant_id: uuid,
    event_id: uuid,
    version: versionPos,
    attempt_count: { type: 'integer', minimum: 1, maximum: 6 },
    max_retries: { const: 5 },
    backoff_schedule_sec: { const: '30,120,600,600,600' },
    status: autoTicketStatusEnum,
    mapped_priority: nullable({ enum: ['P1', 'P2', 'P3'] }),
    mapping_policy_status: { enum: ['CONFIG_PENDING_Q14', 'PUBLISHED_Q14'], description: '当前运行写入固定CONFIG_PENDING_Q14；结构保留PUBLISHED_Q14，不表示本次已发布或客户已确认' },
    ticket_id: nullable(uuid),
    next_retry_at: nullable(time),
    last_error: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
    updated_by: uuid,
    updated_at: time,
  })

  const ingestEventInput = object({
    asset_id: uuid,
    rule_code: { type: 'string', minLength: 1, maxLength: 64 },
    rule_name: { type: 'string', minLength: 1, maxLength: 128 },
    severity: severityEnum,
    source_phase: monitorStateEnum,
    source_event_id: { type: 'string', minLength: 1, maxLength: 256 },
    occurred_at: nullable(time),
    simulate_autoticket_failure: { type: 'boolean' },
  })

  const transitionEventInput = object({
    expected_version: versionPos,
    action: { enum: ['ACKNOWLEDGE', 'START', 'MARK_PENDING_REVIEW', 'RESOLVE', 'CLOSE'] },
    root_cause_note: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
  })
  transitionEventInput.allOf = [{
    if: { properties: { action: { const: 'RESOLVE' } }, required: ['action'] },
    then: { properties: { root_cause_note: { type: 'string', minLength: 20, maxLength: 500 } } },
  }]

  const createMaintenanceWindowInput = object({
    name: { type: 'string', minLength: 1, maxLength: 128 },
    asset_id: uuid,
    starts_at: time,
    ends_at: time,
    silence_mode: { enum: ['SUPPRESS_NOTIFY', 'PAUSE_COLLECTION'] },
    sla_exclusion_requested: { type: 'boolean' },
    reason: { type: 'string', minLength: 1, maxLength: 500 },
  })

  const updateMaintenanceWindowInput = object({
    expected_version: versionPos,
    action: { enum: ['EXTEND', 'END', 'CONFIRM_SLA_EXCLUSION'] },
    new_ends_at: nullable(time),
  })

  const quarantineDecisionInput = object({
    decision: { enum: ['BIND_ASSET', 'DISCARD'] },
    bound_asset_id: nullable(uuid),
    reason: { type: 'string', minLength: 1, maxLength: 500 },
  })

  quarantineDecisionInput.allOf = [
    { if: { properties: { decision: { const: 'BIND_ASSET' } } }, then: { properties: { bound_asset_id: uuid } } },
    { if: { properties: { decision: { const: 'DISCARD' } } }, then: { properties: { bound_asset_id: { type: 'null' } } } },
  ]
  updateMaintenanceWindowInput.allOf = [
    { if: { properties: { action: { const: 'EXTEND' } } }, then: { properties: { new_ends_at: time } } },
  ]
  transitionEventInput.description = '标准动作名；状态机由当前事件状态校验。RESOLVE要求PENDING_REVIEW及trim后不少于20个Unicode码点的根因说明；不能以监控恢复代替人工复核。'

  const manualAutoTicketInput = object({
    expected_version: versionPos,
    // Existing persistence stores an eight-code-point success prefix in varchar(500).
    // This is the safe client contract; the unchanged controller's parser still accepts 500.
    reason: { type: 'string', minLength: 1, maxLength: 492, description: '发送前trim；最多492个Unicode代码点，为既有last_error中的“人工补建成功: ”前缀预留8个代码点，避免超过冻结varchar(500)。这是安全请求上限，不表示后端解析器已改为492。' },
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
      events: { type: 'array', maxItems: 50, items: ref('MonitoringEventSummary') },
      noise_funnel: ref('MonitoringNoiseFunnel'),
      maintenance_windows: { type: 'array', maxItems: 50, items: ref('MaintenanceWindowEntry') },
      quarantine_items: { type: 'array', maxItems: 50, items: ref('MonitoringQuarantineItem') },
      autoticket_attempts: { type: 'array', maxItems: 50, items: ref('AutoTicketAttemptEntry') },
      observed_at: time,
      source: { const: 'postgresql' },
    })),
    MonitoringEventDetailResult: envelope(object({
      event: ref('MonitoringEventSummary'),
      members: { type: 'array', items: ref('MonitoringEventMember') },
      noise_records: { type: 'array', items: ref('MonitoringNoiseRecord') },
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
        { name: 'page_size', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 50, default: 25 } },
      ],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
      'x-mop-allowed-query-keys': ['window', 'page_size'],
      'x-mop-reject-duplicate-query-keys': true,
    }),
  }

  contract.paths['/events/ingest'] = {
    post: base('ingestMonitoringEvent', '归一化接入监控事件：5分钟指纹去重合并×N、闪断静默、依赖拓扑抑制、维护窗口静默与自动建单重试记录（M4-7..M4-11/M5-4）', ref('MonitoringEventSummaryResult'), {
      description: '新事件自动登记前，按当前租户 ticket.default_dedupe_window_sec 关联同指纹窗口内最近的未关闭 WORKFLOW_V33 正式工单，保留原 SLA 快照和处置状态。窗口读取已发布值或无发布时的合同登记默认；停用、过期或无效配置返回 CONFIG_PENDING。未激活登记、其他流程权威不参与复用。恢复观察窗确认和外部通知仍分别受控。',
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringIngestEventInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
      'x-mop-idempotent-replay-reauthorization': true,
    }),
  }

  contract.paths['/events/{id}'] = {
    get: base('getMonitoringEventDetail', '读取监控事件抽屉详情：原始合并成员列表、降噪分层轨迹与通知空状态精确解释（M4-7/M4-14）', ref('MonitoringEventDetailResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
    }),
  }

  contract.paths['/events/{id}/transitions'] = {
    post: base('transitionMonitoringEvent', '执行事件人工处理状态流转（确认固化first_ack_at、开始处理、解决、关闭；监控状态与处理状态正交独立，M4-9）', ref('MonitoringEventSummaryResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringTransitionEventInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
      'x-mop-idempotent-replay-reauthorization': true,
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
      'x-mop-idempotent-replay-reauthorization': true,
    }),
  }

  contract.paths['/maintenance-windows/{id}/actions'] = {
    post: base('updateMaintenanceWindow', '延长、结束维护窗口或由平台管理员二次确认SLA排除生效（M4-10）', ref('MaintenanceWindowResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('UpdateMaintenanceWindowInput') } } },
      'x-mop-action-authorization': {
        EXTEND: { roles: ['msp', 'padmin'], capability: 'ticket_notes:write_internal' },
        END: { roles: ['msp', 'padmin'], capability: 'ticket_notes:write_internal' },
        CONFIRM_SLA_EXCLUSION: { roles: ['padmin'], capability: 'configurations:write' },
      },
      'x-mop-roles': ['msp', 'padmin'],
      description: '授权按action分支检查：CONFIRM_SLA_EXCLUSION要求当前padmin及configurations:write，其他动作要求内部角色及ticket_notes:write_internal；幂等重放前重验同一动作权限。',
      'x-mop-idempotent-replay-reauthorization': true,
    }),
  }

  contract.paths['/monitoring/quarantine/{id}/decisions'] = {
    post: base('resolveMonitoringQuarantine', '人工处置监控隔离区记录（绑定当前租户资产回灌事件流或明确原因丢弃；追加式账本不修改V30隔离原表，M1-2）', ref('MonitoringQuarantineDecisionResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('MonitoringQuarantineDecisionInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
      'x-mop-idempotent-replay-reauthorization': true,
    }),
  }

  contract.paths['/autoticket-attempts/{id}/manual-create'] = {
    post: base('manualCreateAutoTicket', '对连续5次自动建单重试耗尽进入隔离态的事件执行一键人工补建工单（M5-4）', ref('AutoTicketAttemptResult'), {
      description: '与自动登记、到期重试共用同指纹工单去重规则；可能关联窗口内已有正式工单，MANUAL_CREATED 不保证新增一张登记。已有绑定不能覆盖，版本冲突或配置不可用不会留下部分关联。',
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...writeHeaders],
      requestBody: { required: true, content: { 'application/json': { schema: ref('ManualAutoTicketInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
      'x-mop-idempotent-replay-reauthorization': true,
    }),
  }

  contract.info.version = '0.62.0'
}
