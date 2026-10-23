import { ref } from 'vue'
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

export function createProductionReadiness(
  scope: () => { tenantId: string; actorId: string; permissionVersion: number }, client: Pick<typeof api, 'request'> = api,
) {
  const result = ref<ProductionReadiness | null>(null), busy = ref(false), error = ref<unknown>(null)
  let epoch = 0, controller: AbortController | null = null
  async function inspect() {
    const generation = ++epoch, current = scope()
    controller?.abort(); controller = new AbortController(); const active = controller
    busy.value = true; error.value = null; result.value = null
    try {
      const value = await client.request<ProductionReadiness>('/operations/production-readiness', { signal: active.signal })
      if (generation !== epoch || active.signal.aborted) return
      if (!value || value.tenant_id !== current.tenantId || value.actor_id !== current.actorId
        || value.permission_version !== current.permissionVersion || value.production_ready !== false
        || value.activation_performed !== false || value.production_database_kind !== null || value.production_isolation_strategy !== null
        || value.runtime_mode !== 'remote_test' || typeof value.runtime_database_restricted !== 'boolean'
        || !Array.isArray(value.checks) || value.checks.length < 5 || value.checks.length > 9
        || new Set(value.checks.map(check => check.id)).size !== value.checks.length
        || value.checks.some(check => !check || !Object.hasOwn(productionCheckLabels, check.id)
          || !['PASS', 'BLOCKED', 'NOT_READY', 'NOT_RUN', 'CUSTOMER_GATED'].includes(check.status))) {
        throw new ApiError(200, 'INVALID_READINESS_RESPONSE', '运行检查结果与当前授权范围不一致', '—')
      }
      const checks = new Map(value.checks.map(check => [check.id, check.status]))
      if (checks.get('PRODUCTION_DATABASE_ISOLATION') !== 'CUSTOMER_GATED' || checks.get('PRODUCTION_RUNTIME') !== 'BLOCKED'
        || checks.get('PUBLIC_TLS_CERTIFICATE') !== 'NOT_RUN' || !checks.has('SECURE_SESSION_SETTINGS')
        || (value.runtime_database_restricted && ['DATABASE_NAMESPACE', 'DATABASE_ROLE', 'DATABASE_DDL', 'MIGRATION_IDENTITY', 'GLOBAL_DATABASE_CAPABILITIES']
          .some(id => checks.get(id) !== 'PASS'))) {
        throw new ApiError(200, 'INVALID_READINESS_RESPONSE', '运行限制或生产门禁证据不完整', '—')
      }
      result.value = value
    } catch (cause) { if (generation === epoch && !isCancelled(cause)) error.value = cause }
    finally { if (generation === epoch) busy.value = false }
  }
  function reset() { epoch++; controller?.abort(); result.value = null; error.value = null; busy.value = false }
  return { result, busy, error, inspect, reset }
}
