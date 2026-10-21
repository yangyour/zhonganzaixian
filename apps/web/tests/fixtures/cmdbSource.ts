import type {AssetImportSource} from '../../src/api/assetImportTypes'
import schema from '../../src/generated/cmdbImportSchema.json'
import response from '../../../../fixtures/cmdb-source-preflight-controller.json'
export {response}
export const actor='64000000-0000-4000-8000-000000000001'
export function cmdbSource():AssetImportSource{
 const d=response.data,time='2026-10-21T00:00:00Z',art='64000000-0000-4000-8000-000000000004',op='64000000-0000-4000-8000-000000000005'
 return {id:d.source_id,tenant_id:d.tenant_id,created_by:actor,purpose:'asset_import_source',original_name:'synthetic.csv',declared_format:'CSV',size_bytes:100,sha256:'b'.repeat(64),storage_status:'READY',parse_status:'PARSED',store_operation_id:op,parse_operation_id:op,version:3,created_at:time,updated_at:time,latest_artifact_id:art,latest_mapping_id:null,mapping:null,retention_until:null,scan_status:'UNSCANNED',production_approval:'UNVERIFIED',business_status:'CONFIG_PENDING',can_import:false,effective_rule_versions:null,quota_reservation:null,question_ids:['Q20','Q31'],parsed_artifact:{id:art,tenant_id:d.tenant_id,source_id:d.source_id,operation_id:op,parse_hash:d.parse_hash,source_sha256:'b'.repeat(64),schema_version:'contract-m3-6-standard15-v1',created_at:time,selection:{declared_format:'CSV',encoding:'UTF-8',delimiter:',',sheet_index:null,header_row:1},state:'PARSED',detected_format:'CSV',sheets:[{index:0,name:'CSV',hidden:false}],selected_sheet_index:0,header_row:1,date_epoch:'NOT_APPLICABLE',columns:schema.columns.map(c=>({index:c.position,header:c.label,target_suggestions:[]})),rows:[{logical_record_number:2,source_row_number:2,physical_start_line:2,physical_end_line:2,cells:[]}],parser_version:'synthetic',resource_policy_version:'synthetic'}}
}
