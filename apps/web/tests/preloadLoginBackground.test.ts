// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest'
import { preloadLoginBackground } from '../src/utils/preloadLoginBackground'
afterEach(() => document.querySelectorAll('[data-mop-login-background]').forEach(node => node.remove()))
it('discovers the login image before loading the application and avoids duplicate preload entries', () => {
  expect(preloadLoginBackground(document, '/login', '/assets/login-test.png')).toBe(true)
  const link = document.querySelector<HTMLLinkElement>('link[data-mop-login-background]')!
  expect(link.getAttribute('as')).toBe('image'); expect(link.getAttribute('fetchpriority')).toBe('high')
  expect(preloadLoginBackground(document, '/login', '/assets/login-test.png')).toBe(false)
})
it('does not download the login background on business page entries', () => {
  for (const path of ['/assets', '/dashboard', '/account', '/identity/users']) expect(preloadLoginBackground(document, path, '/assets/login-test.png')).toBe(false)
  expect(document.querySelector('[data-mop-login-background]')).toBeNull()
})
