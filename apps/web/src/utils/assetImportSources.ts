import {ApiError} from '../api/client'
import {importUuid} from './assetImportReadback'
import {isUtcInstant,utcInstantNanos,utcNotBefore} from './utcInstant'
export interface ImportSourceItem {id:string;original_name:string;declared_format:'CSV'|'XLSX';size_bytes:number;version:number;storage_status:'PENDING'|'READY'|'FAILED';parse_status:'NOT_REQUESTED'|'PENDING'|'PARSED'|'SELECTION_REQUIRED'|'FAILED';mapping_saved:boolean;created_at:string;updated_at:string}
export interface ImportSourcePage {tenant_id:string;created_by:string;scope:'CREATOR_ONLY';items:ImportSourceItem[];next_cursor:string|null;can_import:false}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
function fail():never{throw new ApiError(200,'INVALID_IMPORT_SOURCE_LIST','来源列表与当前账号、租户或分页范围不符，请重新读取','—')}
function before(a:ImportSourceItem,b:ImportSourceItem){const x=utcInstantNanos(a.created_at)!,y=utcInstantNanos(b.created_at)!;return x<y||x===y&&a.id<b.id}
export function readImportSourcePage(value:unknown,scope:{tenantId:string;actorId:string},limit=20,anchor?:ImportSourceItem):ImportSourcePage{
 if(!importUuid(scope.tenantId)||!importUuid(scope.actorId)||!Number.isInteger(limit)||limit<1||limit>50||!exact(value,['tenant_id','created_by','scope','items','next_cursor','can_import'])||value.tenant_id!==scope.tenantId||value.created_by!==scope.actorId||value.scope!=='CREATOR_ONLY'||value.can_import!==false||!Array.isArray(value.items)||value.items.length>limit)fail()
 const seen=new Set<string>();let prior=anchor
 for(const raw of value.items as unknown[]){
  if(!exact(raw,['id','original_name','declared_format','size_bytes','version','storage_status','parse_status','mapping_saved','created_at','updated_at'])||!importUuid(raw.id)||seen.has(raw.id)||typeof raw.original_name!=='string'||!raw.original_name||[...raw.original_name].length>200||/[\\/\u0000-\u001f\u007f]/.test(raw.original_name)||!['CSV','XLSX'].includes(String(raw.declared_format))||!Number.isSafeInteger(raw.size_bytes)||Number(raw.size_bytes)<1||Number(raw.size_bytes)>20971520||!Number.isSafeInteger(raw.version)||Number(raw.version)<1||!['PENDING','READY','FAILED'].includes(String(raw.storage_status))||!['NOT_REQUESTED','PENDING','PARSED','SELECTION_REQUIRED','FAILED'].includes(String(raw.parse_status))||typeof raw.mapping_saved!=='boolean'||!isUtcInstant(raw.created_at)||!utcNotBefore(raw.updated_at,raw.created_at))fail()
  const item=raw as unknown as ImportSourceItem
  if(item.parse_status!=='NOT_REQUESTED'&&item.storage_status!=='READY'||item.mapping_saved&&item.parse_status!=='PARSED'||prior&&!before(item,prior))fail()
  seen.add(item.id);prior=item
 }
 if(value.next_cursor!==null&&(!importUuid(value.next_cursor)||value.items.length!==limit||value.next_cursor!==(value.items.at(-1) as ImportSourceItem)?.id))fail()
 return structuredClone(value) as unknown as ImportSourcePage
}
