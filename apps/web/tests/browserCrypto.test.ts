import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHash, webcrypto } from 'node:crypto'
import { createRequestId, digestSha256 } from '../src/utils/browserCrypto'

afterEach(() => vi.unstubAllGlobals())
const hex = (value: ArrayBuffer) => Buffer.from(value).toString('hex')
const httpCrypto = () => vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) })

describe('browser cryptography on HTTP IP test origins', () => {
  it('retains native UUID support when available', () => {
    const expected = '89dba120-839e-4d8b-9e33-5ac106b76b7d'
    vi.stubGlobal('crypto', { randomUUID: () => expected })
    expect(createRequestId()).toBe(expected)
  })
  it('sets RFC4122 version and variant using secure random bytes', () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => { bytes.fill(255); return bytes })
    vi.stubGlobal('crypto', { getRandomValues })
    expect(createRequestId()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
    expect(getRandomValues).toHaveBeenCalledOnce()
  })
  it('uses distinct secure UUIDs without randomUUID', () => {
    httpCrypto()
    const values = Array.from({ length: 1000 }, createRequestId)
    expect(new Set(values).size).toBe(1000)
    expect(values.every(value => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value))).toBe(true)
  })
  it('fails closed when there is no secure random source', () => {
    vi.stubGlobal('crypto', {})
    expect(createRequestId).toThrow('安全随机源不可用')
    vi.stubGlobal('crypto', undefined)
    expect(createRequestId).toThrow('安全随机源不可用')
  })
  it.each([['empty', ''], ['ASCII', 'abc'], ['Unicode and control bytes', '巡检来源\u0000😀\r\n'], ['one million bytes', 'a'.repeat(1000000)]])('matches an independent SHA-256 implementation for %s', async (_label, value) => {
    httpCrypto()
    const bytes = new TextEncoder().encode(value)
    expect(hex(await digestSha256(bytes))).toBe(createHash('sha256').update(bytes).digest('hex'))
  })
  it('hashes only the selected view, including a DataView offset', async () => {
    httpCrypto()
    const source = new Uint8Array([0, 97, 98, 99, 255])
    const expected = createHash('sha256').update('abc').digest('hex')
    expect(hex(await digestSha256(source.subarray(1, 4)))).toBe(expected)
    expect(hex(await digestSha256(new DataView(source.buffer, 1, 3)))).toBe(expected)
  })
  it('matches native WebCrypto and accepts ArrayBuffer input', async () => {
    vi.stubGlobal('crypto', webcrypto)
    const bytes = new Uint8Array([0, 128, 255]).buffer
    expect(hex(await digestSha256(bytes))).toBe(createHash('sha256').update(new Uint8Array(bytes)).digest('hex'))
  })
  it('preserves native digest failures instead of hiding them', async () => {
    vi.stubGlobal('crypto', { subtle: { digest: () => Promise.reject(new Error('digest unavailable')) } })
    await expect(digestSha256(new Uint8Array())).rejects.toThrow('digest unavailable')
  })
})
