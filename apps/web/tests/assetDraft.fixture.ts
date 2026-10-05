import type {AssetDraftContext} from '../src/utils/assetDraftWriter'
import type {Asset,AssetDraft} from '../src/api/types'
export const draftScope:AssetDraftContext={tenantId:'77000000-0000-4000-8000-000000000002',actorId:'77000000-0000-4000-8000-000000000001',permissionVersion:7,role:'msp',canWrite:true}
export const draftInput=():AssetDraft=>({name:'明确草稿',type:'switch',management_ip:'192.0.2.1',site_id:'site-a'})
export const draftReceipt=(input=draftInput()):Asset=>({id:'77000000-0000-4000-8000-000000000003',tenant_id:draftScope.tenantId,...input,status:'unknown',lifecycle_status:'DRAFT',source:'manual',sampled_at:null,unknown_reason:'尚未接入，未取得监控采样',version:1,created_at:'2026-10-05T01:00:00.123Z',updated_at:'2026-10-05T01:00:00.124Z',availability:'UNKNOWN',sync_state:'PENDING_ONBOARDING'})
