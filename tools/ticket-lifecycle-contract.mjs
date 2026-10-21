export function addTicketLifecycleContract(contract) {
  const ref = name => ({ $ref: '#/components/schemas/' + name });
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
  const string = { type: 'string' }, uuid = { type: 'string', format: 'uuid' };
  const time = { type: ['string', 'null'], format: 'date-time' };
  const version = { type: 'integer', minimum: 1, maximum: 9007199254740990 };
  const hash = { type: 'string', pattern: '^[0-9a-f]{64}$' };
  const envelope = data => object({ data, trace_id: string });
  const parameter = (name, location, schema, required = true) => ({ name, in: location, schema, required });
  const ticket = parameter('ticketId', 'path', uuid);
  const schemas = contract.components.schemas;
  schemas.TicketLifecycleSnapshot = object({
    tenant_id: uuid, ticket_id: uuid, version,
    state: { enum: ['NEW', 'IN_PROGRESS', 'WAITING_VENDOR', 'PENDING_CUSTOMER', 'RESOLVED', 'CLOSED', 'REOPENED'] },
    reopen_count: { type: 'integer', minimum: 0 },
    occurred_at: { type: 'string', format: 'date-time' },
    resolved_at: time, closed_at: time, response_due_at: time, recovery_due_at: time,
    solution_snapshot_hash: { ...hash, type: ['string', 'null'] },
    close_mode: { enum: [null, 'customer_confirm', 'timeout_auto'] },
    assignee_label: { enum: [null, '工程师'] },
    assigned_engineer_id: { ...uuid, type: ['string', 'null'] },
    production_approved: { const: false },
    notification_status: { const: 'NOT_INTEGRATED' },
    sla_clock_status: { const: 'NOT_INTEGRATED' },
  });
  schemas.TicketLifecycleResult = envelope(object({
    lifecycle: { anyOf: [ref('TicketLifecycleSnapshot'), { type: 'null' }] },
    status: { enum: ['NOT_STARTED', 'TEST_LIFECYCLE'] },
    source: { const: 'postgresql' }, production_approved: { const: false },
  }));
  schemas.TicketLifecycleHistoryResult = envelope(object({
    items: { type: 'array', maxItems: 100, items: ref('TicketLifecycleSnapshot') },
    has_more: { type: 'boolean' }, next_before_version: { ...version, type: ['integer', 'null'] },
    source: { const: 'postgresql' }, production_approved: { const: false },
  }));
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 422, 503].map(code => [code, {
    description: '输入、权限、版本、未配置或真实依赖错误',
    content: { 'application/json': { schema: ref('Error') } },
  }]));
  const responses = schema => ({ 200: { description: '当前租户持久状态；不代表通知或SLA Worker已完成', content: { 'application/json': { schema } } }, ...errors });
  const root = '/tickets/{ticketId}/lifecycle';
  contract.paths[root] = { get: { operationId: 'getTicketLifecycle', parameters: [ticket],
    'x-mop-capabilities': ['tickets:read'], responses: responses(ref('TicketLifecycleResult')) } };
  schemas.TicketLifecycleSolutionResult = envelope(object({
    tenant_id: uuid, ticket_id: uuid, lifecycle_version: version,
    resolved_at: { type: 'string', format: 'date-time' }, ticket_summary: string,
    solution: { type: 'string', minLength: 20 }, snapshot_hash: hash,
    engineer_label: { const: '工程师' }, source: { const: 'postgresql' }, production_approved: { const: false },
  }));
  contract.paths[root + '/solution'] = { get: { operationId: 'getTicketLifecycleSolution',
    parameters: [ticket, parameter('expected_hash', 'query', hash)],
    'x-mop-capabilities': ['tickets:read'], responses: responses(ref('TicketLifecycleSolutionResult')) } };
  contract.paths[root + '/history'] = { get: { operationId: 'getTicketLifecycleHistory', parameters: [
    ticket, parameter('page_size', 'query', { type: 'integer', enum: [10, 15, 25, 50, 100], default: 15 }, false),
    parameter('before_version', 'query', { ...version, maximum: 9007199254740991 }, false),
  ], 'x-mop-capabilities': ['tickets:read'], responses: responses(ref('TicketLifecycleHistoryResult')) } };
  contract.paths[root].post = {
    operationId: 'startTicketLifecycle',
    parameters: [ticket, parameter('Idempotency-Key', 'header', { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' }), parameter('X-CSRF-Token', 'header', string)],
    requestBody: { required: true, content: { 'application/json': { schema: object({ expected_version: { const: 0 }, priority: { enum: ['P1','P2','P3'] } }) } } },
    'x-mop-capabilities': ['tickets:lifecycle_write'],
    description: '授权内部工程师明确分类优先级；租户档位、日历及预算仅从同一数据库事务的生效配置读取。未知/失效/缺少配置返回CONFIG_PENDING，不使用默认值。仅创建测试生命周期，非生产批准。',
    responses: responses(ref('TicketLifecycleResult')),
  };
  const reason = { type: 'string', minLength: 1, maxLength: 16000 };
  const bodies = {
    accept: { expected_version: version },
    transfer: { expected_version: version, reason, assignee_id: uuid, keep_prior_assignee: { type: 'boolean' } },
    'wait-for-vendor': { expected_version: version, reason },
    resume: { expected_version: version },
    'submit-resolution': { expected_version: version, reason },
    confirm: { expected_version: version, expected_solution_hash: hash },
    'not-resolved': { expected_version: version, reason, expected_solution_hash: hash },
    reopen: { expected_version: version, reason },
  };
  for (const [action, properties] of Object.entries(bodies)) {
    const customer = ['confirm', 'not-resolved', 'reopen'].includes(action);
    contract.paths[root + '/actions/' + action] = { post: {
      operationId: 'ticketLifecycle' + action.split('-').map(s => s[0].toUpperCase() + s.slice(1)).join(''),
      parameters: [ticket, parameter('Idempotency-Key', 'header', { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' }), parameter('X-CSRF-Token', 'header', string)],
      requestBody: { required: true, content: { 'application/json': { schema: object(properties) } } },
      'x-mop-capabilities': [customer ? 'tickets:customer_decide' : 'tickets:lifecycle_write'],
      description: '默认关闭，需MOP_TICKET_LIFECYCLE_ENABLED、显式授权及V33；原请求重放前重新核对当前权限和工单关系。客户仅本人报障，客户DTO不暴露工程师标识。无自动关单用户入口。',
      responses: responses(ref('TicketLifecycleResult')),
    } };
  }
  const nullableMillis = { type: ['integer','null'], minimum: 0, maximum: 9007199254740990 };
  schemas.TicketSlaClock = object({ status: { enum: ['RUNNING','PAUSED','COMPLETED','CONFIG_PENDING'] },
    deadline: time, stopped_at: time, elapsed_ms: nullableMillis, budget_ms: nullableMillis,
    breached: { type: ['boolean','null'] }, missing: { type: ['string','null'] } });
  schemas.TicketSlaProjection = object({ tenant_id: uuid, ticket_id: uuid, tier: { enum: ['BASIC','STANDARD','ADVANCED'] },
    priority: { enum: ['P1','P2','P3'] }, observed_at: { type: 'string', format: 'date-time' }, lifecycle_version: version,
    configuration_reference: { type: 'string', pattern: '^sha256:[0-9a-f]{64}$' }, calculation_policy_reference: { type: ['string','null'], maxLength: 256 }, clock_policy: { enum: [null,'EXCLUDE_CONFIRMED_PAUSES','INCLUDE_ALL_ELAPSED_TIME'] }, response: ref('TicketSlaClock'), recovery: ref('TicketSlaClock'), paused_at: time,
    completed_pauses: { type: 'array', maxItems: 10000, items: object({ start: { type: 'string', format: 'date-time' }, end: { type: 'string', format: 'date-time' } }) },
    reminders_delivered: { const: false }, production_approved: { const: false } });
  schemas.TicketSlaResult = envelope(object({ clock: { anyOf: [ref('TicketSlaProjection'), { type: 'null' }] },
    status: { enum: ['NOT_STARTED','PROJECTED'] }, source: { const: 'postgresql' }, production_approved: { const: false } }));
  contract.paths['/tickets/{ticketId}/sla'] = { get: { operationId: 'getTicketSlaProjection', parameters: [ticket],
    'x-mop-capabilities': ['tickets:read'], 'x-mop-read-only': true,
    description: '仅服务端基于不可变开单配置和完整历史投影，最多10000版本。未发布Q27时重开恢复时钟待配置，不默认续算。无提醒送达或生产批准声明。',
    responses: responses(ref('TicketSlaResult')) } };
  schemas.TicketSlaPolicyBindingResult = envelope(object({ tenant_id: uuid, ticket_id: uuid, lifecycle_version: version,
    status: { enum: ['OPENING_FROZEN','BOUND','AVAILABLE','CONFIG_PENDING'] },
    release_id: { ...uuid, type: ['string','null'] }, release_version: { ...version, type: ['integer','null'] },
    clock_policy: { enum: [null,'EXCLUDE_CONFIRMED_PAUSES','INCLUDE_ALL_ELAPSED_TIME'] }, bound_at: time,
    reference: { type: ['string','null'], maxLength: 256 }, missing: { type: ['string','null'] }, source: { const: 'postgresql' }, production_approved: { const: false } }));
  contract.paths['/tickets/{ticketId}/sla/policy-binding'] = {
    get: { operationId: 'getTicketSlaPolicyBinding', parameters: [ticket], 'x-mop-capabilities': ['tickets:read'], responses: responses(ref('TicketSlaPolicyBindingResult')) },
    post: { operationId: 'bindTicketSlaPolicy', parameters: [ticket,parameter('Idempotency-Key','header',{type:'string',pattern:'^[A-Za-z0-9._:-]{1,128}$'}),parameter('X-CSRF-Token','header',string)],
      requestBody: { required: true, content: { 'application/json': { schema: object({ expected_lifecycle_version: version, release_id: uuid, release_version: version }) } } },
      'x-mop-capabilities': ['tickets:lifecycle_write'], description: 'V35显式迁移后，仅为缺失Q27的工单一次绑定当前已生效发布；不可替换既有冻结口径或修改开单快照，绑定和审计同事务。',
      responses: responses(ref('TicketSlaPolicyBindingResult')) }
  };
  schemas.TicketSlaReminder = object({ intent_id: uuid, clock: { enum: ['RESPONSE','RECOVERY'] }, progress: { enum: [0.5,0.8,1] },
    lifecycle_version: version, observed_at: { type: 'string', format: 'date-time' }, elapsed_ms: { type: 'integer', minimum: 0, maximum: 9007199254740990 },
    budget_ms: { type: 'integer', minimum: 1, maximum: 9007199254740990 }, configuration_reference: { type: 'string', pattern: '^sha256:[0-9a-f]{64}$' },
    policy_reference: { type: ['string','null'], maxLength: 256 }, escalation_required: { type: 'boolean' }, notification_status: { const: 'NOT_ROUTED' }, delivered: { const: false } });
  schemas.TicketSlaReminderResult = envelope(object({ tenant_id: uuid, ticket_id: uuid, lifecycle_version: version,
    scan_state: { enum: ['PENDING','LEASED','IDLE','BLOCKED_ATTEMPTS'] }, last_outcome: { enum: [null,'CONFIG_PENDING','INTENTS_RECORDED_NOT_DELIVERED','NO_DUE_NODE','ATTEMPT_LIMIT_REACHED'] }, last_scan_at: time,
    items: { type: 'array', maxItems: 6, items: ref('TicketSlaReminder') }, source: { const: 'postgresql' }, production_approved: { const: false } }));
  contract.paths['/tickets/{ticketId}/sla/reminders'] = { get: { operationId: 'getTicketSlaReminders', parameters: [ticket],
    'x-mop-capabilities': ['tickets:read'], 'x-mop-read-only': true, description: 'V36持久提醒/意图记录，不代表供应商受理或送达。队列状态不证明Worker在线；没有记录不等于未过阈值。', responses: responses(ref('TicketSlaReminderResult')) } };
  contract.info.version = '0.57.0';
}
