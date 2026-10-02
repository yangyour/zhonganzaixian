export type AssetImportFormat = 'CSV' | 'XLSX'
export type AssetImportEncoding = 'UTF-8' | 'UTF-16LE' | 'UTF-16BE' | 'GB18030'
export type AssetImportDelimiter = ',' | ';' | '\t'
export type AssetImportTarget = 'NAME' | 'TYPE' | 'ADDRESS' | 'BRAND' | 'MODEL' | 'SERIAL_NUMBER' | 'SITE' | 'LOCATION'
  | 'OWNER' | 'BUSINESS' | 'PURCHASE_DATE' | 'WARRANTY_EXPIRY' | 'OVERDUE_SERVICE_DATE' | 'COLLECTION_METHOD' | 'NOTE'
export interface AssetImportParseSelection {
  declared_format: AssetImportFormat; encoding: AssetImportEncoding | null; delimiter: AssetImportDelimiter | null
  sheet_index: number | null; header_row: number | null
}
export interface AssetImportParseRequest extends AssetImportParseSelection { expected_version: number }
export interface AssetImportAcceptance { source_id: string; operation_id: string; status: 'PENDING' }
export interface AssetImportPolicy {
  purpose: 'asset_import_source'; allowed_formats: AssetImportFormat[]; format_source: 'CONTRACT_M3_6'
  max_source_bytes: number; max_data_rows: number; max_source_columns: number; max_sheets: number; max_header_row: number; max_cell_chars: number
  resource_policy_version: string; resource_limit_kind: 'TECHNICAL_CONTAINMENT'; schema_version: string
  target_columns: { code: AssetImportTarget; label: string; contract_always_required: boolean }[]
  csv_encodings: AssetImportEncoding[]; csv_delimiters: AssetImportDelimiter[]; sheet_index_base: 0; column_index_base: 0; row_number_base: 1
  scan_status: 'UNSCANNED'; production_approval: 'UNVERIFIED'; retention_until: null; business_status: 'CONFIG_PENDING'; can_import: false; question_ids: ['Q20', 'Q31']
}
export interface AssetImportSourceColumn { index: number; header: string | null; target_suggestions: AssetImportTarget[] }
export type AssetImportCellKind = 'TEXT' | 'NUMERIC' | 'BOOLEAN' | 'ERROR' | 'ISO_DATE' | 'BLANK'
export interface AssetImportSourceCell {
  column_index: number; kind: AssetImportCellKind; lexical: string | null; display: string | null
  style_index: number | null; format_code: string | null
}
export interface AssetImportSourceRow {
  logical_record_number: number; source_row_number: number; physical_start_line: number | null; physical_end_line: number | null
  cells: AssetImportSourceCell[]
}
export interface AssetImportRawArtifact {
  state: 'PARSED' | 'SELECTION_REQUIRED'; detected_format: AssetImportFormat
  sheets: { index: number; name: string; hidden: boolean }[]; selected_sheet_index: number | null; header_row: number | null
  date_epoch: 'NOT_APPLICABLE' | 'WINDOWS_1900' | 'MAC_1904'; columns: AssetImportSourceColumn[]; rows: AssetImportSourceRow[]
  parser_version: string; resource_policy_version: string
}
export interface AssetImportMappingRequest {
  expected_version: number; source_parse_hash: string; target_source_columns: Record<AssetImportTarget, number | null>; ignored_source_columns: number[]
}
export type AssetImportStorageStatus = 'PENDING' | 'READY' | 'FAILED'
export type AssetImportParseStatus = 'NOT_REQUESTED' | 'PENDING' | 'PARSED' | 'SELECTION_REQUIRED' | 'FAILED'
export interface AssetImportMapping {
  id: string; tenant_id: string; source_id: string; artifact_id: string; version: number; source_parse_hash: string; preview_hash: string; schema_version: string; created_at: string
  target_source_columns: Record<AssetImportTarget, number | null>; ignored_source_columns: number[]
  mapped_raw_rows: { logical_record_number: number; source_row_number: number; physical_start_line: number | null; physical_end_line: number | null; cells: Record<AssetImportTarget, AssetImportSourceCell | null> }[]; precheck_status: 'NOT_RUN'
}
export interface AssetImportParsedArtifact extends AssetImportRawArtifact {
  id: string; tenant_id: string; source_id: string; operation_id: string; parse_hash: string; source_sha256: string; schema_version: string; selection: AssetImportParseSelection; created_at: string
}
export interface AssetImportSource {
  id: string; tenant_id: string; created_by: string; purpose: 'asset_import_source'; original_name: string; declared_format: AssetImportFormat
  size_bytes: number; sha256: string; storage_status: AssetImportStorageStatus; parse_status: AssetImportParseStatus
  store_operation_id: string; parse_operation_id: string | null; version: number; created_at: string; updated_at: string
  latest_artifact_id: string | null; latest_mapping_id: string | null; parsed_artifact: AssetImportParsedArtifact | null
  mapping: AssetImportMapping | null; retention_until: null; scan_status: 'UNSCANNED'; production_approval: 'UNVERIFIED'
  business_status: 'CONFIG_PENDING'; can_import: false; effective_rule_versions: null; quota_reservation: null; question_ids: ['Q20', 'Q31']
}
export type AssetImportOperationKind = 'STORE_RAW' | 'PARSE'
export type AssetImportOperationStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type AssetImportOperationStep = 'QUEUED' | 'STORING' | 'PARSING' | 'RETRY_WAIT' | 'STORED' | 'PARSED' | 'SELECTION_REQUIRED' | 'AUTHORIZATION_REVOKED' | 'STORAGE_CONFLICT' | 'PARSE_INVALID'
export interface AssetImportOperation {
  id: string; tenant_id: string; source_id: string; created_by: string; kind: AssetImportOperationKind; status: AssetImportOperationStatus
  step: AssetImportOperationStep; error: { code: string; message: string; retryable: boolean; question_ids: string[] } | null; result_artifact_id: string | null
  result_ref: string; created_at: string; updated_at: string
}
