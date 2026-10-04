import { inspectionUuid, validInspectionContext, validInspectionText, type InspectionItem } from './inspectionTemplates'
import { worksheetBad, type WorksheetContext, type WorksheetObservation } from './inspectionWorksheets'
export interface WorksheetAssetChoice { id: string; tenant_id: string; name: string }
export interface WorksheetAssetChoices { items: WorksheetAssetChoice[]; has_more: boolean; next_cursor: string | null }
/** Only a same-tenant identity/label projection is used; other asset facts are neither synthesized nor displayed here. */
export function worksheetAssetChoice(value: unknown, context: WorksheetContext, expectedId?: string): WorksheetAssetChoice {
  if (!validInspectionContext(context) || !value || typeof value !== 'object' || Array.isArray(value)) throw worksheetBad()
  const row = value as Record<string, unknown>
  if (!inspectionUuid(row.id) || row.tenant_id !== context.tenantId || expectedId !== undefined && row.id !== expectedId || !validInspectionText(row.name, 200)) throw worksheetBad()
  return { id: row.id, tenant_id: context.tenantId, name: row.name as string }
}
export function worksheetAssetChoices(value: unknown, context: WorksheetContext): WorksheetAssetChoices {
  if (!validInspectionContext(context) || !value || typeof value !== 'object' || Array.isArray(value)) throw worksheetBad(); const page = value as Record<string, unknown>
  if (Object.keys(page).length !== 6 || !['items','has_more','next_cursor','source','page_size_limit','page_size_limit_kind'].every(k=>Object.hasOwn(page,k)) || !Array.isArray(page.items) || page.items.length > 15 || typeof page.has_more !== 'boolean' || page.source !== 'postgresql' || page.page_size_limit !== 100 || page.page_size_limit_kind !== 'test_technical_limit') throw worksheetBad()
  if (page.has_more ? page.items.length !== 15 || typeof page.next_cursor !== 'string' || page.next_cursor.length < 1 || page.next_cursor.length > 2048 || /[\u0000-\u0020\u007f]/.test(page.next_cursor) : page.next_cursor !== null) throw worksheetBad()
  const items = page.items.map(row=>worksheetAssetChoice(row,context)); if (new Set(items.map(row=>row.id)).size !== items.length) throw worksheetBad()
  return { items, has_more: page.has_more, next_cursor: page.next_cursor as string | null }
}
export interface WorksheetEditorRow { item: InspectionItem; result: WorksheetObservation['result'] | ''; note: string | null }
export function worksheetEditorRows(items: InspectionItem[], observations: WorksheetObservation[] = []): WorksheetEditorRow[] {
  const answers = new Map(observations.map(row=>[row.key,row]));return items.map(item=>({item:structuredClone(item),result:answers.get(item.key)?.result??'',note:answers.get(item.key)?.note??null}))
}
export function worksheetEditorObservations(rows: WorksheetEditorRow[]): WorksheetObservation[] { return rows.flatMap(row=>row.result===''?[]:[{key:row.item.key,result:row.result,note:row.note}]) }
