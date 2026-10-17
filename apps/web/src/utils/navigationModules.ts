import type {Session} from '../api/types'
import {routeAllowed,type AccessRoute} from './routeAccess'
export interface NavigationItem {name:string;title:string}
export interface NavigationModule {id:string;title:string;items:NavigationItem[]}
/** Detail-only routes retain the context of their existing, permission-filtered entry. */
export function navigationEntryName(name: unknown): unknown { return name === 'contact-import-preview' ? 'contact-imports' : name }
const modules:NavigationModule[]=[
 {id:'resources',title:'资源与监控',items:[['dashboard','运营总览'],['assets','资产管理'],['network-monitoring','基础网络监控'],['threshold-diagnostics','告警阈值诊断']].map(([name,title])=>({name:name!,title:title!}))},
 {id:'service',title:'工单与服务',items:[['tickets','报障与受理'],['manual-incidents','手工故障登记'],['sla-scenarios','SLA 场景测算'],['asset-export-jobs','台账导出'],['my-export-messages','我的导出消息'],['my-worklogs','我的工时记录'],['my-notification-preferences','通知偏好设置']].map(([name,title])=>({name:name!,title:title!}))},
 {id:'maintenance',title:'备份与巡检',items:[['backup-plan-drafts','备份计划草稿'],['change-notice-drafts','变更报备草稿'],['inspection-templates','巡检模板管理'],['inspection-worksheets','人工巡检工单'],['inspection-source-collections','巡检来源集合']].map(([name,title])=>({name:name!,title:title!}))},
 {id:'customers',title:'客户与租户',items:[['tenant-inventory','租户资源台账'],['customer-contacts','客户联系人'],['contact-imports','联系人批量导入']].map(([name,title])=>({name:name!,title:title!}))},
 {id:'operations',title:'经营与容量',items:[['operational-comparison','经营与人力测算'],['capacity-estimates','容量与缓冲评估']].map(([name,title])=>({name:name!,title:title!}))},
 {id:'settings',title:'系统管理',items:[['identity-users','用户与成员授权'],['identity-roles','角色与权限'],['identity-settings','身份认证设置'],['runtime-configurations','运行配置与发布'],['configurations','配置项登记'],['capability-catalog','系统能力目录'],['audit','审计日志']].map(([name,title])=>({name:name!,title:title!}))},
]
/** One responsive navigation, using the same route gates as direct navigation. Unknown routes stay hidden. */
export function visibleNavigationModules(current:Session|null,routes:readonly AccessRoute[]):NavigationModule[]{
 const allowed=new Set(routes.filter(r=>typeof r.name==='string'&&routeAllowed(r.meta,current)).map(r=>String(r.name)))
 return modules.map(group=>({...group,items:group.items.filter(item=>allowed.has(item.name)).map(item=>({...item}))})).filter(group=>group.items.length>0)
}
