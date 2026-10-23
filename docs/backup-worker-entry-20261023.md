# B01：显式后处理 Worker 与签名采集证明

接续任务发现和调度核心，新增真实 Worker 启动分支 `--backup-postprocess` 与依赖检查分支 `--backup-postprocess-check`。只允许一个明确分支；未知 `--backup-*` 或混合参数拒绝，不落入照片 Worker。没有启动参数时仍沿用原应用行为。本轮未启动任何备份处理或访问真实对象。

## 装配和运行配置

需要 `SPRING_PROFILES_ACTIVE=remote_test`、受限 `mop_test_runtime`、经SSH隧道的回环 `mop_test` JDBC地址，以及私有数据库口令。`MOP_BACKUP_WORKER_ENABLED=true` 必须显式提供，默认缺失即拒绝。

技术配置包括 `MOP_BACKUP_TASK_BINDING`、`MOP_BACKUP_WORKER_ACTOR`、`MOP_BACKUP_WORKER_TENANTS`（规范UUID逗号列表）、`MOP_BACKUP_WORKER_PAGE_SIZE`、`MOP_BACKUP_WORKER_POLL_MILLIS`。目标来自私有授权文件中同binding/actor/tenant且未过期、同时具备 DISCOVER、ARCHIVE_READ、ARCHIVE_START、ARCHIVE_RECONCILE、VERSION_READ、VERSION_REGISTER、CRYPTO_VERIFY 的设备项；没有完整项不能启动。启动逐项复核当前身份和全部操作授权，每次处理仍读取当前授权，启动快照不替代后续撤权检查。

复用原 `OssProjectStore.backups()` 私有、不可覆盖存储通道。`MOP_BACKUP_CONTENT_ENABLED=true` 与既有明确 OSS 配置是独立条件。KMS须提供 `MOP_BACKUP_KMS_PROVIDER` 精确ID及 `MOP_BACKUP_PLAINTEXT_LIMIT_BYTES` 技术上限。`BackupKmsProvider` 通过Java ServiceLoader加载经过审查的产品适配器；应用不包含假KMS或明文密钥文件后备。零个/多个同名适配器、实际ready失败均拒绝。Q28未定时没有预装供应商实现，不能把SPI存在记成KMS接入完成。

外部KMS适配Jar需随审核后的运行包配置到Spring Boot PropertiesLauncher的加载路径；不得从请求参数或下载URL加载代码。可用 `java -Dloader.path=<受控适配器目录> -cp <worker.jar> org.springframework.boot.loader.launch.PropertiesLauncher --backup-postprocess-check` 进行显式依赖检查；实际路径和凭据只写受控运行配置。检查模式只验证依赖，不派发归档或版本登记。

启动依次核对当前授权、原日志表、证明信任配置、KMS实际就绪、OSS实际私有存储前提；依赖不全返回CONFIG_PENDING，错误不输出私有路径/口令。`CRYPTO_VERIFY` 与归档/版本授权分开，用于已有密文的AEAD核验，不授权对外下载。服务停止会阻止新任务派发、保留未完成页游标，并关闭本实例资源；不停止其他服务。

## 受信任采集清单

`MOP_BACKUP_COLLECTION_PROOFS_DIRECTORY` 指向采集器已经保存的不可变清单目录；`MOP_BACKUP_COLLECTOR_PUBLIC_KEY` 为受控DER X.509 Ed25519公钥，`MOP_BACKUP_COLLECTOR_BINDING` 为明确 `backup-collector:` 信任标识。清单固定名称为原版本UUID加 `.json`，不接受浏览器路径或URL。公钥轮换需重启本Worker；即时停止授权通过当前grant撤销。

每份外层JSON仅有 `payload` 与 `signature` 两个标准Base64字段；签名覆盖原payload字节，不根据调用者给出的公钥验证。payload严格包含：schema_version=1、source_kind=DEVICE_COLLECTION、collector_binding、tenant_id、asset_id、run_id、attempt_number、version_id、run_version、envelope_sha256、envelope_bytes、lease_request_id、lease_owner、fencing_token、collected_at、expires_at。时间为毫秒Instant，采集时间不在未来，证明必须未过期。未知字段、错误签名、错范围/租约、假来源类别、超限或缺失文件均拒绝。

证据引用为签名payload的SHA256。真实采集器必须先完成设备读取及自己的执行约束，再签发对应原运行/信封清单；本Worker没有签发接口、私钥或生成“采集成功”的代码。签名说明配置的采集器做出该声明，不替代客户生产批准、厂商采集验证或正式TC。

清单在SQL事务外读入；256项有界缓存只持有原签名字节。登记事务内重新验签和核对原运行、租约与时间，没有网络/文件/对象访问。缓存不是允许标志，也不接受单元测试的内存证明回调；进程重启重新从耐久清单核对。文件应保留原始内容供回溯，替换不匹配证明不能覆盖旧事件；撤除文件使下一轮读取失败。

实际对象核对仍由原OSS读取和JCE AEAD执行，签名不能替代对象存在/完整性检查。该证明永远不能调用 CONFIRM_NO_DISPATCH，不能因为证据缺失就重传对象或猜测设备未执行。

## 本轮边界

单元测试使用临时合成签名密钥和文件验证协议/错签名/过期/错范围/原命令/取消，不是实际采集证据。打包CLI只验证缺配置拒绝，不启用处理。真实可信采集器清单、获批KMS适配器和真实存储端到端测试仍缺，记录为未就绪。B01下载/解密/差异与B02业务治理继续推进，B01整项不标VERIFIED。

## 追加：受限只核对入口

`--backup-reconcile-only` / `--backup-reconcile-only-check` 已新增，使用显式 `MOP_BACKUP_RECONCILE_ONLY_ENABLED` 和缩减操作集合，STAGED不上传、VERIFIED不登记。对象写端口与仓储写动作均独立限制；只允许原核验日志追加，不宣称数据库整体只读。详见[模式说明](backup-reconciliation-only-20261023.md)；未启动真实处理。
