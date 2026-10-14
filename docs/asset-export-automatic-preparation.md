# 台账导出自动准备

已部署至[指定测试站](http://192.168.1.215:10010)，源码`1514842`。新增常驻准备Worker并更新Web，原API和其余10服务的镜像/启动时间/重启次数保持；13服务在关闭本机转发后仍运行且ready=true，V30/0迁移，production_ready=false。公开页面实际验证自动1行16列XLSX、进度停止轮询、390px布局、显式发布及4962字节下载摘要一致，既有监控仍200。见[部署与公开链路](evidence/export-queue-deployment-20261014.json)、[最终组件](evidence/export-queue-components-final-20261014.json)、[桌面](evidence/export-queue-ui-20261014/desktop-live-prepared.png)及[手机宽度下载](evidence/export-queue-ui-20261014/mobile-live-download.png)。

2026-10-14补齐M3-7的后台文件准备及页面进度。API受理后，独立常驻Worker读取指定测试库并保存实际XLSX；页面自动读取当前状态。发布、900秒签名下载仍分别确认，[本人导出消息](export-inbox.md)已另行补齐，M3-7正式验收保持NOT_RUN。

## 处理边界

- 默认不开启；精确入口为`--run-queue-without-publication`，独立开关`MOP_EXPORT_PREPARATION_QUEUE_ENABLED=true`。原单作业入口保留，不能同时启用两个角色。
- 只领取固定UTC启用时间之后、有匹配租户/创建人/作业ID及PREPARE_ONLY_NO_PUBLICATION模式的真实API耐久回执、尚无文件的请求。内部仓储测试作业及启用前历史请求不自动消费。
- 重启沿用原`MOP_EXPORT_PREPARATION_ACCEPTED_AFTER`，不得每次改成当前时间导致遗漏排队请求。该值不得晚于实际数据库时钟，也不应回调扩大历史范围。
- 复用现有PG行锁、租约、fence、当前成员授权和不可变请求。每次串行处理一个任务；有效租约不抢占，过期未准备任务可重新领取，已保存文件永不由队列重编码。
- 仅明确的行数/编码大小超限写入FAILED/INPUT_LIMIT并审计；网络、依赖、未知异常保留耐久租约和原请求，以有界退避重试，不伪造输入错误或成功。未改变已有1000行/16MiB技术边界及fence上限；不是批准后的容量或重试SLA。
- 启动和每次循环检查真实库/作业/来源结构；缺依赖启动失败或等待恢复，不启用内存仓储，不自动迁移。致命线程错误返回失败退出，交由容器重启策略处理。
- Worker仅有受限项目PG凭据，无迁移、MinIO或下载签名秘密，不调用发布、下载或通知。SQL V1—V30保持原字节。

## 页面行为

原202回执保持QUEUED/v0，另发GET读取当前作业；每2秒只读刷新尚未准备的QUEUED/RUNNING任务。隐藏页面、手动关闭自动刷新、读取失败、已准备或终态时暂停；原请求/发布结果未知时不自动重发写操作。切换租户与迟到响应沿用原上下文守卫。

文件版本更新时同步刷新发布区，避免上方已显示文件、下方仍显示未准备。准备完成不会自动下载，也不把明确0行与未知结果混淆。原准备租约未到期时，显式发布仍可能返回409；等租约结束后按页面提示操作，不抢占Worker。

## 验证

指定环境为lan-test-20261008，复用PostgreSQL17.9/V30、MinIO及已有Java镜像。相关Java按类去重67项通过（28真实PG、39隔离）；最终前端1886项、130契约、9静态发布门禁通过，类型和生产构建通过。隔离测试与真实组件测试分别计数，不折算合同完成率。

候选真实HTTP完成38次检查，自动准备1行、明确0行及接管后1行的三份16列XLSX，逐份回读ZIP与SHA256；启用前旧作业全行摘要不变，已生成文件和原受理回执不变。一个自有领取探针在领取提交后、编码前被强制中断（137），常驻队列以新fence=2接管。此场景不是编码事务中途宕机、MinIO断网、PG灾备或性能专项。

真实Edge页面提交一次后，无人工准备命令即可看到文件及当前发布状态；准备完成停止轮询。初次验证发现发布区状态滞后并修复，回归已覆盖。手机证据是浏览器窄视口，不代表物理手机/企微验收。见[脱敏验证](evidence/export-queue-validation-20261014.json)及[界面证据](evidence/export-queue-ui-20261014/desktop-automatic-prepared.png)。

## 运行与回退

配置示例为`deploy/export-queue.env.example`，私有值只写服务器项目`private/export-queue.env`及本机忽略配置。使用独立覆盖文件`deploy/lan-test/export-queue.compose.yaml`，`EXPORT_QUEUE_RELEASE_DIR`必须指向已校验摘要的不可变JAR目录，复用已固定`JAVA_IMAGE`。先`docker compose -f compose.yaml -f export-queue.compose.yaml config --quiet`，仅启动`export-preparation-worker`，不使用整组down/up。

启用后以`tools/inspect-lan-test-server.py --include-export-queue`检查13个选定服务；无此参数继续检查原12服务。容器运行不证明业务成功，仍需新建明确虚构的本人请求，核对实际准备及下载结果。回退仅停止该项目自动队列并恢复旧Web引用，保留所有请求、原文件、审计和私有启用时间；需要处理的既有任务可按原单作业守卫显式核对，不删除数据或退回fixture。

正式自动发布及外部渠道送达闭环、批准数据规模下性能及完整TC仍待。生产OSS不是当前MinIO联调的已验能力。
