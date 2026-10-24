import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export type HorizontalScrollDirection = 'left' | 'right' | 'start' | 'end'
const keyDirections: Partial<Record<string, HorizontalScrollDirection>> = { ArrowLeft: 'left', ArrowRight: 'right', Home: 'start', End: 'end' }

/**
 * Horizontal overflow state and paging for a table viewport, shared by AppTable and native wide tables
 * so both show the same visible hint, step buttons and Arrow/Home/End keys. The viewport may come and go
 * (for example while a page reloads); listeners follow it and the hint hides while it is absent.
 */
export function useHorizontalScroll(viewport: Ref<HTMLElement | null | undefined>, content: (viewport: HTMLElement) => Element | null = element => element.firstElementChild) {
  const overflow = ref(false), atStart = ref(true), atEnd = ref(true)
  let attached: HTMLElement | null = null
  let observer: ResizeObserver | undefined
  function measure() {
    if (!attached) return
    const maximum = Math.max(0, attached.scrollWidth - attached.clientWidth)
    overflow.value = attached.clientWidth > 0 && maximum > 2
    atStart.value = attached.scrollLeft <= 2
    atEnd.value = attached.scrollLeft >= maximum - 2
  }
  function scroll(direction: HorizontalScrollDirection) {
    if (!attached) return
    const maximum = Math.max(0, attached.scrollWidth - attached.clientWidth)
    const step = Math.max(120, attached.clientWidth * .7)
    const left = direction === 'start' ? 0 : direction === 'end' ? maximum : attached.scrollLeft + (direction === 'left' ? -step : step)
    attached.scrollLeft = Math.min(maximum, Math.max(0, left))
    measure()
  }
  function onKeydown(event: KeyboardEvent) {
    // Keep links, inputs and other controls inside the table in charge of their own keys.
    if (event.target !== event.currentTarget || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || !overflow.value) return
    const direction = keyDirections[event.key]
    if (direction) { event.preventDefault(); scroll(direction) }
  }
  function detach() {
    observer?.disconnect(); observer = undefined
    attached?.removeEventListener('scroll', measure)
    window.removeEventListener('resize', measure)
    attached = null
    overflow.value = false; atStart.value = true; atEnd.value = true
  }
  watch(viewport, element => {
    detach()
    if (!element) return
    attached = element
    element.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', measure)
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(measure)
      observer.observe(element)
      const inner = content(element)
      if (inner) observer.observe(inner)
    }
    measure()
  }, { immediate: true, flush: 'post' })
  onBeforeUnmount(detach)
  return { overflow, atStart, atEnd, scroll, onKeydown }
}
