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
  contract.components.schemas.ProductionReadiness = { type: 'object', additionalProperties: false, required: Object.keys(properties), properties };
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
