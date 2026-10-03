import { describe, expect, it } from 'vitest'
import { isUtcInstant, utcInstantNanos, utcNotBefore } from '../src/utils/utcInstant'

describe('canonical server UTC facts',()=>{
 it('accepts valid leap days and bounded four digit years',()=>{for(const value of ['0001-01-01T00:00:00Z','9999-12-31T23:59:59.999999999Z','2024-02-29T12:00:00Z','2000-02-29T00:00:00Z'])expect(isUtcInstant(value)).toBe(true)})
 it('rejects impossible dates that JavaScript silently normalizes',()=>{for(const value of ['2026-02-30T00:00:00Z','2026-02-29T00:00:00Z','1900-02-29T00:00:00Z','2026-04-31T00:00:00Z','2026-10-01T24:00:00Z','2026-10-01T00:00:60Z','0000-01-01T00:00:00Z'])expect(isUtcInstant(value)).toBe(false)})
 it('rejects date-only local offset and permissive non-ISO strings',()=>{for(const value of ['0','2026-10-01','2026-10-01T00:00:00','2026-10-01T00:00:00+00:00','2026-10-01 00:00:00Z','2026-10-01T00:00:00z',' 2026-10-01T00:00:00Z'])expect(isUtcInstant(value)).toBe(false)})
 it('accepts one through nine fraction digits without losing nanoseconds',()=>{for(let digits=1;digits<=9;digits++){const fraction='123456789'.slice(0,digits);expect(utcInstantNanos(`1970-01-01T00:00:00.${fraction}Z`)).toBe(BigInt(fraction.padEnd(9,'0')))}})
 it('handles negative epoch seconds correctly',()=>{expect(utcInstantNanos('1969-12-31T23:59:59.999999999Z')).toBe(-1n);expect(utcInstantNanos('1970-01-01T00:00:00Z')).toBe(0n)})
 it('compares submillisecond instants rather than rounded JavaScript dates',()=>{const later='2026-10-01T00:00:00.000000002Z',earlier='2026-10-01T00:00:00.000000001Z';expect(Date.parse(later)).toBe(Date.parse(earlier));expect(utcNotBefore(earlier,later)).toBe(false);expect(utcNotBefore(later,earlier)).toBe(true)})
 it('equivalent fractions are equal without mutating original strings',()=>{for(const value of ['1970-01-01T00:00:00.1Z','1970-01-01T00:00:00.100Z','1970-01-01T00:00:00.100000000Z']){expect(utcInstantNanos(value)).toBe(100000000n);expect(utcNotBefore(value,'1970-01-01T00:00:00.1Z')).toBe(true)}})
 it('rejects null scalar objects oversized fractions and separators',()=>{for(const value of [null,undefined,0,NaN,{},[],new Date(),'2026-10-01T00:00:00.Z','2026-10-01T00:00:00.1234567890Z','2026-10-01T00:00:00,1Z']){expect(utcInstantNanos(value)).toBeNull();expect(utcNotBefore(value,'2026-10-01T00:00:00Z')).toBe(false)}})
})
