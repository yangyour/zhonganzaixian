import { detachedReadState } from './detachedWorkspaceState'
import type { Configuration, ConfigurationRevision } from '../api/types'

export interface ConfigurationFilters {
  config_key: string
  question_id: string
  pending_only: boolean
  registered_scope: string
}

export type ConfigurationQuestionMetadata = { status: 'valid'; ids: readonly string[] } | { status: 'missing' } | { status: 'invalid' }
export function configurationQuestions(value: unknown): ConfigurationQuestionMetadata {
  if (value === null || value === undefined) return { status: 'missing' }
  if (!Array.isArray(value) || !value.every(id => typeof id === 'string' && /^Q[0-9]{2}$/.test(id))) return { status: 'invalid' }
  return { status: 'valid', ids: value }
}
export function configurationQuestionsLabel(value: unknown): string {
  const metadata = configurationQuestions(value)
  if (metadata.status === 'missing') return 'Q 信息缺失 · 来源未提供'
  if (metadata.status === 'invalid') return 'Q 信息格式异常 · 无法确认关联'
  return metadata.ids.length ? metadata.ids.join(' / ') : '无关联 Q 编号'
}
export function configurationMetadataLabel(value: unknown, label: string): string {
  if (value === null || value === undefined) return `${label}未提供`
  return typeof value === 'string' ? value : `${label}格式异常 · 标签须为文本`
}
export function configurationQuestionOptions(rows: readonly { question_ids?: unknown }[]): string[] {
  // Omit invalid option inputs, without replacing the original row's unknown/invalid field.
  return [...new Set(rows.flatMap(item => { const metadata = configurationQuestions(item.question_ids); return metadata.status === 'valid' ? metadata.ids : [] }))].sort()
}
export function configurationScopeOptions(rows: readonly { scope?: unknown }[]): string[] {
  return [...new Set(rows.flatMap(item => typeof item.scope === 'string' ? [item.scope] : []))].sort()
}
interface ConfigurationFilterRow { key: unknown; question_ids?: unknown; decision_status?: unknown; scope?: unknown }
export function filterConfigurations<T extends ConfigurationFilterRow>(rows: readonly T[], filters: ConfigurationFilters): T[] {
  const key = filters.config_key.trim().toLowerCase()
  const question = filters.question_id.trim().toUpperCase()
  const scope = filters.registered_scope.trim()
  return rows.filter(item => {
    const metadata = configurationQuestions(item.question_ids)
    return (!key || (typeof item.key === 'string' && item.key.toLowerCase().includes(key)))
      && (!question || (metadata.status === 'valid' && metadata.ids.includes(question)))
      && (!filters.pending_only || item.decision_status === 'pending')
      && (!scope || (typeof item.scope === 'string' && item.scope === scope))
  })
}
export function configurationQuestionFilterIssues<T extends ConfigurationFilterRow>(rows: readonly T[], filters: ConfigurationFilters) {
  const candidates = filterConfigurations(rows, { ...filters, question_id: '' })
  let missing = 0, invalid = 0
  for (const item of candidates) {
    const metadata = configurationQuestions(item.question_ids)
    if (metadata.status === 'missing') missing++
    if (metadata.status === 'invalid') invalid++
  }
  return { missing, invalid, cannotConfirmMatch: missing + invalid }
}

export interface ConfigurationDifference {
  path: string
  kind: 'added' | 'removed' | 'changed'
  before_present: boolean
  before: unknown
  after_present: boolean
  after: unknown
}

const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const own = (value: object, key: string | number) => Object.prototype.hasOwnProperty.call(value, key)
const pointerToken = (key: string) => key.replace(/~/g, '~0').replace(/\//g, '~1')

// API values are JSON. Object order is irrelevant; array order and absent fields are significant.
export function compareConfigurationValues(before: unknown, after: unknown): ConfigurationDifference[] {
  const changes: ConfigurationDifference[] = []
  function visit(left: unknown, right: unknown, path: string, leftPresent = true, rightPresent = true) {
    if (leftPresent && rightPresent) {
      if (left === right) return
      if (record(left) && record(right)) {
        for (const key of [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()) {
          const leftPresent = own(left, key), rightPresent = own(right, key)
          visit(leftPresent ? left[key] : undefined, rightPresent ? right[key] : undefined, `${path}/${pointerToken(key)}`, leftPresent, rightPresent)
        }
        return
      }
      if (Array.isArray(left) && Array.isArray(right)) {
        for (let index = 0; index < Math.max(left.length, right.length); index++) {
          visit(left[index], right[index], `${path}/${index}`, own(left, index), own(right, index))
        }
        return
      }
    }
    changes.push({ path, kind: !leftPresent ? 'added' : !rightPresent ? 'removed' : 'changed', before_present: leftPresent, before: left, after_present: rightPresent, after: right })
  }
  visit(before, after, '')
  return changes
}

export function comparisonValue(value: unknown, present = true): string {
  if (!present) return '字段不存在'
  if (value === undefined) return '值未提供'
  if (value === null) return 'null（空值）'
  return JSON.stringify(value, null, 2)
}

export type ConfigurationProposal = { kind: 'draft' } | { kind: 'revision'; revision: ConfigurationRevision }

export function configurationComparison(configuration: Pick<Configuration, 'key' | 'registered_value' | 'draft_value' | 'effective_value' | 'decision_status'> & { scope?: unknown }, proposal: ConfigurationProposal) {
  if (proposal.kind === 'revision' && proposal.revision.configuration_key !== configuration.key) throw new Error('修订与配置登记项不匹配，请重新读取')
  const proposedValue = proposal.kind === 'draft' ? configuration.draft_value : proposal.revision.proposed_value
  const proposalAvailable = proposedValue !== null && proposedValue !== undefined
  return {
    registeredValue: configuration.registered_value,
    proposedValue,
    effectiveValue: configuration.effective_value,
    registeredScope: configurationMetadataLabel(configuration.scope, '登记作用域'),
    decisionStatus: configuration.decision_status,
    proposalAvailable,
    workflowStatus: proposal.kind === 'revision' ? proposal.revision.workflow_status : null,
    confirmationStatus: proposal.kind === 'revision' ? proposal.revision.confirmation_status : null,
    differences: proposalAvailable ? compareConfigurationValues(configuration.registered_value, proposedValue) : [],
  }
}

export type ConfigurationComparisonReadState =
  | { status: 'idle' }
  | { status: 'loading'; key: string }
  | { status: 'ready'; key: string; configuration: Configuration; revisions: readonly ConfigurationRevision[] }
  | { status: 'error'; key: string; error: unknown }

// Keep a complete read together. A failed or superseded refresh never exposes an old snapshot.
export function createConfigurationComparisonReader(options: {
  readConfigurations: (signal: AbortSignal) => Promise<readonly Configuration[]>
  readRevisions: (key: string, signal: AbortSignal) => Promise<readonly ConfigurationRevision[]>
  onChange: (state: ConfigurationComparisonReadState) => void
}) {
  let generation = 0
  let controller: AbortController | undefined
  let state: ConfigurationComparisonReadState = { status: 'idle' }
  function update(next: ConfigurationComparisonReadState) { state = detachedReadState(next); options.onChange(detachedReadState(state)) }
  return {
    get state() { return detachedReadState(state) },
    reset() { ++generation; controller?.abort(); update({ status: 'idle' }) },
    async load(key: string): Promise<boolean> {
      const current = ++generation
      controller?.abort()
      const request = new AbortController(); controller = request
      update({ status: 'loading', key })
      try {
        const [configurations, revisions] = await Promise.all([options.readConfigurations(request.signal), options.readRevisions(key, request.signal)])
        if (current !== generation || request.signal.aborted) return false
        const configuration = configurations.find(item => item.key === key)
        if (!configuration || revisions.some(item => item.configuration_key !== key)) throw new Error('配置登记或修订读取不匹配，请重新读取')
        update({ status: 'ready', key, configuration, revisions })
        return true
      } catch (error) {
        if (current !== generation || request.signal.aborted) return false
        request.abort()
        update({ status: 'error', key, error })
        return false
      }
    },
  }
}
