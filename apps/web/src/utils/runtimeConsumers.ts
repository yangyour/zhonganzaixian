import {capacityRuntimeKeys} from './capacityRuntimeInputs'
/** Exact fixed-key reader in TicketRuntimePolicy; publication is not proof of current adoption. */
export const ticketRuntimeKeys=['itsm.local_authority_mode','sla.p1_response_min','sla.p1_recover_min','sla.p2_response_min','sla.p2_recover_min','sla.p3_response_min','sla.advanced_p3_recover_min','sla.tenant_work_calendar','sla.basic_standard_p3_next_day','ticket.customer_confirm_timeout_hours'] as const
export function runtimeConsumer(key:string){
 if(key==='monitor.page_refresh_sec')return {title:'业务采用：已接入监控界面刷新',detail:'页面定期读取已发布周期；可以在监控页核对版本与实际周期。',path:'/network-monitoring',capability:'assets:read',action:'查看监控页面'}
 if(capacityRuntimeKeys.includes(key as typeof capacityRuntimeKeys[number]))return {title:'业务采用：已接入容量估算参数',detail:'容量估算页可读取并明确填入此参数；不改变实际采集周期、数据删除策略或基础资源。',path:'/capacity-estimates',capability:'capacity:estimate',action:'查看容量估算'}
 if(ticketRuntimeKeys.includes(key as typeof ticketRuntimeKeys[number]))return {title:'业务采用：已接入工单运行策略',detail:'明确启用工单时，在同一事务读取已发布配置并冻结SLA来源；已冻结工单不随配置回改。缺少、禁用或过期配置不使用默认值。此处说明代码接线，不代表已有工单采用或客户批准。',path:'/ticket-workflows',capability:'tickets:read',action:'查看工单流程'}
 return {title:'业务采用：尚未接入消费方',detail:'本键已具备值校验、内部审批、发布、回滚和回读；发布记录本身不会替代尚未实现的业务执行逻辑。',path:null,capability:null,action:null}
}
