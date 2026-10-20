export function addOnCallEscalationContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const uuid = { type: 'string', format: 'uuid' }
  const time = { type: 'string', format: 'date-time' }
  const dateStr = { type: 'string', format: 'date' }
  const monthStr = { type: 'string', pattern: '^[0-9]{4}-(0[1-9]|1[0-2])$' }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const tierEnum = { enum: ['BASIC', 'STANDARD', 'ADVANCED'] }
  const policyTierEnum = { enum: ['BASIC', 'STANDARD', 'ADVANCED', 'FALLBACK'] }
  const shiftCodeEnum = { enum: ['DAY', 'NIGHT', 'FULL_DAY'] }
  const triggerEnum = { enum: ['IMMEDIATE', 'UNACKNOWLEDGED', 'UNRESOLVED', 'COUNTDOWN_ZERO', 'PHONE_UNANSWERED'] }
  const targetRoleEnum = { enum: ['L1_ONCALL', 'L2_ONCALL', 'DUTY_LEAD', 'CUSTOMER_ADMIN', 'SERVICE_MANAGER', 'DECISION_MAKER'] }
  const smsStatusEnum = { enum: ['NOT_REQUIRED', 'INTENT_RECORDED_PENDING_GATEWAY'] }
  const versionZeroOrPos = { type: 'integer', minimum: 0, maximum: 9007199254740990 }
  const versionPos = { type: 'integer', minimum: 1, maximum: 9007199254740990 }
  const nonNegInt = { type: 'integer', minimum: 0 }
  const nonNegLong = { type: 'integer', minimum: 0, maximum: 9007199254740990 }
  const pctNum = { type: 'number', minimum: 0, maximum: 100 }
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 413, 422, 503].map(code => [
    code,
    { description: '值班看板、交接班、升级链或SLA月报授权、版本、参数或依赖错误', content: { 'application/json': { schema: ref('Error') } } },
  ]))
  const envelope = data => object({ data, trace_id: { type: 'string' } })
  const writeHeaders = [
    { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' } },
    { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } },
  ]

  const boardKpis = object({
    unacknowledged_count: nonNegInt,
    critical_count: nonNegInt,
    in_progress_count: nonNegInt,
    pending_review_count: nonNegInt,
    recovered_today_count: nonNegInt,
    mtta_p50_sec: nullable(nonNegLong),
    mtta_target_sec: nonNegLong,
  })
  const queueItem = object({
    ticket_id: uuid,
    priority: { enum: ['P1', 'P2', 'P3'] },
    state: { enum: ['OPEN', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED', 'REOPENED'] },
    tier_snapshot: tierEnum,
    assignee_user_id: nullable(uuid),
    created_at: time,
    response_due_at: nullable(time),
    recovery_due_at: nullable(time),
    countdown_remaining_sec: nullable({ type: 'integer' }),
    countdown_progress_pct: nullable({ type: 'number', minimum: 0, maximum: 1 }),
    breached: { type: 'boolean' },
    current_escalation_step: { type: 'integer', minimum: 1, maximum: 8 },
    next_escalation_step: nullable({ type: 'integer', minimum: 1, maximum: 8 }),
    next_escalation_target_role: nullable(targetRoleEnum),
  })
  const rosterEntry = object({
    id: uuid,
    tenant_id: uuid,
    version: versionPos,
    shift_date: dateStr,
    shift_code: shiftCodeEnum,
    handoff_local_time: { const: '18:00' },
    primary_oncall_user_id: uuid,
    backup_oncall_user_id: nullable(uuid),
    note: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
    roster_policy_status: { const: 'CONFIG_PENDING_Q15' },
    updated_by: uuid,
    updated_at: time,
  })
  const rosterUpsertInput = object({
    expected_version: versionZeroOrPos,
    shift_date: dateStr,
    shift_code: shiftCodeEnum,
    primary_oncall_user_id: uuid,
    backup_oncall_user_id: nullable(uuid),
    note: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
  })
  const handoffEntry = object({
    id: uuid,
    tenant_id: uuid,
    roster_id: nullable(uuid),
    outgoing_user_id: uuid,
    incoming_user_id: uuid,
    handoff_local_time: { const: '18:00' },
    unacknowledged_count: nonNegInt,
    in_progress_count: nonNegInt,
    pending_review_count: nonNegInt,
    note: { type: 'string', minLength: 1, maxLength: 1000 },
    handed_off_at: time,
  })
  const handoffInput = object({
    roster_id: nullable(uuid),
    incoming_user_id: uuid,
    note: { type: 'string', minLength: 1, maxLength: 1000 },
  })
  const escalationStep = object({
    step_order: { type: 'integer', minimum: 1, maximum: 8 },
    trigger_condition: triggerEnum,
    wait_minutes: { type: 'integer', minimum: 0, maximum: 10080 },
    target_role: targetRoleEnum,
  })
  const escalationPolicy = object({
    tenant_id: uuid,
    tier: policyTierEnum,
    version: versionZeroOrPos,
    unanswered_escalate_count: { type: 'integer', minimum: 1, maximum: 10 },
    unanswered_retry_interval_sec: { type: 'null' },
    steps: { type: 'array', minItems: 1, maxItems: 8, items: ref('EscalationStep') },
    policy_status: { const: 'CONFIG_PENDING_Q15' },
    updated_by: nullable(uuid),
    updated_at: time,
    persisted: { type: 'boolean' },
  })
  const escalationPolicyUpdateInput = object({
    tier: policyTierEnum,
    expected_version: versionZeroOrPos,
    unanswered_escalate_count: { type: 'integer', minimum: 1, maximum: 10 },
    steps: { type: 'array', minItems: 1, maxItems: 8, items: ref('EscalationStep') },
  })
  const escalationStepRun = object({
    id: uuid,
    tenant_id: uuid,
    ticket_id: uuid,
    tier: policyTierEnum,
    policy_version: versionPos,
    step_order: { type: 'integer', minimum: 1, maximum: 8 },
    trigger_condition: triggerEnum,
    wait_minutes: { type: 'integer', minimum: 0, maximum: 10080 },
    target_role: targetRoleEnum,
    unanswered_phone_count: { type: 'integer', minimum: 0, maximum: 10 },
    escalated_to_step: nullable({ type: 'integer', minimum: 2, maximum: 8 }),
    escalated_target_role: nullable(targetRoleEnum),
    sms_notice_status: smsStatusEnum,
    sms_notice_summary: nullable({ type: 'string', minLength: 1, maxLength: 500 }),
    recorded_by: uuid,
    recorded_at: time,
  })
  const escalationTriggerInput = object({
    ticket_id: uuid,
    tier: policyTierEnum,
    step_order: { type: 'integer', minimum: 1, maximum: 8 },
    trigger_condition: triggerEnum,
    unanswered_phone_count: { type: 'integer', minimum: 0, maximum: 10 },
  })
  const latencyStats = object({
    sample_count: nonNegInt,
    unrecovered_count: nonNegInt,
    mean_sec: nullable(nonNegLong),
    p50_sec: nullable(nonNegLong),
    p95_sec: nullable(nonNegLong),
  })
  const explainedInterval = object({
    kind: { enum: ['KNOWN_AVAILABLE', 'APPROVED_EXCLUSION', 'UNAVAILABLE_UNION', 'UNKNOWN_DATA_GAP'] },
    start: time,
    end: time,
    duration_sec: nonNegLong,
    explanation: { type: 'string', minLength: 1, maxLength: 500 },
  })
  const slaServiceRow = object({
    service_id: { type: 'string', minLength: 1, maxLength: 64 },
    service_name: { type: 'string', minLength: 1, maxLength: 256 },
    target_pct: nullable(pctNum),
    agreed_service_sec: nonNegLong,
    approved_exclusion_sec: nonNegLong,
    effective_service_sec: nonNegLong,
    unavailable_union_sec: nonNegLong,
    unknown_interval_sec: nonNegLong,
    known_available_sec: nonNegLong,
    verified_availability_pct: nullable(pctNum),
    upper_bound_availability_pct: nullable(pctNum),
    incident_count: nonNegInt,
    mtta: ref('SlaLatencyStats'),
    mttr: ref('SlaLatencyStats'),
    unrecovered_count: nonNegInt,
    status: { enum: ['MET', 'NOT_MET', 'INSUFFICIENT_SAMPLES', 'UNKNOWN_INTERVAL_PENDING'] },
    unknown_policy_status: { enum: ['NO_UNKNOWN_GAPS', 'UNKNOWN_EXCLUDED_FROM_NUMERATOR_AND_DENOMINATOR'] },
    interval_details: { type: 'array', maxItems: 40000, items: ref('SlaExplainedInterval') },
  })
  const ticketJudgment = object({
    ticket_id: { type: 'string', minLength: 1, maxLength: 128 },
    priority: { enum: ['P1', 'P2', 'P3'] },
    tier_snapshot: tierEnum,
    applicable: nullable({ type: 'boolean' }),
    outcome: { enum: ['MET', 'NOT_MET', 'UNRESOLVED', 'NOT_APPLICABLE'] },
    response_check: { enum: ['MET', 'NOT_MET', 'UNKNOWN'] },
    recovery_check: { enum: ['MET', 'NOT_MET', 'UNKNOWN'] },
    response_due_at: nullable(time),
    response_actual_at: nullable(time),
    recovery_due_at: nullable(time),
    recovery_actual_at: nullable(time),
    reasons: { type: 'array', maxItems: 16, items: { type: 'string' } },
  })
  const monthlyEvalProps = {
    tier_snapshot: nullable(tierEnum),
    total_ticket_count: nonNegInt,
    applicable_count: nonNegInt,
    met_count: nonNegInt,
    not_met_count: nonNegInt,
    unresolved_count: nonNegInt,
    not_applicable_count: nonNegInt,
    achievement_numerator: nullable(nonNegInt),
    achievement_denominator: nullable(nonNegInt),
    achievement_rate_pct: nullable(pctNum),
    compensation_ratio: nullable({ enum: [0.1, 0.2, 0.3] }),
    monthly_fee_fen: nullable(nonNegLong),
    compensation_amount_fen: { type: 'null' },
    compensation_status: { const: 'CONFIG_PENDING_Q26' },
    candidate_fixed_ratio_fen: nullable(nonNegLong),
    candidate_shortfall_weighted_fen: nullable(nonNegLong),
    formula_version: { enum: ['customer-20261020-availability-v2', 'contract-m5-9-m5-19-v1'] },
    items: { type: 'array', maxItems: 2000, items: ref('SlaTicketJudgment') },
  }
  const monthlyEval = object(monthlyEvalProps)
  const monthlyRecord = object({
    id: uuid,
    tenant_id: uuid,
    report_month: monthStr,
    version: versionPos,
    ...monthlyEvalProps,
    frozen: { type: 'boolean' },
    frozen_at: nullable(time),
    evaluated_by: uuid,
    evaluated_at: time,
  })
  const monthlyResultInput = object({
    report_month: monthStr,
    expected_version: versionZeroOrPos,
    freeze: { type: 'boolean' },
  })

  Object.assign(contract.components.schemas, {
    OnCallBoardKpis: boardKpis,
    OnCallQueueItem: queueItem,
    OnCallRosterEntry: rosterEntry,
    OnCallRosterUpsertInput: rosterUpsertInput,
    OnCallRosterResult: envelope(ref('OnCallRosterEntry')),
    OnCallHandoffEntry: handoffEntry,
    OnCallHandoffInput: handoffInput,
    OnCallHandoffResult: envelope(ref('OnCallHandoffEntry')),
    EscalationStep: escalationStep,
    EscalationPolicy: escalationPolicy,
    EscalationPolicyUpdateInput: escalationPolicyUpdateInput,
    EscalationPolicyResult: envelope(ref('EscalationPolicy')),
    EscalationPolicyListResult: envelope(object({
      tenant_id: uuid,
      items: { type: 'array', minItems: 1, maxItems: 8, items: ref('EscalationPolicy') },
      policy_status: { const: 'CONFIG_PENDING_Q15' },
      source: { const: 'postgresql' },
    })),
    EscalationStepRun: escalationStepRun,
    EscalationTriggerInput: escalationTriggerInput,
    EscalationStepRunResult: envelope(ref('EscalationStepRun')),
    OnCallBoardResult: envelope(object({
      tenant_id: uuid,
      kpis: ref('OnCallBoardKpis'),
      queue: { type: 'array', maxItems: 100, items: ref('OnCallQueueItem') },
      rosters: { type: 'array', maxItems: 100, items: ref('OnCallRosterEntry') },
      handoffs: { type: 'array', maxItems: 100, items: ref('OnCallHandoffEntry') },
      policies: { type: 'array', maxItems: 8, items: ref('EscalationPolicy') },
      step_runs: { type: 'array', maxItems: 100, items: ref('EscalationStepRun') },
      auto_refresh_sec: { const: 15 },
      handoff_local_time: { const: '18:00' },
      roster_policy_status: { const: 'CONFIG_PENDING_Q15' },
      observed_at: time,
      source: { const: 'postgresql' },
    })),
    SlaLatencyStats: latencyStats,
    SlaExplainedInterval: explainedInterval,
    SlaServiceRow: slaServiceRow,
    SlaTicketJudgment: ticketJudgment,
    SlaMonthlyEvaluation: monthlyEval,
    SlaMonthlyResultRecord: monthlyRecord,
    SlaMonthlyResultInput: monthlyResultInput,
    SlaMonthlyResultEnvelope: envelope(ref('SlaMonthlyResultRecord')),
    SlaReportResult: envelope(object({
      tenant_id: uuid,
      period: monthStr,
      current_tier: nullable(tierEnum),
      target_availability_pct: nullable(pctNum),
      kpis: object({
        mean_verified_availability_pct: nullable(pctNum),
        unavailable_union_total_sec: nonNegLong,
        mtta: ref('SlaLatencyStats'),
        mttr: ref('SlaLatencyStats'),
        unrecovered_total_count: nonNegInt,
        unknown_interval_total_sec: nonNegLong,
      }),
      services: { type: 'array', maxItems: 1000, items: ref('SlaServiceRow') },
      monthly_result: nullable(ref('SlaMonthlyResultRecord')),
      live_monthly_evaluation: ref('SlaMonthlyEvaluation'),
      formula_version: { const: 'customer-20261020-availability-v2' },
      timezone: { const: 'Asia/Shanghai' },
      observed_at: time,
      source: { const: 'postgresql' },
    })),
    SlaServiceDetailResult: envelope(object({
      tenant_id: uuid,
      period: monthStr,
      service: ref('SlaServiceRow'),
      interval_details: { type: 'array', maxItems: 40000, items: ref('SlaExplainedInterval') },
      ticket_judgments: { type: 'array', maxItems: 2000, items: ref('SlaTicketJudgment') },
      formula_version: { const: 'customer-20261020-availability-v2' },
      timezone: { const: 'Asia/Shanghai' },
      observed_at: time,
      source: { const: 'postgresql' },
    })),
  })

  const base = (operationId, summary, response, options = {}) => ({
    operationId,
    summary,
    'x-mop-current-membership-recheck': true,
    'x-mop-source': 'postgresql',
    responses: { 200: { description: summary, content: { 'application/json': { schema: response } } }, ...errors },
    ...options,
  })

  contract.paths['/oncall/board'] = {
    get: base('getOnCallBoard', '读取值班看板6项KPI、15s自动刷新工单倒计时队列、18:00交接班台账与升级链配置（M5-12/M5-13）', ref('OnCallBoardResult'), {
      parameters: [{ name: 'page_size', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 25 } }],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
    }),
  }
  contract.paths['/oncall/rosters'] = {
    post: base('upsertOnCallRoster', '登记或更新当班主备值班排班（乐观锁CAS，排班细则状态CONFIG_PENDING_Q15）', ref('OnCallRosterResult'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('OnCallRosterUpsertInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }
  contract.paths['/oncall/handoffs'] = {
    post: base('recordOnCallHandoff', '登记18:00只追加交接班记录，自动固化当前未确认、进行中、待复核工单计数（M5-12）', ref('OnCallHandoffResult'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('OnCallHandoffInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }
  contract.paths['/escalation-policies'] = {
    get: base('listEscalationPolicies', '读取基础/标准/进阶/兜底升级策略链（M5-13）', ref('EscalationPolicyListResult'), {
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'tickets:read',
    }),
    patch: base('updateEscalationPolicy', '修改指定档位升级链步骤与连续未接听阈值（M5-13，重呼叫间隔保持CONFIG_PENDING_Q15）', ref('EscalationPolicyResult'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('EscalationPolicyUpdateInput') } } },
      'x-mop-roles': ['padmin'],
      'x-mop-capability': 'configurations:write',
    }),
  }
  contract.paths['/oncall/escalations'] = {
    post: base('triggerOnCallEscalation', '触发倒计时归零或电话连续2次未接听自动升级并登记短信告知记录（M5-12/M5-13）', ref('EscalationStepRunResult'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('EscalationTriggerInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }
  contract.paths['/sla/reports'] = {
    get: base('getSlaReport', '读取月度SLA报表、MTTA/MTTR(P50/P95)、未知区间单独隔离与赔付候选结果（M5-9/M5-10/M5-19）', ref('SlaReportResult'), {
      parameters: [{ name: 'period', in: 'query', schema: monthStr }],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'sla_scenarios:read',
    }),
  }
  contract.paths['/sla/reports/{serviceId}/details'] = {
    get: base('getSlaServiceDetails', '读取指定服务节点时间区间合并/排除/未知隔离明细与逐工单SLA判定（M5-9/M5-10/M5-19）', ref('SlaServiceDetailResult'), {
      parameters: [
        { name: 'serviceId', in: 'path', required: true, schema: { type: 'string', minLength: 1, maxLength: 64 } },
        { name: 'period', in: 'query', schema: monthStr },
      ],
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'sla_scenarios:read',
    }),
  }
  contract.paths['/sla/monthly-results'] = {
    post: base('evaluateSlaMonthlyResult', '生成或冻结月度SLA达标与赔付快照（已冻结快照不可覆盖，正式赔付金额保持CONFIG_PENDING_Q26）', ref('SlaMonthlyResultEnvelope'), {
      parameters: writeHeaders,
      requestBody: { required: true, content: { 'application/json': { schema: ref('SlaMonthlyResultInput') } } },
      'x-mop-roles': ['msp', 'padmin'],
      'x-mop-capability': 'ticket_notes:write_internal',
    }),
  }
  contract.info.version = '0.56.0'
}
