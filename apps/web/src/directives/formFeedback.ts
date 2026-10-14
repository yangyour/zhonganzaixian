import type { ObjectDirective } from 'vue'

type Field = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
type FieldNotice = { note: HTMLElement; describedBy: string | null; invalid: string | null }
let nextId = 0
const disposers = new WeakMap<HTMLFormElement, () => void>()
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

/** Presents existing HTML constraints; does not add business rules or submit/replay writes. */
export const formFeedback: ObjectDirective<HTMLFormElement> = {
  mounted(form) {
    const notices = new Map<Field, FieldNotice>()
    const summary = document.createElement('p')
    summary.className = 'form-error-summary'; summary.setAttribute('role', 'alert')
    let queued = false, active = true
    function updateSummary() {
      for (const [field] of notices) if (!form.contains(field)) clear(field)
      if (!notices.size) { summary.remove(); return }
      summary.textContent = `有 ${notices.size} 项内容需要检查，请修改标记的字段后再提交。`
      if (!summary.isConnected) form.prepend(summary)
    }
    function clear(field: Field) {
      const entry = notices.get(field)
      if (!entry) return
      entry.note.remove(); field.classList.remove('mop-field-invalid')
      if (entry.describedBy === null) field.removeAttribute('aria-describedby'); else field.setAttribute('aria-describedby', entry.describedBy)
      if (entry.invalid === null) field.removeAttribute('aria-invalid'); else field.setAttribute('aria-invalid', entry.invalid)
      notices.delete(field)
    }
    function mark(field: Field) {
      if (!notices.has(field)) {
        const note = document.createElement('span')
        note.id = `mop-field-error-${++nextId}`; note.className = 'form-field-error'
        notices.set(field, { note, describedBy: field.getAttribute('aria-describedby'), invalid: field.getAttribute('aria-invalid') })
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
        const first = [...form.elements].find(field => isField(field) && notices.has(field)) as Field | undefined
        first?.focus()
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
    disposers.set(form, () => {
      active = false; reset(); form.removeEventListener('invalid', invalid, true)
      form.removeEventListener('input', input); form.removeEventListener('change', input); form.removeEventListener('reset', reset)
    })
  },
  beforeUnmount(form) { disposers.get(form)?.(); disposers.delete(form) },
}
