// @vitest-environment jsdom
// Real Vue / Element Plus input with synthetic layout measurements; browser layout is verified separately.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { ElForm } from 'element-plus'
import AutoTextarea from '../src/components/AutoTextarea.vue'

let app: App | undefined, host: HTMLElement
const observers: TestResizeObserver[] = []
class TestResizeObserver {
  targets = new Set<Element>()
  constructor(private callback: ResizeObserverCallback) { observers.push(this) }
  observe(target: Element) { this.targets.add(target) }
  unobserve(target: Element) { this.targets.delete(target) }
  disconnect() { this.targets.clear() }
  notify(target: Element, width: number) {
    if (this.targets.has(target)) this.callback([{ target, contentRect: { width } } as ResizeObserverEntry], this as unknown as ResizeObserver)
  }
}
const frames = new Map<number, FrameRequestCallback>()
let frameId = 0
async function settle() {
  for (let i = 0; i < 5; i++) {
    await nextTick()
    const scheduled = [...frames.values()]; frames.clear()
    scheduled.forEach(callback => callback(0))
  }
}
beforeEach(() => {
  observers.length = 0; frames.clear(); frameId = 0
  vi.stubGlobal('ResizeObserver', TestResizeObserver)
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.spyOn(HTMLTextAreaElement.prototype, 'scrollHeight', 'get').mockImplementation(function (this: HTMLTextAreaElement) {
    const width = Number.parseFloat(this.style.width) || 400
    const columns = Math.max(1, Math.floor(width / 10))
    const lines = this.value.split('\n').reduce((count, line) => count + Math.max(1, Math.ceil(line.length / columns)), 0)
    return lines * 20 + 10
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function mount(options: { minRows?: number; maxRows?: number; text?: string } = {}) {
  const state = reactive({ text: options.text || '', disabled: false, width: '400px' })
  const changed = vi.fn((value: string) => { state.text = value })
  const input = vi.fn()
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(ElForm, { disabled: state.disabled }, { default: () => h(AutoTextarea, {
    modelValue: state.text, minRows: options.minRows, maxRows: options.maxRows,
    'onUpdate:modelValue': changed, onInput: input, 'aria-label': '原始描述', placeholder: '填写描述',
    inputStyle: { width: state.width, lineHeight: '20px', paddingTop: '5px', paddingBottom: '5px', borderWidth: '1px', boxSizing: 'border-box' },
  }) }) })
  app.mount(host)
  return { state, changed, input, textarea: host.querySelector('textarea')! }
}
function resize(textarea: HTMLTextAreaElement, width: number) { observers.forEach(observer => observer.notify(textarea, width)) }

it('grows for multiline input, caps its height, and shrinks after deletion without rewriting the original text', async () => {
  const { textarea, state, changed, input } = mount(); await settle()
  expect(textarea.style.height).toBe('132px')
  const text = '  故障原文🙂\n'.repeat(8) + '末行  '
  textarea.value = text; textarea.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  expect(state.text).toBe(text); expect(changed).toHaveBeenLastCalledWith(text); expect(input).toHaveBeenLastCalledWith(text)
  expect(textarea.style.height).toBe('192px')
  state.text = '长文本\n'.repeat(40); await settle()
  expect(textarea.style.height).toBe('252px'); expect(textarea.value).toBe(state.text)
  textarea.value = '短记录'; textarea.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  expect(textarea.style.height).toBe('132px'); expect(state.text).toBe('短记录')
})

it('re-measures wrapping after width changes and after reopening at the same width', async () => {
  const { textarea, state, changed } = mount({ text: '长'.repeat(180) }); await settle()
  resize(textarea, 400); await settle(); expect(textarea.style.height).toBe('132px')
  state.width = '200px'; await settle(); resize(textarea, 200); await settle()
  expect(textarea.style.height).toBe('192px'); expect(changed).not.toHaveBeenCalled()
  resize(textarea, 0); state.width = '0px'; state.text = '长'.repeat(200); await settle()
  // A previously hidden control must measure again even when reopening at its old width.
  state.width = '200px'; await settle(); resize(textarea, 200); await settle()
  expect(textarea.style.height).toBe('212px')
  state.width = '400px'; await settle(); resize(textarea, 400); await settle()
  expect(textarea.style.height).toBe('132px'); expect(state.text).toBe('长'.repeat(200))
})

it('keeps custom row limits, accessibility attributes and form-level disabling', async () => {
  const { textarea, state } = mount({ minRows: 4, maxRows: 10 }); await settle()
  expect(textarea.style.height).toBe('92px'); expect(textarea.getAttribute('aria-label')).toBe('原始描述')
  expect(textarea.placeholder).toBe('填写描述'); expect(textarea.style.resize).toBe('none')
  state.text = '工时备注\n'.repeat(25); state.disabled = true; await settle()
  expect(textarea.style.height).toBe('212px'); expect(textarea.disabled).toBe(true)
  state.text = ''; state.disabled = false; await settle()
  expect(textarea.style.height).toBe('92px'); expect(textarea.disabled).toBe(false)
})

it('disconnects width observers and cancels pending measurement on unmount', async () => {
  const { textarea } = mount(); await settle()
  resize(textarea, 300); expect(frames.size).toBeGreaterThan(0)
  app!.unmount(); app = undefined
  expect(observers.every(observer => observer.targets.size === 0)).toBe(true)
  expect(frames.size).toBe(0)
})
