import { getCurrentInstance, onBeforeUnmount, ref, shallowRef } from 'vue'
import { onBeforeRouteLeave, type Router } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { contextTransitionGuards } from './contextTransitionGuard'
import { createIdentityCommand, type IdentityCommand } from './projectIdentity'

export function useIdentityEditor(reload: () => Promise<boolean | 'redirected'>, options: { snapshot?: () => string; preserveUnknown?: boolean; validateResult?: (path:string,body:unknown,result:unknown)=>void } = {}) {
  const command = shallowRef<IdentityCommand>({ state: 'idle', path: null, method: null, key: null, body: null, result: null, error: null })
  const dirty = ref(false), message = ref(''), readbackFailed = ref(false), reading = ref(false)
  let disposed = false, epoch = 0
  const confirming = ref(false)
  const router = getCurrentInstance()?.appContext.config.globalProperties.$router as Router | undefined
  let routeEpoch=0, removeResolve:(()=>void)|undefined
  const clearRouteApproval=()=>{routeEpoch++;removeResolve?.();removeResolve=undefined}
  const inputSnapshot=()=>JSON.stringify([epoch,dirty.value,command.value.state,options.snapshot?.()])
  const writer = createIdentityCommand(api, value => { command.value = value }, undefined, options.validateResult)
  async function leave() {
    if (disposed || confirming.value || reading.value || command.value.state === 'sending') return false
    if (options.preserveUnknown && writer.unresolved()) { message.value='原操作结果尚未确认，请先使用原请求恢复；不会丢弃重试凭据。'; return false }
    if (!dirty.value && !writer.unresolved()) return true
    const before=inputSnapshot(),turn=epoch;confirming.value=true
    try { await ElMessageBox.confirm(writer.unresolved() ? '原操作结果尚未确认。离开将清除内存中的重试凭据；稍后请核对记录，避免重复提交。仍要离开吗？' : '尚有未保存的内容，确认放弃吗？', '离开当前页面', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }); return !disposed&&turn===epoch&&before===inputSnapshot()&&!reading.value } catch { return false }
    finally { if(turn===epoch)confirming.value=false }
  }
  async function leaveRoute(){
    clearRouteApproval();const turn=routeEpoch,before=inputSnapshot()
    if(!await leave()||disposed||turn!==routeEpoch||before!==inputSnapshot())return false
    if(router)removeResolve=router.beforeResolve(()=>!disposed&&turn===routeEpoch&&before===inputSnapshot()&&!(options.preserveUnknown&&writer.unresolved()))
    return true
  }
  onBeforeRouteLeave(leaveRoute)
  const removeAfter=router?.afterEach(clearRouteApproval),removeError=router?.onError(clearRouteApproval)
  const unregister = contextTransitionGuards.register(leave,inputSnapshot)
  const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value || writer.unresolved()) { event.preventDefault(); event.returnValue = '' } }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => { disposed = true; epoch++; clearRouteApproval(); removeAfter?.(); removeError?.(); unregister(); writer.dispose(); window.removeEventListener('beforeunload', beforeUnload) })
  async function accepted(ok: boolean, turn: number, before: string | undefined) {
    if (!ok || disposed || turn !== epoch) return false
    if(options.snapshot&&before!==options.snapshot()){readbackFailed.value=true;message.value='操作已保存，但编辑内容已变化；当前输入已保留，请先核对原操作回执。';return false}
    dirty.value = false; readbackFailed.value = false; reading.value = true
    message.value = '操作已保存，正在回读最新记录。'
    let result: boolean | 'redirected' = false
    try { result = await reload() } catch { /* The accepted write remains accepted even if refresh fails. */ } finally { if (turn === epoch) reading.value = false }
    if (disposed || turn !== epoch) return false
    readbackFailed.value = result !== true && result !== 'redirected'
    message.value = result === true ? '操作已保存，最新记录已回读。' : result === 'redirected' ? '操作已保存，请重新登录。' : '操作已保存，但最新记录尚未确认；请重新读取，不要重复提交。'
    return ok
  }
  return { command, dirty, message, readbackFailed, leave, busy: () => confirming.value || ['sending','uncertain'].includes(command.value.state) || reading.value, async save(path: string, method: string, body: unknown) { if (disposed || confirming.value || reading.value) return false; const turn = epoch, before=options.snapshot?.(); message.value = ''; readbackFailed.value = false; return accepted(await writer.submit(path, method, body), turn, before) }, async retry() { if (disposed || confirming.value || reading.value) return false; const turn = epoch, before=options.snapshot?.(); return accepted(await writer.retry(), turn, before) }, clear() { epoch++; confirming.value=false; clearRouteApproval(); writer.clear(); dirty.value = false; reading.value = false; readbackFailed.value = false; message.value = '' } }
}
