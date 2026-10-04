import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { isUtcInstant } from './utcInstant'
import { displayTime } from './display'

export const tenantUnknownFields = ['isolation_mode','resource_pool','lifecycle_status','asset_quota','monitored_item_count','item_quota','nvps','nvps_quota','data_retention_days','itsm_integration','topology_enabled'] as const
export interface TenantInventoryRow extends Record<typeof tenantUnknownFields[number], null> { id: string; name: string; created_at: string; registered_asset_count: string; registered_site_count: string; active_member_count: string }
export interface TenantInventoryPage { context_tenant_id: string; items: TenantInventoryRow[]; has_more: boolean; next_after_id: string | null; source: 'postgresql'; scope: 'ACTIVE_PADMIN_MEMBERSHIPS_ONLY'; page_size_limit: 100; pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY'; count_basis: 'RECORDED_ROWS_NOT_MONITORED_OR_LICENSED_USAGE'; unknown_fields_policy: 'EXPLICIT_NULL_NO_INFERENCE'; new_tenant_creation_enabled: false }
export interface TenantInventoryContext { tenantId: string; actorId: string; permissionVersion: number; role: string }
export interface TenantInventoryState { page: TenantInventoryPage | null; busy: boolean; error: unknown; canPrevious: boolean; paged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value,k))
const bad = () => new ApiError(200,'INVALID_TENANT_INVENTORY','租户登记资源范围、时间或计数回读异常，未显示未经核对的结果','—')
export const isInventoryCount = (value: unknown): value is string => typeof value === 'string' && /^(?:0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
export function readTenantInventory(value: unknown, context: TenantInventoryContext, after: string | null): TenantInventoryPage {
  if(!uuid.test(context.tenantId)||!uuid.test(context.actorId)||context.role!=='padmin'||!Number.isSafeInteger(context.permissionVersion)||context.permissionVersion<1||after!==null&&!uuid.test(after))throw bad()
  if(!exact(value,['context_tenant_id','items','has_more','next_after_id','source','scope','page_size_limit','pagination_consistency','count_basis','unknown_fields_policy','new_tenant_creation_enabled']) || value.context_tenant_id!==context.tenantId || value.source!=='postgresql' || value.scope!=='ACTIVE_PADMIN_MEMBERSHIPS_ONLY' || value.page_size_limit!==100 || value.pagination_consistency!=='CURRENT_REQUEST_SNAPSHOT_ONLY' || value.count_basis!=='RECORDED_ROWS_NOT_MONITORED_OR_LICENSED_USAGE' || value.unknown_fields_policy!=='EXPLICIT_NULL_NO_INFERENCE' || value.new_tenant_creation_enabled!==false || !Array.isArray(value.items) || value.items.length>15 || typeof value.has_more!=='boolean')throw bad()
  let previous=after
  for(const row of value.items){
    if(!exact(row,['id','name','created_at','registered_asset_count','registered_site_count','active_member_count',...tenantUnknownFields]) || typeof row.id!=='string' || !uuid.test(row.id) || previous!==null&&row.id<=previous || typeof row.name!=='string' || !row.name.trim() || Array.from(row.name).length>100 || !isUtcInstant(row.created_at) || !['registered_asset_count','registered_site_count','active_member_count'].every(key=>isInventoryCount(row[key])) || !tenantUnknownFields.every(key=>row[key]===null))throw bad()
    previous=row.id
  }
  if(value.has_more ? value.items.length!==15 || value.next_after_id!==previous : value.next_after_id!==null)throw bad()
  return structuredClone(value) as unknown as TenantInventoryPage
}
export const emptyTenantInventory = (): TenantInventoryState => ({page:null,busy:false,error:null,canPrevious:false,paged:false})
export function createTenantInventoryReader(client: Pick<ApiClient,'request'>, changed: (state: TenantInventoryState)=>void) {
  let context:TenantInventoryContext|null=null,state=emptyTenantInventory(),generation=0,controller:AbortController|undefined,boundary:string|null=null,history:(string|null)[]=[]
  const emit=()=>changed(detachedWorkspaceState(state))
  function reset(next:TenantInventoryContext|null){controller?.abort();++generation;context=next&&uuid.test(next.tenantId)&&uuid.test(next.actorId)&&Number.isSafeInteger(next.permissionVersion)&&next.permissionVersion>0&&next.role==='padmin'?{...next}:null;boundary=null;history=[];state=emptyTenantInventory();if(next&&!context)state.error=new Error('当前成员无租户登记资源读取权限');emit()}
  async function load(after:string|null,past:(string|null)[]){
    if(!context||state.busy)return false
    const scope={...context},turn=++generation;controller?.abort();const abort=new AbortController();controller=abort
    state={...state,page:null,busy:true,error:null};emit()
    try {const params=new URLSearchParams({page_size:'15'});if(after!==null)params.set('after_id',after)
      const page=readTenantInventory(await client.request('/tenants/inventory?'+params,{signal:abort.signal}),scope,after)
      if(turn!==generation||abort.signal.aborted)return false
      boundary=after;history=[...past];state={...state,page,canPrevious:history.length>0,paged:after!==null};return true
    }catch(error){if(turn===generation&&!isCancelled(error))state={...state,error};return false}
    finally{if(turn===generation){state={...state,busy:false};emit()}}
  }
  emit();return {reset,refresh:()=>load(null,[]),next:()=>state.page?.has_more&&!state.busy?load(state.page.next_after_id,[...history,boundary]):Promise.resolve(false),previous:()=>history.length&&!state.busy?load(history.at(-1)!,history.slice(0,-1)):Promise.resolve(false),dispose:()=>reset(null),state:()=>detachedWorkspaceState(state)}
}
export const tenantInventoryColumns=[
  {key:'name',label:'租户名称'},{key:'isolation',label:'隔离模式·资源池'},{key:'status',label:'状态'},
  {key:'assets',label:'资产数·配额'},{key:'items',label:'监控项Item数·配额'},{key:'nvps',label:'NVPS·配额'},
  {key:'sites',label:'站点数'},{key:'retention',label:'数据保留期'},{key:'members',label:'成员数'},
  {key:'itsm',label:'ITSM对接方式'},{key:'created',label:'创建时间'},{key:'topology',label:'是否开通网络拓扑'},
] as const
export type TenantInventoryColumn = typeof tenantInventoryColumns[number]['key']
export function tenantInventoryCell(row:TenantInventoryRow,key:TenantInventoryColumn):{text:string;detail?:string}{
  switch(key){
    case 'name':return {text:row.name,detail:row.id}
    case 'assets':return {text:row.registered_asset_count+' 条登记',detail:'配额未知；含草稿，不是已采集或计费数量'}
    case 'sites':return {text:row.registered_site_count,detail:'登记站点，不表示Proxy在线'}
    case 'members':return {text:row.active_member_count,detail:'活跃用户且成员关系有效；不是在线人数'}
    case 'created':return {text:displayTime(row.created_at),detail:row.created_at}
    case 'items':case 'nvps':return {text:'未知 / 配额未知',detail:'当前未接入有效监控或配额来源'}
    case 'status':return {text:'未知',detail:'不能从登记记录推断活跃或接入中'}
    default:return {text:'未确认',detail:'接口明确null，未采用默认值'}
  }
}
