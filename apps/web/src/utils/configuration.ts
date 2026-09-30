import type { Configuration } from '../api/types'

export type ValueEditorKind = 'number' | 'boolean' | 'json' | 'text'
export function editorKind(item: Pick<Configuration, 'type'>): ValueEditorKind {
  if (['number', 'integer', 'decimal', 'float'].includes(item.type)) return 'number'
  if (['boolean', 'bool'].includes(item.type)) return 'boolean'
  if (['json', 'object', 'array'].includes(item.type)) return 'json'
  return 'text'
}
export function parseProposedValue(kind: ValueEditorKind, value: string | boolean): unknown {
  if (kind === 'boolean') {
    if (typeof value !== 'boolean') throw new Error('请选择是或否；未选择不能作为 false 保存')
    return value
  }
  if (typeof value !== 'string' || !value.trim()) throw new Error('请填写建议值；空值不能作为默认值保存')
  if (kind === 'number') {
    const result = Number(value)
    if (!Number.isFinite(result)) throw new Error('请填写有效数字')
    return result
  }
  if (kind === 'json') {
    let parsed: unknown
    try { parsed = JSON.parse(value) } catch { throw new Error('JSON 格式有误，请检查后保存') }
    if (parsed === null) throw new Error('建议值不能为 null；未确认值继续保留为空')
    return parsed
  }
  return value.trim()
}
