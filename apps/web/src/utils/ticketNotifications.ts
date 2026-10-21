import {ApiError,type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportTime,exportUuid} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'

/** In-app ticket notifications only. External channels remain NOT_CONFIGURED until Q14/Q18 are confirmed. */
export interface TicketNotification {id:string;ticket_id:string;audience:'CUSTOMER'|'INTERNAL';event_type:string;title:string;body:string;created_at:string;version:0|1;read_at:string|null;channel:'IN_APP';source:'postgresql'}
export interface TicketNotificationPage {items:TicketNotification[];has_more:boolean;next_before_created_at:string|null;next_before_id:string|null;unread_count:number;observed_at:string;external_dispatch:'NOT_CONFIGURED';source:'postgresql'}
export interface NotificationIntent {id:string;ticket_id:string;receipt_no:string;event_type:string;phase:string;priority:'P1'|'P2'|'P3'|null;recipient_status:'COMPLETE'|'PARTIAL'|'PENDING_RULE';recipient_count:number;pending_reasons:string[];external_dispatch:'NOT_CONFIGURED';external_blockers:string[];created_at:string}
export interface NotificationIntentPage {items:NotificationIntent[];has_more:boolean;next_before_created_at:string|null;next_before_id:string|null;observed_at:string;source:'postgresql'}
export interface NotificationContext {tenantId:string;actorId:string;permissionVersion:number;internal:boolean}
export interface NotificationReceipt {id:string;version:1;read_at:string}
type Attempt={id:string;key:string;body:{expected_version:0}}
interface Cursor {time:string;id:string}
export interface NotificationState {page:TicketNotificationPage|null;unreadOnly:boolean;receipt:NotificationReceipt|null;attempt:Attempt|null;busy:'read'|'write'|null;error:unknown;intents:NotificationIntentPage|null;intentError:unknown;intentBusy:boolean}
export const emptyNotificationState=():NotificationState=>({page:null,unreadOnly:false,receipt:null,attempt:null,busy:null,error:null,intents:null,intentError:null,intentBusy:false})
export const PAGE_SIZE=20
const invalid=()=>new ApiError(0,'INVALID_TICKET_NOTIFICATION','通知内容未通过范围或完整性校验，请刷新核对','—')
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&v.trim().length>0&&[...v].length<=max
const older=(time:string,id:string,before:Cursor)=>exportTime(time)<exportTime(before.time)||exportTime(time)===exportTime(before.time)&&id<before.id
const validContext=(c:NotificationContext|null):c is NotificationContext=>Boolean(c&&exportUuid(c.tenantId)&&exportUuid(c.actorId)&&exportInteger(c.permissionVersion,1)&&typeof c.internal==='boolean')

export const eventLabels:Record<string,string>={opened:'建立工单',accepted:'已接单',started:'开始处理',reassigned:'转派',resolved:'待确认',confirmation_reminder:'确认提醒',closed:'客户确认关闭',auto_closed:'超时自动关闭',reopened:'客户重开',manager_review_required:'经理复核',cancelled:'已撤销',sla_50:'SLA 50%',sla_80:'SLA 80%',sla_100:'SLA 100%'}
export const pendingReasonLabels:Record<string,string>={Q15_ONCALL_QUEUE_RECIPIENTS:'值班/派单接收对象待客户确认（Q15）',Q15_ESCALATION_CHAIN:'超时升级链待客户确认（Q15）',Q09_SERVICE_MANAGER_ROLE:'服务经理角色待客户确认（Q09）',UNMAPPED_EVENT_TYPE:'事件类型尚未登记接收规则',RECIPIENT_INACTIVE:'原接收人已停用，未替换他人'}
export const blockerLabels:Record<string,string>={Q14_CHANNEL_MATRIX:'渠道×级别矩阵待确认（Q14）',Q18_PROVIDER_CREDENTIALS:'通道凭据待提供（Q18）'}
export const eventLabel=(type:string)=>eventLabels[type.replace(/^ticket\./,'')]??type

export function readNotificationPage(value:unknown,scope:NotificationContext,before?:Cursor,unreadOnly=false):TicketNotificationPage{
 if(!validContext(scope)||!exportExact(value,['items','has_more','next_before_created_at','next_before_id','unread_count','observed_at','external_dispatch','source'])||!Array.isArray(value.items)||value.items.length>PAGE_SIZE||typeof value.has_more!=='boolean'||!exportInteger(value.unread_count)||value.external_dispatch!=='NOT_CONFIGURED'||value.source!=='postgresql')throw invalid()
 const observed=exportTime(value.observed_at),seen=new Set<string>();let previous=before
 for(const m of value.items){
  if(!exportExact(m,['id','ticket_id','audience','event_type','title','body','created_at','version','read_at','channel','source'])||!exportUuid(m.id)||seen.has(m.id)||!exportUuid(m.ticket_id)||!['CUSTOMER','INTERNAL'].includes(m.audience as string)||typeof m.event_type!=='string'||!/^ticket\.[a-z0-9_]{1,64}$/.test(m.event_type)||!text(m.title,160)||!text(m.body,600)||!exportInteger(m.version,0,1)||m.channel!=='IN_APP'||m.source!=='postgresql')throw invalid()
  if(m.audience==='INTERNAL'&&!scope.internal||m.audience==='CUSTOMER'&&scope.internal)throw invalid()
  const created=exportTime(m.created_at);if(created>observed||(m.version===0)!==(m.read_at===null)||unreadOnly&&m.read_at!==null)throw invalid()
  if(m.read_at!==null&&(exportTime(m.read_at)<created||exportTime(m.read_at)>observed))throw invalid()
  if(previous&&!older(m.created_at as string,m.id,previous))throw invalid();previous={time:m.created_at as string,id:m.id};seen.add(m.id)
 }
 if(value.has_more){const last=value.items.at(-1);if(value.items.length!==PAGE_SIZE||!last||value.next_before_id!==last.id||value.next_before_created_at!==last.created_at)throw invalid()}
 else if(value.next_before_id!==null||value.next_before_created_at!==null)throw invalid()
 const visibleUnread=value.items.filter(m=>m.read_at===null).length
 if(value.unread_count<visibleUnread||!before&&!value.has_more&&value.unread_count!==visibleUnread)throw invalid()
 return structuredClone(value) as unknown as TicketNotificationPage
}
export function readIntentPage(value:unknown,before?:Cursor):NotificationIntentPage{
 if(!exportExact(value,['items','has_more','next_before_created_at','next_before_id','observed_at','source'])||!Array.isArray(value.items)||value.items.length>25||typeof value.has_more!=='boolean'||value.source!=='postgresql')throw invalid()
 const observed=exportTime(value.observed_at);let previous=before;const seen=new Set<string>()
 for(const i of value.items){
  if(!exportExact(i,['id','ticket_id','receipt_no','event_type','phase','priority','recipient_status','recipient_count','pending_reasons','external_dispatch','external_blockers','created_at'])||!exportUuid(i.id)||seen.has(i.id)||!exportUuid(i.ticket_id)||!text(i.receipt_no,64)||typeof i.event_type!=='string'||!/^ticket\.[a-z0-9_]{1,64}$/.test(i.event_type)||typeof i.phase!=='string'||!/^[A-Z][A-Z0-9_]{0,63}$/.test(i.phase)||!(i.priority===null||['P1','P2','P3'].includes(i.priority as string))||!['COMPLETE','PARTIAL','PENDING_RULE'].includes(i.recipient_status as string)||!exportInteger(i.recipient_count,0,100)||i.external_dispatch!=='NOT_CONFIGURED')throw invalid()
  if(!Array.isArray(i.pending_reasons)||!i.pending_reasons.every(r=>typeof r==='string')||!Array.isArray(i.external_blockers)||!i.external_blockers.includes('Q14_CHANNEL_MATRIX')||!i.external_blockers.includes('Q18_PROVIDER_CREDENTIALS'))throw invalid()
  if((i.recipient_status==='COMPLETE')!==(i.pending_reasons.length===0)||(i.recipient_status==='PENDING_RULE')!==(i.recipient_count===0)||exportTime(i.created_at)>observed)throw invalid()
  if(previous&&!older(i.created_at as string,i.id,previous))throw invalid();previous={time:i.created_at as string,id:i.id};seen.add(i.id)
 }
 if(value.has_more){const last=value.items.at(-1);if(value.items.length!==25||!last||value.next_before_id!==last.id||value.next_before_created_at!==last.created_at)throw invalid()}
 else if(value.next_before_id!==null||value.next_before_created_at!==null)throw invalid()
 return structuredClone(value) as unknown as NotificationIntentPage
}

export function createNotificationWorkspace(client:{request<T>(path:string,options?:RequestOptions):Promise<T>},changed:(s:NotificationState)=>void,newKey=createRequestId){
 let state=emptyNotificationState(),context:NotificationContext|null=null,epoch=0,abort:AbortController|null=null,disposed=false,intentEpoch=0,intentAbort:AbortController|null=null
 let lastRead:{unreadOnly:boolean;before:Cursor|undefined}|null=null,lastIntent:{before:Cursor|undefined}|null=null
 const snapshot=()=>({...structuredClone({...state,error:null,intentError:null}),error:state.error,intentError:state.intentError}),emit=()=>changed(snapshot())
 function reset(next:NotificationContext|null){lastRead=null;lastIntent=null;epoch++;intentEpoch++;intentAbort?.abort();intentAbort=null;abort?.abort();abort=null;context=!disposed&&validContext(next)?{...next}:null;state=emptyNotificationState();emit()}
 async function list(unreadOnly=state.unreadOnly,next=false){
  if(disposed||!context||state.busy||state.attempt)return false
  const before=next&&state.page?.has_more?{time:state.page.next_before_created_at!,id:state.page.next_before_id!}:undefined
  if(next&&!before)return false
  return readList(unreadOnly,before)
 }
 async function readList(unreadOnly:boolean,before?:Cursor){
  if(disposed||!context||state.busy||state.attempt)return false
  lastRead={unreadOnly,before:before?{...before}:undefined}
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,page:null,busy:'read',error:null,unreadOnly};emit()
  try{
   const query=new URLSearchParams({page_size:String(PAGE_SIZE),unread_only:String(unreadOnly)});if(before){query.set('before_created_at',before.time);query.set('before_id',before.id)}
   const raw=await client.request<unknown>('/my/notifications?'+query,{signal:controller.signal})
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   state.page=readNotificationPage(raw,scope,before,unreadOnly);return true
  }catch(error){if(!disposed&&turn===epoch)state.error=error;return false}finally{if(!disposed&&turn===epoch){state.busy=null;abort=null;emit()}}
 }
 async function send(attempt:Attempt,retry=false){
  if(disposed||!context||state.busy)return false
  const turn=++epoch,controller=new AbortController();abort=controller;state={...state,busy:'write',error:null,receipt:null,attempt:structuredClone(attempt)};emit()
  try{
   const raw=await client.request<unknown>(`/my/notifications/${attempt.id}/read`,{method:'POST',body:structuredClone(attempt.body),idempotencyKey:attempt.key,signal:controller.signal})
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   if(!exportExact(raw,['id','version','read_at'])||raw.id!==attempt.id||raw.version!==1)throw invalid();exportTime(raw.read_at)
   state.receipt=structuredClone(raw) as unknown as NotificationReceipt;state.attempt=null
   // A write receipt is not a fresh unread-count/list snapshot; force an explicit server read.
   state.page=null
   return true
  }catch(error){if(!disposed&&turn===epoch){state.error=error;if(!writeFailureUncertain(retry,error))state.attempt=null}return false}finally{if(!disposed&&turn===epoch){state.busy=null;abort=null;emit()}}
 }
 function mark(id:string){
  if(disposed||!context||state.busy||state.attempt||!state.page)return Promise.resolve(false)
  const m=state.page.items.find(x=>x.id===id);if(!m||m.version!==0)return Promise.resolve(false)
  try{const key=newKey();if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw invalid();return send({id,key,body:{expected_version:0}})}catch(error){state.error=error;emit();return Promise.resolve(false)}
 }
 async function intents(next=false){
  if(disposed||!context?.internal||state.intentBusy)return false
  const before=next&&state.intents?.has_more?{time:state.intents.next_before_created_at!,id:state.intents.next_before_id!}:undefined
  if(next&&!before)return false
  return readIntents(before)
 }
 async function readIntents(before?:Cursor){
  if(disposed||!context?.internal||state.intentBusy)return false
  lastIntent={before:before?{...before}:undefined}
  const turn=++intentEpoch,controller=new AbortController();intentAbort=controller;state={...state,intents:null,intentBusy:true,intentError:null};emit()
  try{
   const query=new URLSearchParams({page_size:'25'});if(before){query.set('before_created_at',before.time);query.set('before_id',before.id)}
   const raw=await client.request<unknown>('/notification-intents?'+query,{signal:controller.signal})
   if(disposed||turn!==intentEpoch||controller.signal.aborted)return false
   state.intents=readIntentPage(raw,before);return true
  }catch(error){if(!disposed&&turn===intentEpoch)state.intentError=error;return false}finally{if(!disposed&&turn===intentEpoch){state.intentBusy=false;intentAbort=null;emit()}}
 }
 emit()
 return{reset,list,mark,intents,retryRead:()=>lastRead?readList(lastRead.unreadOnly,lastRead.before):Promise.resolve(false),retryIntents:()=>lastIntent?readIntents(lastIntent.before):Promise.resolve(false),retry:()=>state.attempt?send(structuredClone(state.attempt),true):Promise.resolve(false),unresolved:()=>state.attempt!==null,state:snapshot,dispose:()=>{disposed=true;reset(null)}}
}
