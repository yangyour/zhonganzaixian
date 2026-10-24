# 首页通知受理统计与实际送达证据边界

对应 UI02 / N03 / M1-1。本批完成现有邮件与企业微信业务通知的真实受理读取、首页受理数和同快照明细，不把它标作供应商回执验真或送达率完成。

## 实际来源

`GET /api/v1/dashboard/notification-acceptance` 只读、no-store、禁止查询参数。当前会话 msp/padmin 须同时具有 dashboard:read、tickets:read、ticket_notes:read_internal。数据库重复读快照和返回前会话/账号/完整成员复核；前端上下文变化取消并清空旧值。

只查询 `mop_email_delivery`、`mop_wecom_delivery` 中当前租户、`kind=NOTIFICATION`、创建时间在数据库时钟的 `[now-24h,now)` 内的记录。`TEST` 不进入统计，不向供应商发请求。最大完整合并范围10000，超限422，不截断为完整数。返回ID、渠道、状态和时间；不返回接收地址、消息正文或供应商原始载荷。渠道及全部计数来自同一完整列表。

SMTP接受/企业微信API接受只证明 ACCEPTED。现有V50回执状态明确 `providerReceiptVerified=false`，所以其DELIVERED声明完全不作为此投影的实际送达证据。

- `counts.accepted`：当前24小时创建队列中，已有受理终态的记录数，不是24小时内所有历史消息的受理事件流。
- `verified_delivered=null`、`delivery_rate_pct=null`、`delivery_rate_below_95=null`。
- `delivery_verification_status=AUTHENTICATED_DELIVERY_SOURCE_NOT_CONNECTED`。
- 空队列也不能把未接入的送达来源写成0或100%。

后续完整送达率仍需要已认证、关联到真实发送请求、可去重和回读的供应商回执/查询适配器，以及明确的覆盖人群。V51表结构中的RECEIPT类型名称不是完成证据；本轮没有伪造配置或验真记录。

## 页面

卡片把“受理”和“送达”分开，具备读取失败、取消、无权限和超过5分钟的状态。明细可按渠道/状态筛选、20条分页、取消和重读；和卡片共享同一快照。超过5分钟只作为明确标注的历史快照保留，卡片不继续显示旧受理值。实际送达率不可计算时不触发低95%黄色结论。

## 验证与剩余项

证据见 `docs/evidence/dashboard-notification-acceptance-20261024/validation.json`。覆盖所有本地状态、渠道分解、窗口边界、跨范围、终态时间约束、重复记录、撤权与切租户、取消、分页与筛选。

新增3项指定PG只读规格，需 `MOP_RUN_NOTIFICATION_ACCEPTANCE_PG_TESTS=true` 及已有 `MOP_REPORT_CAPTURE_*` 隔离身份/受控连接。当前未提供，NOT_RUN。新增2项桌面浏览器规格，全部197项/31文件仅发现，未绕过既有启动限制。单元和DOM不冒充真实供应商、浏览器或正式合同验收。

无部署、迁移、身份/权限创建、通知发送、回执伪造、真实数据写入或演示初始化。UI02/N03仍IN_PROGRESS，后续继续实际送达来源及其他页面功能。
