import Papa from 'papaparse'
import schema from '../generated/cmdbImportSchema.json'
import type {CmdbTypeCatalog} from './cmdbTypeCatalog'
export const CMDB_IMPORT_COLUMNS=schema.columns
export const CMDB_MAX_FILE_BYTES=1024*1024
export const CMDB_MAX_ROWS=500
export interface CmdbFinding {record:number;column:number|null;level:'ERROR'|'WARNING';code:string;message:string}
export interface CmdbPreflight {records:number;rows:string[][];findings:CmdbFinding[];errorRows:number;warningRows:number;canImport:false;databaseChecks:'NOT_RUN';catalogVersion:string}
export function cmdbBlankCsv():string{return '\ufeff'+Papa.unparse([schema.columns.map(c=>c.label)],{newline:'\r\n'})+'\r\n'}
const fail=(message:string):never=>{throw new Error(message)}
export function preflightCmdbCsv(text:string,catalog:CmdbTypeCatalog):CmdbPreflight{
 if(catalog.catalog_version!==schema.catalog_version||catalog.catalog_hash!==schema.catalog_hash)fail('当前目录与导入列定义不是同一版本，请重新读取字典。')
 if(new TextEncoder().encode(text).length>CMDB_MAX_FILE_BYTES)fail('CSV 超过 1 MiB 本地校验上限。')
 if(text.includes('\u0000'))fail('文件包含 NUL 字节；请导出为 UTF-8 CSV，不要直接选择 XLSX。')
 const parsed=Papa.parse<string[]>(text.replace(/^\ufeff/,''),{header:false,delimiter:',',dynamicTyping:false,skipEmptyLines:false,preview:CMDB_MAX_ROWS+2})
 if(parsed.errors.length)fail('CSV 引号或记录结构错误，请检查原文件后重新选择。')
 const records=parsed.data
 // Ignore only trailing completely empty records, not internal blank rows or short rows.
 while(records.length&&records.at(-1)!.every(v=>v===''))records.pop()
 const header=records.shift()
 if(!header||header.length!==33||header.some((v,i)=>v!==schema.columns[i]!.label))fail('表头必须与客户 33 列空模板完全一致且顺序相同；现有 15 列模板不能直接代入。')
 if(records.length>CMDB_MAX_ROWS||parsed.meta.truncated)fail('一次最多校验 500 条数据记录，请拆分文件。')
 if(!records.length)fail('文件只有表头，没有数据记录。')
 const findings:CmdbFinding[]=[];const names=new Map<string,number>(),addresses=new Map<string,number>()
 const add=(record:number,column:number|null,level:CmdbFinding['level'],code:string,message:string)=>findings.push({record,column,level,code,message})
 records.forEach((row,index)=>{
  const record=index+2
  if(row.length>64)fail(`第 ${record} 条记录列数异常，已停止预检；不会截断后展示。`)
  if(row.some(value=>value.length>2048))fail(`第 ${record} 条记录包含超过 2048 字符的单元格，已停止预检。`)
  if(row.length!==33){add(record,null,'ERROR','COLUMN_COUNT','这一条记录不是 33 列，请检查逗号和引号。');return}
  row.forEach((value,column)=>{
   if(value&&value!==value.trim())add(record,column,'WARNING','WHITESPACE','存在首尾空白；原值保留，请在源文件确认后修改。')
   if(/^\s*[=+@-]/.test(value))add(record,column,'WARNING','SPREADSHEET_FORMULA_RISK','可能被表格软件解释为公式；本地仅按文本显示，请人工复核。')
  })
  for(const column of [0,1,2])if(!row[column]!.trim())add(record,column,'ERROR','REQUIRED','客户模板标记为必填，当前为空。')
  const parent=catalog.primary_types.find(v=>v.code===row[1]||v.label===row[1])
  if(row[1]&&!parent)add(record,1,'ERROR','TYPE_NOT_ALLOWED','设备类型不在当前九类字典中；请选择精确代码或名称。')
  const subtype=catalog.subtypes.find(v=>v.code===row[16]||v.label===row[16])
  if(row[16]&&!subtype)add(record,16,'ERROR','SUBTYPE_NOT_ALLOWED','子类型不在当前十六类字典中。')
  else if(subtype&&parent&&subtype.parent!==parent.code)add(record,16,'ERROR','SUBTYPE_PARENT_MISMATCH','子类型与主类型不匹配。')
  else if(subtype&&!subtype.monitoring_mapping_resolved)add(record,16,'WARNING','MAPPING_PENDING','此子类型的监控映射待确认，不会自动套用模板。')
  if(!row[16])add(record,16,'WARNING','SUBTYPE_BEFORE_SERVICE','子类型未填写；进入服务前仍须补齐并核验。')
  if(row[13]&&!catalog.collection_methods.some(v=>v.code===row[13]||v.label===row[13]))add(record,13,'WARNING','COLLECTION_PENDING','采集方式不在目录枚举内（包括 SSH 来源冲突），需要规则确认。')
  for(const column of [10,11,12,17]){
   const value=row[column]!;if(!value)continue
   if(!/^\d{4}-\d{2}-\d{2}$/.test(value)){add(record,column,'WARNING','DATE_FORMAT_PENDING','当前只检查 YYYY-MM-DD；其他日期格式保留原文，等待明确解析规则。');continue}
   const date=new Date(value+'T00:00:00.000Z')
   if(!Number.isFinite(date.getTime())||date.toISOString().slice(0,10)!==value)add(record,column,'ERROR','DATE_INVALID','这不是有效的日历日期。')
  }
  for(const [column,seen] of [[0,names],[2,addresses]] as const){const value=row[column]!;if(!value)continue;const previous=seen.get(value);if(previous)add(record,column,'WARNING','EXACT_DUPLICATE_IN_FILE',`与第 ${previous} 条记录原文相同；尚未按租户/站点规则做去重。`);else seen.set(value,record)}
 })
 return {records:records.length,rows:records,findings,errorRows:new Set(findings.filter(f=>f.level==='ERROR').map(f=>f.record)).size,warningRows:new Set(findings.filter(f=>f.level==='WARNING').map(f=>f.record)).size,canImport:false,databaseChecks:'NOT_RUN',catalogVersion:catalog.catalog_version}
}
