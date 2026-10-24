import {api,ApiError} from '../api/client'
import {createRequestId} from './browserCrypto'
import {createDurableCommand} from './durableCommand'
export type MappingAction='SUBMIT_DRAFT'|'CANCEL_DRAFT'|'ACTIVATE'|'REVOKE'
export interface MappingDraft {asset_id:string;asset_version:number;submitted_by:string;submitted_at:string}
export interface MappingKey {pool_id:string;object_type:'HOST'|'ITEM';external_id:string}
export interface ObjectMapping {
 state:{document:{id:string;tenant_id:string;key:MappingKey;provider:string;created_by:string;created_at:string};version:number;draft:MappingDraft|null;
 active:{approved_draft:MappingDraft;activated_by:string;activated_at:string;expires_at:string;policy_reference:string;source:{key:MappingKey;provider:string;source_host_id:string;claimed_pool_id:string;claimed_tenant_id:string;claimed_asset_id:string;definition_sha256:string;metadata_sha256:string}}|null;updated_at:string;reason:string|null}
 source_capture_enabled:boolean;problem_intake_enabled?:boolean;availability:string;draft_asset_current:boolean;current_actor_can_activate:boolean;policy_reference:string|null;source_definition_sha256:string|null;monitoring_applied:false;dispatch_allowed:false;device_identity_verified:false
}
export const mappingLabels:Record<string,string>={NO_ACTIVE_MAPPING:'尚无发布记录',POLICY_UNCONFIGURED:'审批配置未完成',POLICY_CHANGED:'审批配置已变化',SOURCE_CONFIGURATION_CHANGED:'来源配置已变化',REVIEWER_REVOKED:'复核人授权已变化',ASSET_CHANGED:'资产版本已变化',PROOF_FROM_FUTURE:'证明时间异常',PROOF_EXPIRED:'归属证明已过期',CURRENT_RECORDED_MAPPING:'读取时归属映射有效'}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
export const mappingUuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const version=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>0&&Number(v)<=9007199254740990
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const date=(v:unknown)=>typeof v==='string'&&/^\d{4}-\d\d-\d\dT.*Z$/.test(v)&&Number.isFinite(Date.parse(v))
const ref=(v:unknown)=>typeof v==='string'&&/^[A-Za-z0-9._:-]{1,256}$/.test(v)
const reason=(v:unknown)=>v===null||typeof v==='string'&&v.trim().length>0&&v.length<=500
const key=(v:unknown):v is MappingKey=>object(v)&&mappingUuid(v.pool_id)&&['HOST','ITEM'].includes(String(v.object_type))&&typeof v.external_id==='string'&&v.external_id.trim().length>0&&v.external_id.length<=256
const draft=(v:unknown):v is MappingDraft=>object(v)&&mappingUuid(v.asset_id)&&version(v.asset_version)&&mappingUuid(v.submitted_by)&&date(v.submitted_at)
const sameKey=(a:MappingKey,b:MappingKey)=>a.pool_id===b.pool_id&&a.object_type===b.object_type&&a.external_id===b.external_id
export const mappingSourceId=(v:unknown):v is string=>typeof v==='string'&&/^[1-9][0-9]{0,19}$/.test(v)&&BigInt(v)<=18446744073709551615n
const invalid=()=>new ApiError(200,'INVALID_MONITORING_MAPPING','映射范围、版本或回执未通过核对','—')
export function readObjectMapping(raw:unknown,tenant:string,id?:string):ObjectMapping{
 if(!object(raw)||raw.problem_intake_enabled!==undefined&&typeof raw.problem_intake_enabled!=='boolean'||typeof raw.source_capture_enabled!=='boolean'||!object(raw.state)||!object(raw.state.document)||!Object.hasOwn(mappingLabels,String(raw.availability))||typeof raw.draft_asset_current!=='boolean'||typeof raw.current_actor_can_activate!=='boolean'||raw.policy_reference!==null&&!ref(raw.policy_reference)||raw.source_definition_sha256!==null&&!hash(raw.source_definition_sha256))throw invalid()
 for(const flag of ['monitoring_applied','dispatch_allowed','device_identity_verified'])if(raw[flag]!==false)throw invalid()
 const s=raw.state,d=s.document
 if(!object(d)||!mappingUuid(d.id)||d.tenant_id!==tenant||id!==undefined&&d.id!==id||!key(d.key)||typeof d.provider!=='string'||!/^[A-Z][A-Z0-9_]{0,63}$/.test(d.provider)||!mappingUuid(d.created_by)||!date(d.created_at)||!version(s.version)||!date(s.updated_at)||!reason(s.reason)||s.draft!==null&&!draft(s.draft))throw invalid()
 if(Date.parse(String(s.updated_at))<Date.parse(String(d.created_at)))throw invalid()
 if(s.draft!==null&&(!draft(s.draft)||Date.parse(s.draft.submitted_at)>Date.parse(String(s.updated_at))))throw invalid()
 if(s.active!==null){const a=s.active;if(!object(a)||!draft(a.approved_draft)||!mappingUuid(a.activated_by)||!date(a.activated_at)||!date(a.expires_at)||!ref(a.policy_reference)||!object(a.source)||!key(a.source.key))throw invalid()
  const p=a.source; if(!sameKey(p.key as MappingKey,d.key)||p.provider!==d.provider||p.claimed_pool_id!==d.key.pool_id||p.claimed_tenant_id!==tenant||p.claimed_asset_id!==a.approved_draft.asset_id||typeof p.source_host_id!=='string'||!/^[1-9][0-9]{0,19}$/.test(p.source_host_id)||!hash(p.definition_sha256)||!hash(p.metadata_sha256)||Date.parse(String(a.expires_at))<=Date.parse(String(a.activated_at))||Date.parse(String(a.activated_at))>Date.parse(String(s.updated_at)))throw invalid()
 }else if(raw.availability!=='NO_ACTIVE_MAPPING')throw invalid()
 if(raw.current_actor_can_activate&&(s.draft===null||raw.draft_asset_current!==true||raw.policy_reference===null||raw.source_definition_sha256===null))throw invalid()
 return raw as unknown as ObjectMapping
}
export type MappingCursor={before:string;before_id:string}
export function readMappingPage(raw:unknown,tenant:string):{items:ObjectMapping[];next:MappingCursor|null}{
 if(!object(raw)||!Array.isArray(raw.items)||raw.items.length>20||raw.snapshot_scope!=='CURRENT_PER_MAPPING_READ'||raw.monitoring_applied!==false||raw.dispatch_allowed!==false)throw invalid()
 const items=raw.items.map(v=>readObjectMapping(v,tenant));if(new Set(items.map(v=>v.state.document.id)).size!==items.length)throw invalid()
 if(raw.next!==null&&(!object(raw.next)||!date(raw.next.before)||!mappingUuid(raw.next.before_id)||raw.next.before_id!==items.at(-1)?.state.document.id||raw.next.before!==items.at(-1)?.state.document.created_at))throw invalid()
 return{items,next:raw.next as MappingCursor|null}
}
export interface MappingCaptureReceipt {mapping_id:string;mapping_version:number;source_event_id:string;source_phase:'PROBLEM'|'RECOVERED';outcome:'RAW_STORED'|'RAW_ALREADY_PRESENT'|'CONFLICT_HELD'|'CONFLICT_ALREADY_HELD';record_id:string;first_received_at:string;source_authorization_status:'UNVERIFIED';business_processed:false;acknowledgement_only:true;current_mapping_claimed:false;monitoring_applied:false;dispatch_allowed:false}
export function readMappingCaptureReceipt(raw:unknown,id:string,version:number,eventId:string):MappingCaptureReceipt{
 if(!object(raw)||raw.mapping_id!==id||raw.mapping_version!==version||raw.source_event_id!==eventId||!['PROBLEM','RECOVERED'].includes(String(raw.source_phase))||!['RAW_STORED','RAW_ALREADY_PRESENT','CONFLICT_HELD','CONFLICT_ALREADY_HELD'].includes(String(raw.outcome))||!mappingUuid(raw.record_id)||!date(raw.first_received_at)||raw.source_authorization_status!=='UNVERIFIED'||raw.business_processed!==false||raw.acknowledgement_only!==true||raw.current_mapping_claimed!==false||raw.monitoring_applied!==false||raw.dispatch_allowed!==false)throw invalid()
 return raw as unknown as MappingCaptureReceipt
}
export interface MappingProblemReceipt {mapping_id:string;mapping_version:number;source_event_id:string;record_id:string;raw_outcome:string;first_received_at:string;business_processed:boolean;event_id:string|null;event_version:number|null;ticket_id:string|null;source_ownership_basis:'CURRENT_MAPPING_AND_SERVER_READ';recovery_confirmed:false;dispatch_allowed:false;acknowledgement_only:true}
export function readMappingProblemReceipt(raw:unknown,id:string,expected:number,eventId:string):MappingProblemReceipt{
 if(!object(raw)||raw.mapping_id!==id||raw.mapping_version!==expected||raw.source_event_id!==eventId||!mappingUuid(raw.record_id)||!date(raw.first_received_at)||raw.source_ownership_basis!=='CURRENT_MAPPING_AND_SERVER_READ'||raw.recovery_confirmed!==false||raw.dispatch_allowed!==false||raw.acknowledgement_only!==true)throw invalid()
 const processed=raw.business_processed===true&&['RAW_STORED','RAW_ALREADY_PRESENT'].includes(String(raw.raw_outcome))&&mappingUuid(raw.event_id)&&version(raw.event_version)&&(raw.ticket_id===null||mappingUuid(raw.ticket_id))
 const held=raw.business_processed===false&&['CONFLICT_HELD','CONFLICT_ALREADY_HELD'].includes(String(raw.raw_outcome))&&raw.event_id===null&&raw.event_version===null&&raw.ticket_id===null
 if(!processed&&!held)throw invalid()
 return raw as unknown as MappingProblemReceipt
}
interface Command {key:string;path:string;body:Record<string,unknown>}
const fields=(b:Record<string,unknown>,keys:string[])=>Object.keys(b).sort().join(',')===keys.sort().join(',')
export function createMappingCommands(scope:()=>string|null,storage:Pick<Storage,'getItem'|'setItem'|'removeItem'>,client:Pick<typeof api,'request'>=api,onCapture:(receipt:MappingCaptureReceipt)=>void=()=>{},onIntake:(receipt:MappingProblemReceipt)=>void=()=>{}){
 const commands=createDurableCommand<Command>({scope,prefix:'mop.object-mapping.pending:',storage,
 validate(raw){if(!object(raw)||!mappingUuid(raw.key)||typeof raw.path!=='string'||!object(raw.body)||!raw.path.endsWith('/captures')&&!raw.path.endsWith('/problem-intakes')&&!reason(raw.body.reason))throw invalid();const b=raw.body
  if(raw.path.endsWith('/captures')||raw.path.endsWith('/problem-intakes')){
   const parts=raw.path.split('/');if(parts.length!==4||parts[1]!=='monitoring-object-mappings'||!mappingUuid(parts[2])||!fields(b,['expected_version','source_event_id','expected_source_definition_sha256'])||!version(b.expected_version)||!mappingSourceId(b.source_event_id)||!hash(b.expected_source_definition_sha256))throw invalid()
  }else if(raw.path==='/monitoring-object-mappings'){
   if(!fields(b,['asset_id','asset_version','pool_id','object_type','external_id','reason'])||!mappingUuid(b.asset_id)||!version(b.asset_version)||!key({pool_id:b.pool_id,object_type:b.object_type,external_id:b.external_id}))throw invalid()
  }else{
   const parts=raw.path.split('/');if(parts.length!==4||parts[1]!=='monitoring-object-mappings'||!mappingUuid(parts[2])||parts[3]!=='actions'||!version(b.expected_version)||Number(b.expected_version)>=9007199254740990)throw invalid()
   const keys=['action','expected_version','reason'];switch(b.action){
    case 'SUBMIT_DRAFT':keys.push('asset_id','asset_version');if(!mappingUuid(b.asset_id)||!version(b.asset_version))throw invalid();break
    case 'ACTIVATE':keys.push('expected_policy_reference','expected_source_definition_sha256');if(!ref(b.expected_policy_reference)||!hash(b.expected_source_definition_sha256))throw invalid();break
    case 'REVOKE':if(b.reason===null)throw invalid();break
    case 'CANCEL_DRAFT':break
    default:throw invalid()
   }if(!fields(b,keys))throw invalid()
  }return raw as unknown as Command},
 send:(c,signal)=>client.request(c.path,{method:'POST',body:c.body,idempotencyKey:c.key,signal,timeoutMs:35000}),
 acknowledge(raw,c){if(c.path.endsWith('/problem-intakes')){onIntake(readMappingProblemReceipt(raw,c.path.split('/')[2]!,Number(c.body.expected_version),String(c.body.source_event_id)));return}if(c.path.endsWith('/captures')){onCapture(readMappingCaptureReceipt(raw,c.path.split('/')[2]!,Number(c.body.expected_version),String(c.body.source_event_id)));return}const create=c.path==='/monitoring-object-mappings';if(!object(raw)||!mappingUuid(raw.id)||raw.id!==(create?raw.id:c.path.split('/')[2])||raw.accepted_version!==(create?1:Number(c.body.expected_version)+1)||raw.action!==(create?'CREATE':c.body.action)||raw.acknowledgement_only!==true||raw.current_mapping_claimed!==false||raw.monitoring_applied!==false||raw.dispatch_allowed!==false)throw invalid()}
 })
 return{...commands,send:(path:string,body:Record<string,unknown>)=>commands.submit({key:createRequestId(),path,body})}
}
