import type {ImportSourceItem,ImportSourcePage} from '../src/utils/assetImportSources'
export const owner='64000000-0000-4000-8000-000000000001',tenant='64000000-0000-4000-8000-000000000002'
export const id=(n:number)=>'64000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
export function item(n:number):ImportSourceItem {const time=new Date(Date.parse('2026-10-21T00:01:00Z')-n*1000).toISOString();return {id:id(n+100),original_name:'SYNTHETIC_'+n+'.csv',declared_format:'CSV',size_bytes:3,version:1,storage_status:'PENDING',parse_status:'NOT_REQUESTED',mapping_saved:false,created_at:time,updated_at:time}}
export function page(start=0,count=20,more=true):ImportSourcePage {const items=Array.from({length:count},(_,i)=>item(start+i));return {tenant_id:tenant,created_by:owner,scope:'CREATOR_ONLY',items,next_cursor:more?items.at(-1)!.id:null,can_import:false}}
