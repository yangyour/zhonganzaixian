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
