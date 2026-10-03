import { describe,it,expect } from 'vitest'
import { ApiError } from '../src/api/client'
import { detachedWorkspaceState, detachedReadState } from '../src/utils/detachedWorkspaceState'

describe('business snapshot detachment',()=>{
 it('detaches nested original command, baseline, receipt and history data',()=>{const original={attempt:{key:'original',payload:'{}',baseline:{version:1,values:[{x:1}]}},receipt:{history:[{actor:'a'}]},error:null},copy=detachedWorkspaceState(original);copy.attempt.key='changed';copy.attempt.baseline.values[0]!.x=9;copy.receipt.history[0]!.actor='b';expect(original.attempt.key).toBe('original');expect(original.attempt.baseline.values[0]!.x).toBe(1);expect(original.receipt.history[0]!.actor).toBe('a')})
 it('new snapshots cannot mutate each other or the original state',()=>{const original={values:[{count:1}],error:null},a=detachedWorkspaceState(original),b=detachedWorkspaceState(original);a.values[0]!.count=5;expect(b.values[0]!.count).toBe(1);original.values[0]!.count=9;expect(b.values[0]!.count).toBe(1)})
 it('preserves ApiError prototype and original error classification',()=>{const error=new ApiError(403,'ACTION_FORBIDDEN','synthetic','trace');const result=detachedWorkspaceState({attempt:null,error});expect(result.error).toBe(error);expect(result.error).toBeInstanceOf(ApiError);expect(result.error.status).toBe(403)})
 it('preserves null and undefined without inventing business values',()=>{const value={a:null,b:undefined,optional:{value:false},error:undefined};expect(detachedWorkspaceState(value)).toEqual(value);expect(Object.hasOwn(detachedWorkspaceState(value),'b')).toBe(true)})
})

it('does not invent error fields on idle or ready union states',()=>{const idle=detachedReadState({status:'idle'});expect(idle).toEqual({status:'idle'});expect(Object.hasOwn(idle,'error')).toBe(false)})
it('keeps both primary and proof errors while detaching proof fields',()=>{const error=new ApiError(403,'DENIED','synthetic','t'),proofError=new ApiError(503,'UNAVAILABLE','synthetic','t'),original={proof:{next:10},error,proofError},copy=detachedReadState(original);copy.proof.next=99;expect(original.proof.next).toBe(10);expect(copy.error).toBe(error);expect(copy.proofError).toBe(proofError)})
