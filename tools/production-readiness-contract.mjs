export function addProductionReadinessContract(contract) {
  const nullable = { type: ['string', 'null'] };
  const properties = {
    tenant_id: { type: 'string', format: 'uuid' }, actor_id: { type: 'string', format: 'uuid' },
    permission_version: { type: 'integer', minimum: 1 }, runtime_mode: { const: 'remote_test' },
    runtime_database_restricted: { type: 'boolean' }, database_observed_at: { ...nullable, format: 'date-time' },
    database_version: nullable, production_database_kind: { type: 'null' }, production_isolation_strategy: { type: 'null' },
    production_ready: { const: false }, activation_performed: { const: false },
    checks: { type: 'array', minItems: 5, maxItems: 9, items: {
      type: 'object', additionalProperties: false, required: ['id', 'status', 'code'], properties: {
        id: { enum: ['DATABASE_NAMESPACE', 'DATABASE_ROLE', 'DATABASE_DDL', 'MIGRATION_IDENTITY', 'GLOBAL_DATABASE_CAPABILITIES',
          'DATABASE_OBSERVATION', 'SECURE_SESSION_SETTINGS', 'PUBLIC_TLS_CERTIFICATE', 'PRODUCTION_DATABASE_ISOLATION', 'PRODUCTION_RUNTIME'] },
        status: { enum: ['PASS', 'BLOCKED', 'NOT_READY', 'NOT_RUN', 'CUSTOMER_GATED'] }, code: { type: 'string', pattern: '^[A-Z0-9_]+$' },
      },
    } },
  };
  const databaseCodes = {
    DATABASE_NAMESPACE: ['DEDICATED_TEST_SCOPE', 'RUNTIME_SCOPE_REJECTED'],
    DATABASE_ROLE: ['ROLE_RESTRICTED', 'ELEVATED_ROLE_REJECTED'],
    DATABASE_DDL: ['RUNTIME_DDL_DENIED', 'RUNTIME_DDL_PRIVILEGE_REJECTED'],
    MIGRATION_IDENTITY: ['MIGRATION_IDENTITY_SEPARATE', 'MIGRATION_ROLE_MEMBERSHIP_REJECTED'],
    GLOBAL_DATABASE_CAPABILITIES: ['GLOBAL_CAPABILITIES_DENIED', 'GLOBAL_CAPABILITIES_REJECTED'],
  };
  const matrix = Object.entries({ ...databaseCodes,
    SECURE_SESSION_SETTINGS: ['DIRECT_TLS_SETTINGS_VALID', 'SECURE_SESSION_NOT_CONFIGURED'],
  }).flatMap(([id, codes]) => codes.map((code, index) => ({ id, status: index === 0 ? 'PASS' : 'BLOCKED', code })));
  matrix.push({ id: 'DATABASE_OBSERVATION', status: 'NOT_READY', code: 'DATABASE_OBSERVATION_UNAVAILABLE' },
    { id: 'PUBLIC_TLS_CERTIFICATE', status: 'NOT_RUN', code: 'PUBLIC_ENTRY_PROBE_REQUIRED' },
    { id: 'PRODUCTION_DATABASE_ISOLATION', status: 'CUSTOMER_GATED', code: 'Q02_PENDING' },
    { id: 'PRODUCTION_RUNTIME', status: 'BLOCKED', code: 'REMOTE_TEST_IS_NOT_PRODUCTION' });
  properties.checks.items = { oneOf: matrix.map(row => ({ type: 'object', additionalProperties: false,
    required: ['id', 'status', 'code'], properties: Object.fromEntries(Object.entries(row).map(([key, value]) => [key, { const: value }])) })) };
  const containsId = id => ({ contains: { type: 'object', required: ['id'], properties: { id: { const: id } } } });
  const fixed = ['SECURE_SESSION_SETTINGS', 'PUBLIC_TLS_CERTIFICATE', 'PRODUCTION_DATABASE_ISOLATION', 'PRODUCTION_RUNTIME'];
  const database = Object.keys(databaseCodes);
  properties.permission_version.maximum = 9007199254740990;
  contract.components.schemas.ProductionReadiness = { type: 'object', additionalProperties: false, required: Object.keys(properties), properties,
    allOf: [{ if: { properties: { database_observed_at: { type: 'null' } } },
      then: { properties: { database_version: { type: 'null' }, runtime_database_restricted: { const: false },
        checks: { minItems: 5, maxItems: 5, allOf: [...fixed, 'DATABASE_OBSERVATION'].map(containsId) } } },
      else: { properties: { database_observed_at: { type: 'string', format: 'date-time', pattern: 'Z$' },
        database_version: { type: 'string', minLength: 1 },
        checks: { minItems: 9, maxItems: 9, allOf: [...fixed, ...database].map(containsId) } },
      allOf: [{ if: { properties: { runtime_database_restricted: { const: true } } },
        then: { properties: { checks: { allOf: database.map(id => ({ contains: { type: 'object', required: ['id', 'status'],
          properties: { id: { const: id }, status: { const: 'PASS' } } } })) } } },
        else: { properties: { checks: { contains: { type: 'object', required: ['id', 'status'],
          properties: { id: { enum: database }, status: { const: 'BLOCKED' } } } } } } }] } }] };
  contract.paths['/operations/production-readiness'] = { get: {
    operationId: 'inspectProductionReadiness', summary: '配置读取成员的真实运行账号隔离检查；不启用生产、不选择Q02方案',
    'x-mop-capability': 'configurations:read', 'x-mop-read-only': true,
    responses: { 200: { description: '授权诊断；数据库断连仍保留未知，不提升为生产就绪', content: {
      'application/json': { schema: { type: 'object', required: ['data', 'trace_id'], additionalProperties: false,
        properties: { data: { $ref: '#/components/schemas/ProductionReadiness' }, trace_id: { type: 'string' } } } },
    } }, ...Object.fromEntries([400, 401, 403, 503].map(code => [code, { description: '输入或当前成员授权／依赖错误',
      content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } } }])) },
  } };
}
