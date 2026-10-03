import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../src/api/client'
import type { AssetImportMapping, AssetImportOperation, AssetImportParsedArtifact, AssetImportPolicy, AssetImportSource } from '../src/api/assetImportTypes'
import type { Session } from '../src/api/types'
import { session } from '../src/stores/session'
import { assetImportTargets } from '../src/utils/assetImportMapping'
import { validateAssetImportSource } from '../src/utils/assetImportReadback'
import { createAssetImportWorkspace } from '../src/utils/assetImportWorkspace'

const tenant = '10000000-0000-0000-0000-000000000000', actor = '20000000-0000-0000-0000-000000000000', sourceId = '30000000-0000-0000-0000-000000000000'
const storeId = '40000000-0000-0000-0000-000000000000', parseId = '50000000-0000-0000-0000-000000000000', artifactId = '60000000-0000-0000-0000-000000000000', mappingId = '70000000-0000-0000-0000-000000000000'
const byteHash = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', parseHash = 'a'.repeat(64), time = '2026-10-02T00:00:00Z'
const policy: AssetImportPolicy = { purpose: 'asset_import_source', allowed_formats: ['CSV', 'XLSX'], format_source: 'CONTRACT_M3_6', max_source_bytes: 20 * 1024 * 1024, max_data_rows: 500, max_source_columns: 64, max_sheets: 32, max_header_row: 20_000, max_cell_chars: 4096, resource_policy_version: 'asset-import-technical-v1', resource_limit_kind: 'TECHNICAL_CONTAINMENT', schema_version: 'contract-m3-6-standard15-v1', target_columns: assetImportTargets.map(target => ({ code: target.code, label: target.label, contract_always_required: target.alwaysMapped })), csv_encodings: ['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030'], csv_delimiters: [',', ';', '\t'], sheet_index_base: 0, column_index_base: 0, row_number_base: 1, scan_status: 'UNSCANNED', production_approval: 'UNVERIFIED', retention_until: null, business_status: 'CONFIG_PENDING', can_import: false, question_ids: ['Q20', 'Q31'] }
const makeSource = (patch: Partial<AssetImportSource> = {}): AssetImportSource => ({ id: sourceId, tenant_id: tenant, created_by: actor, purpose: 'asset_import_source', original_name: 'source.csv', declared_format: 'CSV', size_bytes: 3, sha256: byteHash, storage_status: 'READY', parse_status: 'NOT_REQUESTED', store_operation_id: storeId, parse_operation_id: null, latest_artifact_id: null, latest_mapping_id: null, version: 2, created_at: time, updated_at: time, parsed_artifact: null, mapping: null, retention_until: null, scan_status: 'UNSCANNED', production_approval: 'UNVERIFIED', business_status: 'CONFIG_PENDING', can_import: false, effective_rule_versions: null, quota_reservation: null, question_ids: ['Q20', 'Q31'], ...patch })
const makeArtifact = (patch: Partial<AssetImportParsedArtifact> = {}): AssetImportParsedArtifact => ({ id: artifactId, tenant_id: tenant, source_id: sourceId, operation_id: parseId, source_sha256: byteHash, parse_hash: parseHash, schema_version: policy.schema_version, created_at: time, selection: { declared_format: 'CSV', encoding: 'UTF-8', delimiter: ',', sheet_index: null, header_row: 1 }, state: 'PARSED', detected_format: 'CSV', sheets: [{ index: 0, name: 'CSV', hidden: false }], selected_sheet_index: 0, header_row: 1, date_epoch: 'NOT_APPLICABLE', columns: assetImportTargets.map((target, index) => ({ index, header: target.label, target_suggestions: [target.code] })), rows: [{ logical_record_number: 2, source_row_number: 2, physical_start_line: 2, physical_end_line: 2, cells: assetImportTargets.map((_target, index) => ({ column_index: index, kind: 'TEXT', lexical: index === 14 ? '原始备注' : 'raw', display: index === 14 ? '原始备注' : 'raw', style_index: null, format_code: null })) }], parser_version: 'unit-parser-v1', resource_policy_version: policy.resource_policy_version, ...patch })
const parsedSource = () => makeSource({ version: 4, parse_status: 'PARSED', parse_operation_id: parseId, latest_artifact_id: artifactId, parsed_artifact: makeArtifact() })
const makeMapping = (): AssetImportMapping => ({ id: mappingId, tenant_id: tenant, source_id: sourceId, artifact_id: artifactId, version: 1, source_parse_hash: parseHash, preview_hash: 'b'.repeat(64), schema_version: policy.schema_version, target_source_columns: Object.fromEntries(assetImportTargets.map((target, index) => [target.code, index])) as AssetImportMapping['target_source_columns'], ignored_source_columns: [], mapped_raw_rows: makeArtifact().rows.map(row => ({ ...row, cells: Object.fromEntries(assetImportTargets.map((target, index) => [target.code, row.cells[index]])) as AssetImportMapping['mapped_raw_rows'][number]['cells'] })), precheck_status: 'NOT_RUN', created_at: time })
const savedSource = () => ({ ...parsedSource(), version: 5, latest_mapping_id: mappingId, mapping: makeMapping() })
const makeOperation = (parse = false, patch: Partial<AssetImportOperation> = {}): AssetImportOperation => ({ id: parse ? parseId : storeId, tenant_id: tenant, source_id: sourceId, created_by: actor, kind: parse ? 'PARSE' : 'STORE_RAW', status: 'SUCCEEDED', step: parse ? 'PARSED' : 'STORED', error: null, result_artifact_id: parse ? artifactId : null, result_ref: `/api/v1/assets/import-sources/${sourceId}`, created_at: time, updated_at: time, ...patch })
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data, trace_id: 'import-browser-unit' }), { status })
const failure = (status = 503) => new Response(JSON.stringify({ error: { code: status === 409 ? 'VERSION_CONFLICT' : 'UPSTREAM_UNAVAILABLE', message: 'unit failure', details: [] }, trace_id: 'import-unit-error' }), { status })
const acceptance = (parse = false) => json({ source_id: sourceId, operation_id: parse ? parseId : storeId, status: 'PENDING' }, 202)
const cleanup: (() => void)[] = []
async function workflow(handler: (url: string, options?: RequestInit) => Promise<Response> | Response) {
  const transport = vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => url === '/api/v1/assets/import-policy' ? json(policy) : handler(String(url), options))
  const client = new ApiClient(transport); client.setCsrfToken('unit-csrf')
  const workspace = createAssetImportWorkspace(client, { intervalMs: 0, maxAttempts: 1 }); cleanup.push(workspace.dispose)
  await workspace.loadPolicy()
  return { workspace, transport }
}
async function stored(handler?: (url: string, options?: RequestInit) => Promise<Response> | Response) {
  const result = await workflow((url, options) => handler?.(url, options) ?? (options?.method === 'POST' ? acceptance() : url.includes('asset-import-operations') ? json(makeOperation()) : json(makeSource())))
  expect(result.workspace.chooseFile(new File(['abc'], 'source.csv', { type: 'text/csv' }))).toBe(true)
  await result.workspace.upload(); expect(result.workspace.source.value?.storage_status).toBe('READY')
  return result
}
beforeEach(() => { session.value = { user: { id: actor, role: 'tadmin', display_name: 'Browser unit actor' }, tenant: { id: tenant, name: 'Browser unit tenant' }, permission_version: 1, capabilities: ['assets:write'], allowed_tenants: [], csrf_token: 'unit-csrf', mode: 'remote_test', identity_source: 'unit-only' } satisfies Session })
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); session.value = null; vi.restoreAllMocks() })

// Isolated transport substitutes prove browser behavior only; they do not prove PostgreSQL/MinIO integration.
describe('durable import browser workspace', () => {
  it('keeps original File, multipart declaration, hash and key through uncertainty, then uses only GET after 202', async () => {
    let writes = 0, readable = false
    const { workspace, transport } = await workflow((url, options) => {
      if (options?.method === 'POST') { if (++writes === 1) throw new TypeError('unknown upload outcome'); return acceptance() }
      if (!readable) return failure()
      return url.includes('asset-import-operations') ? json(makeOperation()) : json(makeSource())
    })
    const original = new File(['abc'], 'source.csv', { type: 'text/csv' }); workspace.chooseFile(original)
    await workspace.upload(); const attempt = workspace.uploadAttempt.value!
    expect(attempt.state).toBe('UNCERTAIN'); expect(attempt.sha256).toBe(byteHash); expect(workspace.file.value).toBe(original)
    expect(workspace.reset()).toBe(false); expect(workspace.chooseFile(new File(['b'], 'other.csv'))).toBe(false)
    await workspace.upload(); expect(workspace.uploadAttempt.value?.state).toBe('ACCEPTED'); expect(workspace.source.value).toBeNull()
    await workspace.upload(); expect(writes).toBe(2)
    const requests = transport.mock.calls.filter(([, options]) => options?.method === 'POST')
    for (const [, options] of requests) {
      expect(options?.headers).toMatchObject({ 'Idempotency-Key': attempt.key, 'X-CSRF-Token': 'unit-csrf' })
      const form = options!.body as FormData
      expect([...form.keys()]).toEqual(['file', 'purpose', 'format']); expect(form.get('purpose')).toBe('asset_import_source'); expect(form.get('format')).toBe('CSV')
      expect(await (form.get('file') as File).arrayBuffer()).toEqual(await original.arrayBuffer())
    }
    readable = true; await workspace.refresh(); expect(workspace.uploadAttempt.value?.state).toBe('COMPLETE'); expect(workspace.source.value?.can_import).toBe(false); expect(writes).toBe(2)
  })
  it('rejects mismatched owner/source/hash operation metadata and keeps the accepted request unresolved', async () => {
    const { workspace } = await workflow((url, options) => options?.method === 'POST' ? acceptance() : url.includes('asset-import-operations') ? json(makeOperation()) : json(makeSource({ sha256: 'c'.repeat(64) })))
    workspace.chooseFile(new File(['abc'], 'source.csv')); await workspace.upload()
    expect(workspace.source.value).toBeNull(); expect(workspace.uploadAttempt.value?.state).toBe('ACCEPTED'); expect(workspace.hasUnresolved.value).toBe(true)
    expect(workspace.uploadError.value).toMatchObject({ code: 'INVALID_IMPORT_READBACK' })
  })
  it('does not call source storage complete while the operation and source are pending', async () => {
    let ready = false
    const { workspace } = await workflow((url, options) => options?.method === 'POST' ? acceptance() : url.includes('asset-import-operations') ? json(makeOperation(false, ready ? {} : { status: 'PENDING', step: 'QUEUED' })) : json(makeSource(ready ? {} : { storage_status: 'PENDING', version: 1 })))
    workspace.chooseFile(new File(['abc'], 'source.csv')); await workspace.upload()
    expect(workspace.source.value?.storage_status).toBe('PENDING'); expect(workspace.uploadAttempt.value?.state).toBe('ACCEPTED')
    ready = true; await workspace.refresh(); expect(workspace.uploadAttempt.value?.state).toBe('COMPLETE')
  })
  it('requires XLSX inventory then an explicit hidden sheet/header and preserves the workbook date epoch', async () => {
    let parses = 0
    const inventory = makeArtifact({ state: 'SELECTION_REQUIRED', detected_format: 'XLSX', selection: { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null }, sheets: [{ index: 0, name: 'Cover', hidden: false }, { index: 1, name: 'Raw hidden', hidden: true }], selected_sheet_index: null, header_row: null, date_epoch: 'MAC_1904', columns: [], rows: [] })
    const chosen = makeArtifact({ ...inventory, state: 'PARSED', selection: { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: 1, header_row: 2 }, selected_sheet_index: 1, header_row: 2, columns: makeArtifact().columns, rows: makeArtifact().rows })
    const { workspace, transport } = await workflow((url, options) => {
      if (url.endsWith('/parses')) { parses++; return acceptance(true) }
      if (options?.method === 'POST') return acceptance()
      if (url.includes('asset-import-operations')) return json(makeOperation(parses > 0, parses === 1 ? { step: 'SELECTION_REQUIRED' } : {}))
      return json(makeSource({ original_name: 'source.xlsx', declared_format: 'XLSX', ...(parses ? { version: parses === 1 ? 4 : 6, parse_operation_id: parseId, latest_artifact_id: artifactId, parse_status: parses === 1 ? 'SELECTION_REQUIRED' : 'PARSED', parsed_artifact: parses === 1 ? inventory : chosen } : {}) }))
    })
    workspace.chooseFile(new File(['abc'], 'source.xlsx')); await workspace.upload()
    workspace.selection.value.headerRow = 2; workspace.selection.value.sheetIndex = 1
    await workspace.parse(); expect(parses).toBe(0); expect(workspace.fields.value.sheetIndex).toContain('先读取')
    await workspace.parse(true); expect(workspace.source.value?.parse_status).toBe('SELECTION_REQUIRED'); expect(workspace.mappingDraft.value).toBeNull()
    const requests = () => transport.mock.calls.filter(([url]) => String(url).endsWith('/parses'))
    expect(JSON.parse(String(requests()[0]![1]?.body))).toEqual({ declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null, expected_version: 2 })
    await workspace.parse(); expect(workspace.isParsed.value).toBe(true); expect(workspace.artifact.value?.date_epoch).toBe('MAC_1904'); expect(workspace.artifact.value?.sheets[1]?.hidden).toBe(true)
    expect(JSON.parse(String(requests()[1]![1]?.body))).toEqual({ declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: 1, header_row: 2, expected_version: 4 })
  })
  it('uses explicit CSV parameters plus CAS version, preserving original parse body and key on network uncertainty', async () => {
    let parses = 0
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { if (++parses === 1) throw new TypeError('unknown parse outcome'); return acceptance(true) }
      if (options?.method === 'POST') return acceptance()
      return url.includes('asset-import-operations') ? json(makeOperation(parses > 1)) : json(parses > 1 ? parsedSource() : makeSource())
    })
    await workspace.parse(); expect(parses).toBe(0); expect(workspace.fields.value).toHaveProperty('encoding')
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse()
    const attempt = workspace.parseAttempt.value!; expect(attempt.state).toBe('UNCERTAIN'); expect(workspace.selectionLocked.value).toBe(true)
    workspace.selection.value.headerRow = 3; await workspace.parse()
    const requests = transport.mock.calls.filter(([url]) => String(url).endsWith('/parses'))
    expect(requests).toHaveLength(2)
    for (const [, options] of requests) { expect(options?.body).toBe(JSON.stringify(attempt.body)); expect(options?.headers).toMatchObject({ 'Idempotency-Key': attempt.key }) }
    expect(attempt.body.expected_version).toBe(2); expect(attempt.body.header_row).toBe(1); expect(workspace.isParsed.value).toBe(true)
    expect(workspace.mappingDraft.value?.targets.NOTE).toBe(14); expect(workspace.source.value?.business_status).toBe('CONFIG_PENDING')
  })
  it('saves mapping with the original body and key; accepted 200 plus failed GET only retries reads', async () => {
    let mappingWrites = 0, mappingReadable = false, stage = 'store'
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { stage = 'parsed'; return acceptance(true) }
      if (url.endsWith('/mappings')) { mappingWrites++; if (mappingWrites === 1) throw new TypeError('unknown mapping outcome'); stage = 'mapping'; return json(savedSource()) }
      if (options?.method === 'POST') return acceptance()
      if (url.includes('asset-import-operations')) return json(makeOperation(stage !== 'store'))
      return stage === 'mapping' ? mappingReadable ? json(savedSource()) : failure() : json(stage === 'parsed' ? parsedSource() : makeSource())
    })
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse(); await workspace.saveMapping()
    const attempt = workspace.mappingAttempt.value!; expect(attempt.state).toBe('UNCERTAIN'); expect(workspace.mappingLocked.value).toBe(true)
    workspace.mappingDraft.value!.targets.NAME = null; await workspace.saveMapping()
    expect(workspace.mappingAttempt.value?.state).toBe('ACCEPTED'); expect(workspace.source.value?.mapping).toBeNull()
    await workspace.saveMapping(); expect(mappingWrites).toBe(2)
    const requests = transport.mock.calls.filter(([url]) => String(url).endsWith('/mappings'))
    for (const [, options] of requests) { expect(options?.body).toBe(JSON.stringify(attempt.body)); expect(options?.headers).toMatchObject({ 'Idempotency-Key': attempt.key }) }
    mappingReadable = true; await workspace.refresh(); expect(workspace.mappingAttempt.value?.state).toBe('COMPLETE'); expect(workspace.source.value?.mapping?.precheck_status).toBe('NOT_RUN'); expect(workspace.source.value?.quota_reservation).toBeNull()
  })
  it('requires a successful fresh read and explicit comparison before a new CAS request', async () => {
    let parses = 0
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { parses++; return failure(409) }
      return options?.method === 'POST' ? acceptance() : url.includes('asset-import-operations') ? json(makeOperation()) : json(makeSource({ version: parses ? 3 : 2 }))
    })
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse()
    const key = workspace.parseAttempt.value!.key
    expect(workspace.reviseConflict('parse')).toBe(false); await workspace.parse(); expect(parses).toBe(1)
    await workspace.refresh(); expect(workspace.reviseConflict('parse')).toBe(true); await workspace.parse()
    const requests = transport.mock.calls.filter(([url]) => String(url).endsWith('/parses'))
    expect(JSON.parse(String(requests[1]![1]?.body)).expected_version).toBe(3); expect(requests[1]![1]?.headers).not.toMatchObject({ 'Idempotency-Key': key })
  })
  it.each(['SUCCEEDED', 'FAILED'] as const)('recovers an old %s parse superseded by a new parse through explicit comparison without treating it as current success', async status => {
    const nextParseId = '80000000-0000-0000-0000-000000000000'
    let requested = false, writes = 0
    const next = makeSource({ version: 5, parse_status: 'PENDING', parse_operation_id: nextParseId })
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { requested = true; writes++; return acceptance(true) }
      if (options?.method === 'POST') return acceptance()
      if (url.includes('asset-import-operations')) return json(url.endsWith(parseId) ? makeOperation(true, status === 'SUCCEEDED' ? {} : { status: 'FAILED', step: 'PARSE_INVALID', result_artifact_id: null, error: { code: 'IMPORT_SOURCE_INVALID', message: 'old parse failed', retryable: false, question_ids: [] } }) : makeOperation())
      return json(requested ? next : makeSource())
    })
    const original = workspace.file.value
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse()
    const attempt = workspace.parseAttempt.value!, body = JSON.stringify(attempt.body), key = attempt.key
    expect(attempt.state).toBe('CONFLICT'); expect(attempt.operationId).toBe(parseId); expect(attempt.receiptId).toBe(status === 'SUCCEEDED' ? artifactId : null)
    expect(workspace.parseError.value).toMatchObject({ code: 'IMPORT_REQUEST_SUPERSEDED' }); expect(workspace.source.value).toEqual(next)
    expect(workspace.operation.value).toBeNull(); expect(workspace.isParsed.value).toBe(false); expect(workspace.hasUnresolved.value).toBe(false); expect(workspace.canReset.value).toBe(true)
    expect(workspace.file.value).toBe(original); expect(workspace.reviseConflict('parse')).toBe(false)
    const beforeReads = transport.mock.calls.length
    await workspace.parse(); await workspace.refresh()
    expect(writes).toBe(1); expect(transport.mock.calls.slice(beforeReads).every(([url, options]) => options?.method !== 'POST' && !String(url).includes('asset-import-operations'))).toBe(true)
    expect(attempt.state).toBe('CONFLICT'); expect(attempt.compared).toBe(true); expect(attempt.key).toBe(key); expect(JSON.stringify(attempt.body)).toBe(body)
    expect(workspace.reviseConflict('parse')).toBe(true); await workspace.parse(); expect(writes).toBe(1) // New current parse remains pending; no implicit claim or request.
    expect(workspace.reset()).toBe(true); expect(workspace.source.value).toBeNull(); expect(workspace.chooseFile(new File(['different'], 'new.csv'))).toBe(true); expect(writes).toBe(1)
  })
  it('recovers a confirmed mapping superseded by a new parse while preserving its receipt and only issuing reads', async () => {
    const nextParseId = '80000000-0000-0000-0000-000000000000'
    const nextArtifactId = '90000000-0000-0000-0000-000000000000'
    let stage = 'stored', writes = 0, parseWrites = 0
    const next = makeSource({ version: 7, parse_status: 'PARSED', parse_operation_id: nextParseId, latest_artifact_id: nextArtifactId, parsed_artifact: makeArtifact({ id: nextArtifactId, operation_id: nextParseId, parse_hash: 'c'.repeat(64) }) })
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { stage = 'parsed'; parseWrites++; return acceptance(true) }
      if (url.endsWith('/mappings')) { writes++; stage = 'superseded'; return json(savedSource()) }
      if (options?.method === 'POST') return acceptance()
      if (url.includes('asset-import-operations')) return json(makeOperation(stage !== 'stored'))
      return json(stage === 'superseded' ? next : stage === 'parsed' ? parsedSource() : makeSource())
    })
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse(); await workspace.saveMapping()
    const attempt = workspace.mappingAttempt.value!, body = JSON.stringify(attempt.body), key = attempt.key
    expect(attempt.state).toBe('CONFLICT'); expect(attempt.receiptId).toBe(mappingId); expect(attempt.committedArtifactId).toBe(artifactId); expect(attempt.committedSourceVersion).toBe(5)
    expect(workspace.mappingError.value).toMatchObject({ code: 'IMPORT_REQUEST_SUPERSEDED' }); expect(workspace.source.value).toEqual(next); expect(workspace.source.value?.mapping).toBeNull()
    expect(workspace.operation.value).toBeNull(); expect(workspace.hasUnresolved.value).toBe(false); expect(workspace.canReset.value).toBe(true); expect(workspace.reviseConflict('mapping')).toBe(false)
    const beforeReads = transport.mock.calls.length
    expect(workspace.isParsed.value).toBe(true); expect(workspace.mappingLocked.value).toBe(true); expect(workspace.selectionLocked.value).toBe(true)
    await workspace.saveMapping(); await workspace.parse(); await workspace.refresh()
    expect(writes).toBe(1); expect(parseWrites).toBe(1); expect(transport.mock.calls.slice(beforeReads).every(([url, options]) => options?.method !== 'POST' && !String(url).includes('asset-import-operations'))).toBe(true)
    expect(attempt.state).toBe('CONFLICT'); expect(attempt.compared).toBe(true); expect(attempt.key).toBe(key); expect(JSON.stringify(attempt.body)).toBe(body)
    session.value = { ...session.value!, permission_version: 2 }
    expect(workspace.mappingAttempt.value).toBeNull(); expect(workspace.source.value).toBeNull(); expect(workspace.file.value).toBeNull(); expect(workspace.operation.value).toBeNull(); expect(workspace.mappingDraft.value).toBeNull(); expect(writes).toBe(1)
  })
  it.each(['pending parse', 'completed parse', 'different artifact'] as const)('clears the obsolete COMPLETE display on a read-only refresh: %s', async replacement => {
    const nextParseId = '80000000-0000-0000-0000-000000000000'
    const nextArtifactId = '90000000-0000-0000-0000-000000000000'
    let parsed = false, current = makeSource()
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { parsed = true; current = parsedSource(); return acceptance(true) }
      if (url.endsWith('/mappings')) { current = savedSource(); return json(current) }
      if (options?.method === 'POST') return acceptance()
      return json(url.includes('asset-import-operations') ? makeOperation(parsed) : current)
    })
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }
    await workspace.parse(); await workspace.saveMapping()
    expect(workspace.parseAttempt.value?.state).toBe('COMPLETE')
    expect(workspace.mappingAttempt.value?.state).toBe('COMPLETE')
    expect(workspace.operation.value?.status).toBe('SUCCEEDED')
    const history = JSON.stringify({ upload: workspace.uploadAttempt.value, parse: workspace.parseAttempt.value, mapping: workspace.mappingAttempt.value })
    const originalFile = workspace.file.value
    current = replacement === 'pending parse'
      ? makeSource({ version: 6, parse_status: 'PENDING', parse_operation_id: nextParseId })
      : makeSource({ version: 7, parse_status: 'PARSED', parse_operation_id: replacement === 'different artifact' ? parseId : nextParseId,
        latest_artifact_id: nextArtifactId, parsed_artifact: makeArtifact({ id: nextArtifactId, operation_id: replacement === 'different artifact' ? parseId : nextParseId, parse_hash: 'c'.repeat(64) }) })
    const start = transport.mock.calls.length
    await workspace.refresh(); await workspace.refresh()
    expect(workspace.error.value).toBeNull(); expect(workspace.source.value).toEqual(current)
    expect(workspace.operation.value).toBeNull()
    expect(JSON.stringify({ upload: workspace.uploadAttempt.value, parse: workspace.parseAttempt.value, mapping: workspace.mappingAttempt.value })).toBe(history)
    expect(workspace.mappingAttempt.value?.receiptId).toBe(mappingId)
    expect(workspace.file.value).toBe(originalFile)
    expect(transport.mock.calls.slice(start).map(([url, options]) => [url, options?.method || 'GET'])).toEqual([
      [`/api/v1/assets/import-sources/${sourceId}`, 'GET'], [`/api/v1/assets/import-sources/${sourceId}`, 'GET'],
    ])
    expect(workspace.source.value?.can_import).toBe(false)
    expect(workspace.source.value?.effective_rule_versions).toBeNull(); expect(workspace.source.value?.quota_reservation).toBeNull()
  })
  it.each(['STORE_RAW', 'PARSE'] as const)('retains a still-bound completed %s display during ordinary refresh', async kind => {
    let parsed = false
    const { workspace, transport } = await stored((url, options) => {
      if (url.endsWith('/parses')) { parsed = true; return acceptance(true) }
      if (options?.method === 'POST') return acceptance()
      return json(url.includes('asset-import-operations') ? makeOperation(parsed) : parsed ? parsedSource() : makeSource())
    })
    if (kind === 'PARSE') { workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse() }
    const displayed = workspace.operation.value, start = transport.mock.calls.length
    await workspace.refresh()
    expect(workspace.error.value).toBeNull(); expect(workspace.operation.value).toBe(displayed)
    expect(transport.mock.calls.slice(start)).toHaveLength(1)
    expect(String(transport.mock.calls[start]![0])).toBe(`/api/v1/assets/import-sources/${sourceId}`)
  })
  it.each(['owner', 'hash', 'version', 'policy'] as const)('does not clear the display or history before source validation succeeds: %s', async invalid => {
    let parsed = false, current = makeSource()
    const { workspace } = await stored((url, options) => {
      if (url.endsWith('/parses')) { parsed = true; current = parsedSource(); return acceptance(true) }
      if (options?.method === 'POST') return acceptance()
      return json(url.includes('asset-import-operations') ? makeOperation(parsed) : current)
    })
    workspace.selection.value = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }; await workspace.parse()
    const displayed = workspace.operation.value, previous = workspace.source.value, history = JSON.stringify(workspace.parseAttempt.value)
    const nextParseId = '80000000-0000-0000-0000-000000000000'
    current = invalid === 'policy'
      ? makeSource({ version: 6, parse_status: 'PARSED', parse_operation_id: nextParseId, latest_artifact_id: artifactId,
        parsed_artifact: makeArtifact({ operation_id: nextParseId, resource_policy_version: 'unrecognized-policy' }) })
      : makeSource({ version: invalid === 'version' ? 3 : 5, parse_status: 'PENDING', parse_operation_id: nextParseId,
        ...(invalid === 'owner' ? { created_by: sourceId } : invalid === 'hash' ? { sha256: 'c'.repeat(64) } : {}) })
    await workspace.refresh()
    expect(workspace.error.value).toMatchObject({ code: 'INVALID_IMPORT_READBACK' })
    expect(workspace.operation.value).toBe(displayed); expect(workspace.source.value).toBe(previous)
    expect(JSON.stringify(workspace.parseAttempt.value)).toBe(history)
  })
  it('clears and aborts in-flight requests on session change, rejecting any late response', async () => {
    let release: ((response: Response) => void) | undefined, requestSignal: AbortSignal | null | undefined
    const { workspace } = await workflow((_url, options) => { requestSignal = options?.signal; return new Promise<Response>(resolve => { release = resolve }) })
    workspace.chooseFile(new File(['abc'], 'source.csv')); const pending = workspace.upload()
    await vi.waitFor(() => expect(release).toBeDefined())
    session.value = { ...session.value!, permission_version: 2 }
    expect(requestSignal?.aborted).toBe(true); expect(workspace.file.value).toBeNull(); expect(workspace.uploadAttempt.value).toBeNull()
    release!(acceptance()); await pending; expect(workspace.source.value).toBeNull(); expect(workspace.busy.value).toBeNull()
  })
  it('does not allow a viewer or absent/invalid policy to upload a source', async () => {
    session.value = { ...session.value!, user: { ...session.value!.user, role: 'viewer' } }
    const { workspace, transport } = await workflow(() => acceptance())
    expect(workspace.chooseFile(new File(['abc'], 'source.csv'))).toBe(false); await workspace.upload(); expect(transport).not.toHaveBeenCalled()
  })
  it('rejects cross-source artifacts, epoch changes and mapping hashes while preserving raw cell semantics', () => {
    const valid = parsedSource(); expect(validateAssetImportSource(valid, { tenantId: tenant, actorId: actor }, sourceId)).toBe(valid)
    for (const artifact of [makeArtifact({ tenant_id: sourceId }), makeArtifact({ source_sha256: 'e'.repeat(64) }), makeArtifact({ date_epoch: 'WINDOWS_1900' })]) expect(() => validateAssetImportSource({ ...valid, parsed_artifact: artifact }, { tenantId: tenant, actorId: actor }, sourceId)).toThrow()
    expect(() => validateAssetImportSource({ ...savedSource(), mapping: { ...makeMapping(), source_parse_hash: 'f'.repeat(64) } }, { tenantId: tenant, actorId: actor }, sourceId)).toThrow()
    const mutated = savedSource(); mutated.mapping!.mapped_raw_rows[0]!.cells.NOTE!.lexical = 'changed raw value'
    expect(() => validateAssetImportSource(mutated, { tenantId: tenant, actorId: actor }, sourceId)).toThrow()
    expect(valid.parsed_artifact?.rows[0]?.cells[14]?.lexical).toBe('原始备注')
  })
})

it('keeps original upload uncertainty after denied retries and forbids reset', async () => { for(const status of [400,403,404,409,422]) { let writes=0; const {workspace}=await workflow(()=>{if(++writes===1)throw new TypeError('lost');return failure(status)});workspace.chooseFile(new File(['abc'],'source.csv'));await workspace.upload();const key=workspace.uploadAttempt.value!.key;await workspace.upload();expect(workspace.uploadAttempt.value?.state).toBe('UNCERTAIN');expect(workspace.uploadAttempt.value?.key).toBe(key);expect(workspace.hasUnresolved.value).toBe(true);expect(workspace.reset()).toBe(false) } })
it('keeps original parse uncertainty after denied retries and cannot revise conflict', async () => { for(const status of [400,403,404,409,422]) { let writes=0;const {workspace}=await stored((url,o)=>{if(url.endsWith('/parses')){if(++writes===1)throw new TypeError('lost');return failure(status)}return o?.method==='POST'?acceptance():url.includes('asset-import-operations')?json(makeOperation()):json(makeSource())});workspace.selection.value={encoding:'UTF-8',delimiter:',',sheetIndex:null,headerRow:1};await workspace.parse();const key=workspace.parseAttempt.value!.key;await workspace.parse();expect(workspace.parseAttempt.value?.state).toBe('UNCERTAIN');expect(workspace.parseAttempt.value?.key).toBe(key);expect(workspace.reviseConflict('parse')).toBe(false);expect(workspace.hasUnresolved.value).toBe(true) } })
it('keeps original mapping uncertainty after denied retries and no new parse or reset', async () => { for(const status of [400,403,404,409,422]) { let mappingWrites=0,parsed=false;const {workspace}=await stored((url,o)=>{if(url.endsWith('/parses')){parsed=true;return acceptance(true)}if(url.endsWith('/mappings')){if(++mappingWrites===1)throw new TypeError('lost');return failure(status)}if(parsed)return url.includes('asset-import-operations')?json(makeOperation(true)):json(parsedSource());return o?.method==='POST'?acceptance():url.includes('asset-import-operations')?json(makeOperation()):json(makeSource())});workspace.selection.value={encoding:'UTF-8',delimiter:',',sheetIndex:null,headerRow:1};await workspace.parse();await workspace.saveMapping();const key=workspace.mappingAttempt.value!.key;await workspace.saveMapping();expect(workspace.mappingAttempt.value?.state).toBe('UNCERTAIN');expect(workspace.mappingAttempt.value?.key).toBe(key);expect(workspace.reviseConflict('mapping')).toBe(false);expect(workspace.reset()).toBe(false) } })
