// @vitest-environment jsdom
import { createApp, h, nextTick, ref, withDirectives } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { formFeedback } from '../src/directives/formFeedback'
let stop = () => {}
afterEach(() => stop())
function mount() {
  const host = document.createElement('div'); document.body.append(host)
  const submit = vi.fn((event: Event) => event.preventDefault())
  const showName = ref(true), disableEmail = ref(false)
  const app = createApp({ render: () => withDirectives(h('form', { onSubmit: submit }, [
    showName.value ? h('label', { key: 'name' }, ['名称', h('input', { required: true, 'aria-describedby': 'original-help' })]) : null,
    h('label', { key: 'email' }, ['邮箱', h('input', { type: 'email', required: true, disabled: disableEmail.value })]),
    h('button', { type: 'submit' }, '保存'),
  ]), [[formFeedback]]) })
  app.mount(host); stop = () => { app.unmount(); host.remove() }
  return { host, submit, showName, disableEmail, form: host.querySelector('form')!, fields: [...host.querySelectorAll('input')] }
}
it('blocks invalid submission, focuses a named summary and links each Chinese error to its field', async () => {
  const { host, submit, form, fields } = mount()
  form.requestSubmit(); await Promise.resolve()
  expect(submit).not.toHaveBeenCalled()
  expect(document.activeElement).toBe(host.querySelector('.form-error-summary'))
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('2 项')
  const links = [...host.querySelectorAll<HTMLAnchorElement>('.form-error-summary a')]
  expect(links.map(link => link.textContent)).toEqual(['名称：请填写此项。', '邮箱：请填写此项。'])
  links[1]!.click()
  expect(document.activeElement).toBe(fields[1])
  expect(links[1]!.getAttribute('href')).toBe(`#${fields[1]!.id}`)
  expect(submit).not.toHaveBeenCalled()
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
  expect(fields[0]!.hasAttribute('id')).toBe(false)
})
it('preserves authored IDs, original errors and later-added help text on correction', async () => {
  const { form, fields } = mount()
  fields[0]!.id = 'authored-name'; fields[0]!.setAttribute('aria-invalid', 'grammar')
  form.requestSubmit(); await Promise.resolve()
  fields[0]!.setAttribute('aria-describedby', fields[0]!.getAttribute('aria-describedby') + ' later-help')
  fields[0]!.value = '合成名称'; fields[0]!.dispatchEvent(new Event('input', { bubbles: true }))
  expect(fields[0]!.id).toBe('authored-name')
  expect(fields[0]!.getAttribute('aria-describedby')).toBe('original-help later-help')
  expect(fields[0]!.getAttribute('aria-invalid')).toBe('grammar')
})
it('removes errors for removed or disabled fields after a Vue update', async () => {
  const { form, host, showName, disableEmail } = mount()
  form.requestSubmit(); await Promise.resolve()
  showName.value = false; await nextTick()
  expect(host.querySelector('.form-error-summary')?.textContent).toContain('1 项')
  expect(host.querySelector('.form-error-summary a')?.textContent).toBe('邮箱：请填写此项。')
  disableEmail.value = true; await nextTick()
  expect(host.querySelector('.form-error-summary')).toBeNull()
})
it('orders errors by form order even when validity events arrive in reverse', async () => {
  const { fields, host } = mount()
  fields[1]!.dispatchEvent(new Event('invalid', { cancelable: true }))
  fields[0]!.dispatchEvent(new Event('invalid', { cancelable: true }))
  await Promise.resolve()
  expect([...host.querySelectorAll('.form-error-summary a')].map(x => x.textContent)).toEqual(['名称：请填写此项。', '邮箱：请填写此项。'])
})
it('uses accessible labels as text and never copies entered values into the error summary', async () => {
  const { fields, form, host } = mount()
  fields[1]!.setAttribute('aria-label', '<b>联系邮箱</b>')
  fields[1]!.value = 'private-input-not-an-email'
  form.requestSubmit(); await Promise.resolve()
  const summary = host.querySelector('.form-error-summary')!
  expect(summary.textContent).toContain('<b>联系邮箱</b>：请输入有效的邮箱地址。')
  expect(summary.querySelector('b')).toBeNull()
  expect(summary.textContent).not.toContain('private-input')
})
it('does not recreate untouched errors or submit automatically while editing', () => {
  const { fields, host, submit } = mount()
  fields[1]!.value = 'invalid'
  fields[1]!.dispatchEvent(new Event('input', { bubbles: true }))
  expect(host.querySelector('.form-error-summary')).toBeNull()
  expect(submit).not.toHaveBeenCalled()
})
it('keeps existing error-link nodes stable while updating another marked field', async () => {
  const { fields, form, host } = mount()
  form.requestSubmit(); await Promise.resolve()
  const link = host.querySelectorAll<HTMLAnchorElement>('.form-error-summary a')[1]!
  link.focus()
  fields[0]!.dispatchEvent(new Event('input', { bubbles: true }))
  expect(host.querySelectorAll('.form-error-summary a')[1]).toBe(link)
  expect(document.activeElement).toBe(link)
})
