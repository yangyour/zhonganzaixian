import { digestSha256 } from './browserCrypto'
import { ApiError } from '../api/client'
import { utcInstantNanos } from './utcInstant'
import { inspectionFingerprint, inspectionMaxVersion, inspectionUuid, readInspectionDefinition, readInspectionDetail, readInspectionDetailShape, validInspectionContext, validInspectionText, type InspectionAction, type InspectionContext, type InspectionDefinition, type InspectionDetail, type InspectionItem, type InspectionView } from './inspectionTemplates'
export type WorksheetAction = InspectionAction
export type WorksheetContext = InspectionContext
export type WorksheetView = InspectionView
export interface WorksheetObservation { key: string; result: 'NORMAL' | 'ATTENTION' | 'REQUIRES_ACTION'; note: string | null }
export interface WorksheetCounts { total: number; answered: number; normal: number; attention: number; requires_action: number; unanswered: number; all_results_entered: boolean }
export interface WorksheetFacts { asset_id: string; asset_name: string; template_id: string; template_version: number; template_content_sha256: string; template_device_type: string; template_name: string; template_item_count: number; content_sha256: string; reported_performed_at: string | null; time_source: 'NOT_PROVIDED' | 'SELF_REPORTED_TIME'; summary: WorksheetCounts; verification_status: 'SELF_REPORTED_UNVERIFIED'; template_approval_status: 'UNVERIFIED'; template_applicability_status: 'UNVERIFIED'; publication_status: 'NOT_IMPLEMENTED'; report_published: false; active_scan_performed: false; evidence_binding_status: 'NOT_IMPLEMENTED'; source: 'postgresql'; record_source: 'MANUAL_INSPECTION_DRAFT' }
export interface WorksheetSummary extends WorksheetFacts { id: string; tenant_id: string; created_by: string; version: number; created_at: string; updated_at: string; recorded_by: string; recorded_at: string; action: WorksheetAction; archived: boolean }
export interface WorksheetDetail extends WorksheetSummary { template_items: InspectionItem[]; observations: WorksheetObservation[] }
export interface WorksheetRevision extends WorksheetFacts { version: number; previous_version: number | null; recorded_by: string; recorded_at: string; action: WorksheetAction; archived: boolean; template_items: InspectionItem[]; observations: WorksheetObservation[] }
export interface WorksheetPage { tenant_id: string; items: WorksheetSummary[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; view: WorksheetView; source: 'postgresql'; scope: 'CURRENT_TENANT_PADMIN'; page_size_limit: 20 }
export interface WorksheetHistory { tenant_id: string; worksheet_id: string; items: WorksheetRevision[]; has_more: boolean; next_before_version: number | null; source: 'postgresql'; scope: 'CURRENT_TENANT_PADMIN'; page_size_limit: 20 }
/** This is the selected registered asset label, not a full asset-version snapshot or template applicability approval. */
export interface WorksheetSource { asset: { id: string; tenant_id: string; name: string }; template: InspectionDetail }
export interface WorksheetPayload { expected_version: number; asset_id?: string; template_id?: string; template_version?: number; observations?: WorksheetObservation[]; reported_performed_at?: string | null }
export const worksheetBad = () => new ApiError(0, 'INVALID_INSPECTION_WORKSHEET', '巡检执行单范围、冻结模板、人工内容或摘要未通过核对；未知写入请保留原请求重试', '—')
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const key = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value)
const version = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 1 && Number(value) <= inspectionMaxVersion
const digest = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
export const worksheetTime = (value: unknown): bigint | null => { const time = utcInstantNanos(value); return time !== null && time % 1_000_000n === 0n ? time : null }
export function worksheetSameTime(a: string | null, b: string | null): boolean { return a === null || b === null ? a === b : worksheetTime(a) !== null && worksheetTime(a) === worksheetTime(b) }
export function readWorksheetObservations(value: unknown): WorksheetObservation[] {
  if (!Array.isArray(value) || value.length > 100) throw worksheetBad()
  const seen = new Set<string>(), values: WorksheetObservation[] = []
  for (const row of value) {
    if (!exact(row, ['key','result','note']) || !key(row.key) || seen.has(row.key) || !['NORMAL','ATTENTION','REQUIRES_ACTION'].includes(row.result as string) || !validInspectionText(row.note, 3000, true, true, false)) throw worksheetBad()
    seen.add(row.key); values.push({ key: row.key, result: row.result as WorksheetObservation['result'], note: row.note as string | null })
  }
  return values
}
export function readWorksheetPayload(value: unknown, action: WorksheetAction): WorksheetPayload {
  const fields = action === 'ARCHIVE' ? ['expected_version'] : action === 'CREATE' ? ['expected_version','asset_id','template_id','template_version','observations','reported_performed_at'] : ['expected_version','observations','reported_performed_at']
  if (!['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(action) || !exact(value, fields) || (action === 'CREATE' ? value.expected_version !== 0 : !version(value.expected_version) || value.expected_version >= inspectionMaxVersion)) throw worksheetBad()
  if (action === 'CREATE' && (!inspectionUuid(value.asset_id) || !inspectionUuid(value.template_id) || !version(value.template_version))) throw worksheetBad()
  const payload: WorksheetPayload = { expected_version: value.expected_version as number }
  if (action !== 'ARCHIVE') { if (value.reported_performed_at !== null && worksheetTime(value.reported_performed_at) === null) throw worksheetBad(); payload.observations = readWorksheetObservations(value.observations); payload.reported_performed_at = value.reported_performed_at as string | null }
  if (action === 'CREATE') { payload.asset_id = value.asset_id as string; payload.template_id = value.template_id as string; payload.template_version = value.template_version as number }
  if (new TextEncoder().encode(JSON.stringify(payload)).length > 2_097_152) throw worksheetBad()
  return payload
}
export function readWorksheetSourceShape(value: unknown, context: WorksheetContext): WorksheetSource {
  if (!validInspectionContext(context) || !exact(value, ['asset','template']) || !exact(value.asset, ['id','tenant_id','name']) || !inspectionUuid(value.asset.id) || value.asset.tenant_id !== context.tenantId || !validInspectionText(value.asset.name, 200)) throw worksheetBad()
  const template = readInspectionDetailShape(value.template, context); if (template.archived) throw worksheetBad()
  return { asset: { id: value.asset.id, tenant_id: context.tenantId, name: value.asset.name as string }, template }
}
export async function readWorksheetSource(value: unknown, context: WorksheetContext): Promise<WorksheetSource> { const source = readWorksheetSourceShape(value, context); await readInspectionDetail(source.template, context); return source }
export function worksheetDefinition(value: Pick<WorksheetDetail, 'template_device_type' | 'template_name' | 'template_items'>): InspectionDefinition { return readInspectionDefinition({ device_type: value.template_device_type, name: value.template_name, items: value.template_items }) }
const factKeys = ['asset_id','asset_name','template_id','template_version','template_content_sha256','template_device_type','template_name','template_item_count','content_sha256','reported_performed_at','time_source','summary','verification_status','template_approval_status','template_applicability_status','publication_status','report_published','active_scan_performed','evidence_binding_status','source','record_source']
const metadataKeys = ['id','tenant_id','created_by','version','created_at','updated_at','recorded_by','recorded_at','action','archived']
function facts(value: Record<string, unknown>) {
  if (!inspectionUuid(value.asset_id) || !validInspectionText(value.asset_name, 200) || !inspectionUuid(value.template_id) || !version(value.template_version) || !digest(value.template_content_sha256) || !key(value.template_device_type) || !validInspectionText(value.template_name, 200) || !Number.isSafeInteger(value.template_item_count) || Number(value.template_item_count) < 1 || Number(value.template_item_count) > 100 || !digest(value.content_sha256) || value.verification_status !== 'SELF_REPORTED_UNVERIFIED' || value.template_approval_status !== 'UNVERIFIED' || value.template_applicability_status !== 'UNVERIFIED' || value.publication_status !== 'NOT_IMPLEMENTED' || value.report_published !== false || value.active_scan_performed !== false || value.evidence_binding_status !== 'NOT_IMPLEMENTED' || value.source !== 'postgresql' || value.record_source !== 'MANUAL_INSPECTION_DRAFT') throw worksheetBad()
  if (value.reported_performed_at === null ? value.time_source !== 'NOT_PROVIDED' : value.time_source !== 'SELF_REPORTED_TIME' || worksheetTime(value.reported_performed_at) === null || worksheetTime(value.recorded_at) === null || worksheetTime(value.reported_performed_at)! > worksheetTime(value.recorded_at)!) throw worksheetBad()
  const s = value.summary; if (!exact(s, ['total','answered','normal','attention','requires_action','unanswered','all_results_entered'])) throw worksheetBad()
  for (const field of ['total','answered','normal','attention','requires_action','unanswered']) if (!Number.isSafeInteger(s[field]) || Number(s[field]) < 0 || Number(s[field]) > 100) throw worksheetBad()
  if (s.total !== value.template_item_count || s.answered !== Number(s.normal) + Number(s.attention) + Number(s.requires_action) || s.total !== Number(s.answered) + Number(s.unanswered) || s.all_results_entered !== (s.unanswered === 0)) throw worksheetBad()
}
function lifecycle(value: Record<string, unknown>) {
  if (!version(value.version) || typeof value.action !== 'string' || !['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(value.action) || typeof value.archived !== 'boolean' || value.archived !== (value.action === 'ARCHIVE') || (value.version === 1 ? value.action !== 'CREATE' : value.action === 'CREATE') || !inspectionUuid(value.recorded_by) || worksheetTime(value.recorded_at) === null) throw worksheetBad()
}
export function readWorksheetSummary(value: unknown, context: WorksheetContext): WorksheetSummary {
  if (!validInspectionContext(context) || !exact(value, [...metadataKeys,...factKeys]) || value.tenant_id !== context.tenantId || !inspectionUuid(value.id) || !inspectionUuid(value.created_by)) throw worksheetBad()
  const created = worksheetTime(value.created_at), updated = worksheetTime(value.updated_at), recorded = worksheetTime(value.recorded_at)
  if (created === null || updated === null || recorded === null || updated < created || recorded < created || recorded > updated || value.version === 1 && value.created_by !== value.recorded_by) throw worksheetBad()
  facts(value); lifecycle(value); return structuredClone(value) as unknown as WorksheetSummary
}
function fullContent(value: WorksheetFacts & { template_items: InspectionItem[]; observations: WorksheetObservation[] }) {
  const definition = worksheetDefinition(value), observations = readWorksheetObservations(value.observations), byKey = new Map(observations.map(row => [row.key, row])), keys = new Set(definition.items.map(item => item.key))
  if (definition.items.length !== value.template_item_count || observations.some(row => !keys.has(row.key))) throw worksheetBad()
  const ordered = definition.items.flatMap(item => byKey.has(item.key) ? [byKey.get(item.key)!] : [])
  if (observations.some((row, index) => row.key !== ordered[index]?.key)) throw worksheetBad()
  const counts = { NORMAL: 0, ATTENTION: 0, REQUIRES_ACTION: 0 }; for (const row of observations) counts[row.result]++
  if (value.summary.answered !== observations.length || value.summary.normal !== counts.NORMAL || value.summary.attention !== counts.ATTENTION || value.summary.requires_action !== counts.REQUIRES_ACTION) throw worksheetBad()
  return { definition, observations }
}
export function readWorksheetDetailShape(value: unknown, context: WorksheetContext): WorksheetDetail {
  if (!exact(value, [...metadataKeys,...factKeys,'template_items','observations'])) throw worksheetBad()
  const { template_items, observations, ...summary } = value, row = readWorksheetSummary(summary, context), definition = readInspectionDefinition({ device_type: row.template_device_type, name: row.template_name, items: template_items }), result = { ...row, template_items: definition.items, observations: readWorksheetObservations(observations) }
  fullContent(result); return result
}
/** Canonical content excludes lifecycle metadata and preserves null/time/UTF8 framing exactly as Java and PG. */
export function worksheetBytes(value: WorksheetDetail | WorksheetRevision, tenantId: string): Uint8Array {
  if (!inspectionUuid(tenantId)) throw worksheetBad(); const { definition, observations } = fullContent(value), parts: Uint8Array[] = []
  const integer = (n: number) => { const bytes = new Uint8Array(4); new DataView(bytes.buffer).setInt32(0, n, false); return bytes }, long = (n: bigint) => { const bytes = new Uint8Array(8); new DataView(bytes.buffer).setBigInt64(0, n, false); return bytes }
  const field = (text: string | null) => { if (text === null) { parts.push(integer(-1)); return } const bytes = new TextEncoder().encode(text); parts.push(integer(bytes.length), bytes) }
  for (const text of ['MOP_INSPECTION_WORKSHEET_V1',tenantId,value.asset_id,value.asset_name,value.template_id]) field(text)
  parts.push(long(BigInt(value.template_version))); field(value.template_content_sha256)
  if (value.reported_performed_at === null) parts.push(integer(-1)); else { const time = worksheetTime(value.reported_performed_at); if (time === null) throw worksheetBad(); parts.push(integer(8), long(time / 1_000_000n)) }
  parts.push(integer(observations.length)); const byKey = new Map(observations.map(row => [row.key, row]))
  for (const item of definition.items) { const row = byKey.get(item.key); if (!row) continue; field(row.key); field(row.result); field(row.note); parts.push(integer(0)) }
  const bytes = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0)); if (bytes.length > 2_097_152) throw worksheetBad(); let offset = 0; for (const part of parts) { bytes.set(part, offset); offset += part.length } return bytes
}
export async function worksheetFingerprint(value: WorksheetDetail | WorksheetRevision, tenantId: string): Promise<string> { const bytes = worksheetBytes(value, tenantId), hash = await digestSha256( bytes as Uint8Array<ArrayBuffer>); return Array.from(new Uint8Array(hash)).map(byte => byte.toString(16).padStart(2, '0')).join('') }
async function verifyContent(value: WorksheetDetail | WorksheetRevision, tenantId: string) { if (await inspectionFingerprint(worksheetDefinition(value)) !== value.template_content_sha256 || await worksheetFingerprint(value, tenantId) !== value.content_sha256) throw worksheetBad() }
export async function readWorksheetDetail(value: unknown, context: WorksheetContext): Promise<WorksheetDetail> { const row = readWorksheetDetailShape(value, context); await verifyContent(row, context.tenantId); return row }
export function readWorksheetPage(value: unknown, context: WorksheetContext, view: WorksheetView, assetId: string, before?: { time: string; id: string }): WorksheetPage {
  if (!validInspectionContext(context) || !['CURRENT','ARCHIVED','ALL'].includes(view) || assetId !== '' && !inspectionUuid(assetId) || before && (!inspectionUuid(before.id) || worksheetTime(before.time) === null) || !exact(value, ['tenant_id','items','has_more','next_before_created_at','next_before_id','view','source','scope','page_size_limit']) || value.tenant_id !== context.tenantId || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.view !== view || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_PADMIN' || value.page_size_limit !== 20) throw worksheetBad()
  const items = value.items.map(row => readWorksheetSummary(row, context)), seen = new Set<string>(); let cursor = before
  for (const row of items) { if (seen.has(row.id) || assetId && row.asset_id !== assetId || view === 'CURRENT' && row.archived || view === 'ARCHIVED' && !row.archived || cursor && (worksheetTime(row.created_at)! > worksheetTime(cursor.time)! || worksheetTime(row.created_at) === worksheetTime(cursor.time) && row.id >= cursor.id)) throw worksheetBad(); seen.add(row.id); cursor = { time: row.created_at, id: row.id } }
  if (value.has_more ? items.length !== 15 || value.next_before_id !== cursor?.id || value.next_before_created_at !== cursor?.time : value.next_before_id !== null || value.next_before_created_at !== null) throw worksheetBad()
  return structuredClone({ ...value, items }) as unknown as WorksheetPage
}
export function worksheetBinding(value: WorksheetDetail | WorksheetRevision) { return { asset_id: value.asset_id, asset_name: value.asset_name, template_id: value.template_id, template_version: value.template_version, template_content_sha256: value.template_content_sha256, template_device_type: value.template_device_type, template_name: value.template_name, template_items: worksheetDefinition(value).items } }
export async function readWorksheetHistory(value: unknown, context: WorksheetContext, id: string, before?: number): Promise<WorksheetHistory> {
  if (!validInspectionContext(context) || !inspectionUuid(id) || before !== undefined && !version(before) || !exact(value, ['tenant_id','worksheet_id','items','has_more','next_before_version','source','scope','page_size_limit']) || value.tenant_id !== context.tenantId || value.worksheet_id !== id || !Array.isArray(value.items) || value.items.length > 15 || before === undefined && value.items.length === 0 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_PADMIN' || value.page_size_limit !== 20) throw worksheetBad()
  const snapshot = structuredClone(value); let previous = before, newer: WorksheetRevision | undefined, binding: string | undefined
  for (const [index, raw] of (snapshot.items as unknown[]).entries()) {
    if (!exact(raw, ['version','previous_version','recorded_by','recorded_at','action','archived','template_items','observations',...factKeys])) throw worksheetBad()
    facts(raw); lifecycle(raw); const row = raw as unknown as WorksheetRevision, current = row.version
    if (row.previous_version !== (current === 1 ? null : current - 1) || previous !== undefined && current >= previous || index === 0 && before !== undefined && current !== before - 1 || index > 0 && current !== previous! - 1 || newer && (worksheetTime(newer.recorded_at)! < worksheetTime(row.recorded_at)! || (newer.action === 'RESTORE') !== row.archived || newer.action === 'ARCHIVE' && newer.content_sha256 !== row.content_sha256)) throw worksheetBad()
    fullContent(row); const captured = JSON.stringify(worksheetBinding(row)); if (binding !== undefined && captured !== binding) throw worksheetBad(); binding = captured; await verifyContent(row, context.tenantId); previous = current; newer = row
  }
  if (snapshot.has_more ? (snapshot.items as unknown[]).length !== 15 || previous === undefined || previous <= 1 || snapshot.next_before_version !== previous : snapshot.next_before_version !== null || (snapshot.items as unknown[]).length > 0 && previous !== 1) throw worksheetBad()
  return snapshot as unknown as WorksheetHistory
}
