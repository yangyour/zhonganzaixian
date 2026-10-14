// @vitest-environment jsdom
import { createApp, h, withDirectives } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { formFeedback } from '../src/directives/formFeedback'
let stop = () => {}
afterEach(() => stop())
function mount() {
  const host = document.createElement('div'); document.body.append(host)
  const submit = vi.fn((event: Event) => event.preventDefault())
  const app = createApp({ render: () => withDirectives(h('form', { onSubmit: submit }, [
    h('label', ['名称', h('input', { required: true, 'aria-describedby': 'original-help' })]),
    h('label', ['邮箱', h('input', { type: 'email', required: true })]),
    h('button', { type: 'submit' }, '保存'),
  ]), [[formFeedback]]) })
  app.mount(host); stop = () => { app.unmount(); host.remove() }
  return { host, submit, form: host.querySelector('form')!, fields: [...host.querySelectorAll('input')] }
}
it('blocks invalid submission with Chinese field descriptions and focuses the first field', async () => {
  const { host, submit, form, fields } = mount()
  form.requestSubmit(); await Promise.resolve()
  expect(submit).not.toHaveBeenCalled()
  expect(document.activeElement).toBe(fields[0])
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('2 项')
  expect(fields[0]!.getAttribute('aria-invalid')).toBe('true')
  expect(fields[0]!.getAttribute('aria-describedby')).toContain('original-help mop-field-error-')
  expect(host.querySelector('.form-field-error')?.textContent).toBe('请填写此项。')
})
it('updates only marked fields and restores existing accessibility metadata after correction', async () => {
  const { host, form, fields } = mount()
  form.requestSubmit(); await Promise.resolve()
  fields[0]!.value = '合成名称'; fields[0]!.dispatchEvent(new Event('input', { bubbles: true }))
  expect(fields[0]!.hasAttribute('aria-invalid')).toBe(false)
  expect(fields[0]!.getAttribute('aria-describedby')).toBe('original-help')
  fields[1]!.value = 'invalid'; fields[1]!.dispatchEvent(new Event('input', { bubbles: true }))
  expect(host.querySelector('.form-field-error')?.textContent).toBe('请输入有效的邮箱地址。')
  fields[1]!.value = 'synthetic@example.invalid'; fields[1]!.dispatchEvent(new Event('input', { bubbles: true }))
  expect(host.querySelector('.form-error-summary')).toBeNull()
})
it('keeps a valid user submission intact without submitting a second request', () => {
  const { form, fields, submit } = mount()
  fields[0]!.value = '合成名称'; fields[1]!.value = 'synthetic@example.invalid'
  form.requestSubmit(); expect(submit).toHaveBeenCalledOnce()
})
it('clears errors on reset without modifying validation constraints', async () => {
  const { form, host, fields } = mount()
  form.requestSubmit(); await Promise.resolve(); form.reset()
  expect(host.querySelector('.form-field-error')).toBeNull()
  expect(fields[0]!.required).toBe(true)
})
