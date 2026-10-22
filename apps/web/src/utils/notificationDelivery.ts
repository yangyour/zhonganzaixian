import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {worklogInstant} from './worklogWorkspace'
import type {EmailDelivery} from './email'
import type {WeComDelivery} from './wecom'
export interface ChannelActivity{scopeKey:string;channel:'email'|'wecom';messageId:string;blocked:boolean}
export type NotificationDelivery=EmailDelivery|WeComDelivery
export interface DeliveryScope{channel:'email'|'wecom';tenantId:string;actorId:string;messageId:string}
export interface DeliveryReadState{current:NotificationDelivery|null;busy:boolean;error:unknown}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy=<T>(v:T):T=>structuredClone(v)
const bad=()=>new ApiError(200,'INVALID_NOTIFICATION_DELIVERY','通道回执范围、状态或原记录校验不一致；不能据此确认实际送达','—')
const text=(v:unknown,max:number)=>typeof v==='string'&&v.length>0&&v.length<=max&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)
const date=(v:unknown)=>{try{if(typeof v!=='string'||!v.endsWith('Z'))throw bad();return worklogInstant(v)}catch{throw bad()}}
const validScope=(s:DeliveryScope)=>['email','wecom'].includes(s.channel)&&[s.tenantId,s.actorId,s.messageId].every(v=>uuid.test(v))
export function readNotificationDelivery(raw:unknown,scope:DeliveryScope,expected?:NotificationDelivery):NotificationDelivery{
 const keys=['id','tenant_id','actor_id','source_message_id','recipient','kind','status','version','error_code','created_at','started_at','finished_at',...(scope.channel==='email'?['sender','message_id','smtp_code']:['provider_message_id','provider_code'])]
 if(!validScope(scope)||!raw||typeof raw!=='object'||Array.isArray(raw))throw bad()
 const v=raw as Record<string,unknown>
 if(Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k))||!uuid.test(String(v.id))||v.tenant_id!==scope.tenantId||v.actor_id!==scope.actorId||v.source_message_id!==scope.messageId||v.kind!=='NOTIFICATION'||!['QUEUED','PROCESSING','ACCEPTED','FAILED','UNKNOWN','CANCELLED'].includes(String(v.status))||!Number.isSafeInteger(v.version))throw bad()
 const email=(value:unknown)=>text(value,254)&&value===String(value).toLowerCase()&&/^[^\s<>;,]+@[^\s<>;,]+\.[^\s<>;,]+$/.test(String(value))
 if(scope.channel==='email'?!email(v.sender)||!email(v.recipient):!text(v.recipient,64)||!/^[A-Za-z0-9_.@-]{1,64}$/.test(String(v.recipient))||String(v.recipient).toLowerCase()==='@all')throw bad()
 if(v.error_code!==null&&!text(v.error_code,80))throw bad()
 const created=date(v.created_at),started=v.started_at===null?null:date(v.started_at),finished=v.finished_at===null?null:date(v.finished_at)
 if(started!==null&&started<created||finished!==null&&(started===null||finished<started))throw bad()
 const version=v.status==='QUEUED'?0:v.status==='PROCESSING'?1:2
 if(v.version!==version||version===0&&(started!==null||finished!==null)||version===1&&(started===null||finished!==null)||version===2&&(started===null||finished===null))throw bad()
 const provider=v[scope.channel==='email'?'message_id':'provider_message_id'],code=v[scope.channel==='email'?'smtp_code':'provider_code']
 if(code!==null&&(!Number.isSafeInteger(code)||Number(code)<-2147483648||Number(code)>2147483647))throw bad()
 if(scope.channel==='email'&&code!==null&&(Number(code)<200||Number(code)>599))throw bad()
 if(v.status==='ACCEPTED'?(!text(provider,512)||v.error_code!==null||scope.channel==='email'&&(code===null||Number(code)>299)):provider!==null)throw bad()
 const result=copy(v) as unknown as NotificationDelivery
 if(expected){const prior=readNotificationDelivery(expected,scope);const immutable=['id','tenant_id','actor_id','source_message_id','recipient','kind',...(scope.channel==='email'?['sender']:[])];if(immutable.some(k=>v[k]!==((prior as unknown) as Record<string,unknown>)[k])||date(prior.created_at)!==created||result.version<prior.version||prior.started_at!==null&&(started===null||date(prior.started_at)!==started))throw bad();if(result.version===prior.version&&keys.some(k=>k.endsWith('_at')?(v[k]===null?((prior as unknown) as Record<string,unknown>)[k]!==null:((prior as unknown) as Record<string,unknown>)[k]===null||date(v[k])!==date(((prior as unknown) as Record<string,unknown>)[k])):v[k]!==((prior as unknown) as Record<string,unknown>)[k]))throw bad()}
 return result
}
export function createNotificationDeliveryReader(client:Pick<ApiClient,'request'>,notify:(s:DeliveryReadState)=>void){
 let scope:DeliveryScope|null=null,baseline:NotificationDelivery|null=null,epoch=0,disposed=false,controller:AbortController|undefined,retry=false,state:DeliveryReadState={current:null,busy:false,error:null}
 const emit=()=>notify({...state,current:state.current?copy(state.current):null})
 function reset(next:DeliveryScope|null){epoch++;controller?.abort();controller=undefined;scope=!disposed&&next&&validScope(next)?{...next}:null;baseline=null;retry=false;state={current:null,busy:false,error:null};emit()}
 async function load(seed?:NotificationDelivery){
  if(disposed||!scope||state.busy)return false
  const owner={...scope},turn=++epoch,active=new AbortController();controller=active
  try{if(seed)baseline=readNotificationDelivery(seed,owner,baseline??undefined);if(!baseline)return false;const expected=copy(baseline);retry=true;state={current:null,busy:true,error:null};emit();if(turn!==epoch||disposed||active.signal.aborted)return false
   const raw=await client.request('/'+owner.channel+'/deliveries/'+expected.id,{signal:active.signal});if(turn!==epoch||disposed||active.signal.aborted)return false
   const value=readNotificationDelivery(raw,owner,expected);baseline=copy(value);state.current=value;retry=false;return true
  }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error)){state.current=null;state.error=error}return false}
  finally{if(turn===epoch&&!disposed){state.busy=false;controller=undefined;emit()}}
 }
 function cancelRead(){if(disposed||!state.busy)return false;epoch++;controller?.abort();controller=undefined;state={current:null,busy:false,error:null};emit();return true}
 const canRetryRead=()=>!!scope&&!disposed&&!state.busy&&retry&&baseline!==null
 return{reset,load,cancelRead,canRetryRead,retryRead:()=>canRetryRead()?load():Promise.resolve(false),state:()=>({...state,current:state.current?copy(state.current):null}),dispose(){disposed=true;reset(null)}}
}
