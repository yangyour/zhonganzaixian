import type {ExportMessage} from '../src/utils/exportInbox'
import {scope,id} from './assetExport.fixture'
export const issued='2026-10-14T06:00:00Z',expires='2026-10-14T06:15:00Z',observed='2026-10-14T06:00:01Z'
export const message=(patch:Partial<ExportMessage>={}):ExportMessage=>({job_id:id,tenant_id:scope.tenantId,recipient_id:scope.actorId,permission_version:1,published_version:4,file_sha256:'a'.repeat(64),size_bytes:100,row_count:1,issued_at:issued,expires_at:expires,version:0,read_at:null,content_url:`/api/v1/asset-export-jobs/${id}/content?grant=${Date.parse(expires)}.${'a'.repeat(43)}`,link_status:'AVAILABLE',source:'postgresql',...patch})
export const inboxPage=(items=[message()],patch={})=>({items,has_more:false,next_before_issued_at:null,next_before_id:null,unread_count:items.filter(m=>!m.read_at).length,observed_at:observed,...patch})
