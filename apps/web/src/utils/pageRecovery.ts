/** A page refresh must observe the same pending-write guards as leaving the page. */
export function createPageReload(options: { approve: () => Promise<boolean>; generation: () => number; reload: () => void }) {
  let busy = false
  return async () => {
    if (busy) return false
    busy = true
    const generation = options.generation()
    try {
      if (!await options.approve() || generation !== options.generation()) return false
      options.reload()
      return true
    } catch { return false }
    finally { busy = false }
  }
}
