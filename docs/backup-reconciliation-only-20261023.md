# B01：只核对、不新上传的运行模式

## 运行入口及边界

新增 `--backup-reconcile-only-check` 与 `--backup-reconcile-only`，均只允许单个明确参数，未知或混合备份参数拒绝，不落入照片Worker或完整后处理。前者只核对现有依赖，后者只核对已开始/结果不明的原归档派发。

模式必须同时满足私有配置 `MOP_BACKUP_WORKER_ENABLED=true` 和 `MOP_BACKUP_RECONCILE_ONLY_ENABLED=true`；默认禁用。只核对标志为true时，误选旧完整后处理入口会拒绝，不自动放宽为可上传。开关不会产生授权。私有grant只要求现有 DISCOVER、ARCHIVE_READ、ARCHIVE_RECONCILE、CRYPTO_VERIFY，不要求或创建 ARCHIVE_START、VERSION_REGISTER。所有现有身份、租户/资产、权限版本、有效期、绑定及受限数据库真实权限检查保留。

配置参考 `deploy/examples/backup-reconciliation.env.example` 全部依赖留空/禁用；只复用指定现有 PostgreSQL/OSS/KMS/签名采集证明，不自动填供应商、凭据或客户批准。

## 实际调用链

WorkerApplication → BackupWorkerMain → 只核对调度装配 → 持久任务发现 → 原派发命令重建 → BackupArchiveDispatch.reconcileOnly → 原对象/AEAD及签名来源核对 → 原归档日志 CAS。

- 调度仅处理 STORE_STARTED / VERIFY_REQUIRED，STAGED 与 VERIFIED 记入 `skippedByMode`，不读取其采集证明、不登记版本、不完成运行，也不会把跳过计成归档成功。
- 发现与消费之间状态变回 STAGED 时，消费者重新读取原日志后只返回 RECONCILIATION_REQUIRED，绝不开始派发或 PUT。
- 只核对装配没有上传器或版本登记消费者，归档仓储授权仅允许 READ / MARK_UNCONFIRMED / VERIFY_OBJECT。即便错误调用旧 `once`，START_STORE 也先拒绝。
- 存储端另包只读对象端口，storeImmutable 在到达提供方之前拒绝；外层归档授权同样拒绝 STORE。不是靠隐藏按钮或调用方约定避免上传。
- 原提交、派发ID/owner、租户/资产/运行/版本、证据摘要以及幂等命令保持；对象缺失、来源证明缺失、读失败或回执不明都不能证明未派发。绝不调用 CONFIRM_NO_DISPATCH，不重加密、不重传、不删除。
- 允许按原工作流追加“仍待核对”或“对象已核验”的归档日志，因此此模式不是“整个数据库只读”。它不代表备份已登记、运行已完成、保留/恢复已验证或当前对象永久健康。
- 逐设备有界游标和取消继续复用，跳过不会卡住后续设备；没有新增后台自动启动、迁移或调度服务。

## 验证与未完成

证据见 `docs/evidence/dot-continuation-20261023/b01-reconciliation-only.json`。测试验证旧模式兼容、只核对授权集合、错误入口拒绝、跳过范围、状态竞争、无PUT、旧回执恢复与读取期间撤权，测试替身不冒充真实OSS/KMS。

本轮未启动真实备份Worker，未连接指定服务器或对象存储；私有配置/可信实际采集证明/获批KMS产品适配及端到端验证仍缺。B01仍IN_PROGRESS，不核销真实采集、KMS产品实现、完整页面与正式验收。
