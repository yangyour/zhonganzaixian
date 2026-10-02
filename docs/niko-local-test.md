# niko 独立真实测试环境

## 授权与边界

2026-10-02用户明确允许在niko部署必要组件，并单独批准本机回环的PostgreSQL/Keycloak/MinIO专用测试账号和随机凭据；其后指定阿里云OSS作为存储方向。本文件记录新环境，不代表原指定服务器网络恢复，不沿用旧服务器的通过记录。`remote_test`仍是应用的真实组件适配器profile名称，环境证据必须另标`niko-local-test`；没有启用`local_fixture`。

原`deploy/private/`六文件、原服务器服务/网络/端口不变。新凭据、会话、数据库和日志只在Git忽略的`.local/niko-test/`，私有目录0700、凭据文件0600。任何新环境凭据均不得提交Git；代码不包含实际访问密钥。

## 实际组件

- PostgreSQL 17.11，Debian 13官方签名包仓库下载并解包到工作区，非系统服务安装；原服务器17.9结果不能合并。仅127.0.0.1:25432，SCRAM认证，migrator/runtime分离，运行身份无超级用户/建库/建角色/复制/bypassRLS权限
- Keycloak 26.2.4，Maven Central官方quarkus发行包，发行SHA1校验并另记SHA256；独立PG库mop_keycloak，只127.0.0.1:28110，管理接口也显式绑定回环。应用client关闭password grant和service account，启用S256 PKCE，6个合成测试身份
- API只127.0.0.1:8080；只注入runtime凭据，不注入migrator凭据；API/Worker启动不执行迁移
- MinIO按官方RELEASE.2025-09-07T16-13-09Z源码、已校验官方Go1.24.6编译成功，不是官方预编译包。启动前网络接口枚举因netlink权限EPERM失败，获准提升执行后同样失败，尚未运行对象存储或IAM测试。没有修改系统限制、补丁绕过或用内存存储宣称成功。后续存储按用户选定OSS另行实现/验证

## 同一会话运行

该云执行环境中不同命令会话的回环不可互通，Unix-domain socket也不可用。PostgreSQL使用受密码保护的TCP回环；组件与QA在同一受控会话启动。`tools/niko-test/run-stack.sh`只启动已准备好的PG、Keycloak和API，进入同一命名空间的QA shell；退出时按本次直接子进程归属检查，先停API/Keycloak，再正常停止PG，不按端口或进程名停止其他服务。

前置：仓库根目录、JDK17/Maven已构建工程；相邻`mop-toolchain/components/pg`为解包PG17目录，`keycloak-26.2.4`为官方发行目录；工具链和第三方二进制不进Git。首次准备不是`run-stack`的隐含动作：

1. 经账号/凭据创建批准后，显式运行`python tools/niko-test/prepare-private.py --create-approved-local-test-credentials`；已有配置拒绝再生成或轮换
2. 用该私有目录的pg-init-password显式initdb，`-U mop_niko_admin --auth=scram-sha-256 --encoding=UTF8 --locale=C`；启动受限回环PG后运行`bootstrap-postgres.py`
3. 启动Keycloak后运行`bootstrap-keycloak.py`，只操作带niko项目归属标记的realm；绑定过的用户消失或ID改变时拒绝同名继承
4. 从已构建API jar提取BOOT-INF/lib到`.local/niko-test/java-libs`供独立CLI使用；显式`run-migration.py --initialize-new-niko-database`只接受空的新niko库，应用冻结V1–V4；再分别`--apply-photos`、`--apply-asset-imports`，最后`--check`。manifest核对全部V1–V6源码和编译资源；不是修改旧迁移或重跑原服务器
5. `bind-local-identities.py`仅初次空业务新库，把冻结SQL的合成用户明确绑定新realm签发者+sub；记录本地绑定，后续不按用户名重绑定；不改变角色或原SQL
6. `bash tools/niko-test/run-stack.sh`供后续已有环境启动和正常停止；MinIO/OSS不被隐式启动或伪装就绪

这些入口只支持此次niko测试路径/端口，不是生产部署或任意环境安装器。下载来自[PostgreSQL Debian仓库](https://www.postgresql.org/download/linux/debian/)、[Keycloak官方版本](https://github.com/keycloak/keycloak/releases/tag/26.2.4)和[MinIO官方源版本](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z)。

## 本轮已验证

- 独立迁移执行4+1+1，后续检查及正常重启后检查均0迁移；V1–V6摘要不变
- persistence模块120项通过，其中69真实PG：基础5、修订6、报障8、照片事务24、配置诊断8、导入事务18；其余51隔离检查。照片/导入PG测试的注入存储替身只验证数据库事务，不证明对象存储链路
- 根Maven显式排除6项MinIO检查后：380总数、367执行通过、13历史fixture跳过，0失败/错误。367包含上述69，不重复累加；排除项是NOT_RUN，不是PASS
- 6个真实OIDC身份通过HTTP授权码/S256/nonce/state和HttpOnly应用会话检查。测试客户端只对这两个已授权loopback origin处理Keycloak的Secure cookie本地可信上下文，保留Secure标志；不据此宣称浏览器SameSite/渲染或真实手机通过
- 配置修订44个实际HTTP响应通过Ajv：内部审批五态、自审/伪造主体/CSRF拒绝、幂等、CAS、跨租户404、审批不生效，以及对象/嵌套数组1e999被400拒绝且不新增修订
- 本次拥有的PG/Keycloak/API正常停止再启动，8张项目表按排序行计算的SHA256与行数前后一致；恢复后重新OIDC/HTTP验证。不是崩溃、断网、MinIO故障或Worker耐久重试验收

HTTP脚本支持明确`MOP_TEST_SESSION_DIRECTORY`、`MOP_TEST_ENVIRONMENT_NAME`及`MOP_EVIDENCE_OUTPUT`。使用独立会话目录时必须指定独立证据路径，禁止覆盖历史原环境报告。`capture-http-sessions.py`只写本地私有会话，不输出密码、cookie、授权码或token。

证据：[环境汇总](evidence/niko-local-components-20261002.json)、[HTTP](evidence/niko-configuration-revision-http-20261002.json)、[正常重启](evidence/niko-graceful-restart-20261002.json)。最初测试运行因未给测试进程提供其明确要求的migrator变量而4个类初始化失败，补齐仅测试进程参数后69项全部通过；API进程仍不含迁移账号。此前独立命令无法互连和MinIO启动失败均保留，不包装为全部成功。

## 未完成

OSS连接/IAM/不可覆盖/大文件/照片及导入整链、浏览器UI、真实设备/企微、客户业务值、工作流其余功能和128项完整合同仍未完成。`OBJECT_STORAGE_PRESIGN_EXPIRES`不能直接改变平台授权grant或绕过租户检查；存储方向变更不代表权限或兼容性已通过。

## 独立客户报障验证

`capture-http-sessions.py`另输出4个`qa-ticket-<role>.json`私有别名。`customer-report-smoke.mjs`支持MOP_TEST_SESSION_DIRECTORY、MOP_TEST_ENVIRONMENT_NAME、MOP_TEST_POSTGRES_VERSION、MOP_TEST_KEYCLOAK_VERSION及MOP_EVIDENCE_OUTPUT；自定义环境必须显式全部信息且不能指向旧证据路径。当前脚本要求冻结V5照片表存在，未知附件404，不再沿用V4/Q28预期；照片上传未测试。分页只使用该次真实API创建的虚构报障，测试记录保留不自动删除。

正常重启验证：在同一组件命名空间运行`node tests/niko-report-restart-http.mjs before`；退出run-stack子shell使其仅停止所属组件；重新启动run-stack并重新capture会话，再运行同脚本`after`。脚本不自行重启或迁移、不覆盖既有checkpoint/重启证据，只比较该次已完成HTTP脚本留下的报障与operation；如需新一轮，先使用新的独立证据方案，不删除历史记录。该流程不能标为Worker故障或崩溃恢复通过。
