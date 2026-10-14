/** Only the explicit login entry should spend bandwidth on its decorative hero image. */
export function preloadLoginBackground(document: Document, pathname: string, url: string) {
  if (!['/login', '/login/'].includes(pathname) || document.querySelector('link[data-mop-login-background]')) return false
  const link = document.createElement('link')
  link.rel = 'preload'; link.setAttribute('as', 'image'); link.href = url
  link.setAttribute('fetchpriority', 'high'); link.setAttribute('data-mop-login-background', '')
  document.head.append(link)
  return true
}
