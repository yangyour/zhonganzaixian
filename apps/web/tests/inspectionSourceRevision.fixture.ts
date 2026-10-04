// Synthetic unit DTO only. Production HTTP uses existing real PG and OIDC separately.
import {createHash} from 'node:crypto'
import {inspectionSourceBytes,type InspectionSourceRevision} from '../src/utils/inspectionSourceRevision'
import {row} from './inspectionWorksheet.fixture'
import type {WorksheetDetail} from '../src/utils/inspectionWorksheets'
export function sourceRevision(version=1,patch:Partial<WorksheetDetail>={}):InspectionSourceRevision{
 const {id,updated_at:_updated,...facts}=row(version,patch)
 const result:InspectionSourceRevision={...facts,worksheet_id:id,source_type:'IMMUTABLE_INSPECTION_WORKSHEET_REVISION',source_format:'MOP_INSPECTION_SOURCE_SNAPSHOT_V1',source_snapshot_sha256:'',scope:'CURRENT_TENANT_PADMIN',evidence_access_granted:false}
 result.source_snapshot_sha256=createHash('sha256').update(inspectionSourceBytes(result)).digest('hex');return result
}
