// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { shouldFocusGlobalSearch } from '../src/utils/globalSearchWorkspace'

it.each(['role="dialog"', 'role="alertdialog"', 'aria-modal="true"', 'role="textbox"', 'contenteditable="true"'])(
  'does not open search from a nested control inside %s', attribute => {
    const host = document.createElement('div')
    host.innerHTML = `<section ${attribute}><button><span>当前操作</span></button></section>`
    expect(shouldFocusGlobalSearch({ key: 'k', ctrlKey: true, target: host.querySelector('span') })).toBe(false)
    expect(shouldFocusGlobalSearch({ key: '/', target: host.querySelector('span') })).toBe(false)
  },
)
it('allows a regular navigation control without an editable or modal ancestor', () => {
  const host = document.createElement('div')
  host.innerHTML = '<nav><button><span>导航</span></button></nav>'
  expect(shouldFocusGlobalSearch({ key: 'k', ctrlKey: true, target: host.querySelector('span') })).toBe(true)
  expect(shouldFocusGlobalSearch({ key: '/', target: host.querySelector('span') })).toBe(true)
})
it.each(['ctrlKey','metaKey','altKey','shiftKey','repeat','isComposing','defaultPrevented'])('does not take over slash with %s', flag=>{
  expect(shouldFocusGlobalSearch({key:'/',[flag]:true})).toBe(false)
})
