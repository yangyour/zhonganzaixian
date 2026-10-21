import { ApiError } from '../api/client'
export interface Choice {code:string;label:string}
export interface CmdbSubtype extends Choice {parent:string;monitoring_mapping_resolved:boolean;source_sheet:string;source_row:number}
export interface CmdbAttribute extends Choice {group:string;data_type:string;required_note:string;derived:boolean;source_sheet:string;source_row:number}
export interface CmdbTypeCatalog {tenant_id:string;catalog_version:string;catalog_hash:string;scope:'REFERENCE_ONLY_NOT_ASSET_DATA';primary_types:Choice[];subtypes:CmdbSubtype[];attributes:CmdbAttribute[];collection_methods:Choice[];state_dimensions:{field:string;values:Choice[]}[];unresolved:{code:string;resolution:null}[];runtime_publication:false;asset_mutations:false;production_approved:false}
const types=['SW','RT','FW','WL','SV','HC','VD','ST','DS']
const subtypes=['sw_core','sw_access','rt_gateway','fw_ngfw','fw_ips','fw_audit','fw_adc','wl_ac','wl_ap','sv_physical','sv_vm','hc_node','vd_host','st_san','st_nas','ds_node']
const methods=['agent_active','agent_passive','snmpv3','http','sender']
const dimensions:Record<string,string[]>={lifecycle_state:['planned','procured','onboarding','in_service','maintenance','suspended','retired','disposed'],sync_state:['synced','syncing','failed','drifted'],health_state:['normal','problem','unknown','maintenance'],billable:['yes','no','addon']}
export const cmdbIssueLabels:Record<string,string>={L2_COUNT_CONFLICT:'子类型数量说明与实际条目不一致（15 / 16）',MONITORING_TYPE_MAPPING_PENDING:'负载均衡子类型的监控类型映射待确认',IMPORT_COLLECTION_METHOD_CONFLICT:'示例中的 SSH 不在采集方式枚举内',NOTE_REQUIREDNESS_CONFLICT:'备注是否必填与既有导入规则不一致'}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const text=(v:unknown,max=200):v is string=>typeof v==='string'&&v.length>0&&v.length<=max&&!/[\u0000-\u001f\u007f]/.test(v)
const choices=(v:unknown,codes:string[])=>Array.isArray(v)&&v.length===codes.length&&v.every(x=>exact(x,['code','label'])&&codes.includes(x.code as string)&&text(x.label))&&new Set(v.map(x=>x.code)).size===codes.length
const source=(v:Record<string,unknown>)=>text(v.source_sheet)&&Number.isSafeInteger(v.source_row)&&Number(v.source_row)>1&&Number(v.source_row)<1000
const invalid=()=>new ApiError(200,'INVALID_CMDB_CATALOG','CMDB 字典的范围或结构不一致，已清除显示','—')
export function readCmdbTypeCatalog(v:unknown,tenantId:string):CmdbTypeCatalog{
 if(!exact(v,['tenant_id','catalog_version','catalog_hash','scope','primary_types','subtypes','attributes','collection_methods','state_dimensions','unresolved','runtime_publication','asset_mutations','production_approved'])||v.tenant_id!==tenantId||v.catalog_version!=='customer-cmdb-v1-20261021'||typeof v.catalog_hash!=='string'||!/^[0-9a-f]{64}$/.test(v.catalog_hash)||v.scope!=='REFERENCE_ONLY_NOT_ASSET_DATA'||v.runtime_publication!==false||v.asset_mutations!==false||v.production_approved!==false)throw invalid()
 if(!choices(v.primary_types,types)||!choices(v.collection_methods,methods)||!Array.isArray(v.subtypes)||v.subtypes.length!==16||!Array.isArray(v.attributes)||v.attributes.length!==95||!Array.isArray(v.state_dimensions)||v.state_dimensions.length!==4||!Array.isArray(v.unresolved)||v.unresolved.length!==4)throw invalid()
 for(const row of v.subtypes){if(!exact(row,['code','label','parent','monitoring_mapping_resolved','source_sheet','source_row'])||!subtypes.includes(row.code as string)||!text(row.label)||row.parent!==String(row.code).split('_')[0]!.toUpperCase()||row.monitoring_mapping_resolved!==(row.code!=='fw_adc')||!source(row))throw invalid()}
 if(new Set(v.subtypes.map(x=>x.code)).size!==16)throw invalid()
 for(const row of v.attributes){if(!exact(row,['code','label','group','data_type','required_note','derived','source_sheet','source_row'])||typeof row.code!=='string'||!/^[a-z][a-z0-9_]{0,63}$/.test(row.code)||![row.label,row.group,row.data_type,row.required_note].every(x=>text(x))||typeof row.derived!=='boolean'||!source(row))throw invalid()}
 if(new Set(v.attributes.map(x=>x.code)).size!==95)throw invalid()
 for(const row of v.state_dimensions){if(!exact(row,['field','values'])||typeof row.field!=='string'||!Object.hasOwn(dimensions,row.field)||!choices(row.values,dimensions[row.field]!))throw invalid()}
 if(new Set(v.state_dimensions.map(x=>x.field)).size!==4)throw invalid()
 for(const row of v.unresolved){if(!exact(row,['code','resolution'])||typeof row.code!=='string'||!Object.hasOwn(cmdbIssueLabels,row.code)||row.resolution!==null)throw invalid()}
 if(new Set(v.unresolved.map(x=>x.code)).size!==4)throw invalid()
 return structuredClone(v) as unknown as CmdbTypeCatalog
}
