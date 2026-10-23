import { computed, onUnmounted, ref, watch, type Ref } from 'vue'
import { api } from '../api/client'
import { contextGeneration, session, switchingTenant } from '../stores/session'

/** Optional, server-authorized test inputs. Never replace edits or cross an identity change. */
export function useDemoExample<T>(page: string, permitted: Ref<boolean>, snapshot: () => string, apply: (input: T) => Promise<unknown> | unknown) {
  const loaded = ref(false), appliedInput = ref<string | null>(null)
  let generation = 0, controller: AbortController | null = null
  const pristine = computed(() => loaded.value && appliedInput.value === snapshot())
  const context = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.demo_examples_enabled, permitted.value, switchingTenant.value]))
  watch(context, async () => {
    const turn = ++generation, owner = context.value, current = session.value
    controller?.abort(); loaded.value = false; appliedInput.value = null
    if (!current || !permitted.value || switchingTenant.value || current.mode !== 'remote_test' || current.demo_examples_enabled !== true) return
    const initial = snapshot(), request = new AbortController(); controller = request
    try {
      const result = await api.request<{tenant_id: string; actor_id: string; page: string; available: boolean; source: string; input: T | null; business_data_written: boolean; provider_accessed: boolean}>(`/demo/examples/${page}`, {signal: request.signal})
      if (turn !== generation || context.value !== owner || request.signal.aborted || snapshot() !== initial) return
      if (!result.available || result.input === null) return
      if (result.tenant_id !== current.tenant.id || result.actor_id !== current.user.id || result.page !== page || result.source !== 'EXPLICIT_DEMO_INPUT' || result.business_data_written !== false || result.provider_accessed !== false) return
      const pending = apply(result.input), expectedInput = snapshot()
      await pending
      if (turn === generation && context.value === owner) { appliedInput.value = expectedInput; loaded.value = true }
    } catch { /* Optional demo input cannot hide errors from the page's actual business request. */ }
  }, {immediate: true, flush: 'post'})
  onUnmounted(() => { generation++; controller?.abort() })
  return { loaded, pristine }
}
