# 通知派发与回执开发进度

## 仓库归属核对（2026-10-23）

- 项目：众安在线 MOP；模块：通知后台消费、路由与回执恢复。
- 独立目录：`E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\notifications`。
- origin：`https://github.com/yangyour/zhonganzaixian.git`；组织：`yangyour/zhonganzaixian`。
- 功能分支：`codex/launch-notifications-20261023`；开始时上游：`origin/main`。
- 初始 HEAD 与 fetch 后 main：`05ec77ba`。依据为本检出 Git 配置、AGENTS 首节和 `docs/parallel-development-20261023.md`，未操作原工作区。

## 功能一：工单站内通知后台消费者

定向 reactor 回归：28 项执行通过，2 项旧 PostgreSQL 写测试按环境门禁跳过，0 失败/错误；明细见 [tests.json](../evidence/notification-relay-20261023/tests.json)。首次 PowerShell 未引用带点 Maven 参数导致命令解析失败，修正参数引用后正常构建；该次未执行测试，不计为测试失败或通过。

代码已接入 Worker 的独立 `--notification-relay` 模式。显式租户白名单、有界批次、公平轮询和故障续处理复用 V34 的 outbox→intent→message 原子事务；重启或提交响应丢失后仍按原 outbox 去重。接收人有效性查询持有成员与用户共享锁至事务结束，避免撤权穿过本次消息写入。

无新增迁移、HTTP 写端点或外发适配器。外部渠道继续 `NOT_CONFIGURED`；未启动常驻 Worker、部署或发送外部消息。配置与实际边界见 [后台消费者运行说明](../notification-relay-worker-20261023.md)。

验证：11 项新增轮询/配置单元测试已通过；通知相关回归结果另见证据记录。真实指定环境只读检查确认 PostgreSQL 17.9、V50、outbox 0 行、V50 journal 0 行、运行账号具有来源与日志读取能力。没有核验可用于本轮写测试的独立租户及身份；旧集成测试固定指向已切真实数据的租户，未启用。不能把只读盘点和隔离单元测试作为真实投递联调或正式 TC。

## 后续本线工作

继续补 V50 原命令恢复、当前状态核对与 UNKNOWN 不重发的消费者链。路由发布/绑定的事务核验、供应商可信回执、受控失败重试与人工处置仍未完成；客户正式矩阵、接收人、回执协议、额度及自动外发授权继续独立登记。128 项合同状态不因本次局部功能变更而标 DONE。
