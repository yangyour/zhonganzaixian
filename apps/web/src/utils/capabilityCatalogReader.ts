import { digestSha256 } from './browserCrypto'
import { ApiError,isCancelled,type ApiClient } from '../api/client'
export const catalogRoleIds=['viewer','tadmin','msp','padmin'] as const
export const catalogDimensionIds=['ASSETS_METRICS','ALERTS_TICKETS','TEMPLATES_AUTOMATION','PLATFORM_ADMIN','MEMBER_ADMIN'] as const
export interface CatalogRole{id:typeof catalogRoleIds[number];label:string}
export interface CatalogDimension{id:typeof catalogDimensionIds[number];label:string;capability_ids:string[]}
export interface CatalogEntry{id:string;label:string;dimension_ids:CatalogDimension['id'][];declared_by_role:Record<CatalogRole['id'],boolean>}
export interface CapabilityCatalog{tenant_id:string;scope:'CODE_DECLARATIONS_NOT_TENANT_ASSIGNMENTS';catalog_version:'mop-capability-declarations-v1';source:'STATIC_CODE_CAPABILITIES';declaration_hash:string;roles:CatalogRole[];dimensions:CatalogDimension[];entries:CatalogEntry[];coverage:'DECLARED_CAPABILITIES_ONLY';entry_count:number;resource_authorization_required:true;resource_access_decision:null;configuration_driven_authorization:boolean;dynamic_roles_status:'NOT_IMPLEMENTED'|'PROJECT_RBAC';effective_customer_matrix:null;customer_approval_status:'UNVERIFIED';customer_signature:null;supplier_signature:null;subject_bindings_included:false;production_authority_verified:false;configuration_key:'auth.role_matrix_and_assignments';configuration_status:'CONFIG_PENDING';actions_this_request:{role_created:false;permissions_changed:false;configuration_published:false}}
export interface CatalogContext{tenantId:string;actorId:string;permissionVersion:number;role:string}
export interface CatalogState{current:CapabilityCatalog|null;busy:boolean;error:unknown}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const exact=(v:unknown,keys:readonly string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const text=(v:unknown):v is string=>typeof v==='string'&&Boolean(v.trim())&&Array.from(v).length<=100&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)&&!Array.from(v).some(c=>c.length===1&&/[\ud800-\udfff]/.test(c))
const cap=(v:unknown):v is string=>typeof v==='string'&&v.length<=100&&/^[a-z_]+:[a-z_]+$/.test(v)
const copy=<T>(v:T):T=>JSON.parse(JSON.stringify(v)) as T
const bad=()=>new ApiError(200,'INVALID_CAPABILITY_CATALOG','代码声明目录、四角色五维或摘要校验异常；旧目录已清除','—')
export async function readCapabilityCatalog(v:unknown,scope:CatalogContext):Promise<CapabilityCatalog>{
 const keys=['tenant_id','scope','catalog_version','source','declaration_hash','roles','dimensions','entries','coverage','entry_count','resource_authorization_required','resource_access_decision','configuration_driven_authorization','dynamic_roles_status','effective_customer_matrix','customer_approval_status','customer_signature','supplier_signature','subject_bindings_included','production_authority_verified','configuration_key','configuration_status','actions_this_request']
 if(!exact(v,keys)||v.tenant_id!==scope.tenantId||v.scope!=='CODE_DECLARATIONS_NOT_TENANT_ASSIGNMENTS'||v.catalog_version!=='mop-capability-declarations-v1'||v.source!=='STATIC_CODE_CAPABILITIES'||v.coverage!=='DECLARED_CAPABILITIES_ONLY'||v.resource_authorization_required!==true||v.resource_access_decision!==null||typeof v.configuration_driven_authorization!=='boolean'||v.dynamic_roles_status!==(v.configuration_driven_authorization?'PROJECT_RBAC':'NOT_IMPLEMENTED')||v.effective_customer_matrix!==null||v.customer_approval_status!=='UNVERIFIED'||v.customer_signature!==null||v.supplier_signature!==null||v.subject_bindings_included!==false||v.production_authority_verified!==false||v.configuration_key!=='auth.role_matrix_and_assignments'||v.configuration_status!=='CONFIG_PENDING'||!exact(v.actions_this_request,['role_created','permissions_changed','configuration_published'])||Object.values(v.actions_this_request).some(x=>x!==false)||!Array.isArray(v.roles)||v.roles.length!==4||!Array.isArray(v.dimensions)||v.dimensions.length!==5||!Array.isArray(v.entries)||v.entries.length>256||v.entry_count!==v.entries.length||typeof v.declaration_hash!=='string'||!/^[0-9a-f]{64}$/.test(v.declaration_hash))throw bad()
 const verified=copy(v) as unknown as CapabilityCatalog
 await readCapabilityDefinition({declaration_hash:verified.declaration_hash,roles:verified.roles,dimensions:verified.dimensions,entries:verified.entries})
 return verified
}
export type CapabilityDefinition=Pick<CapabilityCatalog,'declaration_hash'|'roles'|'dimensions'|'entries'>
/** Shared exact four-role/five-dimension definition and fingerprint validation, without inventing approval metadata. */
export async function readCapabilityDefinition(v:unknown):Promise<CapabilityDefinition>{
 if(!exact(v,['declaration_hash','roles','dimensions','entries'])||!Array.isArray(v.roles)||v.roles.length!==4||!Array.isArray(v.dimensions)||v.dimensions.length!==5||!Array.isArray(v.entries)||v.entries.length>256||typeof v.declaration_hash!=='string'||!/^[0-9a-f]{64}$/.test(v.declaration_hash))throw bad()
 v.roles.forEach((role,i)=>{if(!exact(role,['id','label'])||role.id!==catalogRoleIds[i]||!text(role.label))throw bad()})
 const entries:CatalogEntry[]=[];let previous='';let canonical='mop-capability-declarations-v1\nviewer|tadmin|msp|padmin\n'
 for(const entry of v.entries){if(!exact(entry,['id','label','dimension_ids','declared_by_role'])||!cap(entry.id)||entry.id<=previous||!text(entry.label)||!Array.isArray(entry.dimension_ids)||entry.dimension_ids.length<1||entry.dimension_ids.length>5||new Set(entry.dimension_ids).size!==entry.dimension_ids.length||entry.dimension_ids.some(id=>typeof id!=='string'||!catalogDimensionIds.includes(id as CatalogDimension['id']))||!exact(entry.declared_by_role,catalogRoleIds)||Object.values(entry.declared_by_role).some(value=>typeof value!=='boolean'))throw bad();previous=entry.id;const item=copy(entry) as unknown as CatalogEntry;entries.push(item);canonical+=item.id+'|'+item.dimension_ids.join(',')+'|'+catalogRoleIds.map(role=>item.declared_by_role[role]?'1':'0').join('')+'\n'}
 const labels=['资产与指标','告警与工单','模板与自动化','平台管理','成员管理'];v.dimensions.forEach((dimension,i)=>{if(!exact(dimension,['id','label','capability_ids'])||dimension.id!==catalogDimensionIds[i]||dimension.label!==labels[i]||JSON.stringify(dimension.capability_ids)!==JSON.stringify(entries.filter(e=>e.dimension_ids.includes(catalogDimensionIds[i]!)).map(e=>e.id)))throw bad()})
 const verified=copy(v) as unknown as CapabilityDefinition
 const digest=Array.from(new Uint8Array(await digestSha256(new TextEncoder().encode(canonical))),n=>n.toString(16).padStart(2,'0')).join('');if(digest!==verified.declaration_hash)throw bad()
 return verified
}
export function createCapabilityCatalogReader(client:Pick<ApiClient,'request'>,notify:(state:CatalogState)=>void){
 let context:CatalogContext|null=null,generation=0,controller:AbortController|null=null,disposed=false,retryAvailable=false;let state:CatalogState={current:null,busy:false,error:null}
 const snapshot=()=>({...state,current:state.current?copy(state.current):null});const emit=()=>notify(snapshot());const clear=()=>{generation++;controller?.abort();controller=null;state={current:null,busy:false,error:null}}
 function reset(scope:CatalogContext|null){clear();retryAvailable=false;context=!disposed&&scope&&scope.role==='padmin'&&uuid.test(scope.tenantId)&&uuid.test(scope.actorId)&&Number.isSafeInteger(scope.permissionVersion)&&scope.permissionVersion>0?{...scope}:null;emit()}
 async function refresh(){
  if(!context||disposed||state.busy)return false
  clear();const current=generation,scope={...context},active=new AbortController();controller=active;retryAvailable=true;state.busy=true;emit()
  try{
   if(current!==generation||disposed||active.signal.aborted)return false
   const raw=await client.request('/authorization/capability-catalog',{signal:active.signal})
   if(current!==generation||disposed||active.signal.aborted)return false
   const value=await readCapabilityCatalog(raw,scope)
   if(current!==generation||disposed||active.signal.aborted)return false
   state.current=value;retryAvailable=false;return true
  }catch(error){if(current===generation&&!disposed&&!isCancelled(error))state.error=error;return false}
  finally{if(current===generation&&!disposed){state.busy=false;controller=null;emit()}}
 }
 function canRetryRead(){return !!context&&!disposed&&!state.busy&&retryAvailable}
 function cancelRead(){if(disposed||!state.busy)return false;clear();emit();return true}
 return {state:snapshot,reset,refresh,canRetryRead,cancelRead,retryRead:()=>canRetryRead()?refresh():Promise.resolve(false),dispose(){clear();disposed=true;context=null;retryAvailable=false;emit()}}
}
