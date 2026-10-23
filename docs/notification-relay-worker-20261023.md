# 工单站内通知后台消费者

2026-10-23。本功能把已有 V34 通知仓储接入独立 Worker；此前生产代码没有调用 `relay` 的消费者。

## 运行与内部接口

构建 `mvn -B -pl server/worker -am package` 后，以 Worker 工件参数 `--notification-relay` 启动。该模式只消费已提交的工单 workflow outbox，不启动文件处理、SLA、自动关单或其他作业，不监听 API/Web 端口。启动行为仍须按部署流程显式操作，本次开发未启动或部署。

私有环境必须显式提供：

| 配置 | 约束 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `remote_test` |
| `MOP_TICKET_LIFECYCLE_ENABLED` | `true` |
| `MOP_NOTIFICATION_RELAY_ENABLED` | `true`；缺省不启用 |
| `SPRING_DATASOURCE_URL` | 经隧道的 `jdbc:postgresql://127.0.0.1:<port>/mop_test`，拒绝附加 URL 参数 |
| `SPRING_DATASOURCE_USERNAME` | `mop_test_runtime` |
| `SPRING_DATASOURCE_PASSWORD` | 只从私有配置注入，不写文档或命令行 |
| `MOP_NOTIFICATION_RELAY_TENANT_IDS` | 1–100 个明确 UUID，以逗号分隔；拒绝重复、空项与通配符 |
| `MOP_NOTIFICATION_RELAY_BATCH_SIZE` | 1–100，每个租户每轮一批 |
| `MOP_NOTIFICATION_RELAY_POLL_MILLIS` | 100–60000，运维轮询间隔，不是业务通知时限 |

`TicketNotificationRelay.poll` 返回确认落库意图数、失败租户数和依赖就绪情况。数据库/schema/权限不就绪时不消费；单租户批次异常保留待处理来源，下轮重试，继续其他租户。进程不输出私有异常、接收人或数据库连接详情。

原仓储的事务级租户锁及唯一 outbox 引用负责重复消费者互斥和去重。一次批次的意图与全部接收人消息同事务提交；只有提交成功才计入确认数。提交响应丢失不会据此认定失败或另造来源 ID，下一轮重新查询已提交意图。没有待处理数据时如实返回 0。进程停止后无需恢复内存游标；待处理集合由原数据库事实决定。

API 仍沿用 `GET /api/v1/my/notifications`、`POST /api/v1/my/notifications/{id}/read` 和 `GET /api/v1/notification-intents`，鉴权、CSRF、幂等与跨租户隔离契约未变。没有新增公开发送接口。外部 Email/WeCom/SMS/VOICE 等不由该消费者调用。

## 验证与限制

新增 11 项单元测试覆盖显式配置、租户边界、受限连接、公平批次、错误隔离、提交响应丢失后重新查询、停止和异常返回值；这些是明确隔离的轮询测试，不冒充数据库并发或发送验收。

指定 LAN PostgreSQL 17.9 / V50 已只读探测，证据见 [inventory.json](evidence/notification-relay-20261023/inventory.json)。本轮没有写入测试租户、恢复旧身份、建本地数据库或运行自动迁移。现有实际业务租户不能用于合成通知测试；发现其他租户的存在也不能直接推断其可用于本轮测试。独立命名空间和当前有效测试身份未核验，因此真实 PG 的通知批次、撤权并发、提交响应丢失及后台持续消费仍待联调。

站内 outbox 消费并不意味着外部通知路由、可信回执、供应商送达或正式合同 TC 通过。路由发布和客户未知配置保留原状态。
