import {ApiError} from '../api/client'
import type {Asset,AssetPage} from '../api/types'
import {assetDraftIp} from './assetDraftWriter'
import {isUtcInstant,utcNotBefore} from './utcInstant'
const uuid=(v:unknown):v is string=>typeof v==='string'&&v.length===36&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const record=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const bad=()=>new ApiError(200,'INVALID_ASSET_READ_RESPONSE','资产读取结果的租户、标识、版本或格式未通过核对，未显示未经确认的数据。','—')
const text=(v:unknown,max:number)=>typeof v==='string'&&v.trim().length>0&&v.length<=max&&!/[\u0000-\u001f\u007f]/.test(v)
export function readAssetSnapshot(raw:unknown,tenantId:string,assetId?:string):Asset{
 const required=['id','tenant_id','name','type','management_ip','site_id','status','lifecycle_status','source','sampled_at','unknown_reason','version','created_at','updated_at'],allowed=[...required,'availability','sync_state']
 if(!uuid(tenantId)||assetId!==undefined&&!uuid(assetId)||!record(raw)||!required.every(k=>Object.hasOwn(raw,k))||Object.keys(raw).some(k=>!allowed.includes(k))||!uuid(raw.id)||raw.tenant_id!==tenantId||assetId!==undefined&&raw.id!==assetId||!text(raw.name,100)||!['router','switch','firewall','ap','wireless_controller','other'].includes(String(raw.type))||typeof raw.management_ip!=='string'||!assetDraftIp(raw.management_ip)||!text(raw.site_id,64)||!['unknown','ok','problem','maintenance','disabled'].includes(String(raw.status))||!text(raw.lifecycle_status,100)||!['test_seed','manual'].includes(String(raw.source))||!Number.isSafeInteger(raw.version)||Number(raw.version)<1||!isUtcInstant(raw.created_at)||!utcNotBefore(raw.updated_at,raw.created_at)||raw.sampled_at!==null&&!isUtcInstant(raw.sampled_at)||raw.unknown_reason!==null&&typeof raw.unknown_reason!=='string'||['availability','sync_state'].some(k=>Object.hasOwn(raw,k)&&!text(raw[k],100)))throw bad()
 return structuredClone(raw) as unknown as Asset
}
export function readAssetList(raw:unknown,tenantId:string,pageSize:number,mode:'remote_test'|'local_fixture'):AssetPage{
 if(!['remote_test','local_fixture'].includes(mode)||!record(raw)||Object.keys(raw).length!==6||!['items','next_cursor','has_more','source','page_size_limit','page_size_limit_kind'].every(k=>Object.hasOwn(raw,k))||!Array.isArray(raw.items)||!Number.isInteger(pageSize)||pageSize<1||pageSize>100||raw.items.length>pageSize||typeof raw.has_more!=='boolean'||raw.source!==(mode==='remote_test'?'postgresql':'fixture')||raw.page_size_limit!==100||raw.page_size_limit_kind!=='test_technical_limit')throw bad()
 if(raw.has_more?typeof raw.next_cursor!=='string'||!raw.next_cursor||raw.next_cursor.length>16384||!raw.items.length:raw.next_cursor!==null)throw bad()
 const items=raw.items.map(row=>readAssetSnapshot(row,tenantId));if(new Set(items.map(row=>row.id)).size!==items.length)throw bad()
 return {items,next_cursor:raw.next_cursor as string|null,has_more:raw.has_more}
}
