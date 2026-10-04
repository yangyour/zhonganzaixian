import {ApiError} from '../api/client'
import {readWorklog,worklogInstant,type WorklogEntry,type WorklogKind} from './worklogWorkspace'
export interface OwnWorklogContext {tenantId:string;actorId:string;permissionVersion:number;role:string;canRead:boolean}
export interface OwnWorklogFilters {ticket_id:string|null;kind:WorklogKind|null;started_from:string|null;started_before:string|null}
export interface OwnWorklogCursor {time:string;id:string}
export interface OwnWorklogPage {tenant_id:string;owner_id:string;items:WorklogEntry[];has_more:boolean;next_before_created_at:string|null;next_before_id:string|null;filters:OwnWorklogFilters;source:'postgresql';scope:'OWN_CURRENT_TENANT';page_size_limit:20;projection:'CURRENT_MANUAL_ENTRIES';time_selection:'STARTED_AT_HALF_OPEN_IF_SUPPLIED';interval_projection:'FULL_RECORDED_INTERVAL_NOT_CLIPPED';snapshot_frozen:false;aggregates:null;financial_status:'CONFIG_PENDING'}
export const ownWorklogUuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
export const ownWorklogBad=()=>new ApiError(0,'INVALID_OWN_WORKLOG_QUERY','本人工时范围、查询条件或当前事实未通过核对，旧结果已清除','—')
export const emptyOwnWorklogFilters=():OwnWorklogFilters=>({ticket_id:null,kind:null,started_from:null,started_before:null})
export const validOwnWorklogContext=(c:OwnWorklogContext|null):c is OwnWorklogContext=>Boolean(c&&ownWorklogUuid(c.tenantId)&&ownWorklogUuid(c.actorId)&&Number.isSafeInteger(c.permissionVersion)&&c.permissionVersion>0&&c.role==='msp'&&c.canRead===true)
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
export function readOwnWorklogFilters(v:unknown):OwnWorklogFilters{
 if(!exact(v,['ticket_id','kind','started_from','started_before'])||v.ticket_id!==null&&!ownWorklogUuid(v.ticket_id)||v.kind!==null&&!['REMOTE','ONSITE','INSPECTION','COMMUTE'].includes(v.kind as string)||(v.started_from===null)!==(v.started_before===null))throw ownWorklogBad()
 try{const from=v.started_from===null?null:worklogInstant(v.started_from),before=v.started_before===null?null:worklogInstant(v.started_before);if(from!==null&&(before===null||before<=from))throw ownWorklogBad();return{ticket_id:v.ticket_id as string|null,kind:v.kind as WorklogKind|null,started_from:from===null?null:new Date(from).toISOString(),started_before:before===null?null:new Date(before).toISOString()}}catch{throw ownWorklogBad()}
}
export function ownWorklogQuery(filters:unknown,before?:OwnWorklogCursor):string{
 const f=readOwnWorklogFilters(filters),q=new URLSearchParams({page_size:'15'});for(const [key,value]of Object.entries(f))if(value!==null)q.set(key,value)
 if(before){if(!ownWorklogUuid(before.id)||typeof before.time!=='string'||!before.time.endsWith('Z'))throw ownWorklogBad();try{worklogInstant(before.time)}catch{throw ownWorklogBad()}q.set('before_created_at',before.time);q.set('before_id',before.id)}return '/my/worklogs?'+q
}
export function readOwnWorklogEntry(v:unknown,c:OwnWorklogContext):WorklogEntry{
 if(!validOwnWorklogContext(c)||!v||typeof v!=='object'||Array.isArray(v)||!ownWorklogUuid((v as Record<string,unknown>).ticket_id))throw ownWorklogBad()
 try{const row=readWorklog(v,{tenantId:c.tenantId,actorId:c.actorId,ticketId:(v as {ticket_id:string}).ticket_id,permissionVersion:c.permissionVersion,canWrite:false});if(worklogInstant(row.updated_at)<worklogInstant(row.created_at))throw ownWorklogBad();return row}catch{throw ownWorklogBad()}
}
export function readOwnWorklogPage(v:unknown,c:OwnWorklogContext,filters:unknown,before?:OwnWorklogCursor):OwnWorklogPage{
 const f=readOwnWorklogFilters(filters);ownWorklogQuery(f,before)
 if(!validOwnWorklogContext(c)||!exact(v,['tenant_id','owner_id','items','has_more','next_before_created_at','next_before_id','filters','source','scope','page_size_limit','projection','time_selection','interval_projection','snapshot_frozen','aggregates','financial_status'])||v.tenant_id!==c.tenantId||v.owner_id!==c.actorId||!Array.isArray(v.items)||v.items.length>15||typeof v.has_more!=='boolean'||v.source!=='postgresql'||v.scope!=='OWN_CURRENT_TENANT'||v.page_size_limit!==20||v.projection!=='CURRENT_MANUAL_ENTRIES'||v.time_selection!=='STARTED_AT_HALF_OPEN_IF_SUPPLIED'||v.interval_projection!=='FULL_RECORDED_INTERVAL_NOT_CLIPPED'||v.snapshot_frozen!==false||v.aggregates!==null||v.financial_status!=='CONFIG_PENDING'||JSON.stringify(readOwnWorklogFilters(v.filters))!==JSON.stringify(f))throw ownWorklogBad()
 const items=v.items.map(r=>readOwnWorklogEntry(r,c)),seen=new Set<string>();let cursor=before
 for(const row of items){if(seen.has(row.id)||cursor&&(worklogInstant(row.created_at)>worklogInstant(cursor.time)||worklogInstant(row.created_at)===worklogInstant(cursor.time)&&row.id>=cursor.id)||f.ticket_id!==null&&row.ticket_id!==f.ticket_id||f.kind!==null&&row.kind!==f.kind||f.started_from!==null&&(worklogInstant(row.started_at)<worklogInstant(f.started_from)||worklogInstant(row.started_at)>=worklogInstant(f.started_before)))throw ownWorklogBad();seen.add(row.id);cursor={time:row.created_at,id:row.id}}
 if(v.has_more?items.length!==15||v.next_before_created_at!==cursor?.time||v.next_before_id!==cursor?.id:v.next_before_created_at!==null||v.next_before_id!==null)throw ownWorklogBad()
 return structuredClone({...v,filters:f,items}) as unknown as OwnWorklogPage
}
