/** Intercept only an unmodified primary activation; retain native link affordances. */
export function navigateFromLink(event: MouseEvent, navigate: () => unknown): unknown {
  if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  const anchor = event.currentTarget as HTMLAnchorElement | null
  const target = anchor?.getAttribute('target')
  if (anchor?.hasAttribute('download') || target && target.toLowerCase() !== '_self') return
  event.preventDefault()
  return navigate()
}
