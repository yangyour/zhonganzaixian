import type { Session } from '../api/types'
import { routeAllowed, type AccessRoute } from './routeAccess'

export interface NavigationItem { name: string; title: string; section?: string }
export interface NavigationModule { id: string; title: string; items: NavigationItem[] }

/** Detail pages inherit the navigation context of their authorized list entry. */
export function navigationEntryName(name: unknown): unknown {
  if (name === 'actionable-tickets') return 'dashboard'
  return name === 'contact-import-preview' ? 'contact-imports' : name
}

const entries = (rows: [string, string, string?][]): NavigationItem[] =>
  rows.map(([name, title, section]) => ({ name, title, ...(section ? { section } : {}) }))

/** Contract appendix 1, M1–M10. Only implemented and authorized routes become links. */
export const contractNavigationModules: readonly NavigationModule[] = [
  { id: 'M1', title: '首页门户', items: entries([
    ['dashboard', '运营总览'], ['my-tasks', '我的待办'],
  ]) },
  { id: 'M2', title: '租户与权限管理', items: entries([
    ['tenant-inventory', '租户资源台账', '租户与联系人'],
    ['customer-contacts', '客户联系人', '租户与联系人'],
    ['contact-imports', '联系人批量导入', '租户与联系人'],
    ['service-tiers', '服务档位与SLA台账', '服务档位'],
    ['identity-users', '用户与成员授权', '身份与权限'],
    ['identity-roles', '角色与权限', '身份与权限'],
    ['identity-settings', '身份认证设置', '身份与权限'],
    ['audit', '审计日志', '审计'],
  ]) },
  { id: 'M3', title: '设备台账', items: entries([
    ['assets', '设备资产'], ['customer-topology', '客户拓扑'], ['asset-export-jobs', '台账导出'],
  ]) },
  { id: 'M4', title: '监控与告警', items: entries([
    ['network-monitoring', '设备监控', '监控与接入'],
    ['monitoring-onboarding', '服务与设备接入', '监控与接入'],
    ['monitoring-events', '事件中心', '告警与诊断'],
    ['manual-incidents', '手工故障登记', '告警与诊断'],
    ['threshold-diagnostics', '告警阈值诊断', '告警与诊断'],
    ['observation-window-diagnostics', '采样观察窗诊断', '告警与诊断'],
  ]) },
  { id: 'M5', title: '工单与SLA', items: entries([
    ['tickets', '报障与受理', '工单处理'], ['ticket-workflows', '工单处理', '工单处理'],
    ['oncall-board', '值班看板与升级链', '值班与工时'], ['my-worklogs', '我的工时记录', '值班与工时'],
    ['sla-scenarios', 'SLA 场景测算', '服务等级'], ['sla-reports', 'SLA月报与赔付', '服务等级'],
  ]) },
  { id: 'M6', title: '配置备份', items: entries([
    ['backup-plan-drafts', '备份计划草稿'], ['change-notice-drafts', '变更报备草稿'],
  ]) },
  // No live remote-session route exists. Do not substitute a prototype or enable execution.
  { id: 'M7', title: '远程处置与审计', items: [] },
  { id: 'M8', title: '客户门户与通知', items: entries([
    ['ticket-notifications', '工单通知', '通知记录'], ['my-export-messages', '我的导出消息', '通知记录'],
    ['my-notification-preferences', '通知偏好设置', '通知设置'],
    ['wecom', '企业微信', '通知渠道'], ['email', '邮件通知', '通知渠道'],
  ]) },
  { id: 'M9', title: '月报与体检报告', items: entries([
    ['inspection-quarterly-reports', '季度巡检报告草稿', '服务报告'],
    ['inspection-templates', '巡检模板管理', '巡检管理'], ['inspection-worksheets', '人工巡检工单', '巡检管理'],
    ['inspection-source-collections', '巡检来源集合', '巡检管理'],
  ]) },
  { id: 'M10', title: '经营数据看板', items: entries([
    ['operational-comparison', '经营与人力测算'], ['capacity-estimates', '容量与缓冲评估'],
  ]) },
  { id: 'settings', title: '系统设置', items: entries([
    ['runtime-configurations', '运行配置与发布'], ['configurations', '配置项登记'], ['capability-catalog', '系统能力目录'],
  ]) },
]

export function navigationModuleTitle(name: unknown): string | undefined {
  const entry = navigationEntryName(name)
  return contractNavigationModules.find(module => module.items.some(item => item.name === entry))?.title
}

/** Use the same authorization gates for desktop, mobile and direct navigation. */
export function visibleNavigationModules(current: Session | null, routes: readonly AccessRoute[]): NavigationModule[] {
  const allowed = new Set(routes.filter(route => typeof route.name === 'string' && routeAllowed(route.meta, current)).map(route => String(route.name)))
  return contractNavigationModules.map(module => ({
    ...module, items: module.items.filter(item => allowed.has(item.name)).map(item => ({ ...item })),
  })).filter(module => module.items.length > 0)
}
