import { describe, expect, it } from 'vitest'
import type { Configuration } from '../src/api/types'
import { configurationComparison, configurationMetadataLabel, configurationQuestionFilterIssues, configurationQuestionOptions, configurationQuestions, configurationQuestionsLabel, configurationScopeOptions, filterConfigurations, type ConfigurationFilters } from '../src/utils/configurationComparison'

const base: Configuration = {
  key: 'meta.valid', description: 'Metadata-only unit row', type: 'integer', scope: '平台', unit: null,
  value: null, registered_value: null, effective_value: null, decision_status: 'pending', source: 'design_registry', question_ids: ['Q03', 'Q20'],
  activation_gate: null, notes: null, allowed_range: null, contract_value_kind: 'pending', version: 1, draft_value: null, lifecycle_status: 'DRAFT',
}
const row = (key: string, question_ids: unknown) => ({ ...base, key, question_ids })
const filters = (changes: Partial<ConfigurationFilters> = {}): ConfigurationFilters => ({ config_key: '', question_id: '', registered_scope: '', pending_only: false, ...changes })
const mixed = [
  row('meta.valid', ['Q03', 'Q20']), row('meta.empty', []), row('meta.null', null), row('meta.missing', undefined),
  row('meta.object', { body: 'UNIT_STRUCTURED_BODY' }), row('meta.string', 'Q03'),
  row('meta.non_q', ['Q03', 'NOT_Q']), row('meta.non_string', ['Q03', 20]), row('meta.lowercase', ['q03']),
]

describe('mixed registry Q metadata and label rendering', () => {
  it('keeps malformed/missing rows searchable while classifying their display, without rewriting any source field', () => {
    expect(filterConfigurations(mixed, filters())).toEqual(mixed)
    for (const item of mixed) {
      const result = filterConfigurations(mixed, filters({ config_key: item.key }))
      expect(result).toEqual([item]); expect(result[0]).toBe(item)
      expect(() => configurationQuestionsLabel(item.question_ids)).not.toThrow()
    }
    expect(configurationQuestionsLabel(mixed[1]!.question_ids)).toBe('无关联 Q 编号')
    for (const item of mixed.slice(2, 4)) expect(configurationQuestionsLabel(item.question_ids)).toContain('缺失')
    for (const item of mixed.slice(4)) {
      const label = configurationQuestionsLabel(item.question_ids)
      expect(label).toContain('格式异常'); expect(label).not.toContain('无关联'); expect(label).not.toContain('UNIT_STRUCTURED_BODY')
    }
    expect(mixed[2]!.question_ids).toBeNull(); expect(mixed[3]!.question_ids).toBeUndefined()
    expect(mixed[4]!.question_ids).toEqual({ body: 'UNIT_STRUCTURED_BODY' }); expect(mixed[5]!.question_ids).toBe('Q03')
  })

  it('offers Q options only from wholly valid arrays and never harvests an apparently valid prefix from malformed content', () => {
    expect(configurationQuestionOptions(mixed)).toEqual(['Q03', 'Q20'])
    expect(configurationQuestionOptions([row('bad.mixed', ['Q99', 'bad']), row('bad.string', 'Q98'), row('bad.object', { question: 'Q97' })])).toEqual([])
    expect(configurationQuestionOptions([row('empty', []), row('null', null), row('missing', undefined)])).toEqual([])
  })

  it('matches an explicit Q only for valid source arrays and reports the missing/invalid rows as unconfirmed matches', () => {
    expect(filterConfigurations(mixed, filters({ question_id: ' q03 ' })).map(item => item.key)).toEqual(['meta.valid'])
    expect(configurationQuestionFilterIssues(mixed, filters({ question_id: 'Q03' }))).toEqual({ missing: 2, invalid: 5, cannotConfirmMatch: 7 })
    expect(filterConfigurations(mixed, filters({ question_id: 'Q2' }))).toEqual([])
    expect(filterConfigurations(mixed, filters({ question_id: 'Q03', config_key: 'meta.non_q' }))).toEqual([])
    expect(configurationQuestionFilterIssues(mixed, filters({ question_id: 'Q03', config_key: 'meta.non_q' }))).toEqual({ missing: 0, invalid: 1, cannotConfirmMatch: 1 })
    expect(filterConfigurations(mixed, filters({ config_key: 'meta.non_q' })).map(item => item.key)).toEqual(['meta.non_q'])
  })

  it('separates an actual missing property from a valid empty array and does not claim either matches Q03', () => {
    const { question_ids: _questions, ...absent } = base
    expect(configurationQuestions(Reflect.get(absent, 'question_ids'))).toEqual({ status: 'missing' })
    expect(configurationQuestions([])).toEqual({ status: 'valid', ids: [] })
    expect(filterConfigurations([absent], filters())).toEqual([absent])
    expect(filterConfigurations([absent], filters({ question_id: 'Q03' }))).toEqual([])
    expect(configurationQuestionFilterIssues([absent], filters({ question_id: 'Q03' }))).toEqual({ missing: 1, invalid: 0, cannotConfirmMatch: 1 })
  })

  it('counts Q issues only among the rows eligible under key, scope and pending filters', () => {
    const rows = [...mixed, { ...row('other.null', null), scope: '租户' }, { ...row('other.object', {}), decision_status: 'contract_fixed' }]
    expect(configurationQuestionFilterIssues(rows, filters({ config_key: 'meta.', registered_scope: '平台', pending_only: true, question_id: 'Q20' }))).toEqual({ missing: 2, invalid: 5, cannotConfirmMatch: 7 })
    expect(configurationQuestionFilterIssues(rows, filters({ registered_scope: '租户', question_id: 'Q20' }))).toEqual({ missing: 1, invalid: 0, cannotConfirmMatch: 1 })
  })

  it('shows non-text labels as explicit format errors without stringify/body leakage and uses only text scope options', () => {
    const body = { reference: 'UNIT_STRUCTURED_BODY', nested: ['private unit metadata'] }
    const rows = [{ ...base, scope: body, type: body }, { ...base, key: 'scope.array', scope: ['平台'] }, { ...base, key: 'scope.null', scope: null }, { ...base, key: 'scope.valid' }]
    expect(configurationScopeOptions(rows)).toEqual(['平台'])
    expect(filterConfigurations(rows, filters({ registered_scope: '平台' })).map(item => item.key)).toEqual(['scope.valid'])
    expect(filterConfigurations(rows, filters())).toEqual(rows)
    for (const value of [body, ['平台'], 3, false]) {
      const label = configurationMetadataLabel(value, '登记作用域')
      expect(label).toContain('格式异常'); expect(label).not.toContain('UNIT_STRUCTURED_BODY'); expect(label).not.toContain('private unit metadata')
    }
    expect(configurationMetadataLabel(null, '类型')).toContain('未提供')
    expect(configurationMetadataLabel('登记范围原文', '登记作用域')).toBe('登记范围原文')
  })

  it('keeps the comparison values and raw scope intact while the projected scope label is safely abnormal', () => {
    const scope = { body: 'UNIT_STRUCTURED_BODY' }, source = { ...base, scope, registered_value: false, draft_value: 0 }
    const comparison = configurationComparison(source, { kind: 'draft' })
    expect(comparison.registeredScope).toContain('格式异常'); expect(comparison.registeredScope).not.toContain('UNIT_STRUCTURED_BODY')
    expect(comparison).toMatchObject({ registeredValue: false, proposedValue: 0, effectiveValue: null, decisionStatus: 'pending' })
    expect(source.scope).toBe(scope); expect(source.registered_value).toBe(false); expect(source.draft_value).toBe(0)
  })
})
