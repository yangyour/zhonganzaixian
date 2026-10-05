import { createRequestId } from './browserCrypto'
import {ApiError,type ApiClient} from '../api/client'
import type {Asset,AssetDraft} from '../api/types'
import {utcInstantNanos} from './utcInstant'
import {detachedWorkspaceState} from './detachedWorkspaceState'
import {writeFailureUncertain} from './writeFailure'
export interface AssetDraftContext {tenantId:string;actorId:string;permissionVersion:number;role:string;canWrite:boolean}
export interface AssetDraftAttempt {path:'/assets';method:'POST';key:string;payload:string;state:'uncertain'|'accepted'|'rejected'}
export interface AssetDraftState {attempt:AssetDraftAttempt|null;receipt:Asset|null;busy:boolean;error:unknown}
export const emptyAssetDraftState=():AssetDraftState=>({attempt:null,receipt:null,busy:false,error:null})
export const assetDraftBad=()=>new ApiError(0,'INVALID_ASSET_DRAFT_RECEIPT','资产创建回执未通过原请求或租户核对；结果未确认，请保留原请求重试','—')
const uuid=(v:unknown)=>typeof v==='string'&&v.length===36&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const exact=(v:unknown,keys:readonly string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const types=['router','switch','firewall','ap','wireless_controller','other'] as const
const inputError=(message:string)=>new ApiError(400,'ASSET_DRAFT_INPUT_INVALID',message,'—')
export const validAssetDraftContext=(c:AssetDraftContext|null):c is AssetDraftContext=>Boolean(c&&uuid(c.tenantId)&&uuid(c.actorId)&&Number.isSafeInteger(c.permissionVersion)&&c.permissionVersion>=1&&['padmin','msp','tadmin'].includes(c.role)&&c.canWrite===true)
// Match the existing Java String.strip boundary; do not silently remove NBSP as JavaScript trim would.
const strip=(s:string)=>s.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,'')
function text(v:unknown,max:number){if(typeof v!=='string')throw inputError('请提供完整资产草稿字段');const value=strip(v);if(!value.trim()||value.length>max||[...value].some(c=>{const n=c.codePointAt(0)!;return n<=31||n>=127&&n<=159||n>=0xd800&&n<=0xdfff}))throw inputError('资产草稿字段为空、过长或包含无效字符');return value}
export function assetDraftIp(value:string){
 if(value.includes(':')){if(!/^[0-9a-fA-F:.]+$/.test(value))return false;try{return new URL('http://['+value+']/').hostname.startsWith('[')}catch{return false}}
 return /^[0-9]{1,3}(\.[0-9]{1,3}){3}$/.test(value)&&value.split('.').every(p=>Number(p)<=255&&(p.length===1||!p.startsWith('0')))
}
export function readAssetDraftInput(raw:unknown):AssetDraft{
 if(!exact(raw,['name','type','management_ip','site_id']))throw inputError('资产创建只接受名称、类型、管理IP和站点')
 const name=text(raw.name,100),type=text(raw.type,32),management_ip=text(raw.management_ip,45),site_id=text(raw.site_id,64)
 if(!types.includes(type as typeof types[number])||!assetDraftIp(management_ip))throw inputError('请明确选择设备类型并提供合法IPv4或IPv6字面量')
 return{name,type:type as AssetDraft['type'],management_ip,site_id}
}
function time(v:unknown){const n=utcInstantNanos(v);if(typeof v!=='string'||!/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(v)||n===null)throw assetDraftBad();return n}
export function readCreatedAssetReceipt(raw:unknown,context:AssetDraftContext,input:AssetDraft):Asset{
 if(!validAssetDraftContext(context)||!exact(raw,['id','tenant_id','name','type','management_ip','site_id','status','lifecycle_status','source','sampled_at','unknown_reason','version','created_at','updated_at','availability','sync_state'])||!uuid(raw.id)||raw.tenant_id!==context.tenantId||raw.name!==input.name||raw.type!==input.type||raw.management_ip!==input.management_ip||raw.site_id!==input.site_id||raw.version!==1||raw.source!=='manual'||raw.lifecycle_status!=='DRAFT'||raw.status!=='unknown'||raw.sampled_at!==null||raw.availability!=='UNKNOWN'||raw.sync_state!=='PENDING_ONBOARDING')throw assetDraftBad()
 try{text(raw.unknown_reason,500)}catch{throw assetDraftBad()}
 if(time(raw.updated_at)<time(raw.created_at))throw assetDraftBad()
 return structuredClone(raw) as unknown as Asset
}
/** Keeps the original POST body and key independently of asset-list reads and drawer navigation. */
export function createAssetDraftWriter(client:Pick<ApiClient,'request'>,changed:(state:AssetDraftState)=>void,newKey:()=>string=()=>createRequestId()){
 let context:AssetDraftContext|null=null,state=emptyAssetDraftState(),epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot()),unresolved=()=>state.busy||state.attempt?.state==='uncertain'
 function reset(next:AssetDraftContext|null){abort?.abort();abort=null;++epoch;context=!disposed&&validAssetDraftContext(next)?{...next}:null;state=emptyAssetDraftState();emit()}
 async function send(original:AssetDraftAttempt,retry:boolean){
  if(!context||disposed||state.busy)return false
  const attempt=structuredClone(original),scope={...context},turn=++epoch,controller=new AbortController();abort=controller;let dispatched=false
  state={attempt:{...attempt,state:'uncertain'},receipt:null,busy:true,error:null};emit()
  try{
   const body=readAssetDraftInput(JSON.parse(attempt.payload));await Promise.resolve()
   if(turn!==epoch||disposed||controller.signal.aborted)return false
   dispatched=true
   const raw=await client.request<unknown>(attempt.path,{method:attempt.method,body,idempotencyKey:attempt.key,signal:controller.signal})
   if(turn!==epoch||disposed||controller.signal.aborted)return false
   const receipt=readCreatedAssetReceipt(raw,scope,body)
   state={attempt:{...attempt,state:'accepted'},receipt,busy:false,error:null};return true
  }catch(error){if(turn===epoch&&!disposed)state={attempt:{...attempt,state:retry||dispatched&&writeFailureUncertain(false,error)?'uncertain':'rejected'},receipt:null,busy:false,error};return false}
  finally{if(turn===epoch&&!disposed){state.busy=false;abort=null;emit()}}
 }
 async function submit(raw:unknown){
  if(!context||disposed||state.busy||state.attempt)return false
  const turn=epoch
  try{const body=readAssetDraftInput(raw),key=newKey();if(turn!==epoch||disposed||!context)return false;if(typeof key!=='string'||key.length<1||key.length>128||/[^A-Za-z0-9._:-]/.test(key))throw inputError('资产创建请求标识无效，未发送')
   return send({path:'/assets',method:'POST',key,payload:JSON.stringify(body),state:'uncertain'},false)
  }catch(error){if(turn===epoch&&!disposed){state.error=error;emit()}return false}
 }
 emit();return{reset,submit,retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),begin:()=>{if(!context||disposed||unresolved())return false;state=emptyAssetDraftState();emit();return true},unresolved,state:snapshot,dispose:()=>{disposed=true;reset(null)}}
}
