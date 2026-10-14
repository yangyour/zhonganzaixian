export type ResumePhase = 'ready' | 'suspended' | 'checking' | 'failed'
/** A cached document is not evidence that its old session is still authorized. */
export function createPageResumeGuard(options: { hasSession: () => boolean; verify: () => Promise<void>; mask: (value: boolean) => void; changed: (phase: ResumePhase) => void }) {
  let phase: ResumePhase = 'ready', epoch = 0, disposed = false
  const update = (value: ResumePhase) => { phase = value; options.changed(value) }
  return {
    suspend() {
      if (disposed || !options.hasSession()) return
      epoch++; options.mask(true); update('suspended')
    },
    async resume() {
      if (disposed || phase === 'checking' || phase === 'ready' && !options.hasSession()) return false
      const turn = ++epoch
      options.mask(true); update('checking')
      try {
        await options.verify()
        if (disposed || turn !== epoch) return false
        update('ready'); options.mask(false); return true
      } catch {
        if (disposed || turn !== epoch) return false
        if (!options.hasSession()) { update('ready'); options.mask(false) }
        else update('failed')
        return false
      }
    },
    dispose() { disposed = true; epoch++; options.mask(false) },
  }
}
