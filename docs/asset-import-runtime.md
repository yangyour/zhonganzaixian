# 资产导入暂存与独立 Worker 运行说明

本能力处理原始 CSV/XLSX 源文件的耐久暂存、解析产物和显式列映射。它不写入业务资产、不预留配额、不执行业务预检，也不填写 Q20/Q31 尚未确认的字典、冲突域或日期规则。返回 `business_status=CONFIG_PENDING`、`can_import=false`；映射的 `precheck_status=NOT_RUN`。扫描、正式生产批准和保留期限分别为 `UNSCANNED`、`UNVERIFIED`、`null`。

## 指定测试组件与秘密引用

复用指定测试环境的 PostgreSQL 与 MinIO，经本项目 SSH 隧道访问。本机不启动替代数据库、缓存或存储，不在连接失败时改用 fixture。

|用途|固定边界与配置引用|
|---|---|
|运行数据库|`mop_test`，账号 `mop_test_runtime`；`SPRING_DATASOURCE_URL` 为 `jdbc:postgresql://127.0.0.1:<隧道端口>/mop_test`，不允许附加连接选项；用户名、密码分别引用 `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。|
|独立迁移|账号 `mop_test_migrator`；只由独立 CLI 读取 `MOP_MIGRATION_USERNAME`、`MOP_MIGRATION_PASSWORD`。API、Worker 和解析子进程不获得迁移秘密。|
|导入存储|端点 `http://127.0.0.1:19000`，独立 bucket `mop-test-asset-imports`；键为 `asset-import-sources/<tenant UUID>/<source UUID>`，不使用照片 bucket、账号或对象前缀。|
|导入存储秘密|`MOP_IMPORT_S3_ENDPOINT`、`MOP_IMPORT_S3_BUCKET`、`MOP_IMPORT_S3_ACCESS_KEY`、`MOP_IMPORT_S3_SECRET_KEY`，由 Git 忽略的 `deploy/private/runtime.json` 或受控环境提供。|
|API 技术格式策略|显式 `MOP_IMPORT_ALLOWED_FORMATS=CSV,XLSX`。这只是测试开发的格式门禁，不代表客户生产文件策略已经批准。|
|解析器产物|显式 `MOP_IMPORT_PARSER_JAR`，必须是本工程 `server/import-parser/target/` 下实际存在的绝对 JAR 路径。无默认路径或其他工程的回退。|

原始连接文件和凭据只保存在 `deploy/private/`。源码、提交、公开文档和日志不得包含秘密值、原始文件正文或含密连接串。

## 独立 V6 迁移

API 与 Worker 启动均不执行 Flyway 迁移。先完成根工程构建，再准备独立 CLI 的 classpath：

```powershell
pwsh -File deploy/prepare-migration-classpath.ps1
pwsh -File deploy/migrate-test-schema.ps1
```

无 Apply 开关时调用 `--check`，数据库连接强制只读。它校验恰好六个已知 SQL 的身份与历史；V1–V5 的已应用校验和不匹配、缺失或失败都拒绝继续。已登记应用进程或可识别的未登记 MOP API/Worker 仍在运行时，也拒绝迁移。

V6 必须独立、显式执行，不能与照片迁移开关组合：

```powershell
pwsh -File deploy/migrate-test-schema.ps1 -ApplyAssetImports
```

该开关只允许在 V1–V5 已成功并校验一致后执行 V6。`-ApplyPhotos` 仍只执行 V5，不连带执行 V6。两个 Apply 模式共享项目迁移互斥锁；未知、重复、越序或缺失版本不能被忽略。CLI 与 PowerShell 包装器都核验冻结的 V6 SHA256：

```text
1F532822668274B7AEBD12EA78B57E6BE0AA9FD53CEB77023EE8067589CD2B4B
```

V6 的实际结构检查包含五张表的完整类型与空值属性、迁移账号所有权、主键/唯一约束/复合外键、延迟 store-operation 外键、三个 stored 生成列、已验证 CHECK、索引，以及运行账号的逐列权限。原始字节由生成的大小与 SHA256 绑定到 source，并绑定其唯一 `STORE_RAW` 操作。解析与映射指针绑定同源、同操作/产物身份。运行账号只有明确的可变状态列 UPDATE 权限，没有 DELETE/TRUNCATE、表级 UPDATE、持久 schema/database CREATE 或迁移角色成员权限；原始数据、解析产物和映射历史不可改写。

2026-10-02 已在指定环境取得以下结果，记录只含固定安全摘要：

|记录|结果|
|---|---|
|`.local/asset-import-migration-apply.log`|`APPLY_ASSET_IMPORTS`，`migrations_executed=1`，V1–V5 校验和与照片、导入结构 ACL 检查通过。|
|`.local/asset-import-migration-repeat.log`|重复显式 Apply，`migrations_executed=0`，结构 ACL 再次通过。|
|`.local/asset-import-migration-check-after.log`|默认只读检查，`migrations_executed=0`，结构 ACL 通过。|

V1–V5 的已应用字节保持不变。V6 文件保留冻结审阅时的 `UNEXECUTED` 注释以维持已应用校验和；当前应用状态以 Flyway 历史和实际结构 ACL 检查为准，不能为了修改注释改写 SQL 或执行 repair。这些迁移记录不代表完整资产导入业务、MinIO/解析器联调或生产审批完成。

## 可选独立导入 Worker

```powershell
pwsh -File deploy/start-dev.ps1 -WithAssetImportWorker
```

照片与导入 Worker 可以分别选择。导入入口是 `cn.mop.importworker.AssetImportWorkerApplication`，独立启动 `server/worker/target/mop-worker-*-imports.jar`；照片模式的 JAR 搜索排除 `*-imports.jar`。脚本核验 imports JAR 的 Boot 入口及 `Start-Class`，也核验指定解析器的 `Main-Class=cn.mop.importparser.ParserMain` 和入口类。路径必须属于当前工程且不含重解析链接；Worker 的工作目录固定为项目根。

导入子进程只继承普通 OS 运行路径、三个 runtime 数据库设置、`SPRING_PROFILES_ACTIVE=remote_test`、自己的导入存储设置、显式解析器路径和 `MOP_IMPORT_WORKER_ENABLED=true`。它没有照片、OIDC、迁移或其他环境中的应用/云秘密。解析器由 Worker 以独立 JVM 启动，复制精确原始字节到 `.local/import-parser-runs/<UUID>/`，子进程环境只含所需 OS/独立临时目录设置；解析器没有数据库或对象存储凭据。

Worker 在启动时实际检查 V6/运行账号 ACL、导入 bucket 和解析器。解析器探测运行真实无凭据子进程，要求无敏感内容的空表头输入返回协议错误 `HEADER_MISSING`。只有门禁通过才记录启动标记：

```text
Asset import worker started for project import jobs
```

这个标记证明启动门禁通过，不证明 Worker 持续活动或业务导入完成。API 的 `data.asset_imports` 检查技术格式策略、`schema_ready` 和 `storage_ready`；已配置但未就绪时全局就绪接口返回 503 和明确原因。API 对 Worker 活动仍返回 `worker_activity=NOT_PROBED`，不根据启动请求或旧日志伪称活动。

Worker 只消费本项目导入任务，不消费照片、工单或其他 outbox。PostgreSQL 时钟决定 180 秒租约和 fencing token；原始源存储完成后再次读取并验证大小/SHA256，才标记 READY。解析前与提交结果时复核创建者的当前成员关系、权限版本和 `assets:write` 能力。旧 token、到期租约或已观察到的线程中断不能提交成功状态或成功审计。组件暂不可用时，同一耐久任务保持 `PENDING/RETRY_WAIT`，退避上限 300 秒，恢复后自然重试；撤权、摘要冲突或确定的格式/资源拒绝进入失败终态。

## 停止与进程归属

```powershell
pwsh -File deploy/stop-dev.ps1
```

启动记录中的 `asset-import-worker` 保存 PID、启动时间、可执行路径、imports JAR 与解析器 JAR。停止前先整体核验全部已登记进程，并在已证明父进程存活时收集解析子进程候选。每个候选核验父 PID/启动时间、自身 PID/启动时间、同一 Java 可执行文件、确切解析器 JAR 和工程内 UUID run 的 tmp 参数；完整候选先保存到 `parser_children`。

先停止导入父进程以阻止新任务/子进程，再核验并停止事前已证明的解析子进程。身份不明确、PID 被复用、路径变化或停止竞态中新出现的未证明子进程，都保留启动记录并拒绝继续；不按进程名扫停。父进程已经退出时，只有之前保存且仍通过自身身份核验的候选可以继续处理。

可维护的隔离验证入口：

```powershell
pwsh -File tools/test-dev-process-ownership.ps1
```

该脚本仅以本地 fake `Get-Process`/`Get-CimInstance` 调用纯归属 helper，当前 12 项检查通过。它覆盖角色/工程目录、照片与导入 classifier、精确 `-jar` 参数、PID/启动时间复用、解析父子关系、未知 run/子进程，以及父进程退出后的保存候选恢复。它不启动、不停止或盘点实际进程，不能作为实际停服证据。
