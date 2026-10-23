# V50 通知原命令恢复与单次派发消费者

2026-10-23。本批复用已冻结 V50，不新增或改写 SQL。

## 已实现的内部契约

`PostgresDeliveryReceiptRepository.recover(access, scope, commandId)` 在现时授权事务中读取并完整重放 V50 日志，返回原转移命令、该命令最初应用版本以及当前日志头。原命令的 request ID、时间、trace、actor、权限版本及 expected version 均来自持久记录；不会按新时间重造命令。当前状态可能已由 UNKNOWN 前进为 ACCEPTED/DELIVERED/FAILED，不能把原命令版本当作当前状态。

没有根记录或对应转移命令时返回空；数据库错误/损坏返回 503，不能当作“未提交”。跨租户 404，现时读取授权在事务前后都核对。此方法恢复转移命令，不恢复 version=0 的 queue 创建命令；空结果本身不构成补发许可。它是内部 Java 接口，没有新增 HTTP 端点。

`DeliveryDispatch` 的公开构造路径绑定真实 `PostgresDeliveryReceiptRepository`；用于单元测试的 Journal 替身只在包内可见，不提供 runtime fixture 选项。

- `once(Original)` 要求调用方保留完整原 Started 命令。先读当前日志，已有相同原命令只返回当前状态；相同 ID 更改正文拒绝。仅 QUEUED 可尝试原 Started 的落库。
- 只有首次、非 replay、明确成功的 Started 提交结果才可能调用 `Provider.submit(scope, originalRequestId)`。提交结果不明先返回 RECONCILIATION_REQUIRED，不尝试发送。
- 已记录 UNKNOWN、ACCEPTED、FAILED、DEAD_LETTER 或 DELIVERED 的投递不由 `once` 再发送。失败/死信转为 MANUAL_REVIEW，不凭空配置重试预算。
- `reconcile(Original)` 核对完整原命令及当前最新 request ID，只对 UNKNOWN/ACCEPTED 调用 `Provider.lookup`。查无结果继续保持原状态；没有“查不到就重发”的分支。
- 回执必须匹配租户、delivery、原 request，沿用独立 observation command ID。重复相同 observation 使用原命令版本回放，不制造新版本；更改内容拒绝。记录前后仍经过仓储当前授权与 CommandVerifier。
- 新观察写入响应丢失时不认定失败或成功，下次读取当前日志核对。ACCEPTED 仅为受理；DELIVERED_RECORDED 仅表示日志记录了送达观察，`providerReceiptVerified` 保持 false。

消费者在读取、Started、外部提交、外部核对、回执记录和结果返回边界调用必需的 Authority。Authority 必须检查已批准运行绑定、当前租户授权、路由发布、接收对象、静音及额度；缺失时不能启用。外部操作不持有数据库事务，Started 必须先持久化，撤权发生在提交前则不调用供应商。丢失提交响应可能留下从未发送的 UNKNOWN，这需要原请求核对或明确人工处置，不能用自动重发消除不确定性。

## 运行边界

该消费者没有被 Spring 扫描注册、加入常驻 Worker 或接入真实供应商。Binding 禁用时不读库、不发送、不查询供应商；没有默认 enabled 配置。现有站内 `--notification-relay` 模式也不构造此消费者。

路由矩阵/偏好/绑定的事务化发布事实、持久路由队列发现与调度、具体供应商的可信签名/查询适配器、重试政策和人工处置 API/页面仍需继续实现。本批提供的是 V50 原命令消费和故障恢复的具体实现，不能描述为完整外部通知装配或渠道已上线。外部 Provider 与 Authority 必须是可信服务端实现，接口引用/布尔值不能充当客户批准或供应商认证。

## 验证

定向 reactor 共 93 项执行通过，2 项 PostgreSQL 环境测试跳过，0 失败/错误。其中新增 13 项消费者故障/重放测试、4 项恢复仓储授权/故障测试；还重跑日志编解码、领域回执状态、V50 冻结摘要与站内消费回归。明细见 [测试记录](evidence/notification-dispatch-20261023/tests.json)。

消费者测试明确使用隔离的 Journal/Provider 替身，并使用真实 V50 编解码恢复每次读取，覆盖提交响应丢失、重复消费者、网络结果不明、回执响应丢失、受理/送达区别、失败与死信、跨租户/原命令冲突、撤权和伪造回执。未把这些测试计为真实 PostgreSQL 并发、签名认证或供应商集成。

指定服务器的只读盘点沿用同日 [notification-relay inventory](evidence/notification-relay-20261023/inventory.json)，PG17.9／V50。无独立写测试命名空间核验，本批真实队列/回执写联调 NOT_RUN；未运行旧实际租户合成测试、未发送消息、未部署。正式 TC、客户渠道批准和 128 项总验收状态不变。

## 指定 PostgreSQL 隔离验证补充（2026-10-23 03:17 EDT）

上述 NOT_RUN 为首次代码提交时边界。随后按项目测试命名空间授权取得 `environment.lock`，在同一指定数据库新建并核验 `SYNTHETIC_NOTIFICATION_DISPATCH_412ac4c42a7d` 专用租户，用现有受限 `mop_test_runtime` 经临时回环 SSH 隧道验证；未恢复旧身份，未写实际业务租户。测试采用专用合成 actor 与明确的测试授权器，不代表真实成员权限或生产授权接线已验证。

新增 `DeliveryDispatchPostgresTest` 6 项真实数据库检查全部通过：6 个并发消费者仅首次确认 START 调用一次测试适配器；真实 COMMIT 完成后注入响应丢失仍能恢复原命令；查询原 request 推进到 DELIVERED 观察；重复 ACCEPTED 不增加日志行；未登记观察被 CommandVerifier 拒绝；撤权后恢复拒绝；FAILED 不自动生成新尝试。

首轮测试授权器误对只有 SELECT 权限的租户目录使用 FOR SHARE，6 项在 queue 前报错，日志行 0。修正测试为只读 namespace 核验后重跑成功，未扩大角色权限。保留 [首轮记录](evidence/notification-dispatch-20261023/designated-postgres-attempt1.json)、[成功记录](evidence/notification-dispatch-20261023/designated-postgres.json) 和 [6 项用例结果](evidence/notification-dispatch-20261023/designated-postgres-tests.json)。

专用租户保留 15 行不可变合成回执日志作为证据，其他租户 V50 日志前后均 0 行。V50、0 迁移、既有组件生命周期与端口保持；临时 JDBC 连接池和隧道已关闭，环境锁已释放。供应商为明确的隔离测试适配器，外发 0；这是真实 PostgreSQL 恢复/并发证据，不是供应商签名、送达、完整路由调度或正式 TC 验收。

复验须先取得环境锁、核验专用合成 namespace 和角色，再通过私有环境提供 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_PASSWORD`、`MOP_NOTIFICATION_TEST_TENANT`、`MOP_NOTIFICATION_TEST_ACTOR`、`MOP_NOTIFICATION_TEST_NAMESPACE` 及门禁 `MOP_NOTIFICATION_DISPATCH_TEST_ENVIRONMENT=designated-isolated`。仅运行 `mvn -B -pl server/worker -am test -Dtest=DeliveryDispatchPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`；测试不自行创建 namespace、不自动迁移或删除历史。不要改用业务租户或启用旧合成测试。
