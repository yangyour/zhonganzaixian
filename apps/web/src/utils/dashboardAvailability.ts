import {ApiError} from '../api/client'
import {utcInstantNanos} from './utcInstant'

export const dashboardWindows=['HOURS_24','DAYS_7','DAYS_30'] as const
export type DashboardWindow=typeof dashboardWindows[number]
export interface DashboardContext {tenantId:string;actorId:string;permissionVersion:number;role:string;canRead:boolean}
export interface DashboardPeriod {from:string;to:string}
export interface DashboardKpi {id:string;unit:string;period:DashboardPeriod|null;value:null;sample_count:null;sampled_at:null;metric_version:null;threshold_breached:null;drilldown_path:null;state:'UNKNOWN';reader_status:'NOT_IMPLEMENTED';unavailable_reason:string;detail_counts:Record<string,null>}
export interface DashboardPanel {id:string;period:DashboardPeriod|null;contract_item_limit:number|null;items:null;sample_count:null;sampled_at:null;metric_version:null;state:'UNKNOWN';reader_status:'NOT_IMPLEMENTED';unavailable_reason:string}
export interface DashboardSnapshot {tenant_id:string;actor_id:string;permission_version:number;generated_at:string;window:DashboardWindow;timezone:'Asia/Shanghai';trend_period:DashboardPeriod;last_24_hours:DashboardPeriod;month_start:string;month_window_empty:boolean;layout_version:'MOP_FIXED_DASHBOARD_V1';layout_customization_allowed:false;contract_refresh_seconds:30;kpis:DashboardKpi[];panels:DashboardPanel[];metric_samples_loaded:false;clock_accuracy_verified:false;source:'POSTGRESQL_AUTHORIZATION_AND_CLOCK_ONLY';scope:'CURRENT_AUTHORIZED_TENANT';formal_metric_status:'NOT_IMPLEMENTED';writes_performed:false}
export const dashboardBad=()=>new ApiError(0,'INVALID_DASHBOARD_AVAILABILITY','首页数据未通过授权、固定布局或时间核对；未显示替代指标','—')
const exact=(v:unknown,keys:readonly string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const uuid=(v:unknown)=>typeof v==='string'&&v.length===36&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
export const validDashboardContext=(c:DashboardContext|null):c is DashboardContext=>Boolean(c&&uuid(c.tenantId)&&uuid(c.actorId)&&Number.isSafeInteger(c.permissionVersion)&&c.permissionVersion>=1&&['viewer','tadmin','msp','padmin'].includes(c.role)&&c.canRead===true)
export const validDashboardWindow=(value:unknown):value is DashboardWindow=>dashboardWindows.includes(value as DashboardWindow)
export function dashboardTime(value:unknown):bigint {const n=utcInstantNanos(value);if(typeof value!=='string'||!/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)||n===null)throw dashboardBad();return n}
const hour=3_600_000_000_000n
const shanghai=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'})
function parts(n:bigint){return Object.fromEntries(shanghai.formatToParts(new Date(Number(n/1_000_000n))).map(p=>[p.type,p.value]))}
function period(v:unknown,from:bigint,to:bigint){if(!exact(v,['from','to'])||dashboardTime(v.from)!==from||dashboardTime(v.to)!==to||from>to)throw dashboardBad()}
const kpiDefinitions=[
 ['ASSET_ONLINE_RATE','PERCENT','MONITORING_SAMPLE_READER_NOT_IMPLEMENTED',['normal','unknown','total']],
 ['CURRENT_PROBLEMS','COUNT','EVENT_READER_NOT_IMPLEMENTED',['disaster','severe','average','warning','information']],
 ['MONTH_SERVICE_AVAILABILITY','PERCENT','SERVICE_INTERVAL_READER_NOT_IMPLEMENTED',[]],
 ['PENDING_TICKETS','COUNT','FORMAL_TICKET_STATE_NOT_AVAILABLE',['overdue_sla']],
 ['MTTA_24H','MILLISECONDS','EVENT_ACKNOWLEDGEMENT_READER_NOT_IMPLEMENTED',[]],
 ['DELIVERY_RATE_24H','PERCENT','DELIVERY_RECEIPT_READER_NOT_IMPLEMENTED',['accepted','delivered']]
] as const
const panelDefinitions=[['ALERT_TREND','EVENT_READER_NOT_IMPLEMENTED',null],['SERVICE_HEALTH','SERVICE_INTERVAL_READER_NOT_IMPLEMENTED',null],['FAULT_TIMELINE','SERVICE_INTERVAL_READER_NOT_IMPLEMENTED',8],['RECENT_EVENTS','EVENT_READER_NOT_IMPLEMENTED',7],['PENDING_TICKETS','FORMAL_TICKET_STATE_NOT_AVAILABLE',5],['RESOURCE_TOP5','MONITORING_SAMPLE_READER_NOT_IMPLEMENTED',5]] as const
const missingKeys=['sample_count','sampled_at','metric_version'] as const
function missing(v:Record<string,unknown>){if(v.state!=='UNKNOWN'||v.reader_status!=='NOT_IMPLEMENTED'||missingKeys.some(k=>v[k]!==null))throw dashboardBad()}
export function readDashboardSnapshot(raw:unknown,context:DashboardContext,window:DashboardWindow):DashboardSnapshot{
 if(!validDashboardContext(context)||!validDashboardWindow(window)||!exact(raw,['tenant_id','actor_id','permission_version','generated_at','window','timezone','trend_period','last_24_hours','month_start','month_window_empty','layout_version','layout_customization_allowed','contract_refresh_seconds','kpis','panels','metric_samples_loaded','clock_accuracy_verified','source','scope','formal_metric_status','writes_performed']))throw dashboardBad()
 if(raw.tenant_id!==context.tenantId||raw.actor_id!==context.actorId||raw.permission_version!==context.permissionVersion||raw.window!==window||raw.timezone!=='Asia/Shanghai'||raw.layout_version!=='MOP_FIXED_DASHBOARD_V1'||raw.layout_customization_allowed!==false||raw.contract_refresh_seconds!==30||raw.metric_samples_loaded!==false||raw.clock_accuracy_verified!==false||raw.source!=='POSTGRESQL_AUTHORIZATION_AND_CLOCK_ONLY'||raw.scope!=='CURRENT_AUTHORIZED_TENANT'||raw.formal_metric_status!=='NOT_IMPLEMENTED'||raw.writes_performed!==false)throw dashboardBad()
 const now=dashboardTime(raw.generated_at),month=dashboardTime(raw.month_start),lastDay=now-24n*hour,trend=now-BigInt(window==='HOURS_24'?24:window==='DAYS_7'?168:720)*hour,currentParts=parts(now),monthParts=parts(month)
 if(!currentParts.year||Number(currentParts.year)>9999||month>now||currentParts.year!==monthParts.year||currentParts.month!==monthParts.month||monthParts.day!=='01'||monthParts.hour!=='00'||monthParts.minute!=='00'||monthParts.second!=='00'||month%1_000_000_000n!==0n||raw.month_window_empty!==(month===now))throw dashboardBad()
 period(raw.trend_period,trend,now);period(raw.last_24_hours,lastDay,now)
 if(!Array.isArray(raw.kpis)||raw.kpis.length!==6||!Array.isArray(raw.panels)||raw.panels.length!==6)throw dashboardBad()
 raw.kpis.forEach((row:unknown,index:number)=>{const def=kpiDefinitions[index]!;if(!exact(row,['id','unit','period','value',...missingKeys,'threshold_breached','drilldown_path','state','reader_status','unavailable_reason','detail_counts'])||row.id!==def[0]||row.unit!==def[1]||row.unavailable_reason!==def[2]||row.value!==null||row.threshold_breached!==null||row.drilldown_path!==null||!exact(row.detail_counts,def[3])||Object.values(row.detail_counts).some(v=>v!==null))throw dashboardBad();missing(row);if(index===2)period(row.period,month,now);else if(index===4||index===5)period(row.period,lastDay,now);else if(row.period!==null)throw dashboardBad()})
 raw.panels.forEach((row:unknown,index:number)=>{const def=panelDefinitions[index]!;if(!exact(row,['id','period','contract_item_limit','items',...missingKeys,'state','reader_status','unavailable_reason'])||row.id!==def[0]||row.unavailable_reason!==def[1]||row.contract_item_limit!==def[2]||row.items!==null)throw dashboardBad();missing(row);if(index===0)period(row.period,trend,now);else if(index===2)period(row.period,lastDay,now);else if(row.period!==null)throw dashboardBad()})
 return structuredClone(raw) as unknown as DashboardSnapshot
}
