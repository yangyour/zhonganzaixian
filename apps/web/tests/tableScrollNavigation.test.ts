// @vitest-environment jsdom
// Real Vue / Element Plus tables with synthetic scroll dimensions; browser layout is verified separately.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { ElLoading, ElTableColumn } from 'element-plus'
import AppTable from '../src/components/AppTable.vue'

let app: App | undefined, host: HTMLElement
const observers: TestResizeObserver[] = []
class TestResizeObserver {
  targets = new Set<Element>()
  disconnected = false
  constructor(private callback: ResizeObserverCallback) { observers.push(this) }
  observe(target: Element) { this.targets.add(target) }
  unobserve(target: Element) { this.targets.delete(target) }
  disconnect() { this.targets.clear(); this.disconnected = true }
  notify(target: Element) {
    if (this.targets.has(target)) this.callback([{ target, contentRect: target.getBoundingClientRect() } as ResizeObserverEntry], this as unknown as ResizeObserver)
  }
}
beforeEach(() => { observers.length = 0; vi.stubGlobal('ResizeObserver', TestResizeObserver) })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function settle() { for (let i = 0; i < 5; i++) await nextTick() }
async function mount(clientWidth = 400, scrollWidth = 1000) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(AppTable, { rows: [{ id: 'synthetic-row', name: '合成宽表记录' }], label: '资产记录表格' }, {
    default: () => [
      h(ElTableColumn, { prop: 'name', label: '名称', width: 600 }),
      h(ElTableColumn, { label: '操作', width: 400 }, { default: () => [
        h('button', { 'data-control': 'button', type: 'button' }, '查看记录'),
        h('input', { 'data-control': 'input', 'aria-label': '表内输入' }),
      ] }),
    ],
  }) })
  app.directive('loading', ElLoading.directive); app.mount(host)
  const frame = host.querySelector<HTMLElement>('.table-frame')!
  const viewport = frame.querySelector<HTMLElement>('.el-scrollbar__wrap')!
  const content = viewport.querySelector<HTMLElement>('.el-scrollbar__view')!
  const dimensions = { clientWidth, scrollWidth }
  Object.defineProperties(viewport, {
    clientWidth: { configurable: true, get: () => dimensions.clientWidth },
    scrollWidth: { configurable: true, get: () => dimensions.scrollWidth },
  })
  await settle()
  const observer = observers.find(item => item.targets.has(viewport) && item.targets.has(content))!
  expect(observer).toBeDefined()
  return { frame, viewport, content, dimensions, observer }
}
const button = (direction: '左' | '右') => host.querySelector<HTMLButtonElement>(`button[aria-label="向${direction}查看表格列"]`)!
function key(target: HTMLElement, value: string, modifiers: KeyboardEventInit = {}) {
  const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...modifiers })
  target.dispatchEvent(event)
  return event
}

it('shows navigation only for a visible overflowing viewport and rechecks container and content size changes', async () => {
  const { frame, viewport, content, dimensions, observer } = await mount(400, 400)
  expect(host.querySelector('.table-scroll-tools')).toBeNull(); expect(frame.hasAttribute('aria-describedby')).toBe(false)
  expect(key(frame, 'ArrowRight').defaultPrevented).toBe(false)
  dimensions.scrollWidth = 1000; observer.notify(content); await settle()
  expect(host.querySelector('.table-scroll-tools')).not.toBeNull()
  expect(frame.getAttribute('aria-describedby')).toBe(host.querySelector('.table-scroll-tools>span')?.id)
  expect(button('右').getAttribute('aria-controls')).toBe(frame.id)
  dimensions.clientWidth = 1000; observer.notify(viewport); await settle()
  expect(host.querySelector('.table-scroll-tools')).toBeNull()
  dimensions.clientWidth = 0; observer.notify(viewport); await settle()
  expect(host.querySelector('.table-scroll-tools')).toBeNull()
  dimensions.clientWidth = 400; window.dispatchEvent(new Event('resize')); await settle()
  expect(host.querySelector('.table-scroll-tools')).not.toBeNull()
})

it('clamps navigation buttons and Arrow/Home/End keys at either edge and reflects native scrolling', async () => {
  const { frame, viewport } = await mount()
  expect(button('左').disabled).toBe(true); expect(button('右').disabled).toBe(false)
  button('右').click(); await settle(); expect(viewport.scrollLeft).toBe(280)
  expect(button('左').disabled).toBe(false)
  expect(key(frame, 'ArrowRight').defaultPrevented).toBe(true); await settle(); expect(viewport.scrollLeft).toBe(560)
  button('右').click(); await settle(); expect(viewport.scrollLeft).toBe(600); expect(button('右').disabled).toBe(true)
  expect(host.textContent).toContain('已到最后一列')
  expect(key(frame, 'ArrowLeft').defaultPrevented).toBe(true); await settle(); expect(viewport.scrollLeft).toBe(320)
  expect(key(frame, 'Home').defaultPrevented).toBe(true); await settle(); expect(viewport.scrollLeft).toBe(0)
  expect(button('左').disabled).toBe(true)
  button('左').click(); expect(viewport.scrollLeft).toBe(0)
  expect(key(frame, 'End').defaultPrevented).toBe(true); await settle(); expect(viewport.scrollLeft).toBe(600)
  expect(key(frame, 'ArrowRight').defaultPrevented).toBe(true); expect(viewport.scrollLeft).toBe(600)
  viewport.scrollLeft = 100; viewport.dispatchEvent(new Event('scroll')); await settle()
  expect(button('左').disabled).toBe(false); expect(button('右').disabled).toBe(false)
  button('左').click(); await settle(); expect(viewport.scrollLeft).toBe(0)
})

it('leaves table controls, modified shortcuts and unrelated keys in charge of their own behavior', async () => {
  const { frame, viewport } = await mount()
  const controls = host.querySelectorAll<HTMLElement>('[data-control]')
  expect(controls).toHaveLength(2)
  for (const control of controls) {
    for (const value of ['ArrowLeft', 'ArrowRight', 'Home', 'End']) expect(key(control, value).defaultPrevented).toBe(false)
  }
  for (const modifiers of [{ altKey: true }, { ctrlKey: true }, { metaKey: true }, { shiftKey: true }]) {
    expect(key(frame, 'ArrowRight', modifiers).defaultPrevented).toBe(false)
  }
  for (const value of ['Enter', 'Tab', 'ArrowDown', 'Escape']) expect(key(frame, value).defaultPrevented).toBe(false)
  await settle(); expect(viewport.scrollLeft).toBe(0)
})

it('disconnects its observer and removes both scrolling and window listeners on unmount', async () => {
  const { viewport, observer } = await mount()
  const removeScroll = vi.spyOn(viewport, 'removeEventListener')
  const removeWindow = vi.spyOn(window, 'removeEventListener')
  app!.unmount(); app = undefined
  expect(observer.disconnected).toBe(true); expect(observer.targets.size).toBe(0)
  expect(removeScroll).toHaveBeenCalledWith('scroll', expect.any(Function))
  expect(removeWindow).toHaveBeenCalledWith('resize', expect.any(Function))
})
