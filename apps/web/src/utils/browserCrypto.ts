import { sha256 } from '@noble/hashes/sha2.js'

/** HTTP IP test origins expose getRandomValues, but may omit randomUUID/subtle. */
export function createRequestId(): string {
  const source = globalThis.crypto
  if (typeof source?.randomUUID === 'function') return source.randomUUID()
  if (typeof source?.getRandomValues !== 'function') throw new Error('浏览器安全随机源不可用，无法创建请求标识')
  const bytes = source.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Same SHA-256 bytes in every context; absence of WebCrypto never skips verification. */
export async function digestSha256(data: ArrayBuffer | ArrayBufferView): Promise<ArrayBuffer> {
  const view = ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data)
  const bytes = new Uint8Array(view)
  if (typeof globalThis.crypto?.subtle?.digest === 'function') return globalThis.crypto.subtle.digest('SHA-256', bytes)
  return new Uint8Array(sha256(bytes)).buffer
}
