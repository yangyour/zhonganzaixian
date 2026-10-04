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

## 2026-10-04 Windows独立入口与原指定PG实测

新增deploy/prepare-asset-export.ps1：必须明确单个规范JobId、WorkerId及1000–600000ms租期。只从忽略的runtime投影受限PG/必要OS环境，Java子进程没有迁移、OIDC、存储或其他Worker秘密。逐次复制为本工程独立GUID目录内的SHA256命名只读JAR，核验入口、依赖及字节；API/Worker仍不自动迁移。仅准备文件，未实现公开导出受理/OSS/签名下载/站内信。

启动采用Node文件句柄、windowsHide与独立子进程；控制工具收到已发出的原进程记录后即继续，不再等重定向输出EOF。终止前核验当前用户SID、PID/父PID/启动时间、完整Java命令、目标job和固定JAR hash。12项隔离环境/归属/错误角色及参数守卫通过，已接Windows CI，实际行为另由真实JVM验证。

原指定PG17.9上验证正常16列表格准备、源备注修改后的原文件/hash保留、已提交领取后的Windows强制终止及新进程接管四个步骤。实际共14次Worker尝试，早期环境大小写、UTF-8读取、输出EOF等待以及audit锁调度失败均留在私有控制/日志；没有抹去失败记录或重新创建原作业。最终故障点为项目应用停止时，在自有连接短暂锁住本项目测试库mop_asset源表，确认原job领取已提交后仅终止已核验JVM。fence4保留且无prepared文件，租约过期新进程fence5生成并回读真实XLSX。所有临时锁和Worker已结束，共享组件不重启、不改权限/端口。

这不是prepared字节事务写入中途被杀、PG/WAL灾备或完整发布验收。正常/保留步骤来自前次有效运行；七张身份/其他消费者表等值只对最终恢复窗口核验，不冒充14次尝试的整窗等值。原受理收据保持不变，发布/OSS/下载/通知全部0。见[Windows真实证据](evidence/local-export-preparation-worker-20261004.json)。

可复现的单作业入口（JobId必须来自自己已授权的真实请求，以下为参数说明）：

```powershell
./deploy/prepare-asset-export.ps1 -JobId <canonical-job-uuid> -WorkerId designated-manual -LeaseMillis 30000
```

强杀验证仅用tools/verify-designated-export-worker.py的显式模式，既有证据存在时拒绝重跑；保留原控制。原项目其他应用须停止，测试锁只属于mop_test，不操作其他项目或共享服务生命周期。

## 2026-10-04 页面受理→单个真实Worker→同作业页面回读

新增独立链路证明，使用新的明确合成空结果筛选，不复用/覆盖此前页面或四JVM故障测试控制。Vue/native jsdom页面经真实API受理，原202受控丢失后按同key/体恢复；随后只启动这一job的拥有者守卫Java进程。严格投影原niko PG运行配置，不向Worker传入OIDC、存储或其他角色配置；不调claimNext、不排空队列、不发布或下载。实际Worker正常exit0，SHA73eac974cc86e80c7a371d1998a193094bc0f499ee5880c40803e6913d99526e，fence1/version2，生成4817字节、16列表头/0数据行XLSX。这个新批次只覆盖明确空结果，不代替非空数据或OSS验收。

首轮组件流程在Worker成功后，校验脚本用严格Base64解码直接读取PG的换行base64而失败；实际有84个LF，库内准备已完成。保留首轮failed证据、原控制、原Worker日志和初始脚本，不重新创建任务、不重跑Worker、不清除原失败标记。修正只移除PG的LF后再严格解码，独立只读恢复验证原4817字节与数据库/Worker SHA一致，ZIP完整且16个唯一表头含维保状态、元数据ENCODED_NOT_PUBLISHED。

随后新的Vue组件实例通过原作业列表入口读到RUNNING/v2/attempt1与明确0行准备描述，未出现下载入口；另以完全相同原key/体回读202仍QUEUED/v0。两阶段合计14HTTP，3次原key写派发仍只有1受理；最终1job/1attempt/1准备文件/0对象回执，accept/claim/prepare各1审计、原收据1且保持v0，存活的该Worker数据库连接0。续验只读和幂等回放窗口审计不变。不是同一组件实例无中断全程成功，也不是物理故障恢复；真实浏览器/CSS/手机/企微仍未验。

新增9纯目标/运行配置/PG Base64守卫测试，全145Python、116契约和最终类型检查通过。未改生产Java/Web源码、API/V25或重启后端。新工具的正常入口--prepare-accepted-page-proof只接受新确认的合成页控制；--verify-existing-page-proof只读取已保留exit0且解码失败的原作业，不能启动Worker，输出独占写。完成的原控制/恢复文件不可盲目重跑。见[分段链路证据](evidence/niko-export-page-worker-chain-20261004.json)、[首轮未完成](evidence/niko-export-page-e2e-http-20261004.json)及[同作业续验](evidence/niko-export-page-e2e-continuation-20261004.json)。
