import { ElMessage, type MessageHandler, type MessageOptions } from 'element-plus'

/** Important feedback stays until dismissed; repeated messages share one notice. */
function show(type: 'success' | 'warning' | 'error' | 'info', message: string | MessageOptions): MessageHandler {
  const options = typeof message === 'string' ? { message } : message
  return ElMessage[type]({ duration: type === 'success' ? 5000 : 0, showClose: true, grouping: true,
    ...options, type, dangerouslyUseHTMLString: false })
}
export const feedback = {
  success: (message: string | MessageOptions) => show('success', message),
  warning: (message: string | MessageOptions) => show('warning', message),
  error: (message: string | MessageOptions) => show('error', message),
  info: (message: string | MessageOptions) => show('info', message),
  closeAll: () => ElMessage.closeAll(),
}
