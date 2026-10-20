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
  contract.info.version = '0.54.0';
}
