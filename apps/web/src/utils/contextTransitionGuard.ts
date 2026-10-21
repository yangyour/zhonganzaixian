/** Voluntary tenant/logout transitions only; forced session expiry must still clear protected data. */
export function createContextTransitionGuards() {
  type Guard=()=>boolean|Promise<boolean>
  type Snapshot=()=>string|number|boolean|null|undefined
  const guards = new Map<Guard,Snapshot|undefined>()
  let checking = false
  return {
    register(guard:Guard,snapshot?:Snapshot) { guards.set(guard,snapshot); return () => { guards.delete(guard) } },
    async approve() {
      if (checking) return false
      checking = true
      try {
        const entries=[...guards],before=entries.map(([,snapshot])=>snapshot?.())
        for (const [guard] of entries) if (!await guard()) return false
        if(entries.length!==guards.size||entries.some(([guard,snapshot])=>!guards.has(guard)||guards.get(guard)!==snapshot))return false
        return entries.every(([,snapshot],i)=>!snapshot||Object.is(before[i],snapshot()))
      } catch { return false }
      finally { checking = false }
    },
  }
}
export const contextTransitionGuards = createContextTransitionGuards()
