import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {readWorkflow,workflowLabels,type TicketWorkflow,type WorkflowState} from './ticketWorkflow'
import {utcInstantNanos,isUtcInstant} from './utcInstant'
export interface DashboardWorkflowContext {tenantId:string;actorId:string;permissionVersion:number;role:'padmin'|'msp'|'tadmin'|'viewer'}
export interface DashboardWorkflowPage {items:TicketWorkflow[];has_more:boolean;next_cursor:string|null}
export interface DashboardWorkflowState {filter:WorkflowState;page:DashboardWorkflowPage|null;pageNumber:number;canPrevious:boolean;canRetry:boolean;busy:boolean;cancelled:boolean;error:unknown}
export const emptyDashboardWorkflows=():DashboardWorkflowState=>({filter:'OPEN',page:null,pageNumber:1,canPrevious:false,canRetry:false,busy:false,cancelled:false,error:null})
const uuid=(v:unknown)=>typeof v==='string'&&v.length===36&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const valid=(c:DashboardWorkflowContext|null):c is DashboardWorkflowContext=>!!c&&uuid(c.tenantId)&&uuid(c.actorId)&&Number.isSafeInteger(c.permissionVersion)&&c.permissionVersion>0&&['padmin','msp','tadmin','viewer'].includes(c.role)
const bad=()=>new ApiError(200,'INVALID_DASHBOARD_WORKFLOWS','工单工作流的范围、状态、时间或分页未通过核对，未显示旧数据。','—')
export function readDashboardWorkflows(raw:unknown,c:DashboardWorkflowContext,filter:WorkflowState):DashboardWorkflowPage{
 if(!valid(c)||!Object.hasOwn(workflowLabels,filter)||!raw||typeof raw!=='object'||Array.isArray(raw))throw bad()
 const r=raw as Record<string,unknown>
 if(Object.keys(r).length!==4||!['items','source','has_more','next_cursor'].every(k=>Object.hasOwn(r,k))||r.source!=='postgresql'||!Array.isArray(r.items)||r.items.length>5||typeof r.has_more!=='boolean'||(r.has_more?r.items.length!==5||typeof r.next_cursor!=='string'||!r.next_cursor||r.next_cursor.length>4096:r.next_cursor!==null))throw bad()
 const items=r.items.map(value=>{
  const id=(value as {ticket_id?:unknown})?.ticket_id;if(!uuid(id))throw bad()
  const row=readWorkflow(value,{...c,ticketId:id as string,customer:['viewer','tadmin'].includes(c.role)})
  if(row.version<1||row.state!==filter||[row.registration_created_at,row.updated_at,row.observed_at,row.accepted_at,row.resolved_at,row.closed_at,row.confirm_due_at,row.response_due_at,row.recovery_due_at].some(t=>t!==null&&!isUtcInstant(t)))throw bad()
  return row
 })
 if(new Set(items.map(row=>row.ticket_id)).size!==items.length||items.some((row,index)=>{const previous=items[index-1];return previous&&(utcInstantNanos(row.registration_created_at)!>utcInstantNanos(previous.registration_created_at)!||utcInstantNanos(row.registration_created_at)===utcInstantNanos(previous.registration_created_at)&&row.ticket_id>=previous.ticket_id)}))throw bad()
 return {items,has_more:r.has_more,next_cursor:r.next_cursor as string|null}
}
export function createDashboardWorkflows(client:Pick<ApiClient,'request'>,changed:(s:DashboardWorkflowState)=>void){
 let context:DashboardWorkflowContext|null=null,state=emptyDashboardWorkflows(),epoch=0,disposed=false,abort:AbortController|null=null,cursor:string|null=null,history:(string|null)[]=[]
 let pending:{filter:WorkflowState;cursor:string|null;history:(string|null)[]}|null=null
 const emit=()=>changed({...state,page:state.page?structuredClone(state.page):null})
 function reset(c:DashboardWorkflowContext|null){epoch++;abort?.abort();abort=null;context=!disposed&&valid(c)?{...c}:null;state=emptyDashboardWorkflows();cursor=null;history=[];pending=null;emit()}
 async function load(filter:WorkflowState,target:string|null,past:(string|null)[]){
  if(disposed||!context||state.busy||!Object.hasOwn(workflowLabels,filter))return false
  const owner={...context},turn=++epoch;abort?.abort();abort=new AbortController();const signal=abort.signal
  pending={filter,cursor:target,history:[...past]};state={...state,filter,page:null,busy:true,error:null,cancelled:false,canRetry:false};emit()
  try{const params=new URLSearchParams({state:filter,limit:'5'});if(target)params.set('cursor',target);const raw=await client.request('/ticket-workflows?'+params,{signal});if(disposed||turn!==epoch||signal.aborted)return false
   const page=readDashboardWorkflows(raw,owner,filter);cursor=target;history=[...past];state={...state,page,pageNumber:past.length+1,canPrevious:past.length>0};return true
  }catch(error){if(!disposed&&turn===epoch&&!isCancelled(error))state={...state,error,canRetry:true};return false}
  finally{if(!disposed&&turn===epoch){abort=null;state={...state,busy:false};emit()}}
 }
 function first(filter:WorkflowState){if(disposed||!context||state.busy||!Object.hasOwn(workflowLabels,filter))return Promise.resolve(false);cursor=null;history=[];state={...state,pageNumber:1,canPrevious:false};return load(filter,null,[])}
 emit();return {reset,refresh:()=>first(state.filter),select:first,next:()=>state.page?.has_more?load(state.filter,state.page.next_cursor,[...history,cursor]):Promise.resolve(false),previous:()=>history.length?load(state.filter,history.at(-1)!,history.slice(0,-1)):Promise.resolve(false),retry:()=>state.canRetry&&pending?load(pending.filter,pending.cursor,[...pending.history]):Promise.resolve(false),cancel(){if(!state.busy)return;epoch++;abort?.abort();abort=null;state={...state,busy:false,page:null,error:null,cancelled:true,canRetry:true};emit()},dispose(){disposed=true;reset(null)}}
}
