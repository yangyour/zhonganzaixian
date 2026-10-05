import { digestSha256 } from './browserCrypto'
import {ApiError} from '../api/client'
import {utcInstantNanos} from './utcInstant'
export interface OwnPreferenceContext {tenantId:string;actorId:string;permissionVersion:number;role:string;canRead:boolean;canWrite:boolean}
export type OwnPreferenceChannel='EMAIL'|'SMS'|'DINGTALK'|'WECHAT_WORK'|'WEBHOOK'|'VOICE'
export interface OwnPreferenceQuiet {enabled:boolean|null;start:string|null;end:string|null}
export interface OwnPreferenceDefinition {requested_level:'P1_ONLY'|'P1_P2'|'ALL'|null;requested_channels:OwnPreferenceChannel[]|null;requested_quiet_window:OwnPreferenceQuiet|null}
export interface OwnPreferencePayload extends OwnPreferenceDefinition {expected_version:number;intent:'SAVE_REQUEST_DRAFT_ONLY'}
interface Facts extends OwnPreferenceDefinition {owner_id:string;content_sha256:string;timezone:'Asia/Shanghai';configuration_status:'CONFIG_PENDING';effective:false;effective_level:null;effective_channels:null;effective_quiet_window:null;verified_receiver_id:null;receiver_binding_status:'NOT_VERIFIED';delivery_decision:null;notification_dispatched:false;p1_quiet_bypass_contract_rule:true;record_source:'OWN_NOTIFICATION_PREFERENCE_DRAFT';scope:'CURRENT_OWNER_TENANT';source:'postgresql'}
export interface OwnPreferenceSnapshot extends Facts {id:string;tenant_id:string;version:number;recorded_by:string;recorded_permission_version:number;created_at:string;updated_at:string}
export interface OwnPreferenceRevision extends Facts {preference_id:string;tenant_id:string;version:number;previous_version:number|null;action:'CREATE'|'UPDATE';recorded_by:string;recorded_permission_version:number;recorded_at:string}
export interface OwnPreferenceCurrent {tenant_id:string;owner_id:string;exists:boolean;current:OwnPreferenceSnapshot|null;scope:'CURRENT_OWNER_TENANT';source:'postgresql'}
export interface OwnPreferenceHistory {tenant_id:string;owner_id:string;preference_id:string;items:OwnPreferenceRevision[];has_more:boolean;next_before_version:number|null;scope:'CURRENT_OWNER_TENANT';source:'postgresql';page_size_limit:20}
export const ownPreferenceMaxVersion=9_007_199_254_740_990
export const ownPreferenceChannels:readonly OwnPreferenceChannel[]=['DINGTALK','EMAIL','SMS','VOICE','WEBHOOK','WECHAT_WORK']
export const ownPreferenceBad=()=>new ApiError(0,'INVALID_OWN_NOTIFICATION_PREFERENCE','本人通知偏好范围、版本或内容未通过核对；未知结果保留原请求，不视为已生效或已派发','—')
const exact=(v:unknown,keys:readonly string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
export const ownPreferenceUuid=(v:unknown):v is string=>typeof v==='string'&&v.length===36&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const integer=(v:unknown,min=1,max=ownPreferenceMaxVersion):v is number=>Number.isSafeInteger(v)&&Number(v)>=min&&Number(v)<=max
export const validOwnPreferenceContext=(v:OwnPreferenceContext|null):v is OwnPreferenceContext=>Boolean(v&&ownPreferenceUuid(v.tenantId)&&ownPreferenceUuid(v.actorId)&&integer(v.permissionVersion,1,Number.MAX_SAFE_INTEGER)&&['viewer','tadmin','msp','padmin'].includes(v.role)&&v.canRead===true&&typeof v.canWrite==='boolean')
export function ownPreferenceTime(v:unknown):bigint{const n=utcInstantNanos(v);if(typeof v!=='string'||v.trim()!==v||!/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v)||n===null||n%1_000_000n!==0n)throw ownPreferenceBad();return n}
export function ownPreferenceSame(a:unknown,b:unknown):boolean{if(a===b)return true;if(Array.isArray(a)&&Array.isArray(b))return a.length===b.length&&a.every((x,i)=>ownPreferenceSame(x,b[i]));if(a&&b&&typeof a==='object'&&typeof b==='object'&&!Array.isArray(a)&&!Array.isArray(b)){const x=a as Record<string,unknown>,y=b as Record<string,unknown>;return Object.keys(x).length===Object.keys(y).length&&Object.keys(x).every(k=>Object.hasOwn(y,k)&&ownPreferenceSame(x[k],y[k]))}return false}
const definitionKeys=['requested_level','requested_channels','requested_quiet_window'] as const
const factKeys=['owner_id',...definitionKeys,'content_sha256','timezone','configuration_status','effective','effective_level','effective_channels','effective_quiet_window','verified_receiver_id','receiver_binding_status','delivery_decision','notification_dispatched','p1_quiet_bypass_contract_rule','record_source','scope','source'] as const
const metaKeys=['id','tenant_id','version','recorded_by','recorded_permission_version','created_at','updated_at'] as const
const revisionKeys=['preference_id','tenant_id','version','previous_version','action','recorded_by','recorded_permission_version','recorded_at'] as const
const minute=(v:unknown):v is string=>typeof v==='string'&&v.length===5&&/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(v)
export function readOwnPreferenceDefinition(v:unknown):OwnPreferenceDefinition{
 if(!exact(v,definitionKeys)||![null,'P1_ONLY','P1_P2','ALL'].includes(v.requested_level as never)||v.requested_channels!==null&&(!Array.isArray(v.requested_channels)||v.requested_channels.length>6||new Set(v.requested_channels).size!==v.requested_channels.length||!v.requested_channels.every(c=>ownPreferenceChannels.includes(c as OwnPreferenceChannel))))throw ownPreferenceBad()
 const q=v.requested_quiet_window;if(q!==null&&(!exact(q,['enabled','start','end'])||q.enabled!==null&&typeof q.enabled!=='boolean'||(q.enabled===true?(!minute(q.start)||!minute(q.end)||q.start===q.end):(q.start!==null||q.end!==null))))throw ownPreferenceBad()
 return structuredClone(v) as unknown as OwnPreferenceDefinition
}
export function ownPreferenceDefinition(v:OwnPreferenceDefinition):OwnPreferenceDefinition{return readOwnPreferenceDefinition(Object.fromEntries(definitionKeys.map(k=>[k,v[k]])))}
export function readOwnPreferencePayload(v:unknown,action:'CREATE'|'UPDATE'):OwnPreferencePayload{
 if(!['CREATE','UPDATE'].includes(action)||!exact(v,['expected_version','intent',...definitionKeys])||v.intent!=='SAVE_REQUEST_DRAFT_ONLY'||(action==='CREATE'?v.expected_version!==0:!integer(v.expected_version)||v.expected_version>=ownPreferenceMaxVersion))throw ownPreferenceBad()
 const {expected_version,intent,...definition}=v;return{...readOwnPreferenceDefinition(definition),expected_version:expected_version as number,intent:'SAVE_REQUEST_DRAFT_ONLY'}
}
export function ownPreferenceBytes(tenant:string,owner:string,value:unknown):Uint8Array{
 if(!ownPreferenceUuid(tenant)||!ownPreferenceUuid(owner))throw ownPreferenceBad();const d=readOwnPreferenceDefinition(value),parts:Uint8Array[]=[]
 const i4=(n:number)=>{const b=new Uint8Array(4);new DataView(b.buffer).setInt32(0,n,false);parts.push(b)},field=(s:string|null)=>{if(s===null){i4(-1);return}const b=new TextEncoder().encode(s);i4(b.length);parts.push(b)}
 field('MOP_OWN_NOTIFICATION_PREFERENCE_DRAFT_V1');field(tenant);field(owner);field(d.requested_level);if(d.requested_channels===null)i4(-1);else{i4(d.requested_channels.length);for(const c of [...d.requested_channels].sort())field(c)}
 const q=d.requested_quiet_window;if(q===null)i4(-1);else{i4(3);field(q.enabled===null?null:String(q.enabled));field(q.start);field(q.end)}field('Asia/Shanghai')
 const result=new Uint8Array(parts.reduce((sum,p)=>sum+p.length,0));let offset=0;for(const p of parts){result.set(p,offset);offset+=p.length}return result
}
export async function ownPreferenceFingerprint(tenant:string,owner:string,value:unknown){const bytes=ownPreferenceBytes(tenant,owner,value),hash=await digestSha256(bytes as Uint8Array<ArrayBuffer>);return Array.from(new Uint8Array(hash)).map(v=>v.toString(16).padStart(2,'0')).join('')}
function facts(v:Record<string,unknown>,c:OwnPreferenceContext){
 if(!validOwnPreferenceContext(c)||v.tenant_id!==c.tenantId||v.owner_id!==c.actorId||v.recorded_by!==c.actorId||!integer(v.recorded_permission_version,1,Number.MAX_SAFE_INTEGER)||!integer(v.version)||typeof v.content_sha256!=='string'||v.content_sha256.length!==64||!/^[0-9a-f]{64}$/.test(v.content_sha256)||v.timezone!=='Asia/Shanghai'||v.configuration_status!=='CONFIG_PENDING'||v.effective!==false||v.receiver_binding_status!=='NOT_VERIFIED'||v.notification_dispatched!==false||v.p1_quiet_bypass_contract_rule!==true||v.record_source!=='OWN_NOTIFICATION_PREFERENCE_DRAFT'||v.scope!=='CURRENT_OWNER_TENANT'||v.source!=='postgresql')throw ownPreferenceBad()
 for(const k of ['effective_level','effective_channels','effective_quiet_window','verified_receiver_id','delivery_decision'])if(v[k]!==null)throw ownPreferenceBad()
 const d=readOwnPreferenceDefinition(Object.fromEntries(definitionKeys.map(k=>[k,v[k]])));if(d.requested_channels!==null&&!ownPreferenceSame(d.requested_channels,[...d.requested_channels].sort()))throw ownPreferenceBad()
}
export function readOwnPreferenceSnapshotShape(v:unknown,c:OwnPreferenceContext):OwnPreferenceSnapshot{
 if(!exact(v,[...metaKeys,...factKeys])||!ownPreferenceUuid(v.id))throw ownPreferenceBad();facts(v,c);const created=ownPreferenceTime(v.created_at),updated=ownPreferenceTime(v.updated_at);if(updated<created||v.version===1&&created!==updated)throw ownPreferenceBad();return structuredClone(v) as unknown as OwnPreferenceSnapshot
}
export async function readOwnPreferenceSnapshot(v:unknown,c:OwnPreferenceContext):Promise<OwnPreferenceSnapshot>{const s=readOwnPreferenceSnapshotShape(v,c);if(await ownPreferenceFingerprint(c.tenantId,c.actorId,ownPreferenceDefinition(s))!==s.content_sha256)throw ownPreferenceBad();return s}
export async function readOwnPreferenceCurrent(v:unknown,c:OwnPreferenceContext):Promise<OwnPreferenceCurrent>{
 if(!validOwnPreferenceContext(c)||!exact(v,['tenant_id','owner_id','exists','current','scope','source'])||v.tenant_id!==c.tenantId||v.owner_id!==c.actorId||typeof v.exists!=='boolean'||v.scope!=='CURRENT_OWNER_TENANT'||v.source!=='postgresql'||v.exists===(v.current===null))throw ownPreferenceBad()
 const copy=structuredClone(v);if(copy.exists)copy.current=await readOwnPreferenceSnapshot(copy.current,c);return copy as unknown as OwnPreferenceCurrent
}
export async function readOwnPreferenceHistory(v:unknown,c:OwnPreferenceContext,id:string,before?:number):Promise<OwnPreferenceHistory>{
 if(!validOwnPreferenceContext(c)||!ownPreferenceUuid(id)||before!==undefined&&!integer(before)||!exact(v,['tenant_id','owner_id','preference_id','items','has_more','next_before_version','scope','source','page_size_limit'])||v.tenant_id!==c.tenantId||v.owner_id!==c.actorId||v.preference_id!==id||!Array.isArray(v.items)||v.items.length>15||before===undefined&&v.items.length===0||typeof v.has_more!=='boolean'||v.scope!=='CURRENT_OWNER_TENANT'||v.source!=='postgresql'||v.page_size_limit!==20)throw ownPreferenceBad()
 const copy=structuredClone(v);let previous=before,recorded:bigint|undefined
 for(const raw of copy.items as unknown[]){if(!exact(raw,[...revisionKeys,...factKeys])||raw.preference_id!==id||!integer(raw.version)||raw.previous_version!==(raw.version===1?null:raw.version-1)||raw.action!==(raw.version===1?'CREATE':'UPDATE')||previous!==undefined&&raw.version!==previous-1)throw ownPreferenceBad();facts(raw,c);const time=ownPreferenceTime(raw.recorded_at);if(recorded!==undefined&&time>recorded)throw ownPreferenceBad();const d=readOwnPreferenceDefinition(Object.fromEntries(definitionKeys.map(k=>[k,raw[k]])));if(await ownPreferenceFingerprint(c.tenantId,c.actorId,d)!==raw.content_sha256)throw ownPreferenceBad();previous=raw.version;recorded=time}
 if(copy.has_more?(copy.items as unknown[]).length!==15||previous===undefined||previous<=1||copy.next_before_version!==previous:copy.next_before_version!==null||(copy.items as unknown[]).length>0&&previous!==1||before!==undefined&&before>1&&(copy.items as unknown[]).length===0)throw ownPreferenceBad()
 return copy as unknown as OwnPreferenceHistory
}
