import { describe, expect, it } from 'vitest'
import type { Configuration, ConfigurationRevision } from '../src/api/types'
import { compareConfigurationValues, comparisonValue, configurationComparison, createConfigurationComparisonReader, filterConfigurations, type ConfigurationComparisonReadState, type ConfigurationFilters } from '../src/utils/configurationComparison'

const configuration = (changes: Partial<Configuration> = {}): Configuration => ({
  key: 'monitor.threshold', description: '阈值登记', type: 'number', scope: '模板/设备', unit: null,
  value: null, registered_value: null, effective_value: null, decision_status: 'pending', source: 'design_registry', question_ids: ['Q03', 'Q20'],
  activation_gate: null, notes: null, allowed_range: null, contract_value_kind: 'pending', version: 2, draft_value: 0, lifecycle_status: 'DRAFT',
  ...changes,
})
const revision = (changes: Partial<ConfigurationRevision> = {}): ConfigurationRevision => ({
  id: 'revision-a', tenant_id: 'tenant-a', configuration_key: 'monitor.threshold', proposed_value: false, reason: '纯计算测试建议',
  evidence_refs: [], workflow_status: 'DRAFT', version: 1, created_by: 'creator', created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z',
  reviewed_by: null, reviewed_at: null, review_comment: null, decision_status: 'pending', effective_value: null, confirmation_status: 'UNVERIFIED', source: 'postgresql', history: [],
  ...changes,
})
const filters = (changes: Partial<ConfigurationFilters> = {}): ConfigurationFilters => ({ config_key: '', question_id: '', pending_only: false, registered_scope: '', ...changes })

describe('configuration registry filters', () => {
  const rows = [
    configuration(),
    configuration({ key: 'monitor.sample_interval', scope: '平台', question_ids: ['Q20'] }),
    configuration({ key: 'monitor.threshold.fixed', decision_status: 'contract_fixed' }),
    configuration({ key: 'asset.threshold', question_ids: ['Q03'] }),
  ]
  it('combines config key, exact Q identifier, pending status and exact registered scope', () => {
    expect(filterConfigurations(rows, filters({ config_key: ' MONITOR.THRESHOLD ', question_id: ' q20 ', pending_only: true, registered_scope: '模板/设备' })).map(item => item.key)).toEqual(['monitor.threshold'])
    expect(filterConfigurations(rows, filters({ config_key: 'monitor', question_id: 'Q20', pending_only: true })).map(item => item.key)).toEqual(['monitor.threshold', 'monitor.sample_interval'])
  })
  it('does not treat a partial Q or one segment of a registered scope as an exact match', () => {
    expect(filterConfigurations(rows, filters({ question_id: 'Q2' }))).toEqual([])
    expect(filterConfigurations(rows, filters({ registered_scope: '设备' }))).toEqual([])
    expect(filterConfigurations(rows, filters({ question_id: 'Q03', registered_scope: '平台' }))).toEqual([])
  })
  it('retains all statuses when pending is unchecked and leaves input order and values intact', () => {
    expect(filterConfigurations(rows, filters())).toEqual(rows)
    expect(filterConfigurations(rows, filters({ pending_only: true }))).toHaveLength(3)
    expect(rows[0]?.registered_value).toBeNull()
    expect(rows[0]?.draft_value).toBe(0)
    expect(rows[2]?.decision_status).toBe('contract_fixed')
  })
})

describe('structured configuration differences', () => {
  it('preserves explicit null, zero, false, and strings without coercion', () => {
    for (const [before, after] of [[null, 0], [null, false], [0, false], [0, '0'], [false, 'false']]) {
      expect(compareConfigurationValues(before, after)).toEqual([{ path: '', kind: 'changed', before_present: true, before, after_present: true, after }])
    }
    expect(comparisonValue(null)).toBe('null（空值）')
    expect(comparisonValue(0)).toBe('0')
    expect(comparisonValue(false)).toBe('false')
    expect(comparisonValue('0')).toBe('"0"')
  })
  it('compares nested objects independently of property order and returns only changed leaves', () => {
    const before = { window: { minutes: 0, enabled: false }, limits: { lower: null, upper: 10 } }
    const after = { limits: { upper: 12, lower: null }, window: { enabled: false, minutes: 0 } }
    expect(compareConfigurationValues(before, after)).toEqual([{ path: '/limits/upper', kind: 'changed', before_present: true, before: 10, after_present: true, after: 12 }])
    expect(compareConfigurationValues(before, { limits: { upper: 10, lower: null }, window: { enabled: false, minutes: 0 } })).toEqual([])
    expect(before.limits.upper).toBe(10)
  })
  it('distinguishes missing fields from present null, including removed and added nested structures', () => {
    const changes = compareConfigurationValues({ removed: { enabled: false }, pending: null }, { added: { count: 0 }, absent: null })
    expect(changes.map(item => [item.path, item.kind, item.before_present, item.after_present])).toEqual([
      ['/absent', 'added', false, true], ['/added', 'added', false, true], ['/pending', 'removed', true, false], ['/removed', 'removed', true, false],
    ])
    expect(changes[0]?.after).toBeNull()
    expect(changes[2]?.before).toBeNull()
    expect(comparisonValue(changes[0]?.before, false)).toBe('字段不存在')
    expect(comparisonValue(undefined)).toBe('值未提供')
  })
  it('escapes slash and tilde field names without colliding with nested paths', () => {
    const before = JSON.parse('{"a/b":0,"a":{"b":0},"~flag":false,"__proto__":{"enabled":false}}')
    const after = JSON.parse('{"a/b":1,"a":{"b":2},"~flag":true,"__proto__":{"enabled":true}}')
    expect(compareConfigurationValues(before, after).map(item => item.path)).toEqual(['/__proto__/enabled', '/a/b', '/a~1b', '/~0flag'])
    expect(compareConfigurationValues({}, JSON.parse('{"__proto__":null}'))).toEqual([{ path: '/__proto__', kind: 'added', before_present: false, before: undefined, after_present: true, after: null }])
  })
  it('preserves array order and shows positional nested changes, additions and removals', () => {
    expect(compareConfigurationValues([{ active: false }, null], [{ active: true }, null, 0]).map(item => [item.path, item.kind])).toEqual([['/0/active', 'changed'], ['/2', 'added']])
    expect(compareConfigurationValues([0, false], [false, 0]).map(item => item.path)).toEqual(['/0', '/1'])
    expect(compareConfigurationValues([null], [])).toEqual([{ path: '/0', kind: 'removed', before_present: true, before: null, after_present: false, after: undefined }])
  })
  it('shows type changes at the containing field and treats matching empty collections as equal', () => {
    expect(compareConfigurationValues({ value: [] }, { value: {} })).toEqual([{ path: '/value', kind: 'changed', before_present: true, before: [], after_present: true, after: {} }])
    expect(compareConfigurationValues({ value: { enabled: false } }, { value: false })[0]?.path).toBe('/value')
    expect(compareConfigurationValues({ empty: [], nested: {} }, { nested: {}, empty: [] })).toEqual([])
  })
})

describe('saved proposal comparison boundaries', () => {
  it('uses registered_value rather than legacy value or effective_value as the comparison baseline', () => {
    const result = configurationComparison(configuration({ value: 99, registered_value: 10, effective_value: null, draft_value: 0 }), { kind: 'draft' })
    expect(result.registeredValue).toBe(10)
    expect(result.proposedValue).toBe(0)
    expect(result.differences[0]?.before).toBe(10)
    expect(result.effectiveValue).toBeNull()
    expect(result.registeredScope).toBe('模板/设备')
    expect(configurationComparison(configuration({ effective_value: undefined }), { kind: 'draft' }).effectiveValue).toBeUndefined()
  })
  it('does not substitute registered or effective values when no draft exists', () => {
    const result = configurationComparison(configuration({ registered_value: 10, draft_value: null }), { kind: 'draft' })
    expect(result.proposalAvailable).toBe(false)
    expect(result.proposedValue).toBeNull()
    expect(result.differences).toEqual([])
    expect(configurationComparison(configuration({ draft_value: undefined }), { kind: 'draft' }).proposalAvailable).toBe(false)
    expect(configurationComparison(configuration({ draft_value: false }), { kind: 'draft' }).proposalAvailable).toBe(true)
  })
  it('compares the chosen revision proposal without promoting APPROVED to customer confirmation or effective value', () => {
    const registration = configuration({ draft_value: 25 })
    const approved = revision({ workflow_status: 'APPROVED', proposed_value: 0, reviewed_by: 'reviewer' })
    const result = configurationComparison(registration, { kind: 'revision', revision: approved })
    expect(result).toMatchObject({ registeredValue: null, proposedValue: 0, effectiveValue: null, decisionStatus: 'pending', workflowStatus: 'APPROVED', confirmationStatus: 'UNVERIFIED', proposalAvailable: true })
    expect(result.differences[0]).toMatchObject({ before: null, after: 0 })
    expect(registration.draft_value).toBe(25)
    expect(registration.decision_status).toBe('pending')
    expect(approved.effective_value).toBeNull()
  })
  it('rejects a revision from another configuration instead of comparing unrelated proposals', () => {
    expect(() => configurationComparison(configuration(), { kind: 'revision', revision: revision({ configuration_key: 'different.key' }) })).toThrow('不匹配')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail })
  return { promise, resolve, reject }
}

// These callbacks are isolated read-state tests, not HTTP or test-server integration.
describe('configuration comparison refresh boundaries', () => {
  it.each(['registration', 'revisions'] as const)('clears an existing snapshot while refreshing and after a %s read failure, then permits an explicit retry', async failingRead => {
    const failure = new Error('read failed')
    const states: ConfigurationComparisonReadState[] = []
    let fail = false
    const reader = createConfigurationComparisonReader({
      readConfigurations: async () => { if (fail && failingRead === 'registration') throw failure; return [configuration()] },
      readRevisions: async () => { if (fail && failingRead === 'revisions') throw failure; return [revision()] },
      onChange: state => { states.push(state) },
    })
    expect(await reader.load('monitor.threshold')).toBe(true)
    expect(reader.state.status).toBe('ready')
    fail = true
    const refresh = reader.load('monitor.threshold')
    expect(reader.state).toEqual({ status: 'loading', key: 'monitor.threshold' })
    expect(await refresh).toBe(false)
    expect(reader.state).toEqual({ status: 'error', key: 'monitor.threshold', error: failure })
    fail = false
    expect(await reader.load('monitor.threshold')).toBe(true)
    expect(states.map(state => state.status)).toEqual(['loading', 'ready', 'loading', 'error', 'loading', 'ready'])
  })
  it('discards late results and aborts a previous read when another configuration is opened', async () => {
    const oldConfigurations = deferred<Configuration[]>()
    const oldRevisions = deferred<ConfigurationRevision[]>()
    const signals: AbortSignal[] = []
    let reads = 0
    const reader = createConfigurationComparisonReader({
      readConfigurations: signal => { signals.push(signal); return ++reads === 1 ? oldConfigurations.promise : Promise.resolve([configuration({ key: 'new.key', draft_value: false })]) },
      readRevisions: key => key === 'monitor.threshold' ? oldRevisions.promise : Promise.resolve([revision({ configuration_key: 'new.key' })]),
      onChange: () => {},
    })
    const oldRead = reader.load('monitor.threshold')
    expect(await reader.load('new.key')).toBe(true)
    expect(signals[0]?.aborted).toBe(true)
    oldConfigurations.resolve([configuration()]); oldRevisions.resolve([revision()])
    expect(await oldRead).toBe(false)
    expect(reader.state).toMatchObject({ status: 'ready', key: 'new.key', configuration: { draft_value: false } })
  })
  it('clears and cancels a comparison on close or context reset and ignores a late rejection', async () => {
    const registration = deferred<Configuration[]>()
    const proposals = deferred<ConfigurationRevision[]>()
    let signal!: AbortSignal
    const reader = createConfigurationComparisonReader({ readConfigurations: current => { signal = current; return registration.promise }, readRevisions: () => proposals.promise, onChange: () => {} })
    const loading = reader.load('monitor.threshold')
    reader.reset()
    expect(signal.aborted).toBe(true)
    expect(reader.state).toEqual({ status: 'idle' })
    registration.reject(new Error('late cancellation')); proposals.resolve([revision()])
    expect(await loading).toBe(false)
    expect(reader.state).toEqual({ status: 'idle' })
  })
  it.each(['missing registration', 'unrelated revision'] as const)('rejects a partial snapshot with %s', async boundary => {
    const reader = createConfigurationComparisonReader({
      readConfigurations: async () => boundary === 'missing registration' ? [] : [configuration()],
      readRevisions: async () => [revision({ configuration_key: boundary === 'unrelated revision' ? 'different.key' : 'monitor.threshold' })],
      onChange: () => {},
    })
    expect(await reader.load('monitor.threshold')).toBe(false)
    expect(reader.state.status).toBe('error')
    expect(reader.state).not.toHaveProperty('configuration')
    expect(reader.state).not.toHaveProperty('revisions')
  })
  it('returns saved approved revisions as proposals alongside an unchanged pending registration', async () => {
    const reader = createConfigurationComparisonReader({ readConfigurations: async () => [configuration()], readRevisions: async () => [revision({ workflow_status: 'APPROVED' })], onChange: () => {} })
    expect(await reader.load('monitor.threshold')).toBe(true)
    expect(reader.state).toMatchObject({ status: 'ready', configuration: { decision_status: 'pending', registered_value: null, effective_value: null }, revisions: [{ workflow_status: 'APPROVED', confirmation_status: 'UNVERIFIED', effective_value: null }] })
  })
})
it.each(['getter','callback'])('cannot rewrite a completed comparison through %s',async mode=>{const states:ConfigurationComparisonReadState[]=[];const reader=createConfigurationComparisonReader({readConfigurations:async()=>[configuration()],readRevisions:async()=>[revision()],onChange:s=>{states.push(s)}});expect(await reader.load(configuration().key)).toBe(true);const expected=structuredClone(reader.state),exposed=mode==='getter'?reader.state:states.at(-1)!;if(exposed.status!=='ready')throw Error('Expected ready');exposed.configuration.draft_value='tampered';exposed.revisions[0]!.reason='tampered';expect(reader.state).toEqual(expected)})
it('detaches comparison data from the original read-source arrays',async()=>{const configs=[configuration()],revisions=[revision()];const reader=createConfigurationComparisonReader({readConfigurations:async()=>configs,readRevisions:async()=>revisions,onChange:()=>{}});await reader.load(configs[0]!.key);const expected=structuredClone(reader.state);configs[0]!.draft_value='tampered';revisions[0]!.reason='tampered';expect(reader.state).toEqual(expected)})
