// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import { navigateFromLink } from '../src/utils/linkNavigation'

function activate(init: MouseEventInit = {}, attributes: Record<string, string | undefined> = {}, handled = false) {
  const anchor = document.createElement('a'), navigate = vi.fn()
  anchor.href = '/assets'
  for (const [key, value] of Object.entries(attributes)) if (value !== undefined) anchor.setAttribute(key, value)
  const event = new MouseEvent('click', { bubbles: true, cancelable: true, ...init })
  if (handled) event.preventDefault()
  let intercepted: boolean | undefined
  anchor.addEventListener('click', value => {
    navigateFromLink(value, navigate)
    intercepted = value.defaultPrevented
    value.preventDefault() // Do not ask jsdom to perform native navigation.
  })
  anchor.dispatchEvent(event)
  return { navigate, intercepted }
}
it.each([{}, { detail: 0 }, { detail: 1 }])('routes an unmodified click or keyboard activation once (%j)', init => {
  const result = activate(init)
  expect(result.intercepted).toBe(true)
  expect(result.navigate).toHaveBeenCalledOnce()
})
it.each(['ctrlKey', 'metaKey', 'shiftKey', 'altKey'])('leaves %s clicks to the browser', key => {
  const result = activate({ [key]: true })
  expect(result.intercepted).toBe(false)
  expect(result.navigate).not.toHaveBeenCalled()
})
it.each([1, 2])('does not intercept mouse button %s', button => {
  const result = activate({ button })
  expect(result.intercepted).toBe(false)
  expect(result.navigate).not.toHaveBeenCalled()
})
it.each([{ target: '_blank' }, { target: 'reference-window' }, { download: '' }])('retains native destination attributes %j', attributes => {
  const result = activate({}, attributes)
  expect(result.intercepted).toBe(false)
  expect(result.navigate).not.toHaveBeenCalled()
})
it('respects an earlier handler and still supports an explicit current-tab target', () => {
  expect(activate({}, {}, true).navigate).not.toHaveBeenCalled()
  expect(activate({}, { target: '_self' }).navigate).toHaveBeenCalledOnce()
})
