// @vitest-environment jsdom
// Composable mounted in Vue with an isolated request transport; no real account writes.
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, type App } from 'vue'
import { api, ApiError } from '../src/api/client'
import { useIdentityEditor } from '../src/utils/useIdentityEditor'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const id = '59000000-0000-4000-8000-000000000003'
let app: App | undefined, host: HTMLElement, editor: ReturnType<typeof useIdentityEditor>
function mount(reload: () => Promise<any>) { host = document.createElement('div'); document.body.appendChild(host); app = createApp(defineComponent({ setup() { editor = useIdentityEditor(reload); return () => h('div') } })); app.mount(host) }
function write() { return editor.save(`/identity/users/${id}/unlock`, 'POST', { expected_version: 1 }) }
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.restoreAllMocks() })
function accept() { return vi.spyOn(api, 'request').mockResolvedValue({ user_id: id, unlocked: true }) }
it('does not call a failed readback successful even after an accepted write', async () => {
 const request = accept(); mount(async () => false)
 expect(await write()).toBe(true); expect(editor.command.value.state).toBe('accepted'); expect(editor.message.value).not.toContain('最新记录已回读')
 expect(editor.message.value).toContain('尚未确认'); expect(editor.readbackFailed.value).toBe(true); expect(request).toHaveBeenCalledTimes(1)
})
it('a thrown readback error does not erase the accepted receipt or escape as a write failure', async () => {
 const request = accept(); mount(async () => { throw new Error('synthetic-readback-failure') })
 expect(await write()).toBe(true); expect(editor.command.value.state).toBe('accepted'); expect(editor.message.value).toContain('尚未确认'); expect(editor.readbackFailed.value).toBe(true); expect(request).toHaveBeenCalledTimes(1)
})
it('only an explicit successful readback confirms freshness', async () => {
 accept(); mount(async () => true); expect(await write()).toBe(true); expect(editor.message.value).toContain('最新记录已回读')
})
it('does not perform readback after an unconfirmed command', async () => {
 vi.spyOn(api, 'request').mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', 'lost', 'synthetic')); const reload = vi.fn(async () => true); mount(reload)
 expect(await write()).toBe(false); expect(reload).not.toHaveBeenCalled(); expect(editor.command.value.state).toBe('uncertain')
})

it('redirect completion does not pretend to have read fresh account records', async () => {
 accept(); mount(async () => 'redirected'); expect(await write()).toBe(true); expect(editor.message.value).toContain('请重新登录'); expect(editor.message.value).not.toContain('最新记录已回读'); expect(editor.readbackFailed.value).toBe(false)
})
it('blocks another write while accepted command readback remains pending', async () => {
 let finish!: (v: boolean) => void; const request = accept(); mount(() => new Promise(resolve => { finish = resolve }))
 const pending = write(); for (let i = 0; i < 6; i++) await Promise.resolve()
 expect(editor.busy()).toBe(true); expect(await write()).toBe(false); expect(await editor.retry()).toBe(false); expect(request).toHaveBeenCalledTimes(1)
 finish(true); expect(await pending).toBe(true); expect(editor.busy()).toBe(false)
})
it('ignores readback completion after the editor is unmounted', async () => {
 let finish!: (v: boolean) => void; accept(); mount(() => new Promise(resolve => { finish = resolve }))
 const pending = write(); for (let i = 0; i < 6; i++) await Promise.resolve()
 app!.unmount(); app = undefined; const previous = editor.message.value; finish(true); await pending
 expect(editor.command.value.state).toBe('idle'); expect(editor.message.value).toBe(previous)
})
