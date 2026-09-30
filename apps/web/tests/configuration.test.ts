import { describe, expect, it } from 'vitest'
import { parseProposedValue } from '../src/utils/configuration'
import { displayValue } from '../src/utils/display'
describe('unknown configuration semantics', () => {
  it('does not convert an empty number to zero or an unselected boolean to false', () => {
    expect(() => parseProposedValue('number', '')).toThrow()
    expect(() => parseProposedValue('boolean', '')).toThrow()
    expect(parseProposedValue('number', '0')).toBe(0)
    expect(parseProposedValue('boolean', false)).toBe(false)
  })
  it('renders pending null distinctly from confirmed zero and false', () => {
    expect(displayValue(null)).toBe('待确认')
    expect(displayValue(0)).toBe('0')
    expect(displayValue(false)).toBe('否')
  })
  it('validates JSON syntax and leaves unknown values pending', () => {
    expect(() => parseProposedValue('json', '{broken')).toThrow()
    expect(() => parseProposedValue('json', 'null')).toThrow()
    expect(parseProposedValue('json', '{"threshold":null}')).toEqual({ threshold: null })
  })
})
