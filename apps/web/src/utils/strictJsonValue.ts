/** Inspect raw JSON before JSON.parse can discard duplicate keys or round numeric input. */
export interface JsonLimits { bytes: number; depth: number; tokens: number; stringLength: number }
export function parseStrictJsonValue(text: string, limits: JsonLimits): unknown {
 const bad = () => new Error('Invalid bounded explicit JSON')
 if (!limits || Object.keys(limits).length !== 4 || !['bytes', 'depth', 'tokens', 'stringLength'].every(k => Object.hasOwn(limits, k)) || Object.values(limits).some(v => !Number.isSafeInteger(v) || v < 1) || typeof text !== 'string' || text.length > limits.bytes || new TextEncoder().encode(text).length > limits.bytes) throw bad()
 let position = 0, tokens = 0
 const space = () => { while (/[\x20\t\r\n]/.test(text[position] ?? '\0')) position++ }
 const token = () => { if (++tokens > limits.tokens) throw bad() }
 function string(): string { const start = position; if (text[position++] !== '"') throw bad(); let escaped = false; while (position < text.length) { const ch = text[position++]!; if (!escaped && ch === '"') { const value: unknown = JSON.parse(text.slice(start, position)); if (typeof value !== 'string' || value.length > limits.stringLength) throw bad(); return value } if (!escaped && ch === '\\') escaped = true; else escaped = false } throw bad() }
 function value(depth: number): void {
  space(); token(); const ch = text[position]
  if (ch === '{' || ch === '[') {
   if (depth > limits.depth) throw bad(); position++; space(); const close = ch === '{' ? '}' : ']', keys = new Set<string>()
   if (text[position] === close) { position++; token(); return }
   while (true) { if (ch === '{') { space(); token(); const key = string(); if (keys.has(key)) throw bad(); keys.add(key); space(); if (text[position++] !== ':') throw bad() } value(depth + 1); space(); if (text[position] === close) { position++; token(); return } if (text[position++] !== ',') throw bad() }
  }
  if (ch === '"') { string(); return }
  const literal = /^(?:true|false|null)/.exec(text.slice(position)); if (literal) { position += literal[0].length; return }
  const number = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(position)); if (!number || number[0].length > 32) throw bad()
  if (!/^(?:0|-?[1-9][0-9]*)$/.test(number[0]) || !Number.isSafeInteger(Number(number[0]))) throw bad(); position += number[0].length
 }
 value(1); space(); if (position !== text.length) throw bad(); return JSON.parse(text)
}
