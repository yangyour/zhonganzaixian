import {isCancelled,type ApiClient} from '../api/client'
import {detachedWorkspaceState} from './detachedWorkspaceState'
import {readWorklogHistory,worklogInstant,type WorklogEntry} from './worklogWorkspace'
import {emptyOwnWorklogFilters,ownWorklogBad,ownWorklogQuery,ownWorklogUuid,readOwnWorklogEntry,readOwnWorklogFilters,readOwnWorklogPage,validOwnWorklogContext,type OwnWorklogContext,type OwnWorklogFilters,type OwnWorklogPage,type OwnWorklogCursor} from './ownWorklogBrowse'
type ReadRequest={kind:'LIST';filters:OwnWorklogFilters;next:boolean;before?:OwnWorklogCursor}|{kind:'DETAIL';baseline:WorklogEntry;more:boolean;boundary:number|null}
export interface OwnWorklogBrowserState {page:OwnWorklogPage|null;filters:OwnWorklogFilters|null;selected:WorklogEntry|null;history:ReturnType<typeof readWorklogHistory>|null;busy:'list'|'detail'|null;paged:boolean;historyRestarted:boolean;error:unknown}
export const emptyOwnWorklogBrowserState=():OwnWorklogBrowserState=>({page:null,filters:null,selected:null,history:null,busy:null,paged:false,historyRestarted:false,error:null})
/** GET-only workspace. Draft filters, pages and record scope never grant team access or write authority. */
export function createOwnWorklogBrowser(client:Pick<ApiClient,'request'>,changed:(s:OwnWorklogBrowserState)=>void){
 let lastRead:ReadRequest|null=null
 let scope:OwnWorklogContext|null=null,state=emptyOwnWorklogBrowserState(),epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot())
 function invalidate(){abort?.abort();abort=null;++epoch;lastRead=null;state=emptyOwnWorklogBrowserState();emit()}
 function reset(c:OwnWorklogContext|null){scope=!disposed&&validOwnWorklogContext(c)?{...c}:null;invalidate()}
 async function list(input:unknown,next=false,recovery?:Extract<ReadRequest,{kind:'LIST'}>){
  if(!scope||disposed||next&&state.busy)return false
  const before=recovery?recovery.before:next&&state.page?.has_more?{time:state.page.next_before_created_at!,id:state.page.next_before_id!}:undefined;if(next&&!before)return false
  let filters:OwnWorklogFilters,path:string;try{filters=readOwnWorklogFilters(input);if(next&&JSON.stringify(filters)!==JSON.stringify(state.filters))throw ownWorklogBad();path=ownWorklogQuery(filters,before)}catch(error){invalidate();state.error=error;emit();return false}
  lastRead=structuredClone({kind:'LIST',filters,next,before});abort?.abort();const turn=++epoch,context={...scope},controller=new AbortController();abort=controller;state={...emptyOwnWorklogBrowserState(),filters,busy:'list',paged:next};emit()
  try{if(turn!==epoch||disposed||controller.signal.aborted)return false;const raw=await client.request<unknown>(path,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false;state.page=readOwnWorklogPage(raw,context,filters,before);lastRead=null;return true}catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 async function show(id:string,more=false,recovery?:Extract<ReadRequest,{kind:'DETAIL'}>){
  if(!scope||disposed||state.busy||!ownWorklogUuid(id))return false
  const found=recovery?.baseline??(state.selected?.id===id?state.selected:state.page?.items.find(v=>v.id===id));if(!found||!recovery&&more&&(!state.history?.has_more||state.selected?.id!==id))return false
  const baseline=readOwnWorklogEntry(found,scope),boundary=recovery?recovery.boundary:more?state.history!.next_before_version:null,context={...scope},turn=++epoch,controller=new AbortController();lastRead=structuredClone({kind:'DETAIL',baseline,more,boundary});abort=controller;state={...state,selected:null,history:null,busy:'detail',historyRestarted:false,error:null};emit()
  try{
   if(turn!==epoch||disposed||controller.signal.aborted)return false;const path='/tickets/'+baseline.ticket_id+'/worklogs/'+id,raw=await client.request<unknown>(path,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   const row=readOwnWorklogEntry(raw,context);if(row.id!==id||row.ticket_id!==baseline.ticket_id||row.created_at!==baseline.created_at||row.version<baseline.version||worklogInstant(row.updated_at)<worklogInstant(baseline.updated_at)||row.version===baseline.version&&Object.keys(row).some(k=>row[k as keyof WorklogEntry]!==baseline[k as keyof WorklogEntry]))throw ownWorklogBad()
   const before=more&&row.version===baseline.version?boundary:null;lastRead=structuredClone({kind:'DETAIL',baseline:row,more,boundary:before});const historyRaw=await client.request<unknown>(path+'/history?page_size=15'+(before?'&before_version='+before:''),{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   const history=readWorklogHistory(historyRaw,{tenantId:context.tenantId,actorId:context.actorId,ticketId:row.ticket_id,permissionVersion:context.permissionVersion,canWrite:false},row,before);state={...state,selected:row,history,historyRestarted:more&&before===null};lastRead=null;return true
  }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 function closeDetails(){if(!scope||disposed||state.busy==='list')return false;lastRead=null;if(state.busy==='detail'){abort?.abort();abort=null;++epoch}state={...state,selected:null,history:null,busy:null,historyRestarted:false,error:null};emit();return true}
 function canRetryRead(){return !!scope&&!disposed&&!state.busy&&lastRead!==null}
 function cancelRead(){if(disposed||!state.busy)return false;++epoch;abort?.abort();abort=null;state={...state,busy:null,error:null};emit();return true}
 async function retryRead(){if(!canRetryRead()||!lastRead)return false;const request=structuredClone(lastRead);return request.kind==='LIST'?list(request.filters,request.next,request):show(request.baseline.id,request.more,request)}
 emit();return{cancelRead,retryRead,canRetryRead,reset,invalidate,closeDetails,apply:(v:unknown)=>list(v),refresh:()=>list(state.filters??emptyOwnWorklogFilters()),next:()=>state.filters?list(state.filters,true):Promise.resolve(false),show:(id:string)=>show(id),moreHistory:()=>state.selected?show(state.selected.id,true):Promise.resolve(false),state:snapshot,dispose:()=>{disposed=true;reset(null)}}
}
