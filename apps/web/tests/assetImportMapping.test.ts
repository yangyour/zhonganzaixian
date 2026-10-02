import { describe, expect, it } from 'vitest'
import type { AssetImportSourceColumn } from '../src/api/assetImportTypes'
import { assetImportTargets, AssetImportInputError, buildAssetImportMappingRequest, buildAssetImportParseSelection, createAssetImportMappingDraft } from '../src/utils/assetImportMapping'

const hash = 'a'.repeat(64)
const columns: AssetImportSourceColumn[] = assetImportTargets.map((target, index) => ({ index, header: target.label, target_suggestions: [target.code] }))
describe('explicit asset source selection and contract mapping', () => {
  it('requires explicit CSV encoding, delimiter and one-based header; TAB is a real tab', () => {
    expect(() => buildAssetImportParseSelection('CSV', { encoding: null, delimiter: null, sheetIndex: null, headerRow: null })).toThrow(AssetImportInputError)
    expect(() => buildAssetImportParseSelection('CSV', { encoding: 'UTF-8', delimiter: '\\t', sheetIndex: null, headerRow: 1 })).toThrow(AssetImportInputError)
    expect(buildAssetImportParseSelection('CSV', { encoding: 'GB18030', delimiter: '\t', sheetIndex: null, headerRow: 1 })).toEqual({ declared_format: 'CSV', encoding: 'GB18030', delimiter: '\t', sheet_index: null, header_row: 1 })
    expect(() => buildAssetImportParseSelection('CSV', { encoding: 'UTF-8', delimiter: ',', sheetIndex: 0, headerRow: 1 })).toThrow(AssetImportInputError)
  })
  it('requests XLSX sheet inventory without guessing; actual selection requires both sheet and header', () => {
    const empty = { encoding: null, delimiter: null, sheetIndex: null, headerRow: null }
    expect(buildAssetImportParseSelection('XLSX', empty, true)).toEqual({ declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null })
    expect(() => buildAssetImportParseSelection('XLSX', empty)).toThrow(AssetImportInputError)
    expect(buildAssetImportParseSelection('XLSX', { ...empty, sheetIndex: 0, headerRow: 2 }).sheet_index).toBe(0)
    expect(() => buildAssetImportParseSelection('XLSX', { ...empty, sheetIndex: 32, headerRow: 1 })).toThrow(AssetImportInputError)
  })
  it('preserves duplicate header positions and leaves ambiguous suggestions undecided', () => {
    const duplicate = [...columns, { index: 15, header: '备注', target_suggestions: ['NOTE'] as const }]
    const draft = createAssetImportMappingDraft(duplicate.map(column => ({ ...column, target_suggestions: [...column.target_suggestions] })))
    expect(draft.targets.NOTE).toBe('UNDECIDED'); expect(draft.ignored).toEqual([])
    expect(() => buildAssetImportMappingRequest(1, hash, duplicate.map(column => ({ ...column, target_suggestions: [...column.target_suggestions] })), draft)).toThrow(AssetImportInputError)
    draft.targets.NOTE = 15; draft.ignored = [14]
    const body = buildAssetImportMappingRequest(1, hash, duplicate.map(column => ({ ...column, target_suggestions: [...column.target_suggestions] })), draft)
    expect(body.target_source_columns.NOTE).toBe(15); expect(body.ignored_source_columns).toEqual([14])
  })
  it('requires all fifteen explicit targets, NOTE, unique source assignment and explicit ignored columns', () => {
    const draft = createAssetImportMappingDraft(columns)
    expect(Object.keys(buildAssetImportMappingRequest(3, hash, columns, draft).target_source_columns)).toHaveLength(15)
    draft.targets.NAME = null
    expect(() => buildAssetImportMappingRequest(3, hash, columns, draft)).toThrow(AssetImportInputError)
    draft.ignored = [0]
    expect(buildAssetImportMappingRequest(3, hash, columns, draft).target_source_columns.NAME).toBeNull()
    draft.targets.NOTE = null
    expect(() => buildAssetImportMappingRequest(3, hash, columns, draft)).toThrow(AssetImportInputError)
    draft.targets.NOTE = 13
    expect(() => buildAssetImportMappingRequest(3, hash, columns, draft)).toThrow(AssetImportInputError)
  })
  it('requires explicit position 0 and ignoring both duplicate/extra columns 15 and 16', () => {
    const extended: AssetImportSourceColumn[] = [...columns,
      { index: 15, header: '设备名称', target_suggestions: ['NAME'] },
      { index: 16, header: '附加说明', target_suggestions: [] },
    ]
    const draft = createAssetImportMappingDraft(extended)
    expect(draft.targets.NAME).toBe('UNDECIDED'); expect(draft.ignored).toEqual([])
    expect(() => buildAssetImportMappingRequest(4, hash, extended, draft)).toThrow(AssetImportInputError)
    draft.targets.NAME = 0
    for (const ignored of [[], [15], [16], [0, 15, 16]]) {
      draft.ignored = ignored
      expect(() => buildAssetImportMappingRequest(4, hash, extended, draft)).toThrow(AssetImportInputError)
    }
    draft.ignored = [16, 15]
    const request = buildAssetImportMappingRequest(4, hash, extended, draft)
    expect(request.target_source_columns.NAME).toBe(0)
    expect(request.target_source_columns.NOTE).toBe(14)
    expect(Object.keys(request.target_source_columns)).toHaveLength(15)
    expect(request.ignored_source_columns).toEqual([15, 16])
    expect(draft.ignored).toEqual([16, 15]) // Request normalization must not mutate the draft.
    expect(extended.map(column => column.index)).toEqual(Array.from({ length: 17 }, (_, index) => index))
  })
  it('does not suggest normalization, alternate dictionaries or missing header values', () => {
    const draft = createAssetImportMappingDraft([{ index: 0, header: ' 备注 ', target_suggestions: [] }, { index: 1, header: null, target_suggestions: [] }])
    expect(draft.targets.NOTE).toBe('UNDECIDED')
    expect(() => buildAssetImportMappingRequest(1, 'not-a-hash', columns, createAssetImportMappingDraft(columns))).toThrow(AssetImportInputError)
  })
})
