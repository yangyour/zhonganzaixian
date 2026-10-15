import type { ObjectDirective } from 'vue'

type Field = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
type FieldNotice = { note: HTMLElement; item: HTMLLIElement; link: HTMLAnchorElement; describedBy: string | null; invalid: string | null; originalId: string | null; generatedId: string | null }
let nextId = 0
const controllers = new WeakMap<HTMLFormElement, { dispose: () => void; refresh: () => void }>()
function isField(value: EventTarget | null): value is Field {
  return value instanceof HTMLInputElement || value instanceof HTMLTextAreaElement || value instanceof HTMLSelectElement
}
export function constraintMessage(field: Field): string {
  const v = field.validity
  if (v.valueMissing) return field instanceof HTMLSelectElement ? '请选择一项。' : field instanceof HTMLInputElement && ['checkbox', 'radio'].includes(field.type) ? '请先确认此项。' : '请填写此项。'
  if (v.typeMismatch) return field instanceof HTMLInputElement && field.type === 'email' ? '请输入有效的邮箱地址。' : '请检查输入格式。'
  if (v.rangeUnderflow && field instanceof HTMLInputElement) return `请输入不小于 ${field.min} 的值。`
  if (v.rangeOverflow && field instanceof HTMLInputElement) return `请输入不大于 ${field.max} 的值。`
  if (v.tooLong && 'maxLength' in field) return `最多填写 ${field.maxLength} 个字符。`
  if (v.tooShort && 'minLength' in field) return `至少填写 ${field.minLength} 个字符。`
  if (v.badInput || v.stepMismatch) return '请输入符合要求的数值。'
  if (v.patternMismatch) return '请按字段说明填写。'
  return field.validationMessage || '请检查此项。'
}

function fieldLabel(field: Field, fallback: string): string {
  const labelledBy = field.getAttribute('aria-labelledby')?.split(/\s+/).map(id => document.getElementById(id)?.textContent || '').join(' ').trim()
  if (labelledBy) return labelledBy
  if (field.getAttribute('aria-label')?.trim()) return field.getAttribute('aria-label')!.trim()
  const label = field.labels?.[0]?.cloneNode(true) as HTMLElement | undefined
  // Never put input values, select options or our own error into the field name.
  label?.querySelectorAll('input, textarea, select, button, .form-field-error, .field-help').forEach(node => node.remove())
  return label?.textContent?.replace(/\s+/g, ' ').trim() || fallback
}

/** Presents existing HTML constraints; does not add business rules or submit/replay writes. */
export const formFeedback: ObjectDirective<HTMLFormElement> = {
  mounted(form) {
    const notices = new Map<Field, FieldNotice>()
    const summary = document.createElement('div')
    const title = document.createElement('h3'), list = document.createElement('ul')
    title.id = `mop-error-summary-${++nextId}`
    summary.className = 'form-error-summary'; summary.setAttribute('role', 'alert'); summary.tabIndex = -1
    summary.setAttribute('aria-labelledby', title.id); summary.append(title, list)
    let queued = false, active = true
    function updateSummary() {
      for (const [field] of notices) if (!form.contains(field) || !field.willValidate || field.validity.valid) clear(field)
      if (!notices.size) { summary.remove(); return }
      const heading = `有 ${notices.size} 项内容需要检查`
      if (title.textContent !== heading) title.textContent = heading
      const ordered = [...form.elements].filter((field): field is Field => isField(field) && notices.has(field))
      ordered.forEach((field, index) => {
        const entry = notices.get(field)!
        entry.note.textContent = constraintMessage(field)
        const text = `${fieldLabel(field, `第 ${index + 1} 项`)}：${constraintMessage(field)}`
        if (entry.link.textContent !== text) entry.link.textContent = text
        entry.link.href = `#${encodeURIComponent(field.id)}`
        if (list.children[index] !== entry.item) list.insertBefore(entry.item, list.children[index] || null)
      })
      if (!summary.isConnected) form.prepend(summary)
    }
    function clear(field: Field) {
      const entry = notices.get(field)
      if (!entry) return
      entry.note.remove(); entry.item.remove(); field.classList.remove('mop-field-invalid')
      const descriptions = (field.getAttribute('aria-describedby') || '').split(/\s+/).filter(id => id && id !== entry.note.id).join(' ')
      if (descriptions) field.setAttribute('aria-describedby', descriptions)
      else if (entry.describedBy === null) field.removeAttribute('aria-describedby')
      else field.setAttribute('aria-describedby', entry.describedBy)
      if (entry.invalid === null) field.removeAttribute('aria-invalid'); else field.setAttribute('aria-invalid', entry.invalid)
      if (entry.generatedId && field.id === entry.generatedId) {
        if (entry.originalId === null) field.removeAttribute('id'); else field.id = entry.originalId
      }
      notices.delete(field)
    }
    function mark(field: Field) {
      if (!notices.has(field)) {
        const note = document.createElement('span')
        note.id = `mop-field-error-${++nextId}`; note.className = 'form-field-error'
        const originalId = field.getAttribute('id'), generatedId = field.id ? null : `mop-invalid-field-${nextId}`
        if (generatedId) field.id = generatedId
        const item = document.createElement('li'), link = document.createElement('a')
        item.append(link)
        link.addEventListener('click', event => {
          event.preventDefault()
          if (!form.contains(field) || !field.willValidate) { updateSummary(); return }
          field.focus({ preventScroll: true }); field.scrollIntoView?.({ block: 'center' })
        })
        notices.set(field, { note, item, link, describedBy: field.getAttribute('aria-describedby'), invalid: field.getAttribute('aria-invalid'), originalId, generatedId })
        field.insertAdjacentElement('afterend', note)
        field.setAttribute('aria-describedby', [field.getAttribute('aria-describedby'), note.id].filter(Boolean).join(' '))
      }
      notices.get(field)!.note.textContent = constraintMessage(field)
      field.setAttribute('aria-invalid', 'true'); field.classList.add('mop-field-invalid')
    }
    function invalid(event: Event) {
      if (!isField(event.target)) return
      event.preventDefault(); mark(event.target)
      if (queued) return
      queued = true
      queueMicrotask(() => {
        queued = false
        if (!active) return
        updateSummary()
        if (notices.size) { summary.focus({ preventScroll: true }); summary.scrollIntoView?.({ block: 'nearest' }) }
      })
    }
    function input(event: Event) {
      if (!isField(event.target) || !notices.has(event.target)) return
      if (event.target.validity.valid) clear(event.target); else mark(event.target)
      updateSummary()
    }
    form.addEventListener('invalid', invalid, true)
    form.addEventListener('input', input); form.addEventListener('change', input)
    form.addEventListener('reset', reset)
    function reset() { for (const [field] of notices) clear(field); updateSummary() }
    controllers.set(form, { refresh: updateSummary, dispose: () => {
      active = false; reset(); form.removeEventListener('invalid', invalid, true)
      form.removeEventListener('input', input); form.removeEventListener('change', input); form.removeEventListener('reset', reset)
    } })
  },
  updated(form) { controllers.get(form)?.refresh() },
  beforeUnmount(form) { controllers.get(form)?.dispose(); controllers.delete(form) },
}
