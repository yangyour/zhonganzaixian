// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, useRoute, useRouter, type Router } from 'vue-router'
import { useListDetailNavigation } from '../src/utils/useListDetailNavigation'

let app: App | undefined, host: HTMLElement, router: Router
let navigation: ReturnType<typeof useListDetailNavigation>
const scope = ref('tenant-a:user-a:v1')
const rows = ref([{ id: 'one' }, { id: 'two' }, { id: 'three' }])
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
const Harness = defineComponent({
  setup() {
    const route = useRoute(), router = useRouter(), list = ref<HTMLElement>(), detail = ref<HTMLElement>()
    navigation = useListDetailNavigation({
      route, router, key: 'record', scope: () => scope.value,
      signature: () => JSON.stringify([route.query.q, route.query.cursor]), rows: () => rows.value,
      allowed: () => scope.value !== 'revoked', list, detail,
    })
    return () => h('main', [
      h('section', { ref: list, style: { display: route.query.record ? 'none' : '' } }, [
        h('input', { 'aria-label': 'Search' }),
        h('div', { 'data-list-scroll': '', class: 'scroll' }, rows.value.map(row => h('button', {
          'data-list-record': row.id, onClick: (event: Event) => navigation.open(row.id, event),
        }, row.id))),
      ]),
      route.query.record ? h('section', { ref: detail }, [h('button', { onClick: () => navigation.close() }, 'Back')]) : null,
    ])
  },
})
async function mount(url = '/list?q=switch&cursor=page-two') {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/list', component: Harness }] })
  await router.push(url)
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host)
  await router.isReady(); await settle()
}
async function open(id = 'two') {
  const button = host.querySelector<HTMLButtonElement>(`[data-list-record="${id}"]`)!
  button.focus(); button.click()
  await vi.waitFor(() => expect(router.currentRoute.value.query.record).toBe(id)); await settle()
}
beforeEach(() => {
  scope.value = 'tenant-a:user-a:v1'; rows.value = [{ id: 'one' }, { id: 'two' }, { id: 'three' }]
  host = document.createElement('div'); document.body.append(host)
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  vi.stubGlobal('scrollY', 650); vi.stubGlobal('scrollX', 0)
})
afterEach(() => { app?.unmount(); host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('list/detail reading continuity', () => {
  it('preserves the applied query and cursor, and browser back returns to the list', async () => {
    await mount(); await open()
    expect(router.currentRoute.value.query).toMatchObject({ q: 'switch', cursor: 'page-two', record: 'two' })
    expect(document.activeElement?.textContent).toBe('Back')
    router.back(); await vi.waitFor(() => expect(router.currentRoute.value.query.record).toBeUndefined()); await settle()
    expect(document.activeElement?.getAttribute('data-list-record')).toBe('two')
    expect(window.scrollTo).toHaveBeenLastCalledWith({ left: 0, top: 650, behavior: 'instant' })
  })

  it('replaces adjacent detail history so one browser back still returns to the same list', async () => {
    await mount(); await open()
    expect(navigation.adjacent(-1)).toBe('one'); expect(navigation.adjacent(1)).toBe('three')
    await navigation.open('three'); await settle()
    expect(navigation.position.value).toBe(2); expect(navigation.adjacent(1)).toBeUndefined()
    router.back(); await vi.waitFor(() => expect(router.currentRoute.value.query.record).toBeUndefined())
    expect(router.currentRoute.value.query.cursor).toBe('page-two')
  })

  it('restores table scroll and focus after explicit close', async () => {
    await mount(); const scroller = host.querySelector<HTMLElement>('.scroll')!; scroller.scrollLeft = 230
    await open(); scroller.scrollLeft = 0
    await navigation.close(); await settle()
    expect(scroller.scrollLeft).toBe(230)
    expect(document.activeElement?.getAttribute('data-list-record')).toBe('two')
  })

  it('keeps a directly linked record inside this list on close', async () => {
    await mount('/list?q=switch&record=outside')
    const back = vi.spyOn(router, 'back')
    expect(navigation.position.value).toBe(-1); expect(navigation.adjacent(1)).toBeUndefined()
    await navigation.close(); await settle()
    expect(back).not.toHaveBeenCalled(); expect(router.currentRoute.value.fullPath).toBe('/list?q=switch')
  })

  it('uses the owned browser history entry for explicit close and releases a cancelled back', async () => {
    await mount(); await open()
    Object.defineProperty(router.options.history, 'state', { configurable: true, get: () => ({ back: '/list?q=switch&cursor=page-two' }) })
    const remove = router.beforeEach(() => false)
    await navigation.close()
    expect(router.currentRoute.value.query.record).toBe('two')
    expect(navigation.busy.value).toBe(false)
    remove(); await navigation.close(); await settle()
    expect(router.currentRoute.value.query.record).toBeUndefined()
    expect(document.activeElement?.getAttribute('data-list-record')).toBe('two')
  })

  it('does not navigate or discard history when an existing route guard refuses', async () => {
    await mount(); await open()
    const remove = router.beforeEach(() => false)
    expect(await navigation.open('three')).toBe(false)
    await navigation.close()
    expect(router.currentRoute.value.query.record).toBe('two'); expect(navigation.busy.value).toBe(false)
    remove(); await navigation.close(); await settle()
    expect(router.currentRoute.value.query.record).toBeUndefined()
  })

  it('suppresses repeated detail activation while navigation is awaiting a guard', async () => {
    await mount()
    let release!: () => void
    const remove = router.beforeEach(() => new Promise<void>(resolve => { release = resolve }))
    const first = navigation.open('one')
    await vi.waitFor(() => expect(release).toBeDefined())
    expect(await navigation.open('two')).toBe(false)
    release(); await first; remove()
    expect(router.currentRoute.value.query.record).toBe('one')
  })

  it('clears restoration and previous-row context when authorization changes', async () => {
    await mount(); await open(); vi.mocked(window.scrollTo).mockClear()
    scope.value = 'tenant-b:user-b:v2'; rows.value = []; await settle()
    await navigation.close(); await settle()
    expect(window.scrollTo).not.toHaveBeenCalled(); expect(navigation.adjacent(1)).toBeUndefined()
    scope.value = 'revoked'; await settle(); expect(await navigation.open('one')).toBe(false)
  })

  it('does not restore an old viewport after the applied query changes', async () => {
    await mount(); await open()
    await router.replace('/list?q=changed&record=two'); await settle(); vi.mocked(window.scrollTo).mockClear()
    await navigation.close(); await settle()
    expect(window.scrollTo).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query.q).toBe('changed')
  })
})
