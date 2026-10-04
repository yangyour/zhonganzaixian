# 独立、单任务、仅准备文件的导出Worker

## 范围

`server/export-preparation-worker` 生成独立 `mop-export-preparation-worker-0.1.0-SNAPSHOT.jar`。入口是 `cn.mop.exportworker.ExportPreparationWorkerApplication`，不启动Spring上下文/Web服务器/Flyway，也不构造存储或通知客户端；包内不包含原photo/import/contact Worker和mop-adapters/MinIO依赖。API/原Worker包不因此增加POI运行依赖。

它只处理一个明确job，不调用claimNext、不清空其他队列，不建立账号或改变成员权限。准备成功的进程exit=0不表示完整导出完成：数据库仍RUNNING，或已有原文件的过期接管为RECONCILING_STORAGE。发布、OSS核查、15分钟授权下载和站内信均未执行。

## 显式启动边界

参数必须是 `--prepare-one-without-publication <canonical-job-uuid>`。必须显式提供：

- SPRING_PROFILES_ACTIVE=remote_test，MOP_EXPORT_PREPARATION_WORKER_ENABLED=true
- 已批准loopback项目库的SPRING_DATASOURCE_URL、mop_test_runtime身份和私有密码；不接受远端地址、URL附加选项或migrator账号
- MOP_EXPORT_PREPARATION_WORKER_ID（技术标签，不是认证凭据）
- MOP_EXPORT_PREPARATION_LEASE_MILLIS（没有默认值；1000–600000的技术范围）

其他已启用Worker角色不能混入同一启动环境。密码只从受控私有环境注入，不放进参数、Git、日志或证据。本次测试租期明确为30000ms，属于验证输入，不是生产重试或容量决策。

输出仅有阶段和描述字段，不返回文件bytes/地址/凭据。NOT_CLAIMED返回exit=3；异常返回exit=1和脱敏错误码。正常阶段是PREPARED_STORAGE_NOT_ATTEMPTED，复用旧文件的阶段是RETAINED_REQUIRES_STORAGE_RECONCILIATION；两者published均false。

## 真实四进程证据

使用固定SHA、只读且独立复制的JAR，不直接运行Maven可变target。信号前校验uid、直接父进程、完整Java argv及目标job、exe路径、启动ticks和已钉住JAR摘要；不按端口或进程名停止其他服务。

1. 新建明确标识的合成源资产和自己的导出请求。第一个真实Worker JVM读实际PG、生成实际16列XLSX并持久化，正常退出
2. 明确修改该源资产的备注，再在旧租约过期后启动第二个JVM。fence=2，恢复的原文件SHA/selection SHA和原备注均未改变，状态保留RECONCILING_STORAGE；没有用当前新台账重新生成另一份文件
3. 第二个独立源资产/请求用于故障场景。在真实PG审计advisory锁上，先观察claim事务等待，再排队到claim提交之后持锁，实际观察prepare审计阶段等待。只对经过完整归属验证的第三个JVM发SIGKILL，exit=-9；原已提交fence=1/租约保留，未提交的文件/版本/审计整体回滚，没有半文件
4. 租约过期后第四个JVM以fence=2恢复，产生并回读正确实际XLSX，正常退出。两个原受理收据仍QUEUED/version=0，七张身份/其他消费者表的行数与逐行hash聚合相同

两个合成源资产和历史请求保留，没有删除真实业务数据。四个Worker均已结束，证明后对应PG连接数为0；API/PG没有为本次测试重启。共15项Java设置/编排单元检查、9项Python不可执行制品/进程元数据门禁检查，不能与四个真实JVM运行重复相加。

本次固定JAR SHA256：e64a590174ddbedee181b96176da58d9893bac1ac7f9a8616648cf583abf3aa9；完整回归后的最终构建摘要相同。源码与控制工具见tools/niko-test/export_worker_artifact.py、owned_export_worker.py、ExportPreparationWorkerProbe.java、verify-export-preparation-worker.py。

验证入口必须显式传 `--verify-owned-export-preparation-worker`，既有control/evidence存在时拒绝重跑。原始过程日志与控制记录保留在Git忽略的私有测试目录；不能删除控制记录后盲目重跑或把前后不同场景拼成一次成功。

证据见[实际进程与故障恢复](evidence/niko-export-preparation-worker-process-20261004.json)。原测试服务器未执行这组进程验证，HTTP导出/OSS/下载/通知仍未验，M3-7不是DONE。
