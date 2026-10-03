/** Voluntary tenant/logout transitions only; forced session expiry must still clear protected data. */
export function createContextTransitionGuards() {
  const guards = new Set<() => boolean | Promise<boolean>>()
  let checking = false
  return {
    register(guard: () => boolean | Promise<boolean>) { guards.add(guard); return () => { guards.delete(guard) } },
    async approve() {
      if (checking) return false
      checking = true
      try { for (const guard of [...guards]) if (!await guard()) return false; return true }
      catch { return false }
      finally { checking = false }
    },
  }
}
export const contextTransitionGuards = createContextTransitionGuards()
