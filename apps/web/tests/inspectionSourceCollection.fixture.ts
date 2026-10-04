// Explicit synthetic unit DTOs. Actual integration uses the existing approved PG/OIDC separately.
import {createHash} from 'node:crypto'
import {sourceCollectionDiagnostics,sourceCollectionDocumentBytes,sourceCollectionManifestBytes,type SourceCollectionDetail,type SourceCollectionSummary,type SourceCollectionPage,type SourceCollectionContext} from '../src/utils/inspectionSourceCollections'
import type {InspectionSourceRevision} from '../src/utils/inspectionSourceRevision'
import {sourceRevision} from './inspectionSourceRevision.fixture'
import {scope,id} from './inspectionWorksheet.fixture'
export {scope,id,sourceRevision}
const sha=(b:Uint8Array)=>createHash('sha256').update(b).digest('hex')
export function collection(sources:InspectionSourceRevision[]=[sourceRevision()],patch:Partial<SourceCollectionDetail>={},context:SourceCollectionContext=scope):SourceCollectionDetail{
 const tenant=patch.tenant_id??context.tenantId,manifest=sha(sourceCollectionManifestBytes(tenant,sources.map(s=>({worksheet_id:s.worksheet_id,version:s.version,expected_source_sha256:s.source_snapshot_sha256}))))
 return {id:id(50),tenant_id:tenant,created_by:context.actorId,recorded_at:'2026-10-04T09:00:00Z',version:1,label:'SYNTHETIC_UNIT_SOURCE_COLLECTION',source_count:sources.length,document_format:'MOP_INSPECTION_SOURCE_DOCUMENT_V1',document_sha256:sha(sourceCollectionDocumentBytes(tenant,manifest,sources)),source_manifest_format:'MOP_INSPECTION_SOURCE_MANIFEST_V1',source_manifest_sha256:manifest,projection:'FULL_SOURCES',document_integrity:'VERIFIED_SERVER_SIDE',record_source:'EXPLICIT_IMMUTABLE_SOURCE_COLLECTION',verification_status:'SELF_REPORTED_UNVERIFIED',scope:'CURRENT_TENANT_PADMIN',source:'postgresql',evidence_access_granted:false,period_time_zone:null,quarter_selection_rule:null,archived_source_policy:null,metric_version:null,report_template_version:null,tenant_asset_population:null,generation_status:'CONFIG_PENDING',formal_report_generatable:false,report_published:false,sources:structuredClone(sources),diagnostics:sourceCollectionDiagnostics(sources),...patch}
}
export function summary(value=collection()):SourceCollectionSummary{const {sources:_sources,diagnostics:_diagnostics,document_integrity:_integrity,...meta}=value;return {...meta,projection:'METADATA_ONLY'}}
export const page=(items=[summary()]):SourceCollectionPage=>({tenant_id:scope.tenantId,items,has_more:false,next_before_recorded_at:null,next_before_id:null,page_size_limit:20,scope:'CURRENT_TENANT_PADMIN',source:'postgresql'})
export const payload=(value=collection())=>({expected_version:0 as const,label:value.label,sources:value.sources.map(s=>({worksheet_id:s.worksheet_id,version:s.version,expected_source_sha256:s.source_snapshot_sha256}))})
