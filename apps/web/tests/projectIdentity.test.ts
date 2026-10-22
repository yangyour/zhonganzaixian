import { describe, expect, it, vi } from 'vitest'
import { ApiError, type ApiClient } from '../src/api/client'
import { createIdentityCommand, validateIdentityWrite, type IdentityCommand } from '../src/utils/projectIdentity'
const id = '55000000-0000-4000-8000-000000000001'
const reset = { expected_version: 7, new_password: 'synthetic-memory-only' }
const receipt = { user_id: id, password_reset: true, sessions_revoked: true }
function setup(request: ReturnType<typeof vi.fn>) { let state: IdentityCommand | undefined; const notify = vi.fn((v: IdentityCommand) => { state = v }); const editor = createIdentityCommand({ request } as unknown as Pick<ApiClient, 'request'>, notify, () => 'original-key'); return { editor, notify, get state() { return state! } } }
describe('project identity command recovery', () => {
  it('replays the original secret command with one key after response loss without displaying it', async () => {
    const request = vi.fn().mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'lost', 'trace')).mockResolvedValueOnce(receipt), ctx = setup(request), body = { ...reset }
    expect(await ctx.editor.submit(`/identity/users/${id}/password`, 'POST', body)).toBe(false)
    body.new_password = 'edited-after-send'; expect(ctx.state.state).toBe('uncertain'); expect(ctx.state.body).toBeNull()
    expect(await ctx.editor.submit(`/identity/users/${id}/password`, 'POST', body)).toBe(false)
    expect(await ctx.editor.retry()).toBe(true)
    expect(request.mock.calls[0]![1].body).toEqual(reset); expect(request.mock.calls[1]![1].body).toEqual(reset)
    expect(request.mock.calls[1]![1].idempotencyKey).toBe('original-key'); expect(JSON.stringify(ctx.notify.mock.calls)).not.toContain(reset.new_password)
  })
  it.each([{ ...receipt, user_id: id.replace(/1$/, '2') }, { password_reset: true }, { ...receipt, password_hash: 'forbidden' }])('does not accept malformed or secret-bearing successful receipts', async raw => {
    const ctx = setup(vi.fn().mockResolvedValue(raw)); expect(await ctx.editor.submit(`/identity/users/${id}/password`, 'POST', reset)).toBe(false); expect(ctx.state.state).toBe('uncertain'); ctx.editor.dispose()
  })
  it('cannot infer the original write failed from a later permission denial', async () => {
    const ctx = setup(vi.fn().mockRejectedValueOnce(new ApiError(0,'NETWORK_ERROR','lost','trace')).mockRejectedValueOnce(new ApiError(403,'ACTION_FORBIDDEN','revoked','trace')))
    await ctx.editor.submit(`/identity/users/${id}/password`,'POST',reset); await ctx.editor.retry(); expect(ctx.state.state).toBe('uncertain'); ctx.editor.dispose()
  })
  it('allows editing after a definite first CAS rejection', async () => {
    const ctx = setup(vi.fn().mockRejectedValue(new ApiError(409,'VERSION_CONFLICT','changed','trace'))); await ctx.editor.submit(`/identity/users/${id}/password`,'POST',reset); expect(ctx.state.state).toBe('rejected'); expect(ctx.editor.unresolved()).toBe(false)
  })
  it('ignores late success after context disposal and clears sensitive original state', async () => {
    let finish!: (value: unknown) => void; const ctx = setup(vi.fn().mockImplementation(() => new Promise(resolve => { finish = resolve })))
    const pending = ctx.editor.submit(`/identity/users/${id}/password`,'POST',reset); ctx.editor.dispose(); finish(receipt); expect(await pending).toBe(false); expect(ctx.state.state).toBe('idle'); expect(await ctx.editor.retry()).toBe(false)
  })
  it('blocks duplicate concurrent submissions', async () => {
    let finish!: (value: unknown) => void; const request = vi.fn().mockImplementation(() => new Promise(resolve => { finish = resolve })), ctx = setup(request)
    const pending = ctx.editor.submit(`/identity/users/${id}/password`,'POST',reset); expect(await ctx.editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(false); finish(receipt); await pending; expect(request).toHaveBeenCalledTimes(1)
  })
  it('validates explicit null policy responses and exact version', () => {
    const input = { expected_version: 1, minimum_password_length: null, force_change_on_reset: null }
    expect(() => validateIdentityWrite('/identity/policy', input, { version: 2, minimum_password_length: 15, force_change_on_reset: null })).toThrow()
    expect(() => validateIdentityWrite('/identity/policy', input, { version: 2, minimum_password_length: null, force_change_on_reset: null })).not.toThrow()
  })
})

describe('identity command notification reentrancy', () => {
  it('disposal becomes terminal before its idle notification', async () => {
    const request = vi.fn().mockResolvedValue(receipt)
    let react = false, nested: Promise<boolean> | undefined
    const editor = createIdentityCommand({ request } as unknown as Pick<ApiClient, 'request'>, value => {
      if (react && value.state === 'idle') { react = false; nested = editor.submit(`/identity/users/${id}/password`, 'POST', reset) }
    })
    react = true; editor.dispose()
    expect(await nested).toBe(false); expect(request).not.toHaveBeenCalled()
  })
  it('does not overwrite a nested submission started by its initial idle notification', async () => {
    let react = true, nested: Promise<boolean> | undefined, finish!: (value: unknown) => void
    const request = vi.fn().mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const editor = createIdentityCommand({ request } as unknown as Pick<ApiClient, 'request'>, value => {
      if (react && value.state === 'idle') { react = false; nested = editor.submit(`/identity/users/${id}/password`, 'POST', { ...reset, new_password: 'synthetic-nested' }) }
    })
    const outer = editor.submit(`/identity/users/${id}/password`, 'POST', reset)
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0]![1].body.new_password).toBe('synthetic-nested')
    finish(receipt); expect(await nested).toBe(true); expect(await outer).toBe(false); editor.dispose()
  })
  it('retains the nested original body for recovery after response loss', async () => {
    let react = true, nested: Promise<boolean> | undefined
    const request = vi.fn().mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'lost', 'trace')).mockResolvedValueOnce(receipt)
    const editor = createIdentityCommand({ request } as unknown as Pick<ApiClient, 'request'>, value => {
      if (react && value.state === 'idle') { react = false; nested = editor.submit(`/identity/users/${id}/password`, 'POST', { ...reset, new_password: 'synthetic-nested' }) }
    })
    expect(await editor.submit(`/identity/users/${id}/password`, 'POST', reset)).toBe(false)
    expect(await nested).toBe(false); expect(await editor.retry()).toBe(true)
    expect(request.mock.calls[1]![1].body).toEqual(request.mock.calls[0]![1].body)
    expect(request.mock.calls[1]![1].idempotencyKey).toBe(request.mock.calls[0]![1].idempotencyKey)
    editor.dispose()
  })
  it('aborts preparation if its idle notification clears the command again', async () => {
    let react = true
    const request = vi.fn().mockResolvedValue(receipt)
    const editor = createIdentityCommand({ request } as unknown as Pick<ApiClient, 'request'>, value => {
      if (react && value.state === 'idle') { react = false; editor.clear() }
    })
    expect(await editor.submit(`/identity/users/${id}/password`, 'POST', reset)).toBe(false)
    expect(request).not.toHaveBeenCalled(); editor.dispose()
  })
  it('does not dispatch if the sending notification disposes the command', async () => {
    const request = vi.fn().mockResolvedValue(receipt)
    const editor = createIdentityCommand({ request } as unknown as Pick<ApiClient, 'request'>, value => { if (value.state === 'sending') editor.dispose() })
    expect(await editor.submit(`/identity/users/${id}/password`, 'POST', reset)).toBe(false)
    expect(request).not.toHaveBeenCalled(); expect(editor.unresolved()).toBe(false)
  })
})
describe('local identity request preparation',()=>{
 it('reports local key failure as not sent without throwing or disclosing the exception',async()=>{
  const request=vi.fn(),notify=vi.fn();const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=>{throw Error('synthetic-secret-in-exception')});expect(await editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(false);expect(request).not.toHaveBeenCalled();expect(editor.unresolved()).toBe(false);expect(notify.mock.calls.at(-1)![0].state).toBe('rejected');expect(notify.mock.calls.at(-1)![0].error.code).toBe('REQUEST_PREPARATION_FAILED');expect(JSON.stringify(notify.mock.calls)).not.toContain('synthetic-secret-in-exception');expect(JSON.stringify(notify.mock.calls)).not.toContain(reset.new_password)
 })
 it('fixes the original payload before a key generator can change the caller object',async()=>{
  const request=vi.fn().mockResolvedValue(receipt),body={...reset};const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,()=>{},()=>{body.new_password='changed by key factory';return 'original-key'});expect(await editor.submit(`/identity/users/${id}/password`,'POST',body)).toBe(true);expect(request.mock.calls[0]![1].body).toEqual(reset)
 })
})
it('a preparation failure can be corrected and submitted with a fresh local key',async()=>{
 const request=vi.fn().mockResolvedValue(receipt),notify=vi.fn();let fail=true;const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=>{if(fail)throw Error('local');return 'new-key'});expect(await editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(false);expect(await editor.retry()).toBe(false);fail=false;expect(await editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(true);expect(request).toHaveBeenCalledTimes(1);expect(request.mock.calls[0]![1].idempotencyKey).toBe('new-key')
})
it('uncloneable input is rejected locally without generating a key or retaining a body',async()=>{
 const request=vi.fn(),notify=vi.fn(),key=vi.fn(()=> 'key');const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,key);expect(await editor.submit('/identity/roles','POST',{bad:()=>{}})).toBe(false);expect(key).not.toHaveBeenCalled();expect(request).not.toHaveBeenCalled();expect(notify.mock.calls.at(-1)![0].body).toBeNull();expect(editor.unresolved()).toBe(false)
})
it('a key failure after disposal does not restore an error into the cleared command',async()=>{
 const request=vi.fn(),notify=vi.fn();let editor:ReturnType<typeof createIdentityCommand>;editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=>{editor.dispose();throw Error('synthetic')});expect(await editor.submit('/identity/roles','POST',{})).toBe(false);expect(request).not.toHaveBeenCalled();expect(notify.mock.calls.at(-1)![0].state).toBe('idle');expect(notify.mock.calls.at(-1)![0].error).toBeNull()
})
it('an empty request key is a local preparation error, never an unresolved send',async()=>{
 const request=vi.fn(),notify=vi.fn();const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=> '');expect(await editor.submit('/identity/roles','POST',{})).toBe(false);expect(request).not.toHaveBeenCalled();expect(editor.unresolved()).toBe(false);expect(notify.mock.calls.at(-1)![0].error.code).toBe('REQUEST_PREPARATION_FAILED')
})
it('retains original input before the initial idle notification mutates the caller object',async()=>{
 const request=vi.fn().mockResolvedValue(receipt),body={...reset};const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,v=>{if(v.state==='idle')body.new_password='changed during idle notify'},()=> 'key');expect(await editor.submit(`/identity/users/${id}/password`,'POST',body)).toBe(true);expect(request.mock.calls[0]![1].body).toEqual(reset)
})
it('a caller getter that clears the intent prevents cloning from resuming under a new generation',async()=>{
 const request=vi.fn(),key=vi.fn(()=> 'key');let editor:ReturnType<typeof createIdentityCommand>;editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,()=>{},key);const body={get expected_version(){editor.clear();return 0}};expect(await editor.submit('/identity/roles','POST',body)).toBe(false);expect(key).not.toHaveBeenCalled();expect(request).not.toHaveBeenCalled()
})
it('preparation errors never overwrite a nested request started by an idle notification',async()=>{
 const request=vi.fn().mockResolvedValue(receipt);let nested:Promise<boolean>|undefined,react=true;let editor:ReturnType<typeof createIdentityCommand>;editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,v=>{if(v.state==='idle'&&react){react=false;nested=editor.submit(`/identity/users/${id}/password`,'POST',reset)}},()=> 'key');expect(await editor.submit('/identity/roles','POST',{bad:()=>{}})).toBe(false);expect(await nested).toBe(true);expect(request).toHaveBeenCalledTimes(1)
})
it('scoped result validation failure remains uncertain and never publishes an accepted receipt',async()=>{
 const request=vi.fn().mockResolvedValue(receipt),notify=vi.fn();const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=> 'key',()=>{throw new ApiError(200,'INVALID_SCOPE','invalid receipt','—')});expect(await editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(false);expect(notify.mock.calls.at(-1)![0].state).toBe('uncertain');expect(notify.mock.calls.at(-1)![0].result).toBeNull();editor.dispose()
})
it('receipt snapshots are detached from transport and notification consumers',async()=>{
 const raw={...receipt},request=vi.fn().mockResolvedValue(raw),notify=vi.fn();const editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=> 'key');expect(await editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(true);const result=notify.mock.calls.at(-1)![0].result;raw.user_id='changed transport';expect(result.user_id).toBe(id);expect(result).not.toBe(raw)
})
it('a scoped validation callback that disposes the command cannot publish its old result',async()=>{
 const request=vi.fn().mockResolvedValue(receipt),notify=vi.fn();let editor:ReturnType<typeof createIdentityCommand>;editor=createIdentityCommand({request} as unknown as Pick<ApiClient,'request'>,notify,()=> 'key',()=>editor.dispose());expect(await editor.submit(`/identity/users/${id}/password`,'POST',reset)).toBe(false);expect(notify.mock.calls.at(-1)![0].state).toBe('idle');expect(notify.mock.calls.at(-1)![0].result).toBeNull()
})
