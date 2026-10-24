import { shallowRef } from 'vue'
import { ApiError, type RequestOptions } from '../api/client'
import { createDurableCommand } from './durableCommand'
import { deliveryUuid } from './deliveryJournal'
import { isUtcInstant } from './utcInstant'
export interface DeadLetterCommand { command_id:string;tenant_id:string;actor_id:string;delivery_id:string;resource_pool_id:string;expected_version:number;reason:string }
export interface DeadLetterReceipt {tenant_id:string;actor_id:string;delivery_id:string;resource_pool_id:string;command_id:string;applied_version:number;recorded_status_at_application:'DEAD_LETTER';reason:string;applied_at:string;source:'LOCAL_FAILED_DECLARATION';provider_receipt_verified:false;automatic_send_enabled:false;fresh_send_authorized:false;receipt_scope:'ORIGINAL_COMMAND_NOT_CURRENT_HEAD'}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const invalid=()=>new ApiError(200,'INVALID_DELIVERY_DISPOSITION','处置命令或回执未通过原身份、版本和原因核对，请保留原命令','—')
export function validateDeadLetterCommand(v:unknown,scope:{tenant:string;actor:string}):DeadLetterCommand{
 if(!object(v)||Object.keys(v).sort().join(',')!=='actor_id,command_id,delivery_id,expected_version,reason,resource_pool_id,tenant_id'
  ||!['actor_id','command_id','delivery_id','resource_pool_id','tenant_id'].every(k=>deliveryUuid(v[k]))
  ||v.tenant_id!==scope.tenant||v.actor_id!==scope.actor||!Number.isSafeInteger(v.expected_version)||Number(v.expected_version)<0||Number(v.expected_version)>=12000
  ||typeof v.reason!=='string'||!v.reason.trim()||v.reason.length>1000||/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(v.reason))throw invalid()
 return v as unknown as DeadLetterCommand
}
export function readDeadLetterReceipt(v:unknown,command:DeadLetterCommand):DeadLetterReceipt{
 if(!object(v)||Object.keys(v).sort().join(',')!=='actor_id,applied_at,applied_version,automatic_send_enabled,command_id,delivery_id,fresh_send_authorized,provider_receipt_verified,reason,receipt_scope,recorded_status_at_application,resource_pool_id,source,tenant_id'
  ||v.tenant_id!==command.tenant_id||v.actor_id!==command.actor_id||v.delivery_id!==command.delivery_id||v.resource_pool_id!==command.resource_pool_id||v.command_id!==command.command_id
  ||v.applied_version!==command.expected_version+1||v.reason!==command.reason||!isUtcInstant(v.applied_at)||v.recorded_status_at_application!=='DEAD_LETTER'
  ||v.source!=='LOCAL_FAILED_DECLARATION'||v.provider_receipt_verified!==false||v.automatic_send_enabled!==false||v.fresh_send_authorized!==false||v.receipt_scope!=='ORIGINAL_COMMAND_NOT_CURRENT_HEAD')throw invalid()
 return v as unknown as DeadLetterReceipt
}
export function createDeadLetterCommands(client:{request(path:string,options?:RequestOptions):Promise<unknown>},storage:Pick<Storage,'getItem'|'setItem'|'removeItem'>,scope:()=>{key:string;tenant:string;actor:string}|null){
 const receipt=shallowRef<DeadLetterReceipt|null>(null)
 const commands=createDurableCommand<DeadLetterCommand>({scope:()=>scope()?.key??null,prefix:'mop.delivery-dead-letter.pending:',storage,
  validate:raw=>{const owner=scope();if(!owner)throw invalid();return validateDeadLetterCommand(raw,owner)},
  send:(c,signal)=>client.request('/notification-deliveries/'+c.delivery_id+'/dead-letter',{method:'POST',body:{expected_version:c.expected_version,reason:c.reason},idempotencyKey:c.command_id,signal,timeoutMs:30000}),
  acknowledge:(raw,c)=>{receipt.value=readDeadLetterReceipt(raw,c)}
 })
 return {...commands,receipt,reset(){receipt.value=null;commands.reset()},dispose(){receipt.value=null;commands.dispose()}}
}
