export function addServiceTierContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const uuid = { type: 'string', format: 'uuid' }, time = { type: 'string', format: 'date-time' }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const tierEnum = { enum: ['BASIC', 'STANDARD', 'ADVANCED'] }
  const modeEnum = { enum: ['IMMEDIATE', 'NEXT_MONTH_FIRST_DAY'] }
  const cycleEnum = { enum: ['MONTHLY', 'QUARTERLY', 'YEARLY'] }
  const targetNum = { type: 'number', exclusiveMinimum: 0, maximum: 100 }
  const deviceCount = { type: 'integer', minimum: 0, maximum: 1000000 }
  const feeFen = { type: 'integer', minimum: 0, maximum: 9007199254740990 }
  const versionPos = { type: 'integer', minimum: 1, maximum: 9007199254740990 }
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 413, 422, 503].map(code => [code, { description: '服务档位或SLA时钟授权、版本、参数或依赖错误', content: { 'application/json': { schema: ref('Error') } } }]))
  const envelope = data => object({ data, trace_id: { type: 'string' } })

  const snapshot = object({
    tenant_id: uuid, version: versionPos, tier: tierEnum, availability_target: nullable(targetNum),
    contract_device_count: nullable(deviceCount), billing_cycle: nullable(cycleEnum), monthly_fee_fen: nullable(feeFen),
    effective_mode: modeEnum, effective_from: time, updated_by: uuid, updated_at: time,
    customer_approval_status: { const: 'UNVERIFIED' },
  })
  const change = object({
    id: uuid, tenant_id: uuid, version: versionPos, previous_version: nullable({ type: 'integer', minimum: 1, maximum: 9007199254740989 }),
    from_tier: nullable(tierEnum), to_tier: tierEnum, availability_target: nullable(targetNum),
    contract_device_count: nullable(deviceCount), billing_cycle: nullable(cycleEnum), monthly_fee_fen: nullable(feeFen),
    effective_mode: modeEnum, effective_from: time, reason: { type: 'string', minLength: 1, maxLength: 1000 },
    approved_by: uuid, changed_at: time, customer_approval_status: { const: 'UNVERIFIED' },
  })
  const changeInput = object({
    expected_version: { type: 'integer', minimum: 0, maximum: 9007199254740990 },
    to_tier: tierEnum, effective_mode: modeEnum,
    contract_device_count: nullable(deviceCount), billing_cycle: nullable(cycleEnum), monthly_fee_fen: nullable(feeFen),
    reason: { type: 'string', minLength: 1, maxLength: 1000 },
  })
  const clockStatus = object({
    name: { enum: ['RESPONSE', 'RECOVERY'] }, due_at: nullable(time), completed_at: nullable(time),
    paused: { type: 'boolean' }, breached: { type: 'boolean' },
    status: { enum: ['PENDING_CONFIG', 'RUNNING', 'PAUSED_FOR_CUSTOMER_CONFIRM', 'MET', 'BREACHED', 'CANCELLED'] },
    missing_reason: nullable({ type: 'string' }),
    thresholds: object({ p50_at: nullable(time), p80_at: nullable(time), p100_at: nullable(time) }),
    working_windows_count: { type: 'integer', minimum: 0, maximum: 1000 },
  })
  const slaInspection = object({
    ticket_id: uuid, tenant_id: uuid, receipt_no: { type: 'string' }, registration_created_at: time,
    workflow_version: { type: 'integer', minimum: 0, maximum: 9007199254740990 },
    state: nullable({ enum: ['OPEN', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'] }),
    priority: nullable({ enum: ['P1', 'P2', 'P3'] }),
    frozen_service_tier: nullable(tierEnum), current_tenant_service_tier: nullable(tierEnum),
    tier_changed_since_open: { type: 'boolean' },
    response_clock: nullable(ref('TicketSlaClockStatus')), recovery_clock: nullable(ref('TicketSlaClockStatus')),
    clock_tasks: { type: 'array', maxItems: 64, items: object({ clock_key: { type: 'string' }, kind: { type: 'string' }, due_at: time, outcome: nullable({ type: 'string' }), completed_at: nullable(time) }) },
    sla_missing: { type: 'object', additionalProperties: { type: 'string' } }, sla_references: { type: 'array', maxItems: 20 },
    reopen_count: { type: 'integer', minimum: 0 }, reopen_policy_status: { enum: ['NOT_REOPENED', 'PENDING_Q27'] },
    metric_version: { const: 'D16_TICKET_SLA_CLOCK_V1' }, observed_at: time, source: { const: 'postgresql' },
  })

  Object.assign(contract.components.schemas, {
    ServiceTierSnapshot: snapshot,
    ServiceTierChange: change,
    ServiceTierChangeInput: changeInput,
    ServiceTierResult: envelope(ref('ServiceTierSnapshot')),
    ServiceTierPageResult: envelope(object({
      tenant_id: uuid, current: nullable(ref('ServiceTierSnapshot')), items: { type: 'array', items: ref('ServiceTierChange'), maxItems: 100 },
      has_more: { type: 'boolean' }, next_before_version: nullable(versionPos), observed_at: time,
      customer_approval_status: { const: 'UNVERIFIED' }, in_flight_ticket_retroactive: { const: false }, source: { const: 'postgresql' },
    })),
    TicketSlaClockStatus: clockStatus,
    TicketSlaInspection: slaInspection,
    TicketSlaInspectionResult: envelope(ref('TicketSlaInspection')),
  })

  const base = (operationId, summary, response, options = {}) => ({
    operationId, summary, 'x-mop-current-membership-recheck': true, 'x-mop-source': 'postgresql',
    responses: { 200: { description: summary, content: { 'application/json': { schema: response } } }, ...errors }, ...options,
  })
  contract.paths['/service-tiers'] = {
    get: base('getServiceTiers', '读取当前租户服务档位快照与只追加变更历史；档位变更不追溯在途工单（M2-10/M5-20）', ref('ServiceTierPageResult'), {
      parameters: [
        { name: 'page_size', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 25 } },
        { name: 'before_version', in: 'query', schema: versionPos },
      ],
      'x-mop-roles': ['msp', 'padmin'], 'x-mop-capability': 'sla_scenarios:read',
    }),
  }
  contract.paths['/service-tiers/changes'] = {
    post: base('changeServiceTier', '记录当前租户服务档位变更并更新档位快照；历史不可修改，不追溯已开单SLA截止时间', ref('ServiceTierResult'), {
      parameters: [
        { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' } },
        { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } },
      ],
      requestBody: { required: true, content: { 'application/json': { schema: ref('ServiceTierChangeInput') } } },
      'x-mop-roles': ['padmin'], 'x-mop-capability': 'configurations:write',
    }),
  }
  contract.paths['/tickets/{id}/sla'] = {
    get: base('readTicketSlaClock', '读取工单冻结SLA时钟、50/80/100%提醒阈值、暂停状态与当前租户档位对照（M2-10/M5-8/M5-18/M5-20）', ref('TicketSlaInspectionResult'), {
      parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }],
      'x-mop-capability': 'tickets:read',
    }),
  }
  contract.info.version = '0.55.0'
}
