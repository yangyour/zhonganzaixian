// @vitest-environment jsdom
// Presentation checks only; no API or external component is substituted for acceptance.
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import { ApiError } from '../src/api/client'
import ApiErrorNotice from '../src/components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../src/components/IdentityCommandNotice.vue'
import AppTable from '../src/components/AppTable.vue'
import type { IdentityCommand } from '../src/utils/projectIdentity'

let app: App | undefined, host: HTMLElement
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp({ render: () => h(component, props) })
  for (const c of [ElAlert, ElButton]) app.component(c.name!, c)
  app.directive('loading', {}); app.mount(host); await nextTick()
}
afterEach(() => { app?.unmount(); host?.remove() })
it('provides an explicit read retry only when the caller supplies one', async () => {
  const retry = vi.fn()
  await mount(ApiErrorNotice, { error: new ApiError(503, 'HTTP_503', 'unavailable', 'trace'), onRetry: retry })
  expect(host.querySelector('button')?.textContent).toContain('重新读取')
  host.querySelector('button')!.click(); expect(retry).toHaveBeenCalledOnce()
})
it('keeps troubleshooting information collapsed and omits a missing trace number', async () => {
  await mount(ApiErrorNotice, { error: new ApiError(0, 'NETWORK_ERROR', 'technical network message', '—') })
  expect(host.querySelector('.el-alert__title')?.textContent).toBe('未能收到服务响应，请检查网络连接')
  expect(host.querySelector('details')?.open).toBe(false)
  expect(host.querySelector('details')?.textContent).toContain('NETWORK_ERROR')
  expect(host.textContent).not.toContain('排查编号')
  expect(host.textContent).toContain('避免重复提交')
})
it('preserves business rejection text, customer questions and a usable trace number', async () => {
  await mount(ApiErrorNotice, { error: new ApiError(422, 'CONFIG_PENDING', '监控接入条件尚未确认', 'synthetic-trace', [{ question_ids: ['Q02', 'Q03', 'Q02'] }]), pendingMessage: '草稿尚未生效。' })
  expect(host.querySelector('.el-alert__title')?.textContent).toBe('监控接入条件尚未确认')
  expect(host.textContent).toContain('Q02、Q03')
  expect(host.textContent).toContain('草稿尚未生效')
  expect(host.querySelector('details')?.textContent).toContain('synthetic-trace')
})
it('does not invent a version conflict for a different 409 rejection', async () => {
  await mount(ApiErrorNotice, { error: new ApiError(409, 'DUPLICATE_NAME', '名称已存在', 'trace') })
  expect(host.textContent).not.toContain('这条记录已被更新')
  expect(host.querySelector('.el-alert__title')?.textContent).toBe('名称已存在')
})
it('shows one warning and delegates retry without sending another command itself', async () => {
  const retry = vi.fn()
  const command: IdentityCommand = { state: 'uncertain', path: null, method: null, key: null, body: null, result: null, error: new ApiError(0, 'NETWORK_ERROR', 'network', '—') }
  await mount(IdentityCommandNotice, { command, message: '', onRetry: retry })
  expect(host.querySelectorAll('.el-alert')).toHaveLength(1)
  expect(host.querySelector('.el-alert--warning')).not.toBeNull()
  expect(host.querySelector('.el-alert--error')).toBeNull()
  host.querySelector('button')!.click(); expect(retry).toHaveBeenCalledOnce()
})
it('keeps accepted writes with failed readback as a warning without a write retry', async () => {
  const command: IdentityCommand = { state: 'accepted', path: null, method: null, key: null, body: null, result: {}, error: null }
  await mount(IdentityCommandNotice, { command, message: '已保存，但最新内容加载失败。请刷新查看，不要重复提交。', readbackFailed: true })
  expect(host.querySelector('.el-alert--warning')).not.toBeNull()
  expect(host.querySelector('button')).toBeNull()
})
it('switches from loading to the real empty state only after loading finishes', async () => {
  const props = reactive({ rows: [], loading: true, emptyText: '没有匹配资产' })
  await mount(AppTable, props)
  expect(host.querySelector('.table-frame')?.getAttribute('aria-busy')).toBe('true')
  const status = host.querySelector('[role="status"]')!
  expect(status.closest('[aria-busy="true"]')).toBeNull()
  expect(host.textContent).toContain('正在加载')
  expect(host.textContent).not.toContain('没有匹配资产')
  props.loading = false; await nextTick(); await nextTick()
  expect(host.querySelector('[role="status"]')).toBe(status)
  expect(status.textContent).toBe('')
  expect(host.querySelector('.table-frame')?.getAttribute('aria-busy')).toBe('false')
  expect(host.textContent).toContain('没有匹配资产')
})
