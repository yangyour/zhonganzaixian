import type {AssetExportContext,AssetExportFilters,AssetExportSummary,AssetExportDetail,AssetExportPage,AssetExportReceipt} from '../src/utils/assetExportJobs'
export const id='7f000000-0000-4000-8000-000000000003',other='7f000000-0000-4000-8000-000000000004'
export const scope:AssetExportContext={tenantId:'7f000000-0000-4000-8000-000000000002',actorId:'7f000000-0000-4000-8000-000000000001',permissionVersion:1,role:'padmin',canRead:true}
export const filters=():AssetExportFilters=>({type:null,status:null,site_id:null,query:null})
export const summary=(patch:Partial<AssetExportSummary>={}):AssetExportSummary=>({id,tenant_id:scope.tenantId,created_by:scope.actorId,accepted_permission_version:1,accepted_role:'padmin',input_sha256:'a'.repeat(64),filters:filters(),status:'QUEUED',version:0,created_at:'2026-10-04T00:00:00Z',updated_at:'2026-10-04T00:00:00Z',source:'postgresql',scope:'CURRENT_CREATOR_TENANT',worker_liveness:null,object_verification:'NOT_CHECKED_BY_PROGRESS_READER',publication_status:'NOT_IMPLEMENTED',download_allowed:false,...patch})
export const detail=(patch:Partial<AssetExportDetail>={}):AssetExportDetail=>({...summary(),attempts:0,prepared:null,error_code:null,...patch})
export const prepared=()=>({selection_sha256:'b'.repeat(64),file_sha256:'c'.repeat(64),size_bytes:4,row_count:0,generated_at:'2026-10-04T00:00:00Z'})
export const running=():AssetExportDetail=>detail({status:'RUNNING',version:2,attempts:1,prepared:prepared()})
export const page=(items:AssetExportSummary[]=[]):AssetExportPage=>({tenant_id:scope.tenantId,created_by:scope.actorId,items,has_more:false,next_before_created_at:null,next_before_id:null,page_size_limit:20,source:'postgresql',scope:'CURRENT_CREATOR_TENANT',worker_liveness:null,publication_status:'NOT_IMPLEMENTED',download_allowed:false})
export const receipt=():AssetExportReceipt=>({job_id:id,status:'QUEUED',version:0,mode:'PREPARE_ONLY_NO_PUBLICATION',publication_status:'NOT_IMPLEMENTED',download_allowed:false})
