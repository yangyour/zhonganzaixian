import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'

export async function runIdentity(h) {
  const a = h.clients.padmin, viewer = h.clients.viewerA, other = h.clients.viewerB
  let role, user, account, policy
  await h.case('IAM-01', '项目登录、账号读取、未设置策略与匿名/越权保护', async () => {
    const options = await a.call('/auth/options')
    assert.equal(options.local_enabled, true); assert.equal(options.sso_enabled, false)
    policy = await a.call('/identity/policy')
    for (const [key, value] of Object.entries(policy)) if (key !== 'version') assert.equal(value, null)
    await h.clients.anonymous.call('/identity/users', 'GET', undefined, 401)
    await viewer.call('/identity/users', 'GET', undefined, 403)
    await a.call('/identity/users/' + other.session.user.id, 'GET', undefined, 404)
    await a.call('/identity/roles', 'POST', { expected_version: 0, name: h.prefix + '_DENIED' }, 403, { noCsrf: true })
  })
  try {
    await h.case('IAM-02', '独立角色与用户创建、同请求重试与冲突', async () => {
      role = await a.call('/identity/roles', 'POST', { expected_version: 0, name: h.prefix + '_ROLE', description: '虚构逐功能验收专用，结束后停用', base_role: 'viewer', permissions: ['assets:read'], enabled: true })
      account = { username: 'accept-' + h.runId, password: randomBytes(24).toString('base64url') }
      const body = { expected_version: 0, ...account, display_name: h.prefix + '_USER', role_id: role.id, active: true }, key = randomUUID()
      user = await a.call('/identity/users', 'POST', body, 200, { key })
      assert.deepEqual(await a.call('/identity/users', 'POST', body, 200, { key }), user)
      await a.call('/identity/users', 'POST', { ...body, display_name: h.prefix + '_CONFLICT' }, 409, { key })
      h.artifact('synthetic_identity', { user_id: user.id, role_id: role.id })
    })
    await h.case('IAM-03', '权限裁剪与实时撤权', async () => {
      if (!user || !role) h.block('SYNTHETIC_IDENTITY_UNAVAILABLE')
      const limited = new h.Client('synthetic-viewer'); const first = await limited.login(account)
      assert.deepEqual(first.capabilities, ['assets:read']); await limited.call('/assets'); await limited.call('/identity/roles', 'GET', undefined, 403)
      const body = { expected_version: role.version, name: role.name, description: role.description, base_role: 'viewer', permissions: [], enabled: true }
      role = await a.call('/identity/roles/' + role.id, 'PATCH', body)
      await limited.call('/assets', 'GET', undefined, 403)
      assert.deepEqual((await limited.call('/session')).capabilities, [])
      await a.call('/identity/roles/' + role.id, 'PATCH', body, 409)
    })
    await h.case('IAM-04', '重置密码、撤销会话与禁用账号', async () => {
      if (!user) h.block('SYNTHETIC_IDENTITY_UNAVAILABLE')
      const old = new h.Client('synthetic-before-reset'); await old.login(account)
      user = await a.call('/identity/users/' + user.id)
      const password = randomBytes(24).toString('base64url')
      await a.call('/identity/users/' + user.id + '/password', 'POST', { expected_version: user.version, new_password: password })
      await old.call('/session', 'GET', undefined, 401)
      const rejected = new h.Client('synthetic-old-password'); rejected.csrf = (await rejected.call('/auth/csrf')).csrf_token
      await rejected.call('/auth/local/login', 'POST', account, 401)
      account.password = password
      const fresh = new h.Client('synthetic-after-reset'); await fresh.login(account)
      assert.equal(fresh.session.password_change_required, false)
      user = await a.call('/identity/users/' + user.id)
      await a.call('/identity/users/' + user.id + '/revoke-sessions', 'POST', { expected_version: user.version })
      await fresh.call('/session', 'GET', undefined, 401)
    })
  } finally {
    await h.case('IAM-CLEANUP', '仅停用本轮虚构账号和角色，策略保持原值', async () => {
      if (user) {
        const current = await a.call('/identity/users/' + user.id)
        assert.equal(current.display_name, h.prefix + '_USER')
        await a.call('/identity/users/' + user.id, 'PATCH', { expected_version: current.version, display_name: current.display_name, active: false })
        const disabled = new h.Client('synthetic-disabled'); disabled.csrf = (await disabled.call('/auth/csrf')).csrf_token
        await disabled.call('/auth/local/login', 'POST', account, 401)
      }
      if (role) {
        const roles = await a.call('/identity/roles')
        const current = (Array.isArray(roles) ? roles : roles.items || roles.roles).find(item => item.id === role.id)
        assert.ok(current); assert.equal(current.name, h.prefix + '_ROLE')
        await a.call('/identity/roles/' + role.id, 'PATCH', { expected_version: current.version, name: current.name, description: current.description, base_role: 'viewer', permissions: [], enabled: false })
      }
      if (policy) assert.deepEqual(await a.call('/identity/policy'), policy)
    })
  }
}
