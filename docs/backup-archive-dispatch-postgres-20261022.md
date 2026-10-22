# 归档单次消费者：实际 PostgreSQL 组合验证

## 环境及授权范围

在 dot 自有云电脑新建可丢弃 PostgreSQL 17.11 集群，仅监听127.0.0.1:28177，禁用Unix套接字；全新mop_test库和临时测试角色/随机凭据，不读取或连接原服务器。PostgreSQL、客户端及libpq来自Debian官方HTTPS软件源，与Packages索引SHA256一致；仅解压到工作目录，不修改系统服务或系统软件配置。

通过独立初始化器显式应用既有V1–V4，然后逐项运行既有TestSchemaMigration显式命令应用V5–V50，每项1次。重复V50执行0次，检查前后只读检查均0次。V1–V50资源SHA256保持原字节，没有新增迁移，没有改写首次冻结证据；后续SQL仍必须V51起。应用时间/脚本/历史checksum见证据。

## 5项真实组合检查

BackupArchiveDispatchPostgresTest默认由明确环境门禁跳过；本次显式启用，使用真实PostgreSQL仓储、设备协调日志、运行日志、V48暂存/归档日志、真实JCE和生产消费者构造器。授权、KMS和对象存储为清楚标识的合成适配器，不是生产权限或云服务验证。

1. 原信封暂存 → 新START_STORE → 原字节存储 → 独立对象复核 → VERIFIED日志；新消费者再次读取不会PUT。父运行仍RUNNING，V49版本登记仍0。
2. 模拟对象PUT已经提交但响应丢失，真实日志转VERIFY_REQUIRED；换新消费者只读回查后记录VERIFIED，存储调用总数1。
3. 已START_STORE但对象尚未观察到，保持核查需求；重复核查不PUT、不反复追加未知标记，不虚构原派发未发生。
4. 第一消费者在PUT阶段阻塞，第二消费者经独立连接只能核查并记录未知；首个后续CAS不覆盖新事件，最终原命令复核成功。全程PUT总数1。
5. START_STORE之后撤销合成授权，存储调用0、结果拒绝；恢复授权后也只读核查，不把没有观察到对象解释为允许重发。

110张mop_业务表中105张完整行指纹不变；仅mop_asset、mop_backup_device_lease_event、mop_backup_run_event、mop_backup_archive_submission、mop_backup_archive_event按测试新增合成记录。Flyway历史单列保存；不能把111张含历史表的总表数与110张业务表混用。

## 结果及清理

5项全部实际执行通过。测试结束再次通过只读结构/权限检查，SQL源文件摘要不变；Hikari池关闭，PG停止，临时库及凭据目录删除。没有设备连接、真实KMS/OSS、自动调度、版本登记或父运行完成操作。

初次测试编译引用了persistence包私有schema verifier，不能从worker测试访问；移除该跨包调用，由独立迁移CLI完整核验结构，测试仅检查明确的50项历史门禁。未扩大产品类可见性、未改SQL。首轮编译失败记录保留，不计作成功运行。

完整默认回归精确执行/跳过数见validation.json；默认门禁仍跳过上述5项，不能把一次明确启用当成后续默认自动执行。前端未变化，浏览器105项/12文件实际仍NOT_RUN；128正式TC仍NOT_RUN。

门禁：MOP_BACKUP_ARCHIVE_DISPATCH_PG_TEST_ENVIRONMENT=dot-backup-dispatch-disposable。测试命令：mvn -pl server/worker -am -Dtest=BackupArchiveDispatchPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test。连接仅允许临时回环mop_test，迁移/运行角色分离，凭据不入源码或证据。

证据：docs/evidence/backup-archive-dispatch-postgres-20261022/。任务发现/调度、真实绑定和V49消费者接线仍待，不声明端到端生产备份完成。
