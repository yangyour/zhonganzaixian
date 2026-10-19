import { ApiError,isCancelled,type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'
import { isUtcInstant } from './utcInstant'
import type { EventSeverity,MonitorState,HandlingState } from './monitoringEvents'
export interface SearchAsset { id:string;tenant_id:string;name:string;type:string;record_source:string;version:number;resource_kind:'ASSET_RECORD' }
export interface SearchTicket { id:string;tenant_id:string;title:string;receipt_no:string;record_source:'customer_report';registration_status:'REGISTERED';version:number;resource_kind:'CUSTOMER_REPORT_REGISTRATION' }
export interface SearchEvent { id:string;tenant_id:string;rule_code:string;rule_name:string;version:number;severity:EventSeverity;monitor_state:MonitorState;handling_state:HandlingState;occurrence_count:number;last_occurred_at:string;resource_kind:'MONITORING_EVENT' }
export interface SearchAvailable<T> { kind:'assets'|'events'|'tickets';status:'AVAILABLE';limit:number;returned:number;has_more:boolean;items:T[];source_scope:string;matched_fields:string[];reason:null }
export interface SearchUnauthorizedEvents { kind:'events';status:'NOT_AUTHORIZED';limit:4;returned:null;has_more:null;items:null;source_scope:null;matched_fields:null;reason:'INTERNAL_EVENT_READ_REQUIRED' }
export interface SearchUnavailable { kind:'automation_scripts';status:'NOT_IMPLEMENTED';limit:number;returned:null;has_more:null;items:null;source_scope:null;matched_fields:null;reason:'SOURCE_NOT_IMPLEMENTED' }
export interface GroupedSearch { query:string;tenant_id:string;observed_at:string;scope:'CURRENT_TENANT_AUTHORIZED_GROUPED_SEARCH';coverage:'PARTIAL';coverage_reason:'AUTOMATION_SOURCE_NOT_IMPLEMENTED'|'AUTOMATION_SOURCE_NOT_IMPLEMENTED_AND_EVENT_NOT_AUTHORIZED';query_semantics:'LITERAL_UNICODE_CASE_INSENSITIVE';snapshot_consistency:'ONE_READONLY_TRANSACTION';source:'postgresql_supported_groups';notification_bell_status:'NOT_IMPLEMENTED';groups:[SearchAvailable<SearchAsset>,SearchAvailable<SearchEvent>|SearchUnauthorizedEvents,SearchAvailable<SearchTicket>,SearchUnavailable] }
export interface SearchContext {tenantId:string;actorId:string;permissionVersion:number;role:string}
export interface SearchState {query:string;result:GroupedSearch|null;busy:boolean;error:unknown}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy=<T>(v:T):T=>JSON.parse(JSON.stringify(v)) as T
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&Boolean(v.trim())&&Array.from(v).length<=max&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)&&!Array.from(v).some(c=>c.length===1&&/[\ud800-\udfff]/.test(c))
// Event text is stored server data; its D15 contract permits embedded whitespace/control characters.
const eventText=(v:unknown,max:number):v is string=>typeof v==='string'&&Boolean(v.trim())&&Array.from(v).length<=max
export const validSearchQuery=(v:unknown):v is string=>text(v,100)
const bad=()=>new ApiError(200,'INVALID_GROUPED_SEARCH_RESPONSE','搜索范围、分组或最小字段回读异常，旧结果已清除','—')
export function readGroupedSearch(v:unknown,context:SearchContext,query:string):GroupedSearch{
 const own=context.role==='viewer'||context.role==='tadmin'
 if(!['viewer','tadmin','msp','padmin'].includes(context.role))throw bad()
 if(!exact(v,['query','tenant_id','observed_at','scope','coverage','coverage_reason','query_semantics','snapshot_consistency','source','notification_bell_status','groups'])||v.query!==query||v.tenant_id!==context.tenantId||v.scope!=='CURRENT_TENANT_AUTHORIZED_GROUPED_SEARCH'||v.coverage!=='PARTIAL'||v.coverage_reason!==(own?'AUTOMATION_SOURCE_NOT_IMPLEMENTED_AND_EVENT_NOT_AUTHORIZED':'AUTOMATION_SOURCE_NOT_IMPLEMENTED')||v.query_semantics!=='LITERAL_UNICODE_CASE_INSENSITIVE'||v.snapshot_consistency!=='ONE_READONLY_TRANSACTION'||v.source!=='postgresql_supported_groups'||v.notification_bell_status!=='NOT_IMPLEMENTED'||!Array.isArray(v.groups)||v.groups.length!==4)throw bad()
 try{if(typeof v.observed_at!=='string'||!v.observed_at.endsWith('Z'))throw bad();worklogInstant(v.observed_at)}catch{throw bad()}
 const kinds=['assets','events','tickets','automation_scripts'],limits=[5,4,3,3]
 v.groups.forEach((g,i)=>{
  if(!exact(g,['kind','status','limit','returned','has_more','items','source_scope','matched_fields','reason'])||g.kind!==kinds[i]||g.limit!==limits[i])throw bad()
  if(i===1&&own){if(g.status!=='NOT_AUTHORIZED'||g.reason!=='INTERNAL_EVENT_READ_REQUIRED'||['returned','has_more','items','source_scope','matched_fields'].some(k=>g[k]!==null))throw bad();return}
  if(i===3){if(g.status!=='NOT_IMPLEMENTED'||g.reason!=='SOURCE_NOT_IMPLEMENTED'||['returned','has_more','items','source_scope','matched_fields'].some(k=>g[k]!==null))throw bad();return}
  const scope=i===0?'CURRENT_TENANT':i===2&&own?'OWN_REPORT_REGISTRATIONS':'CURRENT_TENANT_INTERNAL',fields=i===0?['name']:i===1?['rule_name','rule_code']:['title','receipt_no']
  if(g.status!=='AVAILABLE'||g.reason!==null||!Array.isArray(g.items)||g.items.length>limits[i]!||g.returned!==g.items.length||typeof g.has_more!=='boolean'||g.has_more&&g.items.length!==limits[i]||g.source_scope!==scope||JSON.stringify(g.matched_fields)!==JSON.stringify(fields))throw bad()
  const seen=new Set<string>();g.items.forEach(hit=>{const keys=i===0?['id','tenant_id','name','type','record_source','version','resource_kind']:i===1?['id','tenant_id','rule_code','rule_name','version','severity','monitor_state','handling_state','occurrence_count','last_occurred_at','resource_kind']:['id','tenant_id','title','receipt_no','record_source','registration_status','version','resource_kind'];if(!exact(hit,keys)||typeof hit.id!=='string'||!uuid.test(hit.id)||seen.has(hit.id)||hit.tenant_id!==context.tenantId||!Number.isSafeInteger(hit.version)||Number(hit.version)<1)throw bad();seen.add(hit.id)
   if(i===0){if(!text(hit.name,100)||typeof hit.type!=='string'||!['router','switch','firewall','ap','wireless_controller','other'].includes(hit.type)||!text(hit.record_source,64)||hit.resource_kind!=='ASSET_RECORD')throw bad()}
   else if(i===1){if(!eventText(hit.rule_code,64)||!eventText(hit.rule_name,128)||!['DISASTER','HIGH','AVERAGE','WARNING','INFO'].includes(hit.severity as string)||!['PROBLEM','RECOVERED'].includes(hit.monitor_state as string)||!['NEW','ACKNOWLEDGED','IN_PROGRESS','PENDING_REVIEW','RESOLVED','CLOSED'].includes(hit.handling_state as string)||!Number.isSafeInteger(hit.occurrence_count)||Number(hit.occurrence_count)<1||Number(hit.occurrence_count)>2147483647||!isUtcInstant(hit.last_occurred_at)||hit.resource_kind!=='MONITORING_EVENT')throw bad()}
   else if(!text(hit.title,200)||!text(hit.receipt_no,128)||hit.record_source!=='customer_report'||hit.registration_status!=='REGISTERED'||hit.resource_kind!=='CUSTOMER_REPORT_REGISTRATION')throw bad()
  })
 });return copy(v) as unknown as GroupedSearch
}
export function searchDestination(kind:'assets'|'events'|'tickets',id:string){if(!['assets','events','tickets'].includes(kind)||!uuid.test(id))throw bad();return kind==='assets'?{name:'assets',query:{asset_id:id}}:kind==='events'?{name:'monitoring-events',query:{event_id:id}}:{name:'tickets',query:{ticket_id:id}}}
export function shouldFocusGlobalSearch(event:{key:string;ctrlKey?:boolean;altKey?:boolean;metaKey?:boolean;shiftKey?:boolean;repeat?:boolean;isComposing?:boolean;defaultPrevented?:boolean;target?:unknown}):boolean{
 // WCAG 2.1.4: use a non-character modifier instead of an always-on bare slash.
 if(event.key.toLowerCase()!=='k'||!(event.ctrlKey||event.metaKey)||event.ctrlKey&&event.metaKey||event.altKey||event.shiftKey||event.repeat||event.isComposing||event.defaultPrevented)return false
 const target=event.target as {tagName?:string;isContentEditable?:boolean;closest?:(selector:string)=>unknown}|null
 return !target?.isContentEditable&&!['INPUT','TEXTAREA','SELECT'].includes(typeof target?.tagName==='string'?target.tagName.toUpperCase():'')&&!target?.closest?.('[contenteditable]:not([contenteditable="false"]), [role="textbox"], [role="dialog"], [role="alertdialog"], [aria-modal="true"]')
}
export function createGlobalSearchWorkspace(client:Pick<ApiClient,'request'>,notify:(state:SearchState)=>void){
 let context:SearchContext|null=null,generation=0,controller:AbortController|null=null,disposed=false
 let state:SearchState={query:'',result:null,busy:false,error:null};const snapshot=()=>({...state,result:state.result?copy(state.result):null});const emit=()=>notify(snapshot())
 const cancel=()=>{generation++;controller?.abort();controller=null;state.result=null;state.busy=false;state.error=null}
 return {state:snapshot,reset(scope:SearchContext|null){cancel();context=!disposed&&scope&&uuid.test(scope.tenantId)&&uuid.test(scope.actorId)&&Number.isSafeInteger(scope.permissionVersion)&&scope.permissionVersion>0&&['viewer','tadmin','msp','padmin'].includes(scope.role)?{...scope}:null;state.query='';emit()},change(value:string){if(disposed)return;cancel();state.query=value;emit()},clear(){cancel();state.query='';emit()},async search(){if(!context||disposed||state.busy)return false;cancel();if(!validSearchQuery(state.query)){state.error=new Error('搜索词需为1至100个非空白Unicode字符，不接受控制字符');emit();return false}const current=generation,scope={...context},query=state.query;controller=new AbortController();state.busy=true;emit();try{const raw=await client.request('/search?'+new URLSearchParams({q:query}),{signal:controller.signal});if(current!==generation||disposed)return false;state.result=readGroupedSearch(raw,scope,query);return true}catch(error){if(current===generation&&!disposed&&!isCancelled(error))state.error=error;return false}finally{if(current===generation&&!disposed){state.busy=false;controller=null;emit()}}},dispose(){cancel();disposed=true;context=null;state.query='';emit()}}
}
