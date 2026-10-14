import { afterEach, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { feedback } from '../src/utils/feedback'
afterEach(() => vi.restoreAllMocks())
it('keeps important messages dismissible and grouped without HTML', () => {
  const spy = vi.spyOn(ElMessage, 'warning').mockReturnValue({ close: vi.fn() })
  feedback.warning('原请求结果尚未确认')
  expect(spy).toHaveBeenCalledWith(expect.objectContaining({ duration: 0, showClose: true, grouping: true, dangerouslyUseHTMLString: false }))
})
it('allows success to expire while preserving its precise wording', () => {
  const spy = vi.spyOn(ElMessage, 'success').mockReturnValue({ close: vi.fn() })
  feedback.success('草稿已保存，尚未生效')
  expect(spy).toHaveBeenCalledWith(expect.objectContaining({ duration: 5000, message: '草稿已保存，尚未生效' }))
})
