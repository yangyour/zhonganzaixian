import {sourceCollectionManifestBytes, sourceCollectionSha, readSourceCollectionPayload, type SourceCollectionSelection} from './inspectionSourceCollections'
import {exact, readReportConfiguration, readReportPayload, readReportSummary, reportBad, reportInputSha, type ReportConfiguration, type ReportContext, type ReportSummary} from './inspectionQuarterlyReports'

export interface ReportCapturePayload {
  expected_version: 0
  label: string
  sources: SourceCollectionSelection[]
  year: number
  quarter: number
  configuration: ReportConfiguration
}

/** Capture accepts explicit revisions only; all computed fields and source collection IDs are server owned. */
export function readReportCapturePayload(raw: unknown): ReportCapturePayload {
  if (!exact(raw, ['expected_version', 'label', 'sources', 'year', 'quarter', 'configuration'])) throw reportBad()
  const source = readSourceCollectionPayload({expected_version: raw.expected_version, label: raw.label, sources: raw.sources})
  if (!Number.isSafeInteger(raw.year) || Number(raw.year) < 1 || Number(raw.year) > 9998
      || !Number.isSafeInteger(raw.quarter) || Number(raw.quarter) < 1 || Number(raw.quarter) > 4) throw reportBad()
  const result: ReportCapturePayload = {expected_version: 0, label: source.label, sources: source.sources,
    year: Number(raw.year), quarter: Number(raw.quarter), configuration: readReportConfiguration(raw.configuration)}
  if (new TextEncoder().encode(JSON.stringify(result)).length > 262144) throw reportBad()
  return result
}

/** A validated capture receipt becomes ordinary metadata, so list/detail comparisons retain the existing strict contract. */
export async function readCaptureReceipt(raw: unknown, scope: ReportContext, payload: ReportCapturePayload): Promise<ReportSummary> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw reportBad()
  const {capture_mode, ...metadata} = raw as Record<string, unknown>
  if (capture_mode !== 'ATOMIC_EXPLICIT_WORKSHEET_REVISIONS') throw reportBad()
  const receipt = readReportSummary(metadata, scope)
  const manifest = await sourceCollectionSha(sourceCollectionManifestBytes(scope.tenantId, payload.sources))
  const expected = await reportInputSha(readReportPayload({expected_version: 0, label: payload.label,
    source_collection_id: receipt.source_collection_id, expected_source_document_sha256: receipt.source_document_sha256,
    expected_source_manifest_sha256: manifest, year: payload.year, quarter: payload.quarter, configuration: payload.configuration}))
  if (receipt.created_by !== scope.actorId || receipt.label !== payload.label || receipt.year !== payload.year
      || receipt.quarter !== payload.quarter || receipt.source_manifest_sha256 !== manifest || receipt.report_sha256 !== expected) throw reportBad()
  return receipt
}
