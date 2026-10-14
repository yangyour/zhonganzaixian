import { nextTick, onScopeDispose, watch } from 'vue'

function revealElement(selector: string) {
  const region = document.querySelector<HTMLElement>(selector)
  if (!region) return
  region.classList.add('workspace-reveal')
  const heading = region.querySelector<HTMLElement>('h2, h3') ?? region
  heading.tabIndex = -1
  heading.focus({ preventScroll: true })
  region.scrollIntoView?.({ block: 'start', behavior: 'auto' })
}

export async function revealWorkspace(selector: string) {
  await nextTick()
  revealElement(selector)
}

/** Reveal a newly selected workspace, without stealing focus during refreshes. */
export function useWorkspaceReveal(selection: () => string | null | undefined, selector: string) {
  let active = true
  onScopeDispose(() => { active = false })
  watch(selection, async (value, previous) => {
    if (!value || value === previous) return
    await nextTick()
    if (!active || selection() !== value) return
    revealElement(selector)
  })
}
