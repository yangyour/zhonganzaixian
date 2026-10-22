import { computed, nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { isNavigationFailure, type RouteLocationNormalizedLoaded, type Router } from 'vue-router'

/** Keep a list's reading position in memory, within the current authorization scope. */
export function useListDetailNavigation(options: {
  route: RouteLocationNormalizedLoaded
  router: Router
  key: string
  scope: () => string
  signature: () => string
  rows: () => { id: string }[]
  allowed: () => boolean
  list: Ref<HTMLElement | undefined>
  detail?: Ref<HTMLElement | undefined>
  restoreAfterClose?: boolean
}) {
  const { route, router } = options
  const currentId = computed(() => typeof route.query[options.key] === 'string' ? route.query[options.key] as string : '')
  const position = computed(() => options.rows().findIndex(row => row.id === currentId.value))
  const total = computed(() => options.rows().length)
  const busy = ref(false)
  let epoch = 0
  let disposed = false
  let origin: {
    scope: string
    signature: string
    path: string
    id: string
    trigger: HTMLElement | null
    x: number
    y: number
    scrolls: { element: HTMLElement; left: number; top: number }[]
  } | undefined

  function capture(id: string, trigger: HTMLElement | null = null) {
    const query = { ...route.query, [options.key]: undefined }
    origin = {
      scope: options.scope(), signature: options.signature(),
      path: router.resolve({ path: route.path, query, hash: route.hash }).fullPath,
      id, trigger: trigger ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null),
      x: window.scrollX, y: window.scrollY,
      scrolls: Array.from(options.list.value?.querySelectorAll<HTMLElement>('.el-scrollbar__wrap, .table-frame, [data-list-scroll]') ?? [])
        .map(element => ({ element, left: element.scrollLeft, top: element.scrollTop })),
    }
  }

  async function restore() {
    const turn = epoch
    await nextTick()
    if (disposed || turn !== epoch || currentId.value || !options.allowed()) return
    const saved = origin
    if (!saved || saved.scope !== options.scope() || saved.signature !== options.signature()) return
    const matching = Array.from(options.list.value?.querySelectorAll<HTMLElement>('[data-list-record]') ?? [])
      .find(element => element.dataset.listRecord === saved.id)
    const trigger = saved.trigger?.isConnected && options.list.value?.contains(saved.trigger) ? saved.trigger : matching
    const target = trigger ?? options.list.value?.querySelector<HTMLElement>('input, button, [tabindex="0"]')
    target?.focus({ preventScroll: true })
    for (const item of saved.scrolls) {
      if (item.element.isConnected) {
        item.element.scrollLeft = item.left
        item.element.scrollTop = item.top
      }
    }
    window.scrollTo({ left: saved.x, top: saved.y, behavior: 'instant' })
  }

  watch(currentId, async (id, previous) => {
    if (!id) {
      if (previous && options.restoreAfterClose !== false) await restore()
      return
    }
    // Back/forward can reopen a previously visited detail without calling open().
    if (previous === '' && origin && options.allowed()) capture(id, origin.trigger)
    const turn = epoch
    await nextTick()
    if (disposed || turn !== epoch || currentId.value !== id || !options.allowed()) return
    const firstAction = options.detail?.value?.querySelector<HTMLElement>('button')
    if (firstAction) {
      firstAction.focus({ preventScroll: true })
      window.scrollTo({ left: 0, top: 0, behavior: 'instant' })
    }
  })
  watch(options.scope, () => { epoch++; origin = undefined }, { flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; epoch++; origin = undefined })

  async function open(id: string, event?: Event) {
    if (!id || id === currentId.value || busy.value || !options.allowed()) return false
    const fromList = !currentId.value
    const prior = origin
    if (fromList) capture(id, event?.currentTarget instanceof HTMLElement ? event.currentTarget : null)
    busy.value = true
    const turn = epoch
    try {
      const result = await router[fromList ? 'push' : 'replace']({ query: { ...route.query, [options.key]: id } })
      if (isNavigationFailure(result)) {
        if (fromList && turn === epoch) origin = prior
        return false
      }
      return turn === epoch && !disposed
    } catch (cause) {
      if (fromList && turn === epoch) origin = prior
      throw cause
    } finally { busy.value = false }
  }

  async function close() {
    if (!currentId.value || busy.value || !options.allowed()) return
    busy.value = true
    try {
      const saved = origin
      // Only go back to this exact list entry. A deep link must never leave the app.
      if (saved && saved.scope === options.scope() && saved.signature === options.signature()
          && router.options?.history?.state?.back === saved.path) {
        await new Promise<void>((resolve, reject) => {
          const removeAfter = router.afterEach(() => { cleanup(); resolve() })
          const removeError = router.onError(error => { cleanup(); reject(error) })
          const cleanup = () => { removeAfter(); removeError() }
          router.back()
        })
      } else {
        await router.replace({ query: { ...route.query, [options.key]: undefined } })
      }
    } finally { busy.value = false }
  }

  function adjacent(direction: -1 | 1) {
    if (position.value < 0) return undefined
    return options.rows()[position.value + direction]?.id
  }
  return { busy, position, total, open, close, restore, adjacent }
}
