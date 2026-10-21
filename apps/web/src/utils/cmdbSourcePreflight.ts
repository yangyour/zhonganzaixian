import type {DeepReadonly} from 'vue'
import {ApiError} from '../api/client'
import type {AssetImportSource} from '../api/assetImportTypes'
export interface SourceFinding {record:number;source_row:number;column:number;level:'ERROR'|'WARNING';code:string;message:string}
export interface SourcePreflight {tenant_id:string;source_id:string;source_version:number;parse_hash:string;catalog_version:string;scope:'SOURCE_DICTIONARY_ONLY';status:'ERROR'|'CONFIG_PENDING';records:number;error_rows:number;warning_rows:number;findings:SourceFinding[];database_checks:'NOT_RUN';can_import:false;asset_mutations:false;production_approved:false}
const fields=['tenant_id','source_id','source_version','parse_hash','catalog_version','scope','status','records','error_rows','warning_rows','findings','database_checks','can_import','asset_mutations','production_approved']
const codes=['SOURCE_CELL_ERROR','WHITESPACE','REQUIRED','TYPE_NOT_ALLOWED','SUBTYPE_BEFORE_SERVICE','SUBTYPE_NOT_ALLOWED','SUBTYPE_PARENT_MISMATCH','MAPPING_PENDING','COLLECTION_PENDING','EXCEL_DATE_POLICY_PENDING','DATE_FORMAT_PENDING','DATE_INVALID','EXACT_DUPLICATE_IN_SOURCE']
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
function fail():never{throw new ApiError(200,'CMDB_SOURCE_PREFLIGHT_MISMATCH','校验结果与当前来源版本或结构不一致，请刷新来源后重试','—')}
export function readCmdbSourcePreflight(value:unknown,source:DeepReadonly<AssetImportSource>):SourcePreflight{
 if(!exact(value,fields)||source.parse_status!=='PARSED'||!source.parsed_artifact||value.tenant_id!==source.tenant_id||value.source_id!==source.id||value.source_version!==source.version||value.parse_hash!==source.parsed_artifact.parse_hash||value.catalog_version!=='customer-cmdb-v1-20261021'||value.scope!=='SOURCE_DICTIONARY_ONLY'||value.database_checks!=='NOT_RUN'||value.can_import!==false||value.asset_mutations!==false||value.production_approved!==false||value.records!==source.parsed_artifact.rows.length||!Array.isArray(value.findings)||value.findings.length>50000)fail()
 const rows=new Map(source.parsed_artifact!.rows.map(r=>[r.logical_record_number,r.source_row_number]))
 for(const f of value.findings as unknown[]){
  if(!exact(f,['record','source_row','column','level','code','message'])||!Number.isSafeInteger(f.record)||!Number.isSafeInteger(f.source_row)||Number(f.record)<1||Number(f.source_row)<1||rows.get(f.record as number)!==f.source_row||!Number.isInteger(f.column)||Number(f.column)<0||Number(f.column)>32||!['ERROR','WARNING'].includes(f.level as string)||!codes.includes(f.code as string)||typeof f.message!=='string'||!f.message||f.message.length>300)fail()
 }
 const findings=value.findings as SourceFinding[]
 const errorRows=new Set(findings.filter(f=>f.level==='ERROR').map(f=>f.record)).size,warningRows=new Set(findings.filter(f=>f.level==='WARNING').map(f=>f.record)).size
 if(value.error_rows!==errorRows||value.warning_rows!==warningRows||value.status!==(errorRows?'ERROR':'CONFIG_PENDING'))fail()
 return structuredClone(value) as unknown as SourcePreflight
}
