# 联系人批量新建：专用运行入口

## 范围和前置条件

API0.32提供显式JSON CREATE_ONLY受理、创建者/租户专属作业分页与详情。一次1–100行为技术边界；全部行有效、完全相同行另行确认后，202只证明耐久排队。既有同租户联系人不会被合并或覆盖；Worker成功必须整批联系人、v1修订/结果、审计和执行幂等收据共同提交。通知、账号身份、CSV/XLSX、对象存储与客户业务规则不在本入口中。

必须先按当前根AGENTS.md和独立迁移流程核验V1–V19、受限mop_test_runtime、已有当前身份绑定与共同租户约束。V17、V18、V19分别有独立显式迁移，不会通过启动API/Worker自动补齐，不编辑冻结SQL。原指定服务器是否完成这些迁移须以该环境真实记录为准；niko通过不能代替原服务器通过。

## Windows本工程显式选项

在已具备本项目私有运行配置、固定SSH主机信任/现有组件访问、构建产物的工作区：

```powershell
pwsh -File deploy/start-dev.ps1 -SkipBuild -WithContactImportWorker
```

未传开关不启动联系人Worker，可独立于照片和资产导入Worker选择；不要仅打开API后就宣称后台已消费。脚本在读取就绪结果时要求contact_imports.schema_access_ready=true，再启动唯一contact-imports JAR，核验JarLauncher/专用Start-Class与本工程路径。三类产物的选择规则互斥，联系人JAR不会作为资产导入Worker候选。

联系人子进程只投影普通OS运行路径、已有SPRING_DATASOURCE_URL/USERNAME/PASSWORD三项受限PG设置、remote_test、自己的显式启用开关和30000毫秒租期。URL仅允许项目回环mop_test且无附加参数，拒绝高权限账号；不读取/继承迁移、OIDC、S3/OSS、其他Worker开关或JAVA_TOOL_OPTIONS等环境注入设置，不创建或保存新凭据。临时修改的父进程环境无论启动成功/失败都恢复。

每次联系人启动日志使用新的随机后缀，避免旧启动标记冒充本次就绪。专用进程先实际核验V19结构/访问，产生固定启动标记后脚本才报告通过启动门禁；旧日志、API ready和租约状态均不能证明持续在线。失败保持明确拒绝，没有fixture或存储回退。

启动记录包含contact-import-worker的PID、启动时间、Java与独立JAR完整路径。正常项目停止仍使用：

```powershell
pwsh -File deploy/stop-dev.ps1
```

整体身份核验通过才停止已登记进程，不从端口/进程名发现后杀死，不管理任何无关服务。联系人Worker没有资产解析器子进程，不应套用资产解析器识别。停止中未完成事务依靠PG回滚/租约fence恢复；此文不把Windows Stop-Process称作已实际验证的优雅停止。

## niko独立环境

自己的已批准隔离组件使用tools/niko-test/start-contact-worker.py；必须先准备经过路径/manifest/SHA核验的固定副本，并明确租期。此前正常执行、处理中仅杀已证明自有JVM、真实PG行锁等待、到期fence2重启恢复见niko-contact-import-worker-process-20261003.json。公开API388HTTP和实际前端模块72HTTP分别记录；这些不冒充Windows启动或浏览器DOM。

## 界面和回执

/contact-imports默认为空，不自动提交。显式只读预览后独立确认新建；结果未知时保持原body/key并禁止用新批次替换。403或普通GET成功均不证明未知原POST失败或已确认；只有原请求受理回执确认才解除未知。受理回执永远PENDING，当前结果另读；可显式每2秒GET进度，到终态/错误/上下文失效即停。

PENDING/QUEUED为待领取，PENDING/RETRY_WAIT为临时依赖失败后的技术退避，RUNNING为存在处理租约且在线仍未知，SUCCEEDED才有完整行结果。FAILED为不可重试原作业；先核对原因与已有作业，再明确准备新批次。成功结果链接固定v1，之后归档/修订不改原导入结果。

## 当前验证边界

- 产物角色修复7587fa8的CI37152864074已成功，Windows job111290142515实际执行21项隔离进程/角色测试，真实进程操作0
- 新增WithContactImportWorker选项及环境投影，新增6项隔离检查，总27项；c33cc11 CI37153152496及Windows job111290992448已回读成功，实际隔离检查27项/真实进程操作0，见niko-contact-worker-windows-ci-20261003.json
- 此Linux环境没有pwsh；本次没有执行原Windows机器的启动、SSH、迁移或停止
- API/Worker真实PG与HTTP证据、Vue内存处理器测试、浏览器真实DOM和通知送达分别记录，完整M2-9/128项未完成
