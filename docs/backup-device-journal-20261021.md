# 设备协调事务与数据库验收（2026-10-21）

状态：隔离 PostgreSQL 17.11 验证通过，未作为生产备份执行能力开放。前置纯状态模型见 [租约状态基础](backup-device-lease-core-20261021.md)。

## 能力与限制

- 每个租户/设备使用一份追加式协调事件日志；现有资产行锁串行化事务，版本与 fencing token 单调推进，数据库时钟填写全部事件时间。
- ACQUIRE、RENEW、RELEASE 不涉及 SSH、密码、备份内容、调度或客户设备命令。
- 过期但未释放的记录必须保持阻塞。租约到期、接口超时、数据库断连都不能证明远端进程已停止；本增量没有强制解锁或过期接管入口。
- 新请求仅可跟在明确 RELEASE 后。历史请求重试返回原状态，不重新获得租约；释放旧请求的重试不得改变新请求。
- 仓储必须显式提供当前授权检查和停止核查适配器，没有默认允许实现，没有 Spring 自动装配，没有 HTTP 路由，也没有后台消费者。
- 授权适配器须在同一数据库事务中锁定当前权限/撤销版本直到提交。停止核查适配器须独立核实已停止/从未启动的证据，单独提供 sha256 字符串不构成证明。实际适配器仍是待接入项。
- 单次事务 lock_timeout=5s、statement_timeout=10s、JDBC queryTimeout=12s 是存储技术保护，不是业务租期默认值。显式租期范围 1ms–5min；短租期可能在核查过程中到期并被数据库拒绝。
- 读接口和重放也执行当前授权。读接口只返回指定请求当前是否占用的观察事实，不授予执行权。

## 验收标准

1. 两个独立连接并发申请同一设备时恰好一个获得租约；不同设备互不阻塞。
2. 错误 owner、token、version、租户或请求不得更新状态；错误响应不泄漏 SQL/凭据。
3. 丢失申请响应后的相同请求不增加日志；请求体不同则拒绝；已释放旧请求不再次抢占。
4. 过期后申请接管、续约、释放都失败，当前观察明确为 RECONCILIATION_REQUIRED。
5. 停止证据未通过核查时事务回滚，不增加日志。
6. 数据库拒绝调用者伪造观察时间、跳版本及缺少释放确认引用。
7. 运行角色不能 UPDATE、DELETE、TRUNCATE 日志；主键、唯一索引、范围外键及 CHECK、触发器、ACL 都需独立验证。
8. V1–V45 不改写；V46 仅显式测试迁移命令可应用，服务启动不自动执行。
9. 数据库和合成适配器验收不能标为真实设备、真实授权/停止协议验收。该增量不增加前端通过项。

## 证据记录

- 首次实验在启动数据库前因复用旧 PDF 驱动的 JAR 哈希预检失败，未运行 SQL、未启动服务。已保留 exploratory-attempt-1/preflight.log。
- 实验性直接 SQL 应用后 29 项 JDBC 检查通过，不冒充 Flyway 验收。
- 第一次完整 Flyway V46 已成功应用，但后置校验器的 text[] / varchar[] 比较失败。仅修正 Java 显式类型转换；V46 SQL 从首次应用起不再改写。
- 独立目录探针还发现测试使用的 SQL, DMY 不受 PostgreSQL JDBC 支持，改为合法 ISO, DMY；原错误保留。7 项目录/防篡改复验通过。
- 最终 flyway-attempt-2：V1–V46 全部成功，V46 新增 1 张日志表，既有业务表指纹不变，仅迁移历史新增一行；重复应用与 --check 均不变更 106 张表指纹。
- 同一隔离库执行 33 项 JDBC 业务断言、7 项结构/防篡改检查通过。篡改在事务中回滚，106 张表指纹不变。临时数据库、进程、凭据已清理。
- 完整 Maven verify：3924 项，2910 执行通过，1014 按环境跳过；新增 10 项单元测试通过。新增 PostgreSQL JUnit 测试在默认 Maven 中跳过，等价业务检查通过独立 JDBC 驱动实际执行，二者不重复计数。
- Python 121 项、Node 合同 173 项通过；API 0.76 生成校验通过，未增加 API 路由。首次 Python 失败是新增版本未纳入两端迁移清单，清单、模式、上限更新后复验通过。
- 本增量未改前端，不新增浏览器通过数；53 条浏览器规格与 128 项正式 TC 仍 NOT_RUN。

## 执行方式与证据

显式迁移模式为 --apply-backup-device-coordination，仅允许既有 V45 历史完整的 mop_test。Java CLI、Python 包装与 PowerShell 纯校验策略已一致更新。服务启动不自动迁移。普通 Maven verify 不连接数据库；只有明确设置 MOP_DEVICE_LEASE_TEST_ENVIRONMENT=dot-backup-device-lease-disposable 才会启用仓储数据库测试，必须使用自建可丢弃库和合成数据。

[验证汇总](evidence/backup-device-journal-20261021/validation.json)、[最终迁移摘要](evidence/backup-device-journal-20261021/flyway-attempt-2/migration-summary.json)、[首次实际应用](evidence/backup-device-journal-20261021/flyway-attempt-1/v46-application.json)、[最终应用与表指纹](evidence/backup-device-journal-20261021/flyway-attempt-2/v46-application.json)、[失败与复测历史](evidence/backup-device-journal-20261021/development-attempts.json)均保留。

证据 driver 是此次隔离环境的运行记录，包含当时临时工具路径，不是生产启动脚本。可重复的 Maven 测试源码在 server/persistence/src/test/java/cn/mop/persistence/BackupDeviceLeasePostgresTest.java。没有真实授权、停止核查或物理 fencing 适配器，不得把合成测试适配器装入运行配置。
