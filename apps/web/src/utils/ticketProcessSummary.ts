import type {Session,RegisteredTicket} from '../api/types'
import {lifecycleLabels,type LifecycleAction,type LifecycleSnapshot} from './ticketLifecycleWorkspace'
import type {TicketSlaProjection} from './ticketSlaReader'
export interface TicketProcessSummary {authorization_key:string;loaded:boolean;loading:boolean;failed:boolean;locked:boolean;snapshot:LifecycleSnapshot|null;clock:TicketSlaProjection|null;actions:LifecycleAction[]}
export function ticketAuthorityKey(session:Session|null,generation:number,ticket:RegisteredTicket,switching:boolean){return JSON.stringify([generation,switching,session?.tenant.id,session?.user.id,session?.user.role,session?.permission_version,session?.account_version,[...(session?.capabilities??[])].sort(),session?.mode,ticket.tenant_id,ticket.id,ticket.version])}
export function processLabel(value:TicketProcessSummary|null){if(value?.loading)return '正在读取流程';if(value?.failed)return '流程读取失败';if(!value?.loaded)return '流程尚未读取';return value.snapshot?lifecycleLabels[value.snapshot.state]:'尚未创建当前生命周期'}
export function priorityClockLabel(value:TicketProcessSummary|null){if(!value?.clock)return value?.loading?'正在读取':'尚未取得同版本SLA快照';return `${value.clock.priority} · ${value.clock.tier==='BASIC'?'基础':value.clock.tier==='STANDARD'?'标准':'高级'}服务`}
/** Only current state and explicit server evidence qualify a marker; comments never imply a completed stage. */
export function processStages(value:TicketProcessSummary|null){
 const s=value?.loaded&&!value.failed&&!value.loading?value.snapshot:null
 return [
  {title:'工单发起',detail:'已受理入库',recorded:true},
  {title:'工单受理',detail:s?s.state==='NEW'?'当前待接单':'非当前状态':'尚未取得流程事实',recorded:s?.state==='NEW'},
  {title:'处理过程',detail:s?['IN_PROGRESS','WAITING_VENDOR','REOPENED'].includes(s.state)?lifecycleLabels[s.state]:'非当前状态':'尚未取得流程事实',recorded:!!s&&['IN_PROGRESS','WAITING_VENDOR','REOPENED'].includes(s.state)},
  {title:'解决方案',detail:s?.resolved_at&&s.solution_snapshot_hash?'已有本轮方案证据':'尚未取得方案证据',recorded:!!s?.resolved_at&&!!s.solution_snapshot_hash},
  {title:'客户确认',detail:s?.state==='PENDING_CUSTOMER'?'待客户确认':s?.close_mode==='customer_confirm'?'客户已确认':'尚无客户确认结论',recorded:s?.state==='PENDING_CUSTOMER'||s?.close_mode==='customer_confirm'},
  {title:'关闭结果',detail:s?.state==='CLOSED'?(s.close_mode==='timeout_auto'?'超时自动关闭':'客户确认关闭'):s?'当前未关闭':'尚未取得流程事实',recorded:s?.state==='CLOSED'},
 ]
}
