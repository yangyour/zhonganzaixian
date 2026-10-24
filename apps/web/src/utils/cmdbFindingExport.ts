import {CMDB_IMPORT_COLUMNS,type CmdbPreflight} from './cmdbImportPreflight'
import type {SourcePreflight} from './cmdbSourcePreflight'
export const CMDB_FINDING_EXPORT_MAX_BYTES=8*1024*1024
interface Finding {record:number;sourceRow:number|null;column:number|null;level:string;code:string;message:string}
interface Metadata {scope:string;catalog:string;records:number;errors:number;warnings:number;database:string;tenant:string;source:string;version:number|null;parseHash:string}
export interface CmdbFindingExport {csv:string;filename:'cmdb-local-findings.csv'|'cmdb-source-findings.csv';findingCount:number}
const fail=():never=>{throw Error('问题清单结构不完整或超过 8 MiB，未导出不完整文件。请保留原检查结果并分批修正源文件。')}
function cell(value:unknown):string{
 let s=value===null?'':String(value)
 if(s.length>1000||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]|[\uD800-\uDFFF]/u.test(s))fail()
 // Quotes do not neutralize spreadsheet formula evaluation. This review CSV intentionally escapes risky text.
 if(/^\s*[=+@-]/u.test(s)||/^[\t\r]/.test(s))s="'"+s
 return '"'+s.replaceAll('"','""')+'"'
}
function render(meta:Metadata,findings:Finding[],filename:CmdbFindingExport['filename']):CmdbFindingExport{
 if(!Number.isSafeInteger(meta.records)||meta.records<1||findings.length>50000||new Set(findings.filter(f=>f.level==='ERROR').map(f=>f.record)).size!==meta.errors||new Set(findings.filter(f=>f.level==='WARNING').map(f=>f.record)).size!==meta.warnings)fail()
 const lines:string[]=[];let size=3;const encoder=new TextEncoder()
 const append=(values:unknown[])=>{const line=values.map(cell).join(',')+'\r\n';size+=encoder.encode(line).length;if(size>CMDB_FINDING_EXPORT_MAX_BYTES)fail();lines.push(line)}
 append(['row_type','check_scope','catalog_version','can_import','database_checks','records_checked','error_rows','warning_rows','tenant_id','source_id','source_version','parse_hash','logical_record','source_row','column_index_0_based','column_label','level','code','message'])
 const common=[meta.scope,meta.catalog,false,meta.database,meta.records,meta.errors,meta.warnings,meta.tenant,meta.source,meta.version,meta.parseHash]
 append(['SUMMARY',...common,null,null,null,null,null,null,'有限检查结果，不是导入许可；零问题不代表其余检查通过。'])
 for(const f of findings){
  if(!Number.isSafeInteger(f.record)||f.record<1||f.sourceRow!==null&&(!Number.isSafeInteger(f.sourceRow)||f.sourceRow<1)||f.column!==null&&(!Number.isInteger(f.column)||f.column<0||f.column>=33)||!['ERROR','WARNING'].includes(f.level)||!f.code||!f.message)fail()
  append(['FINDING',...common,f.record,f.sourceRow,f.column,f.column===null?'整条记录':CMDB_IMPORT_COLUMNS[f.column]!.label,f.level,f.code,f.message])
 }
 return {csv:'\ufeff'+lines.join(''),filename,findingCount:findings.length}
}
export function localCmdbFindingExport(value:CmdbPreflight):CmdbFindingExport{
 if(value.canImport!==false||value.databaseChecks!=='NOT_RUN'||value.records!==value.rows.length||value.findings.some(f=>f.record<2||f.record>value.records+1))fail()
 return render({scope:'LOCAL_CSV_DICTIONARY_ONLY',catalog:value.catalogVersion,records:value.records,errors:value.errorRows,warnings:value.warningRows,database:value.databaseChecks,tenant:'',source:'',version:null,parseHash:''},value.findings.map(f=>({...f,sourceRow:null})),'cmdb-local-findings.csv')
}
export function sourceCmdbFindingExport(value:SourcePreflight):CmdbFindingExport{
 if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.tenant_id)||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.source_id)||!Number.isSafeInteger(value.source_version)||value.source_version<1||!/^[0-9a-f]{64}$/.test(value.parse_hash)||value.can_import!==false||value.asset_mutations!==false||value.production_approved!==false||!(value.scope==='SOURCE_DICTIONARY_ONLY'&&value.database_checks==='NOT_RUN'||value.scope==='SOURCE_DICTIONARY_AND_ASSET_NAMES'&&value.database_checks==='EXACT_ASSET_NAMES_ONLY'))fail()
 return render({scope:value.scope,catalog:value.catalog_version,records:value.records,errors:value.error_rows,warnings:value.warning_rows,database:value.database_checks,tenant:value.tenant_id,source:value.source_id,version:value.source_version,parseHash:value.parse_hash},value.findings.map(f=>({...f,sourceRow:f.source_row})),'cmdb-source-findings.csv')
}
export function downloadCmdbFindings(file:CmdbFindingExport){
 if(!['cmdb-local-findings.csv','cmdb-source-findings.csv'].includes(file.filename)||!file.csv.startsWith('\ufeff"row_type"')||new TextEncoder().encode(file.csv).length>CMDB_FINDING_EXPORT_MAX_BYTES)fail()
 const url=URL.createObjectURL(new Blob([file.csv],{type:'text/csv;charset=utf-8'})),link=document.createElement('a')
 try{link.href=url;link.download=file.filename;link.hidden=true;document.body.append(link);link.click()}finally{link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}
}
