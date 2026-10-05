import { onBeforeUnmount, ref, shallowRef } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { contextTransitionGuards } from './contextTransitionGuard'
import { createIdentityCommand, type IdentityCommand } from './projectIdentity'

export function useIdentityEditor(reload: () => Promise<boolean | 'redirected'>) {
  const command = shallowRef<IdentityCommand>({ state: 'idle', path: null, method: null, key: null, body: null, result: null, error: null })
  const dirty = ref(false), message = ref(''), readbackFailed = ref(false), reading = ref(false)
  let disposed = false
  const writer = createIdentityCommand(api, value => { command.value = value })
  async function leave() {
    if (command.value.state === 'sending') return false
    if (!dirty.value && !writer.unresolved()) return true
    try { await ElMessageBox.confirm(writer.unresolved() ? '原操作结果尚未确认。离开将清除内存中的重试凭据；稍后请核对记录，避免重复提交。仍要离开吗？' : '尚有未保存的内容，确认放弃吗？', '离开当前页面', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
  }
  onBeforeRouteLeave(leave)
  const unregister = contextTransitionGuards.register(leave)
  const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value || writer.unresolved()) { event.preventDefault(); event.returnValue = '' } }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => { disposed = true; unregister(); writer.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
  async function accepted(ok: boolean) {
    if (!ok || disposed) return ok
    dirty.value = false; readbackFailed.value = false; reading.value = true
    message.value = '操作已保存，正在回读最新记录。'
    let result: boolean | 'redirected' = false
    try { result = await reload() } catch { /* The accepted write remains accepted even if refresh fails. */ } finally { reading.value = false }
    if (disposed) return ok
    readbackFailed.value = result !== true && result !== 'redirected'
    message.value = result === true ? '操作已保存，最新记录已回读。' : result === 'redirected' ? '操作已保存，请重新登录。' : '操作已保存，但最新记录尚未确认；请重新读取，不要重复提交。'
    return ok
  }
  return { command, dirty, message, readbackFailed, leave, busy: () => writer.unresolved() || reading.value, async save(path: string, method: string, body: unknown) { if (reading.value) return false; message.value = ''; readbackFailed.value = false; return accepted(await writer.submit(path, method, body)) }, async retry() { if (reading.value) return false; return accepted(await writer.retry()) }, clear: writer.clear }
}
