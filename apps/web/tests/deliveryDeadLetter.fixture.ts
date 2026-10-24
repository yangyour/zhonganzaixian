import type { DeadLetterCommand,DeadLetterReceipt } from '../src/utils/deliveryDeadLetter'
import { deliveryScope as s } from './deliveryJournal.fixture'
export const owner={key:'synthetic-owner',tenant:s.tenant,actor:s.actor}
export const command:DeadLetterCommand={command_id:s.command,tenant_id:s.tenant,actor_id:s.actor,delivery_id:s.id,resource_pool_id:s.pool,expected_version:2,reason:'Synthetic reason'}
export function receipt(c:DeadLetterCommand=command):DeadLetterReceipt{return {tenant_id:c.tenant_id,actor_id:c.actor_id,delivery_id:c.delivery_id,resource_pool_id:c.resource_pool_id,command_id:c.command_id,applied_version:c.expected_version+1,recorded_status_at_application:'DEAD_LETTER',reason:c.reason,applied_at:'2026-10-24T12:00:00Z',source:'LOCAL_FAILED_DECLARATION',provider_receipt_verified:false,automatic_send_enabled:false,fresh_send_authorized:false,receipt_scope:'ORIGINAL_COMMAND_NOT_CURRENT_HEAD'}}
export function storage(){const data=new Map<string,string>();return {data,getItem:(k:string)=>data.get(k)??null,setItem:(k:string,v:string)=>{data.set(k,v)},removeItem:(k:string)=>{data.delete(k)}}}
