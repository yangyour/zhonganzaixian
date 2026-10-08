// @vitest-environment jsdom
// Isolated control tests with synthetic responses; no remote login or browser-layout acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import LoginPage from '../src/pages/LoginPage.vue'
import { api, ApiError } from '../src/api/client'
import type { AuthOptions } from '../src/api/types'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))

const options: AuthOptions = { mode: 'remote_test', identity_source: 'project_local', local_enabled: true, sso_enabled: false, login_url: null }
let app: App | undefined, host: HTMLElement
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { resolve, promise }
}
function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(LoginPage)
  app.component('ElAlert', ElAlert)
  app.component('ElButton', ElButton)
  app.mount(host)
}
async function settle() { await nextTick(); await Promise.resolve(); await nextTick() }
function field(label: string) { return host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)! }
function input(label: string, value: string) {
  const control = field(label)
  control.value = value
  control.dispatchEvent(new Event('input', { bubbles: true }))
}
function button(name: string) {
  const result = [...host.querySelectorAll<HTMLButtonElement>('button')].find(control => (control.getAttribute('aria-label') || control.textContent?.trim()) === name)
  if (!result) throw new Error(`Missing button: ${name}`)
  return result
}
function submit() { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
async function loaded() { await vi.waitFor(() => expect(host.querySelector('form')).not.toBeNull()); await settle() }

beforeEach(() => { vi.spyOn(api, 'setCsrfToken').mockImplementation(() => undefined) })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.restoreAllMocks() })

it('announces the initial connection and replaces it with the usable form', async () => {
  const pending = deferred<AuthOptions>()
  const request = vi.spyOn(api, 'request').mockReturnValue(pending.promise as never)
  mount()
  expect(host.textContent).toContain('正在连接登录服务…')
  expect(host.querySelector('form')).toBeNull()
  pending.resolve(options)
  await loaded()
  expect(host.textContent).not.toContain('正在连接登录服务…')
  expect(request).toHaveBeenCalledTimes(1)
})

it('offers a connection retry and does not leave failed loading feedback on screen', async () => {
  const request = vi.spyOn(api, 'request').mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '连接未完成', 'synthetic-trace')).mockResolvedValueOnce(options)
  mount()
  await vi.waitFor(() => expect(host.textContent).toContain('连接未完成'))
  expect(host.textContent).not.toContain('正在连接登录服务…')
  button('重新连接').click()
  await loaded()
  expect(request).toHaveBeenCalledTimes(2)
  expect(host.querySelector('.login-error')).toBeNull()
})

it('rejects whitespace-only usernames before requesting CSRF and focuses the account field', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(options)
  mount(); await loaded()
  input('登录账号', '   ')
  input('登录密码', 'synthetic-password')
  submit(); await settle()
  expect(request).toHaveBeenCalledTimes(1)
  await vi.waitFor(() => expect(host.textContent).toContain('请输入账号，不能只填写空格。'))
  expect(field('登录账号').getAttribute('aria-invalid')).toBe('true')
  expect(field('登录账号').getAttribute('aria-describedby')).toBe('login-username-error')
  expect(document.activeElement).toBe(field('登录账号'))
  input('登录账号', 'synthetic-user'); await settle()
  await vi.waitFor(() => expect(host.querySelector('#login-username-error')).toBeNull())
})

it('points an empty password to its field without introducing length or character rules', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValueOnce(options).mockResolvedValueOnce({ csrf_token: 'SYNTHETIC_NOT_CREDENTIAL' }).mockRejectedValueOnce(new ApiError(401, 'LOGIN_FAILED', '账号或密码不正确，或账号暂不可用', 'synthetic-trace'))
  mount(); await loaded()
  input('登录账号', ' synthetic-user ')
  submit(); await settle()
  expect(document.activeElement).toBe(field('登录密码'))
  expect(field('登录密码').getAttribute('aria-describedby')).toBe('login-password-error')
  expect(request).toHaveBeenCalledTimes(1)
  input('登录密码', ' ')
  submit()
  await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(3))
  expect(request.mock.calls[2]![1]).toMatchObject({ method: 'POST', body: { username: 'synthetic-user', password: ' ' } })
  expect(api.setCsrfToken).toHaveBeenCalledWith('SYNTHETIC_NOT_CREDENTIAL')
})

it('toggles password visibility and help with keyboard-accessible state', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(options)
  mount(); await loaded()
  input('登录密码', 'synthetic-password')
  expect(field('登录密码').type).toBe('password')
  button('显示密码').click(); await settle()
  expect(field('登录密码').type).toBe('text')
  expect(button('隐藏密码').getAttribute('aria-pressed')).toBe('true')
  expect(button('隐藏密码').getAttribute('aria-controls')).toBe(field('登录密码').id)
  button('隐藏密码').click(); await settle()
  expect(field('登录密码').type).toBe('password')
  expect(button('忘记密码？').getAttribute('aria-expanded')).toBe('false')
  button('忘记密码？').click(); await settle()
  expect(button('收起找回说明').getAttribute('aria-expanded')).toBe('true')
  expect(host.querySelector('#login-password-help')?.textContent).toContain('请联系项目管理员重置密码')
  button('收起找回说明').click(); await settle()
  expect(host.querySelector('#login-password-help')).toBeNull()
  expect(request).toHaveBeenCalledTimes(1)
})

it('shows Caps Lock only while the password field reports it active', async () => {
  vi.spyOn(api, 'request').mockResolvedValue(options)
  mount(); await loaded()
  field('登录密码').focus()
  field('登录密码').dispatchEvent(new KeyboardEvent('keydown', { key: 'A', modifierCapsLock: true, bubbles: true }))
  await settle()
  expect(host.textContent).toContain('大写锁定已开启')
  expect(field('登录密码').getAttribute('aria-describedby')).toBe('login-caps-lock')
  field('登录密码').dispatchEvent(new KeyboardEvent('keyup', { key: 'CapsLock', modifierCapsLock: false, bubbles: true }))
  await settle()
  expect(host.querySelector('#login-caps-lock')).toBeNull()
  field('登录密码').dispatchEvent(new KeyboardEvent('keydown', { key: 'A', modifierCapsLock: true, bubbles: true }))
  await settle()
  field('登录密码').blur(); await settle()
  expect(host.querySelector('#login-caps-lock')).toBeNull()
})

it('masks a revealed password when the page is hidden and never reveals it on return', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  const request = vi.spyOn(api, 'request').mockResolvedValue(options)
  mount(); await loaded()
  input('登录账号', 'synthetic-user'); input('登录密码', 'synthetic-password')
  button('显示密码').click(); await settle()
  field('登录密码').focus()
  field('登录密码').dispatchEvent(new KeyboardEvent('keydown', { key: 'A', modifierCapsLock: true, bubbles: true }))
  await settle()
  expect(field('登录密码').type).toBe('text')
  expect(host.querySelector('#login-caps-lock')).not.toBeNull()
  visibility.mockReturnValue('hidden'); hidden.mockReturnValue(true)
  document.dispatchEvent(new Event('visibilitychange')); await settle()
  expect(field('登录密码').type).toBe('password')
  expect(field('登录密码').value).toBe('synthetic-password')
  expect(field('登录账号').value).toBe('synthetic-user')
  expect(button('显示密码').getAttribute('aria-pressed')).toBe('false')
  expect(host.querySelector('#login-caps-lock')).toBeNull()
  visibility.mockReturnValue('visible'); hidden.mockReturnValue(false)
  document.dispatchEvent(new Event('visibilitychange')); await settle()
  expect(field('登录密码').type).toBe('password')
  expect(field('登录密码').value).toBe('synthetic-password')
  expect(button('显示密码').getAttribute('aria-pressed')).toBe('false')
  expect(host.querySelector('#login-caps-lock')).toBeNull()
  expect(request).toHaveBeenCalledTimes(1)
})

it('shows the available login methods and a separator only when both methods are available', async () => {
  const request = vi.spyOn(api, 'request')
  const cases: { auth: AuthOptions; local: boolean; unified: boolean }[] = [
    { auth: options, local: true, unified: false },
    { auth: { ...options, sso_enabled: true, login_url: '/oauth2/authorization/synthetic' }, local: true, unified: true },
    { auth: { ...options, sso_enabled: true, login_url: null }, local: true, unified: false },
    { auth: { ...options, sso_enabled: false, login_url: '/oauth2/authorization/synthetic' }, local: true, unified: false },
    { auth: { ...options, local_enabled: false, sso_enabled: true, login_url: '/oauth2/authorization/synthetic' }, local: false, unified: true },
    // Existing legacy OIDC-only options do not have the newer local/SSO flags.
    { auth: { mode: 'remote_test', identity_source: 'oidc', login_url: '/oauth2/authorization/synthetic' }, local: false, unified: true },
    { auth: { ...options, local_enabled: false }, local: false, unified: false },
  ]
  for (const [index, scenario] of cases.entries()) {
    request.mockResolvedValueOnce(scenario.auth)
    mount()
    await vi.waitFor(() => expect(host.querySelector('.login-progress')?.textContent).toBe(''))
    await settle()
    expect(Boolean(host.querySelector('form')), `local form in options case ${index}`).toBe(scenario.local)
    const unified = [...host.querySelectorAll<HTMLButtonElement>('button')].filter(control => control.textContent?.trim() === '使用统一身份认证')
    expect(unified, `unified entry in options case ${index}`).toHaveLength(scenario.unified ? 1 : 0)
    if (scenario.unified) expect(unified[0]!.disabled).toBe(false)
    const divider = host.querySelector('.el-divider')
    expect(Boolean(divider), `method separator in options case ${index}`).toBe(scenario.local && scenario.unified)
    if (divider) expect(divider.textContent?.trim()).toBe('或')
    expect(request).toHaveBeenCalledTimes(index + 1)
    app!.unmount(); app = undefined; host.remove()
  }
})

it('hides the password while logging in, blocks duplicate requests, and returns focus after rejected credentials', async () => {
  const pending = deferred<{ csrf_token: string }>()
  const request = vi.spyOn(api, 'request').mockResolvedValueOnce(options).mockReturnValueOnce(pending.promise as never).mockRejectedValueOnce(new ApiError(401, 'LOGIN_FAILED', '账号或密码不正确，或账号暂不可用', 'synthetic-trace'))
  mount(); await loaded()
  input('登录账号', 'synthetic-user'); input('登录密码', 'synthetic-password')
  button('显示密码').click(); await settle()
  submit(); await settle()
  expect(field('登录密码').type).toBe('password')
  expect(field('登录账号').disabled).toBe(true)
  expect(button('显示密码').disabled).toBe(true)
  expect(button('正在登录…').disabled).toBe(true)
  expect(host.querySelector('[role="status"]')?.textContent).toContain('正在核对账号信息')
  submit(); await settle()
  expect(request).toHaveBeenCalledTimes(2)
  pending.resolve({ csrf_token: 'SYNTHETIC_NOT_CREDENTIAL' })
  await vi.waitFor(() => expect(document.activeElement).toBe(field('登录密码')))
  expect(request).toHaveBeenCalledTimes(3)
  expect(field('登录密码').value).toBe('synthetic-password')
  expect(field('登录密码').selectionStart).toBe(0)
  expect(field('登录密码').selectionEnd).toBe('synthetic-password'.length)
  expect(button('登录').disabled).toBe(false)
  expect(host.textContent).toContain('账号或密码不正确，或账号暂不可用')
})

it('focuses an actionable error instead of blaming credentials when the connection fails', async () => {
  vi.spyOn(api, 'request').mockResolvedValueOnce(options).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '连接未完成', 'synthetic-trace'))
  mount(); await loaded()
  input('登录账号', 'synthetic-user'); input('登录密码', 'synthetic-password')
  submit()
  await vi.waitFor(() => expect(document.activeElement).toBe(host.querySelector('.login-error')))
  expect(field('登录密码').getAttribute('aria-invalid')).toBeNull()
  expect(button('登录').disabled).toBe(false)
})

it('aborts an in-flight login on unmount without dispatching the password afterwards', async () => {
  const pending = deferred<{ csrf_token: string }>()
  const request = vi.spyOn(api, 'request').mockResolvedValueOnce(options).mockReturnValueOnce(pending.promise as never)
  mount(); await loaded()
  input('登录账号', 'synthetic-user'); input('登录密码', 'synthetic-password')
  submit(); await settle()
  const signal = request.mock.calls[1]![1]!.signal!
  app!.unmount(); app = undefined
  expect(signal.aborted).toBe(true)
  pending.resolve({ csrf_token: 'SYNTHETIC_NOT_CREDENTIAL' }); await settle()
  expect(request).toHaveBeenCalledTimes(2)
  expect(api.setCsrfToken).not.toHaveBeenCalled()
})
