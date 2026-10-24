import { ref } from 'vue'
import { isUtcInstant } from './utcInstant'
import { api, ApiError, isCancelled } from '../api/client'

export interface ProductionReadiness {
  tenant_id: string; actor_id: string; permission_version: number; runtime_mode: string
  runtime_database_restricted: boolean; database_observed_at: string | null; database_version: string | null
  production_database_kind: null; production_isolation_strategy: null; production_ready: false; activation_performed: false
  checks: { id: string; status: 'PASS' | 'BLOCKED' | 'NOT_READY' | 'NOT_RUN' | 'CUSTOMER_GATED'; code: string }[]
}
export const productionCheckLabels: Record<string, string> = {
  DATABASE_NAMESPACE: '测试数据库及账号归属', DATABASE_ROLE: '运行账号权限', DATABASE_DDL: '运行账号结构变更权限',
  MIGRATION_IDENTITY: '迁移账号分离', GLOBAL_DATABASE_CAPABILITIES: '数据库系统级权限', DATABASE_OBSERVATION: '数据库实际观测',
  SECURE_SESSION_SETTINGS: '安全会话配置', PUBLIC_TLS_CERTIFICATE: '公开入口证书',
  PRODUCTION_DATABASE_ISOLATION: '生产数据库隔离方案', PRODUCTION_RUNTIME: '生产运行环境',
}

export interface ProductionReadinessScope { tenantId: string; actorId: string; permissionVersion: number }
const exact = (value: unknown, keys: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const validScope = (scope: ProductionReadinessScope) => [scope.tenantId, scope.actorId].every(id => typeof id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id))
  && Number.isSafeInteger(scope.permissionVersion) && scope.permissionVersion > 0 && scope.permissionVersion <= 9_007_199_254_740_990
const sameScope = (left: ProductionReadinessScope, right: ProductionReadinessScope) => left.tenantId === right.tenantId && left.actorId === right.actorId && left.permissionVersion === right.permissionVersion
const bad = () => new ApiError(200, 'INVALID_READINESS_RESPONSE', '运行检查的授权范围、数据库观测或状态证据不一致，未展示通过结论', '—')
const fields = ['tenant_id', 'actor_id', 'permission_version', 'runtime_mode', 'runtime_database_restricted', 'database_observed_at', 'database_version',
  'production_database_kind', 'production_isolation_strategy', 'production_ready', 'activation_performed', 'checks']
const databaseChecks = ['DATABASE_NAMESPACE', 'DATABASE_ROLE', 'DATABASE_DDL', 'MIGRATION_IDENTITY', 'GLOBAL_DATABASE_CAPABILITIES']
const commonChecks = ['SECURE_SESSION_SETTINGS', 'PUBLIC_TLS_CERTIFICATE', 'PRODUCTION_DATABASE_ISOLATION', 'PRODUCTION_RUNTIME']
/** Exact outcomes emitted by ProductionReadinessController; a known ID alone is not evidence of a pass. */
const checkCodes: Record<string, Readonly<Record<string, string>>> = {
  DATABASE_NAMESPACE: { PASS: 'DEDICATED_TEST_SCOPE', BLOCKED: 'RUNTIME_SCOPE_REJECTED' },
  DATABASE_ROLE: { PASS: 'ROLE_RESTRICTED', BLOCKED: 'ELEVATED_ROLE_REJECTED' },
  DATABASE_DDL: { PASS: 'RUNTIME_DDL_DENIED', BLOCKED: 'RUNTIME_DDL_PRIVILEGE_REJECTED' },
  MIGRATION_IDENTITY: { PASS: 'MIGRATION_IDENTITY_SEPARATE', BLOCKED: 'MIGRATION_ROLE_MEMBERSHIP_REJECTED' },
  GLOBAL_DATABASE_CAPABILITIES: { PASS: 'GLOBAL_CAPABILITIES_DENIED', BLOCKED: 'GLOBAL_CAPABILITIES_REJECTED' },
  DATABASE_OBSERVATION: { NOT_READY: 'DATABASE_OBSERVATION_UNAVAILABLE' },
  SECURE_SESSION_SETTINGS: { PASS: 'DIRECT_TLS_SETTINGS_VALID', BLOCKED: 'SECURE_SESSION_NOT_CONFIGURED' },
  PUBLIC_TLS_CERTIFICATE: { NOT_RUN: 'PUBLIC_ENTRY_PROBE_REQUIRED' },
  PRODUCTION_DATABASE_ISOLATION: { CUSTOMER_GATED: 'Q02_PENDING' },
  PRODUCTION_RUNTIME: { BLOCKED: 'REMOTE_TEST_IS_NOT_PRODUCTION' },
}

/** Validate complete observed/unobserved branches before any per-check status reaches the page. */
export function readProductionReadiness(raw: unknown, scope: ProductionReadinessScope): ProductionReadiness {
  if (!validScope(scope) || !exact(raw, fields) || raw.tenant_id !== scope.tenantId || raw.actor_id !== scope.actorId
      || raw.permission_version !== scope.permissionVersion || raw.runtime_mode !== 'remote_test'
      || typeof raw.runtime_database_restricted !== 'boolean' || raw.production_ready !== false || raw.activation_performed !== false
      || raw.production_database_kind !== null || raw.production_isolation_strategy !== null || !Array.isArray(raw.checks)) throw bad()
  const observed = raw.database_observed_at !== null
  if (observed ? !isUtcInstant(raw.database_observed_at) || typeof raw.database_version !== 'string' || !raw.database_version.trim()
    : raw.database_version !== null || raw.runtime_database_restricted !== false) throw bad()
  const required = [...(observed ? databaseChecks : ['DATABASE_OBSERVATION']), ...commonChecks]
  if (raw.checks.length !== required.length) throw bad()
  const checks = new Map<string, string>()
  for (const check of raw.checks) {
    if (!exact(check, ['id', 'status', 'code']) || typeof check.id !== 'string' || !required.includes(check.id) || checks.has(check.id)
        || typeof check.status !== 'string' || typeof check.code !== 'string' || !Object.hasOwn(checkCodes[check.id]!, check.status)
        || checkCodes[check.id]![check.status] !== check.code) throw bad()
    checks.set(check.id, check.status)
  }
  if (required.some(id => !checks.has(id)) || observed && raw.runtime_database_restricted !== databaseChecks.every(id => checks.get(id) === 'PASS')) throw bad()
  return structuredClone(raw) as unknown as ProductionReadiness
}

export function createProductionReadiness(
  scope: () => ProductionReadinessScope, client: Pick<typeof api, 'request'> = api,
) {
  const result = ref<ProductionReadiness | null>(null), busy = ref(false), error = ref<unknown>(null)
  let epoch = 0, controller: AbortController | null = null
  async function inspect() {
    const generation = ++epoch, current = { ...scope() }
    controller?.abort(); controller = new AbortController(); const active = controller
    busy.value = true; error.value = null; result.value = null
    try {
      if (!validScope(current)) throw new ApiError(401, 'SESSION_EXPIRED', '当前会话范围无效', '—')
      if (generation !== epoch || active.signal.aborted || !sameScope(current, scope())) return
      const value = await client.request<unknown>('/operations/production-readiness', { signal: active.signal })
      if (generation !== epoch || active.signal.aborted || !sameScope(current, scope())) return
      result.value = readProductionReadiness(value, current)
    } catch (cause) { if (generation === epoch && !isCancelled(cause)) error.value = cause }
    finally { if (generation === epoch) busy.value = false }
  }
  function reset() { epoch++; controller?.abort(); result.value = null; error.value = null; busy.value = false }
  return { result, busy, error, inspect, reset }
}
