# 持久备份任务发现与独立授权

2026-10-23，首个并行备份增量。复用 V48 原信封及事件、V49 已登记版本，不改写冻结 SQL，不新增迁移。

## 内部 Java 契约

`PostgresBackupTaskDiscovery.discover(Access, AssetScope, Cursor, limit)` 按一个明确租户/设备枚举尚未登记版本的持久归档来源。`limit` 为 1–50；按原暂存时间和版本 ID 升序键集分页，额外读取一条判断下一页。游标绑定租户和设备，跨范围在连接数据库前返回 404。

返回 `Page(items, nextAfter)`。每项包含原 scope、submissionId、暂存时 runVersion、stagedAt、最新归档 phase/eventVersion 和原 dispatch 标识；不返回信封字节、密钥引用、对象地址、采集内容或个人身份。`executionAuthorized=false`，`STORE_STARTED`/`VERIFY_REQUIRED` 明确为待核对。`VERIFIED` 仍只是待登记发现结果，不证明父运行完成、当前对象健康或可以下载。发现结果会过期，执行前仍须由原仓储检查当前事实和 CAS。

查询只从已有暂存来源发现；不从计划草稿创建运行，不自动补采集证明，不把 `CONFIRM_NO_DISPATCH` 后的来源变成重传许可。V49 已登记版本排除；单个异常或畸形行使整页失败，数据库不可达不会返回成功空列表。外部服务和新 HTTP 路由未加入，因此不存在新增浏览器写接口或 CSRF 入口。

## 独立授权与装配

公开构造器必须接收 `PostgresBackupTaskAuthorization`，无默认允许回调。配置提供者每次检查返回当前 `BackupTaskGrant` 列表；缺失、异常、过期、撤销均拒绝。每个 grant 明确绑定 `backup-task-binding:` 引用、租户、设备、账号、权限版本、到期时间及操作集合；没有通配范围、管理员旁路或草稿权限继承。

操作分开为 DISCOVER、ARCHIVE_READ、ARCHIVE_START、ARCHIVE_RECONCILE、VERSION_READ、VERSION_REGISTER。授权读取当前 active 用户/成员，严格比较 permissionVersion，并在调用者事务内持有成员和用户 `FOR SHARE` 锁。读取前后再次读取配置授权，不能使用陈旧配置结果返回查询。配置引用是独立部署授权标识，不代表客户生产批准。

`archiveAuthorizer()` 与 `versionAuthorizer()` 可直接装入已有仓储，保留原事务内授权、审计日志、幂等及乐观锁。备份任务授权不能授权新暂存或 `CONFIRM_NO_DISPATCH`；加密、对象存储和采集完成证明仍须独立实现并核验。

## 运行与验证

没有自动 Spring Bean、默认账号或启动开关，本次不启用后台执行。已有 V48/V49 数据是唯一任务事实来源；数据库故障返回 503。正式运行仍需受控当前 grant 配置提供者及后续调度装配，不能用单元测试允许回调充当生产授权。

- 19 项 Java 单元/边界测试通过，使用 JDBC 替身，明确不是数据库并发验证。
- 1 项指定 LAN PostgreSQL 真实只读流程通过：现有身份/设备选择、当前身份锁、原日志查询、末尾游标空页及失效权限版本拒绝；没有创建测试身份、任务、对象证明或业务记录。
- `BackupTaskDiscoveryPostgresReadTest` 默认跳过。仅在受控 SSH 隧道及 `MOP_BACKUP_DISCOVERY_READ_ONLY=true` 下运行；连接资料通过 `MOP_BACKUP_READ_JDBC`、`MOP_BACKUP_READ_PASSWORD` 提供，账号固定受限 `mop_test_runtime`，不输出凭据。
- 首次 Maven 命令因 PowerShell 拆分未引号包裹的属性失败，未执行测试；修正参数引号后通过，未隐藏失败尝试。
- 后续补采组件版本时，受限运行账号读取 Flyway 管理历史被拒绝。保留该失败，移除不属于业务运行账号职责的管理表读取；未放宽权限。驱动元数据确认 PostgreSQL 17.9（Debian 17.9-1.pgdg12+1）；本轮不以历史文档代替新的管理历史检查。

真实任务非空分页、多进程争用、原对象核对、KMS/OSS、设备、下载/解密、恢复、失败工单、清理与演练没有因此验收。正式合同 TC 保持 NOT_RUN；后续调度是下一独立功能。

后续增量：发现项已增加 originalLease，来自暂存时 runVersion 所绑定的不可变运行事件；用于固定登记输入，不代表当前可重新执行设备。显式轮询、授权装配与原命令恢复见 [调度实现](backup-task-scheduler-20261023.md)。
