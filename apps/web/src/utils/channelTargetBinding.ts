import {ApiError,isCancelled,type ApiClient} from '../api/client'
import type {EmailBinding} from './email'
import type {WeComBinding} from './wecom'
export interface TargetBindingContext {kind:'email'|'wecom';tenantId:string;userId:string;channelIdentity:string}
export type ChannelBinding=EmailBinding|WeComBinding
export interface TargetBindingState {busy:boolean;ready:boolean;context:TargetBindingContext|null;binding:ChannelBinding|null;error:unknown}
export const emptyTargetBindingState=():TargetBindingState=>({busy:false,ready:false,context:null,binding:null,error:null})
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const record=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const exact=(v:Record<string,unknown>,keys:string[])=>Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const bad=()=>new ApiError(200,'INVALID_CHANNEL_BINDING_RESPONSE','所选账号绑定读取结果未通过范围或版本校验，请重新读取；不能据此推断尚未绑定。','—')
function utc(v:unknown):v is string{
 if(typeof v!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v)||v.startsWith('0000'))return false
 const n=Date.parse(v);if(!Number.isFinite(n))return false
 const normalized=v.includes('.')?v.replace(/\.(\d{1,3})Z$/,(_,f:string)=>'.'+f.padEnd(3,'0')+'Z'):v.replace(/Z$/,'.000Z')
 return new Date(n).toISOString()===normalized
}
function validContext(c:TargetBindingContext){return ['email','wecom'].includes(c.kind)&&uuid.test(c.tenantId)&&uuid.test(c.userId)&&typeof c.channelIdentity==='string'&&c.channelIdentity.length>0}
export function readTargetBinding(raw:unknown,c:TargetBindingContext):ChannelBinding|null{
 const identity=c.kind==='email'?'sender':'corp_id'
 if(!validContext(c)||!record(raw)||!exact(raw,['tenant_id','user_id',identity,'binding','source'])||raw.tenant_id!==c.tenantId||raw.user_id!==c.userId||raw[identity]!==c.channelIdentity||raw.source!=='postgresql')throw bad()
 if(raw.binding===null)return null
 const b=raw.binding,field=c.kind==='email'?'email':'wecom_user_id'
 if(!record(b)||!exact(b,['id','user_id','display_name',field,'active','version','updated_at',...(c.kind==='wecom'?['verified_at']:[])])||!uuid.test(String(b.id))||b.user_id!==c.userId||typeof b.display_name!=='string'||typeof b.active!=='boolean'||!Number.isSafeInteger(b.version)||Number(b.version)<1||!utc(b.updated_at))throw bad()
 if(c.kind==='email'){if(typeof b.email!=='string'||b.email.length>254||/[\s<>;,]/.test(b.email)||b.email!==b.email.toLowerCase()||!/^\S+@\S+\.\S+$/.test(b.email))throw bad()}
 else if(typeof b.wecom_user_id!=='string'||!(/^[A-Za-z0-9_.@-]{1,64}$/).test(b.wecom_user_id)||b.wecom_user_id.toLowerCase()==='@all'||!utc(b.verified_at))throw bad()
 return structuredClone(b) as unknown as ChannelBinding
}
/** A successful explicit null is the only evidence for version zero. Failed reads remain unready. */
export function createTargetBindingWorkspace(client:Pick<ApiClient,'request'>,notify:(s:TargetBindingState)=>void){
 let state=emptyTargetBindingState(),epoch=0,disposed=false,controller:AbortController|null=null
 const emit=(s:TargetBindingState)=>{state=s;notify({...s})}
 function reset(){epoch++;controller?.abort();controller=null;emit(emptyTargetBindingState())}
 async function load(input:TargetBindingContext){
  if(disposed)return false
  const c={...input},turn=++epoch;controller?.abort();controller=new AbortController();const signal=controller.signal
  emit({busy:true,ready:false,context:c,binding:null,error:null})
  try{if(!validContext(c))throw bad();const raw=await client.request(`/${c.kind}/bindings/${c.userId}`,{signal});if(disposed||turn!==epoch)return false
   const binding=readTargetBinding(raw,c);emit({busy:false,ready:true,context:c,binding,error:null});return true
  }catch(error){if(!disposed&&turn===epoch)emit({busy:false,ready:false,context:c,binding:null,error:isCancelled(error)?null:error});return false}
  finally{if(turn===epoch)controller=null}
 }
 return {load,reset,retry:()=>state.context?load(state.context):Promise.resolve(false),dispose(){disposed=true;reset()}}
}
