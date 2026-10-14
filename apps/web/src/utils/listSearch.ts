/** Keep unsubmitted edits when an unrelated applied filter or cursor changes. */
export function syncFilterDraft<Key extends string>(draft: Record<Key, string>, applied: Record<Key, string>, previous?: Record<Key, string>) {
  for (const key of Object.keys(applied) as Key[]) {
    if (!previous || applied[key] !== previous[key]) draft[key] = applied[key]
  }
}

/** The candidate-selection Enter in an IME must never submit a list query. */
export function createSearchCompositionGuard() {
  let composing = false
  return {
    start() { composing = true },
    end() { composing = false },
    isComposing: () => composing,
    keydown(event: KeyboardEvent) {
      if (event.key === 'Enter' && (composing || event.isComposing || event.keyCode === 229)) event.preventDefault()
    },
  }
}
