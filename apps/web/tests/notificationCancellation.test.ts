import { expect, it, vi } from 'vitest'
import { ApiError } from '../src/api/client'
import { createControlCommands } from '../src/utils/notificationControl'

const id='79000000-0000-4000-8000-000000000001',key='79000000-0000-4000-8000-000000000002'
const body={expected_version:0,reason:'SYNTHETIC cancel reason'},path=`/notification-routing/requests/${id}/cancel`
function storage(){const values=new Map<string,string>();return{values,getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>values.set(key,value),removeItem:(key:string)=>{values.delete(key)}}}
function receipt(){return{kind:'REQUEST',id,version:1,command_id:key,recorded_at:'2026-10-24T12:00:00Z',state:{status:'CANCELLED',cancellation_reason:body.reason,external_dispatch_enabled:false}}}

it('lost cancellation recovers the same exact command after reconstruction without automatically resending',async()=>{
 const local=storage(),request=vi.fn().mockRejectedValueOnce(new ApiError(504,'TIMEOUT','synthetic','unit')).mockResolvedValue(receipt())
 const first=createControlCommands({request},local,()=>{},()=>key);first.reset('same-stable-account');await first.submit(path,body,'REQUEST',id)
 expect(first.state().pending?.body).toEqual(body);first.dispose()
 const restored=createControlCommands({request},local,()=>{},()=>key);restored.reset('same-stable-account')
 expect(request).toHaveBeenCalledTimes(1);expect(await restored.recover()).toBe(true)
 expect(request.mock.calls[1][0]).toBe('/notification-routing/commands/'+key);expect(request.mock.calls[1][1]?.method).toBeUndefined()
 expect(local.values.size).toBe(0)
})
it('cancel receipt must match status, exact reason, object and original command',async()=>{
 for(const patch of [{status:'ROUTED'},{cancellation_reason:'different'},{external_dispatch_enabled:true}]){
  const local=storage(),request=vi.fn().mockResolvedValue({...receipt(),state:{...receipt().state,...patch}})
  const commands=createControlCommands({request},local,()=>{},()=>key);commands.reset('unit')
  expect(await commands.submit(path,body,'REQUEST',id)).toBe(false);expect(commands.state().pending?.id).toBe(key)
 }
})
it('invalid cancel scope and unexpected body fields are rejected before storage or transport',async()=>{
 const request=vi.fn(),local=storage(),commands=createControlCommands({request},local,()=>{},()=>key);commands.reset('unit')
 for(const input of [{...body,expected_version:-1},{...body,reason:' '},{...body,tenant_id:id}])expect(await commands.submit(path,input,'REQUEST',id)).toBe(false)
 expect(await commands.submit(path,body,'ROUTE',id)).toBe(false)
 expect(request).not.toHaveBeenCalled();expect(local.values.size).toBe(0)
})
