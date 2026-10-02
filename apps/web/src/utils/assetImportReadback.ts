import { ApiError } from '../api/client'
import type { AssetImportOperation, AssetImportOperationKind, AssetImportSource } from '../api/assetImportTypes'
import { assetImportTargets, buildAssetImportMappingRequest } from './assetImportMapping'

export const importUuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const hash = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const integer = (value: unknown, min: number, max: number) => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const fail = (): never => { throw new ApiError(200, 'INVALID_IMPORT_READBACK', '导入来源回读元数据不一致；保留原请求，不能判定成功', '—') }
export interface ImportReadContext { tenantId: string; actorId: string }

export function validateAssetImportSource(source: AssetImportSource, context: ImportReadContext, sourceId: string, original?: { name: string; size: number; format: string; sha256?: string }, previous?: AssetImportSource | null) {
  if (!source || !importUuid(source.id) || source.id !== sourceId || source.tenant_id !== context.tenantId || source.created_by !== context.actorId || source.purpose !== 'asset_import_source'
    || !hash(source.sha256) || !integer(source.version, 1, Number.MAX_SAFE_INTEGER) || !integer(source.size_bytes, 1, 20 * 1024 * 1024) || !['CSV', 'XLSX'].includes(source.declared_format)
    || !['PENDING', 'READY', 'FAILED'].includes(source.storage_status) || !['NOT_REQUESTED', 'PENDING', 'PARSED', 'SELECTION_REQUIRED', 'FAILED'].includes(source.parse_status)
    || !importUuid(source.store_operation_id) || source.parse_operation_id !== null && !importUuid(source.parse_operation_id)
    || source.business_status !== 'CONFIG_PENDING' || source.can_import !== false || source.effective_rule_versions !== null || source.quota_reservation !== null
    || source.retention_until !== null || source.scan_status !== 'UNSCANNED' || source.production_approval !== 'UNVERIFIED'
    || !Array.isArray(source.question_ids) || source.question_ids.length !== 2 || !source.question_ids.includes('Q20') || !source.question_ids.includes('Q31')) fail()
  if (original && (source.original_name !== original.name || source.size_bytes !== original.size || source.declared_format !== original.format || original.sha256 && source.sha256 !== original.sha256)) fail()
  if (previous && (source.version < previous.version || source.sha256 !== previous.sha256 || source.store_operation_id !== previous.store_operation_id)) fail()
  const artifact = source.parsed_artifact
  if (artifact) {
    if (!importUuid(artifact.id) || artifact.id !== source.latest_artifact_id || artifact.tenant_id !== source.tenant_id || artifact.source_id !== source.id || !importUuid(artifact.operation_id)
      || !hash(artifact.parse_hash) || artifact.source_sha256 !== source.sha256 || artifact.detected_format !== source.declared_format || artifact.selection?.declared_format !== source.declared_format
      || !['PARSED', 'SELECTION_REQUIRED'].includes(artifact.state) || !Array.isArray(artifact.sheets) || !artifact.sheets.length || artifact.sheets.length > 32
      || !Array.isArray(artifact.columns) || artifact.columns.length > 64 || !Array.isArray(artifact.rows) || artifact.rows.length > 500
      || typeof artifact.parser_version !== 'string' || !artifact.parser_version || typeof artifact.resource_policy_version !== 'string' || !artifact.resource_policy_version || typeof artifact.schema_version !== 'string' || !artifact.schema_version) fail()
    if (source.declared_format === 'CSV' ? artifact.date_epoch !== 'NOT_APPLICABLE' : !['WINDOWS_1900', 'MAC_1904'].includes(artifact.date_epoch)) fail()
    const sheets = new Set<number>(), columns = new Set<number>(), rows = new Set<number>()
    for (const sheet of artifact.sheets) {
      if (!integer(sheet.index, 0, 31) || sheets.has(sheet.index) || typeof sheet.name !== 'string' || typeof sheet.hidden !== 'boolean') fail()
      sheets.add(sheet.index)
    }
    for (const column of artifact.columns) {
      if (!integer(column.index, 0, 63) || columns.has(column.index) || column.header !== null && typeof column.header !== 'string' || !Array.isArray(column.target_suggestions)
        || column.target_suggestions.some(target => !assetImportTargets.some(known => known.code === target))) fail()
      columns.add(column.index)
    }
    if (artifact.state === 'SELECTION_REQUIRED' && (artifact.columns.length || artifact.rows.length)) fail()
    if (artifact.state === 'PARSED' && (!columns.size || !sheets.has(artifact.selected_sheet_index!) || !integer(artifact.header_row, 1, 20_000)
      || artifact.header_row !== artifact.selection.header_row || source.declared_format === 'XLSX' && artifact.selected_sheet_index !== artifact.selection.sheet_index)) fail()
    for (const row of artifact.rows) {
      if (!integer(row.logical_record_number, 1, Number.MAX_SAFE_INTEGER) || rows.has(row.logical_record_number) || !integer(row.source_row_number, 1, 20_000) || !Array.isArray(row.cells)) fail()
      rows.add(row.logical_record_number); const cells = new Set<number>()
      if (row.physical_start_line !== null && !integer(row.physical_start_line, 1, Number.MAX_SAFE_INTEGER) || row.physical_end_line !== null && (!integer(row.physical_end_line, 1, Number.MAX_SAFE_INTEGER) || row.physical_start_line !== null && row.physical_end_line < row.physical_start_line)) fail()
      for (const cell of row.cells) {
        if (!columns.has(cell.column_index) || cells.has(cell.column_index) || !['TEXT', 'NUMERIC', 'BOOLEAN', 'ERROR', 'ISO_DATE', 'BLANK'].includes(cell.kind)
          || cell.lexical !== null && typeof cell.lexical !== 'string' || cell.display !== null && typeof cell.display !== 'string'
          || cell.style_index !== null && !integer(cell.style_index, 0, Number.MAX_SAFE_INTEGER) || cell.format_code !== null && typeof cell.format_code !== 'string') fail()
        cells.add(cell.column_index)
      }
    }
    if (['PARSED', 'SELECTION_REQUIRED'].includes(source.parse_status) && (artifact.state !== source.parse_status || artifact.operation_id !== source.parse_operation_id)) fail()
  } else if (source.latest_artifact_id !== null || ['PARSED', 'SELECTION_REQUIRED'].includes(source.parse_status)) fail()
  const mapping = source.mapping
  if (mapping) {
    if (!artifact || artifact.state !== 'PARSED' || !importUuid(mapping.id) || mapping.id !== source.latest_mapping_id || mapping.tenant_id !== source.tenant_id || mapping.source_id !== source.id
      || mapping.artifact_id !== artifact.id || mapping.source_parse_hash !== artifact.parse_hash || !hash(mapping.preview_hash) || mapping.schema_version !== artifact.schema_version
      || !integer(mapping.version, 1, Number.MAX_SAFE_INTEGER) || mapping.precheck_status !== 'NOT_RUN' || !Array.isArray(mapping.mapped_raw_rows) || mapping.mapped_raw_rows.length !== artifact.rows.length) fail()
    try { buildAssetImportMappingRequest(source.version, artifact!.parse_hash, artifact!.columns, { targets: mapping.target_source_columns, ignored: mapping.ignored_source_columns }) } catch { fail() }
    const rawRows = new Map(artifact!.rows.map(row => [row.logical_record_number, row])), mappedRows = new Set<number>()
    for (const row of mapping.mapped_raw_rows) {
      const raw = rawRows.get(row.logical_record_number)
      if (!raw || mappedRows.has(row.logical_record_number) || row.source_row_number !== raw.source_row_number || row.physical_start_line !== raw.physical_start_line || row.physical_end_line !== raw.physical_end_line
        || !row.cells || Object.keys(row.cells).length !== 15) fail()
      mappedRows.add(row.logical_record_number)
      for (const target of assetImportTargets) {
        const index = mapping.target_source_columns[target.code], expectedCell = index === null ? null : raw!.cells.find(cell => cell.column_index === index) || null
        const cell = row.cells[target.code]
        if (expectedCell === null ? cell !== null : !cell || (['column_index', 'kind', 'lexical', 'display', 'style_index', 'format_code'] as const).some(field => cell[field] !== expectedCell[field])) fail()
      }
    }
  } else if (source.latest_mapping_id !== null) fail()
  return source
}

export function validateAssetImportOperation(operation: AssetImportOperation, context: ImportReadContext, id: string, sourceId: string, kind: AssetImportOperationKind) {
  if (!operation || operation.id !== id || !importUuid(id) || operation.source_id !== sourceId || operation.tenant_id !== context.tenantId || operation.created_by !== context.actorId || operation.kind !== kind
    || !['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(operation.status) || operation.result_ref !== `/api/v1/assets/import-sources/${sourceId}`
    || operation.result_artifact_id !== null && !importUuid(operation.result_artifact_id)) fail()
  return operation
}
