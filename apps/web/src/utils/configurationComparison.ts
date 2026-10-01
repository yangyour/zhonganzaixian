import type { Configuration, ConfigurationRevision } from '../api/types'

export interface ConfigurationFilters {
  config_key: string
  question_id: string
  pending_only: boolean
  registered_scope: string
}

export function filterConfigurations(rows: readonly Configuration[], filters: ConfigurationFilters): Configuration[] {
  const key = filters.config_key.trim().toLowerCase()
  const question = filters.question_id.trim().toUpperCase()
  const scope = filters.registered_scope.trim()
  return rows.filter(item => (!key || item.key.toLowerCase().includes(key))
    && (!question || item.question_ids.some(id => id.toUpperCase() === question))
    && (!filters.pending_only || item.decision_status === 'pending')
    && (!scope || item.scope === scope))
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

export function configurationComparison(configuration: Configuration, proposal: ConfigurationProposal) {
  if (proposal.kind === 'revision' && proposal.revision.configuration_key !== configuration.key) throw new Error('修订与配置登记项不匹配，请重新读取')
  const proposedValue = proposal.kind === 'draft' ? configuration.draft_value : proposal.revision.proposed_value
  const proposalAvailable = proposedValue !== null && proposedValue !== undefined
  return {
    registeredValue: configuration.registered_value,
    proposedValue,
    effectiveValue: configuration.effective_value,
    registeredScope: configuration.scope,
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
  function update(next: ConfigurationComparisonReadState) { state = next; options.onChange(next) }
  return {
    get state() { return state },
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
