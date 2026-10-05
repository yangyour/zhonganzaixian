import {isCancelled,type ApiClient} from '../api/client'
import {detachedWorkspaceState} from './detachedWorkspaceState'
import {dashboardBad,dashboardTime,readDashboardSnapshot,validDashboardContext,validDashboardWindow,type DashboardContext,type DashboardSnapshot,type DashboardWindow} from './dashboardAvailability'
export interface DashboardState {current:DashboardSnapshot|null;window:DashboardWindow;busy:boolean;error:unknown}
export const emptyDashboardState=():DashboardState=>({current:null,window:'HOURS_24',busy:false,error:null})
/** Read-only replacement requests. A context/preset change invalidates even transports that ignore abort. */
export function createDashboardWorkspace(client:Pick<ApiClient,'request'>,changed:(state:DashboardState)=>void){
 let context:DashboardContext|null=null,state=emptyDashboardState(),epoch=0,abort:AbortController|null=null,disposed=false,latestTime:bigint|null=null
 const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot())
 function reset(next:DashboardContext|null){abort?.abort();abort=null;++epoch;context=!disposed&&validDashboardContext(next)?{...next}:null;state=emptyDashboardState();latestTime=null;emit()}
 async function load(window:DashboardWindow=state.window){
  if(!context||disposed)return false
  if(!validDashboardWindow(window)){abort?.abort();abort=null;++epoch;state={...state,current:null,busy:false,error:dashboardBad()};emit();return false}
  if(state.busy&&state.window===window)return false
  abort?.abort();const controller=new AbortController(),turn=++epoch,scope={...context};abort=controller;state={current:null,window,busy:true,error:null};emit()
  try{
   if(turn!==epoch||disposed||controller.signal.aborted)return false
   const raw=await client.request<unknown>('/dashboard?'+new URLSearchParams({window}),{method:'GET',signal:controller.signal})
   if(turn!==epoch||disposed||controller.signal.aborted)return false
   const result=readDashboardSnapshot(raw,scope,window),time=dashboardTime(result.generated_at)
   if(latestTime!==null&&time<latestTime)throw dashboardBad()
   latestTime=time;state.current=result;return true
  }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}
  finally{if(turn===epoch&&!disposed){state.busy=false;abort=null;emit()}}
 }
 emit();return{reset,load,state:snapshot,dispose:()=>{disposed=true;reset(null)}}
}
