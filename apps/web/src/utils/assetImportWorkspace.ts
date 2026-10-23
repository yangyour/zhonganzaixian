import { createRequestId, digestSha256 } from './browserCrypto'
import { computed, readonly, ref, shallowRef, watch } from 'vue'
import { api, ApiError, isCancelled, StaleRequestError } from '../api/client'
import type { AssetImportAcceptance, AssetImportFormat, AssetImportMappingRequest, AssetImportOperation, AssetImportParseRequest, AssetImportPolicy, AssetImportSource } from '../api/assetImportTypes'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { assetImportTargets, AssetImportInputError, buildAssetImportMappingRequest, buildAssetImportParseSelection, createAssetImportMappingDraft, type AssetImportMappingDraft } from './assetImportMapping'
import { importUuid, validateAssetImportOperation, validateAssetImportSource, type ImportReadContext } from './assetImportReadback'

type AttemptState = 'UNCERTAIN' | 'ACCEPTED' | 'COMPLETE' | 'REJECTED' | 'CONFLICT'
interface Attempt { key: string; state: AttemptState; sourceId: string | null; operationId: string | null; compared: boolean; receiptId?: string | null }
interface UploadAttempt extends Attempt { file: File; format: AssetImportFormat; sha256?: string }
interface ParseAttempt extends Attempt { sourceId: string; body: AssetImportParseRequest }
interface MappingAttempt extends Attempt { sourceId: string; body: AssetImportMappingRequest; committedArtifactId?: string; committedSourceVersion?: number; expectedArtifactId: string }
const unresolved = (attempt: Attempt | null) => attempt?.state === 'UNCERTAIN' || attempt?.state === 'ACCEPTED'
const failureState = (cause: unknown): AttemptState => cause instanceof ApiError && cause.status === 409 ? 'CONFLICT' : cause instanceof ApiError && [400,401,403,404,413,415,422].includes(cause.status) ? 'REJECTED' : 'UNCERTAIN'

/** Only browser state is held here. Every successful stage is confirmed from the owner-scoped API. */
export function createAssetImportWorkspace(client: Pick<typeof api, 'request'> = api, options: { intervalMs?: number; maxAttempts?: number } = {}) {
  const file = shallowRef<File | null>(null), format = ref<AssetImportFormat | null>(null), source = shallowRef<AssetImportSource | null>(null), operation = shallowRef<AssetImportOperation | null>(null)
  const policy = shallowRef<AssetImportPolicy | null>(null), policyLoading = ref(false), policyError = ref<unknown>(null)
  const error = ref<unknown>(null), uploadError = ref<unknown>(null), parseError = ref<unknown>(null), mappingError = ref<unknown>(null)
  const resumeError = ref<unknown>(null)
  const executionPending = ref(false)
  const busy = ref<'upload' | 'parse' | 'mapping' | 'read' | 'resume' | null>(null), selectionMessage = ref(''), fields = ref<Record<string, string>>({})
  const uploadAttempt = ref<UploadAttempt | null>(null), parseAttempt = ref<ParseAttempt | null>(null), mappingAttempt = ref<MappingAttempt | null>(null)
  const selection = ref<{ encoding: string | null; delimiter: string | null; sheetIndex: number | null; headerRow: number | null }>({ encoding: null, delimiter: null, sheetIndex: null, headerRow: null })
  const mappingDraft = ref<AssetImportMappingDraft | null>(null)
  const disposed = ref(false)
  let controller: AbortController | undefined, policyController: AbortController | undefined, epoch = 0
  const permitted = computed(() => !disposed.value && !switchingTenant.value && session.value?.mode === 'remote_test' && can('assets:write') && ['padmin', 'msp', 'tadmin'].includes(session.value?.user.role || ''))
  const hasUnresolved = computed(() => Boolean(busy.value || executionPending.value || unresolved(uploadAttempt.value) || unresolved(parseAttempt.value) || unresolved(mappingAttempt.value)))
  const hasConflict = computed(() => parseAttempt.value?.state === 'CONFLICT' || mappingAttempt.value?.state === 'CONFLICT')
  const canReset = computed(() => !hasUnresolved.value)
  const artifact = computed(() => source.value?.parsed_artifact || null)
  const isParsed = computed(() => source.value?.parse_status === 'PARSED' && artifact.value?.state === 'PARSED')
  const selectionLocked = computed(() => Boolean(busy.value || executionPending.value || hasConflict.value || unresolved(parseAttempt.value) || source.value?.parse_status === 'PENDING' || mappingAttempt.value?.state === 'ACCEPTED' || source.value?.mapping))
  const mappingLocked = computed(() => Boolean(busy.value || executionPending.value || hasConflict.value || unresolved(mappingAttempt.value) || source.value?.mapping))
  const context = (): ImportReadContext => ({ tenantId: session.value?.tenant.id || '', actorId: session.value?.user.id || '' })
  const original = () => file.value && format.value ? { name: file.value.name, size: file.value.size, format: format.value, sha256: uploadAttempt.value?.sha256 } : undefined
  const alive = (expected: number) => { if (disposed.value || expected !== epoch || controller?.signal.aborted) throw new StaleRequestError() }
  function begin(kind: NonNullable<typeof busy.value>) { controller?.abort(); controller = new AbortController(); busy.value = kind; error.value = null; return { expected: epoch, signal: controller.signal, context: context() } }
  function clear() {
    executionPending.value = false
    controller?.abort(); policyController?.abort(); ++epoch; busy.value = null; policyLoading.value = false
    file.value = null; format.value = null; source.value = null; operation.value = null; mappingDraft.value = null; policy.value = null
    uploadAttempt.value = null; parseAttempt.value = null; mappingAttempt.value = null; error.value = null; uploadError.value = null; parseError.value = null; mappingError.value = null; policyError.value = null; resumeError.value = null
    selectionMessage.value = ''; fields.value = {}; selection.value = { encoding: null, delimiter: null, sheetIndex: null, headerRow: null }
  }
  function reset() { if (disposed.value || !permitted.value || !canReset.value) return false; const savedPolicy = policy.value; clear(); policy.value = savedPolicy; return true }
  async function loadPolicy() {
    if (!permitted.value || policyLoading.value || busy.value) return
    const expected = epoch, request = new AbortController(); policyController?.abort(); policyController = request; policyLoading.value = true; policyError.value = null; policy.value = null
    try {
      const result = await client.request<AssetImportPolicy>('/assets/import-policy', { signal: request.signal })
      if (expected !== epoch || request.signal.aborted) return
      if (!result || result.purpose !== 'asset_import_source' || result.format_source !== 'CONTRACT_M3_6' || result.resource_limit_kind !== 'TECHNICAL_CONTAINMENT'
        || result.max_source_bytes !== 20 * 1024 * 1024 || result.max_data_rows !== 500 || result.max_source_columns !== 64 || result.max_sheets !== 32 || result.max_header_row !== 20_000 || result.max_cell_chars !== 4096
        || !Array.isArray(result.allowed_formats) || result.allowed_formats.length !== 2 || !result.allowed_formats.includes('CSV') || !result.allowed_formats.includes('XLSX')
        || !Array.isArray(result.csv_encodings) || result.csv_encodings.length !== 4 || !['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030'].every(value => result.csv_encodings.includes(value as typeof result.csv_encodings[number]))
        || !Array.isArray(result.csv_delimiters) || result.csv_delimiters.length !== 3 || ![',', ';', '\t'].every(value => result.csv_delimiters.includes(value as typeof result.csv_delimiters[number]))
        || result.sheet_index_base !== 0 || result.column_index_base !== 0 || result.row_number_base !== 1 || !result.schema_version || !result.resource_policy_version
        || !Array.isArray(result.target_columns) || result.target_columns.length !== 15 || !assetImportTargets.every(target => result.target_columns.some(column => column.code === target.code && column.label === target.label && column.contract_always_required === target.alwaysMapped))
        || result.business_status !== 'CONFIG_PENDING' || result.can_import !== false || result.retention_until !== null || result.scan_status !== 'UNSCANNED' || result.production_approval !== 'UNVERIFIED'
        || !Array.isArray(result.question_ids) || result.question_ids.length !== 2 || !result.question_ids.includes('Q20') || !result.question_ids.includes('Q31')) throw new ApiError(200, 'INVALID_IMPORT_POLICY', '导入来源技术政策回读异常', '—')
      policy.value = result
    } catch (cause) { if (!isCancelled(cause) && expected === epoch) policyError.value = cause }
    finally { if (expected === epoch) policyLoading.value = false }
  }
  const canResume=computed(()=>permitted.value&&!!policy.value&&!busy.value&&!file.value&&!source.value&&!hasUnresolved.value)
  async function resume(id:string){
    if(!canResume.value)return false
    resumeError.value=null
    if(!importUuid(id)){resumeError.value=new ApiError(400,'VALIDATION_ERROR','请选择有效的来源记录','—');return false}
    const request=begin('resume')
    try{
      const result=await client.request<AssetImportSource>(`/assets/import-sources/${id}`,{signal:request.signal});alive(request.expected)
      acceptSource(result,id,request.context);format.value=result.declared_format
      const old=result.parsed_artifact?.selection
      if(old)selection.value={encoding:old.encoding,delimiter:old.delimiter,sheetIndex:old.sheet_index,headerRow:old.header_row}
      return true
    }catch(cause){if(request.expected===epoch&&!isCancelled(cause))resumeError.value=cause;return false}
    finally{if(request.expected===epoch)busy.value=null}
  }
  function cancelResume(){if(busy.value!=='resume')return;controller?.abort();++epoch;busy.value=null;resumeError.value=null}
  function chooseFile(selected: File) {
    if (busy.value || uploadAttempt.value || source.value || !permitted.value || !policy.value) return false
    const extension = /\.xlsx$/i.test(selected.name) ? 'XLSX' : /\.csv$/i.test(selected.name) ? 'CSV' : null
    if (!extension || !policy.value.allowed_formats.includes(extension) || selected.size < 1 || selected.size > policy.value.max_source_bytes || [...selected.name].length > 200 || /[\\/\u0000-\u001f\u007f]/.test(selected.name)) { selectionMessage.value = '请选择名称有效的非空CSV或XLSX，原文件技术上限为20MiB'; return false }
    file.value = selected; format.value = extension; selectionMessage.value = ''; resumeError.value = null; return true
  }
  function acceptSource(result: AssetImportSource, id: string, readContext: ImportReadContext) {
    validateAssetImportSource(result, readContext, id, original(), source.value)
    if (result.parsed_artifact && policy.value && (result.parsed_artifact.resource_policy_version !== policy.value.resource_policy_version || result.parsed_artifact.schema_version !== policy.value.schema_version)) throw new ApiError(200, 'INVALID_IMPORT_READBACK', '解析结果与技术政策版本不一致', '—')
    // A completed request is historical evidence, not necessarily the current operation.
    // Reconcile only the display after validating the source; never fetch or run a new request here.
    const displayed = operation.value
    if (displayed && (displayed.source_id !== result.id || displayed.tenant_id !== result.tenant_id || displayed.created_by !== result.created_by
      || displayed.id !== (displayed.kind === 'STORE_RAW' ? result.store_operation_id : result.parse_operation_id)
      || displayed.kind === 'PARSE' && displayed.result_artifact_id !== null && (displayed.result_artifact_id !== result.parsed_artifact?.id || displayed.id !== result.parsed_artifact?.operation_id))) operation.value = null
    const changed = result.parsed_artifact?.parse_hash !== source.value?.parsed_artifact?.parse_hash
    source.value = result
    if (result.parsed_artifact?.state === 'PARSED' && (changed || !mappingDraft.value)) mappingDraft.value = result.mapping
      ? { targets: { ...result.mapping.target_source_columns }, ignored: [...result.mapping.ignored_source_columns] }
      : createAssetImportMappingDraft(result.parsed_artifact.columns)
    return result
  }
  async function readSource(id: string, readContext: ImportReadContext, expected: number, signal: AbortSignal, check?: (result: AssetImportSource) => void) {
    const result = await client.request<AssetImportSource>(`/assets/import-sources/${id}`, { signal }); alive(expected)
    validateAssetImportSource(result, readContext, id, original(), source.value); check?.(result)
    return acceptSource(result, id, readContext)
  }
  function superseded(attempt: ParseAttempt | MappingAttempt, current: AssetImportSource, readContext: ImportReadContext) {
    // Current source has already passed owner/tenant/hash/version validation in readSource.
    acceptSource(current, attempt.sourceId, readContext)
    operation.value = null
    attempt.state = 'CONFLICT'; attempt.compared = false
    return new ApiError(409, 'IMPORT_REQUEST_SUPERSEDED', '原请求已结束，但当前来源已被后续解析或映射更新；原请求保留，请回读比较后手动准备新请求或新来源', '—')
  }
  async function readAttempt(attempt: UploadAttempt | ParseAttempt, kind: 'STORE_RAW' | 'PARSE', request: ReturnType<typeof begin>, poll: boolean) {
    if (!attempt.operationId || !attempt.sourceId) throw new ApiError(200, 'INVALID_IMPORT_ACCEPTANCE', '受理凭据缺失；请使用原请求重试', '—')
    for (let index = 0; index < (poll ? options.maxAttempts ?? 20 : 1); index++) {
      const result = await client.request<AssetImportOperation>(`/asset-import-operations/${attempt.operationId}`, { signal: request.signal }); alive(request.expected)
      validateAssetImportOperation(result, request.context, attempt.operationId, attempt.sourceId, kind)
      operation.value = result
      const current = await readSource(attempt.sourceId, request.context, request.expected, request.signal, current => {
        if (kind === 'STORE_RAW' ? current.store_operation_id !== result.id : current.parse_operation_id !== result.id) {
          if (kind === 'PARSE' && ['SUCCEEDED', 'FAILED'].includes(result.status) && importUuid(current.parse_operation_id) && current.version > (attempt as ParseAttempt).body.expected_version) {
            attempt.receiptId = result.result_artifact_id
            throw superseded(attempt as ParseAttempt, current, request.context)
          }
          throw new ApiError(200, 'INVALID_IMPORT_READBACK', '操作与来源版本不一致；保留原请求', '—')
        }
        if (kind === 'PARSE' && current.parsed_artifact && Object.entries((attempt as ParseAttempt).body).some(([key, value]) => key !== 'expected_version' && current.parsed_artifact!.selection[key as keyof typeof current.parsed_artifact.selection] !== value)) throw new ApiError(200, 'INVALID_IMPORT_READBACK', '解析选择与原请求不一致；保留原请求', '—')
        if (result.status === 'SUCCEEDED' && (kind === 'STORE_RAW' ? current.storage_status !== 'READY' : !['PARSED', 'SELECTION_REQUIRED'].includes(current.parse_status) || current.parsed_artifact?.operation_id !== result.id || result.result_artifact_id !== current.parsed_artifact?.id)) throw new ApiError(200, 'INVALID_IMPORT_READBACK', '完成操作与来源回读结果不一致；未判定成功', '—')
      })
      if (result.status === 'FAILED') {
        if (kind === 'STORE_RAW' ? current.storage_status !== 'FAILED' : current.parse_status !== 'FAILED') throw new ApiError(200, 'INVALID_IMPORT_READBACK', '失败操作与来源状态不一致', '—')
        attempt.state = 'COMPLETE'; throw new ApiError(422, result.error?.code || 'IMPORT_OPERATION_FAILED', result.error?.message || '原文件技术处理失败；未导入资产', '—', [{ question_ids: result.error?.question_ids || [] }])
      }
      if (result.status === 'SUCCEEDED') {
        if (kind === 'STORE_RAW' ? current.storage_status !== 'READY' : !['PARSED', 'SELECTION_REQUIRED'].includes(current.parse_status) || current.parsed_artifact?.operation_id !== result.id || result.result_artifact_id !== current.parsed_artifact?.id)
          throw new ApiError(200, 'INVALID_IMPORT_READBACK', '完成操作与来源回读结果不一致；未判定成功', '—')
        attempt.state = 'COMPLETE'; return
      }
      if (!poll || index + 1 >= (options.maxAttempts ?? 20)) return
      await new Promise<void>((resolve, reject) => {
        const done = () => { request.signal.removeEventListener('abort', cancel); resolve() }
        const timer = setTimeout(done, options.intervalMs ?? 1500)
        const cancel = () => { clearTimeout(timer); reject(new StaleRequestError()) }
        request.signal.addEventListener('abort', cancel, { once: true })
      })
      alive(request.expected)
    }
  }
  function accept(attempt: Attempt, result: AssetImportAcceptance) {
    if (!result || !importUuid(result.source_id) || !importUuid(result.operation_id) || result.status !== 'PENDING' || attempt.sourceId && attempt.sourceId !== result.source_id)
      throw new ApiError(200, 'INVALID_IMPORT_ACCEPTANCE', '来源受理返回异常；保留原输入和原请求标识', '—')
    attempt.sourceId = result.source_id; attempt.operationId = result.operation_id; attempt.state = 'ACCEPTED'
  }
  async function upload() {
    if (!permitted.value || !policy.value || busy.value || !file.value || !format.value) return
    if (uploadAttempt.value?.state === 'ACCEPTED') { await refresh(); return }
    if (source.value || uploadAttempt.value && uploadAttempt.value.state !== 'UNCERTAIN') return
    const retry = uploadAttempt.value?.state === 'UNCERTAIN'
    const attempt = uploadAttempt.value || { file: file.value, format: format.value, key: createRequestId(), state: 'UNCERTAIN' as const, sourceId: null, operationId: null, compared: false }
    uploadAttempt.value = attempt; const active = uploadAttempt.value; const request = begin('upload'); uploadError.value = null
    const body = new FormData(); body.append('file', attempt.file, attempt.file.name); body.append('purpose', 'asset_import_source'); body.append('format', attempt.format)
    try {
      if (!active.sha256) { const digest = await digestSha256( await active.file.arrayBuffer()); alive(request.expected); active.sha256 = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('') }
      const result = await client.request<AssetImportAcceptance>('/assets/import-sources', { method: 'POST', body, idempotencyKey: active.key, signal: request.signal }); alive(request.expected); accept(active, result); await readAttempt(active, 'STORE_RAW', request, true)
    }
    catch (cause) { if (!isCancelled(cause) && request.expected === epoch) { if (active.state === 'UNCERTAIN' && !retry) active.state = failureState(cause); uploadError.value = cause } }
    finally { if (request.expected === epoch) busy.value = null }
  }
  async function parse(inventory = false) {
    if (!permitted.value || !policy.value || busy.value || executionPending.value || hasConflict.value || !source.value || source.value.storage_status !== 'READY' || !format.value || source.value.mapping) return
    if (parseAttempt.value?.state === 'ACCEPTED') { await refresh(); return }
    if (parseAttempt.value?.state === 'CONFLICT' || source.value.parse_status === 'PENDING' && parseAttempt.value?.state !== 'UNCERTAIN') return
    const retry = parseAttempt.value?.state === 'UNCERTAIN'
    let attempt = retry ? parseAttempt.value : null
    if (!attempt) {
      try {
        const body = { ...buildAssetImportParseSelection(format.value, selection.value, inventory), expected_version: source.value.version }
        if (format.value === 'XLSX' && !inventory && !source.value.parsed_artifact?.sheets.some(sheet => sheet.index === body.sheet_index)) throw new AssetImportInputError({ sheetIndex: '请先读取工作表目录，再明确选择其中的工作表' })
        attempt = { sourceId: source.value.id, body, key: createRequestId(), state: 'UNCERTAIN', operationId: null, compared: false }; parseAttempt.value = attempt; fields.value = {}
      } catch (cause) { if (cause instanceof AssetImportInputError) fields.value = cause.fields; else parseError.value = cause; return }
    }
    const request = begin('parse'); parseError.value = null
    try { const result = await client.request<AssetImportAcceptance>(`/assets/import-sources/${attempt.sourceId}/parses`, { method: 'POST', body: attempt.body, idempotencyKey: attempt.key, signal: request.signal }); alive(request.expected); accept(attempt, result); await readAttempt(attempt, 'PARSE', request, true) }
    catch (cause) { if (!isCancelled(cause) && request.expected === epoch) { if (attempt.state === 'UNCERTAIN' && !retry) attempt.state = failureState(cause); parseError.value = cause } }
    finally { if (request.expected === epoch) busy.value = null }
  }
  function sameMapping(result: AssetImportSource, attempt: MappingAttempt) {
    const saved = result.mapping
    return Boolean(saved && saved.artifact_id === attempt.expectedArtifactId && (!attempt.receiptId || saved.id === attempt.receiptId) && (!attempt.committedArtifactId || saved.artifact_id === attempt.committedArtifactId)
      && saved.source_parse_hash === attempt.body.source_parse_hash && Object.keys(attempt.body.target_source_columns).every(key => saved.target_source_columns[key as keyof typeof saved.target_source_columns] === attempt.body.target_source_columns[key as keyof typeof saved.target_source_columns])
      && JSON.stringify([...saved.ignored_source_columns].sort((a, b) => a - b)) === JSON.stringify(attempt.body.ignored_source_columns))
  }
  async function readMapping(attempt: MappingAttempt, request: ReturnType<typeof begin>) {
    await readSource(attempt.sourceId, request.context, request.expected, request.signal, result => {
      if (!sameMapping(result, attempt)) {
        if (attempt.receiptId && attempt.committedArtifactId && attempt.committedSourceVersion !== undefined && result.version > attempt.committedSourceVersion
          && (result.parsed_artifact?.id !== attempt.committedArtifactId || result.mapping?.id !== attempt.receiptId)) throw superseded(attempt, result, request.context)
        throw new ApiError(200, 'INVALID_IMPORT_READBACK', '已受理映射尚未在来源回读中确认；保留原映射请求', '—')
      }
    })
    attempt.state = 'COMPLETE'
  }
  async function saveMapping() {
    if (!permitted.value || !policy.value || busy.value || executionPending.value || hasConflict.value || !source.value || !isParsed.value || !artifact.value || !mappingDraft.value) return
    if (mappingAttempt.value?.state === 'ACCEPTED') { await refresh(); return }
    if (mappingAttempt.value?.state === 'CONFLICT' || source.value.mapping && mappingAttempt.value?.state !== 'UNCERTAIN') return
    const retry = mappingAttempt.value?.state === 'UNCERTAIN'
    let attempt = retry ? mappingAttempt.value : null
    if (!attempt) {
      try { const body = buildAssetImportMappingRequest(source.value.version, artifact.value.parse_hash, artifact.value.columns, mappingDraft.value); attempt = { sourceId: source.value.id, expectedArtifactId: artifact.value.id, body, key: createRequestId(), state: 'UNCERTAIN', operationId: null, compared: false }; mappingAttempt.value = attempt; fields.value = {} }
      catch (cause) { if (cause instanceof AssetImportInputError) fields.value = cause.fields; else mappingError.value = cause; return }
    }
    const request = begin('mapping'); mappingError.value = null
    try {
      const result = await client.request<AssetImportSource>(`/assets/import-sources/${attempt.sourceId}/mappings`, { method: 'POST', body: attempt.body, idempotencyKey: attempt.key, signal: request.signal }); alive(request.expected)
      validateAssetImportSource(result, request.context, attempt.sourceId, original(), source.value)
      if (result.version !== attempt.body.expected_version + 1 || !sameMapping(result, attempt)) throw new ApiError(200, 'INVALID_IMPORT_READBACK', '映射响应与原请求不一致；保留原请求', '—')
      attempt.receiptId = result.mapping!.id; attempt.committedArtifactId = result.mapping!.artifact_id; attempt.committedSourceVersion = result.version
      attempt.state = 'ACCEPTED'; await readMapping(attempt, request)
    } catch (cause) { if (!isCancelled(cause) && request.expected === epoch) { if (attempt.state === 'UNCERTAIN' && !retry) attempt.state = failureState(cause); mappingError.value = cause } }
    finally { if (request.expected === epoch) busy.value = null }
  }
  async function refresh() {
    if (!permitted.value || busy.value) return
    const request = begin('read')
    try {
      if (mappingAttempt.value?.state === 'ACCEPTED') await readMapping(mappingAttempt.value, request)
      else if (parseAttempt.value?.state === 'ACCEPTED') await readAttempt(parseAttempt.value, 'PARSE', request, false)
      else if (uploadAttempt.value?.state === 'ACCEPTED') await readAttempt(uploadAttempt.value, 'STORE_RAW', request, false)
      else if (source.value) {
        await readSource(source.value.id, request.context, request.expected, request.signal)
        if (parseAttempt.value?.state === 'CONFLICT') parseAttempt.value.compared = true
        if (mappingAttempt.value?.state === 'CONFLICT') mappingAttempt.value.compared = true
      }
    } catch (cause) { if (!isCancelled(cause) && request.expected === epoch) error.value = cause }
    finally { if (request.expected === epoch) busy.value = null }
  }
  function reviseConflict(kind: 'parse' | 'mapping') {
    const attempt = kind === 'parse' ? parseAttempt : mappingAttempt
    if (!permitted.value || busy.value || attempt.value?.state !== 'CONFLICT' || !attempt.value.compared) return false
    attempt.value = null; fields.value = {}; return true
  }
  // A navigation refresh replaces the session object even when its authority is unchanged.
  // Keep original File/key/body state unless a scope or effective access value actually changes.
  const stop = watch([contextGeneration, () => session.value?.tenant.id, () => session.value?.user.id,
    () => session.value?.user.role, () => session.value?.permission_version, () => session.value?.account_version, permitted], clear, { flush: 'sync' })
  function dispose() { stop(); disposed.value = true; clear() }
  return { file: readonly(file), format: readonly(format), source: readonly(source), artifact: readonly(artifact), operation: readonly(operation), policy: readonly(policy), policyLoading: readonly(policyLoading), policyError, permitted, busy: readonly(busy), error, uploadError, parseError, mappingError, selectionMessage, fields, selection, mappingDraft,
    uploadAttempt: readonly(uploadAttempt), parseAttempt: readonly(parseAttempt), mappingAttempt: readonly(mappingAttempt), hasUnresolved, canReset, canResume, resumeError, resume, cancelResume, isParsed, selectionLocked, mappingLocked, chooseFile, upload, parse, saveMapping, refresh, reviseConflict, loadPolicy, reset, dispose,
    setExecutionPending: (value: boolean) => { executionPending.value = value } }
}
