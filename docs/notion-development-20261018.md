# Notion剩余功能接续（2026-10-18）

本轮已通过用户连接的Notion读取总索引及D05—D14十个子页；总索引ID为`2c438d8df572417eb2ae7abe9b28148d`，最后编辑2026-10-18。文档基于API0.52/V32，按其P0工单、通知优先级接续。Notion示例是实现参考，不直接执行其中SQL、部署命令或替换已批准的项目安全约束。

## 与现有实现对齐

- 示例的`com.mop`、`tickets/accounts`和`src/frontend`须适配实际`cn.mop`、`mop_ticket/mop_user`和`apps/web`。冻结V1—V32不改，新结构从V33追加，应用启动不自动迁移。
- 租户身份继续来自已认证的服务端会话及实时成员授权；不信任`X-Tenant-Id`决定范围。错误使用现有响应封装、CSRF、幂等与CAS，跨租户统一404。
- 不导入示例P1=1/4h、P2=4/24h、P3=24/72h种子，不给旧报障默认P3。采用合同登记/明确发布配置并冻结来源；基础档工作日历和P3“次日”未定义时保持未知。
- Notion的RESOLVED表示“已解决，待客户确认”，不是已关单。保留合同20字解决说明、客户身份核验、确认提醒、超时关闭以及关闭后72小时重开；转派/重开不延后原SLA截止。
- 不明文存企微Secret，不把供应商受理当送达，不静默丢弃未配置通知。Notion内的RabbitMQ/企微示例需要耐久去重、租约、回执/未知结果处理后才能用于真实派发。
- D05防火墙密码过期已由2026-10-14后续证据解除，不能用新文档的旧描述覆盖；当前可用性须重新探测。D08联系人100行耐久导入与重启恢复已有真实证据，不另建一套导入Worker。资产业务入库、通知与不同规模指标分别保留。
- D09、D10、D06及D12的内部审批、运行生效、设备执行、报告发布与客户生产批准分别留证。D13不安装共享组件或开放设备连接；路线、组件及录屏能力未验证时不得发放会话。D14不会因迁移版本达到示例V40而宣称上线通过。

## D07：状态与冻结时钟核心已验证

新增`TicketLifecycle`和`TicketSlaSnapshot`，实现OPEN→ACCEPTED→IN_PROGRESS→RESOLVED→CLOSED、转派、客户未解决/关闭后重开、管理员撤销和独立服务端超时关闭计算。首次受理时间不覆盖；每次解决有独立确认周期；累计两次重开产生经理复核事件意图。内部角色不能代客户确认，非负责人普通工程师不能处理；过期版本409、非法状态422。

SLA按明确优先级/档位/参数冻结，工作时段推进复用已有区间算法；50%/80%/100%节点按有效工作时长计算。配置缺失/不完整保留具体原因和null，不回退4小时；P3次日必须明确给出规则计算的时间。该层产生到期事实，尚不能声称已持久化或已发通知。

验证：`mvn -B -ntp -o -pl server/api -am '-Dtest=TicketLifecycleTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，34用例、0失败、0错误、0跳过；覆盖完整路径、跨角色拒绝、CAS、转派冻结、20码点、72小时边界、确认到期、缺配置不关单、重复确认周期、工作周末时钟、取消和时间倒退。首次命令因PowerShell未引用点号参数失败，引用后执行成功；未把首次命令失败当业务失败。

随后追加V33并完成指定测试服务器真实持久化验证：V29—V32从原V28按冻结迁移顺序补齐，V33一次应用、重复0次、只读结构/权限核验通过。`PostgresTicketWorkflowIntegrationTest`使用项目测试命名空间的合成报障，真实验证OPEN→ACCEPTED→IN_PROGRESS→RESOLVED→CLOSED→IN_PROGRESS、客户投影隐藏负责人、旧版本409、跨租户404、只追加事件和通知待办；2项通过。证据见[ticket-workflow-integration-20261018.json](evidence/ticket-workflow-integration-20261018.json)。

HTTP控制器、前端页面、迁移和持久层均已完成对应代码检查，但未启动发布站切换或企业微信真实送达；D07合同整体状态仍NOT_RUN。

## D07/D11/D16：工单通知意图与本人站内通知已验证（V34 / API 0.54.0）

针对D07工单生命周期与SLA节点产生的`mop_ticket_workflow_outbox`事件，新增V34 `mop_notification_intent`与`mop_notification_message`、领域规划器`TicketNotificationPlanner`、PostgreSQL中继/查询/首次已读仓储、API控制器`TicketNotificationController`及前端`TicketNotificationsPage.vue`。

- 仅已明确归属的报障人、当前负责人及协办人生成站内通知；Q09服务经理角色、Q15值班队列与升级链未确认前记录在`pending_reasons`，不猜测接收人。
- 外部渠道（企业微信、短信、语音、邮件）因Q14渠道矩阵与Q18凭据未配置固定标记为`NOT_CONFIGURED`，不伪造已送达回执。
- 数据库触发器强制校验outbox来源、延迟约束校验意图`recipient_count`与实际站内消息行数一致、校验接收人成员/角色/报障人资格，并限制站内消息仅允许首次已读更新（`version 0→1`）。
- 远程PG17.9 `mop_test`完成V34首次迁移、重复0次及只读检查；`PostgresTicketNotificationIntegrationTest`（2项）、`TestSchemaMigrationTest`（41项）、`TicketNotificationPlannerTest`（9项）、`TicketNotificationControllerTest`（8项）、`server/api`全量（1845项）、OpenAPI契约（135项）及Web全量测试（156文件/2204项）与构建通过。证据见[ticket-notification-intents-20261018.json](evidence/ticket-notification-intents-20261018.json)。

## D16：租户服务档位、只追加变更台账与工单冻结SLA时钟核验已验证（V35 / API 0.55.0）

针对合同M2-10、M5-8、M5-18、M5-20，新增V35 `mop_service_tier`与`mop_service_tier_change`、领域模型`ServiceTierRepository`与`TicketSlaClockEvaluation`、PostgreSQL仓储`PostgresServiceTierRepository`、API控制器`ServiceTierController`（`GET /api/v1/service-tiers`、`POST /api/v1/service-tiers/changes`）、工单SLA时钟核验接口`GET /api/v1/tickets/{id}/sla`及前端`ServiceTiersPage.vue`。

- 档位快照与变更历史由延迟约束触发器强制同事务成对写入，变更历史只追加、禁止UPDATE/DELETE；可用性目标从`sla.tier_availability_target`运行配置解析，停用配置时不回退默认值。
- 20项SLA时钟边界测试（`TicketSlaClockBoundaryTest`）覆盖23:50跨夜、节假日、跨月、待客户确认暂停、50%/80%/100%阈值、P3次日口径（Q08）、重开策略待确认（Q27）及档位变更后在途工单不追溯。
- 远程PG17.9 `mop_test`完成V35迁移验证与`PostgresServiceTierIntegrationTest`（2项）：验证3张在途工单在租户档位从BASIC连续变更为STANDARD、ADVANCED后，原响应/恢复截止时间与提醒任务完全不变。`server/api`全量（1871项）、OpenAPI契约（136项）及Web全量（157文件/2210项）与构建通过。证据见[service-tiers-and-sla-clocks-20261018.json](evidence/service-tiers-and-sla-clocks-20261018.json)。

## 当前环境阻塞

读取Git忽略的既有LAN连接配置时，LAN测试站SSH握手失败（SSHException / banner未取得，尚未进入认证）；随后独立端口检查SSH读取超时，10010可建TCP但ready/live读取RemoteDisconnected。原指定测试服务器随后恢复并通过项目SSH隧道完成V29—V35与PG真实测试；LAN站点的当前版本/HTTP/浏览器证据仍未取得。未安装本机替代组件、未修改远端服务配置。

## 模块接续台账

|Notion模块|本轮状态|尚需完成/复核|
|---|---|---|
|D07 工单闭环|V33状态机/事件/Outbox/时钟任务及API/UI/真实PG已验证|测试站部署切换、正式SLA/客户规则确认与合同TC|
|D11 通知与企微|V34工单通知意图与本人站内通知（API0.54/Web/真实PG）已验证|Q14/Q15/Q18确认后外部企微/短信/语音派发、死信与H5门户|
|D16 SLA与值班|V35服务档位快照、只追加变更历史与工单SLA冻结时钟核验（API0.55/Web/真实PG）已验证|正式SLA报表/补偿（Q26）、值班看板与升级链（Q15）|
|D15 告警事件链|V29/V30原始与隔离存储已有|归并/防抖/抑制/维护窗口、事件处置状态机与自动建单|
|D05 采集|已有AF持续采集证据；当前连通性未确认|SCP授权与资源能力、采集故障恢复|
|D10 变更|已核对设计|审批/窗口配置/运行匹配，不把审批当设备执行|
|D09 巡检|已核对设计|模板发布、执行证据、报告生成与冻结|
|D06 容量|已有运行参数消费；未完成告警/审批|审批及真实时序阈值计算，未知不当正常|
|D08 导入|联系人Worker已存在；不重复实现|资产提交、结果通知及完整规模复验|
|D12 报告|已核对设计|真实同源指标、不可变报告、H5/PDF、阅读与推送|
|D13 堡垒机|组件/客户路线未验证|审批控制、适配接口、录屏可用性与真实联调|
|D14 上线|未验收|128项TC、TLS/隔离/备份恢复及生产批准|

