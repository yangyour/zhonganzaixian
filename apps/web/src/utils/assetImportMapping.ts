import type { AssetImportFormat, AssetImportMappingRequest, AssetImportParseSelection, AssetImportSourceColumn, AssetImportTarget } from '../api/assetImportTypes'

/** Labels and NOTE's fixed source requirement are the existing contract Column enum, not a guessed dictionary. */
export const assetImportTargets: readonly { code: AssetImportTarget; label: string; alwaysMapped: boolean }[] = [
  { code: 'NAME', label: '设备名称', alwaysMapped: false }, { code: 'TYPE', label: '设备类型', alwaysMapped: false },
  { code: 'ADDRESS', label: 'IP或域名', alwaysMapped: false }, { code: 'BRAND', label: '品牌', alwaysMapped: false },
  { code: 'MODEL', label: '型号', alwaysMapped: false }, { code: 'SERIAL_NUMBER', label: '序列号', alwaysMapped: false },
  { code: 'SITE', label: '站点', alwaysMapped: false }, { code: 'LOCATION', label: '位置', alwaysMapped: false },
  { code: 'OWNER', label: '负责人', alwaysMapped: false }, { code: 'BUSINESS', label: '所属业务', alwaysMapped: false },
  { code: 'PURCHASE_DATE', label: '采购日期', alwaysMapped: false }, { code: 'WARRANTY_EXPIRY', label: '维保到期日', alwaysMapped: false },
  { code: 'OVERDUE_SERVICE_DATE', label: '超期服役日期', alwaysMapped: false }, { code: 'COLLECTION_METHOD', label: '采集方式', alwaysMapped: false },
  { code: 'NOTE', label: '备注', alwaysMapped: true },
]
export type AssetImportMappingChoice = number | null | 'UNDECIDED'
export interface AssetImportMappingDraft { targets: Record<AssetImportTarget, AssetImportMappingChoice>; ignored: number[] }
export class AssetImportInputError extends Error {
  constructor(public fields: Record<string, string>) { super('请明确选择导入来源的技术参数与列映射'); this.name = 'AssetImportInputError' }
}
const integer = (value: unknown, minimum: number, maximum: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
export function buildAssetImportParseSelection(format: AssetImportFormat, values: { encoding: string | null; delimiter: string | null; sheetIndex: number | null; headerRow: number | null }, inventory = false): AssetImportParseSelection {
  const fields: Record<string, string> = {}
  if (format === 'XLSX' && inventory) return { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null }
  if (!integer(values.headerRow, 1, 20_000)) fields.headerRow = '请明确选择1起的表头行（技术范围1–20,000）'
  if (format === 'CSV') {
    if (!['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030'].includes(values.encoding || '')) fields.encoding = '请选择CSV编码，不默认猜测编码'
    if (![',', ';', '\t'].includes(values.delimiter || '')) fields.delimiter = '请选择逗号、分号或TAB分隔符'
    if (values.sheetIndex !== null) fields.sheetIndex = 'CSV没有可选择的工作表'
  } else if (format === 'XLSX') {
    if (!integer(values.sheetIndex, 0, 31)) fields.sheetIndex = '请明确选择工作表'
    if (values.encoding !== null || values.delimiter !== null) fields.encoding = 'XLSX不接受CSV编码或分隔符参数'
  } else fields.format = '请选择CSV或XLSX格式'
  if (Object.keys(fields).length) throw new AssetImportInputError(fields)
  return { declared_format: format, encoding: values.encoding as AssetImportParseSelection['encoding'], delimiter: values.delimiter as AssetImportParseSelection['delimiter'], sheet_index: values.sheetIndex, header_row: values.headerRow }
}
export function assetImportHeaderSuggestions(columns: readonly AssetImportSourceColumn[]) {
  return assetImportTargets.map(target => ({ ...target, candidates: columns.filter(column => column.header === target.label).map(column => column.index) }))
}
export function createAssetImportMappingDraft(columns: readonly AssetImportSourceColumn[]): AssetImportMappingDraft {
  const targets = Object.fromEntries(assetImportHeaderSuggestions(columns).map(target => [target.code, target.candidates.length === 1 ? target.candidates[0]! : 'UNDECIDED'])) as Record<AssetImportTarget, AssetImportMappingChoice>
  return { targets, ignored: [] }
}
export function assetImportUnaddressedColumns(columns: readonly AssetImportSourceColumn[], draft: AssetImportMappingDraft) {
  const used = new Set(Object.values(draft.targets).filter((value): value is number => typeof value === 'number'))
  return columns.filter(column => !used.has(column.index) && !draft.ignored.includes(column.index))
}
export function buildAssetImportMappingRequest(expectedVersion: number, parseHash: string, columns: readonly AssetImportSourceColumn[], draft: AssetImportMappingDraft): AssetImportMappingRequest {
  const fields: Record<string, string> = {}, available = new Set(columns.map(column => column.index))
  if (!integer(expectedVersion, 1, Number.MAX_SAFE_INTEGER)) fields.version = '请回读当前来源版本'
  if (!/^[0-9a-f]{64}$/.test(parseHash)) fields.parseHash = '请回读已保存的解析结果hash'
  if (!columns.length || available.size !== columns.length || columns.some(column => !integer(column.index, 0, 63))) fields.columns = '来源列位置异常，请重新查询解析结果'
  if (Object.keys(draft.targets).length !== assetImportTargets.length || assetImportTargets.some(target => !Object.hasOwn(draft.targets, target.code))) fields.targets = '请明确列出全部15个目标字段'
  const used = new Set<number>(), targets = {} as Record<AssetImportTarget, number | null>
  for (const target of assetImportTargets) {
    const choice = draft.targets[target.code]
    if (choice === 'UNDECIDED' || choice === undefined) { fields[target.code] = '请明确选择源列或未映射'; continue }
    if (choice === null) { if (target.alwaysMapped) fields[target.code] = '备注是固定要求，必须选择源列'; targets[target.code] = null; continue }
    if (!integer(choice, 0, 63) || !available.has(choice)) { fields[target.code] = '所选源列不存在'; continue }
    if (used.has(choice)) fields[target.code] = '同一源列不能映射到多个目标字段'
    used.add(choice); targets[target.code] = choice
  }
  const ignored = new Set(draft.ignored)
  if (ignored.size !== draft.ignored.length || draft.ignored.some(index => !integer(index, 0, 63) || !available.has(index) || used.has(index))) fields.ignored = '忽略列须明确且不能同时用于映射'
  if (columns.some(column => !used.has(column.index) && !ignored.has(column.index))) fields.unaddressed = '还有源列未映射或未明确忽略，请逐项确认'
  if (Object.keys(fields).length) throw new AssetImportInputError(fields)
  return { expected_version: expectedVersion, source_parse_hash: parseHash, target_source_columns: targets, ignored_source_columns: [...draft.ignored].sort((left, right) => left - right) }
}
