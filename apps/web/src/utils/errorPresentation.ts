import { ApiError } from '../api/client'

export function errorPresentation(error: unknown) {
  const value = error instanceof ApiError ? error : new ApiError(0, 'CLIENT_ERROR', '暂时无法确认操作结果', '—')
  let title = value.message, guidance = ''
  if (value.code === 'NETWORK_ERROR') title = '未能收到服务响应，请检查网络连接'
  else if (value.code === 'REQUEST_TIMEOUT') title = '等待服务响应超时'
  else if (value.code === 'INVALID_RESPONSE') title = '服务返回异常，暂时无法确认结果'
  else if (value.status >= 500) title = '服务暂时不可用'
  else if (value.status === 401 && value.code !== 'LOGIN_FAILED') title = '登录状态已失效，请重新登录'
  else if (value.status === 403) title = '当前操作未获授权'
  else if (value.status === 404) title = '内容不存在或当前无法访问'
  if (value.status >= 500 || ['NETWORK_ERROR', 'REQUEST_TIMEOUT', 'CLIENT_ERROR', 'INVALID_RESPONSE'].includes(value.code))
    guidance = '如果刚提交过内容，请先核对处理结果，避免重复提交。'
  else if (value.code === 'VERSION_CONFLICT') guidance = '这条记录已被更新。请先重新读取，再核对需要修改的内容。'
  else if (value.status === 429) guidance = '操作较频繁，请稍等一会儿再试。'
  else if (value.status === 403) guidance = '请确认当前租户与账号权限，或联系项目管理员。'
  else if (value.status === 404) guidance = '请返回列表重新查询，或确认当前租户。'
  return { value, title, guidance }
}
