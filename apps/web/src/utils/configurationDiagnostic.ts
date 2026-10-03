import { isUtcInstant } from './utcInstant'
import { api, ApiError, isCancelled } from '../api/client'
import type { ConfigurationDiagnostic, ConfigurationDiagnosticReasonCode, ConfigurationDiagnosticSource } from '../api/types'
import { isTicketUuid } from './ticket'

export interface ConfigurationDiagnosticContext { tenantId: string; key: string; permitted: boolean }
export type ConfigurationDiagnosticReadState =
  | { status: 'idle' }
  | { status: 'loading'; key: string }
  | { status: 'ready'; key: string; diagnostic: ConfigurationDiagnostic }
  | { status: 'error'; key: string; error: unknown }

const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
function shape(value: unknown, fields: readonly string[]): value is Record<string, unknown> {
  return record(value) && fields.every(field => Object.hasOwn(value, field)) && Object.keys(value).every(field => fields.includes(field))
}
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const version = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 1
const questions = (value: unknown): value is string[] | null => value === null || (Array.isArray(value) && value.every(item => typeof item === 'string' && /^Q[0-9]{2}$/.test(item)))
const validKey = (key: unknown): key is string => typeof key === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(key)
const invalidResult = () => new ApiError(200, 'INVALID_CONFIGURATION_DIAGNOSTIC', '诊断返回与当前配置、租户或未生效门禁不一致，请重新查询', '—')
const reasonSources: Record<ConfigurationDiagnosticReasonCode, ConfigurationDiagnosticSource> = {
  DESIGN_REGISTRY_NOT_RUNTIME_CONFIG: 'DESIGN_REGISTRY', RUNTIME_DEFINITION_SOURCE_UNAVAILABLE: 'RUNTIME_DEFINITION',
  PUBLICATION_SOURCE_UNAVAILABLE: 'RUNTIME_PUBLICATION', CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE: 'CUSTOMER_VERIFICATION',
  INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION: 'INTERNAL_REVISION', PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE: 'INTERNAL_REVISION',
  REGISTRY_QUESTIONS_UNAVAILABLE: 'DESIGN_REGISTRY',
}

/** Accept metadata only. Unavailable sources remain null; internal approval never becomes activation. */
export function validateConfigurationDiagnostic(value: unknown, key: string, tenantId: string): ConfigurationDiagnostic {
  if (!validKey(key) || !isTicketUuid(tenantId) || !shape(value, ['key', 'status', 'effective_value', 'context', 'observed_at', 'registry', 'recent_proposals', 'runtime_definition', 'publication', 'customer_verification', 'reasons', 'question_ids'])
    || value.key !== key || value.status !== 'CONFIG_PENDING' || value.effective_value !== null
    || !shape(value.context, ['tenant_id', 'environment']) || value.context.tenant_id !== tenantId || value.context.environment !== 'remote_test'
    || !isUtcInstant(value.observed_at)
    || !shape(value.registry, ['origin', 'version', 'type_label', 'unit_label', 'scope_label', 'source_ref', 'decision_status_label', 'classification_label', 'question_ids'])
    || value.registry.origin !== 'DESIGN_REGISTRY' || !version(value.registry.version)
    || !['type_label', 'unit_label', 'scope_label', 'source_ref', 'decision_status_label', 'classification_label'].every(field => nullableText(value.registry && (value.registry as Record<string, unknown>)[field]))
    || !questions(value.question_ids) || !questions(value.registry.question_ids) || JSON.stringify(value.question_ids) !== JSON.stringify(value.registry.question_ids)
    || !shape(value.recent_proposals, ['items', 'truncated']) || !Array.isArray(value.recent_proposals.items)
    || value.recent_proposals.items.length > 5 || typeof value.recent_proposals.truncated !== 'boolean' || (value.recent_proposals.truncated && value.recent_proposals.items.length !== 5)) throw invalidResult()
  const proposals = value.recent_proposals.items
  const ids = new Set<string>()
  for (const item of proposals) {
    if (!shape(item, ['id', 'version', 'workflow_status', 'origin', 'confirmation_status', 'evidence_input_status', 'evidence_reference_count'])
      || !isTicketUuid(item.id) || ids.has(item.id) || !version(item.version)
      || typeof item.workflow_status !== 'string' || !['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'].includes(item.workflow_status)
      || item.origin !== 'INTERNAL_REVISION' || item.confirmation_status !== 'UNVERIFIED' || item.evidence_input_status !== 'REFERENCE_ONLY'
      || !(item.evidence_reference_count === null || (typeof item.evidence_reference_count === 'number' && Number.isSafeInteger(item.evidence_reference_count) && item.evidence_reference_count >= 0))) throw invalidResult()
    ids.add(item.id)
  }
  for (const [source, fields] of [
    [value.runtime_definition, ['schema_version', 'allowed_layers', 'precedence', 'resolved_scope']],
    [value.publication, ['record_ref', 'revision_id', 'revision_version', 'effective_at', 'authorization_verified', 'production_approval_verified']],
    [value.customer_verification, ['required', 'decision_ref', 'authority_verified', 'authenticity_verified']],
  ] as const) {
    if (!shape(source, ['availability', ...fields]) || source.availability !== 'NOT_IMPLEMENTED' || !fields.every(field => source[field] === null)) throw invalidResult()
  }
  if (!Array.isArray(value.reasons) || !value.reasons.length) throw invalidResult()
  for (const reason of value.reasons) {
    if (!shape(reason, ['code', 'source_kind', 'source_id', 'source_version']) || typeof reason.code !== 'string'
      || !Object.hasOwn(reasonSources, reason.code) || reason.source_kind !== reasonSources[reason.code as ConfigurationDiagnosticReasonCode]) throw invalidResult()
    if (reason.source_kind === 'DESIGN_REGISTRY') {
      if (reason.source_id !== key || reason.source_version !== value.registry.version || (reason.code === 'REGISTRY_QUESTIONS_UNAVAILABLE' && value.question_ids !== null)) throw invalidResult()
    } else if (reason.source_kind === 'INTERNAL_REVISION') {
      const proposal = proposals.find(item => item.id === reason.source_id && item.version === reason.source_version)
      if (!proposal || (reason.code === 'PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE' && proposal.evidence_reference_count !== null)) throw invalidResult()
    } else if (reason.source_id !== null || reason.source_version !== null) throw invalidResult()
  }
  const hasReason = (code: string, id: unknown) => (value.reasons as Record<string, unknown>[]).some(reason => reason.code === code && reason.source_id === id)
  if (!hasReason('DESIGN_REGISTRY_NOT_RUNTIME_CONFIG', key) || !hasReason('RUNTIME_DEFINITION_SOURCE_UNAVAILABLE', null)
    || !hasReason('PUBLICATION_SOURCE_UNAVAILABLE', null) || !hasReason('CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE', null)
    || (value.question_ids === null && !hasReason('REGISTRY_QUESTIONS_UNAVAILABLE', key))
    || proposals.some(item => !hasReason('INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION', item.id)
      || (item.evidence_reference_count === null && !hasReason('PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE', item.id)))) throw invalidResult()
  return value as unknown as ConfigurationDiagnostic
}

export function createConfigurationDiagnosticReader(options: {
  context: () => ConfigurationDiagnosticContext; onChange: (state: ConfigurationDiagnosticReadState) => void
}, client: Pick<typeof api, 'request'> = api) {
  let generation = 0, controller: AbortController | undefined
  let state: ConfigurationDiagnosticReadState = { status: 'idle' }
  function update(next: ConfigurationDiagnosticReadState) { state = next; options.onChange(next) }
  return {
    get state() { return state },
    reset() { ++generation; controller?.abort(); controller = undefined; update({ status: 'idle' }) },
    async load(key: string): Promise<boolean> {
      const context = { ...options.context() }, epoch = ++generation
      controller?.abort(); const request = new AbortController(); controller = request
      const current = () => epoch === generation && !request.signal.aborted && options.context().key === context.key && options.context().tenantId === context.tenantId && options.context().permitted === context.permitted
      update({ status: 'loading', key })
      try {
        if (!context.permitted) throw new ApiError(403, 'ACTION_FORBIDDEN', '当前账号无配置诊断查询权限', '—')
        if (!validKey(key) || !isTicketUuid(context.tenantId) || !context.key) throw new ApiError(0, 'INVALID_DIAGNOSTIC_REFERENCE', '当前配置键或租户上下文异常，请重新查询登记表', '—')
        const result = await client.request<unknown>(`/settings/effective?${new URLSearchParams({ key })}`, { signal: request.signal })
        if (!current()) { if (epoch === generation) update({ status: 'idle' }); return false }
        update({ status: 'ready', key, diagnostic: validateConfigurationDiagnostic(result, key, context.tenantId) })
        return true
      } catch (cause) {
        if (!current()) { if (epoch === generation) update({ status: 'idle' }); return false }
        if (!isCancelled(cause)) update({ status: 'error', key, error: cause })
        else update({ status: 'idle' })
        return false
      }
    },
  }
}

export const configurationDiagnosticReasonLabel: Record<ConfigurationDiagnosticReasonCode, string> = {
  DESIGN_REGISTRY_NOT_RUNTIME_CONFIG: '设计登记尚未接入运行配置来源',
  RUNTIME_DEFINITION_SOURCE_UNAVAILABLE: '运行定义来源尚未接入',
  PUBLICATION_SOURCE_UNAVAILABLE: '发布记录来源尚未接入',
  CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE: '客户核验来源尚未接入；本项是否需要核验仍未知',
  INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION: '内部修订仅为提案，尚未发布为运行配置',
  PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE: '提案证据引用数量未提供，核验状态未知',
  REGISTRY_QUESTIONS_UNAVAILABLE: '登记来源未提供关联Q编号',
}
export const configurationDiagnosticSourceLabel: Record<ConfigurationDiagnosticSource, string> = {
  DESIGN_REGISTRY: '设计登记', INTERNAL_REVISION: '内部提案', RUNTIME_DEFINITION: '运行定义',
  RUNTIME_PUBLICATION: '运行发布', CUSTOMER_VERIFICATION: '客户核验',
}
export const diagnosticLabel = (value: string | null) => value === null ? '未知 · 来源未提供' : value
export const diagnosticQuestions = (value: string[] | null) => value === null ? '未知 · 登记来源未提供Q编号' : value.length ? value.join(' / ') : '登记未列关联Q编号'
